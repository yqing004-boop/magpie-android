package com.magpie.android;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 用量页：切周期、看排行、翻请求明细。
 *
 * <p>两个接口各管一半：
 * <ul>
 *   <li>{@code /api/usage?period=} —— 总量、时间序列、按 agent/模型/账号分组</li>
 *   <li>{@code /api/usage/requests?period=&limit=} —— 最近若干条请求的明细</li>
 * </ul>
 *
 * <p>周期按钮在页面顶部，点了就地重取，不切 tab。
 */
final class NativePageUsage extends NativePage {

    private String period = "today";
    private String providerFilter = "";
    private String modelFilter = "";
    private final java.util.List<String> providerOptions = new java.util.ArrayList<>();
    private final java.util.List<String> modelOptions = new java.util.ArrayList<>();
    private boolean pendingReload;

    private LinearLayout body;

    NativePageUsage(NativeHome host) {
        super(host);
    }

    @Override
    String name() {
        return "用量";
    }

    // ------------------------------------------------------------------
    // 骨架：周期条固定在上，下面整块重画
    // ------------------------------------------------------------------

    private void ensureSkeleton() {
        if (body != null) {
            return;
        }
        final int keepY = scroll.getScrollY();
        content.removeAllViews();
        if (keepY > 0) {
            scroll.post(() -> scroll.scrollTo(0, keepY));
        }
        content.addView(buildHeader(), 0);
        body = ui.column();
        content.addView(body, ui.matchWrap());
    }

    /** 周期胶囊条 + 供应商/模型下拉（两页共用 UsageFilterView）。 */
    private View buildHeader() {
        LinearLayout wrap = ui.column();
        wrap.setLayoutParams(ui.matchWrap());
        wrap.addView(UsageFilterView.periodBar(host, ui, period, v -> {
            if (v == null || v.equals(period)) {
                return;
            }
            period = v;
            requestReload();
        }));
        wrap.addView(UsageFilterView.dropdownRow(host, ui,
                "全部供应商", providerFilter, providerOptions,
                v -> {
                    providerFilter = v;
                    requestReload();
                },
                "全部模型", modelFilter, modelOptions,
                v -> {
                    modelFilter = v;
                    requestReload();
                }));
        return wrap;
    }

    /** 周期/筛选变化后重取；若正在加载就记下，等 done 后再重载（避免并发）。 */
    private void requestReload() {
        if (loading) {
            pendingReload = true;
            return;
        }
        refresh(true);
    }

    @Override
    LinearLayout renderTarget() {
        ensureSkeleton();
        return body;
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    @Override
    void load() {
        final String p = period;
        MagpieApi.Result base = MagpieApi.usage(host, p);
        if (!base.ok()) {
            host.post(() -> {
                period = p;
                body = null;
                ensureSkeleton();
                body.removeAllViews();
                failed(base.why());
                done();
            });
            return;
        }
        JSONObject baseJson = base.json();
        final java.util.List<String> pv = new java.util.ArrayList<>();
        final java.util.List<String> mv = new java.util.ArrayList<>();
        UsageFilterView.collectCandidates(baseJson, pv, mv);
        boolean filtered = (providerFilter != null && !providerFilter.isEmpty())
                || (modelFilter != null && !modelFilter.isEmpty());
        final JSONObject u;
        if (filtered) {
            MagpieApi.Result f = MagpieApi.usageFiltered(host, p, providerFilter, modelFilter);
            if (!f.ok()) {
                host.post(() -> {
                    period = p;
                    body = null;
                    ensureSkeleton();
                    body.removeAllViews();
                    failed(f.why());
                    done();
                });
                return;
            }
            u = UsageFilterView.adaptFiltered(f.json());
        } else {
            u = baseJson;
        }
        MagpieApi.Result req = MagpieApi.requests(host, p, 5);
        final JSONObject r = req.ok() ? req.json() : null;
        final String reqWhy = req.ok() ? null : req.why();
        host.post(() -> {
            period = p;
            providerOptions.clear();
            providerOptions.addAll(pv);
            modelOptions.clear();
            modelOptions.addAll(mv);
            body = null;
            ensureSkeleton();
            body.removeAllViews();
            render(u, r, reqWhy);
            done();
        });
    }

    @Override
    void done() {
        final boolean reload = pendingReload;
        pendingReload = false;
        super.done();
        if (reload) {
            host.post(() -> refresh(true));
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void render(JSONObject usage, JSONObject req, String reqWhy) {
        body.addView(summaryCard(usage));
        body.addView(modelsCard(usage));
        body.addView(agentsCard(usage));
        body.addView(requestsCard(req, reqWhy));
    }

    private View summaryCard(JSONObject usage) {
        LinearLayout card = ui.cardWithMargin();

        long in = NativeData.lng(usage, "input");
        long out = NativeData.lng(usage, "output");
        long cacheRead = NativeData.lng(usage, "cache_read");
        long cacheWrite = NativeData.lng(usage, "cache_write");
        long calls = NativeData.num(usage, "calls");
        long errors = NativeData.num(usage, "errors");
        long retried = NativeData.num(usage, "retried");
        double cost = NativeData.dec(usage, "cost");
        double hit = NativeData.cacheHit(usage, usage);

        LinearLayout head = ui.row();
        head.addView(ui.text(NativeData.periodName(period), 15, M3.ON_SURFACE, true),
                ui.weight(1));
        String since = NativeData.str(usage, "since", "");
        if (!since.isEmpty()) {
            head.addView(ui.text("自 " + NativeData.stamp(since), 11,
                    M3.ON_SURFACE_VARIANT, false));
        }
        card.addView(head);

        LinearLayout row1 = ui.row();
        row1.setLayoutParams(ui.matchWrap(14));
        row1.addView(ui.stat(NativeData.tokens(in), "输入", M3.ON_SURFACE, 1f));
        row1.addView(ui.stat(NativeData.tokens(out), "输出", M3.ON_SURFACE, 1f));
        row1.addView(ui.stat(NativeData.tokens(cacheRead), "缓存读", M3.ON_SURFACE, 1f));
        card.addView(row1);

        LinearLayout row2 = ui.row();
        row2.setLayoutParams(ui.matchWrap(14));
        row2.addView(ui.stat(NativeData.count(calls), "请求", M3.ON_SURFACE, 1f));
        row2.addView(ui.stat(NativeData.cost(cost), "费用", M3.ON_SURFACE, 1f));
        row2.addView(ui.stat(NativeData.percent(hit), "缓存命中", M3.ON_SURFACE, 1f));
        card.addView(row2);

        StringBuilder tail = new StringBuilder();
        if (errors > 0) {
            tail.append(errors).append(" 次失败");
        }
        if (retried > 0) {
            if (tail.length() > 0) {
                tail.append(" · ");
            }
            tail.append(retried).append(" 次重试成功");
        }
        long unknownCalls = NativeData.num(usage, "cache_unknown_calls");
        if (unknownCalls > 0) {
            if (tail.length() > 0) {
                tail.append(" · ");
            }
            tail.append(unknownCalls).append(" 次不计缓存");
        }
        if (cacheWrite > 0) {
            if (tail.length() > 0) {
                tail.append(" · ");
            }
            tail.append("缓存写 ").append(NativeData.tokens(cacheWrite));
        }
        if (tail.length() > 0) {
            TextView t = ui.text(tail.toString(), 11, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(12));
            card.addView(t);
        }
        return card;
    }

    // ------------------------------------------------------------------

    private View modelsCard(JSONObject usage) {
        LinearLayout card = ui.cardWithMargin();
        JSONArray models = NativeData.arr(usage, "models");

        LinearLayout head = ui.row();
        head.addView(ui.text("模型排行", 15, M3.ON_SURFACE, true), ui.weight(1));
        head.addView(ui.text(models.length() + " 个", 11, M3.ON_SURFACE_VARIANT, false));
        card.addView(head);

        if (models.length() == 0) {
            TextView t = ui.text("这个周期没有调用。", 12.5f, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(10));
            card.addView(t);
            return card;
        }

        long top = 1;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = NativeData.at(models, i);
            top = Math.max(top, NativeData.lng(m, "input") + NativeData.lng(m, "output"));
        }
        for (int i = 0; i < models.length() && i < 8; i++) {
            JSONObject m = NativeData.at(models, i);
            if (m == null) {
                continue;
            }
            card.addView(rankRow(m, top, i == 0));
        }
        if (models.length() > 8) {
            TextView more = ui.text("还有 " + (models.length() - 8) + " 个模型没列出来",
                    11, M3.ON_SURFACE_VARIANT, false);
            more.setLayoutParams(ui.matchWrap(8));
            card.addView(more);
        }
        return card;
    }

    /** 一条排行：名字 / 比例条 / 数字。 */
    private View rankRow(JSONObject g, long top, boolean first) {
        LinearLayout box = ui.column();
        box.setLayoutParams(ui.matchWrap(11));

        String model = NativeData.str(g, "model", NativeData.str(g, "id", "?"));
        String provider = NativeData.str(g, "provider", "");
        long tokens = NativeData.lng(g, "input") + NativeData.lng(g, "output");
        long calls = NativeData.num(g, "calls");
        double cost = NativeData.dec(g, "cost");

        LinearLayout head = ui.row();
        TextView name = ui.text(provider.isEmpty() ? model : model + " · " + NativeData.pretty(provider),
                12.5f, M3.ON_SURFACE, first);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(name, ui.weight(1));
        TextView val = ui.text(NativeData.tokens(tokens), 12, M3.ON_SURFACE, false);
        head.addView(val);
        box.addView(head);

        // 比例条：靠主色的透明度，不做第二套颜色
        LinearLayout track = new LinearLayout(host);
        track.setLayoutParams(ui.matchWrap(5));
        track.setBackground(ui.round(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 999));
        View fill = new View(host);
        fill.setBackground(ui.round(ui.col(M3.PRIMARY), 999));
        float w = Math.max(0.02f, (float) tokens / top);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ui.dp(5), w);
        LinearLayout.LayoutParams rest = new LinearLayout.LayoutParams(0, ui.dp(5), 1f - w);
        track.addView(fill, flp);
        track.addView(new View(host), rest);
        box.addView(track);

        String sub = NativeData.count(calls) + " 次";
        if (cost > 0) {
            sub += " · " + NativeData.cost(cost);
        }
        TextView s = ui.text(sub, 10.5f, M3.ON_SURFACE_VARIANT, false);
        s.setLayoutParams(ui.matchWrap(3));
        box.addView(s);
        return box;
    }

    // ------------------------------------------------------------------

    private View agentsCard(JSONObject usage) {
        LinearLayout card = ui.cardWithMargin();
        JSONArray agents = NativeData.arr(usage, "agents");

        LinearLayout head = ui.row();
        head.addView(ui.text("谁在用", 15, M3.ON_SURFACE, true), ui.weight(1));
        head.addView(ui.text(agents.length() + " 个", 11, M3.ON_SURFACE_VARIANT, false));
        card.addView(head);

        if (agents.length() == 0) {
            TextView t = ui.text("没有客户端调用记录。", 12.5f, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(10));
            card.addView(t);
            return card;
        }
        for (int i = 0; i < agents.length(); i++) {
            JSONObject a = NativeData.at(agents, i);
            if (a == null) {
                continue;
            }
            LinearLayout row = ui.row();
            row.setLayoutParams(ui.matchWrap(9));
            String id = NativeData.str(a, "id", "?");
            String nm = NativeData.str(a, "name", NativeData.pretty(id));
            row.addView(ui.text(nm, 12.5f, M3.ON_SURFACE, i == 0), ui.weight(1));
            long tok = NativeData.lng(a, "input") + NativeData.lng(a, "output");
            row.addView(ui.text(NativeData.count(NativeData.num(a, "calls")) + " 次 · "
                    + NativeData.tokens(tok), 11.5f, M3.ON_SURFACE_VARIANT, false));
            card.addView(row);
        }
        return card;
    }

    // ------------------------------------------------------------------
    // 请求明细
    // ------------------------------------------------------------------

    private View requestsCard(JSONObject req, String why) {
        LinearLayout card = ui.cardWithMargin();
        LinearLayout head = ui.row();
        head.addView(ui.text("最近请求", 15, M3.ON_SURFACE, true), ui.weight(1));
        if (req != null) {
            head.addView(ui.text(String.valueOf(NativeData.num(req, "total")) + " 条",
                    11, M3.ON_SURFACE_VARIANT, false));
        }
        card.addView(head);

        if (req == null) {
            TextView t = ui.text(why == null ? "明细读不到。" : "明细读不到：" + why,
                    12.5f, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(10));
            card.addView(t);
            return card;
        }

        JSONArray rows = NativeData.arr(req, "rows");
        if (rows.length() == 0) {
            TextView t = ui.text("这个周期还没有请求。", 12.5f, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(10));
            card.addView(t);
            return card;
        }

        int shown = 0;
        for (int i = 0; i < rows.length() && shown < 5; i++) {
            JSONObject r = NativeData.at(rows, i);
            if (r == null) {
                continue;
            }
            if (shown > 0) {
                card.addView(ui.divider());
            }
            card.addView(requestRow(r));
            shown++;
        }
        if (rows.length() > shown) {
            TextView more = ui.text("只显示最近 " + shown + " 条，完整列表去网页界面看",
                    11, M3.ON_SURFACE_VARIANT, false);
            more.setLayoutParams(ui.matchWrap(12));
            card.addView(more);
        }
        return card;
    }

    private View requestRow(JSONObject r) {
        LinearLayout box = ui.column();

        String model = NativeData.str(r, "model", "?");
        String provider = NativeData.str(r, "providerName",
                NativeData.str(r, "provider", ""));
        String agent = NativeData.str(r, "agentName", NativeData.str(r, "agent", ""));

        int status = NativeData.num(r, "status");
        boolean failed = status >= 400 || !NativeData.str(r, "err", "").isEmpty();
        long ms = (long) NativeData.dec(r, "ms");
        long ttft = (long) NativeData.dec(r, "ttft_ms");
        if (ttft <= 0) {
            ttft = (long) NativeData.dec(r, "ttft");
        }
        long tokens = NativeData.num(r, "in") + NativeData.num(r, "out");
        double cost = NativeData.dec(r, "cost");
        String time = NativeData.str(r, "t", "");

        LinearLayout head = ui.row();
        TextView t = ui.text(NativeData.clock(time), 11, M3.ON_SURFACE_VARIANT, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(t);

        TextView name = ui.text(model, 12.5f, failed ? M3.ERROR : M3.ON_SURFACE, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(name, ui.weight(1));

        // 缓存命中率：行自带 in/out/cache_read/cache_write；无数据/分母<=0 不显示
        double cacheHit = CacheHit.pct(r);
        if (cacheHit >= 0) {
            TextView ch = ui.text("缓存 " + NativeData.percent(cacheHit), 10,
                    M3.ON_SURFACE_VARIANT, false);
            LinearLayout.LayoutParams chlp = ui.wrap();
            chlp.leftMargin = ui.dp(6);
            head.addView(ch, chlp);
        }

        if (failed) {
            head.addView(ui.pill(status > 0 ? String.valueOf(status) : "失败",
                    M3.ERROR_CONTAINER, M3.ON_ERROR_CONTAINER));
        } else {
            // 首字 + 总耗时并列在第一行右侧，一眼可读
            StringBuilder times = new StringBuilder();
            if (ttft > 0) {
                times.append("首字 ").append(NativeData.ms(ttft));
            }
            if (ms > 0) {
                if (times.length() > 0) {
                    times.append(" · ");
                }
                times.append(NativeData.ms(ms));
            }
            if (times.length() > 0) {
                head.addView(ui.text(times.toString(), 11,
                        M3.ON_SURFACE_VARIANT, false));
            }
        }
        box.addView(head);

        StringBuilder sub = new StringBuilder();
        if (!provider.isEmpty()) {
            sub.append(NativeData.pretty(provider));
        }
        if (!agent.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(agent);
        }
        if (tokens > 0) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(NativeData.tokens(tokens)).append(" token");
        }
        if (cost > 0) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(NativeData.cost(cost));
        }
        String err = NativeData.str(r, "err", "");
        if (!err.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(err.length() > 60 ? err.substring(0, 60) + "…" : err);
        }

        TextView s = ui.text(sub.toString(), 10.5f,
                failed ? M3.ERROR : M3.ON_SURFACE_VARIANT, false);
        s.setLayoutParams(ui.matchWrap(4));
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(s);
        return box;
    }
}
