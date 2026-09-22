# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.5.2] - 2026-09-22

### 修复

- **修复布局视口失控导致内容极小、元素错位**（核心问题）
  - `setInitialScale(38)` 被 WebView 当作初始缩放因子去反推布局视口：
    `1260 物理px ÷ 0.38 = 3315 CSS px`。页面于是在 3315px 的超宽视口上按桌面版式排版，
    再整体缩到屏宽 —— 1 个 CSS px 仅占 0.38 物理像素，13px 正文 ≈ 0.033 cm，几乎不可辨。
  - 同时这也是"弹窗在屏幕正中、侧边栏跑到右上角"的原因：超宽视口下，
    固定居中元素与流内右对齐元素被拉开到屏幕两端。**同一根因，两种表现。**
  - 修法：`setUseWideViewPort(false)` → `true`，让 `setInitialScale` 真正锁住布局视口。
  - 实机验证：`innerWidth` 从 **3315 → 1280**。

- **缩放基准改用物理像素**（`BASELINE_DENSITY_DPI`：420 → 160）
  - 缩放百分比 = `100 × 物理宽 / 目标版式宽`，iQOO Z9 上为 `1260 / 1280 = 98%`。
  - 只依赖物理分辨率，**不随系统「显示大小 / 字体大小」漂移**。

- **诊断读数改用系统真实 `density` 折算**
  - `dpi` / `fontScale` / insets 的 dp 与 cm 换算不再跟随尺度基准，
    避免改了基准后诊断数字一起失真。诊断必须独立可信。

### 新增

- 设置面板新增「**视口诊断（只读）**」：显示布局视口、视觉视口、视觉缩放、
  `dpr`、页面 viewport meta，附「重新读取（不关闭本面板）」按钮。
  排查版式问题不用再靠推算。
- 「恢复默认」按钮改为**不关闭面板**，方便接着看诊断数字。
- 设置面板显示系统栏 insets 的实测 dp 值与底部留白 cm 估算。
- 离线页加入「第一次使用？」引导：明确提示**连点右上角三次**可打开设置改服务器地址。
  因为仓库里的预构建 APK 用示例占位地址，首次打开必然走这条路。

### 发布

- 新增 `release/` 目录，提供**预构建 APK**，不需要自己搭工具链即可安装使用。
- 同时发布 `release/dsh-release.keystore`（口令 `dshmobile`）：
  Android 要求升级包签名一致，公开密钥才能让用户直接覆盖升级。
  **该密钥仅供本项目的示例 APK 使用，不要用于正式发布。**

### 文档

- 新增 [`docs/webview-viewport-pitfalls.md`](docs/webview-viewport-pitfalls.md)：
  完整记录本次排查过程，包括**两次错误判断**（推算 `densityDpi`、
  误判为"两个视口脱钩"）与最终定位根因的关键数据。

## [1.5.1] - 2026-09-22

### 新增

- 设置面板加入实机核对信息：物理分辨率、`densityDpi`、`fontScale`、
  系统栏 insets 实测值。
- 版式宽度输入框改为**边改边显示**换算后的缩放百分比。

### 变更

- 安全区 insets 改为 `systemBars` / `displayCutout` / `mandatorySystemGestures` /
  `tappableElement` **per-edge 取最大值**，避免 Android 15 强制 edge-to-edge 时
  `systemBars()` 退化成 0 导致底部留白丢失。
- `AndroidManifest` 的 `configChanges` 补 `density|fontScale`。
- 新增 `onConfigurationChanged` / `onWindowFocusChanged`，旋转或切换导航方式后重取 insets。

## [1.5.0] - 2026-09-22

### 变更

- 缩放基准改为固定常量，不再读 `DisplayMetrics.density`（思路正确，但基准值取错，见 1.5.2）。
- 显式 `setTextZoom(100)`，避免系统字体缩放二次影响页面文本。

## [1.4.0] - 2026-09-22

### 新增

- 连点右上角三次打开设置：可改服务器地址与版式宽度。
- 支持文件上传（`onShowFileChooser` + SAF）与下载（`DownloadManager`，带 cookie）。
- 返回键：先网页后退，到底后双击退出。

## [1.0.0] - 2026-09-22

### 新增

- 首个版本：WebView 全屏外壳，无 ActionBar，所有 http/https 跳转留在 WebView 内。
- 开启第三方 cookie 并持久化，登录/验证状态可长期保持。
- 不使用 Gradle，直接用 `aapt2` / `javac` / `d8` / `zipalign` / `apksigner` 构建。
