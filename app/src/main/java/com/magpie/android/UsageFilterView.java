package com.magpie.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * 用量/概览两页共用的「周期胶囊条 + 供应商/模型下拉筛选」构建工具，以及
 * usageFiltered 结果 → 类 /api/usage 形状的适配。
 */
final class UsageFilterView {

    static final String[][] PERIODS = {
            {"today", "今天"},
            {"last:24h0m0s", "24 小时"},
            {"7d", "7 天"},
            {"30d", "30 天"},
            {"all", "全部"},
    };

    private UsageFilterView() {
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 周期胶囊条：选中=主色实心白字，未选中=描边胶囊，横向可滚动。 */
    static View periodBar(Activity host, Ui ui, String current, Consumer<String> onPick) {
        HorizontalScrollView scroll = new HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(2));
        for (String[] opt : PERIODS) {
            final String value = opt[0];
            boolean on = value.equals(current);
            TextView t = ui.text(opt[1], 12.5f,
                    on ? M3.ON_PRIMARY : M3.ON_SURFACE_VARIANT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
            if (on) {
                t.setBackground(ui.round(ui.col(M3.PRIMARY), 999));
            } else {
                t.setBackground(ui.roundStroke(android.graphics.Color.TRANSPARENT, 999,
                        ui.col(M3.OUTLINE), 1f));
            }
            t.setOnClickListener(v -> {
                if (!value.equals(current) && onPick != null) {
                    onPick.accept(value);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            row.addView(t, lp);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** 两个下拉：供应商 + 模型（圆角带边框 + ▾）。 */
    static View dropdownRow(final Activity host, final Ui ui,
                            final String provAllLabel, String curProvider, List<String> provOptions,
                            Consumer<String> onProvider,
                            final String modelAllLabel, String curModel, List<String> modelOptions,
                            Consumer<String> onModel) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(4));

        View prov = dropdown(host, ui, "选择供应商", provAllLabel,
                curProvider, provOptions, onProvider);
        View model = dropdown(host, ui, "选择模型", modelAllLabel,
                curModel, modelOptions, onModel);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plp.rightMargin = ui.dp(8);
        row.addView(prov, plp);
        row.addView(model, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static View dropdown(final Activity host, final Ui ui, final String title,
                                 final String allLabel, String currentValue,
                                 final List<String> options, final Consumer<String> onPick) {
        String display = (currentValue == null || currentValue.isEmpty()) ? allLabel : currentValue;
        TextView b = ui.text(display + "  \u25BE", 12.5f, M3.ON_SURFACE, false);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(ui.dp(12), ui.dp(8), ui.dp(10), ui.dp(8));
        b.setBackground(ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_HIGH), 12,
                ui.col(M3.OUTLINE_VARIANT), 1f));
        final String curVal = currentValue == null ? "" : currentValue;
        b.setOnClickListener(v -> showOptions(host, ui, title, allLabel, options, curVal, onPick));
        return b;
    }

    /** 统一卡片式弹窗：标题 + 可滚动的选项卡片，选中项高亮（PRIMARY_CONTAINER）并打 ✓。 */
    static void showOptions(final Activity host, final Ui ui, String title, String allLabel,
                            List<String> options, String currentValue, final Consumer<String> onPick) {
        final List<String> values = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        values.add("");
        labels.add(allLabel);
        if (options != null) {
            for (String o : options) {
                if (o == null || o.isEmpty()) {
                    continue;
                }
                values.add(o);
                labels.add(o);
            }
        }
        final String cur = currentValue == null ? "" : currentValue;

        final Dialog dlg = new Dialog(host, R.style.Theme_Magpie_Sheet);
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        root.setBackground(ui.dialogBg());

        TextView titleView = ui.text(title, 15, M3.ON_SURFACE, true);
        titleView.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(2));
        root.addView(titleView, wrap());
        TextView sub = ui.text("共 " + (labels.size() - 1) + " 项", 11,
                M3.ON_SURFACE_VARIANT, false);
        sub.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8));
        root.addView(sub, wrap());

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.size(); i++) {
            final String value = values.get(i);
            boolean on = value.equals(cur);

            LinearLayout item = new LinearLayout(host);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = ui.roundStroke(
                    ui.col(on ? M3.PRIMARY_CONTAINER : M3.SURFACE_CONTAINER_LOW), 12,
                    ui.col(M3.OUTLINE_VARIANT), 1f);
            ui.clickable(item, bg);
            item.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));

            TextView label = ui.text(labels.get(i), 13,
                    on ? M3.ON_PRIMARY_CONTAINER : M3.ON_SURFACE, on);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tick = ui.text(on ? "\u2713" : "", 13,
                    M3.ON_PRIMARY_CONTAINER, true);
            item.addView(tick);

            item.setOnClickListener(v -> {
                dlg.dismiss();
                if (onPick != null) {
                    onPick.accept(value);
                }
            });
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.bottomMargin = ui.dp(6);
            list.addView(item, ilp);
        }
        sc.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
        int maxH = (int) (dm.heightPixels * 0.6f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                labels.size() > 8 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(sc, slp);

        dlg.setContentView(root);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.max(ui.dp(240), dm.widthPixels - ui.dp(48));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
    }

    /** 从 /api/usage 的 models[] 去重出供应商列表与模型列表（不含「全部」）。 */
    static void collectCandidates(JSONObject usage, List<String> provOut, List<String> modOut) {
        if (provOut != null) {
            provOut.clear();
        }
        if (modOut != null) {
            modOut.clear();
        }
        if (usage == null) {
            return;
        }
        LinkedHashSet<String> ps = new LinkedHashSet<>();
        LinkedHashSet<String> ms = new LinkedHashSet<>();
        JSONArray models = NativeData.arr(usage, "models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String provider = NativeData.str(m, "provider", "");
            String model = NativeData.str(m, "model", NativeData.str(m, "id", ""));
            if (!provider.isEmpty()) {
                ps.add(provider);
            }
            if (!model.isEmpty()) {
                ms.add(model);
            }
        }
        if (provOut != null) {
            provOut.addAll(ps);
        }
        if (modOut != null) {
            modOut.addAll(ms);
        }
    }

    /** 把 usageFiltered 的 body 转成类 /api/usage 形状：顶层照抄，by.agent/model/provider → agents/models/providers。 */
    static JSONObject adaptFiltered(JSONObject body) {
        JSONObject out = new JSONObject();
        if (body == null) {
            return out;
        }
        try {
            Iterator<String> it = body.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("by".equals(k)) {
                    continue;
                }
                out.put(k, body.opt(k));
            }
            JSONObject by = body.optJSONObject("by");
            if (by != null) {
                out.put("agents", by.optJSONArray("agent"));
                out.put("models", by.optJSONArray("model"));
                out.put("providers", by.optJSONArray("provider"));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
