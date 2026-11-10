package com.magpie.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

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
    public static final String LAN = "lan";
    public static final String AUTOSTART = "autostart";
    public static final String UPDATE_FEED = "update_feed";
    /** 更新渠道：true=稳定版（?channel=stable），false=每个版本。 */
    public static final String STABLE_CHANNEL = "stable_channel";
    /** 后台多久后释放 WebView（分钟）；0 = 从不。 */
    public static final String WEB_RELEASE_MINUTES = "web_release_minutes";
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

    /** 0=从不；其余值为后台释放 WebView 的分钟数。 */
    public static int webReleaseMinutes(Context ctx) {
        int m = sp(ctx).getInt(WEB_RELEASE_MINUTES, 0);
        return m >= 1 && m <= 1440 ? m : 0;
    }

    public static void setWebReleaseMinutes(Context ctx, int minutes) {
        int m = minutes >= 1 && minutes <= 1440 ? minutes : 0;
        sp(ctx).edit().putInt(WEB_RELEASE_MINUTES, m).apply();
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

    /** 是否已经把 magpie 自带的自动更新关掉（只做一次）。 */
    public static boolean updatesManaged(Context ctx) {
        return sp(ctx).getBoolean("updates_managed", false);
    }

    /**
     * 网页按桌面宽屏排版（图1 那样设置 tab 全展开）还是按手机窄屏排版。
     * 页面是 magpie 自带的 Web UI，排版完全由它自己的 CSS 响应式决定：
     * 窄屏下 tab 行要横滑，宽屏下全铺开。
     */
    public static boolean desktopMode(Context ctx) {
        return sp(ctx).getBoolean("desktop_mode", false);
    }

    public static void setDesktopMode(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("desktop_mode", on).apply();
    }

    public static void setUpdatesManaged(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("updates_managed", on).apply();
    }

    /** 莫奈取色：从系统壁纸里取强调色（Android 12+ 才有）。 */
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
    public static String webKey(Context ctx) {
        String k = sp(ctx).getString(WEBKEY, null);
        if (k == null || k.length() < 8) {
            k = newKey();
            sp(ctx).edit().putString(WEBKEY, k).apply();
        }
        return k;
    }

    public static void setHost(Context ctx, String h) {
        if (h != null && !h.trim().isEmpty()) {
            sp(ctx).edit().putString(HOST, h.trim()).apply();
        }
    }

    public static void setPort(Context ctx, String p) {
        try {
            int v = Integer.parseInt(p.trim());
            if (v > 0 && v < 65536) {
                sp(ctx).edit().putString(PORT, String.valueOf(v)).apply();
            }
        } catch (Exception ignored) {
        }
    }

    public static void setLan(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(LAN, on).apply();
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
