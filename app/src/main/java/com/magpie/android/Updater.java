package com.magpie.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 在 App 里把 magpie 本体更新掉。
 *
 * <p>magpie 是个 Go 程序：App 只是壳，真正干活的是 {@code filesDir/magpie/magpie}
 * 这个 ELF。上游把每个版本发布成 yetone/magpie-releases 的 GitHub Release，
 * 并在 {@code https://usemagpie.ai/api/latest} 给出同一版本的清单（版本号、更新
 * 说明、每个文件的 SHA-256）。所以更新就是四件事：
 *
 * <ol>
 *   <li>问清单：最新版本是多少，比本机新吗；</li>
 *   <li>下载 {@code magpie-cli-android-arm64}（约 70 MB，支持断点续传）；</li>
 *   <li>核对 SHA-256，跑一次 {@code magpie.new version} 做冒烟测试（直接 exec
 *       不行就走 {@code /system/bin/linker64}，和 GatewayService 启动时一样）；</li>
 *   <li>把旧文件留成 {@code magpie.bak}，新文件 rename 到位，再让
 *       GatewayService 重启 —— 正在跑的老进程继续用老 inode，新进程用新文件，
 *       中间不空窗。</li>
 * </ol>
 *
 * <p>为什么不直接跑 {@code magpie update}：本工程自建的二进制是
 * {@code GOOS=linux} 编的，magpie 自己算出来的资产名是
 * {@code magpie-cli-linux-arm64}，那是桌面 GUI 版（动态链接 glibc + GTK），
 * Android 上会段错误。所以资产名必须由壳自己按 ABI 指定为
 * {@code magpie-cli-android-arm64}。
 */
public final class Updater {

    private static final String TAG = "MagpieUpdater";

    /** 官方更新清单：版本、说明、每个文件的 SHA-256。 */
    public static final String DEFAULT_FEED = "https://usemagpie.ai/api/latest";

    /** 备用源：GitHub Releases API。未认证时限流很凶，所以只在官方源失败时用。 */
    public static final String GITHUB_FEED =
            "https://api.github.com/repos/yetone/magpie-releases/releases/latest";

    /** 更新说明显示的 GitHub 页面。 */
    public static final String RELEASES_PAGE =
            "https://github.com/yetone/magpie-releases/releases";

    public interface Step {
        void status(String text);

        /** done/total 都是字节；total<=0 表示未知。 */
        void progress(long done, long total);
    }

    /** 清单里的一条发布。 */
    public static final class Release {
        public String version = "";
        public String notes = "";
        public String page = "";
        public String assetName = "";
        public String assetUrl = "";
        public String sha256 = "";
        public long size = -1;

        public boolean hasAsset() {
            return assetUrl != null && assetUrl.length() > 0;
        }

        @Override
        public String toString() {
            return version + (hasAsset() ? " (" + assetName + ")" : "");
        }
    }

    // ------------------------------------------------------------------
    // 检查更新的进度（Activity 重建后还能接着看）
    // ------------------------------------------------------------------

    private static volatile boolean running;
    private static volatile String stage = "";
    private static volatile long done = -1;
    private static volatile long total = -1;
    private static volatile String error = "";
    private static volatile Release checked;

    public static boolean isRunning() {
        return running;
    }

    public static String stage() {
        return stage;
    }

    public static long done() {
        return done;
    }

    public static long total() {
        return total;
    }

    public static String error() {
        return error;
    }

    /** 上一次 {@link #latest} 拿到的清单，重进界面时先显示它。 */
    public static Release lastChecked() {
        return checked;
    }

    private Updater() {
    }

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------

    /** 本机二进制的版本，例如 "0.1.3307"；自建的壳版本是 "android-1.0"。 */
    public static String localVersion(Context ctx) {
        File bin = GatewayService.binary(ctx);
        if (!bin.exists()) {
            return "";
        }
        try {
            return clean(runVersion(ctx, bin));
        } catch (IOException e) {
            Log.w(TAG, "读本机版本失败: " + e);
            return "";
        }
    }

    /** 把 "magpie 0.1.3307\n" 收成 "0.1.3307"。 */
    static String clean(String out) {
        if (out == null) {
            return "";
        }
        String s = out.trim();
        if (s.length() == 0) {
            return "";
        }
        // 可能有多行（日志、警告），挑第一行带 magpie 的
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            int at = line.toLowerCase(Locale.ROOT).indexOf("magpie");
            if (at >= 0) {
                line = line.substring(at + 6).trim();
            }
            if (line.length() > 0) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    line = line.substring(0, sp);
                }
                return line;
            }
        }
        return "";
    }

    /** "1.2.3" → {1,2,3,0}；不是纯数字的版本（自建、git describe）返回 null。 */
    static int[] semver(String v) {
        String s = clean(v);
        if (!s.matches("\\d+(\\.\\d+){0,2}")) {
            return null;
        }
        String[] parts = s.split("\\.");
        int[] r = new int[3];
        for (int i = 0; i < parts.length && i < 3; i++) {
            try {
                r[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return r;
    }

    /** latest 比 local 新吗。自建版本（android-1.0）没有可比性，只要有发布就算新。 */
    public static boolean newer(String latest, String local) {
        if (latest == null || latest.trim().length() == 0) {
            return false;
        }
        String l = clean(local);
        String r = clean(latest);
        if (l.equals(r)) {
            return false;
        }
        int[] a = semver(r);
        int[] b = semver(l);
        if (a == null) {
            return false;
        }
        if (b == null) {
            // 自建版（android-1.0）或者根本读不出版本：任何官方发布都值得换
            return true;
        }
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 问清单
    // ------------------------------------------------------------------

    static String feed(Context ctx) {
        String custom = Prefs.updateFeed(ctx);
        if (custom != null && custom.trim().length() > 0) {
            return custom.trim();
        }
        return DEFAULT_FEED;
    }

    /** 本机该下哪个文件。壳里只发 arm64 / armv7 的 ELF。 */
    public static String assetName() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        if (abi.startsWith("arm64")) {
            return "magpie-cli-android-arm64";
        }
        if (abi.startsWith("armeabi") || abi.startsWith("arm")) {
            return "magpie-cli-android-arm";
        }
        return "magpie-cli-android-arm64";
    }

    /**
     * 拉最新发布。先问设置里的源（默认 usemagpie.ai），失败再问 GitHub。
     * 拿不到就抛 IOException，消息直接给人看。
     */
    public static Release latest(Context ctx) throws IOException {
        String primary = feed(ctx);
        IOException first = null;
        try {
            Release r = parse(ctx, fetchJson(ctx, primary));
            checked = r;
            return r;
        } catch (IOException e) {
            first = e;
            Log.w(TAG, "更新源 " + primary + " 失败: " + e);
        }
        if (!primary.startsWith(GITHUB_FEED)) {
            try {
                Release r = parse(ctx, fetchJson(ctx, GITHUB_FEED));
                checked = r;
                return r;
            } catch (IOException e) {
                Log.w(TAG, "备用更新源失败: " + e);
                throw new IOException(first.getMessage() + "；GitHub 也没成：" + e.getMessage());
            }
        }
        throw first;
    }

    private static String fetchJson(Context ctx, String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                String body = "";
                try (InputStream in = c.getErrorStream()) {
                    if (in != null) {
                        body = readSmall(in);
                    }
                } catch (IOException ignored) {
                }
                throw new IOException("更新源返回 " + code
                        + (body.length() > 0 ? "：" + shorten(body, 160) : ""));
            }
            try (InputStream in = c.getInputStream()) {
                return readSmall(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** 两种源的 JSON 都吃：usemagpie 的 assets 是对象，GitHub 的是数组。 */
    static Release parse(Context ctx, String json) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            throw new IOException("更新源返回的不是 JSON");
        }
        if (o.optString("message", "").length() > 0 && o.optString("version", "").length() == 0) {
            throw new IOException("更新源说：" + shorten(o.optString("message"), 160));
        }

        Release r = new Release();
        String tag = o.optString("tag_name", "");
        if (tag.length() > 0) { // GitHub Releases API
            r.version = clean(tag);
            r.notes = o.optString("body", "");
            r.page = o.optString("html_url", "");
            JSONArray assets = o.optJSONArray("assets");
            String want = assetName();
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    if (name.equals(want) || (r.assetName.length() == 0 && name.startsWith("magpie-cli-android-"))) {
                        r.assetName = name;
                        r.assetUrl = a.optString("browser_download_url", "");
                        r.size = a.optLong("size", -1);
                        r.sha256 = digestFrom(a.optString("digest", ""));
                        if (name.equals(want)) {
                            break;
                        }
                    }
                }
            }
        } else { // usemagpie.ai/api/latest
            r.version = clean(o.optString("version", ""));
            r.notes = o.optString("notes", "");
            r.page = o.optString("url", "");
            JSONObject assets = o.optJSONObject("assets");
            String want = assetName();
            if (assets != null) {
                JSONObject a = assets.optJSONObject(want);
                if (a == null) {
                    // 名字对不上就挑第一个 android 的
                    java.util.Iterator<String> it = assets.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (k.startsWith("magpie-cli-android-") || k.startsWith("magpie-android-")) {
                            a = assets.optJSONObject(k);
                            break;
                        }
                    }
                }
                if (a != null) {
                    r.assetName = a.optString("name", want);
                    r.assetUrl = a.optString("url", "");
                    r.size = a.optLong("size", -1);
                    r.sha256 = digestFrom(a.optString("sha256", ""));
                }
            }
        }
        if (r.version.length() == 0) {
            throw new IOException("更新源没给版本号");
        }
        if (r.page.length() == 0) {
            r.page = RELEASES_PAGE;
        }
        if (r.assetName.length() == 0) {
            r.assetName = assetName();
        }
        return r;
    }

    private static String digestFrom(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("sha256:")) {
            s = s.substring(7);
        }
        return s.matches("[0-9a-f]{64}") ? s : "";
    }

    // ------------------------------------------------------------------
    // 下载 + 校验 + 落位
    // ------------------------------------------------------------------

    /** 更新用的临时目录：filesDir/magpie/update。 */
    static File updateDir(Context ctx) {
        return new File(GatewayService.rootDir(ctx), "update");
    }

    /**
     * 把 release 装到本机。返回装好的版本号。
     *
     * <p>线程阻塞（70 MB 下载），只能在后台线程调。
     */
    public static String install(Context ctx, Release rel, Step step) throws IOException {
        if (rel == null || !rel.hasAsset()) {
            throw new IOException("这条发布里没有安卓用的二进制（" + assetName() + "）");
        }
        File root = GatewayService.rootDir(ctx);
        File dir = updateDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }

        // 空间：要放得下新文件 + 一份备份
        long need = (rel.size > 0 ? rel.size : 80L * 1024 * 1024) * 2;
        StatFs fs = new StatFs(root.getAbsolutePath());
        long free = fs.getAvailableBytes();
        if (free < need) {
            throw new IOException(String.format(Locale.ROOT,
                    "存储空间不够：要 %s，只剩 %s", mb(need), mb(free)));
        }

        File part = new File(dir, rel.assetName + ".part");
        step.status("下载 " + rel.assetName + "（" + (rel.size > 0 ? mb(rel.size) : "未知大小") + "）…");
        download(ctx, rel, part, step);

        // 校验
        String sum = sha256(part);
        if (rel.sha256.length() > 0 && !rel.sha256.equalsIgnoreCase(sum)) {
            part.delete();
            throw new IOException("SHA-256 不对：期望 " + rel.sha256.substring(0, 16) + "…，实际 "
                    + sum.substring(0, 16) + "…（文件已删除，重试一次）");
        }
        if (rel.size > 0 && part.length() != rel.size) {
            part.delete();
            throw new IOException("大小不对：期望 " + rel.size + "，实际 " + part.length());
        }
        step.status("完整（" + mb(part.length()) + "，SHA-256 " + sum.substring(0, 12) + "…）");
        step.progress(part.length(), part.length());

        File staged = new File(dir, "magpie." + rel.version + ".new");
        if (staged.exists() && !staged.delete()) {
            throw new IOException("删不掉旧的 " + staged);
        }
        if (!part.renameTo(staged)) {
            copy(part, staged);
            part.delete();
        }
        chmod0755(staged);

        // 冒烟：跑得起来才算数，别把壳变砖
        step.status("试跑新版本…");
        String v;
        try {
            v = clean(runVersion(ctx, staged));
        } catch (IOException e) {
            staged.delete();
            throw new IOException("新二进制跑不起来，已放弃这次更新（" + e.getMessage() + "）");
        }
        if (v.length() == 0) {
            staged.delete();
            throw new IOException("新二进制没报版本，已放弃这次更新");
        }

        // 留一份能回滚的旧文件
        File cur = GatewayService.binary(ctx);
        File bak = new File(root, "magpie.bak");
        if (cur.exists()) {
            step.status("备份当前版本…");
            try {
                if (bak.exists()) {
                    bak.delete();
                }
                copy(cur, bak);
                chmod0755(bak);
            } catch (IOException e) {
                Log.w(TAG, "备份失败，继续更新: " + e);
            }
        }

        // Linux 上 rename 覆盖正在执行的 ELF 是原子的：老进程继续跑老 inode，
        // 重启后的新进程跑新文件。
        step.status("换上新版本 " + v + "…");
        if (!staged.renameTo(cur)) {
            copy(staged, cur);
            staged.delete();
        }
        chmod0755(cur);
        Prefs.noteInstalled(ctx, v, cur.length());
        step.status("已更新到 " + v);
        return v;
    }

    /** 用备份换回来。 */
    public static void rollback(Context ctx) throws IOException {
        File root = GatewayService.rootDir(ctx);
        File bak = new File(root, "magpie.bak");
        File cur = GatewayService.binary(ctx);
        if (!bak.exists() || bak.length() < 1024) {
            throw new IOException("没有可回滚的备份");
        }
        String v = clean(runVersion(ctx, bak));
        File tmp = new File(root, "magpie.rollback");
        copy(bak, tmp);
        chmod0755(tmp);
        if (!tmp.renameTo(cur)) {
            copy(tmp, cur);
            tmp.delete();
        }
        chmod0755(cur);
        Prefs.noteInstalled(ctx, v, cur.length());
    }

    /** GET asset 到 part，支持断点续传；边下边报进度。 */
    private static void download(Context ctx, Release rel, File part, Step step) throws IOException {
        long have = part.exists() ? part.length() : 0;
        if (rel.size > 0 && have > rel.size) {
            part.delete();
            have = 0;
        }
        if (rel.size > 0 && have == rel.size) {
            return; // 上次已经下完，交给校验
        }

        HttpURLConnection c = open(rel.assetUrl);
        if (have > 0) {
            c.setRequestProperty("Range", "bytes=" + have + "-");
        }
        int code;
        try {
            code = c.getResponseCode();
        } catch (IOException e) {
            c.disconnect();
            throw new IOException("连不上下载地址：" + e.getMessage());
        }
        if (code == 416) { // 续传位置越界，从头来
            c.disconnect();
            part.delete();
            download(ctx, rel, part, step);
            return;
        }
        if (code != 200 && code != 206) {
            String body = "";
            try (InputStream in = c.getErrorStream()) {
                if (in != null) {
                    body = readSmall(in);
                }
            } catch (IOException ignored) {
            }
            c.disconnect();
            throw new IOException("下载失败：" + code + (body.length() > 0 ? "：" + shorten(body, 120) : ""));
        }
        boolean resuming = code == 206 && have > 0;
        if (!resuming) {
            have = 0;
            part.delete();
        }
        long total = rel.size > 0 ? rel.size : (have + c.getContentLength());
        step.progress(have, total);

        try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part, resuming), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            long n = have;
            long last = 0;
            long lastAt = 0;
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                n += read;
                long now = System.currentTimeMillis();
                if (n - last > (1 << 20) || now - lastAt > 400) {
                    step.progress(n, total);
                    last = n;
                    lastAt = now;
                }
            }
            out.flush();
            if (out instanceof FileOutputStream) {
                ((FileOutputStream) out).getFD().sync();
            }
        } catch (IOException e) {
            throw new IOException("下载中断（" + mb(part.length()) + " 已保存，可续传）：" + e.getMessage());
        } finally {
            c.disconnect();
        }
        if (rel.size > 0 && part.length() != rel.size) {
            throw new IOException("下载不完整：" + part.length() + " / " + rel.size + "（可重试续传）");
        }
    }

    // ------------------------------------------------------------------
    // 跑二进制
    // ------------------------------------------------------------------

    /** 直接 exec；Android 10+ 拒绝时改走 /system/bin/linker64，和 GatewayService 一样。 */
    static String runVersion(Context ctx, File bin) throws IOException {
        try {
            return exec(ctx, bin, false);
        } catch (IOException direct) {
            Log.i(TAG, "直接 exec 失败（" + direct.getMessage() + "），改走 linker64");
            return exec(ctx, bin, true);
        }
    }

    private static String exec(Context ctx, File bin, boolean viaLinker) throws IOException {
        List<String> cmd = new ArrayList<>();
        if (viaLinker) {
            cmd.add("/system/bin/linker64");
        }
        cmd.add(bin.getAbsolutePath());
        cmd.add("version");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(GatewayService.rootDir(ctx));
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("HOME", GatewayService.homeDir(ctx).getAbsolutePath());
        env.put("PATH", bin.getParentFile().getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("LANG", "C.UTF-8");
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (sb.length() < 8192) {
                        sb.append(new String(buf, 0, r, "UTF-8"));
                    }
                }
            } catch (IOException ignored) {
            }
        }, "magpie-version");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean done = p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                throw new IOException("新二进制 25 秒没回话");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("被中断");
        }
        int code = p.exitValue();
        String out = sb.toString().trim();
        if (code != 0) {
            throw new IOException("退出码 " + code + (out.length() > 0 ? "：" + shorten(out, 200) : ""));
        }
        if (out.length() == 0) {
            throw new IOException("没有输出");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "magpie-android");
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    private static String readSmall(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        int n = 0;
        while ((r = in.read(buf)) > 0 && n < 1 << 20) {
            out.write(buf, 0, r);
            n += r;
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    md.update(buf, 0, r);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("没有 SHA-256", e);
        }
    }

    static void copy(File from, File to) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(from), 1 << 16);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            out.flush();
            out.getFD().sync();
        }
    }

    static void chmod0755(File f) {
        f.setReadable(true, false);
        f.setWritable(false, false);
        f.setExecutable(true, false);
    }

    private static String mb(long n) {
        if (n < 0) {
            return "未知";
        }
        if (n < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", n / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024.0));
    }

    private static String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ------------------------------------------------------------------
    // 全局状态（给界面用）
    // ------------------------------------------------------------------

    /** 界面轮询这些静态状态，所以下载中重建 Activity 也不会丢进度。 */
    public static void reset() {
        running = false;
        stage = "";
        done = -1;
        total = -1;
        error = "";
    }

    public static void markRunning(String what) {
        running = true;
        stage = what;
        done = -1;
        total = -1;
        error = "";
    }

    public static void markFailed(String why) {
        running = false;
        error = why == null ? "" : why;
    }

    public static void markDone() {
        running = false;
    }

    static void setStage(String s) {
        stage = s;
    }

    static void setProgress(long d, long t) {
        done = d;
        total = t;
    }
}
