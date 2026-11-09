package com.magpie.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首次安装后的后台保活引导：通知权限 → 电池优化 →（小米）手动步骤。
 *
 * <p>每一步都能跳到对应的系统设置页；允许「以后再说」与「不再提示」。
 * 引导只是减少系统限制后台的概率，**不承诺**网关永不被杀。
 */
public final class BackgroundGuide {

    private BackgroundGuide() {
    }

    /** @param entry true=从设置页进入（底部按钮是“完成”）；false=首次启动（含以后再说/不再提示）。 */
    public static void show(Activity activity, boolean entry) {
        int[] c = M3.current(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        box.setPadding(pad, dp(activity, 4), pad, 0);

        LinearLayout rowN = step(activity, c,
                activity.getString(R.string.background_guide_notifications),
                activity.getString(R.string.background_guide_notifications_hint),
                activity.getString(R.string.background_guide_open),
                () -> openNotificationSettings(activity));
        box.addView(rowN);

        LinearLayout rowB = step(activity, c,
                activity.getString(R.string.background_guide_battery),
                activity.getString(R.string.background_guide_battery_hint),
                activity.getString(R.string.background_guide_open),
                () -> openBatterySettings(activity));
        box.addView(rowB);

        LinearLayout rowX = step(activity, c,
                activity.getString(R.string.background_guide_xiaomi),
                activity.getString(R.string.background_guide_xiaomi_hint), null, null);
        box.addView(rowX);

        TextView note = new TextView(activity);
        note.setTextSize(11f);
        note.setTextColor(c[M3.OUTLINE]);
        note.setText(R.string.lan_security);
        note.setPadding(0, dp(activity, 12), 0, 0);
        box.addView(note);

        AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle(R.string.background_guide_title)
                .setMessage(R.string.background_guide_intro)
                .setView(box)
                .setNegativeButton(entry
                        ? R.string.background_guide_done
                        : R.string.background_guide_later, null)
                .setPositiveButton(entry
                        ? android.R.string.ok
                        : R.string.background_guide_never,
                        (d, w) -> Prefs.setBackgroundGuideSeen(activity, true))
                .create();
        style(dlg, c);
        dlg.show();
        // “不再提示”只在首次引导里出现；从设置页进入时不动已读标记
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Prefs.setBackgroundGuideSeen(activity, true);
            dlg.dismiss();
        });
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (!entry) {
                Prefs.setBackgroundGuideSeen(activity, true);
            }
            dlg.dismiss();
        });
    }

    /** 首次打开 App 时使用：只有一次，之后被“以后再说/不再提示”关掉。 */
    static void maybeShowOnLaunch(Activity activity) {
        if (!Prefs.backgroundGuideSeen(activity)) {
            show(activity, false);
        }
    }

    private static LinearLayout step(Activity activity, int[] c, String title,
                                     String hint, String action, Runnable onAction) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, 10);
        row.setLayoutParams(lp);

        TextView t = new TextView(activity);
        t.setTextSize(13.5f);
        t.setTextColor(c[M3.ON_SURFACE]);
        t.setText(title);
        row.addView(t);

        TextView h = new TextView(activity);
        h.setTextSize(12f);
        h.setTextColor(c[M3.ON_SURFACE_VARIANT]);
        h.setText(hint);
        row.addView(h);

        if (action != null && onAction != null) {
            TextView go = new TextView(activity);
            go.setTextSize(12.5f);
            go.setTextColor(c[M3.PRIMARY]);
            go.setText(action);
            go.setPadding(0, dp(activity, 4), 0, 0);
            go.setOnClickListener(v -> onAction.run());
            row.addView(go);
        }
        return row;
    }

    private static void style(AlertDialog dlg, int[] c) {
        dlg.setOnShowListener(x -> {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(c[M3.SURFACE_CONTAINER_HIGH]);
                bg.setCornerRadius(dp(dlg.getContext(), 28));
                w.setBackgroundDrawable(bg);
            }
            for (int id : new int[]{AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
                android.widget.Button b = dlg.getButton(id);
                if (b != null) {
                    b.setTextColor(c[M3.PRIMARY]);
                }
            }
        });
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static void openNotificationSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
            ctx.startActivity(i);
        } catch (Exception e) {
            ctx.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private static void openBatterySettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Exception e) {
            ctx.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
