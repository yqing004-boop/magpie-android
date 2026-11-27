package com.magpie.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 直接跟本机 magpie 的 HTTP 接口说话，绕开 WebView。
 *
 * <p>magpie 的网页只是它 API 的一个消费方：网页能看到的用量、额度、路由，
 * 原生界面调同一批接口就能拿到，省掉一整个 WebView 的渲染开销。
 *
 * <p>鉴权跟壳里 WebView 用的是同一个 Cookie：{@code magpie_web_<webPort>=<webKey>}
 * （见 magpie internal/gui/web.go 的 webGuard）。
 *
 * <p>这些是 magpie 的内部接口，不是稳定公开 API。所以每个方法都只回
 * {@link Result}，解析失败由调用方自己兜底 —— 界面永远不能因为 magpie
 * 改了字段就整页白屏。
 */
final class MagpieApi {

    private static final String TAG = "MagpieApi";

    /**
     * 单次响应读多大就停。
     *
     * <p>{@code /api/gateway/history?day=<今天>} 在忙碌的一天能到 17 MB
     * （每条请求都带着完整的 order/left 路由池），8 MB 根本不够——截断后
     * JSON 解析失败，页面上就是「没有记录」。给到 64 MB 留足余量。
     */
    private static final int MAX_BODY = 64 << 20;

    // ------------------------------------------------------------------
    // 超时：集中放这里，别每个调用点各写一套
    // ------------------------------------------------------------------

    /** 本机回环，连不上基本就是网关没起来，不用等。 */
    static final int CONNECT_MS = 6000;
    /** 普通 JSON（用量、分组、供应商缓存）。 */
    static final int READ_MS = 25000;
    /** 要问供应商的（额度），本来就慢。 */
    static final int READ_SLOW_MS = 32000;
    /** 长轮询挂住的上限，比网关自己的 25 秒多一点。 */
    static final int READ_POLL_MS = 26000;
    /** 写类操作（保存供应商会顺手拉模型表）。 */
    static final int WRITE_MS = 120000;

    private MagpieApi() {
    }

    static final class Result {
        int code;
        String body;
        String error;
        /** 响应超过 {@link #MAX_BODY} 被截断了。 */
        boolean truncated;
        /** magpie 说「额度正读着，这还只是旧卡」时为 true（X-Magpie-Reading: 1）。 */
        boolean reading;

        boolean ok() {
            // 截断的响应 body 是残缺串，解析会失败：当成失败走 why() 提示，别让调用方进成功分支后拿到 null。
            return error == null && code == 200 && body != null && !truncated;
        }

        JSONObject json() {
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                return null;
            }
        }

        JSONArray array() {
            try {
                return new JSONArray(body);
            } catch (Exception e) {
                return null;
            }
        }

        /** 给界面看的一句话：连不上 / 401 / 500 / 解析失败。 */
        String why() {
            if (error != null) {
                return error;
            }
            if (code == 401) {
                return "密钥不对（magpie 拒绝了这次请求）";
            }
            if (code != 200) {
                return "magpie 返回 " + code;
            }
            if (truncated) {
                return "返回内容超过 " + (MAX_BODY >> 20) + "MB 被截断";
            }
            return "返回内容读不出来";
        }
    }

    static String base(Context ctx) {
        return "http://" + Prefs.browserHost(ctx) + ":" + GatewayService.webPort(ctx);
    }

    static String cookie(Context ctx) {
        return "magpie_web_" + GatewayService.webPort(ctx) + "=" + Prefs.webKey(ctx);
    }

    // ------------------------------------------------------------------
    // 具体接口
    // ------------------------------------------------------------------

    /** 一个周期的用量总览：总量、时间序列、按 agent/模型/账号分组。 */
    static Result usage(Context ctx, String period) {
        return get(ctx, "/api/usage?period=" + period, CONNECT_MS, READ_MS);
    }

    /** 请求明细（账本）：rows / total / by / through / direct。 */
    static Result requests(Context ctx, String period, int limit) {
        return get(ctx, "/api/usage/requests?period=" + period + "&limit=" + limit,
                CONNECT_MS, READ_MS);
    }

    /**
     * 按供应商/模型筛选的用量聚合：走 {@code /api/usage/requests}（limit=1，只取顶层聚合，不含明细行）。
     *
     * <p>provider/model 传空串表示「全部」。返回的 Result.body 顶层结构与 {@code /api/usage} 类似：
     * calls/errors/retried/canceled/input/output/cache_read/cache_write/reasoning/cost/unpriced/
     * timed/ttft_ms/decode_ms/decode_out/decode_n/rates/up_bytes/down_bytes… + series[] + by{...}。
     */
    public static Result usageFiltered(Context ctx, String period, String provider, String model) {
        StringBuilder q = new StringBuilder("/api/usage/requests?period=");
        q.append(android.net.Uri.encode(period));
        if (provider != null && !provider.isEmpty()) {
            q.append("&provider=").append(android.net.Uri.encode(provider));
        }
        if (model != null && !model.isEmpty()) {
            q.append("&model=").append(android.net.Uri.encode(model));
        }
        q.append("&limit=1");
        return get(ctx, q.toString(), CONNECT_MS, READ_MS);
    }

    /**
     * 额度卡。magpie 读供应商要十几秒：第一次问往往先给旧卡，并在
     * X-Magpie-Reading: 1 里说「正在读」。这里不等它读完 —— 先把手上的画出来，
     * 页面看到 {@link Result#reading} 自己再排一次延后重取。
     */
    static Result quotas(Context ctx) {
        return get(ctx, "/api/usage/quotas", CONNECT_MS, READ_SLOW_MS);
    }

    /** 路由组：成员顺序、会话保持、首字超时。 */
    static Result groups(Context ctx) {
        return get(ctx, "/api/groups", CONNECT_MS, READ_MS);
    }

    /** 网关 + 供应商（很大，只在账号页需要启停标记时才拉）。 */
    static Result providersLast(Context ctx) {
        return get(ctx, "/api/providers?last=1", CONNECT_MS, READ_MS);
    }

    /**
     * 供应商全量列表。
     *
     * <p>为什么要有这个：{@code ?last=1} 返回的是「上一次读到的缓存」，
     * 如果这台手机从来没打开过网页的供应商页，缓存就是 {@code null}，
     * 原生界面于是拿不到任何 keyList —— 启停按钮就永远找不到账号
     * （抖「这个账号没在供应商列表里找到」）。所以找不到时用这个兜底。
     *
     * <p>这个响应比 last=1 大（含模型表），所以只在必要时用。
     */
    static Result providersFull(Context ctx) {
        return get(ctx, "/api/providers", CONNECT_MS, READ_SLOW_MS);
    }

    /**
     * 全量供应商列表 + 强制拉一次新模型。
     *
     * <p>{@code ?wait} 会让 magpie 同步执行 {@code provider.FetchNew}（最多等
     * 8 秒），所以返回的模型表是**刚向各家供应商要过的**，而不是上次缓存。
     * 这是「手动刷新模型」按钮背后的那个接口。
     */
    static Result providersWait(Context ctx) {
        return get(ctx, "/api/providers?wait", CONNECT_MS, WRITE_MS);
    }

    /** 一条请求的原文：{@code {headers, requestBody, responseBody, …}}。 */
    static Result gatewayCall(Context ctx, long routeId) {
        return get(ctx, "/api/gateway/call?route=" + routeId, CONNECT_MS, READ_SLOW_MS);
    }

    /** 设置与网关状态（?last=1 是上次缓存，秒回）。 */
    static Result stateLast(Context ctx) {
        return get(ctx, "/api/state?last=1", CONNECT_MS, READ_MS);
    }

    // ------------------------------------------------------------------
    // 传输层
    // ------------------------------------------------------------------

    static Result get(Context ctx, String path, int connectMs, int readMs) {
        return req(ctx, "GET", path, null, connectMs, readMs);
    }

    static Result post(Context ctx, String path, String json, int readMs) {
        return req(ctx, "POST", path, json, CONNECT_MS, readMs);
    }

    private static Result req(Context ctx, String method, String path, String body,
                              int connectMs, int readMs) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            URL url = new URL(base(ctx) + path);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setRequestProperty("Cookie", cookie(ctx));
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            r.code = c.getResponseCode();
            r.reading = "1".equals(c.getHeaderField("X-Magpie-Reading"));
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
                r.truncated = readInto(in, buf);
                // 直接从缓冲解出字符串，省掉 toByteArray() 的那份整块拷贝（峰值内存减半）。
                r.body = buf.toString(StandardCharsets.UTF_8.name());
            } else {
                r.body = "";
            }
        } catch (Throwable t) {
            // 兜住 OOM 等 Error：大响应（history 一天可达 17MB）解析时可能抛 OutOfMemoryError，
            // 之前只 catch(Exception) 接不住会让整页崩掉。这里转成一句可读错误。
            r.error = t instanceof OutOfMemoryError
                    ? "内容太大，内存不足（可减少 history 天数或稍后重试）"
                    : message(t);
            Log.w(TAG, method + " " + path + " 失败: " + r.error);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    /** 把 in 读完塞进 buf；超过上限就停并返回 true。 */
    private static boolean readInto(InputStream in, ByteArrayOutputStream buf) throws Exception {
        byte[] b = new byte[1 << 15];
        int n;
        try {
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
                if (buf.size() > MAX_BODY) {
                    return true;
                }
            }
            return false;
        } finally {
            in.close();
        }
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        if (m.contains("Connection refused") || m.contains("ECONNREFUSED")) {
            return "网关没在运行";
        }
        if (m.contains("timed out") || m.contains("timeout")) {
            return "网关响应超时";
        }
        return m;
    }
}
