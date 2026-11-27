package com.magpie.android;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 概览：今天用了多少、还剩多少、网关怎么样。
 *
 * <p>三块内容，从上到下：
 * <ol>
 *   <li>今日数字：token / 请求 / 费用 / 缓存命中 —— 一张卡四列</li>
 *   <li>近 24 小时趋势：迷你柱状图</li>
 *   <li>额度卡横滑：每个账号还剩多少、什么时候重置</li>
 *   <li>网关状态：地址、端口、局域网</li>
 * </ol>
 *
 * <p>数据来源都在 magpie 的 /api/usage 与 /api/usage/quotas。
 */
final class NativePageOverview extends NativePage {

    /** 额度要问供应商，很贵：同一份结果 20 分钟内不重复拉。 */
    private static final long QUOTA_TTL = 20 * 60 * 1000L;

    /**
     * 还在读的时候重提不受 TTL 管。
     *
     * <p>原来的 bug：recheckLater 排了刷新，但刷新一看「两分钟内刚拉过」就
     * 直接拿缓存返回，于是 magpie 还在陆续回来的那几家永远没机会被装上来 ——
     * 页面上就永远只有第一批卡。
     */
    private volatile boolean quotaPending;

    /** 当前选的周期：今天 / 24 小时 / 7 天 / 30 天 / 全部。 */
    private String period = "today";
    /** 供应商 / 模型筛选（空串=全部）。 */
    private String providerFilter = "";
    private String modelFilter = "";
    private final java.util.List<String> providerOptions = new java.util.ArrayList<>();
    private final java.util.List<String> modelOptions = new java.util.ArrayList<>();

    private JSONArray cachedQuotas;
    /** 记下这份缓存对应的 QuotaCache 版本，变了就作废。 */
    private int cachedQuotaVersion = -1;
    private String cachedQuotaWhy;
    private long quotaAt;

    NativePageOverview(NativeHome host) {
        super(host);
    }

    @Override
    String name() {
        return "概览";
    }

    @Override
    void load() {
        // 用量是本地日志算的，很快就回来；额度要问供应商，可能十几秒。
        // 所以分两步画：先把数字摆上去，额度那块慢慢补，别让整页空着等。
        final String p = period;
        MagpieApi.Result base = MagpieApi.usage(host, p);
        if (!base.ok()) {
            String why = base.why();
            host.post(() -> {
                failed(why);
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
                String why = f.why();
                host.post(() -> {
                    failed(why);
                    done();
                });
                return;
            }
            u = UsageFilterView.adaptFiltered(f.json());
        } else {
            u = baseJson;
        }
        boolean fresh = !quotaPending && cachedQuotas != null
                && cachedQuotaVersion == QuotaCache.version()
                && System.currentTimeMillis() - quotaAt < QUOTA_TTL;
        JSONArray shown = fresh ? cachedQuotas : QuotaCache.snapshot();
        String shownWhy = fresh ? cachedQuotaWhy : null;

        host.post(() -> rebuildKeepingScroll(() -> {
            providerOptions.clear();
            providerOptions.addAll(pv);
            modelOptions.clear();
            modelOptions.addAll(mv);
            content.removeAllViews();
            content.addView(UsageFilterView.periodBar(host, ui, period, v -> onPeriodPicked(v)));
            content.addView(UsageFilterView.dropdownRow(host, ui,
                    "全部供应商", providerFilter, providerOptions,
                    v -> {
                        providerFilter = v;
                        refresh(true);
                    },
                    "全部模型", modelFilter, modelOptions,
                    v -> {
                        modelFilter = v;
                        refresh(true);
                    }));
            content.addView(heroCard(u));
            content.addView(trendCard(u));
            if (shown != null && shown.length() > 0) {
                content.addView(quotaSection(shown));
            } else {
                content.addView(quotaLoading(fresh));
            }
            content.addView(gatewayCard());
        }));

        if (fresh) {
            host.post(this::done);
            return;
        }

        MagpieApi.Result quotas = MagpieApi.quotas(host);
        JSONArray q = quotas.ok() ? QuotaCache.merge(quotas.array()) : QuotaCache.snapshot();
        String why = quotas.ok() ? null : quotas.why();
        boolean reading = quotas.ok() && quotas.reading;
        quotaPending = reading;
        if (quotas.ok()) {
            cachedQuotas = QuotaCache.snapshot();
            cachedQuotaVersion = QuotaCache.version();
            cachedQuotaWhy = null;
        } else {
            cachedQuotaWhy = why;
        }
        quotaAt = System.currentTimeMillis();

        host.post(() -> {
            // 只换掉额度那一块，上面的数字不动，滚动位置也不会跳
            View old = quotaBlock;
            int index = old == null ? -1 : content.indexOfChild(old);
            View now = q != null && q.length() > 0 ? quotaSection(q) : quotaEmpty(why);
            if (index >= 0) {
                content.removeViewAt(index);
                content.addView(now, index);
            } else {
                content.addView(now);
            }
            quotaBlock = now;
            done();
            // 卡还在读：过一会儿自己再问一次，别让用户手动刷
            if (reading) {
                recheckLater();
            } else {
                rechecksDone();
            }
        });
    }

    /** 额度那一块当前挂着的视图，用来原地替换。 */
    private View quotaBlock;

    private View quotaLoading(boolean stale) {
        LinearLayout card = ui.cardWithMargin();
        card.addView(ui.text("账号额度", 15, M3.ON_SURFACE, true));
        TextView t = ui.text(stale ? "正在重新读取额度…"
                        : "正在向各家供应商要额度，通常十几秒 —— 上面这些数字已经出来了，先看它们。",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        t.setLayoutParams(ui.matchWrap(8));
        t.setLineSpacing(ui.dp(2), 1f);
        card.addView(t);
        quotaBlock = card;
        return card;
    }

    // ------------------------------------------------------------------
    // 时间范围
    // ------------------------------------------------------------------

    /** 所有周期选项：值 → 显示名。跟网页那个下拉一致。 */
    /** 周期胶囊点了之后：换周期并重取（筛选保留）。 */
    private void onPeriodPicked(String v) {
        if (v == null || v.equals(period)) {
            return;
        }
        period = v;
        refresh(true);
    }

    private static final String[][] PERIODS = {
            {"today", "今天"},
            {"last:24h0m0s", "24 小时"},
            {"7d", "7 天"},
            {"30d", "30 天"},
            {"all", "全部"},
    };

    /**
     * 时间范围横条：一排胶囊，选中的那颗带底色。
     *
     * <p>网页是下拉菜单，手机上横排点一下就换更快，也少一次弹窗。
     */
    private View periodBar() {
        // 设计稿：下划线选中态（青色文字 + 短横线），等宽均分，无胶囊背景
        if (ui.design) {
            LinearLayout row = ui.row();
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(2));
            row.setLayoutParams(rlp);
            for (String[] opt : PERIODS) {
                final String value = opt[0];
                boolean on = value.equals(period);
                TextView t = ui.text(opt[1], 13, on ? M3.PRIMARY : M3.ON_SURFACE_VARIANT, on);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, ui.dp(8), 0, ui.dp(8));
                // 选中时文字下画一条细短下划线（2dp 高，更精致）
                t.setBackground(on ? underline(ui.col(M3.PRIMARY), ui.dp(16), ui.dp(2))
                        : null);
                t.setOnClickListener(v -> {
                    if (!value.equals(period)) {
                        period = value;
                        refresh(true);
                    }
                });
                row.addView(t, ui.weight(1));
            }
            return row;
        }

        LinearLayout row = ui.row();
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(2));
        row.setLayoutParams(rlp);

        for (String[] opt : PERIODS) {
            final String value = opt[0];
            boolean on = value.equals(period);
            TextView t = ui.text(opt[1], 12, on ? M3.ON_SECONDARY_CONTAINER
                    : M3.ON_SURFACE_VARIANT, on);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
            t.setBackground(on ? ui.round(ui.col(M3.SECONDARY_CONTAINER), 999) : null);
            t.setOnClickListener(v -> {
                if (value.equals(period)) {
                    return;
                }
                period = value;
                refresh(true);
            });
            LinearLayout.LayoutParams lp = ui.wrap();
            lp.rightMargin = ui.dp(6);
            row.addView(t, lp);
        }
        return row;
    }

    /** 设计稿分段标签的下划线：一条贴底的圆角短横。 */
    private android.graphics.drawable.Drawable underline(int color, int widthDp, int heightDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(ui.dp(1));
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{d});
        layer.setLayerSize(0, ui.dp(widthDp), ui.dp(heightDp));
        layer.setLayerGravity(0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        return layer;
    }

    // ------------------------------------------------------------------
    // 今日数字
    // ------------------------------------------------------------------

    /**
     * 汇总卡：完全照 magpie 网页 Usage 页那个 2×2 网格。
     *
     * <pre>
     * ┌─────────────────┬─────────────────┐
     * │ Token           │ 请求            │
     * │ 92M             │ 877             │
     * │ 输入 1.1M · 输出246K │ 没有失败     │
     * │ 缓存 90M        │ 11 次重试后成功  │
     * │                 │ 24 次已取消      │
     * │                 │ ↑438 MB · ↓66 MB│
     * ├─────────────────┼─────────────────┤
     * │ 费用            │ 缓存命中率       │
     * │ ≈ $0.078+       │ 87.1%           │
     * │ 565 次未计价     │ 1 个未计入 ▬▬▬  │
     * ├─────────────────┴─────────────────┤
     * │ 输出速度                          │
     * │ 176 token/秒  344 次计时           │
     * │ 首字平均 5.8 秒 · 546 次计时        │
     * └───────────────────────────────────┘
     * </pre>
     */
    private View heroCard(JSONObject usage) {
        long in = NativeData.lng(usage, "input");
        long out = NativeData.lng(usage, "output");
        long cacheRead = NativeData.lng(usage, "cache_read");
        long cacheWrite = NativeData.lng(usage, "cache_write");
        long calls = NativeData.num(usage, "calls");
        long errors = NativeData.num(usage, "errors");
        long retried = NativeData.num(usage, "retried");
        long canceled = NativeData.num(usage, "canceled");
        long upBytes = NativeData.lng(usage, "up_bytes");
        long downBytes = NativeData.lng(usage, "down_bytes");
        double cost = NativeData.dec(usage, "cost");
        long unpriced = NativeData.num(usage, "unpriced");
        long unknownCalls = NativeData.num(usage, "cache_unknown_calls");
        double hit = NativeData.cacheHit(usage, usage);
        long timed = NativeData.num(usage, "timed");
        long ttftMs = NativeData.num(usage, "ttft_ms");
        long decodeMs = NativeData.num(usage, "decode_ms");
        long decodeOut = NativeData.lng(usage, "decode_out");
        long total = in + out + cacheRead + cacheWrite;

        // 设计稿：2×2 独立发光卡片，每张卡一个大数字 + 一句副文，卡片之间有间隙
        if (ui.design) {
            return designHero(in, out, cacheRead, cacheWrite, calls, errors, retried,
                    canceled, upBytes, downBytes, cost, unpriced, unknownCalls, hit,
                    timed, ttftMs, decodeMs, decodeOut, total);
        }

        LinearLayout card = ui.cardWithMargin();
        card.setPadding(0, 0, 0, 0);

        // ---- 第一行：Token | 请求 ----
        LinearLayout row1 = ui.row();
        row1.setLayoutParams(ui.matchWrap(0));
        row1.addView(cell("Token", NativeData.tokens(total), null, null,
                splitLine(in, out, cacheRead, cacheWrite), M3.ON_SURFACE, 1f, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right1 = ui.column();
        right1.addView(cell("请求", NativeData.count(calls), null, null, null,
                M3.ON_SURFACE, 1f, false),
                wrap());
        StringBuilder reqNotes = new StringBuilder();
        if (errors == 0) {
            reqNotes.append("没有失败");
        } else {
            reqNotes.append(errors).append(" 次失败 · 成功 ")
                    .append(NativeData.percent(calls > 0 ? (calls - errors) * 100.0 / calls : 100));
        }
        if (retried > 0) {
            reqNotes.append("\n").append(retried).append(" 次重试后成功");
        }
        if (canceled > 0) {
            reqNotes.append("\n").append(canceled).append(" 次已取消");
        }
        if (upBytes + downBytes > 0) {
            reqNotes.append("\n↑ ").append(NativeData.bytes(upBytes))
                    .append(" · ↓ ").append(NativeData.bytes(downBytes));
        }
        right1.addView(note(reqNotes.toString(), errors > 0 ? M3.ERROR : M3.ON_SURFACE_VARIANT));
        row1.addView(right1, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row1);
        card.addView(ui.divider());

        // ---- 第二行：费用 | 缓存命中率 ----
        LinearLayout row2 = ui.row();
        String costText = (unpriced > 0 ? "≈ " : "") + NativeData.cost(cost)
                + (unpriced > 0 ? "+" : "");
        LinearLayout costCell = cell("费用", costText, null, null, null,
                M3.TERTIARY, 1f, true);
        if (unpriced > 0) {
            costCell.addView(note(unpriced + " 次调用未计价", M3.ON_SURFACE_VARIANT));
        }
        row2.addView(costCell, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout hitCell = cell("缓存命中率", NativeData.percent(hit), null, null, null,
                M3.ON_SURFACE, 1f, false);
        if (unknownCalls > 0) {
            hitCell.addView(note(unknownCalls + " 个请求未计入：其服务商不统计缓存",
                    M3.ON_SURFACE_VARIANT));
        }
        LinearLayout barWrap = ui.column();
        barWrap.setLayoutParams(wrap());
        barWrap.addView(ui.quotaBar(hit));
        hitCell.addView(barWrap);
        row2.addView(hitCell, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row2);
        card.addView(ui.divider());

        // ---- 第三行：输出速度 ----
        LinearLayout speed = cell("输出速度", null, null, null, null,
                M3.ON_SURFACE, 1f, false);
        LinearLayout line = ui.row();
        line.setGravity(android.view.Gravity.BOTTOM);
        line.setLayoutParams(ui.matchWrap(2));
        double speedVal = decodeMs > 0 ? decodeOut * 1000.0 / decodeMs : 0;
        String speedText = speedVal > 0
                ? (speedVal >= 100 ? String.valueOf(Math.round(speedVal))
                : String.format(java.util.Locale.US, "%.0f", speedVal)) : "—";
        line.addView(ui.bigNum(speedText));
        TextView unit = ui.text(" token/秒", 13, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams ulp = ui.wrap();
        ulp.bottomMargin = ui.dp(5);
        line.addView(unit, ulp);
        long decodeN = NativeData.lng(usage, "decode_n");
        if (decodeN > 0) {
            TextView cnt = ui.text("  " + decodeN + " 次计时", 12,
                    M3.ON_SURFACE_VARIANT, false);
            LinearLayout.LayoutParams clp = ui.wrap();
            clp.bottomMargin = ui.dp(5);
            line.addView(cnt, clp);
        }
        speed.addView(line);

        StringBuilder ttft = new StringBuilder();
        if (timed > 0) {
            ttft.append("首字平均 ").append(NativeData.ms(ttftMs / Math.max(1, timed)))
                    .append(" · ").append(timed).append(" 次计时");
        } else {
            ttft.append("没有计时数据");
        }
        speed.addView(note(ttft.toString(), M3.ON_SURFACE_VARIANT));
        card.addView(speed);
        return card;
    }

    /**
     * 设计稿版汇总：2×2 独立发光卡片。
     *
     * <p>跟网页版不同：网页是一个大卡用分割线分节，设计稿是四张独立的
     * 玻璃卡片，每张卡里「标签 + 大号无衬线数字 + 副文」三层，卡片之间
     * 有间隙、靠青色描边和阴影分层。信息保留全量，只是拆成四张。
     */
    private View designHero(long in, long out, long cacheRead, long cacheWrite,
                            long calls, long errors, long retried, long canceled,
                            long upBytes, long downBytes, double cost, long unpriced,
                            long unknownCalls, double hit, long timed, long ttftMs,
                            long decodeMs, long decodeOut, long total) {
        LinearLayout grid = ui.column();
        grid.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4));

        LinearLayout row1 = ui.row();
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1lp.bottomMargin = ui.dp(10);
        row1.setLayoutParams(r1lp);

        row1.addView(designCell("Token 用量", NativeData.tokens(total),
                (CharSequence) splitLine(in, out, cacheRead, cacheWrite)), designCellLp());

        // 请求卡：失败红 / 重试绿 / 取消黄 分色，流量在数字右侧竖排
        row1.addView(designCell("请求数量", NativeData.count(calls),
                coloredNotes(errors, calls, retried, canceled),
                trafficCorner(upBytes, downBytes)),
                designCellLp(true));
        grid.addView(row1);

        LinearLayout row2 = ui.row();
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.bottomMargin = ui.dp(10);
        row2.setLayoutParams(r2lp);
        String costText = (unpriced > 0 ? "≈ " : "") + NativeData.cost(cost)
                + (unpriced > 0 ? "+" : "");
        row2.addView(designCell("费用", costText,
                unpriced > 0 ? unpriced + " 次调用未计价" : "模型调用费用"), designCellLp());

        String hitNote = unknownCalls > 0
                ? unknownCalls + " 个未计入"
                : "节省 " + NativeData.count(Math.round(hit * calls / 100.0)) + " 次请求";
        row2.addView(designCell("缓存命中率", NativeData.percent(hit), hitNote),
                designCellLp(true));
        grid.addView(row2);

        double speedVal = decodeMs > 0 ? decodeOut * 1000.0 / decodeMs : 0;
        String speedText = speedVal > 0
                ? (speedVal >= 100 ? String.valueOf(Math.round(speedVal))
                : String.format(java.util.Locale.US, "%.0f", speedVal)) : "—";
        StringBuilder ttft = new StringBuilder();
        if (timed > 0) {
            ttft.append("首字平均 ").append(NativeData.ms(ttftMs / Math.max(1, timed)))
                    .append(" · ").append(timed).append(" 次计时");
        } else {
            ttft.append("没有计时数据");
        }
        LinearLayout speedCard = designCell("输出速度", speedText + " token/秒", ttft.toString());
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 和上面第二行卡片拉开距离，别贴死
        scp.topMargin = ui.dp(10);
        speedCard.setLayoutParams(scp);
        grid.addView(speedCard);
        return grid;
    }

    /** 设计稿单张数据卡的布局参数。 */
    private LinearLayout.LayoutParams designCellLp() {
        return designCellLp(false);
    }

    /** 设计稿单张数据卡的布局参数，last=是否最后一张（右边留白）。 */
    private LinearLayout.LayoutParams designCellLp(boolean last) {
        // 高度 MATCH_PARENT：同一行的两张卡等高对齐（请求卡副文多，
        // Token 卡跟着拉伸到一样高，不会一高一矮）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (!last) {
            lp.rightMargin = ui.dp(10);
        }
        return lp;
    }

    /** 设计稿单张数据卡：标签 + 大号无衬线数字 + 副文，独立发光卡片。 */
    private LinearLayout designCell(CharSequence title, String big, CharSequence sub) {
        return designCell(title, big, sub, null);
    }

    /**
     * 设计稿单张数据卡：标签 + 大号无衬线数字 + 副文，独立发光卡片。
     *
     * @param corner 右上角角标小字（如上下行流量），可为 null
     */
    private LinearLayout designCell(CharSequence title, String big, CharSequence sub,
                                    String corner) {
        LinearLayout box = ui.card();
        box.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        box.setGravity(Gravity.START);

        // 标题行：左边标签
        TextView t = ui.text(title.toString(), 13, M3.ON_SURFACE_VARIANT, false);
        t.setSingleLine(true);
        box.addView(t);

        // 数字行：左边大数字，右边可选的流量角标（竖排两行：↑一行 ↓一行）
        LinearLayout numRow = ui.row();
        // 字号和等宽跟随主题（经典 30sp 无衬线 / 紧凑 28sp tnum）
        TextView v = ui.bigNum(big);
        numRow.addView(v, ui.weight(1));
        if (corner != null && !corner.isEmpty()) {
            TextView cv = ui.text(corner, 11.5f, M3.ON_SURFACE_VARIANT, false);
            cv.setGravity(Gravity.CENTER_VERTICAL);
            cv.setLineSpacing(ui.dp(2), 1f);
            numRow.addView(cv);
        }
        LinearLayout.LayoutParams nrlp = ui.matchWrap(6);
        box.addView(numRow, nrlp);

        TextView s = new TextView(host);
        s.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
        s.setTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        s.setText(sub);
        s.setLineSpacing(ui.dp(2), 1f);
        // 副文三行（失败/重试/取消各一行），放不下才省略
        s.setMaxLines(3);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(s);
        return box;
    }

    /** 副文着色片段：失败红 / 重试绿 / 取消黄。流量放标题行角标，不在这里。 */
    private CharSequence coloredNotes(long errors, long calls, long retried, long canceled) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();

        android.text.style.CharacterStyle errStyle = new android.text.style.ForegroundColorSpan(
                ui.col(M3.ERROR));
        android.text.style.CharacterStyle okStyle = new android.text.style.ForegroundColorSpan(
                ui.col(M3.TERTIARY));
        android.text.style.CharacterStyle warnStyle = new android.text.style.ForegroundColorSpan(
                ui.col(M3.QUOTA_WARN));
        android.text.style.CharacterStyle dimStyle = new android.text.style.ForegroundColorSpan(
                ui.col(M3.ON_SURFACE_VARIANT));

        Runnable sep = () -> {
            if (sb.length() > 0) {
                sb.append("\n");
            }
        };

        // 第一行：失败 / 成功率
        int st = sb.length();
        if (errors == 0) {
            sb.append("没有失败");
            sb.setSpan(okStyle, st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            String fail = errors + " 次失败";
            sb.append(fail);
            sb.setSpan(errStyle, st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            st = sb.length();
            String ok = " · 成功 " + NativeData.percent(
                    calls > 0 ? (calls - errors) * 100.0 / calls : 100);
            sb.append(ok);
            sb.setSpan(okStyle, st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // 第二行：重试后成功（绿）
        if (retried > 0) {
            sep.run();
            st = sb.length();
            sb.append(String.valueOf(retried)).append(" 次重试成功");
            sb.setSpan(okStyle, st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // 第三行：已取消（黄）
        if (canceled > 0) {
            sep.run();
            st = sb.length();
            sb.append(String.valueOf(canceled)).append(" 次已取消");
            sb.setSpan(warnStyle, st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // 第四行不放了：上下行流量在标题行右侧角标
        return sb;
    }

    /** 请求卡大数字右侧的角标：竖排 ↑流量 / ↓流量。成功率只在有失败时出现在副文。 */
    private String trafficCorner(long upBytes, long downBytes) {
        if (upBytes + downBytes <= 0) {
            return null;
        }
        return "↑ " + NativeData.bytes(upBytes) + "\n↓ " + NativeData.bytes(downBytes);
    }

    /** 「输入 1.1M · 输出 246K · 缓存 90M」那一行。 */
    private String splitLine(long in, long out, long cacheRead, long cacheWrite) {
        StringBuilder sb = new StringBuilder();
        sb.append("输入 ").append(NativeData.tokens(in));
        sb.append(" · 输出 ").append(NativeData.tokens(out));
        if (cacheRead + cacheWrite > 0) {
            sb.append(" · 缓存 ").append(NativeData.tokens(cacheRead + cacheWrite));
        }
        return sb.toString();
    }

    /**
     * 网格里的一格：小标题 + 大数字（可选）+ 说明行。
     *
     * @param title 小标题（Token / 请求 / 费用…）
     * @param big   大数字，null 就是只有标题和说明
     * @param split 标题下面那行拆分说明
     * @param value 大数字的颜色令牌
     * @param bold  大数字是否加粗（网页上 Token 和请求是粗的）
     */
    private LinearLayout cell(String title, String big, String unused1, String unused2,
                              String split, int value, float weight, boolean bold) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(16), ui.dp(14), ui.dp(14), ui.dp(14));

        TextView t = ui.text(title, 12, M3.ON_SURFACE_VARIANT, false);
        box.addView(t);

        if (big != null) {
            TextView v = ui.text(big, 30, value, bold);
            LinearLayout.LayoutParams lp = ui.matchWrap(4);
            v.setLayoutParams(lp);
            box.addView(v);
        }
        if (split != null && !split.isEmpty()) {
            box.addView(note(split, M3.ON_SURFACE_VARIANT));
        }
        return box;
    }

    private TextView note(String text, int role) {
        TextView t = ui.text(text, 11.5f, role, false);
        t.setLineSpacing(ui.dp(2), 1f);
        t.setLayoutParams(ui.matchWrap(4));
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 输入 / 输出 / 缓存 三段的细比例条。 */
    private View splitBar(long in, long out, long cacheRead, long cacheWrite) {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        // 高度必须定死：里面的段是 MATCH_PARENT + weight，父高不定时
        // Android 会把它们量成整屏高（踩过一次）。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8));
        lp.topMargin = ui.dp(15);
        bar.setLayoutParams(lp);
        bar.setBackground(ui.round(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 999));
        bar.setClipToOutline(true);

        long total = in + out + cacheRead + cacheWrite;
        if (total <= 0) {
            return bar;
        }
        addSegment(bar, cacheRead, total, M3.PRIMARY);
        addSegment(bar, in, total, M3.SECONDARY);
        addSegment(bar, cacheWrite, total, M3.TERTIARY);
        addSegment(bar, out, total, M3.OUTLINE);
        return bar;
    }

    private void addSegment(LinearLayout bar, long part, long total, int role) {
        if (part <= 0) {
            return;
        }
        View v = new View(host);
        v.setBackgroundColor(ui.col(role));
        float w = Math.max(0.02f, (float) part / total);
        bar.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w));
    }

    private View splitLegend(long in, long out, long cacheRead, long cacheWrite) {
        LinearLayout row = ui.row();
        LinearLayout.LayoutParams lp = ui.matchWrap();
        lp.topMargin = ui.dp(7);
        row.setLayoutParams(lp);
        row.addView(legend("缓存读", cacheRead, M3.PRIMARY), ui.weight(1));
        row.addView(legend("输入", in, M3.SECONDARY), ui.weight(1));
        row.addView(legend("缓存写", cacheWrite, M3.TERTIARY), ui.weight(1));
        row.addView(legend("输出", out, M3.OUTLINE), ui.weight(1));
        return row;
    }

    private View legend(String label, long value, int role) {
        LinearLayout box = ui.row();
        View dot = new View(host);
        dot.setBackground(ui.round(ui.col(role), 999));
        box.addView(dot, new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)));
        TextView t = ui.text(label + " " + NativeData.tokens(value), 10.5f,
                M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams lp = ui.wrap();
        lp.leftMargin = ui.dp(5);
        box.addView(t, lp);
        return box;
    }

    // ------------------------------------------------------------------
    // 趋势
    // ------------------------------------------------------------------

    private View trendCard(JSONObject usage) {
        LinearLayout card = ui.cardWithMargin();
        JSONArray series = NativeData.arr(usage, "series");
        String bucket = NativeData.str(usage, "bucket", "hour");

        LinearLayout head = ui.row();
        head.addView(ui.text("用量趋势", 15, M3.ON_SURFACE, true), ui.weight(1));
        head.addView(ui.text("hour".equals(bucket) ? "按小时" : "按天",
                11.5f, M3.ON_SURFACE_VARIANT, false));
        card.addView(head);

        if (series.length() == 0) {
            // 空状态：照设计稿给一句友好的提示，而不是一行冷冰的「没有记录」
            TextView t = ui.text("这个周期还没有记录，去跟 agent 聊两句就有了。",
                    12.5f, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(12));
            t.setLineSpacing(ui.dp(2), 1f);
            card.addView(t);
            return card;
        }

        int n = series.length();
        float[] values = new float[n];
        String[] labels = new String[n];
        long peak = 0;
        String peakLabel = "";
        long sum = 0;
        for (int i = 0; i < n; i++) {
            JSONObject p = NativeData.at(series, i);
            // 逐日累加，单日就可能上亿；用 num() 会 int 溢出变负数，走势图整条塌掉
            long tok = NativeData.lng(p, "input") + NativeData.lng(p, "output")
                    + NativeData.lng(p, "cache_read") + NativeData.lng(p, "cache_write");
            values[i] = tok;
            sum += tok;
            labels[i] = NativeData.str(p, "label", "");
            if (tok > peak) {
                peak = tok;
                peakLabel = labels[i];
            }
        }

        // 设计稿那张：平滑曲线 + 渐变面积填充，比柱状图更贴近「开发者监控台」
        Ui.AreaChart chart = ui.areaChart();
        chart.setLayoutParams(ui.matchWrap(12));
        chart.setData(values, labels);
        card.addView(chart);

        LinearLayout foot = ui.row();
        foot.setLayoutParams(ui.matchWrap(8));
        foot.addView(ui.text("共 " + NativeData.tokens(sum) + " token", 11.5f,
                M3.ON_SURFACE_VARIANT, false), ui.weight(1));
        if (peak > 0) {
            foot.addView(ui.text("峰值 " + NativeData.tokens(peak)
                    + (peakLabel.isEmpty() ? "" : "（" + peakLabel + "）"),
                    11.5f, M3.ON_SURFACE_VARIANT, false));
        }
        card.addView(foot);
        return card;
    }

    // ------------------------------------------------------------------
    // 额度
    // ------------------------------------------------------------------

    private View quotaSection(JSONArray quotas) {
        LinearLayout wrap = ui.column();
        quotaBlock = wrap;

        boolean expanded = Collapse.expanded(host, "overview:quota", true);

        LinearLayout head = ui.row();
        head.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(6));
        head.addView(ui.text("账号额度", 13, M3.ON_SURFACE, true), ui.weight(1));
        head.addView(ui.text(quotas.length() + " 个账号", 11,
                M3.ON_SURFACE_VARIANT, false));
        TextView arrow = ui.text("", 12, M3.ON_SURFACE_VARIANT, false);
        arrow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
        alp.leftMargin = ui.dp(6);
        head.addView(arrow, alp);

        // 一张卡 = 一家供应商，卡上是这家的合计（余额去重相加 / 额度窗口按名字累加）。
        // 账号明细缩进一级放在下面，跟账号页是同一套台阶。
        Map<String, List<JSONObject>> byProvider = new LinkedHashMap<>();
        for (int i = 0; i < quotas.length(); i++) {
            JSONObject q = NativeData.at(quotas, i);
            if (q == null) {
                continue;
            }
            String p = NativeData.str(q, "provider", "?");
            List<JSONObject> list = byProvider.get(p);
            if (list == null) {
                list = new ArrayList<>();
                byProvider.put(p, list);
            }
            list.add(q);
        }

        LinearLayout cards = ui.column();
        for (Map.Entry<String, List<JSONObject>> e : byProvider.entrySet()) {
            cards.addView(providerQuotaCard(e.getKey(), e.getValue()));
        }

        Collapse.wire(host, head,
                new Collapse.Unit(arrow, cards, null, expanded), "overview:quota");
        wrap.addView(head);
        wrap.addView(cards);
        return wrap;
    }

    /**
     * 一家供应商一张卡：卡头写这家的合计，下面缩进列出每个账号。
     *
     * <p>合计就是「不用点开也知道这家还剩多少」：
     * <ul>
     *   <li>有余额 → 余额（共享账户去重，天枢 5 个 key 只算一次 $4.18）</li>
     *   <li>有额度窗口 → 按名字累加，优先显示叫 Total 的那个</li>
     *   <li>都没有 → 所有窗口里最紧的那个百分比</li>
     * </ul>
     */
    private View providerQuotaCard(String providerId, List<JSONObject> list) {
        LinearLayout card = ui.cardWithMargin();

        JSONObject first = list.get(0);
        String name = NativeData.str(first, "name", NativeData.pretty(providerId));
        String key = "overview:provquota:" + providerId;
        boolean expandedNow = Collapse.expanded(host, key, list.size() <= 1);

        LinearLayout head = ui.row();
        TextView arrow = ui.text("", 12, M3.ON_SURFACE_VARIANT, false);
        arrow.setGravity(Gravity.CENTER);
        head.addView(arrow, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));

        LinearLayout titleBox = ui.column();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(4);
        head.addView(titleBox, tlp);

        TextView title = ui.text(name, 14, M3.ON_SURFACE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(title);

        // 这家合计
        TextView total = ui.text(QuotaAgg.totalLine(list), 11.5f,
                M3.ON_SURFACE_VARIANT, false);
        total.setSingleLine(true);
        total.setEllipsize(android.text.TextUtils.TruncateAt.END);
        total.setLayoutParams(ui.matchWrap(3));
        titleBox.addView(total);

        // 合计后面挂一条进度条：条走「这家总已用」，颜色按「总剩余」判定
        double usedPct = QuotaAgg.headlineUsed(list);
        card.addView(head);
        if (usedPct >= 0) {
            card.addView(ui.quotaBar(usedPct, 100 - usedPct));
        }

        // 二级：每个账号一行，缩进
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(6), ui.dp(8), 0, 0);
        for (JSONObject q : list) {
            body.addView(accountLine(q));
        }
        Collapse.wire(host, head,
                new Collapse.Unit(arrow, body, null, expandedNow), key);
        card.addView(body);
        return card;
    }

    /** 概览页里的一个账号：一行名字 + 摘要，点开才露额度窗口。 */
    private View accountLine(JSONObject q) {
        String key = "acct:" + NativeData.str(q, "provider", "") + "|"
                + NativeData.str(q, "user", "") + "|"
                + NativeData.str(q, "alias", "");
        boolean expanded = Collapse.expanded(host, key, false);

        LinearLayout box = ui.column();
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = ui.dp(6);
        box.setLayoutParams(blp);

        String alias = NativeData.str(q, "alias", "");
        String user = NativeData.str(q, "user", "");
        String title = !alias.isEmpty() ? alias : !user.isEmpty() ? user : "账号";

        LinearLayout row = ui.row();
        TextView arrow = ui.text("", 10.5f, M3.ON_SURFACE_VARIANT, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));

        TextView n = ui.text(title, 12.5f, M3.ON_SURFACE, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = ui.dp(2);
        row.addView(n, nlp);

        String err = NativeData.str(q, "error", "");
        if (!err.isEmpty()) {
            row.addView(ui.text("读不到", 11, M3.ERROR, false));
        } else {
            String balance = NativeData.str(q, "balance", "");
            double worst = accountTightest(q);
            if (!balance.isEmpty()) {
                row.addView(ui.text(balance, 11.5f, M3.ON_SURFACE_VARIANT, false));
            } else if (worst < 101) {
                row.addView(ui.textColor(NativeData.percent(worst) + " 剩余", 11.5f,
                        ui.quotaColor(worst), true));
            }
        }
        box.addView(row);

        LinearLayout detail = ui.column();
        detail.setPadding(ui.dp(20), ui.dp(4), 0, 0);
        JSONArray windows = NativeData.arr(q, "windows");
        for (int i = 0; i < windows.length(); i++) {
            JSONObject win = NativeData.at(windows, i);
            if (win != null) {
                detail.addView(windowBlock(win));
            }
        }
        if (!err.isEmpty()) {
            TextView e = ui.text(err, 11, M3.ERROR, false);
            e.setLayoutParams(ui.matchWrap(6));
            detail.addView(e);
        }

        Collapse.wire(host, row, new Collapse.Unit(arrow, detail, null, expanded), key);
        box.addView(detail);
        return box;
    }

    /** 一个账号最紧的那个窗口还剩多少（0..100），没有窗口返回 101。 */
    private double accountTightest(JSONObject q) {
        double worst = 101;
        JSONArray windows = NativeData.arr(q, "windows");
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = NativeData.at(windows, i);
            if (w == null || NativeData.flag(w, "unlimited")) {
                continue;
            }
            double left = 100 - Math.max(0, Math.min(100, NativeData.dec(w, "used")));
            if (left < worst) {
                worst = left;
            }
        }
        return worst;
    }

    private View windowBlock(JSONObject win) {
        LinearLayout box = ui.column();
        box.setLayoutParams(ui.matchWrap(10));

        String name = Zh.of(NativeData.str(win, "name", "额度"));
        String display = NativeData.str(win, "display", "");
        boolean unlimited = NativeData.flag(win, "unlimited");
        boolean text = NativeData.flag(win, "text");
        double used = NativeData.dec(win, "used");
        // magpie 的 remaining 字段几乎不出现在 JSON 里（它不 omitempty 但多数
        // 卡片不填）：真正的口径是 used 百分比，剩下 = 100 - used。
        double remaining = 100 - Math.max(0, Math.min(100, used));
        double amount = NativeData.dec(win, "amount");
        double limit = NativeData.dec(win, "limit");
        String unit = Zh.of(NativeData.str(win, "unit", ""));
        String reset = NativeData.str(win, "resetsAt", "");
        String runsOut = NativeData.str(win, "runsOutAt", "");

        LinearLayout head = ui.row();
        head.addView(ui.text(name, 12, M3.ON_SURFACE, false), ui.weight(1));
        String right;
        int color;
        if (unlimited) {
            right = "无限";
            color = ui.col(M3.TERTIARY);
        } else if (limit > 0) {
            // 与账号页一致：写「剩余 / 总额度」，条画已用。
            boolean showUnit = !unit.isEmpty() && !name.contains(unit);
            right = shortCount(Math.max(0, limit - amount)) + " / " + shortCount(limit)
                    + (showUnit ? " " + unit : "");
            color = ui.quotaColor(remaining);
        } else if (!display.isEmpty()) {
            right = display;
            color = ui.quotaColor(remaining);
        } else {
            right = NativeData.percent(remaining) + " 剩余";
            color = ui.quotaColor(remaining);
        }
        TextView r = ui.textColor(right, 11.5f, color, true);
        r.setSingleLine(true);
        // 名字在前占位，数字太长就省略号，不硬撑破卡片
        r.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(r);
        box.addView(head);

        if (!unlimited) {
            double usedPct = limit > 0
                    ? Math.max(0, Math.min(100, amount * 100.0 / limit))
                    : Math.max(0, Math.min(100, used));
            box.addView(ui.quotaBar(usedPct, remaining));
        }

        StringBuilder sub = new StringBuilder();
        if (!reset.isEmpty()) {
            String u = NativeData.until(reset);
            if (!u.isEmpty()) {
                sub.append(u).append("重置");
            }
        }
        if (!runsOut.isEmpty()) {
            String u = NativeData.until(runsOut);
            if (!u.isEmpty()) {
                if (sub.length() > 0) {
                    sub.append(" · ");
                }
                sub.append("最晚的额度 ").append(u).append("过期");
            }
        }
        if (sub.length() > 0) {
            TextView s = ui.text(sub.toString(), 10.5f, M3.ON_SURFACE_VARIANT, false);
            s.setLayoutParams(ui.matchWrap(4));
            box.addView(s);
        }
        return box;
    }

    private String count(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return trim2(v);
    }

    /** 大数缩写：200000000 → 200M（窄卡里必须缩，否则数字把标题顶没）。 */
    private String shortCount(double v) {
        long n = Math.round(v);
        if (n < 100_000) {
            return count(v);
        }
        return NativeData.tokens(n);
    }

    private static String trim2(double v) {
        String s = String.format(java.util.Locale.US, "%.2f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }



    private View quotaEmpty(String why) {
        LinearLayout card = ui.cardWithMargin();
        quotaBlock = card;
        card.addView(ui.text("账号额度", 15, M3.ON_SURFACE, true));
        TextView t = ui.text(why == null
                        ? "还没有登录任何账号，或者这个账号类型不给额度。"
                        : "额度读不到：" + why,
                12.5f, M3.ON_SURFACE_VARIANT, false);
        t.setLayoutParams(ui.matchWrap(8));
        t.setLineSpacing(ui.dp(2), 1f);
        card.addView(t);
        TextView more = ui.pill("去账号页看看", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        more.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        more.setOnClickListener(v -> host.selectTab(NativeHome.TAB_ACCOUNTS, true));
        LinearLayout row = ui.row();
        row.setLayoutParams(ui.matchWrap(10));
        row.addView(more);
        card.addView(row);
        return card;
    }

    // ------------------------------------------------------------------
    // 网关
    // ------------------------------------------------------------------

    private View gatewayCard() {
        LinearLayout card = ui.cardWithMargin();
        LinearLayout head = ui.row();
        head.addView(ui.text("网关", 15, M3.ON_SURFACE, true), ui.weight(1));
        boolean ready = host.gatewayReady;
        head.addView(ui.pill(ready ? "运行中" : "未就绪",
                ready ? M3.TERTIARY_CONTAINER : M3.ERROR_CONTAINER,
                ready ? M3.ON_TERTIARY_CONTAINER : M3.ON_ERROR_CONTAINER));
        card.addView(head);

        String gw = "http://" + Prefs.browserHost(host) + ":"
                + GatewayService.gatewayPort(host) + "/v1";
        card.addView(kv("OpenAI 兼容", gw, () -> copy(gw)));
        card.addView(kv("网页界面", "http://127.0.0.1:"
                + GatewayService.webPort(host) + "/", this::openWeb));

        boolean lan = Prefs.lan(host);
        card.addView(kv("局域网", lan ? "已开启" : "仅本机", () -> host.startActivity(
                new android.content.Intent(host, SettingsActivity.class))));

        TextView note = ui.text("点地址复制。长按顶栏地址也能进网页界面。",
                10.5f, M3.ON_SURFACE_VARIANT, false);
        note.setLayoutParams(ui.matchWrap(10));
        card.addView(note);
        return card;
    }

    private View kv(String key, String value, Runnable tap) {
        LinearLayout row = ui.row();
        row.setLayoutParams(ui.matchWrap(7));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (tap != null) {
                tap.run();
            }
        });
        TextView k = ui.text(key, 12, M3.ON_SURFACE_VARIANT, false);
        k.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(k);
        TextView v = ui.text(value, 12, M3.ON_SURFACE, false);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(v, ui.weight(1));
        return row;
    }

    private void copy(String s) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) host.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("magpie", s));
        }
        android.widget.Toast.makeText(host, "已复制 " + s,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void openWeb() {
        host.startActivity(new android.content.Intent(host, MainActivity.class));
    }
}
