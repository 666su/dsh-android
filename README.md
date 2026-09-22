# dsh-android

把一个**桌面版式的 Web 应用**（DSH = DeepSeek Harness Web GUI）封装成 Android APK，
在手机上全屏使用。

这不是一个通用的浏览器壳，而是针对一类具体问题做的原生外壳：**桌面 Web 应用 + 移动端窄屏**，
需要控制版式宽度、绕开 WebView 的缩放陷阱、正确处理 edge-to-edge 安全区。

> 项目里不含任何私有域名、后台地址或 API 端点——服务地址由你自己在构建时或应用内设置。

## 为什么要做原生外壳

DSH 前端自带 `manifest.webmanifest`（`display: fullscreen`），理论上浏览器"添加到主屏幕"
也能全屏。但那份清单只有 SVG 图标、没有 service worker，安卓上不一定被当作可安装应用，
而且浏览器壳会带来地址栏、跳转外链、cookie 隔离等问题。所以做了原生外壳。

## 功能

| 需求 | 实现 |
|---|---|
| 全屏无浏览器界面 | 无 ActionBar 主题 + WebView 铺满 |
| 登录/验证跳转能走通 | 所有 http/https 跳转**留在 WebView 内**，开启**第三方 cookie** |
| 验证一次长期有效 | cookie 持久化在应用私有目录，退出时主动 flush |
| 文件上传 | `onShowFileChooser` + SAF |
| 文件下载 | `DownloadManager`，并带上 WebView 的 cookie |
| 返回键 | 先网页后退，到底后双击退出 |
| 改地址 | **连点右上角三次** 打开设置（无界面占用） |
| 尺度稳定 | 缩放基准固定为**物理像素**，不随系统「显示大小 / 字体大小」漂移 |
| 底部安全区 | 按系统实测 insets（systemBars / 刘海 / 手势区 per-edge 取最大），**不写死高度** |

## 目录结构

```
dsh-android/
├─ src/com/dshmobile/app/MainActivity.java   唯一的 Activity，全部逻辑
├─ res/                                      图标、主题、字符串、网络安全配置
├─ AndroidManifest.xml
├─ assets/logo-512.png                       图标源图
├─ tools/make-android-icons.ps1              生成各密度 mipmap + 自适应图标前景
├─ docs/webview-viewport-pitfalls.md         踩坑记录：WebView 缩放与布局视口
├─ release/                                  ★ 可直接下载安装的分发件
│  ├─ DSH-mobile-1.5.2.apk                   预构建 APK
│  └─ dsh-release.keystore                   公开签名密钥（保证能覆盖升级）
├─ build-apk.ps1                             构建 APK（不用 Gradle）
├─ toolchain/                                JDK + Android SDK（约 450MB，可删可重下）
├─ build/                                    中间产物
└─ out/DSH-mobile.apk                        产物
```

## 为什么不用 Gradle

Gradle + Android Gradle Plugin 需要额外下载几百 MB 依赖，而且对 JDK 版本挑剔。
这个工程只有一个 Activity，直接用 SDK 自带的命令行工具就够了：

```
aapt2 compile  ->  编译资源
aapt2 link     ->  链接资源 + 生成 R.java
javac          ->  编译 Java
d8             ->  转成 classes.dex
zipalign       ->  对齐
apksigner      ->  签名
```

## 下载即用（推荐先用这个试试）

不想自己构建的话，直接下载 `release/` 里的 APK 安装：

**→ [`release/DSH-mobile-1.5.2.apk`](release/DSH-mobile-1.5.2.apk)**

传到手机点击安装，按提示允许「安装未知来源应用」。

> ⚠️ **这个 APK 里的服务地址是示例占位符**（`http://192.168.1.10:3080/`），
> 所以打开会看到「无法连接」。按下面提示改成你自己的地址即可。

### 第一次使用：改成你自己的地址

打开 APK 后会看到引导页，两种改法：

1. **应用内改（推荐，不用重新构建）**
   **连点屏幕右上角三次** → 打开设置 → 填「服务器地址」→ 点「保存并重新加载」。
   在设置里还能调「版式宽度」改变内容大小，并查看只读的视口诊断。
2. **改源码重新构建**
   改 `src/com/dshmobile/app/MainActivity.java` 里的 `DEFAULT_URL`，
   或者构建时用 `-Url` 参数覆盖（见下）。

### 关于 `release/dsh-release.keystore`

它**故意放在仓库里**，口令是 `dshmobile`。原因：Android 要求升级包与已安装应用
**签名一致**，否则覆盖安装会失败、必须先卸载。把签名密钥一起发布，你才能直接升级后续版本。

这个密钥是**公开的**，仅用于本项目的示例 APK。**不要用它签名正式发布的应用**
（任何人都能用它伪造你的更新包）。自己正式发布请生成自己的 keystore。

## 从源码构建

如果你要改代码，可以完全不用 Gradle。

### 1. 准备工具链（一次性，约 450MB）

本机没有 JDK 和 Android SDK 时需要先装：

```powershell
$root = $PWD
$tc   = Join-Path $root "toolchain"
New-Item -ItemType Directory -Force -Path $tc | Out-Null

# 1) JDK 17
Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile "$tc\jdk17.zip"
Expand-Archive "$tc\jdk17.zip" -DestinationPath $tc -Force

# 2) Android cmdline-tools
Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip" -OutFile "$tc\cmdline-tools.zip"
$sdk = "$tc\android-sdk"
Expand-Archive "$tc\cmdline-tools.zip" -DestinationPath $sdk -Force
New-Item -ItemType Directory -Force -Path "$sdk\cmdline-tools\latest" | Out-Null
Get-ChildItem "$sdk\cmdline-tools" -Exclude latest | Move-Item -Destination "$sdk\cmdline-tools\latest"

# 3) SDK 组件
$env:JAVA_HOME = (Get-ChildItem $tc -Directory | Where-Object Name -like "jdk-17*").FullName
"y" * 200 -join [char]10 | Set-Content "$tc\yes.txt"
Get-Content "$tc\yes.txt" | & "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "--sdk_root=$sdk" --licenses
Get-Content "$tc\yes.txt" | & "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "--sdk_root=$sdk" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

### 2. 设置你的服务地址

改 `src/com/dshmobile/app/MainActivity.java` 里的 `DEFAULT_URL`，
**或者**在构建时覆盖（会直接改写源码里的该常量）：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Url "http://192.168.1.10:3080/"
```

装好后也可以随时连点右上角三次在应用内改。

### 3. 构建

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

产物：`out\DSH-mobile.apk`

如果你删掉了 `dsh-release.keystore`，首次构建会自动生成一个新的。
**生成后要留着**，否则以后升级必须先卸载旧版。想覆盖安装就必须用同一个密钥——
这也是 `release/` 里放了一份的原因。

### 4. 装到手机

方式一：USB 连接后

```powershell
& "toolchain\android-sdk\platform-tools\adb.exe" install -r out\DSH-mobile.apk
```

方式二：把 `out\DSH-mobile.apk` 传到手机（微信/网盘/USB），点击安装。

## 使用

- 打开即全屏加载
- 若跳转到验证页，正常验证即可，之后长期免验证
- **连点右上角三次** → 设置（改地址、改版式宽度、看实机诊断）
- 返回键：先网页后退，到底后双击退出
- 双指缩放：可自己放大局部

## 尺度是怎么定的

缩放百分比 = `100 × 物理屏宽 / 目标版式宽`。

桌面版式需要足够宽度，侧边栏才不会挤掉正文，所以目标版式宽默认 **1280 CSS px**。
手机屏宽通常只有 1000~1400 物理像素，因此这个比值接近 100%，页面几乎 1:1 显示。

设置面板里的「版式宽度」就是这个目标值：

- **调大**（如 1400~1600）= 内容变小，一屏看更多，更接近桌面视野
- **调小**（如 900~1000）= 内容变大，更好点
- 底部出现横向滚动条 = 缩放过大，把目标值调大即可

> ⚠️ 这个尺度只依赖**物理分辨率**，与系统「显示大小 / 字体大小」无关。
> 原因和踩过的坑见 [`docs/webview-viewport-pitfalls.md`](docs/webview-viewport-pitfalls.md)。

## 已知限制与排查

| 现象 | 原因 / 处理 |
|---|---|
| 一直卡在验证页 | 验证方可能识别 WebView。可尝试在 `configureWebView()` 里设置 `s.setUserAgentString(<桌面 Chrome UA>)` |
| 白屏 | 用 `chrome://inspect` 远程调试；或把地址改成局域网 IP 试 |
| 图标是白底小图 | 自适应图标背景是透明的，部分启动器会自己加底色。需要实色底可改 `res/values/colors.xml` |
| 覆盖安装失败 | 换了签名密钥。必须先卸载旧版，或始终用同一个 keystore |
| 版式错乱、内容极小 | 见下面的排查方法 |

### 排查：内容极小 / 元素错位

打开设置面板看「视口诊断」：

| 观察 | 含义 |
|---|---|
| `innerWidth` ≈ 目标版式宽度 | 正常，缩放落点正确 |
| `innerWidth` 是目标值的数倍 | **布局视口失控**，见 docs 里的踩坑记录 |
| 有横向滚动条 | 缩放过大，调大「版式宽度」 |

## 数据与隐私

- 所有数据都在 WebView 的 cookie / localStorage 里，存于应用私有目录，不额外上传
- 应用只申请 `INTERNET`、`ACCESS_NETWORK_STATE`，以及 API 28 以下的存储权限（用于下载）
- 开启明文 HTTP 是为了能改用局域网地址自测，生产环境建议用 HTTPS

## License

MIT

---

作者 **Jason** · 博客 [blog.20240606.xyz](https://blog.20240606.xyz) · GitHub [666su](https://github.com/666su)
