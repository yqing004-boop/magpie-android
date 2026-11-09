package com.magpie.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 局域网分享的两块信息：手机在局域网里的地址，和网关密钥。
 *
 * <p>地址从网络接口枚举（Java 标准库，不用额外权限），过滤掉回环、链路本地、
 * VPN 的 fake-ip（Clash/Mihomo 的 198.18.0.0/15 —— 那是本机自己的，别的设备
 * 到不了），优先 wlan/ap 接口。
 *
 * <p>密钥是 magpie 核心的 caller key，明文存在
 * {@code filesDir/magpie/data/caller-keys.json} 的 {@code secret} 字段里。
 * 壳和 magpie 是同一个 uid，直接读文件；判定规则与内核的
 * {@code access.LANSecret()} 一致：settings.json 的 {@code lan} 开着时，先找
 * {@code lanKeyId} 指的或标着 {@code lan:true} 的、未停用的那条，找不到再回退
 * settings.json 的 {@code lanKey}（跳过已撤销的 {@code sk-magpie-revoked-} 占位）。
 *
 * <p>明文只在显式"复制"时写剪贴板，不进日志、不进 SharedPreferences。
 */
public final class LanInfo {

    private LanInfo() {
    }

    /** 手机在局域网里可能有的地址（Wi-Fi、热点、多个网卡）都列出来。 */
    public static List<String> localIps() {
        List<String> preferred = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) {
                        continue; // 只要 IPv4
                    }
                    byte[] b = a.getAddress();
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
                        continue;
                    }
                    // 198.18.0.0/15：Clash/Mihomo/sing-box 的 TUN fake-ip，别的设备到不了
                    if ((b[0] & 0xFF) == 198 && (b[1] & 0xFF) >= 18 && (b[1] & 0xFF) <= 19) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap")) {
                        preferred.add(ip); // Wi-Fi 或热点接口排前面
                    } else {
                        others.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败就当没有；调用方会给"没找到地址"的提示
        }
        preferred.addAll(others);
        return preferred;
    }

    /** 最可能可达的局域网地址；没有返回空串。 */
    public static String localIp() {
        List<String> ips = localIps();
        return ips.isEmpty() ? "" : ips.get(0);
    }

    /**
     * 共享用的网关密钥明文。共享没开、密钥被停用或已撤销时返回空串。
     */
    public static String lanSecret(Context ctx) {
        String data = new File(ctx.getFilesDir(), "magpie/data").getAbsolutePath();
        JSONObject settings = readObject(new File(data, "settings.json"));
        if (settings == null || !settings.optBoolean("lan", false)) {
            return "";
        }
        String lanKeyId = settings.optString("lanKeyId", "");
        String lanKey = settings.optString("lanKey", "");
        JSONArray keys = readArray(new File(data, "caller-keys.json"));
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                JSONObject k = keys.optJSONObject(i);
                if (k == null) {
                    continue;
                }
                boolean defaultKey = (lanKeyId != null && !lanKeyId.isEmpty()
                        && lanKeyId.equals(k.optString("id"))) || k.optBoolean("lan", false);
                if (defaultKey && !k.optBoolean("off", false)) {
                    String secret = k.optString("secret", "");
                    if (!secret.isEmpty() && !secret.startsWith("sk-magpie-revoked-")) {
                        return secret;
                    }
                }
            }
        }
        if (!lanKey.isEmpty() && !lanKey.startsWith("sk-magpie-revoked-")) {
            return lanKey;
        }
        return "";
    }

    /** 掩码形态：sk-magpie-key-…后六位，只在界面上展示。 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        String prefix = secret.startsWith("sk-magpie-key-") ? "sk-magpie-key-"
                : secret.startsWith("sk-magpie-") ? "sk-magpie-" : "";
        if (secret.length() > 8) {
            return (prefix.isEmpty() ? "" : prefix) + "…" + secret.substring(secret.length() - 6);
        }
        return "…";
    }

    private static JSONObject readObject(File f) {
        try {
            return new JSONObject(readAll(f));
        } catch (Exception e) {
            return null; // 文件还没有、magpie 正在写、解析失败 —— 都当没有
        }
    }

    private static JSONArray readArray(File f) {
        try {
            return new JSONArray(readAll(f));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(File f) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
