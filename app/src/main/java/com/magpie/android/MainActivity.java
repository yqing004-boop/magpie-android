package com.magpie.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * magpie's window on a phone: a WebView pointed at the Web UI the gateway
 * process serves on its own loopback port, with the page's API, the model
 * picker and the agent keys all behind it.

 * Nothing in here knows anything about magpie's internals — the Activity
 * only polls a TCP port and loads an URL, so a newer magpie needs no change
 * to the wrapper.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MagpieActivity";

    /** 打开时直接落到哪一页（"plugins" / "providers" / "usage" …）。 */
    static final String EXTRA_VIEW = "view";
    private static final int REQ_SETTINGS = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_UPDATE = 3;
    /** 导入本地插件：SAF 选文件夹。 */
    /** 自动查更新的间隔：一天问一次就够了，别每次开 App 都跑流量。 */
    private static final long AUTO_CHECK_EVERY = 24L * 60 * 60 * 1000;

    private WebView web;
    private View overlay;
    private TextView statusTitle;
    private TextView statusBody;
    private Button retry;
    private Button showLog;
    private Button statusSettings;
    private ScrollView overlayScroll;

    // 顶栏卡片
    private View webCard;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> internalHosts = new HashSet<>();

    private volatile boolean loaded;
    private volatile boolean polling;
    private volatile ValueCallback<Uri[]> fileCallback;
    /** 文件选择器是否已经在前台：防止同时弹出两个 chooser 抢同一个回调。 */
    private volatile boolean fileChooserActive;

    /** 「跳转到某页」的轮次号与待撤销的定时任务：页面重载/销毁时整体作废。 */
    private int jumpGen;
    private final java.util.ArrayList<Runnable> jumpTasks = new java.util.ArrayList<>();
    /** 重建 WebView 期间挡住重复回调，别一次死两个进程建出三个页面。 */
    private volatile boolean webRebuilding;
    /** Activity 不可见后，按设置延迟释放 WebView；不碰 GatewayService。 */
    private boolean appInBackground;
    private boolean webReleased;
    private final Runnable releaseWebTask = this::releaseWebForBackground;

    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.webKey(this);
        setContentView(R.layout.activity_main);
        // 按设置决定是否从系统「最近任务」隐藏（防手滑划掉）
        NativeHome.applyHideFromRecents(this, Prefs.hideFromRecents(this));

        web = findViewById(R.id.web);
        overlay = findViewById(R.id.overlay);
        overlayScroll = findViewById(R.id.overlay);
        statusTitle = findViewById(R.id.status_title);
        statusBody = findViewById(R.id.status_body);
        retry = findViewById(R.id.status_retry);
        showLog = findViewById(R.id.status_show_log);
        statusSettings = findViewById(R.id.status_settings);
        webCard = findViewById(R.id.web_card);

        statusBody.setMovementMethod(new ScrollingMovementMethod());

        setupImmersive();
        applyTheme();

        setupWebView(savedInstanceState);

        retry.setOnClickListener(v -> startGatewayAndWait());
        showLog.setOnClickListener(v -> showLog());
        statusSettings.setOnClickListener(v -> openSettings());

        startGatewayAndWait();
        // 更新检查、后台保活引导、Marvis / 插件导入等壳侧动作都在原生主界面做
    }

    // ------------------------------------------------------------------
    // 沉浸：状态栏/导航栏透明，内容铺到屏幕边
    // ------------------------------------------------------------------

    private void setupImmersive() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        applySystemBars(M3.current(this)[M3.SURFACE]);

        // 没有壳顶栏了：WebView 直接让出状态栏。底部也要让出导航条，
        // 否则 magpie 自己的底栏（会话/资源库/供应商/路由/更多）会被
        // 系统手势条压住一截。
        int sb = barHeight("status_bar_height");
        int nav = barHeight("navigation_bar_height");
        if (webCard != null) {
            webCard.setPadding(0, sb, 0, nav);
        }
        overlay.setPadding(0, sb, 0, nav);
    }

    /** 系统栏高度（给别的界面复用，避免各自反射一遍）。 */
    static int barHeightOf(android.content.Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id)
                : Math.round(24 * ctx.getResources().getDisplayMetrics().density);
    }

    private int barHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // 外观：莫奈取色 / 自定义背景色
    // ------------------------------------------------------------------

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg2 = (b >> 8) & 0xff, bb = b & 0xff;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg2 - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return Color.rgb(r, g, bl);
    }

    static boolean darkColor(int c) {
        double lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
        return lum < 0.5;
    }

    /** 一块 M3 圆角底。 */
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** M3 filled tonal 按钮：secondary container 底 + on-secondary container 字。 */
    private void tonalButton(Button b, int[] s) {
        b.setBackground(rounded(s[M3.SECONDARY_CONTAINER], 20));
        b.setTextColor(s[M3.ON_SECONDARY_CONTAINER]);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
    }

    /**
     * 只给错误页上色：整个界面就是一个 WebView，magpie 自己的配色由它决定，
     * 壳不越权改。
     */
    private void applyTheme() {
        int[] s = M3.current(this);
        int surface = s[M3.SURFACE];

        findViewById(R.id.root).setBackgroundColor(surface);
        if (webCard != null) {
            webCard.setBackgroundColor(surface);
        }
        overlay.setBackgroundColor(surface);

        statusTitle.setTextColor(s[M3.ON_SURFACE]);
        statusBody.setTextColor(s[M3.ON_SURFACE_VARIANT]);
        tonalButton(retry, s);
        tonalButton(showLog, s);
        tonalButton(statusSettings, s);

        applySystemBars(surface);
    }

    private static String hex(int c) {
        return String.format("#%06X", 0xFFFFFF & c);
    }




    /** 状态栏/导航栏透明，图标颜色按背景深浅翻。 */
    private void applySystemBars(int pageColor) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        boolean light = !darkColor(pageColor);
        if (light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }


    @Override
    protected void onResume() {
        super.onResume();
        appInBackground = false;
        ui.removeCallbacks(releaseWebTask);
        // 回前台若页面还活着就重新起心跳（幂等：先撤再加，别叠两份）
        ui.removeCallbacks(heartbeat);
        if (loaded && web != null) {
            ui.postDelayed(heartbeat, 15000);
        }
        if (webReleased && web == null) {
            rebuildWeb(false);
            webReleased = false;
        }
        // 从设置页回来可能换了背景/莫奈色，重画一遍
        applyTheme();
        GatewayService.syncNotification(this, GatewayService.isAlive());
        String patchNote = GatewayService.takePatchNote();
        if (patchNote != null && !patchNote.isEmpty()) {
            Toast.makeText(this, patchNote, Toast.LENGTH_LONG).show();
        }
        if (!loaded && !polling && GatewayService.isAlive()) {
            poll();
        }
    }

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!loaded) {
                return;
            }
            if (!GatewayService.isAlive()) {
                showStatus();
            } else {
                ui.postDelayed(this, 15000);
            }
        }
    };

    /**
     * 通知点击在 singleTask Activity 已存在时走这里，而不是再创建一个窗口。
     * 只刷新壳状态；不重启网关、不重新 loadUrl，避免打断正在进行的页面操作。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        GatewayService.syncNotification(this, GatewayService.isAlive());
        // 已经有一扇 MainActivity 在栈上（singleTop）时，若这次带 view，按新 URL 重新加载一次
        String v = sanitizeView(intent == null ? null : intent.getStringExtra(EXTRA_VIEW));
        if (v != null && web != null) {
            loaded = false;
            loadPage();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(heartbeat);
    }

    @Override
    protected void onStop() {
        super.onStop();
        appInBackground = true;
        // 退后台收掉文件选择回调，别让 WebView/页面挂着等结果（否则泄漏/卡死）
        clearFileCallback();
        ui.removeCallbacks(releaseWebTask);
        int minutes = Prefs.webReleaseMinutes(this);
        if (minutes > 0 && !isChangingConfigurations()) {
            long delay = minutes * 60_000L;
            Log.i(TAG, "后台页面释放计时开始：" + minutes + " 分钟");
            ui.postDelayed(releaseWebTask, delay);
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(releaseWebTask);
        ui.removeCallbacks(heartbeat);
        clearFileCallback();
        destroyWeb();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            if (resultCode == RESULT_OK) {
                // 设置页自己会在端口/局域网/更新源变化时重启网关；这里**不再**
                // 重启第二遍 —— 以前两边各重启一次，日志里就是连着两条 exec 启动，
                // 两个进程抢同一个端口，正在跑的请求也被打断。
                loaded = false;
                poll();
            }
        } else if (requestCode == REQ_UPDATE) {
            if (resultCode == RESULT_OK) {
                // 更新页换掉了二进制并重启了网关：丢掉旧页面，等新进程上线
                loaded = false;
                GatewayService.resetState();
                startGatewayAndWait();
            }
        } else if (requestCode == REQ_FILE) {
            fileChooserActive = false;
            ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            if (cb != null) {
                cb.onReceiveValue(data != null && data.getData() != null
                        ? new Uri[]{data.getData()} : null);
            }
        } else if (requestCode == PluginTools.REQ_PLUGIN_DIR) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                PluginTools.onFolderPicked(this, data.getData(), this::styleDialog);
            }
        }
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    private void setupWebView(Bundle saved) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // 让网页自己的 <meta name="viewport" content="width=device-width"> 生效：
        // useWideViewPort=true = 支持 viewport meta；loadWithOverviewMode=true = 按该宽度铺满屏幕。
        // **不设**这两项时 WebView 退回默认宽视口（约 980px），magpie 网页
        // @media(max-width:760px) 的手机适配不命中，就会出现「App 内是桌面版、底部 tab
        // 挤成一行，浏览器打开却正常」——正是这里必须设的原因。（旧注释把因果写反了。）
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptFileSchemeCookies(true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (isInternal(url)) {
                    hideOverlay();
                }
            }

            /**
             * 页面渲染进程被系统回收（内存压力、长时间后台、渲染崩溃）时，
             * 不接管的话 Android 会把整个 App 一起杀掉 —— 表现为闪退。
             * 返回 true 表示自己处理：丢掉这个 WebView，重建一个再加载。
             */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    return false;
                }
                boolean crashed = detail != null && detail.didCrash();
                Log.w(TAG, "WebView 渲染进程结束（didCrash=" + crashed + "），准备重建");
                ui.post(() -> rebuildWeb(crashed));
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (isInternal(u)) {
                    return false;
                }
                String scheme = schemeOf(u);
                if (!"http".equals(scheme) && !"https".equals(scheme) && !"intent".equals(scheme)) {
                    // javascript:/about:/data:/blob: 是页面自己的动作（复制、下载、
                    // 打开面板都靠它们），交给外部 Activity 会把页面打断；留在
                    // WebView 里由页面自己处理。
                    return false;
                }
                openExternal(u);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()
                        && isInternal(request.getUrl().toString())) {
                    confirmGatewayDown();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request != null && request.isForMainFrame()
                        && isInternal(request.getUrl().toString())
                        && response.getStatusCode() >= 500) {
                    confirmGatewayDown();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isInternal(url)) {
                    loaded = true;
                    // 记住控制后台最后停留的完整地址（含 ?k=），供菜单「复制上次网页地址」用
                    try {
                        Prefs.setLastWebUrl(MainActivity.this,
                                view != null ? view.getUrl() : url);
                    } catch (Exception ignored) {
                    }
                    hideOverlay();
                    jumpToRequestedView();
                    if (!GatewayService.isAlive()) {
                        confirmGatewayDown();
                    } else {
                        ui.postDelayed(heartbeat, 15000);
                    }
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(view.getTitle())
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(view.getTitle())
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(view.getTitle())
                        .setMessage(message)
                        .setCancelable(false);
                final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setText(defaultValue);
                b.setView(input);
                b.setPositiveButton(android.R.string.ok, (d, w) -> result.confirm(input.getText().toString()));
                b.setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel());
                b.setOnCancelListener(d -> result.cancel());
                b.show();
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                WebView.HitTestResult hit = view.getHitTestResult();
                String extra = hit == null ? null : hit.getExtra();
                if (extra != null && !extra.isEmpty() && !isInternal(extra)) {
                    String scheme = schemeOf(extra);
                    if ("http".equals(scheme) || "https".equals(scheme) || "intent".equals(scheme)) {
                        openExternal(extra);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // magpie's page asks for the microphone and the camera for its
                // agent tools; nothing on this device is worth capturing, so
                // the request is answered so it does not sit there forever
                request.deny();
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserActive) {
                    // 已经有一个选择器在前台：拒绝这次重复请求，避免两个 chooser 抢回调
                    callback.onReceiveValue(null);
                    return true;
                }
                fileChooserActive = true;
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // accept 写的是 MIME（"text/plain"）还是扩展名（".md,.txt"）？
                // 扩展名列表不是合法 MIME，setType 后系统选择器要么打不开、要么
                // 什么都选不了（插件页的 accept=".md,.markdown,.txt,.json,.zip"
                // 就踩的这条）。扩展名一律放 */* —— 让用户选任何文件，类型检查由
                // 网页自己的 JS 做（file.name 后缀、file.text() 解析都在页面里）。
                String type = "*/*";
                if (params != null) {
                    String[] mime = params.getAcceptTypes();
                    if (mime != null && mime.length > 0 && mime[0] != null
                            && mime[0].contains("/") && !mime[0].startsWith(".")) {
                        type = mime[0];
                    }
                }
                intent.setType(type);
                try {
                    startActivityForResult(intent, REQ_FILE);
                } catch (Exception e) {
                    Log.w(TAG, "no file picker: " + e);
                    callback.onReceiveValue(null);
                    fileCallback = null;
                    fileChooserActive = false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, rowId) -> {
            // the page's own fetch handles its downloads; anything else goes
            // to the browser, which is where a link belongs
            if (isInternal(url)) {
                return;
            }
            openExternal(url);
        });

        web.setPadding(0, 0, 0, 0);
        web.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
        web.setVerticalScrollBarEnabled(true);
        WebView.setWebContentsDebuggingEnabled(true); // adb forward tcp:9222 tcp:9222, then chrome://inspect

        if (saved != null && saved.containsKey("magpie_web_state")) {
            web.restoreState(saved);
        }
    }

    /**
     * 到时只释放页面：不 stop/restart 服务、不碰 magpie ELF、不影响 3425 上的请求。
     * 回到前台时 onResume 发现 web=null，再创建新的 WebView。
     */
    private void releaseWebForBackground() {
        if (!appInBackground || web == null || webRebuilding || isChangingConfigurations()) {
            return;
        }
        Log.i(TAG, "后台超时，释放 WebView；网关继续运行");
        destroyWeb();
        webReleased = true;
    }

    /** 彻底销毁当前 WebView 并置空（后台释放与 Activity 销毁共用，避免泄漏）。 */
    private void destroyWeb() {
        // 页面要销毁/重建：作废所有待跑的跳转重试
        jumpGen++;
        cancelJumpTasks();
        WebView w = web;
        if (w == null) {
            return;
        }
        try {
            ViewGroup parent = (ViewGroup) w.getParent();
            if (parent != null) {
                parent.removeView(w);
            }
        } catch (Exception ignored) {
        }
        try {
            w.setWebViewClient(null);
            w.setWebChromeClient(null);
            w.stopLoading();
            w.destroy();
        } catch (Exception e) {
            Log.w(TAG, "销毁 WebView 失败", e);
        }
        web = null;
    }

    /** 清掉挂起的文件选择回调（退后台/销毁时调用），否则页面/WebView 挂住不释放。 */
    private void clearFileCallback() {
        fileChooserActive = false;
        ValueCallback<Uri[]> cb = fileCallback;
        fileCallback = null;
        if (cb != null) {
            try {
                cb.onReceiveValue(null);
            } catch (Exception ignored) {
            }
        }
    }


    /**
     * 渲染进程没了：旧 WebView 实例已经作废（不能复用），销毁后在同一个
     * FrameLayout 里新建一个，重新绑定客户端再加载页面。
     *
     * <p>不 restoreState —— 页面状态本来就存在那个死掉的渲染进程里，
     * 直接重新加载首页最可靠。网关的数据不受影响。
     */
    private void rebuildWeb(final boolean crashed) {
        if (webRebuilding) {
            return;
        }
        webRebuilding = true;
        try {
            ViewGroup parent = (ViewGroup) webCard;
            try {
                web.setWebViewClient(null);
            } catch (Exception ignored) {
            }
            parent.removeView(web);
            try {
                web.destroy();
            } catch (Exception ignored) {
            }

            web = new WebView(this);
            web.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            parent.addView(web);

            setupWebView(null);
            web.setBackgroundColor(M3.current(this)[M3.SURFACE]);

            loaded = false;
            Toast.makeText(this, crashed ? R.string.web_render_crashed : R.string.web_restarted,
                    Toast.LENGTH_LONG).show();
            if (GatewayService.isAlive()) {
                loadPage();
            } else {
                showStatus();
            }
        } catch (Exception e) {
            Log.e(TAG, "重建 WebView 失败", e);
            loaded = false;
            showStatus();
        } finally {
            webRebuilding = false;
        }
    }

    /**
     * 真正的 magpie 页面 = http(s) + 本机主机 + magpie 自己的端口。
     *
     * <p>复制按钮、下载、剪贴板这些页面内动作会用到 {@code javascript:}、
     * {@code about:}、{@code blob:}、{@code data:} 之类的地址：它们既不该被当成
     * "外部链接"交给系统浏览器，也不该被当成"我们的页面出错"。以前只按 host
     * 判断（这些地址的 host 是空的就落到 else 分支），一次复制就可能把整页顶成
     * 网关错误页。这里把协议和端口都算进来。
     */
    private boolean isInternal(String url) {
        String scheme = schemeOf(url);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return false;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return false;
        }
        boolean local = host.equals("127.0.0.1") || host.equals("localhost")
                || host.equals("0.0.0.0") || host.equals("::1")
                || host.equals("magpie") || host.endsWith(".magpie")
                || internalHosts.contains(host);
        if (!local) {
            return false;
        }
        int port = portOf(url);
        return port == GatewayService.webPort(this) || port == GatewayService.gatewayPort(this);
    }

    private static String schemeOf(String url) {
        if (url == null) {
            return "";
        }
        int colon = url.indexOf(':');
        return colon <= 0 ? "" : url.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
    }

    private static int portOf(String url) {
        String host = hostOf(url);
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return -1;
        }
        String rest = url.substring(scheme + 3);
        String hostPart = rest;
        int slash = hostPart.indexOf('/');
        if (slash >= 0) {
            hostPart = hostPart.substring(0, slash);
        }
        int at = hostPart.lastIndexOf('@');
        if (at >= 0) {
            hostPart = hostPart.substring(at + 1);
        }
        int colon = hostPart.lastIndexOf(':');
        if (colon < 0 || colon < hostPart.length() - 6) {
            return -1;
        }
        try {
            return Integer.parseInt(hostPart.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest.toLowerCase();
    }

    private void openExternal(String url) {
        // 安全：外部链接只当普通 ACTION_VIEW「查看」处理。禁止 intent: 伪协议
        // 把任意 action/component/extras 透传给别的 App（intent-redirection）。
        try {
            Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            if (i != null && (i.getAction() == null
                    || Intent.ACTION_VIEW.equals(i.getAction()))) {
                i.setAction(Intent.ACTION_VIEW);
                i.setComponent(null);
                i.setSelector(null);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "cannot open " + url + ": " + e);
        }
    }

    // ------------------------------------------------------------------
    // starting and waiting
    // ------------------------------------------------------------------

    private void startGatewayAndWait() {
        hideOverlay();
        try {
            Intent i = new Intent(this, GatewayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot start the service", e);
        }
        poll();
    }

    private void poll() {
        if (polling) {
            return;
        }
        polling = true;
        String host = Prefs.browserHost(this);
        int port = GatewayService.webPort(this);
        ui.post(() -> statusTitle.setText(R.string.status_starting));
        new Thread(() -> {
            for (int i = 0; i < 400; i++) {
                if (GatewayService.portOpen(host, port)) {
                    ui.post(() -> {
                        polling = false;
                        loadPage();
                    });
                    return;
                }
                GatewayService.sleep(300);
            }
            ui.post(() -> {
                polling = false;
                showStatus();
            });
        }, "magpie-wait").start();
    }

    /** 只允许 [a-z0-9_-]{1,32}，其余当空（防注入 / 防拼坏 URL）。 */
    static String sanitizeView(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.isEmpty() || s.length() > 32) {
            return null;
        }
        return s.matches("[a-z0-9_-]{1,32}") ? s : null;
    }

    /**
     * 把「要落的 view」拼进 magpie 网页 URL：{@code ...?k=<key>&view=<v>#view=<v>}。
     * 网页路由认 query {@code view=} + hash {@code #view=}，直接按 URL 打开即可落到目标页。
     */
    static String withView(Context ctx, String view) {
        String base = GatewayService.webUrl(ctx);
        String v = sanitizeView(view);
        if (v == null || v.isEmpty()) {
            return base;
        }
        try {
            String enc = java.net.URLEncoder.encode(v, "UTF-8");
            return base + "&view=" + enc + "#view=" + enc;
        } catch (Exception e) {
            return base;
        }
    }

    private void loadPage() {
        if (loaded) {
            return;
        }
        loaded = true;
        hideOverlay();
        String view = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_VIEW);
        String url = withView(this, view);
        Log.i(TAG, "loading " + url);
        web.loadUrl(url);
    }

    /**
     * 如果是从「插件」胶囊点进来的，加载完就切到那一页。
     *
     * <p>magpie 的网页是自绘的单页应用，`show(view)` 是模块内的局部函数、
     * 不在 window 上，调不到；手机版底栏还会被它自己重新分组。所以这里
     * 分三步试：
     * <ol>
     *   <li>直接点 `#nav` 里 `data-view` 匹配的按钮</li>
     *   <li>找不到就点「更多」，展开后再点</li>
     *   <li>还没有就按按钮上的文字找（中文/英文都试）</li>
     * </ol>
     * 全都失败就留在原页，不报错。
     */
    private void jumpToRequestedView() {
        String want = sanitizeView(getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_VIEW));
        if (want == null || want.isEmpty()) {
            return;
        }
        // 已经按 ?view=/#view= 直接打开了目标页：无需再模拟点击
        // （下面 6 轮点击只作为 URL 未带 view= 时的兜底）
        try {
            String cur = web == null ? null : web.getUrl();
            if (cur != null && cur.contains("view=")) {
                if (getIntent() != null) {
                    getIntent().removeExtra(EXTRA_VIEW);
                }
                return;
            }
        } catch (Exception ignored) {
        }
        final String w = want;
        final String[] texts = textsFor(w);
        // 新一轮：作废上一轮所有待跑任务，然后按 [200,600,1200,2000,3000,4500]ms 依次尝试
        final int gen = ++jumpGen;
        cancelJumpTasks();
        final long[] delays = {200, 600, 1200, 2000, 3000, 4500};
        for (int i = 0; i < delays.length; i++) {
            final int attempt = i;
            Runnable r = () -> attemptJump(gen, w, texts, attempt);
            jumpTasks.add(r);
            ui.postDelayed(r, delays[i]);
        }
    }

    /** 一次尝试：先执行「hash + data-view 点击 + 文字匹配」的 JS，320ms 后再校验是否到位。 */
    private void attemptJump(final int gen, final String want, final String[] texts,
                             final int attempt) {
        if (gen != jumpGen || web == null || isFinishing() || isDestroyed()) {
            return;
        }
        try {
            web.evaluateJavascript(jumpActionJs(want, texts), null);
        } catch (Exception e) {
            Log.w(TAG, "跳转动作失败(" + want + "): " + e);
            return;
        }
        ui.postDelayed(() -> verifyJump(gen, want, texts, attempt), 320);
    }

    /** 校验目标页是否已经显示；成功就停掉后续重试并清掉 EXTRA_VIEW。 */
    private void verifyJump(final int gen, final String want, final String[] texts,
                            final int attempt) {
        if (gen != jumpGen || web == null || isFinishing() || isDestroyed()) {
            return;
        }
        try {
            web.evaluateJavascript(jumpVerifyJs(want, texts), value -> {
                if (gen != jumpGen) {
                    return;
                }
                if (isJsTrue(value)) {
                    // 确认到位：清掉 extra，作废剩余轮次
                    jumpGen++;
                    cancelJumpTasks();
                    if (getIntent() != null) {
                        getIntent().removeExtra(EXTRA_VIEW);
                    }
                    Log.i(TAG, "已跳到 " + want + "（第 " + (attempt + 1) + " 轮确认）");
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "校验跳转失败(" + want + "): " + e);
        }
    }

    private void cancelJumpTasks() {
        for (Runnable r : jumpTasks) {
            ui.removeCallbacks(r);
        }
        jumpTasks.clear();
    }

    private static boolean isJsTrue(String v) {
        if (v == null) {
            return false;
        }
        return v.trim().startsWith("true");
    }

    /** 各 view 的中/英文文案（按文字匹配按钮时用）。 */
    private static String[] textsFor(String want) {
        switch (want) {
            case "library": return new String[]{"资源库", "Library"};
            case "plugins": return new String[]{"插件", "Plugins"};
            case "providers": return new String[]{"供应商", "Providers"};
            case "usage": return new String[]{"用量", "Usage"};
            case "routing": return new String[]{"路由", "Routing"};
            case "masking": return new String[]{"脱敏", "Masking"};
            default: return new String[]{want};
        }
    }

    /** 执行动作的 JS：hash → data-view 点击/展开更多 → 文字匹配。 */
    private static String jumpActionJs(String want, String[] texts) {
        return "(function(){\n"
                + "var want=" + jsStr(want) + ";\n"
                + "var txt=" + jsStrArray(texts) + ";\n"
                + "try{ if(location.hash!=='#view='+want){ location.hash='view='+want;\n"
                + "  try{window.dispatchEvent(new HashChangeEvent('hashchange'));}catch(e){}\n"
                + "} }catch(e){}\n"
                + "var found=false;\n"
                + "var bs=document.querySelectorAll('[data-view]');\n"
                + "for(var i=0;i<bs.length;i++){ if(bs[i].getAttribute('data-view')===want){\n"
                + "  try{bs[i].click();found=true;}catch(e){} break; } }\n"
                + "if(!found){ var more=document.querySelector('#nav .nav-more')||document.querySelector('#nav [data-more]');\n"
                + "  if(more){ try{more.click();}catch(e){} } }\n"
                + "var all=document.querySelectorAll('button, a, [role=button]');\n"
                + "for(var i=0;i<all.length;i++){ var s=(all[i].textContent||'').trim();\n"
                + "  for(var k=0;k<txt.length;k++){ if(s===txt[k]){ try{all[i].click();}catch(e){} } } }\n"
                + "return true;\n"
                + "})();";
    }

    /** 校验的 JS：nav 选中态 / 目标容器可见 / 标题文字 / hash —— 任一命中即 true。 */
    private static String jumpVerifyJs(String want, String[] texts) {
        return "(function(){\n"
                + "var want=" + jsStr(want) + ";\n"
                + "var txt=" + jsStrArray(texts) + ";\n"
                + "function active(el){ var c=(el.className||'')+' '+(el.getAttribute&&el.getAttribute('class')||'');\n"
                + "  return /(^|\\s)(active|selected|current|is-active)(\\s|$)/.test(c) || el.getAttribute('aria-selected')==='true'; }\n"
                + "var bs=document.querySelectorAll('[data-view]');\n"
                + "for(var i=0;i<bs.length;i++){ if(bs[i].getAttribute('data-view')===want && active(bs[i])) return true; }\n"
                + "var el=document.querySelector('#view-'+want)||document.querySelector('[data-page=\"'+want+'\"]')||document.getElementById(want);\n"
                + "if(el){ var st=getComputedStyle(el);\n"
                + "  if(st.display!=='none'&&st.visibility!=='hidden'&&el.offsetParent!==null) return true; }\n"
                + "var heads=document.querySelectorAll('h1,h2,h3,[role=heading]');\n"
                + "for(var i=0;i<heads.length;i++){ var s=(heads[i].textContent||'').trim();\n"
                + "  for(var k=0;k<txt.length;k++){ if(s===txt[k]) return true; } }\n"
                + "if(location.hash.indexOf('view='+want)>=0) return true;\n"
                + "return false;\n"
                + "})();";
    }

    private static String jsStrArray(String[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsStr(arr[i]));
        }
        return sb.append(']').toString();
    }

    /** Java 字符串 → JS 字符串字面量（只用来塞 view 名，很短）。 */
    private static String jsStr(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c < 0x20) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    private void hideOverlay() {
        if (overlay != null && overlay.getVisibility() != View.GONE) {
            overlay.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // the overlay
    // ------------------------------------------------------------------

    /**
     * 页面报错不等于网关死了：子资源 404、复制动作失败、WebView 的一次空导航
     * 都会走到这里。先真的去连一次 Web UI 端口，连不上才盖错误页。
     */
    private void confirmGatewayDown() {
        final String host = Prefs.browserHost(this);
        final int port = GatewayService.webPort(this);
        new Thread(() -> {
            boolean up = false;
            for (int i = 0; i < 6 && !up; i++) {
                up = GatewayService.portOpen(host, port);
                if (!up) {
                    GatewayService.sleep(250);
                }
            }
            final boolean alive = up;
            ui.post(() -> {
                if (!alive) {
                    showStatus();
                }
            });
        }, "magpie-check").start();
    }

    private void showStatus() {
        loaded = false;
        statusTitle.setText(getString(GatewayService.isAlive()
                ? R.string.status_not_answering
                : R.string.status_not_running));
        statusBody.setText(statusText());
        overlay.setVisibility(View.VISIBLE);
        if (overlayScroll != null) {
            overlayScroll.post(() -> overlayScroll.fullScroll(View.FOCUS_UP));
        }
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("网关地址  ").append(GatewayService.bindHost(this))
                .append(':').append(GatewayService.gatewayPort(this)).append('\n');
        sb.append("界面地址  http://127.0.0.1:").append(GatewayService.webPort(this))
                .append("/?k=…\n");
        if (Prefs.lan(this)) {
            sb.append("已开启局域网：同一 Wi-Fi 的电脑可用\n")
                    .append("  http://<本机IP>:").append(GatewayService.gatewayPort(this)).append('\n');
        }
        sb.append('\n');
        sb.append("数据目录  ").append(GatewayService.dataDir(this)).append('\n');
        sb.append("二进制    ").append(GatewayService.binary(this))
                .append(GatewayService.binary(this).exists() ? "" : "（尚未释放）").append('\n');
        sb.append("日志      ").append(GatewayService.logFile(this)).append('\n');
        if (GatewayService.bootError() != null && !GatewayService.bootError().isEmpty()) {
            sb.append('\n').append("启动错误  ").append(GatewayService.bootError()).append('\n');
        }
        int code = GatewayService.exitCode();
        if (code >= 0) {
            sb.append("退出码    ").append(code).append('\n');
        }
        sb.append("\n── magpie 最近输出 ──────────────\n");
        String log = GatewayService.lastLog(60);
        if (log == null || log.trim().isEmpty()) {
            sb.append("(空)\n");
        } else {
            sb.append(log).append('\n');
        }
        return sb.toString();
    }




    private TextView label(int[] c, int text, boolean bright) {
        TextView t = new TextView(this);
        t.setTextSize(12f);
        t.setText(text);
        t.setTextColor(c[bright ? M3.ON_SURFACE : M3.ON_SURFACE_VARIANT]);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    /** 把回调地址交给插件：写到 magpie 子进程的 HOME 下，插件读完即删。 */
    private boolean writeMarvisCallback(String url) {
        try {
            java.io.File dir = GatewayService.homeDir(this);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            java.io.File f = new java.io.File(dir, "marvis-login-url.txt");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "marvis callback write failed: " + e);
            return false;
        }
    }

    private void showLog() {
        String tail = GatewayService.lastLog(0);
        if (tail == null || tail.isEmpty()) {
            tail = "(还没有输出)";
        }
        final String log = tail;
        AlertDialog logDialog = new AlertDialog.Builder(this)
                .setTitle("magpie 日志（" + GatewayService.logFile(this).getAbsolutePath() + "）")
                .setMessage(log)
                .setNeutralButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("magpie", log));
                    }
                })
                .setPositiveButton("关闭", null)
                .create();
        styleDialog(logDialog);
        logDialog.show();
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivityForResult(i, REQ_SETTINGS);
    }

    private void openUpdate() {
        Intent i = new Intent(this, UpdateActivity.class);
        startActivityForResult(i, REQ_UPDATE);
    }


    /** 给系统 AlertDialog 换上 M3 的容器色，并把标题/正文/按钮刷成对应前景。 */
    private void styleDialog(AlertDialog dlg) {
        int[] c = M3.current(this);
        int stroke = (c[M3.PRIMARY] & 0x00FFFFFF) | 0x52000000;
        GradientDrawable dbg = rounded(c[M3.SURFACE_CONTAINER_LOW], 28);
        dbg.setStroke(Math.max(1, dp(2)), stroke);
        dlg.setOnShowListener(x -> {
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(dbg);
            }
            int alertTitleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView title = alertTitleId > 0 ? dlg.findViewById(alertTitleId) : null;
            if (title != null) {
                title.setTextColor(c[M3.ON_SURFACE]);
            }
            TextView msg = dlg.findViewById(android.R.id.message);
            if (msg != null) {
                msg.setTextColor(c[M3.ON_SURFACE_VARIANT]);
            }
            GradientDrawable btnBg = rounded(0x00000000, 999);
            btnBg.setStroke(Math.max(1, dp(1)), stroke);
            for (int id : new int[]{AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
                Button b = dlg.getButton(id);
                if (b != null) {
                    b.setTextColor(c[M3.PRIMARY]);
                    b.setBackground(btnBg);
                }
            }
        });
    }

    /**
     * 页面是 magpie 自带的 Web UI，排版全由它自己的 CSS 响应式决定：
     * 窄屏下设置 tab 行要横滑（“同步与备份”“关于”在右边外面），
     * 宽屏下就跟你截图里在电脑浏览器看到的一样全铺开。
     * 桌面模式 = 报桌面 UA + 改宽视口，页面自己就会按宽屏排。
     */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";


    // ------------------------------------------------------------------
    // 壳（APK）自更新：方案一 —— App 内下载 + 拉起系统安装器，不跳浏览器
    // ------------------------------------------------------------------



    private String mirrorLabel(String mirror) {
        if (mirror == null || mirror.isEmpty()) {
            return getString(R.string.shell_mirror_official);
        }
        return mirror;
    }




    // ------------------------------------------------------------------
    // 导入本地插件：SAF 选文件夹 → 拷进沙箱 → 调 magpie API 加载
    // ------------------------------------------------------------------



    private String folderNameOf(Uri tree, String docId) {
        String name = null;
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        Cursor nc = getContentResolver().query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (nc != null) {
            try {
                if (nc.moveToFirst()) {
                    name = nc.getString(0);
                }
            } finally {
                nc.close();
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = docId.substring(docId.lastIndexOf('/') + 1);
        }
        return name;
    }
}
