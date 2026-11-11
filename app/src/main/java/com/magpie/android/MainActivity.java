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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private TextView lanPill;
    private View webCard;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> internalHosts = new HashSet<>();

    private volatile boolean loaded;
    private volatile boolean polling;
    private volatile ValueCallback<Uri[]> fileCallback;
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
        lanPill = findViewById(R.id.lan_pill);
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
        // MIUI 的侧边返回手势把屏幕右缘那一条算作返回区，右上角的 ⋮ 有一半落进去，
        // 点下去被系统当成返回、App 直接退出。报一块手势排除区把这块要回来。
        excludeFromGestures(menu);
        gwPill.setOnClickListener(v -> copyGateway());
        gwPill.setOnLongClickListener(v -> {
            openInBrowser();
            return true;
        });
        lanPill.setOnClickListener(v -> showLanDialog());

        startGatewayAndWait();
        autoCheckUpdates();
        autoCheckShellUpdate();
        // 首次启动的后台保活引导：通知权限、电池优化、厂商自启动步骤
        ui.postDelayed(() -> BackgroundGuide.maybeShowOnLaunch(this), 2500);
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
        if (lanPill != null) {
            // 入口始终只写「局域网」；地址、密钥都放在弹窗里，顶栏不被长 IP 撑大。
            lanPill.setVisibility(View.VISIBLE);
            lanPill.setText(Prefs.lan(this) ? R.string.lan_pill_on : R.string.lan_pill_off);
        }
        boolean alive = GatewayService.isAlive();
        // 让前台通知跟上真实状态：别再出现「App 显示运行中、通知显示正在启动」
        GatewayService.syncNotification(this, alive);
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
        applySystemBars(M3.current(this)[M3.SURFACE]);

        // 整个壳往下让出状态栏，顶栏就不会钻进时间/电量那一条里
        int sb = barHeight("status_bar_height");
        int nav = barHeight("navigation_bar_height");
        View shell = findViewById(R.id.shell);
        shell.setPadding(0, sb, 0, 0);
        overlay.setPadding(0, sb, 0, nav);

        // 内容区四角圆弧：容器与 WebView 各自带一个圆角 outline 再裁剪。
        // WebView 自己也会画到边界外（滚动条、浮动按钮），所以两边都裁。
        if (webCard != null) {
            webCard.setClipToOutline(true);
            applyWebOutline();
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
     * 把整套 M3 令牌刷到壳上：顶栏用 surface container、内容卡用
     * surface container lowest + outline variant 细边、chip 用 assist chip 样式。
     * 页面本身的深浅仍由 magpie 自己的设置决定，壳不越权改它。
     */
    private void applyTheme() {
        int[] s = M3.current(this);
        int surface = s[M3.SURFACE];
        int onSurface = s[M3.ON_SURFACE];
        int onVariant = s[M3.ON_SURFACE_VARIANT];

        // 方案二：顶栏、内容区容器都透明，透出 root 的 surface，和 magpie 页面主背景（--bg=surface）同色。
        header.setBackgroundColor(Color.TRANSPARENT);

        GradientDrawable chip = rounded(s[M3.SURFACE], 16);
        chip.setStroke(dp(1), s[M3.OUTLINE_VARIANT]);
        gwPill.setBackground(chip);
        gwPill.setTextColor(onVariant);

        // 「局域网」也是独立的 assist chip：有明确外框，不是裸文字。
        GradientDrawable lanChip = rounded(s[M3.SECONDARY_CONTAINER], 16);
        lanChip.setStroke(dp(1), s[M3.OUTLINE_VARIANT]);
        lanPill.setBackground(lanChip);
        lanPill.setTextColor(s[M3.ON_SECONDARY_CONTAINER]);

        GradientDrawable av = new GradientDrawable();
        av.setShape(GradientDrawable.OVAL);
        av.setColor(s[M3.PRIMARY_CONTAINER]);
        avatar.setBackground(av);

        headerTitle.setTextColor(onSurface);
        stateText.setTextColor(onVariant);
        if (menu instanceof TextView) {
            ((TextView) menu).setTextColor(onVariant);
        }

        if (webCard != null) {
            webCard.setBackgroundColor(Color.TRANSPARENT);
        }
        findViewById(R.id.root).setBackgroundColor(surface);
        overlay.setBackgroundColor(surface);

        statusTitle.setTextColor(onSurface);
        statusBody.setTextColor(onVariant);
        tonalButton(retry, s);
        tonalButton(showLog, s);
        tonalButton(statusSettings, s);

        applySystemBars(surface);
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
        int[] c = M3.current(this);
        int surface = c[M3.SURFACE];
        web.setBackgroundColor(surface);
        if (webCard instanceof android.widget.FrameLayout) {
            ((android.widget.FrameLayout) webCard).setForeground(new CornerMask(surface, dp(24)));
        }
        if (!loaded) {
            return; // 页面还没加载好，onPageFinished 里会再来一次
        }
        inject(pageCss(c));
        injectNavReorder();
    }

    /**
     * 手机底栏顺序：供应商 / 路由 / 用量 / 插件放底栏，其余（会话、资源库、
     * 网关、脱敏、路由组…）进「更多」。
     *
     * <p>底栏由内核的 phonetabs.js 按 #nav 里 button 的文档顺序切成「前 4 个 +
     * 更多」。壳不复制网页、不改内核，只把 button 按顺序重排，phonetabs 的
     * MutationObserver 会自己重新分组。顺序固定，重复执行幂等，官方新增页面
     * 若不在前 4 个里会自动落进「更多」。
     */
    private void injectNavReorder() {
        String js = "(function(){"
                + "function reorder(){"
                + "var nav=document.getElementById('nav');"
                + "if(!nav)return;"
                + "var order=['providers','routing','usage','plugins'];"
                + "var btns=Array.prototype.slice.call(nav.querySelectorAll(':scope > button[data-view]'));"
                + "if(!btns.length)return;"
                + "var byId={};"
                + "btns.forEach(function(b){byId[b.getAttribute('data-view')]=b;});"
                + "var rest=btns.filter(function(b){return order.indexOf(b.getAttribute('data-view'))<0;});"
                + "var sorted=order.map(function(id){return byId[id];}).filter(Boolean).concat(rest);"
                + "var more=nav.querySelector('.nav-more');"
                + "sorted.forEach(function(b){if(more){nav.insertBefore(b,more);}else{nav.appendChild(b);}});"
                + "}"
                + "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',reorder);}else{reorder();}"
                + "setTimeout(reorder,200);setTimeout(reorder,800);setTimeout(reorder,2000);"
                + "})();";
        try {
            web.evaluateJavascript(js, null);
        } catch (Exception e) {
            Log.w(TAG, "重排底栏失败: " + e);
        }
    }

    /**
     * 覆盖 magpie 页面自己的主题变量。
     *
     * <p>页面全是 CSS 变量（--bg / --card / --bar / --pill / --fg …），只把
     * html/body 弄透明的话，那些自己带白底的元素（工具栏、卡片、底部条）仍是白的。
     * 直接把这些变量按用户选的颜色重算一遍，整页才会真的一块变色；顺带把文字色
     * 按背景深浅翻过来，不至于看不清。
     */
    private String pageCss(int[] c) {
        String bg = hex(c[M3.SURFACE]);
        return ":root,html,body{"
                + "--bg:" + bg + "!important;"
                + "--card:" + hex(c[M3.SURFACE_CONTAINER_LOW]) + "!important;"
                + "--card-2:" + hex(c[M3.SURFACE_CONTAINER]) + "!important;"
                + "--bar:" + hex(c[M3.SURFACE_CONTAINER_HIGH]) + "!important;"
                + "--pill:" + hex(c[M3.PRIMARY_CONTAINER]) + "!important;"
                + "--fg:" + hex(c[M3.ON_SURFACE]) + "!important;"
                + "--muted:" + hex(c[M3.ON_SURFACE_VARIANT]) + "!important;"
                + "--faint:" + hex(c[M3.OUTLINE]) + "!important;"
                + "--line:" + hex(c[M3.OUTLINE_VARIANT]) + "!important;"
                + "}"
                + "html{background:" + bg + "!important}"
                + "body{background-color:" + bg + "!important}"
                // 手机上这两条是 position:sticky：一条钉在 view 顶部（资源库/插件页的
                // tab 行），一条钉在底部（插件页的「有想装的」输入条）。钉住时它们用
                // 半透明毛玻璃铺底，滚动中会和卡片文字叠在一起看不清。改成 static，
                // 让它们跟着内容滚走；撑底的 ::before 一并去掉，否则绝对定位会乱飘。
                + ".lib-head,.pm-manual{position:static!important}"
                + ".lib-head::before,.pm-manual::before{display:none!important}"
                // 手机底栏（magpie web 的 tab bar）：每个 tab 做成胶囊，
                // 选中项用主题色胶囊底，一眼看出停留在哪个页面
                + "body.web:not(.panel) .top #nav > button{border-radius:16px!important;height:42px!important;margin:4px 3px!important;padding:2px 4px!important}"
                + "body.web:not(.panel) .top #nav > button.on,"
                + "body.web:not(.panel) .top #nav > .nav-more.on{background:var(--pill)!important;color:var(--fg)!important}";
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
            rounded.addRoundRect(new android.graphics.RectF(b),
                    new float[]{0, 0, 0, 0, radius, radius, radius, radius},
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
        appInBackground = false;
        ui.removeCallbacks(releaseWebTask);
        if (webReleased && web == null) {
            rebuildWeb(false);
            webReleased = false;
        }
        // 从设置页回来可能换了背景/莫奈色，重画一遍
        applyTheme();
        String patchNote = GatewayService.takePatchNote();
        if (patchNote != null && !patchNote.isEmpty()) {
            Toast.makeText(this, patchNote, Toast.LENGTH_LONG).show();
        }
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

    /**
     * 通知点击在 singleTask Activity 已存在时走这里，而不是再创建一个窗口。
     * 只刷新壳状态；不重启网关、不重新 loadUrl，避免打断正在进行的页面操作。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshHeader();
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
        ui.removeCallbacks(stateTicker);
        super.onDestroy();
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
                    hideOverlay();
                    paintPageBackground();
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

    /**
     * 到时只释放页面：不 stop/restart 服务、不碰 magpie ELF、不影响 3425 上的请求。
     * 回到前台时 onResume 发现 web=null，再创建新的 WebView。
     */
    private void releaseWebForBackground() {
        if (!appInBackground || web == null || webRebuilding || isChangingConfigurations()) {
            return;
        }
        Log.i(TAG, "后台超时，释放 WebView；网关继续运行");
        try {
            ((ViewGroup) webCard).removeView(web);
            web.setWebViewClient(null);
            web.destroy();
        } catch (Exception e) {
            Log.w(TAG, "释放后台 WebView 失败", e);
        }
        web = null;
        webReleased = true;
    }

    /** 内容区四角圆弧：与顶栏拼卡后只裁底部两角。 */
    private void applyWebOutline() {
        final float r = dp(20);
        android.view.ViewOutlineProvider round = new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                android.graphics.Path p = new android.graphics.Path();
                p.addRoundRect(0, 0, v.getWidth(), v.getHeight(),
                        new float[]{0, 0, 0, 0, r, r, r, r},
                        android.graphics.Path.Direction.CW);
                o.setConvexPath(p);
            }
        };
        if (webCard != null) {
            webCard.setOutlineProvider(round);
            webCard.setClipToOutline(true);
        }
        if (web != null) {
            web.setOutlineProvider(round);
            web.setClipToOutline(true);
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
            applyWebOutline();
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

    /** 让系统手势别吃这块视图的触摸（API 29+，旧版本本来也没手势导航）。 */
    private void excludeFromGestures(View v) {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        v.post(() -> {
            java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>(1);
            rects.add(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()));
            v.setSystemGestureExclusionRects(rects);
        });
    }

    /**
     * Marvis 登录在 Android 上没有能 spawn 的 Chromium：插件（打了补丁后）
     * 会等 HOME/marvis-login-url.txt 出现，这里就是写它的地方 —— 用户在
     * 浏览器里登录完，把地址栏那条回调 URL 贴进来。
     */
    private void showMarvisPaste() {
        int[] c = M3.current(this);
        int pad = dp(20);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(4), pad, 0);

        // 补丁状态就摆在输入框上面：插件在不在、版本、补丁有没有打上，
        // 用户（和分享出去以后的别人）第一眼就能看出问题在哪。
        TextView status = new TextView(this);
        status.setTextSize(11.5f);
        status.setText(GatewayService.patchStatus(this));
        status.setTextColor(c[M3.ON_SURFACE_VARIANT]);
        box.addView(status);

        TextView help = new TextView(this);
        help.setText(R.string.marvis_paste_help);
        help.setTextSize(12.5f);
        help.setTextColor(c[M3.ON_SURFACE]);
        help.setPadding(0, dp(10), 0, 0);
        box.addView(help);

        final android.widget.EditText input = new android.widget.EditText(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        input.setLayoutParams(lp);
        input.setHint(R.string.marvis_paste_hint);
        input.setTextSize(12.5f);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        GradientDrawable field = rounded(c[M3.SURFACE_CONTAINER_HIGHEST], 12);
        field.setStroke(dp(1), c[M3.OUTLINE_VARIANT]);
        input.setBackground(field);
        input.setTextColor(c[M3.ON_SURFACE]);
        input.setHintTextColor(c[M3.ON_SURFACE_VARIANT]);
        int inPad = dp(12);
        input.setPadding(inPad, inPad, inPad, inPad);
        box.addView(input);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.marvis_paste_title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.marvis_paste_ok, null)
                .create();
        styleDialog(dlg);
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!url.startsWith("http") || !url.contains("code=")) {
                Toast.makeText(this, R.string.marvis_paste_bad, Toast.LENGTH_LONG).show();
                return;
            }
            if (writeMarvisCallback(url)) {
                dlg.dismiss();
                Toast.makeText(this, R.string.marvis_paste_done, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.marvis_paste_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 局域网共享信息：开着 → 局域网 URL + 掩码密钥（按钮复制对应的明文）；
     * 没开 → 说明 + 「开启局域网共享」按钮（开壳的绑定开关并重启网关）。
     * 密钥明文不显示在界面上，只有"复制"动作把它放进剪贴板。
     */
    private void showLanDialog() {
        int[] c = M3.current(this);
        boolean shellLan = Prefs.lan(this);
        String ip = LanInfo.localIp();
        String secret = LanInfo.lanSecret(this);
        boolean shared = shellLan && !secret.isEmpty() && !ip.isEmpty();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(4), pad, 0);

        TextView hint = new TextView(this);
        hint.setTextSize(12.5f);
        hint.setTextColor(c[M3.ON_SURFACE_VARIANT]);
        hint.setText(shared ? R.string.lan_on_hint : R.string.lan_off_hint);
        box.addView(hint);

        TextView addrLabel = label(c, R.string.lan_addr_label, shared && !ip.isEmpty());
        box.addView(addrLabel);
        TextView addr = new TextView(this);
        addr.setTextSize(13.5f);
        addr.setTypeface(android.graphics.Typeface.MONOSPACE);
        addr.setTextIsSelectable(true);
        addr.setTextColor(shared ? c[M3.PRIMARY] : c[M3.ON_SURFACE_VARIANT]);
        addr.setText(shared
                ? "http://" + ip + ":" + GatewayService.gatewayPort(this) + "/v1"
                : getString(R.string.lan_addr_missing));
        addr.setPadding(0, dp(3), 0, 0);
        box.addView(addr);

        TextView keyLabel = label(c, R.string.lan_key_label, !secret.isEmpty());
        keyLabel.setPadding(0, dp(12), 0, 0);
        box.addView(keyLabel);
        TextView key = new TextView(this);
        key.setTextSize(13.5f);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setTextIsSelectable(true);
        key.setTextColor(secret.isEmpty() ? c[M3.ON_SURFACE_VARIANT] : c[M3.PRIMARY]);
        key.setText(secret.isEmpty() ? getString(R.string.lan_key_missing) : LanInfo.mask(secret));
        key.setPadding(0, dp(3), 0, 0);
        box.addView(key);

        TextView sec = new TextView(this);
        sec.setTextSize(11f);
        sec.setTextColor(c[M3.OUTLINE]);
        sec.setText(R.string.lan_security);
        sec.setPadding(0, dp(12), 0, 0);
        box.addView(sec);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.lan_title)
                .setView(box)
                .setPositiveButton(shared ? R.string.lan_copy_addr : R.string.lan_turn_on, null)
                .setNeutralButton(secret.isEmpty() ? android.R.string.cancel : R.string.lan_copy_key, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        styleDialog(dlg);
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (shared) {
                copyText(addr.getText().toString());
                Toast.makeText(this, R.string.lan_copied, Toast.LENGTH_SHORT).show();
                return;
            }
            // 不在这里悄悄改开关：用户需要先在壳的设置页开放监听，
            // 再由 magpie 网页生成/启用默认密钥；弹窗只负责说明这两个步骤。
            dlg.dismiss();
            Toast.makeText(this, R.string.lan_opening, Toast.LENGTH_LONG).show();
        });
        if (!secret.isEmpty()) {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                copyText(secret);
                Toast.makeText(this, R.string.lan_copied, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private TextView label(int[] c, int text, boolean bright) {
        TextView t = new TextView(this);
        t.setTextSize(12f);
        t.setText(text);
        t.setTextColor(c[bright ? M3.ON_SURFACE : M3.ON_SURFACE_VARIANT]);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("magpie", text));
        }
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
        addSheetItem(box, d, "\u21c4", Prefs.desktopMode(this)
                        ? getString(R.string.menu_narrow_layout) : getString(R.string.menu_desktop_layout),
                getString(R.string.menu_desktop_sub), this::toggleDesktop);
        addSheetItem(box, d, "\u21e9", getString(R.string.act_update),
                getString(R.string.act_update_sub), this::openUpdate);
        addSheetItem(box, d, "\u2b07", getString(R.string.act_shell_update),
                getString(R.string.act_shell_update_sub), this::checkShellUpdate);
        addSheetItem(box, d, "\u270e", getString(R.string.act_marvis_paste),
                getString(R.string.act_marvis_paste_sub), this::showMarvisPaste);
        addSheetItem(box, d, "\u26a1", getString(R.string.act_battery),
                getString(R.string.act_battery_sub), this::ignoreBattery);
        addSheetItem(box, d, "\u24d8", getString(R.string.act_about),
                getString(R.string.act_about_sub), this::showAbout);

        LinearLayout sheetRoot = d.findViewById(R.id.sheet_root);
        int[] sc = M3.current(this);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(sc[M3.SURFACE_CONTAINER_LOW]);
        float sr = dp(28);
        sheetBg.setCornerRadii(new float[]{sr, sr, sr, sr, 0, 0, 0, 0});
        sheetRoot.setBackground(sheetBg);
        ((TextView) d.findViewById(R.id.sheet_title)).setTextColor(sc[M3.ON_SURFACE]);
        d.findViewById(R.id.sheet_handle).setBackground(rounded(sc[M3.OUTLINE_VARIANT], 2));
        sub.setTextColor(sc[M3.ON_SURFACE_VARIANT]);
        sheetRoot.setPadding(dp(16), dp(10), dp(16), dp(16) + barHeight("navigation_bar_height"));

        Window w = d.getWindow();
        if (w != null) {
            // 小屏内容超高时限制到屏幕 85%，让列表区（ScrollView）滚动；否则自适应内容。
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            sheetRoot.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int contentH = sheetRoot.getMeasuredHeight();
            int maxH = (int) (screenH * 0.85f);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    contentH > maxH ? maxH : WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    /** M3 list item：56dp 高，44dp 圆角图标底（secondary container），两行文字。 */
    private void addSheetItem(LinearLayout box, final Dialog d, String icon,
                              String title, String sub, Runnable act) {
        int[] c = M3.current(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        // 注意：android.R.attr.selectableItemBackground 是 attr 的 id，不是 drawable
        // 的 id，直接塞给 setBackgroundResource 会抛 NotFoundException 把整个 Activity
        // 带崩（点 ⋮ 闪退的根因）。要先在主题里解析出真正的 resourceId。
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                && ripple.resourceId != 0) {
            row.setBackgroundResource(ripple.resourceId);
        }

        TextView ic = new TextView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(44), dp(44));
        ic.setLayoutParams(ip);
        ic.setGravity(Gravity.CENTER);
        ic.setText(icon);
        ic.setTextSize(18);
        ic.setTextColor(c[M3.ON_SECONDARY_CONTAINER]);
        ic.setBackground(rounded(c[M3.SECONDARY_CONTAINER], 14));
        row.addView(ic);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(16);
        texts.setLayoutParams(tp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(c[M3.ON_SURFACE]);
        TextView sb = new TextView(this);
        sb.setText(sub);
        sb.setTextSize(12);
        sb.setTextColor(c[M3.ON_SURFACE_VARIANT]);
        texts.addView(t);
        texts.addView(sb);
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
        int[] c = M3.current(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_about, null);
        final TextView ver = v.findViewById(R.id.about_version);
        String shell = "";
        try {
            shell = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        final String shellVer = shell;
        ver.setText(getString(R.string.about_ver, shellVer, Prefs.installedVersion(this)));

        ((TextView) v.findViewById(R.id.about_title)).setTextColor(c[M3.ON_SURFACE]);
        ver.setTextColor(c[M3.ON_SURFACE_VARIANT]);
        int[][] labels = {{R.id.about_upstream_label, M3.ON_SURFACE_VARIANT},
                {R.id.about_author_label, M3.ON_SURFACE_VARIANT},
                {R.id.about_license_text, M3.ON_SURFACE_VARIANT},
                {R.id.about_ai, M3.ON_SURFACE_VARIANT},
                {R.id.about_unofficial, M3.OUTLINE}};
        for (int[] pair : labels) {
            ((TextView) v.findViewById(pair[0])).setTextColor(c[pair[1]]);
        }
        int[][] links = {{R.id.about_repo, M3.PRIMARY},
                {R.id.about_author_name, M3.PRIMARY},
                {R.id.about_author_url, M3.ON_SURFACE_VARIANT},
                {R.id.about_notice, M3.PRIMARY}};
        for (int[] pair : links) {
            ((TextView) v.findViewById(pair[0])).setTextColor(c[pair[1]]);
        }

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
            new Thread(() -> {
                String text = "";
                try (java.io.InputStream in = getAssets().open("MAGPIE_LICENSE.txt");
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                    text = out.toString("UTF-8");
                } catch (Exception ignored) {
                    text = "许可证读取失败";
                }
                final String body = text;
                ui.post(() -> {
                    AlertDialog dlg = new AlertDialog.Builder(this)
                            .setTitle("magpie · MIT License")
                            .setMessage(body)
                            .setPositiveButton(android.R.string.ok, null)
                            .create();
                    styleDialog(dlg);
                    dlg.show();
                });
            }, "magpie-license").start();
        });

        AlertDialog about = new AlertDialog.Builder(this)
                .setView(v)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        styleDialog(about);
        about.show();
    }

    /** 给系统 AlertDialog 换上 M3 的容器色，并把标题/正文/按钮刷成对应前景。 */
    private void styleDialog(AlertDialog dlg) {
        int[] c = M3.current(this);
        dlg.setOnShowListener(x -> {
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(rounded(c[M3.SURFACE_CONTAINER_HIGH], 28));
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
            for (int id : new int[]{AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
                Button b = dlg.getButton(id);
                if (b != null) {
                    b.setTextColor(c[M3.PRIMARY]);
                }
            }
        });
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

    // ------------------------------------------------------------------
    // 壳（APK）自更新：方案一 —— App 内下载 + 拉起系统安装器，不跳浏览器
    // ------------------------------------------------------------------

    /** 三点菜单里的「检查 App 更新」：查 GitHub → 有新版本弹下载确认。 */
    private void checkShellUpdate() {
        final String local = ShellUpdater.localVersion(this);
        ShellUpdater.check(this, r -> {
            if (!r.ok()) {
                AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle(R.string.shell_update_title)
                        .setMessage(getString(R.string.shell_update_failed, r.error))
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                styleDialog(dlg);
                dlg.show();
                return;
            }
            if (!ShellUpdater.newer(r.latestTag, local)) {
                AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle(R.string.shell_update_title)
                        .setMessage(getString(R.string.shell_update_latest, local))
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                styleDialog(dlg);
                dlg.show();
                return;
            }
            final String mirror = Prefs.shellMirror(this);
            if (!r.hasApk()) {
                // Release 里没有 APK 资产，退回跳发布页
                openUrl(r.page);
                return;
            }
            final String url = ShellUpdater.mirrored(mirror, r.downloadUrl);
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(R.string.shell_update_title)
                    .setMessage(getString(R.string.shell_update_available_download,
                            r.latestTag, mirrorLabel(mirror)))
                    .setPositiveButton(R.string.shell_download_install,
                            (d, w) -> downloadApkAndInstall(url, r.latestTag))
                    .setNeutralButton(R.string.shell_open_page, (d, w) -> openUrl(r.page))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            styleDialog(dlg);
            dlg.show();
        });
    }

    /** 启动时静默查一次壳更新（每天一次，设置里可关）。 */
    private void autoCheckShellUpdate() {
        if (!Prefs.shellAutoCheck(this)) {
            return;
        }
        if (System.currentTimeMillis() - Prefs.shellLastCheck(this) < AUTO_CHECK_EVERY) {
            return;
        }
        ShellUpdater.check(this, r -> {
            if (!r.ok()) {
                return; // 网络失败这次不算，下次再试
            }
            Prefs.noteShellChecked(this, r.latestTag);
            if (ShellUpdater.newer(r.latestTag, ShellUpdater.localVersion(this))) {
                final String mirror = Prefs.shellMirror(this);
                AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle(R.string.shell_update_title)
                        .setMessage(getString(R.string.shell_update_auto_found, r.latestTag))
                        .setPositiveButton(R.string.shell_download_install, (d, w) -> {
                            if (r.hasApk()) {
                                downloadApkAndInstall(ShellUpdater.mirrored(mirror, r.downloadUrl),
                                        r.latestTag);
                            } else {
                                openUrl(r.page);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                styleDialog(dlg);
                dlg.show();
            }
        });
    }

    private String mirrorLabel(String mirror) {
        if (mirror == null || mirror.isEmpty()) {
            return getString(R.string.shell_mirror_official);
        }
        return mirror;
    }

    /** 下载 APK 到 cacheDir，然后拉起系统安装器。 */
    private void downloadApkAndInstall(String url, String version) {
        if (Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(R.string.shell_update_title)
                    .setMessage(R.string.shell_need_unknown_source)
                    .setPositiveButton(R.string.shell_go_settings, (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(this, R.string.shell_install_failed, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            styleDialog(dlg);
            dlg.show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.shell_downloading));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "apk");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("无法创建下载目录");
                }
                File apk = new File(dir, "magpie-update.apk");
                download(url, apk);
                ui.post(() -> {
                    if (pd.isShowing()) {
                        pd.dismiss();
                    }
                    installApk(apk);
                });
            } catch (IOException e) {
                Log.w(TAG, "下载 APK 失败: " + e);
                ui.post(() -> {
                    if (pd.isShowing()) {
                        pd.dismiss();
                    }
                    AlertDialog dlg = new AlertDialog.Builder(this)
                            .setTitle(R.string.shell_update_title)
                            .setMessage(getString(R.string.shell_download_failed, e.getMessage()))
                            .setPositiveButton(android.R.string.ok, null)
                            .create();
                    styleDialog(dlg);
                    dlg.show();
                });
            }
        }, "magpie-apk-download").start();
    }

    /** 拉起系统安装器装 APK（content:// + 临时读授权）。 */
    private void installApk(File apk) {
        try {
            Uri uri = Uri.parse("content://" + ApkFileProvider.AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "拉起安装器失败: " + e);
            Toast.makeText(this, R.string.shell_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** 简单 GET 下载，写满本地文件。 */
    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "magpie-android-shell");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("写不出目标文件");
            }
        } finally {
            c.disconnect();
        }
    }
}
