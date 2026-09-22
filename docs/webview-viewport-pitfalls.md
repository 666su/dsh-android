# WebView 缩放与布局视口的踩坑记录

一次真实的排查过程：**桌面版式 Web 应用塞进手机窄屏后，内容小到几乎不可读、元素错位**。
从"靠推算改参数"到"靠实测数据定位根因"，中间错了两次，记下来避免重犯。

结论在最后，急着看就直接跳过去。

---

## 1. 背景

一个桌面版式的 Web 应用（侧边栏 + 正文分栏），要在手机全屏使用。
手机屏宽只有 1000~1400 物理像素，而桌面版式需要 ~1280 CSS px 才不挤。

思路：**把布局视口锁在目标宽度（1280 CSS px），再整体缩放到屏宽**。
这需要同时控制两个量：

- 布局视口宽度（页面按多宽排版）
- 视觉缩放（内容在屏幕上多大）

WebView 里看起来现成的 API 是 `WebView.setInitialScale(percent)`。

### 用到的实机

| 项 | 值 |
|---|---|
| 机型 | iQOO Z9（6.78" AMOLED） |
| 物理分辨率 | 2800 × 1260，20:9 |
| 系统 | OriginOS 6（Android 15） |
| `densityDpi` | 490 |
| `fontScale` | 0.80 |

> 注意：`densityDpi` **不是** Android 默认的 420。厂商按「显示大小」改写过它。
> 这一点后面很关键。

---

## 2. 第一次错：以为 `densityDpi` 可以由屏幕尺寸推算

### 推算过程

```
6.78 英寸、20:9 比例
→ 屏宽 = 6.78 × 20 / √(20² + 9²) = 6.18 英寸
→ 1260 px / 6.18 inch ≈ 204 ppi（物理像素密度）
```

然后按 Android 的 density bucket 规则，猜系统取 420dpi（基准 `density = 2.625`），于是：

```
1260 × 160 / 420 = 480 CSS px   ← 以为的「设备CSS宽度」
480 / 1280 = 37.5%              ← 以为的缩放
```

### 为什么错

**`densityDpi` 是厂商/系统设定的，不能从屏幕尺寸推算。**
它取决于出厂固件、以及用户在「设置 → 显示 → 显示大小」里选的档位。

实机 `densityDpi = 490`（不是 420），`fontScale = 0.80`（字体被调小过）。
推算值与真值差了 17%，而缩放百分比又是在此基础上二次推算的，误差被放大。

### 教训

> **不要用推算值当缩放基准。** 设备相关的量（`densityDpi`、`fontScale`、insets）
> 一律读系统真实值，或者直接测。

不过这次错误的**副产物是有用的**：为了避免"用户改一下系统显示大小，页面尺度就漂移"，
把缩放基准**固定写死**成了一个常量（不读 `DisplayMetrics.density`）。
这个思路后来被保留下来了，只是基准值要换。

---

## 3. 第二次错：把 `setUseWideViewPort(false)` 当成"精确锁定"

### 当时的实现

```java
s.setUseWideViewPort(false);       // 忽略页面自带的 width=device-width
s.setLoadWithOverviewMode(false);  // 不让 overview 插手
web.setInitialScale(computeScalePercent());   // 期望锁住布局视口
```

推理是：`setUseWideViewPort(false)` 让 WebView 忽略页面的 viewport meta，
然后 `setInitialScale` 精确控制缩放。代码注释里还写着"这套算术锁定了两个量"。

### 实机表现

- 内容**极小**，正文几乎不可辨
- 弹窗在屏幕正中、很小；侧边栏跑到很远的右上角，两者像"不在一条水平线上"

### 当时的误判

第一反应是"**布局视口和视觉视口脱钩**了，`position: fixed` 元素相对视觉视口定位，
所以和流内内容错位"。这个假设听起来很合理，但**是错的**。

---

## 4. 关键一步：不要再猜，做只读诊断面板

与其继续推理，不如让应用自己把真实数字报出来。加了一个**只读**探针：
页面加载完成后注入 JS，读一组视口指标，显示在设置面板里。

```java
// onPageFinished 里
web.evaluateJavascript(diagJs(), value -> diagText = decodeDiag(value));
```

```javascript
(function () {
  var vv = window.visualViewport;
  var m = document.querySelector('meta[name=viewport]');
  return JSON.stringify({
    iw: window.innerWidth,                                  // 布局视口宽
    ih: window.innerHeight,
    cw: document.documentElement.clientWidth,
    sw: document.documentElement.scrollWidth,               // 有横向溢出时 > cw
    vvw: vv ? Math.round(vv.width) : -1,                    // 视觉视口宽
    vvh: vv ? Math.round(vv.height) : -1,
    vvs: vv ? Math.round(vv.scale * 100) / 100 : -1,
    dpr: Math.round(window.devicePixelRatio * 100) / 100,
    vp: m ? m.getAttribute('content') : '(无meta)'
  });
})()
```

面板里同时显示 Java 侧的实测值（`densityDpi`、`fontScale`、insets），
这样**两边的数字可以对照**，不用再靠推算。

### 实机回传的数据

```
布局视口  innerWidth            3315 × 6657
文档      clientWidth           3315 / scrollWidth 3315
视觉视口  visualViewport        3316 × 6657
设备像素比 dpr                  3.06
WebView   scale                 0.38
物理分辨率                      1260 × 2800 px
densityDpi / fontScale          490 / 0.80
```

### 这些数字说明了什么

先做一个除法：

```
1260 (物理宽) ÷ 0.38 (scale) = 3315.8   ← 正好是 innerWidth
3315 (innerWidth) × 0.38     = 1259.7   ← 正好是屏宽
```

**两个方向都严丝合缝。** 这不是巧合：

> `setInitialScale(p)` 里的 `p` 被 WebView 当成**初始缩放因子**，
> 用它去**反推布局视口宽度**：`布局视口 = 物理宽 ÷ scale`。

于是实际发生的是：

```
setInitialScale(38)
→ 布局视口 = 1260 / 0.38 = 3315 CSS px      （期望 1280）
→ 页面在 3315px 的超宽视口上按桌面版式排版
→ 再整体缩到屏宽
→ 1 个 CSS px 只占 0.38 个物理像素
→ 13px 正文 ≈ 5 物理 px ≈ 0.033 cm  ← 肉眼几乎不可辨
```

顺带纠正之前的误判：`visualViewport 3316 ≈ innerWidth 3315`，
**两个视口其实是一致的**，"脱钩"的假设不成立。

### 错位的真实原因

不是坐标系分裂，而是**在 3315px 的超宽视口里按桌面版式排版**：
固定的居中弹窗、靠右的流内侧边栏，在超宽画布上被拉开到屏幕两端，
视觉上就成了"一个在正中、一个在右上角"。

**同一个根因，两种表现。**

---

## 5. 修复

三处改动：

### (1) 打开 `setUseWideViewPort`

```java
// 必须为 true：允许布局视口宽于设备宽度，
// setInitialScale 才会把布局视口锁在目标宽度上。
s.setUseWideViewPort(true);
s.setLoadWithOverviewMode(false);   // 不让 overview 再插手缩放
web.setInitialScale(computeScalePercent());
```

设成 `false` 时，布局视口会被放大成 `物理宽 ÷ scale`——就是上面 3315 的来源。

### (2) 缩放基准改用**物理像素**

```java
private static final float BASELINE_DENSITY_DPI = 160f;   // 即直接用物理像素

private int computeScalePercentFor(int target) {
    float w = getResources().getDisplayMetrics().widthPixels;   // 物理宽
    int pct = Math.round(100f * w / target);
    return Math.max(20, Math.min(200, pct));
}
```

```
1260 / 1280 = 98%  →  布局视口 1280 CSS px，视觉缩放 0.98，正好铺满屏宽
```

只依赖物理分辨率，**与系统「显示大小 / 字体大小」无关**，尺度恒定——
这一步保留了第 2 节那个"固定基准"的正确思路。

### (3) 诊断量用系统真实 density 折算

面板里除缩放百分比外的所有读数（`densityDpi`、`fontScale`、insets 的 dp / cm）
一律用 `DisplayMetrics` 的真实值折算，**不跟着尺度基准走**。

否则换了尺度基准，诊断数字会一起失真，下次又得靠猜。
**诊断本身必须独立可信。**

---

## 6. 验证

修完后设置面板应显示：

```
物理宽 1260px，目标版式 1280px 时 自动缩放 98%

视口诊断（只读）
布局视口 innerWidth 1280 × ~2744     ← 关键是从 3315 变成 ~1280
视觉视口 visualViewport 411 × ~881
视觉缩放 WebView scale 0.98
```

判读要点：

| 指标 | 正常 | 异常含义 |
|---|---|---|
| `innerWidth` | ≈ 目标版式宽度 | 数倍于目标值 = 布局视口失控 |
| `clientWidth` vs `scrollWidth` | 相等 | `scrollWidth > clientWidth` = 横向溢出，缩放过大 |
| `visualViewport.width` | 约等于 `物理宽 / dpr` | — |

---

## 7. 可复用的结论

1. **`setInitialScale(p)` 的 `p` 是"用布局视口宽度换来的"**：`布局视口 = 物理宽 ÷ (p/100)`。
   它不是单纯的缩放系数。要锁定布局视口，必须同时开 `setUseWideViewPort(true)`。
2. **`setUseWideViewPort(false)` + `setInitialScale` 是危险组合**：
   布局视口会被放大成 `物理宽 ÷ scale`，页面在超宽视口上排版后缩到屏幕，
   结果是内容极小 + 固定定位元素与流内元素被拉开。
3. **设备相关参数不要推算**（`densityDpi`、`fontScale`、insets）——
   厂商会按「显示大小」改写，出厂值也可能不是 Android 默认值。
4. **卡住时先加只读诊断，别再推理。**
   这组数据 `3315 = 1260 / 0.38` 一旦看到，根因是自明的；
   而在此之前的两轮推理都是错的。
5. **诊断输出要独立于被测逻辑**：诊断折算用系统真实值，
   否则改了基准后诊断也跟着失真，就失去了裁判作用。
6. **用了 deprecated API 就要实测**。`setUseWideViewPort` / `setLoadWithOverviewMode` /
   `setInitialScale` 全是 deprecated，官方文档含糊，不同 WebView 版本行为不一致——
   只能测。

## 附：为什么用 160 作为基准

Android 的 `dp` 定义是 `1 dp = 1/160 英寸`，所以 `dpi = 160` 时 `density = 1.0`，
`widthPixels` 就是尺度单位本身。写 `BASELINE_DENSITY_DPI = 160f` 只是把
"直接用物理像素当基准"这件事显式化，让公式形态和其他密度基准保持一致。
