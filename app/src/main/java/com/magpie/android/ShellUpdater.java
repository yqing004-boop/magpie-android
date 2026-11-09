package com.magpie.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 检查这个 App（壳）自己在 GitHub 开源仓库里的新版本。
 *
 * <p>壳更新和内核更新是两回事：内核是 {@code filesDir/magpie/magpie} 那个 ELF，
 * 走 {@link Updater}；壳是安装的这个 APK，走这里。只做「提示 + 跳到 Release 页」，
 * 下载安装交给系统浏览器和安装器，壳不碰 APK 文件、不请求安装权限。
 *
 * <p>源是 GitHub Releases API 的 {@code /releases/latest}，公开仓库匿名可读
 * （未认证限流 60 次/小时，检查更新这点频率够用）。仓库地址在下面两个常量里，
 * 开源发布后填成真实的 owner/repo 再出包。
 */
public final class ShellUpdater {

    private static final String TAG = "MagpieShellUpdater";

    /** 开源仓库 owner。 */
    private static final String OWNER = "yqing004-boop";

    /** 开源仓库名。 */
    private static final String REPO = "magpie-android";

    public interface Callback {
        void result(Result result);
    }

    /** 一次检查的结果。 */
    public static final class Result {
        public final String latestTag;
        public final String page;
        public final String error;

        Result(String tag, String page, String error) {
            this.latestTag = tag;
            this.page = page;
            this.error = error;
        }

        public boolean ok() {
            return error == null && latestTag != null && !latestTag.isEmpty();
        }
    }

    private ShellUpdater() {
    }

    public static String releasesPage() {
        return "https://github.com/" + OWNER + "/" + REPO + "/releases";
    }

    /** 本 App 的版本号（versionName），例如 "1.0.25"。 */
    public static String localVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 后台查一次最新版本，结果回主线程。 */
    public static void check(Context ctx, Callback cb) {
        new Thread(() -> {
            Result result;
            try {
                result = fetch();
            } catch (IOException e) {
                Log.w(TAG, "检查壳更新失败: " + e);
                result = new Result("", "", e.getMessage());
            }
            final Result r = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.result(r));
        }, "magpie-shell-update").start();
    }

    private static Result fetch() throws IOException {
        String url = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "magpie-android-shell");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try {
            int code = c.getResponseCode();
            if (code == 404) {
                return new Result("", "", "仓库还没有 Release");
            }
            if (code != 200) {
                return new Result("", "", "GitHub 返回 " + code);
            }
            String body = readAll(c.getInputStream());
            try {
                JSONObject j = new JSONObject(body);
                String tag = j.optString("tag_name", "");
                String page = j.optString("html_url", releasesPage());
                return new Result(stripV(tag), page, null);
            } catch (org.json.JSONException e) {
                throw new IOException("GitHub 响应不是 JSON：" + e.getMessage());
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /** tag 里的 "v" 前缀去掉，好和 versionName 比较。 */
    static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        return t.length() > 1 && (t.charAt(0) == 'v' || t.charAt(0) == 'V')
                && Character.isDigit(t.charAt(1)) ? t.substring(1) : t;
    }

    /** 按 "1.0.25" 这种点分数字比较；latest > local 返回 true。 */
    public static boolean newer(String latest, String local) {
        if (latest == null || latest.isEmpty()) {
            return false;
        }
        if (local == null || local.isEmpty()) {
            return true;
        }
        String[] a = latest.split("\\.");
        String[] b = local.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim().replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
