package com.magpie.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs magpie.

 * The Go binary is packed into the APK as {@code assets/bin/magpie.bin}. On
 * first run it is extracted to {@code filesDir}/magpie/magpie, beside a
 * {@code .portable} marker — that one marker is what makes magpie keep
 * everything it owns (settings.json, providers.json, the cache, the logs)
 * inside {@code filesDir}/magpie/data instead of a desktop home folder, and
 * it is how the wrapper needs no root and no symlinks.

 * magpie is then started as

 *     MAGPIE_ADDR=127.0.0.1:P magpie serve --web=127.0.0.1:W

 * one process, two ports: P is the gateway (OpenAI/Anthropic/… for the
 * agents), W is the Web UI the Activity puts in a WebView. They have to be
 * different ports — magpie dials its own gateway address to ask whether the
 * port is taken, and with one port it would be asking itself.

 * Android 10 and newer refuse exec() on files inside an app's home directory
 * to apps that target SDK 29+ (W^X). This app targets 28 for exactly that
 * reason; if the system still refuses, the service falls back to
 * /system/bin/linker64, which Android lets load an app-data ELF.
 */
public class GatewayService extends Service {

    private static final String TAG = "MagpieGateway";
    private static final int NOTIF_ID = 9101;
    private static final String CHANNEL_ID = "magpie_gateway";
    public static final String ACTION_RESTART = "com.magpie.android.action.RESTART";
    public static final String EXTRA_FORCE = "force";

    /** Ports above this are the gateway's own range; the Web UI sits P+5
     *  away from it (3425 → 3430, as upstream's Dockerfile exposes). */
    private static final int WEB_PORT_OFFSET = 5;

    private static final Object LOCK = new Object();
    private static Process process;
    private static Thread logThread;
    private static Thread watchThread;
    private static boolean ready;
    private static boolean starting;
    private static int exitCode = -1;
    private static String bootError = "";
    private static final StringBuilder tail = new StringBuilder();

    private static volatile PowerManager.WakeLock wakeLock;

    private final AtomicBoolean stopping = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // paths
    // ------------------------------------------------------------------

    /** filesDir/magpie — the portable root: binary + .portable + data/. */
    static File rootDir(Context ctx) {
        return new File(ctx.getFilesDir(), "magpie");
    }

    static File binary(Context ctx) {
        return new File(rootDir(ctx), "magpie");
    }

    static File dataDir(Context ctx) {
        return new File(rootDir(ctx), "data");
    }

    /** The $HOME magpie gives the agents (~/.claude, ~/.codex, …). */
    static File homeDir(Context ctx) {
        return new File(ctx.getFilesDir(), "home");
    }

    static File logDir(Context ctx) {
        return new File(ctx.getFilesDir(), "logs");
    }

    static File logFile(Context ctx) {
        return new File(logDir(ctx), "magpie.log");
    }

    static File callerKeysFile(Context ctx) {
        return new File(dataDir(ctx), "caller-keys.json");
    }

    // ------------------------------------------------------------------
    // ports
    // ------------------------------------------------------------------

    static int gatewayPort(Context ctx) {
        return Prefs.port(ctx);
    }

    static int webPort(Context ctx) {
        int p = gatewayPort(ctx) + WEB_PORT_OFFSET;
        if (p > 65535) {
            p = 3430;
        }
        return p;
    }

    /** The address magpie binds for the gateway; 0.0.0.0 in LAN mode. */
    static String bindHost(Context ctx) {
        return Prefs.lan(ctx) ? "0.0.0.0" : Prefs.DEFAULT_HOST;
    }

    /** The Web UI is always reached over loopback by the WebView, but in
     *  LAN mode it is also bound on every interface so a computer on the
     *  same Wi-Fi can open it in its own browser. */
    static String webBind(Context ctx) {
        return Prefs.lan(ctx) ? "0.0.0.0" : Prefs.DEFAULT_HOST;
    }

    static String webUrl(Context ctx) {
        return "http://" + Prefs.browserHost(ctx) + ":" + webPort(ctx)
                + "/?k=" + Prefs.webKey(ctx);
    }

    // ------------------------------------------------------------------
    // service lifecycle
    // ------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, notification(this, "Magpie", "正在启动网关…",
                Notification.FLAG_ONGOING_EVENT));
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            restart();
            return START_STICKY;
        }
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                return START_STICKY;
            }
            if (starting) {
                return START_STICKY;
            }
            starting = true;
            ready = false;
            bootError = "";
            exitCode = -1;
        }
        Thread t = new Thread(this::boot, "magpie-boot");
        t.setDaemon(true);
        t.start();
        return START_STICKY;
    }

    /** 把网关切掉（服务也停），下次点重试或重新打开 App 再起来。 */
    public static void stop(Context ctx) {
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
            process = null;
            ready = false;
            starting = false;
        }
        LocalProxy.stop();
        try {
            ctx.stopService(new Intent(ctx, GatewayService.class));
        } catch (Exception ignored) {
        }
    }

    /** Stops magpie and starts it again in one go. */
    public static void restart(Context ctx) {
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
        Intent i = new Intent(ctx, GatewayService.class);
        i.setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    private void boot() {
        try {
            patchPlugins(this);
            File bin = ensureBinary(this);
            quietOwnUpdater(bin);
            startProcess(this, bin);
        } catch (Exception e) {
            Log.e(TAG, "boot failed", e);
            synchronized (LOCK) {
                starting = false;
                bootError = e.toString();
            }
            postNotice(this, "Magpie 启动失败", e.toString());
        }
    }

    /**
     * 让 magpie 自己的自动更新闭嘴。
     *
     * <p>它按 {@code runtime.GOOS} 算资产名：这个壳发的是 {@code GOOS=android}
     * 的二进制，算出来是对的（magpie-cli-android-arm64），但 Web UI 里那个「更新」
     * 按钮走的是 magpie 自己的替换逻辑，在手机沙箱里换完还得重启整个 App，体验不如下面
     * 壳里的更新页；而自建版（GOOS=linux）算出来的是桌面 GUI 版，换上就段错误。
     * 所以两边都只留壳的更新页这一条路。
     */
    private void quietOwnUpdater(File bin) {
        if (Prefs.updatesManaged(this)) {
            return;
        }
        try {
            execQuiet(bin, "settings", "set", "noAutoUpdate", "on");
            execQuiet(bin, "settings", "set", "noUpdatePill", "on");
            Prefs.setUpdatesManaged(this, true);
            appendLog("已关掉 magpie 自己的自动更新，更新走 App 的「⋮ → 检查更新」");
        } catch (Exception e) {
            appendLog("关 magpie 自带更新失败（不影响使用）：" + e.getMessage());
        }
    }

    private void execQuiet(File bin, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir(this));
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("HOME", homeDir(this).getAbsolutePath());
        Process p = pb.start();
        try {
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        p.destroy();
    }

    private void startProcess(Context ctx, File bin) throws IOException {
        String gwHost = bindHost(ctx);
        int gwPort = gatewayPort(ctx);
        int web = webPort(ctx);

        // magpie 是 CGO 关掉的静态 Go：域名解析读 /etc/resolv.conf，读不到就去连
        // 127.0.0.1:53 / ::1:53（Android 上没人监听）。把它的出站 HTTP 交给本机
        // 代理，域名由 Java 走 netd 解析 —— 见 LocalProxy 的注释。
        String proxyUrl = "";
        try {
            proxyUrl = LocalProxy.ensure();
        } catch (IOException e) {
            appendLog("本地代理起不来（" + e.getMessage() + "），域名解析可能会失败");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        cmd.add("--web=" + webBind(ctx) + ":" + web);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir(ctx));
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("HOME", homeDir(ctx).getAbsolutePath());
        env.put("USERPROFILE", homeDir(ctx).getAbsolutePath());
        env.put("PATH", bin.getParentFile().getAbsolutePath() + ":" + safePath());
        env.put("MAGPIE_ADDR", gwHost + ":" + gwPort);
        env.put("MAGPIE_WEB_KEY", Prefs.webKey(ctx));
        env.put("MAGPIE_UPDATE_FEED", Prefs.updateFeed(ctx));
        env.put("MAGPIE_TRUST_PROXY", "");
        if (proxyUrl.length() > 0) {
            // magpie 的 netproxy 在 Settings 没设 Proxy 时会读环境变量
            env.put("HTTP_PROXY", proxyUrl);
            env.put("HTTPS_PROXY", proxyUrl);
            env.put("http_proxy", proxyUrl);
            env.put("https_proxy", proxyUrl);
            env.put("NO_PROXY", "127.0.0.1,localhost,::1");
            env.put("no_proxy", "127.0.0.1,localhost,::1");
        }
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("TZ", java.util.TimeZone.getDefault().getID());

        Process p;
        try {
            p = pb.start();
            appendLog("以 targetSdk 28 直接 exec 启动");
        } catch (IOException direct) {
            // Android 10+ W^X: the system linker is allowed to load an
            // ELF out of app data even where exec() is refused.
            List<String> alt = new ArrayList<>();
            alt.add("/system/bin/linker64");
            alt.addAll(cmd);
            ProcessBuilder pb2 = new ProcessBuilder(alt);
            pb2.directory(rootDir(ctx));
            pb2.redirectErrorStream(true);
            pb2.environment().putAll(env);
            appendLog("直接 exec 被拒（" + direct.getMessage() + "），改用 /system/bin/linker64 启动");
            p = pb2.start();
        }

        synchronized (LOCK) {
            process = p;
            starting = false;
        }

        acquireWakeLock(ctx);

        final Process child = p;
        Thread lt = new Thread(() -> pumpLog(ctx, child), "magpie-log");
        lt.setDaemon(true);
        logThread = lt;
        lt.start();

        Thread wt = new Thread(() -> watch(ctx, child, gwHost, gwPort, web), "magpie-watch");
        wt.setDaemon(true);
        watchThread = wt;
        wt.start();
    }

    private static String safePath() {
        String p = System.getenv("PATH");
        return p == null ? "/system/bin:/system/xbin:/vendor/bin" : p;
    }

    /** Reads magpie's own output into the log ring and Logcat, then waits. */
    private void pumpLog(Context ctx, Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                appendLog(line);
                Log.i(TAG, line);
            }
        } catch (Exception ignored) {
        }
        appendLog("— magpie 的输出结束 —");
    }

    /** Waits for the gateway to answer, then for it to go away. */
    private void watch(Context ctx, Process p, String host, int gwPort, int webPort) {
        for (int i = 0; i < 400 && p.isAlive(); i++) {
            if (portOpen(Prefs.browserHost(ctx), webPort)) {
                synchronized (LOCK) {
                    ready = true;
                }
                notifyReady(ctx);
                break;
            }
            sleep(300);
        }
        if (!p.isAlive()) {
            return;
        }
        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (LOCK) {
            exitCode = code;
            ready = false;
        }
        appendLog("magpie 退出，退出码 " + code);
        notifyDown(ctx, code);
        stopSelf();
    }

    private void restart() {
        synchronized (LOCK) {
            stopping.set(true);
        }
        Process p;
        synchronized (LOCK) {
            p = process;
        }
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        synchronized (LOCK) {
            process = null;
            ready = false;
            starting = false;
        }
        LocalProxy.stop();
        stopping.set(false);
        boot();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (LOCK) {
            stopping.set(true);
        }
        Process p;
        synchronized (LOCK) {
            p = process;
        }
        if (p != null && p.isAlive()) {
            p.destroy();
        }
        LocalProxy.stop();
        releaseWakeLock();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The Activity is the whole point of the app; keep serving.
        Intent restart = new Intent(getApplicationContext(), GatewayService.class);
        restart.setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(restart);
        }
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------
    // binary extraction
    // ------------------------------------------------------------------

    private static final String ASSET = "bin/magpie.bin";

    /** Extracts assets/bin/magpie.bin to filesDir/magpie/magpie if it is
     *  missing or was replaced by a newer build of the app. */
    static File ensureBinary(Context ctx) throws IOException {
        File root = rootDir(ctx);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("无法创建目录 " + root);
        }
        File marker = new File(root, ".portable");
        if (!marker.exists()) {
            writeFile(marker, "magpie portable here\n");
        }
        File home = homeDir(ctx);
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("无法创建目录 " + home);
        }
        File logs = logDir(ctx);
        if (!logs.isDirectory() && !logs.mkdirs()) {
            throw new IOException("无法创建目录 " + logs);
        }
        File data = dataDir(ctx);
        if (!data.isDirectory() && !data.mkdirs()) {
            throw new IOException("无法创建目录 " + data);
        }

        File dst = binary(ctx);
        SharedPreferences sp = ctx.getSharedPreferences("magpie_prefs", Context.MODE_PRIVATE);

        // APK 本身升级过（装了新版 App）→ assets 里的二进制才是权威的，
        // 清掉大小记录让它重新释放。没升级过就别动：这个文件可能是用户
        // 在 App 里从 GitHub 拉到的新版，比 assets 里的新，冲掉就白更新了。
        long stamp = appStamp(ctx);
        if (stamp > 0 && sp.getLong("assets_stamp", -1) != stamp) {
            sp.edit().putLong("assets_stamp", stamp).remove("bin_size").apply();
        }

        long want = sp.getLong("bin_size", -1);

        if (dst.exists() && want > 0 && dst.length() == want && looksLikeElf(dst)) {
            return dst;
        }

        File tmp = new File(root, "magpie.tmp");
        long n = 0;
        try (InputStream in = new BufferedInputStream(ctx.getAssets().open(ASSET), 1 << 16);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                n += read;
            }
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        }
        if (n < 4 || !looksLikeElf(tmp)) {
            throw new IOException("解压出来的文件不是 ELF（" + n + " 字节）");
        }
        dst.delete();
        if (!tmp.renameTo(dst)) {
            throw new IOException("无法写出 " + dst);
        }
        dst.setReadable(true, false);
        dst.setWritable(true, false);
        dst.setExecutable(true, false);
        sp.edit().putLong("bin_size", n).apply();
        appendLog("已从 APK 释放 magpie 二进制：" + dst + "（" + n + " 字节）");
        return dst;
    }

    /** App 自己的安装/更新时间；升级过一次就变了，拿它当 assets 的新鲜度标记。 */
    private static long appStamp(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean looksLikeElf(File f) {
        if (f == null || !f.exists() || f.length() < 4) {
            return false;
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4];
            return in.read(b) == 4
                    && (b[0] & 0xff) == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // log ring
    // ------------------------------------------------------------------

    private static final int TAIL_LINES = 400;
    private static final Object TAIL_LOCK = new Object();

    static void appendLog(String line) {
        synchronized (TAIL_LOCK) {
            if (tail.length() > 0) {
                tail.append('\n');
            }
            if (tail.length() > 64 * 1024) {
                tail.setLength(0);
            }
            tail.append(line);
        }
        try {
            File f = logFile(App.context());
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            try (OutputStream out = new FileOutputStream(f, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    static String lastLog(int lines) {
        synchronized (TAIL_LOCK) {
            String all = tail.toString();
            if (lines <= 0) {
                return all;
            }
            int from = 0;
            int count = 0;
            for (int i = all.length() - 1; i >= 0 && count < lines; i--) {
                if (all.charAt(i) == '\n') {
                    count++;
                    from = i + 1;
                }
            }
            return all.substring(from);
        }
    }

    static void clearLog() {
        synchronized (TAIL_LOCK) {
            tail.setLength(0);
        }
        File f = logFile(App.context());
        if (f.exists()) {
            f.delete();
        }
    }

    // ------------------------------------------------------------------
    // state
    // ------------------------------------------------------------------

    static boolean isReady() {
        synchronized (LOCK) {
            return ready && process != null && process.isAlive();
        }
    }

    static boolean isStarting() {
        synchronized (LOCK) {
            return starting;
        }
    }

    static boolean isAlive() {
        synchronized (LOCK) {
            return process != null && process.isAlive();
        }
    }

    static String bootError() {
        synchronized (LOCK) {
            return bootError;
        }
    }

    static int exitCode() {
        synchronized (LOCK) {
            return exitCode;
        }
    }

    static void resetState() {
        synchronized (LOCK) {
            ready = false;
            starting = false;
            bootError = "";
            exitCode = -1;
        }
    }

    public static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 插件补丁表：桌面浏览器 / 桌面进程内存这些东西 Android 上没有，
    // 靠这张表把插件的登录换成一条 Android 能走的路
    // ------------------------------------------------------------------

    /**
     * 一条插件补丁。
     *
     * <p>{@code anchor} 是插件里稳定的一段源码，{@code replacement} 是换成带
     * Android 兜底的版本，{@code helper} 追加到文件末尾（新增的函数定义）。
     *
     * <p>只做锚点替换：锚点找不到就跳过、只记一行日志 —— 绝不整文件覆盖，
     * 否则插件作者的新逻辑会被一起丢掉。
     */
    private static final class Patch {
        final String id;
        final String file;
        final String mark;
        final String helperMark;
        final String anchor;
        final String replacement;
        final String helper;

        Patch(String id, String file, String mark, String helperMark,
              String anchor, String replacement, String helper) {
            this.id = id;
            this.file = file;
            this.mark = mark;
            this.helperMark = helperMark;
            this.anchor = anchor;
            this.replacement = replacement;
            this.helper = helper;
        }
    }

    private static final String MARVIS_FILE =
            "data/plugins/node_modules/magpie-marvis/src/hooks/methods.mjs";
    private static final String MARVIS_MARK = "MAGPIE_ANDROID_PASTE";
    private static final String MARVIS_HELPER_MARK = "MAGPIE_ANDROID_PASTE_HELPER";

    private static final String MARVIS_ANCHOR =
            "        async callback(_url, _input, signal) {\n"
                    + "          const browser = findBrowser()";

    private static final String MARVIS_REPLACEMENT =
            "        async callback(_url, _input, signal) {\n"
                    + "          /* MAGPIE_ANDROID_PASTE */\n"
                    + "          // Android 上没有可 spawn 的 Chromium：等壳把用户粘贴的回调地址写进文件。\n"
                    + "          if (!findBrowser()) {\n"
                    + "            const pasted = await waitPastedCallback(signal)\n"
                    + "            const st = stateOfUrl(pasted)\n"
                    + "            const vg = pendingDevices.take(st) || (loginType === \"WX\" ? st : \"\")\n"
                    + "            if (!vg) {\n"
                    + "              throw new Error(\"登录流程已失效（找不到这次登录的设备标识）。请重新点一次登录。\")\n"
                    + "            }\n"
                    + "            let acct = await parseCallback(pasted, \"\", { loginType, guid: vg })\n"
                    + "            acct = await withProfile({ ...acct, guid: vg }, loginType)\n"
                    + "            return authOf({ ...acct, guid: vg, loginType: acct.loginType ?? LOGIN_TYPE[loginType] ?? \"1\" })\n"
                    + "          }\n"
                    + "          const browser = findBrowser()";

    private static final String MARVIS_HELPER = "\n\n/* MAGPIE_ANDROID_PASTE_HELPER */\n"
            + "// 壳把粘贴的回调地址写在 HOME/marvis-login-url.txt，读完即删。\n"
            + "async function waitPastedCallback(signal) {\n"
            + "  const fs = await import(\"node:fs/promises\")\n"
            + "  const os = await import(\"node:os\")\n"
            + "  const path = await import(\"node:path\")\n"
            + "  const file = path.join(os.homedir(), \"marvis-login-url.txt\")\n"
            + "  const started = Date.now()\n"
            + "  while (Date.now() - started < 10 * 60_000) {\n"
            + "    if (signal && signal.aborted) throw new Error(\"登录已取消\")\n"
            + "    try {\n"
            + "      const raw = (await fs.readFile(file, \"utf8\")).trim()\n"
            + "      if (raw) {\n"
            + "        await fs.unlink(file).catch(() => {})\n"
            + "        return raw\n"
            + "      }\n"
            + "    } catch {\n"
            + "      /* 还没写进来 */\n"
            + "    }\n"
            + "    await new Promise((r) => setTimeout(r, 500))\n"
            + "  }\n"
            + "  throw new Error(\"等待粘贴 Marvis 授权回调超时（10 分钟）。请重新点一次登录。\")\n"
            + "}\n";

    /** 已知需要 Android 适配的插件。加新插件就往这里加一条。 */
    private static final Patch[] PATCHES = {
            new Patch("magpie-marvis", MARVIS_FILE, MARVIS_MARK, MARVIS_HELPER_MARK,
                    MARVIS_ANCHOR, MARVIS_REPLACEMENT, MARVIS_HELPER),
    };

    /** 补丁出问题时留给 UI 的一句话（一次性，读完即清）。 */
    private static volatile String patchNote = "";

    static String takePatchNote() {
        String n = patchNote;
        patchNote = "";
        return n;
    }

    /** 每次启动网关前跑一遍：已经补过的跳过，插件更新后被覆盖的补回来。 */
    static void patchPlugins(Context ctx) {
        for (Patch p : PATCHES) {
            applyPatch(ctx, p);
        }
    }

    private static void applyPatch(Context ctx, Patch p) {
        File f = new File(rootDir(ctx), p.file);
        if (!f.isFile()) {
            return; // 没装这个插件
        }
        String src;
        try {
            src = readText(f);
        } catch (IOException e) {
            appendLog("插件补丁：读不到 " + p.id + "（" + e.getMessage() + "）");
            return;
        }
        if (src.contains(p.mark) && src.contains(p.helperMark)) {
            return; // 已经补过
        }
        if (!src.contains(p.anchor)) {
            patchNote = p.id + " 的兼容补丁没生效：插件已改版，需要更新这个 App";
            appendLog("插件补丁跳过：" + p.id + " 锚点找不到（插件结构变了）");
            return;
        }
        String out = src.replace(p.anchor, p.replacement);
        if (!out.contains(p.helperMark)) {
            out = out + p.helper;
        }
        try {
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(out.getBytes(StandardCharsets.UTF_8));
            }
            appendLog("插件补丁已写入：" + p.id);
        } catch (IOException e) {
            patchNote = p.id + " 的兼容补丁写不进去";
            appendLog("插件补丁写入失败：" + p.id + " " + e.getMessage());
        }
    }

    /** 给「粘贴 Marvis 授权回调」对话框看的：插件在不在、版本、补丁状态。 */
    static String patchStatus(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Patch p : PATCHES) {
            File f = new File(rootDir(ctx), p.file);
            String ver = pluginVersion(ctx, p.id);
            sb.append(p.id).append(ver.isEmpty() ? "" : " " + ver).append("：");
            if (!f.isFile()) {
                sb.append("没装这个插件");
            } else {
                String src;
                try {
                    src = readText(f);
                } catch (IOException e) {
                    src = "";
                }
                if (src.contains(p.mark) && src.contains(p.helperMark)) {
                    sb.append("兼容补丁已生效");
                } else if (src.contains(p.anchor)) {
                    sb.append("补丁待写入（重启网关即可）");
                } else {
                    sb.append("补丁不适用（插件已改版，需要更新这个 App）");
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String pluginVersion(Context ctx, String id) {
        File f = new File(rootDir(ctx), "data/plugins/node_modules/" + id + "/package.json");
        if (!f.isFile()) {
            return "";
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(readText(f));
            return m.find() ? m.group(1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    // ------------------------------------------------------------------
    // wake lock
    // ------------------------------------------------------------------

    private static void acquireWakeLock(Context ctx) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magpie:gateway");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private static void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // notification
    // ------------------------------------------------------------------

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Magpie 网关", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("后台运行 magpie 网关");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private static Notification notification(Context ctx, String title, String body, int flags) {
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        return b
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOngoing((flags & Notification.FLAG_ONGOING_EVENT) != 0)
                .setWhen(System.currentTimeMillis())
                .getNotification();
    }

    private void notifyReady(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification(ctx, "Magpie 已就绪",
                    "网关 http://" + bindHost(ctx) + ":" + gatewayPort(ctx), 0));
        }
    }

    private void notifyDown(Context ctx, int code) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification(ctx, "Magpie 已停止",
                    code < 0 ? "网关进程结束" : "退出码 " + code, 0));
        }
    }

    static void postNotice(Context ctx, String title, String body) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID + 7, notification(ctx, title, body, 0));
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeFile(File f, String content) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
