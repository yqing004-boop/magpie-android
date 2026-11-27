package com.magpie.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * The handful of things the wrapper keeps: which address the gateway binds,
 * the key the Web UI signs a browser in with, and whether the service should
 * start itself on boot.
 *
 * Everything else magpie keeps — settings.json, providers.json, the agent
 * configs, the cache — lives under {@code filesDir}/magpie/data, because the
 * binary runs in portable mode (a {@code .portable} marker beside it).
 */
public final class Prefs {

    private static final String NAME = "magpie_prefs";

    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String WEBKEY = "webkey";
    /** 固定（锁定）的网关访问密钥：非空时 webKey() 永远优先用它，避免意外重置后 k 变化。 */
    public static final String WEBKEY_LOCKED = "webkey_locked";
    public static final String LAN = "lan";
    public static final String AUTOSTART = "autostart";
    public static final String UPDATE_FEED = "update_feed";
    /** 更新渠道：true=稳定版（?channel=stable），false=每个版本。 */
    public static final String STABLE_CHANNEL = "stable_channel";
    /** 壳（APK）更新渠道：true=稳定版，false=测试版（含 prerelease）。 */
    public static final String SHELL_STABLE_CHANNEL = "shell_stable_channel";
    /** 后台多久后释放 WebView（分钟）；0 = 从不。 */
    public static final String WEB_RELEASE_MINUTES = "web_release_minutes";
    /** 「释放时间已按新版默认值覆盖过」的标记，只迁移一次。 */
    public static final String WEB_RELEASE_MIGRATED = "web_release_migrated_v2";
    /** 后台运行检查卡被用户主动关闭过，别每次进设置都强提醒。 */
    public static final String BACKGROUND_GUIDE_DISMISSED = "background_guide_dismissed";
    /** 首次后台保活引导是否已经完成或被用户选择不再提示。 */
    public static final String BACKGROUND_GUIDE_SEEN = "background_guide_seen";
    /** 壳自己装过的版本，用它判断“是不是比本机的更新”，不用每次都跑二进制。 */
    public static final String INSTALLED_VERSION = "installed_version";
    /** 上次静默检查更新是几点（毫秒），别每次开 App 都问一遍网络。 */
    public static final String LAST_CHECK = "last_check";
    /** 上次检查时看到的新版本号，菜单上可以提示一句。 */
    public static final String KNOWN_LATEST = "known_latest";
    /** 壳更新：自动检查开关（默认开）。 */
    public static final String SHELL_AUTO_CHECK = "shell_auto_check";
    /** 壳更新：上次静默检查时间（毫秒）。 */
    public static final String SHELL_LAST_CHECK = "shell_last_check";
    /** 壳更新：上次检查时看到的新版本号。 */
    public static final String SHELL_KNOWN_LATEST = "shell_known_latest";
    /** 壳更新：当前下载镜像前缀（空串 = GitHub 官方直连）。 */
    public static final String SHELL_MIRROR = "shell_mirror";
    /** 壳更新：用户手动添加的镜像前缀列表。 */
    public static final String SHELL_CUSTOM_MIRRORS = "shell_custom_mirrors";
    /** 数字单位：0=英文 K/M/B，1=中文 万/亿。 */
    public static final String UNIT_MODE = "unit_mode";
    /** 主题模式：0=跟随系统，1=深色，2=浅色。 */
    public static final String THEME_MODE = "theme_mode";
    /** 界面风格下标：0=经典，1=紧凑（对应 M3.THEMES）。 */
    public static final String THEME_STYLE = "theme_style";
    /** 脱敏总开关（壳自己的闸）：false = 已把三个子开关全写 false 到 magpie。 */
    public static final String MASK_MASTER = "mask_master";
    /** 关总闸前对 redact/redactPersonal/redactWords/redactKinds 的快照（JSON），恢复用。 */
    public static final String MASK_SNAPSHOT = "mask_snapshot";

    public static int themeMode(Context ctx) {
        int m = sp(ctx).getInt(THEME_MODE, 0);
        return m < 0 || m > 2 ? 0 : m;
    }

    public static void setThemeMode(Context ctx, int m) {
        sp(ctx).edit().putInt(THEME_MODE, m < 0 || m > 2 ? 0 : m).apply();
    }

    /**
     * 界面风格下标，具体有几套由 {@link M3#THEMES} 决定。
     *
     * <p>默认值取「玻璃」而不是下标 0：老用户升级上来必须保持原来的样子，
     * 不能因为我们把「原版」摆到第一位，就自作主张换了别人的界面。
     */
    public static int themeStyle(Context ctx) {
        int i = sp(ctx).getInt(THEME_STYLE, M3.DEFAULT_THEME);
        return i < 0 || i >= M3.THEMES.length ? M3.DEFAULT_THEME : i;
    }

    public static void setThemeStyle(Context ctx, int i) {
        int v = i < 0 || i >= M3.THEMES.length ? M3.DEFAULT_THEME : i;
        sp(ctx).edit().putInt(THEME_STYLE, v).apply();
    }

    /** 脱敏总闸：true=正常（各子开关自己说话），false=已整体关闭。默认 true。 */
    public static boolean maskMaster(Context ctx) {
        return sp(ctx).getBoolean(MASK_MASTER, true);
    }

    public static void setMaskMaster(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(MASK_MASTER, on).apply();
    }

    /** 关闸前的子开关快照。 */
    public static String maskSnapshot(Context ctx) {
        return sp(ctx).getString(MASK_SNAPSHOT, "");
    }

    public static void setMaskSnapshot(Context ctx, String json) {
        sp(ctx).edit().putString(MASK_SNAPSHOT, json).apply();
    }

    /** 上次发「设为无限制」提醒的时间（毫秒），控制提醒频率。 */
    public static final String FREEZE_NUDGE_AT = "freeze_nudge_at";
    /** 退到后台的时间（毫秒）：用它判断这次后台是不是长到会被冻结。 */
    public static final String FREEZE_LEFT_AT = "freeze_left_at";

    /** 壳导入的本地插件文件夹名清单（下次导入时清已移除的残留）。 */
    public static final String IMPORTED_PLUGINS = "imported_plugins";

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 3425;

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String host(Context ctx) {
        return sp(ctx).getString(HOST, DEFAULT_HOST);
    }

    public static int port(Context ctx) {
        try {
            int p = Integer.parseInt(sp(ctx).getString(PORT, String.valueOf(DEFAULT_PORT)));
            if (p > 0 && p < 65536) {
                return p;
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_PORT;
    }

    /** The address the gateway binds. 0.0.0.0 when LAN is on, so a computer
     *  on the same Wi-Fi can reach the phone's magpie too. */
    public static String bindHost(Context ctx) {
        return lan(ctx) ? "0.0.0.0" : DEFAULT_HOST;
    }

    /** The address the WebView talks to. Always loopback: the page and the
     *  gateway are in the same process's network namespace. */
    /** The address the WebView reaches magpie on. LAN mode binds magpie on
     *  0.0.0.0 so other devices on the Wi-Fi can open it, but 0.0.0.0 is a
     *  bind address, not a destination — the phone itself always goes
     *  through loopback. */
    public static String browserHost(Context ctx) {
        String h = host(ctx);
        if (h == null || h.isEmpty() || h.equals("0.0.0.0") || h.equals("::")) {
            return DEFAULT_HOST;
        }
        return h;
    }

    public static boolean lan(Context ctx) {
        return sp(ctx).getBoolean(LAN, false);
    }

    /** 0=从不；其余值为后台释放 WebView 的分钟数。默认 10 分钟。 */
    public static int webReleaseMinutes(Context ctx) {
        int m = sp(ctx).getInt(WEB_RELEASE_MINUTES, 5);
        return m >= 1 && m <= 1440 ? m : 0;
    }

    /**
     * 把释放时间强制改成本版的默认值，**只做一次**。
     *
     * <p>为什么需要：上一版起 WebView 从主界面降级成「打开控制后台」，
     * 释放时间的语义变了；老用户存着的 10/15/30 分钟是旧界面时代的设置，
     * 继续沿用会让新版看起来没生效（比如存了 1 分钟，网页界面刚退就被销毁）。
     *
     * <p>用独立的迁移标记，所以**不会**每次启动都覆盖用户后来手动改的值。
     */
    public static void migrateWebReleaseOnce(Context ctx, int minutes) {
        if (sp(ctx).getBoolean(WEB_RELEASE_MIGRATED, false)) {
            return;
        }
        sp(ctx).edit()
                .putBoolean(WEB_RELEASE_MIGRATED, true)
                .putInt(WEB_RELEASE_MINUTES, minutes)
                .apply();
    }

    public static void setWebReleaseMinutes(Context ctx, int minutes) {
        int m = minutes >= 1 && minutes <= 1440 ? minutes : 0;
        sp(ctx).edit().putInt(WEB_RELEASE_MINUTES, m).apply();
    }

    /** 数字单位：0=英文（K/M/B），1=中文（万/亿）。 */
    public static int unitMode(Context ctx) {
        return sp(ctx).getInt(UNIT_MODE, 0) == 1 ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // 后台冻结提醒（FreezeGuard）
    // ------------------------------------------------------------------

    public static long freezeNudgeAt(Context ctx) {
        return sp(ctx).getLong(FREEZE_NUDGE_AT, 0);
    }

    public static void setFreezeNudgeAt(Context ctx, long at) {
        sp(ctx).edit().putLong(FREEZE_NUDGE_AT, at).apply();
    }

    public static long freezeLeftAt(Context ctx) {
        return sp(ctx).getLong(FREEZE_LEFT_AT, 0);
    }

    public static void setFreezeLeftAt(Context ctx, long at) {
        sp(ctx).edit().putLong(FREEZE_LEFT_AT, at).apply();
    }

    public static void setUnitMode(Context ctx, int mode) {
        sp(ctx).edit().putInt(UNIT_MODE, mode == 1 ? 1 : 0).apply();
    }

    public static boolean backgroundGuideDismissed(Context ctx) {
        return sp(ctx).getBoolean(BACKGROUND_GUIDE_DISMISSED, false);
    }

    public static void setBackgroundGuideDismissed(Context ctx, boolean dismissed) {
        sp(ctx).edit().putBoolean(BACKGROUND_GUIDE_DISMISSED, dismissed).apply();
    }

    public static boolean backgroundGuideSeen(Context ctx) {
        return sp(ctx).getBoolean(BACKGROUND_GUIDE_SEEN, false);
    }

    public static void setBackgroundGuideSeen(Context ctx, boolean seen) {
        sp(ctx).edit().putBoolean(BACKGROUND_GUIDE_SEEN, seen).apply();
    }

    public static boolean stableChannel(Context ctx) {
        return sp(ctx).getBoolean(STABLE_CHANNEL, false);
    }

    public static void setStableChannel(Context ctx, boolean stable) {
        sp(ctx).edit().putBoolean(STABLE_CHANNEL, stable).apply();
    }

    /** 壳（APK）更新是否走稳定版；默认稳定。 */
    public static boolean shellStableChannel(Context ctx) {
        return sp(ctx).getBoolean(SHELL_STABLE_CHANNEL, true);
    }

    public static void setShellStableChannel(Context ctx, boolean stable) {
        sp(ctx).edit().putBoolean(SHELL_STABLE_CHANNEL, stable).apply();
    }

    public static boolean autostart(Context ctx) {
        return sp(ctx).getBoolean(AUTOSTART, true);
    }

    public static String updateFeed(Context ctx) {
        return sp(ctx).getString(UPDATE_FEED, "");
    }

    /** 壳装上（或更新到）的二进制版本，记在最前面，读一次省一次 exec。 */
    public static String installedVersion(Context ctx) {
        return sp(ctx).getString(INSTALLED_VERSION, "");
    }

    /** 记下刚装好的版本，同时把 Assets 释放逻辑认的大小改掉 —— 否则
     *  GatewayService 下次启动会以为二进制被换过，从 APK 里把旧的一份又
     *  解压出来，把刚下载的新版冲掉。 */
    public static void noteInstalled(Context ctx, String version, long size) {
        sp(ctx).edit()
                .putString(INSTALLED_VERSION, version == null ? "" : version)
                .putLong("bin_size", size)
                .apply();
    }

    public static long lastCheck(Context ctx) {
        return sp(ctx).getLong(LAST_CHECK, 0);
    }

    public static void noteChecked(Context ctx, String latest) {
        sp(ctx).edit()
                .putLong(LAST_CHECK, System.currentTimeMillis())
                .putString(KNOWN_LATEST, latest == null ? "" : latest)
                .apply();
    }

    public static String knownLatest(Context ctx) {
        return sp(ctx).getString(KNOWN_LATEST, "");
    }

    // ------------------------------------------------------------------
    // 壳更新（APK）
    // ------------------------------------------------------------------

    public static boolean shellAutoCheck(Context ctx) {
        return sp(ctx).getBoolean(SHELL_AUTO_CHECK, true);
    }

    public static void setShellAutoCheck(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(SHELL_AUTO_CHECK, on).apply();
    }

    public static long shellLastCheck(Context ctx) {
        return sp(ctx).getLong(SHELL_LAST_CHECK, 0);
    }

    public static void noteShellChecked(Context ctx, String latest) {
        sp(ctx).edit()
                .putLong(SHELL_LAST_CHECK, System.currentTimeMillis())
                .putString(SHELL_KNOWN_LATEST, latest == null ? "" : latest)
                .apply();
    }

    public static String shellKnownLatest(Context ctx) {
        return sp(ctx).getString(SHELL_KNOWN_LATEST, "");
    }

    /** 当前壳更新镜像前缀；空串表示 GitHub 官方直连。 */
    public static String shellMirror(Context ctx) {
        return sp(ctx).getString(SHELL_MIRROR, "");
    }

    public static void setShellMirror(Context ctx, String prefix) {
        if (prefix == null) {
            prefix = "";
        }
        sp(ctx).edit().putString(SHELL_MIRROR, prefix).apply();
    }

    /** 用户手动添加的镜像前缀。 */
    public static Set<String> shellCustomMirrors(Context ctx) {
        return sp(ctx).getStringSet(SHELL_CUSTOM_MIRRORS, new HashSet<String>());
    }

    public static void addShellCustomMirror(Context ctx, String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return;
        }
        Set<String> s = new HashSet<>(shellCustomMirrors(ctx));
        s.add(prefix.trim());
        sp(ctx).edit().putStringSet(SHELL_CUSTOM_MIRRORS, s).apply();
    }

    public static void removeShellCustomMirror(Context ctx, String prefix) {
        Set<String> s = new HashSet<>(shellCustomMirrors(ctx));
        s.remove(prefix);
        sp(ctx).edit().putStringSet(SHELL_CUSTOM_MIRRORS, s).apply();
    }

    /** 壳导入过的本地插件文件夹名。 */
    public static Set<String> importedPlugins(Context ctx) {
        return sp(ctx).getStringSet(IMPORTED_PLUGINS, new HashSet<String>());
    }

    public static void addImportedPlugin(Context ctx, String name) {
        Set<String> s = new HashSet<>(importedPlugins(ctx));
        s.add(name);
        sp(ctx).edit().putStringSet(IMPORTED_PLUGINS, s).apply();
    }

    public static void removeImportedPlugin(Context ctx, String name) {
        Set<String> s = new HashSet<>(importedPlugins(ctx));
        s.remove(name);
        sp(ctx).edit().putStringSet(IMPORTED_PLUGINS, s).apply();
    }

    /** 是否已经把 magpie 自带的自动更新关掉（只做一次）。 */
    public static boolean updatesManaged(Context ctx) {
        return sp(ctx).getBoolean("updates_managed", false);
    }

    public static void setUpdatesManaged(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("updates_managed", on).apply();
    }

    /** 莫奈取色：从系统壁纸里取强调色（Android 12+ 才有）。 */
    // ------------------------------------------------------------------
    // 悬浮球（保活）
    // ------------------------------------------------------------------

    /** 图标模式：0=自身图标 1=自定义图片 2=透明。 */
    public static final int BALL_ICON_DEFAULT = 0;
    public static final int BALL_ICON_CUSTOM = 1;
    public static final int BALL_ICON_TRANSPARENT = 2;

    public static boolean ballEnabled(Context ctx) {
        return sp(ctx).getBoolean("ball_enabled", false);
    }

    public static void setBallEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("ball_enabled", on).apply();
    }

    /** 保活小窗口单独开关（跟悬浮球独立）。 */
    public static boolean keepAliveEnabled(Context ctx) {
        return sp(ctx).getBoolean("keepalive_enabled", false);
    }

    public static void setKeepAliveEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("keepalive_enabled", on).apply();
    }

    public static int ballIconMode(Context ctx) {
        int m = sp(ctx).getInt("ball_icon_mode", BALL_ICON_DEFAULT);
        return m >= 0 && m <= 2 ? m : BALL_ICON_DEFAULT;
    }

    public static void setBallIconMode(Context ctx, int mode) {
        sp(ctx).edit().putInt("ball_icon_mode", mode).apply();
    }

    public static String ballIconPath(Context ctx) {
        return sp(ctx).getString("ball_icon_path", "");
    }

    public static void setBallIconPath(Context ctx, String path) {
        sp(ctx).edit().putString("ball_icon_path", path == null ? "" : path).apply();
    }

    /** 悬浮球直径（dp），默认 40，范围 24-96。 */
    public static int ballSizeDp(Context ctx) {
        int v = sp(ctx).getInt("ball_size_dp", 40);
        return v >= 24 && v <= 96 ? v : 40;
    }

    public static void setBallSizeDp(Context ctx, int dp) {
        sp(ctx).edit().putInt("ball_size_dp", Math.max(24, Math.min(96, dp))).apply();
    }

    /** 保活窗口边长（dp），默认 1，范围 1-16。 */
    public static int ballKeepAliveDp(Context ctx) {
        int v = sp(ctx).getInt("ball_keep_dp", 1);
        return v >= 1 && v <= 16 ? v : 1;
    }

    public static void setBallKeepAliveDp(Context ctx, int dp) {
        sp(ctx).edit().putInt("ball_keep_dp", Math.max(1, Math.min(16, dp))).apply();
    }

    /** 悬浮球默认位置（屏幕左上角起算的像素坐标）。 */
    public static final int BALL_DEFAULT_X = 0;
    public static final int BALL_DEFAULT_Y = 400;

    public static int ballX(Context ctx) {
        return sp(ctx).getInt("ball_x", BALL_DEFAULT_X);
    }

    public static int ballY(Context ctx) {
        return sp(ctx).getInt("ball_y", BALL_DEFAULT_Y);
    }

    /** 把悬浮球位置复位到默认。 */
    public static void resetBallPos(Context ctx) {
        setBallPos(ctx, BALL_DEFAULT_X, BALL_DEFAULT_Y);
    }

    public static void setBallPos(Context ctx, int x, int y) {
        sp(ctx).edit().putInt("ball_x", x).putInt("ball_y", y).apply();
    }

    // ------------------------------------------------------------------
    // 息屏省电（实验室）
    // ------------------------------------------------------------------

    /** 息屏闲置后释放 CPU 唤醒锁（默认关）。 */
    public static boolean wakeSaverEnabled(Context ctx) {
        return sp(ctx).getBoolean("wake_saver", false);
    }

    public static void setWakeSaverEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("wake_saver", on).apply();
    }

    /** 闲置多少分钟后释放（默认 10，范围 1-240）。 */
    public static int wakeSaverMinutes(Context ctx) {
        int m = sp(ctx).getInt("wake_saver_minutes", 10);
        return m >= 1 && m <= 240 ? m : 10;
    }

    public static void setWakeSaverMinutes(Context ctx, int m) {
        sp(ctx).edit().putInt("wake_saver_minutes", Math.max(1, Math.min(240, m))).apply();
    }

    public static boolean monet(Context ctx) {
        return sp(ctx).getBoolean("monet", false);
    }

    public static void setMonet(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("monet", on).apply();
    }

    /** 主题种子色索引（0..7，对应 M3.SEEDS）。 */
    public static int seed(Context ctx) {
        int i = sp(ctx).getInt("seed", 0);
        return Math.max(0, Math.min(M3.SEEDS.length - 1, i));
    }

    public static void setSeed(Context ctx, int i) {
        sp(ctx).edit().putInt("seed", Math.max(0, Math.min(M3.SEEDS.length - 1, i))).apply();
    }

    /** The key the Web UI asks for. Generated once and kept, so the WebView
     *  can be pointed at {@code /?k=…} without scraping magpie's stdout. */
    public static synchronized String webKey(Context ctx) {
        // 已固定（locked 有效）就永远用它，不因任何重置而变。
        String locked = sp(ctx).getString(WEBKEY_LOCKED, "");
        if (locked != null && locked.length() >= 8) {
            return locked;
        }
        String k = sp(ctx).getString(WEBKEY, null);
        if (k == null || k.length() < 8) {
            k = newKey();
            sp(ctx).edit().putString(WEBKEY, k).apply();
        }
        return k;
    }

    /** 当前固定的 key（没固定返回 ""）。 */
    public static synchronized String webKeyLocked(Context ctx) {
        String v = sp(ctx).getString(WEBKEY_LOCKED, "");
        return v == null ? "" : v;
    }

    /** 固定当前 key：把当前 webKey 写入 locked，并确保 WEBKEY 也是它。返回固定后的值。 */
    public static synchronized String lockWebKey(Context ctx) {
        String cur = webKey(ctx); // 现有：读 WEBKEY，空/短则生成
        sp(ctx).edit().putString(WEBKEY_LOCKED, cur).putString(WEBKEY, cur).apply();
        return cur;
    }

    /** 是否已固定 key。 */
    public static boolean isWebKeyLocked(Context ctx) {
        return !webKeyLocked(ctx).isEmpty();
    }

    public static void setHost(Context ctx, String h) {
        if (h != null && !h.trim().isEmpty()) {
            sp(ctx).edit().putString(HOST, h.trim()).apply();
        }
    }

    /** 控制后台最后停留的完整 URL（含 ?k=，仅 http/https 内部页）。 */
    public static String lastWebUrl(Context ctx) {
        String v = sp(ctx).getString("last_web_url", "");
        return v == null ? "" : v;
    }

    public static void setLastWebUrl(Context ctx, String url) {
        if (url == null) {
            return;
        }
        String u = url.trim();
        if (u.isEmpty() || u.length() > 2000) {
            return;
        }
        String low = u.toLowerCase(java.util.Locale.US);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            return; // 只存 http(s)，别存 javascript:/data:
        }
        sp(ctx).edit().putString("last_web_url", u).apply();
    }

    public static void setPort(Context ctx, String p) {
        try {
            int v = Integer.parseInt(p.trim());
            // 端口下限 1024：<1024 是特权端口，普通 App 绑定会 EACCES 空转重试。
            if (v >= 1024 && v < 65536) {
                sp(ctx).edit().putString(PORT, String.valueOf(v)).apply();
            }
        } catch (Exception ignored) {
        }
    }

    public static void setLan(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(LAN, on).apply();
    }

    /** 是否从系统「最近任务/多任务」里隐藏（防手滑划掉）。 */
    public static boolean hideFromRecents(Context ctx) {
        return sp(ctx).getBoolean("hide_recents", false);
    }

    public static void setHideFromRecents(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("hide_recents", on).apply();
    }

    public static void setAutostart(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(AUTOSTART, on).apply();
    }

    public static void setUpdateFeed(Context ctx, String url) {
        if (url == null || url.trim().isEmpty()) {
            sp(ctx).edit().remove(UPDATE_FEED).apply();
        } else {
            sp(ctx).edit().putString(UPDATE_FEED, url.trim()).apply();
        }
    }

    /** magpie's own key format: 16 random bytes, hex — see
     *  gui.webKey/gui.NewWebKey in the Go source. */
    private static String newKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }
}
