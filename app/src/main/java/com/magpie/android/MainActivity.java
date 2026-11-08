package com.magpie.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.view.KeyEvent;
import android.view.View;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashSet;
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
    private static final int REQ_SETTINGS = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_UPDATE = 3;
    /** 自动查更新的间隔：一天问一次就够了，别每次开 App 都跑流量。 */
    private static final long AUTO_CHECK_EVERY = 24L * 60 * 60 * 1000;

    private WebView web;
    private View overlay;
    private TextView statusTitle;
    private TextView statusBody;
    private Button retry;
    private Button showLog;
    private Button statusSettings;
    private View menu;
    private ScrollView overlayScroll;

    // 顶栏卡片
    private View header;
    private ImageView avatar;
    private TextView headerTitle;
    private View stateDot;
    private TextView stateText;
    private TextView gwPill;
    private View webCard;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> internalHosts = new HashSet<>();

    private volatile boolean loaded;
    private volatile boolean polling;
    private volatile ValueCallback<Uri[]> fileCallback;

    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.webKey(this);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        overlay = findViewById(R.id.overlay);
        overlayScroll = findViewById(R.id.overlay);
        statusTitle = findViewById(R.id.status_title);
        statusBody = findViewById(R.id.status_body);
        retry = findViewById(R.id.status_retry);
        showLog = findViewById(R.id.status_show_log);
        statusSettings = findViewById(R.id.status_settings);
        menu = findViewById(R.id.menu);
        header = findViewById(R.id.header);
        avatar = findViewById(R.id.avatar);
        headerTitle = findViewById(R.id.header_title);
        stateDot = findViewById(R.id.state_dot);
        stateText = findViewById(R.id.state_text);
        gwPill = findViewById(R.id.gw_pill);
        webCard = findViewById(R.id.web_card);

        statusBody.setMovementMethod(new ScrollingMovementMethod());

        setupImmersive();
        applyTheme();
        refreshHeader();
        ui.postDelayed(stateTicker, 1500);

        setupWebView(savedInstanceState);

        retry.setOnClickListener(v -> startGatewayAndWait());
        showLog.setOnClickListener(v -> showLog());
        statusSettings.setOnClickListener(v -> openSettings());
        menu.setOnClickListener(v -> showSheet());
        gwPill.setOnClickListener(v -> copyGateway());
        gwPill.setOnLongClickListener(v -> {
            openInBrowser();
            return true;
        });

        startGatewayAndWait();
        autoCheckUpdates();
    }

    /** 卡片上的状态点：运行中 / 启动中 / 已停止，两秒刷一次。 */
    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            refreshHeader();
            ui.postDelayed(this, 2000);
        }
    };

    private void refreshHeader() {
        if (gwPill == null) {
            return;
        }
        String host = Prefs.browserHost(this);
        gwPill.setText(host + ":" + GatewayService.gatewayPort(this));
        boolean alive = GatewayService.isAlive();
        if (alive && loaded) {
            stateDot.setBackgroundResource(R.drawable.dot_ok);
            stateText.setText(R.string.header_running);
        } else if (alive) {
            stateDot.setBackgroundResource(R.drawable.dot_ok);
            stateText.setText(R.string.header_starting);
        } else {
            stateDot.setBackgroundResource(R.drawable.dot_off);
            stateText.setText(overlay != null && overlay.getVisibility() == View.VISIBLE
                    ? R.string.header_starting : R.string.header_stopped);
        }
    }

    // ------------------------------------------------------------------
    // 沉浸：状态栏/导航栏透明，内容铺到屏幕边
    // ------------------------------------------------------------------

    private void setupImmersive() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        applySystemBars(Prefs.pageColor(this) != 0 ? Prefs.pageColor(this) : 0xFFF1EFEC);

        // 整个壳往下让出状态栏，顶栏就不会钻进时间/电量那一条里
        int sb = barHeight("status_bar_height");
        int nav = barHeight("navigation_bar_height");
        View shell = findViewById(R.id.shell);
        shell.setPadding(0, sb, 0, 0);
        overlay.setPadding(0, sb, 0, nav);

        // 内容区四角圆弧：容器与 WebView 各自带一个圆角 outline 再裁剪。
        // WebView 自己也会画到边界外（滚动条、浮动按钮），所以两边都裁。
        if (webCard != null) {
            android.view.ViewOutlineProvider round = new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(20));
                }
            };
            webCard.setOutlineProvider(round);
            webCard.setClipToOutline(true);
            web.setOutlineProvider(round);
            web.setClipToOutline(true);
            webCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.invalidateOutline());
        }
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

    /** 从系统里取莫奈强调色；Android 12 以下或者没开就是 0。 */
    static int accentOf(Context ctx) {
        if (!Prefs.monet(ctx)) {
            return 0;
        }
        int id = ctx.getResources().getIdentifier("system_accent1_400", "color", "android");
        if (id == 0) {
            return 0;
        }
        try {
            return ctx.getResources().getColor(id);
        } catch (Exception e) {
            return 0;
        }
    }

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

    private void applyTheme() {
        int accent = accentOf(this);
        int headerColor = Prefs.headerColor(this);
        int pageColor = Prefs.pageColor(this);

        int card = headerColor != 0 ? headerColor
                : (accent != 0 ? mix(accent, Color.WHITE, 0.80f) : 0xFFFBF2EE);
        int page = pageColor != 0 ? pageColor
                : (accent != 0 ? mix(accent, Color.WHITE, 0.94f) : 0xFFF1EFEC);
        boolean darkCard = darkColor(card);
        int ink = darkCard ? 0xFFF4F1EF : 0xFF2A2320;
        int subInk = darkCard ? 0xFFC9C3C0 : 0xFF6B625D;

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(card);
        cardBg.setCornerRadius(dp(22));
        header.setBackground(cardBg);

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(mix(card, Color.WHITE, 0.45f));
        pill.setCornerRadius(dp(16));
        pill.setStroke(dp(1), accent != 0 ? accent : 0xFFE7CCC1);
        gwPill.setBackground(pill);
        gwPill.setTextColor(accent != 0 ? mix(accent, Color.BLACK, 0.35f)
                : (darkCard ? 0xFFE6D8D0 : 0xFF8A4B32));

        GradientDrawable av = new GradientDrawable();
        av.setShape(GradientDrawable.OVAL);
        av.setColor(mix(card, accent != 0 ? accent : 0xFFF5E4DC,
                accent != 0 ? 0.35f : 0f));
        avatar.setBackground(av);

        headerTitle.setTextColor(ink);
        stateText.setTextColor(subInk);
        if (menu instanceof TextView) {
            ((TextView) menu).setTextColor(subInk);
        }

        // 内容区：圆角底 + 四周底色，两个都跟着「内容背景色」走
        GradientDrawable content = new GradientDrawable();
        content.setColor(page);
        content.setCornerRadius(dp(20));
        if (webCard != null) {
            webCard.setBackground(content);
        }
        findViewById(R.id.root).setBackgroundColor(page);
        overlay.setBackgroundColor(page);

        // 换色后状态栏图标的深浅也要跟着翻
        applySystemBars(page);
        paintPageBackground();
    }

    /**
     * 把「内容区背景色」刷进页面。
     *
     * <p>只改容器底色没用 —— WebView 里那页 magpie 自己铺了一层不透明背景，
     * 我们的颜色只从圆角缝里露出来。所以要往页面里注入一条样式，让
     * {@code html/body} 的背景透明，露出 WebView 自己的底色。
     * 选「跟随系统」时把注入的样式摘掉，页面恢复原样。
     */
    private void paintPageBackground() {
        if (web == null) {
            return;
        }
        int custom = Prefs.pageColor(this);
        int accent = accentOf(this);
        int base = custom != 0 ? custom
                : (accent != 0 ? mix(accent, Color.WHITE, 0.94f) : 0);
        web.setBackgroundColor(base != 0 ? base : 0xFFFFFFFF);

        // 页面里有些元素（浮动按钮、滚动条）会画到圆角外面，撑一块同色遮罩压住四角
        if (webCard instanceof android.widget.FrameLayout) {
            ((android.widget.FrameLayout) webCard).setForeground(
                    base != 0 && custom != 0 ? new CornerMask(base, dp(20)) : null);
        }

        if (!loaded) {
            return; // 页面还没加载好，onPageFinished 里会再来一次
        }
        if (custom == 0) {
            inject(null); // 跟随系统/莫奈：把注入的样式摘掉，页面恢复原样
            return;
        }
        inject(pageCss(custom));
    }

    /**
     * 覆盖 magpie 页面自己的主题变量。
     *
     * <p>页面全是 CSS 变量（--bg / --card / --bar / --pill / --fg …），只把
     * html/body 弄透明的话，那些自己带白底的元素（工具栏、卡片、底部条）仍是白的。
     * 直接把这些变量按用户选的颜色重算一遍，整页才会真的一块变色；顺带把文字色
     * 按背景深浅翻过来，不至于看不清。
     */
    private String pageCss(int color) {
        boolean dark = darkColor(color);
        // 卡片靠近白、底层留用户选的颜色 —— 整页有色调，但卡片还是卡片
        int card = dark ? mix(color, Color.BLACK, 0.30f) : mix(color, Color.WHITE, 0.86f);
        int card2 = dark ? mix(color, Color.BLACK, 0.18f) : mix(color, Color.WHITE, 0.74f);
        int bar = dark ? mix(color, Color.BLACK, 0.40f) : mix(color, Color.WHITE, 0.62f);
        String fg = dark ? "#f2f2f4" : "#1c1c1e";
        String muted = dark ? "#a9a9b0" : "#6b6b70";
        String faint = dark ? "#7c7c84" : "#9a9a9e";
        String line = dark ? "rgba(255,255,255,0.14)" : "rgba(0,0,0,0.09)";
        return ":root,html,body{"
                + "--bg:" + hex(color) + "!important;"
                + "--card:" + hex(card) + "!important;"
                + "--card-2:" + hex(card2) + "!important;"
                + "--bar:" + hex(bar) + "!important;"
                + "--pill:" + hex(bar) + "!important;"
                + "--fg:" + fg + "!important;"
                + "--muted:" + muted + "!important;"
                + "--faint:" + faint + "!important;"
                + "--line:" + line + "!important;"
                + "}"
                + "html{background:" + hex(color) + "!important}"
                + "body{background-color:" + hex(color) + "!important}";
    }

    private static String hex(int c) {
        return String.format("#%06X", 0xFFFFFF & c);
    }

    /** 把一段 CSS 塞进页面；传 null 表示撤掉之前注入的。 */
    private void inject(String css) {
        StringBuilder js = new StringBuilder("(function(){var id='magpie-shell-bg';"
                + "var o=document.getElementById(id);if(o){o.parentNode.removeChild(o);}");
        if (css != null) {
            js.append("var s=document.createElement('style');s.id=id;s.textContent=")
                    .append(quote(css))
                    .append(";(document.head||document.documentElement).appendChild(s);");
        }
        js.append("})();");
        try {
            web.evaluateJavascript(js.toString(), null);
        } catch (Exception e) {
            Log.w(TAG, "注入页面主题失败: " + e);
        }
    }

    /** 四角遮罩：把圆角外面的溢出内容用底色盖住。 */
    private static final class CornerMask extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final float radius;

        CornerMask(int color, float radius) {
            this.radius = radius;
            paint.setColor(color);
            paint.setAntiAlias(true);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            android.graphics.Path all = new android.graphics.Path();
            all.addRect(b.left, b.top, b.right, b.bottom, android.graphics.Path.Direction.CW);
            android.graphics.Path rounded = new android.graphics.Path();
            rounded.addRoundRect(new android.graphics.RectF(b), radius, radius,
                    android.graphics.Path.Direction.CW);
            all.op(rounded, android.graphics.Path.Op.DIFFERENCE);
            canvas.drawPath(all, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** JS 字符串字面量（不引 JSON 依赖，手写就够了）。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
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

    /** 点胶囊：复制 http://127.0.0.1:3425/v1。 */
    private void copyGateway() {
        String url = "http://" + Prefs.browserHost(this) + ":"
                + GatewayService.gatewayPort(this) + "/v1";
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("magpie", url));
        }
        Toast.makeText(this, getString(R.string.header_copied, url), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页回来可能换了背景/莫奈色，重画一遍
        applyTheme();
        refreshHeader();
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

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(heartbeat);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(heartbeat);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
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
                // a port or a host changed: the running gateway is on the
                // old one, so it goes down and a new one comes up
                GatewayService.resetState();
                loaded = false;
                GatewayService.restart(this);
                startGatewayAndWait();
            }
        } else if (requestCode == REQ_UPDATE) {
            if (resultCode == RESULT_OK) {
                // 更新页换掉了二进制并重启了网关：丢掉旧页面，等新进程上线
                loaded = false;
                GatewayService.resetState();
                startGatewayAndWait();
            }
        } else if (requestCode == REQ_FILE) {
            ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            if (cb != null) {
                cb.onReceiveValue(data != null && data.getData() != null
                        ? new Uri[]{data.getData()} : null);
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
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        applyRendering();

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

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (isInternal(u)) {
                    return false;
                }
                openExternal(u);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (isInternal(request.getUrl().toString())) {
                    showStatus();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (isInternal(request.getUrl().toString()) && response.getStatusCode() >= 500) {
                    showStatus();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isInternal(url)) {
                    loaded = true;
                    hideOverlay();
                    paintPageBackground();
                    if (!GatewayService.isAlive()) {
                        showStatus();
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
                if (extra != null && !extra.isEmpty()) {
                    openExternal(extra);
                    return true;
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
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                if (params != null) {
                    String[] mime = params.getAcceptTypes();
                    if (mime != null && mime.length > 0) {
                        intent.setType(mime[0]);
                    }
                } else {
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, REQ_FILE);
                } catch (Exception e) {
                    Log.w(TAG, "no file picker: " + e);
                    callback.onReceiveValue(null);
                    fileCallback = null;
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

    private boolean isInternal(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) {
            return false;
        }
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("0.0.0.0")
                || host.equals("::1") || host.equals("magpie") || host.endsWith(".magpie")
                || internalHosts.contains(host);
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
        try {
            Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            startActivity(i);
            return;
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

    private void loadPage() {
        if (loaded) {
            return;
        }
        loaded = true;
        hideOverlay();
        String url = GatewayService.webUrl(this);
        Log.i(TAG, "loading " + url);
        web.loadUrl(url);
    }

    private void hideOverlay() {
        if (overlay != null && overlay.getVisibility() != View.GONE) {
            overlay.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // the overlay
    // ------------------------------------------------------------------

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

    private void showLog() {
        String tail = GatewayService.lastLog(0);
        if (tail == null || tail.isEmpty()) {
            tail = "(还没有输出)";
        }
        final String log = tail;
        new AlertDialog.Builder(this)
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
                .show();
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivityForResult(i, REQ_SETTINGS);
    }

    private void openUpdate() {
        Intent i = new Intent(this, UpdateActivity.class);
        startActivityForResult(i, REQ_UPDATE);
    }

    /** 右上角 ⋮：设置、检查更新。 */
    /** 右上角三个点：底部弹层，和那张参考图一样的排版。 */
    private void showSheet() {
        final Dialog d = new Dialog(this, R.style.Theme_Magpie_Sheet);
        d.setContentView(R.layout.sheet_menu);

        TextView sub = d.findViewById(R.id.sheet_subtitle);
        String host = Prefs.browserHost(this);
        sub.setText(getString(R.string.sheet_subtitle,
                host + ":" + GatewayService.gatewayPort(this),
                host + ":" + GatewayService.webPort(this)));

        LinearLayout box = d.findViewById(R.id.sheet_items);
        final String api = "http://" + host + ":" + GatewayService.gatewayPort(this) + "/v1";

        addSheetItem(box, d, "\u21bb", getString(R.string.act_reload),
                getString(R.string.act_reload_sub), this::reloadPage);
        addSheetItem(box, d, "\u2197", getString(R.string.act_browser),
                getString(R.string.act_browser_sub), this::openInBrowser);
        addSheetItem(box, d, "\u29c9", getString(R.string.act_copy),
                getString(R.string.act_copy_sub, api), this::copyGateway);
        addSheetItem(box, d, "\u27f3", getString(R.string.act_restart),
                getString(R.string.act_restart_sub), this::restartGateway);
        addSheetItem(box, d, "\u25a0", getString(R.string.act_stop),
                getString(R.string.act_stop_sub), this::stopGateway);
        addSheetItem(box, d, "\u25a4", getString(R.string.act_log),
                getString(R.string.act_log_sub), this::showLog);
        addSheetItem(box, d, "\u2699", getString(R.string.act_settings),
                getString(R.string.act_settings_sub), this::openSettings);
        addSheetItem(box, d, "\u21e9", getString(R.string.act_update),
                getString(R.string.act_update_sub), this::openUpdate);
        addSheetItem(box, d, "\u26a1", getString(R.string.act_battery),
                getString(R.string.act_battery_sub), this::ignoreBattery);
        addSheetItem(box, d, "\u24d8", getString(R.string.act_about),
                getString(R.string.act_about_sub), this::showAbout);

        LinearLayout sheetRoot = d.findViewById(R.id.sheet_root);
        sheetRoot.setPadding(dp(18), dp(10), dp(18), dp(18) + barHeight("navigation_bar_height"));

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    /** 一行：方块图标 + 标题 + 副标题，点下去先关弹层再做事。 */
    private void addSheetItem(LinearLayout box, final Dialog d, String icon,
                              String title, String sub, Runnable act) {
        int accent = accentOf(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView ic = new TextView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
        ic.setLayoutParams(ip);
        ic.setGravity(Gravity.CENTER);
        ic.setText(icon);
        ic.setTextSize(18);
        ic.setTextColor(accent != 0 ? mix(accent, Color.BLACK, 0.30f) : 0xFF7A5A4A);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent != 0 ? mix(accent, Color.WHITE, 0.78f) : 0xFFF8EEE9);
        bg.setCornerRadius(dp(14));
        ic.setBackground(bg);
        row.addView(ic);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(14);
        texts.setLayoutParams(tp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(0xFF2A2320);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12.5f);
        s.setTextColor(0xFF8B847F);
        texts.addView(t);
        texts.addView(s);
        row.addView(texts);

        row.setOnClickListener(v -> {
            d.dismiss();
            act.run();
        });
        box.addView(row);
    }

    private void reloadPage() {
        loaded = true;
        web.reload();
    }

    private void restartGateway() {
        GatewayService.resetState();
        loaded = false;
        GatewayService.restart(this);
        startGatewayAndWait();
        Toast.makeText(this, R.string.act_restart, Toast.LENGTH_SHORT).show();
    }

    private void stopGateway() {
        GatewayService.stop(this);
        loaded = false;
        Toast.makeText(this, R.string.act_stop, Toast.LENGTH_SHORT).show();
        showStatus();
    }

    private void ignoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "系统没有这个设置页", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 关于：外壳版本、内核版本、官方仓库、移植作者。
     * 链接行直接可以点，点不开就把地址用 Toast 贴出来。
     */
    private void showAbout() {
        View v = getLayoutInflater().inflate(R.layout.dialog_about, null);
        final TextView ver = v.findViewById(R.id.about_version);
        String shell = "";
        try {
            shell = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        final String shellVer = shell;
        ver.setText(getString(R.string.about_ver, shellVer, Prefs.installedVersion(this)));

        // 内核版本要跑一下二进制，别卡着对话框
        new Thread(() -> {
            final String core = Updater.localVersion(this);
            if (core != null && core.length() > 0) {
                ui.post(() -> ver.setText(getString(R.string.about_ver, shellVer, core)));
            }
        }, "magpie-about").start();

        v.findViewById(R.id.about_repo).setOnClickListener(x ->
                openUrl("https://github.com/yetone/magpie"));
        v.findViewById(R.id.about_author_name).setOnClickListener(x ->
                openUrl("https://www.coolapk.com/u/1642567"));
        v.findViewById(R.id.about_author_url).setOnClickListener(x ->
                openUrl("https://www.coolapk.com/u/1642567"));
        v.findViewById(R.id.about_notice).setOnClickListener(x -> {
            try (java.io.InputStream in = getAssets().open("MAGPIE_LICENSE.txt");
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                new AlertDialog.Builder(this)
                        .setTitle("magpie · MIT License")
                        .setMessage(out.toString("UTF-8"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } catch (Exception e) {
                Toast.makeText(this, "许可证读取失败", Toast.LENGTH_SHORT).show();
            }
        });

        new AlertDialog.Builder(this)
                .setView(v)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
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

    private void applyRendering() {
        WebSettings s = web.getSettings();
        boolean desktop = Prefs.desktopMode(this);
        s.setUserAgentString(desktop ? DESKTOP_UA : null);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setSupportZoom(desktop);
        s.setBuiltInZoomControls(desktop);
        s.setDisplayZoomControls(false);
    }

    private void toggleDesktop() {
        boolean on = !Prefs.desktopMode(this);
        Prefs.setDesktopMode(this, on);
        applyRendering();
        web.reload();
        Toast.makeText(this, on ? R.string.toast_desktop : R.string.toast_narrow,
                Toast.LENGTH_SHORT).show();
    }

    private void openInBrowser() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GatewayService.webUrl(this))));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 后台静默问一次有没有新版（一天一次），有就在界面上提一句。
     * 不自动下载：70 MB 得用户自己点，別在人家的流量上自作主张。
     */
    private void autoCheckUpdates() {
        long since = System.currentTimeMillis() - Prefs.lastCheck(this);
        if (since < AUTO_CHECK_EVERY) {
            String known = Prefs.knownLatest(this);
            if (known.length() > 0) {
                ui.post(() -> Toast.makeText(this,
                        "magpie " + known + " 可以更新了（右上角 ⋮）", Toast.LENGTH_LONG).show());
            }
            return;
        }
        new Thread(() -> {
            try {
                Updater.Release rel = Updater.latest(this);
                String local = Updater.localVersion(this);
                if (local.length() == 0) {
                    local = Prefs.installedVersion(this);
                }
                boolean newer = Updater.newer(rel.version, local);
                Prefs.noteChecked(this, newer ? rel.version : "");
                if (newer && !Updater.isRunning()) {
                    ui.post(() -> Toast.makeText(this,
                            "magpie " + rel.version + " 可以更新了（右上角 ⋮）", Toast.LENGTH_LONG).show());
                }
            } catch (IOException e) {
                Log.i(TAG, "静默检查更新没成功: " + e.getMessage());
            }
        }, "magpie-auto-update").start();
    }
}
