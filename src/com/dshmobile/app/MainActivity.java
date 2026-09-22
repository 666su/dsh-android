package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DSH 移动端外壳。
 *
 * 设计要点：
 *  - 指向 DSH Web 地址；若该地址会跳转到监控平台做验证，验证流程在同一个 WebView 内完成，
 *    所以必须开启第三方 cookie 并保持 cookie 持久化（签名 cookie 默认 30 天），验证一次长期有效。
 *  - 所有 http/https 跳转都留在 WebView 内（否则跨域验证会被踢到外部浏览器而断链）。
 *  - 版式：忽略页面自带的 width=device-width，由 setInitialScale 精确锁定
 *    「布局视口宽度 = 目标版式宽度(默认1280)」并整体缩放到屏幕，不依赖 WebView 默认行为。
 *    缩放基准用**固定 420dpi**（见 BASELINE_DENSITY_DPI），不读 DisplayMetrics.density，
 *    因此系统的「显示大小 / 字体大小」设置不会让整个页面尺度漂移。
 *  - targetSdk 35 起 Android 强制 edge-to-edge，WebView 会画到状态栏/导航栏底下，
 *    所以必须按 insets 让出安全区，否则底部内容被导航键遮住。安全区**严格按系统实测**
 *    （per-edge 取 systemBars / displayCutout / mandatorySystemGestures 的最大值），
 *    不写死任何高度，三键导航、手势条、横屏刘海都能自适应。
 *  - 旋转 / 切换导航方式后重新取 insets 并重算缩放。
 *  - 支持文件上传（onShowFileChooser）与下载（DownloadManager，带 cookie）。
 *  - 设置入口：连点右上角三次（无界面占用），可改服务器地址与缩放。
 */
public class MainActivity extends Activity {

    /**
     * 内置默认地址。**请改成你自己的服务地址**，或在构建时用
     * build-apk.ps1 -Url "..." 覆盖，也可以装好后连点右上角三次在设置里改。
     * 局域网自测示例：http://192.168.1.10:3080/
     */
    private static final String DEFAULT_URL = "http://192.168.1.10:3080/";
    private static final String PREFS = "dsh_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_LAYOUT_WIDTH = "layout_width";
    /** 目标版式宽度（CSS px）。DSH 是桌面版式，需要足够宽度侧边栏才不会挤掉正文。 */
    private static final int DEFAULT_LAYOUT_WIDTH = 1280;
    /**
     * 缩放基准 density（dpi）：160，即**直接用物理像素**作为尺度基准。
     *
     * 为什么不再用 420：实机诊断证明，旧方案（setUseWideViewPort(false) + setInitialScale）
     * 会让 WebView 用「物理宽 ÷ scale」反推布局视口 —— iQOO Z9 上得到
     *   1260 / 0.38 = 3315 CSS px
     * 页面于是按 3315px 的桌面版式排版，再整体缩到屏宽：一个 CSS px 仅占 0.38 物理像素，
     * 13px 正文 ≈ 5 物理 px ≈ 0.033 cm，几乎不可读（这就是「对话框很小」的根因）。
     *
     * 改用物理像素为基准后，缩放百分比直接等于「物理宽 ÷ 目标版式宽」，
     * 配合 setUseWideViewPort(true)，布局视口就精确落在目标宽度上：
     *   iQOO Z9：100 × 1260 / 1280 = 98%，布局视口 1280 CSS px，视觉缩放 0.98。
     * 同时这个比值只依赖**物理分辨率**，与系统「显示大小 / 字体大小」无关，尺度依然恒定。
     */
    private static final float BASELINE_DENSITY_DPI = 160f;

    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private long lastBackPress = 0L;
    private int cornerTaps = 0;
    private long firstCornerTap = 0L;
    /** 最近一次实测到的系统栏 insets（px），仅用于设置面板展示，便于实机核对。 */
    private int insetTopPx = 0, insetBottomPx = 0, insetLeftPx = 0, insetRightPx = 0;

    /**
     * 页面内实测的视口数据（只读诊断）。
     *
     * 用来确认「布局视口」与「视觉视口」是否脱钩 —— setInitialScale 会让二者宽度
     * 相差很大，而 position:fixed 元素相对视觉视口定位，脱钩时就会与普通流内内容
     * 错位（表现为「对话框居中、侧栏跑到右上角」）。拿到真实数字再决定修法，不猜。
     */
    private String diagText = "（正在读取…若一直如此，请下拉刷新页面）";

    /** 在页面里读一组只读视口指标并以 JSON 回传。 */
    private static String diagJs() {
        return "(function(){try{var vv=window.visualViewport;"
                + "var m=document.querySelector('meta[name=viewport]');"
                + "var pct=document.documentElement.clientWidth"
                + "?Math.round(window.innerWidth/document.documentElement.clientWidth*100)/100:0;"
                + "return JSON.stringify({"
                + "iw:window.innerWidth, ih:window.innerHeight,"
                + "cw:document.documentElement.clientWidth, ch:document.documentElement.clientHeight,"
                + "sw:document.documentElement.scrollWidth,"
                + "vvw:vv?Math.round(vv.width):-1, vvh:vv?Math.round(vv.height):-1,"
                + "vvs:vv?Math.round(vv.scale*100)/100:-1,"
                + "dpr:Math.round(window.devicePixelRatio*100)/100,"
                + "zoom:pct,"
                + "vp:m?m.getAttribute('content'):'(无meta)'"
                + "});}catch(e){return 'ERR '+e.message;}})()";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF18181B);

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(web);
        setContentView(root);

        applyEdgeToEdge(root);
        configureWebView();
        applyViewport();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(getServerUrl());
        }
    }

    // ── 安全区：让出状态栏 / 导航栏 / 刘海，避免内容被遮 ──────────────────────
    private void applyEdgeToEdge(final View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left, top, right, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    // 合并多来源再 per-edge 取最大：只看 systemBars() 在
                    // Android 15 强制 edge-to-edge 时可能退化成 0，导致底部留白丢失。
                    Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                    Insets c = insets.getInsets(WindowInsets.Type.displayCutout());
                    Insets g = insets.getInsets(WindowInsets.Type.mandatorySystemGestures());
                    Insets t = insets.getInsets(WindowInsets.Type.tappableElement());
                    left = max4(b.left, c.left, g.left, t.left);
                    top = max4(b.top, c.top, g.top, t.top);
                    right = max4(b.right, c.right, g.right, t.right);
                    bottom = max4(b.bottom, c.bottom, g.bottom, t.bottom);
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                insetLeftPx = left; insetTopPx = top;
                insetRightPx = right; insetBottomPx = bottom;
                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    // ── 版式 ──────────────────────────────────────────────────────────────
    private int getLayoutWidth() {
        int w = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAYOUT_WIDTH, 0);
        if (w < 640 || w > 3840) w = DEFAULT_LAYOUT_WIDTH;
        return w;
    }

    private static int max4(int a, int b, int c, int d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    /**
     * 尺度基准宽度 = **物理像素宽度**（不参与 density 换算）。
     *
     * 缩放百分比由此反算：pct = 100 × 物理宽 / 目标版式宽。
     * 只依赖物理分辨率，因此系统的「显示大小 / 字体大小」不会改变页面尺度。
     * 例：iQOO Z9 物理宽 1260px，目标 1280 → 98%。
     */
    private float deviceCssWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels * 160f / BASELINE_DENSITY_DPI;
    }

    /**
     * 由「目标版式宽度」反算缩放百分比。
     *
     * setInitialScale(p) 会让布局视口宽度 = 设备CSS宽度 / (p/100)，
     * 所以取 p = 100 * 设备CSS宽度 / 目标宽度，布局视口就恰好等于目标宽度，
     * 同时整体缩放到屏幕宽度 —— 版式与桌面一致，不会出现横向滚动。
     *
     * 用算术锁定这两个量，而不是依赖 setUseWideViewPort /
     * setLoadWithOverviewMode 的默认行为（那套在不同 WebView 版本上不一致，
     * 实测会退化成设备宽度视口，导致设置侧边栏占满整屏、正文错位）。
     */
    @SuppressWarnings("deprecation")
    private int computeScalePercent() {
        return computeScalePercentFor(getLayoutWidth());
    }

    @SuppressWarnings("deprecation")
    private int computeScalePercentFor(int target) {
        if (target < 640) target = DEFAULT_LAYOUT_WIDTH;
        int pct = Math.round(100f * deviceCssWidth() / target);
        if (pct < 20) pct = 20;
        if (pct > 200) pct = 200;
        return pct;
    }

    @SuppressWarnings("deprecation")
    private void applyViewport() {
        WebSettings s = web.getSettings();
        // 必须为 true：允许布局视口宽于设备宽度，setInitialScale 才会把布局视口
        // 锁在目标版式宽度上。设成 false 时实测布局视口会被放大成
        // 「物理宽 ÷ scale」（iQOO Z9 上是 3315 CSS px），页面尺度彻底失控。
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);  // 不让 overview 再插手缩放，只认下面的 scale
        web.setInitialScale(computeScalePercent());
    }

    private String getServerUrl() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String u = sp.getString(KEY_URL, DEFAULT_URL);
        if (u == null || u.trim().length() == 0) u = DEFAULT_URL;
        return u.trim();
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 版式尺度由 setInitialScale 锁定，文本不再随系统「字体大小」二次缩放，
        // 否则调系统字体就会让页面文字相对版式漂移（标签溢出、行高错位）。
        s.setTextZoom(100);

        // 支持双指缩放：默认整体偏小时可自己放大局部
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                return handleUrl(req.getUrl().toString());
            }

            private boolean handleUrl(String url) {
                if (url == null) return false;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false; // 留在应用内，跨域验证必须保持同一会话
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(getServerUrl())) {
                    showOfflinePage(description);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                probeDiagnostics();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) req.addRequestHeader("Cookie", cookie);
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                    Toast.makeText(MainActivity.this, "开始下载：" + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "下载失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 读一次页面内的视口指标，结果存到 diagText，供设置面板展示。 */
    private void probeDiagnostics() {
        if (web == null) return;
        try {
            web.evaluateJavascript(diagJs(), new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String json) {
                    diagText = decodeDiag(json);
                }
            });
        } catch (Exception e) {
            diagText = "读取失败：" + e.getMessage();
        }
    }

    /** 把回传的 JSON 解成可读文本（不引 JSON 库，手工取字段）。 */
    private String decodeDiag(String json) {
        if (json == null) return "（无回传）";
        String s = json.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (!s.startsWith("{")) return s;
        String wvScale;
        try {
            wvScale = String.valueOf(web.getScale());
        } catch (Exception e) {
            wvScale = "?";
        }
        return "布局视口 innerWidth " + field(s, "iw") + " × " + field(s, "ih")
                + "\n文档 clientWidth " + field(s, "cw") + " / scrollWidth " + field(s, "sw")
                + "\n视觉视口 visualViewport " + field(s, "vvw") + " × " + field(s, "vvh")
                + "\n视觉缩放 vv.scale " + field(s, "vvs") + " / WebView scale " + wvScale
                + "\n设备像素比 dpr " + field(s, "dpr")
                + "\n页面 viewport meta：" + field(s, "vp");
    }

    /** 从扁平 JSON 里取一个字段的原始文本。 */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return "?";
        int j = i + needle.length();
        int end = j;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        String v = json.substring(j, end).trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private void showOfflinePage(String detail) {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;flex-direction:column;"
                + "align-items:center;justify-content:center;background:#18181B;color:#EDEDED;"
                + "font-family:system-ui,sans-serif;padding:24px;text-align:center}"
                + "h2{font-size:19px;margin:0 0 10px}p{color:#8A8A96;font-size:13.5px;margin:0 0 22px;"
                + "word-break:break-all}a{display:inline-block;padding:12px 28px;border-radius:10px;"
                + "background:#4D6BFE;color:#fff;text-decoration:none;font-weight:600}</style></head>"
                + "<body><h2>无法连接</h2><p>" + (detail == null ? "" : detail)
                + "<br>" + getServerUrl() + "</p>"
                + "<a href='" + getServerUrl() + "'>重试</a></body></html>";
        web.loadDataWithBaseURL(getServerUrl(), html, "text/html", "utf-8", null);
    }

    /** 连点右上角三次 -> 设置 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            float density = getResources().getDisplayMetrics().density;
            float zone = 76 * density;
            int w = getWindow().getDecorView().getWidth();
            if (ev.getX() > w - zone && ev.getY() < zone) {
                long now = System.currentTimeMillis();
                if (now - firstCornerTap > 1600L) cornerTaps = 0;
                firstCornerTap = now;
                cornerTaps++;
                if (cornerTaps >= 3) {
                    cornerTaps = 0;
                    showSettings();
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void showSettings() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * dp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * dp), pad, 0);

        TextView l1 = new TextView(this);
        l1.setText("服务器地址");
        box.addView(l1);

        final EditText urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(getServerUrl());
        urlInput.setSelection(urlInput.getText().length());
        box.addView(urlInput);

        TextView l2 = new TextView(this);
        l2.setText("版式宽度（CSS px，默认 " + DEFAULT_LAYOUT_WIDTH + "）");
        l2.setPadding(0, (int) (14 * dp), 0, 0);
        box.addView(l2);

        final EditText widthInput = new EditText(this);
        widthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        widthInput.setText(String.valueOf(getLayoutWidth()));
        box.addView(widthInput);

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText(layoutHint(getLayoutWidth()));
        // 边改边看缩放百分比，不用先保存再回来
        widthInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(android.text.Editable e) {
                int w;
                try {
                    w = Integer.parseInt(e.toString().trim());
                } catch (Exception ex) {
                    return;
                }
                if (w < 640 || w > 3840) return;
                hint.setText(layoutHint(w));
            }
        });
        box.addView(hint);

        TextView dev = new TextView(this);
        dev.setTextSize(12f);
        dev.setPadding(0, (int) (10 * dp), 0, 0);
        dev.setText(deviceHint());
        box.addView(dev);

        // ── 只读诊断：布局视口 vs 视觉视口 ──────────────────────────────────
        TextView l3 = new TextView(this);
        l3.setText("视口诊断（只读）");
        l3.setPadding(0, (int) (14 * dp), 0, 0);
        box.addView(l3);

        final TextView diag = new TextView(this);
        diag.setTextSize(11.5f);
        diag.setTypeface(android.graphics.Typeface.MONOSPACE);
        diag.setText(diagText);
        box.addView(diag);

        Button reread = new Button(this);
        reread.setText("重新读取（不关闭本面板）");
        reread.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagText = "（正在读取…）";
                diag.setText(diagText);
                probeDiagnostics();
                // 探针是异步的，稍后再取一次
                diag.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        diag.setText(diagText);
                    }
                }, 700L);
            }
        });
        box.addView(reread);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("DSH 设置")
                .setView(box)
                .setPositiveButton("保存并重新加载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String u = urlInput.getText().toString().trim();
                        if (u.length() == 0) u = DEFAULT_URL;
                        if (!u.startsWith("http://") && !u.startsWith("https://")) {
                            u = "https://" + u;
                        }
                        int lw = DEFAULT_LAYOUT_WIDTH;
                        try {
                            lw = Integer.parseInt(widthInput.getText().toString().trim());
                        } catch (Exception ignored) { }
                        if (lw < 640 || lw > 3840) lw = DEFAULT_LAYOUT_WIDTH;

                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putString(KEY_URL, u)
                                .putInt(KEY_LAYOUT_WIDTH, lw)
                                .apply();

                        applyViewport();
                        web.loadUrl(u);
                    }
                })
                .setNeutralButton("恢复默认", null)
                .setNegativeButton("取消", null)
                .create();

        // 「恢复默认」保持面板不关闭，方便接着看诊断数字
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                        .putString(KEY_URL, DEFAULT_URL)
                                        .putInt(KEY_LAYOUT_WIDTH, DEFAULT_LAYOUT_WIDTH)
                                        .apply();
                                urlInput.setText(DEFAULT_URL);
                                widthInput.setText(String.valueOf(DEFAULT_LAYOUT_WIDTH));
                                hint.setText(layoutHint(DEFAULT_LAYOUT_WIDTH));
                                applyViewport();
                                web.loadUrl(DEFAULT_URL);
                                Toast.makeText(MainActivity.this,
                                        "已恢复默认，重新加载中", Toast.LENGTH_SHORT).show();
                            }
                        });
            }
        });

        dialog.show();
    }

    /** 版式宽度 → 缩放说明（改数字时实时更新）。 */
    private String layoutHint(int target) {
        return "物理宽 " + Math.round(deviceCssWidth()) + "px，目标版式 " + target + "px 时"
                + "自动缩放 " + computeScalePercentFor(target) + "%。\n"
                + "版式宽度调大 = 更接近桌面（内容更小），建议 1100~1500。\n"
                + "若底部出现横向滚动条，说明缩放过大，把这里调大即可。";
    }

    /**
     * 实机核对信息：确认缩放基准与底部安全区是否按预期生效。
     * dp / cm 一律用**系统真实 density** 折算（与页面尺度基准无关），
     * 这样即使系统「显示大小」改过，读到的仍是系统实际给出的栏高。
     */
    private String deviceHint() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float d = dm.density <= 0f ? 1f : dm.density;
        float dpi = dm.densityDpi <= 0 ? 160f : dm.densityDpi;
        StringBuilder sb = new StringBuilder();
        sb.append("实机核对：物理 ").append(dm.widthPixels).append("×").append(dm.heightPixels)
          .append("px / ").append(dm.densityDpi).append("dpi / fontScale ")
          .append(String.format(java.util.Locale.US, "%.2f", dm.scaledDensity / d))
          .append("（系统真实值，不影响页面尺度）")
          .append("\n系统栏 insets：上 ").append(Math.round(insetTopPx / d))
          .append("dp / 下 ").append(Math.round(insetBottomPx / d))
          .append("dp / 左 ").append(Math.round(insetLeftPx / d))
          .append("dp / 右 ").append(Math.round(insetRightPx / d))
          .append("dp\n底部留白约 ")
          .append(String.format(java.util.Locale.US, "%.2f", insetBottomPx * 2.54f / dpi))
          .append(" cm（按系统实测，非写死值）");
        return sb.toString();
    }

    /**
     * 旋转屏幕、切换三键/手势导航、改动系统「显示大小」都会走到这里。
     * 重取一次 insets（横屏时刘海会跑到左边），并按新宽度重算缩放。
     *
     * 安全区**立即生效**；缩放参数由 setInitialScale 承载，它只在加载期生效，
     * 所以旋转后**下一次加载**才按新宽度排版（不强刷，避免打断你正在看的内容）。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        View root = findViewById(android.R.id.content);
        if (root != null) {
            final View r = root;
            r.post(new Runnable() {
                @Override
                public void run() {
                    r.requestApplyInsets();
                }
            });
        }

        applyViewport();
    }

    /** 首次布局完成时系统栏数据才齐，补取一次，避免启动瞬间底部留白为 0。 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View root = findViewById(android.R.id.content);
            if (root != null) root.requestApplyInsets();
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000L) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
