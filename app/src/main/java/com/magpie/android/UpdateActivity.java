package com.magpie.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/**
 * 更新页：问一次 GitHub（其实是 magpie 的发布清单），把新版拉下来换掉旧二进制。
 *
 * <p>magpie 本体是那个 ELF，不是这个 APK，所以更新完 Web UI 也跟着变新 ——
 * 重启网关就位。APK 里的 UI 只有这一页和设置页。
 */
public class UpdateActivity extends Activity {

    private static final String TAG = "MagpieUpdate";

    private TextView curVersion;
    private TextView latestVersion;
    private TextView status;
    private TextView notes;
    private ProgressBar progress;
    private Button check;
    private Button updateNow;
    private Button openPage;
    private Button rollback;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            paint();
            if (busy || Updater.isRunning()) {
                ui.postDelayed(this, 400);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);
        setTitle(R.string.update_title);

        int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbId > 0) {
            View page = findViewById(R.id.update_root);
            page.setPadding(page.getPaddingLeft(),
                    getResources().getDimensionPixelSize(sbId) + page.getPaddingTop(),
                    page.getPaddingRight(), page.getPaddingBottom());
        }

        curVersion = findViewById(R.id.cur_version);
        latestVersion = findViewById(R.id.latest_version);
        status = findViewById(R.id.update_status);
        notes = findViewById(R.id.notes);
        progress = findViewById(R.id.progress);
        check = findViewById(R.id.check);
        updateNow = findViewById(R.id.update_now);
        openPage = findViewById(R.id.open_page);
        rollback = findViewById(R.id.rollback);

        check.setOnClickListener(v -> checkForUpdates(true));
        updateNow.setOnClickListener(v -> installNow());
        openPage.setOnClickListener(v -> {
            String url = Updater.RELEASES_PAGE;
            Updater.Release r = Updater.lastChecked();
            if (r != null && r.page.length() > 0) {
                url = r.page;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, url, Toast.LENGTH_LONG).show();
            }
        });
        rollback.setOnClickListener(v -> confirmRollback());

        applyTheme();
        showLocalVersion();
        Updater.Release known = Updater.lastChecked();
        if (known != null) {
            showRelease(known);
        }
        if (Updater.isRunning()) {
            busy = true;
            ui.post(tick);
        } else if (known == null && System.currentTimeMillis() - Prefs.lastCheck(this) > 60_000) {
            checkForUpdates(false);
        }
    }

    /** M3：surface 底，两组数字 + 说明文字按令牌上色，四个按钮分别是
     *  filled / filled / outlined / text。 */
    private void applyTheme() {
        int[] c = M3.current(this);
        findViewById(R.id.update_root).setBackgroundColor(c[M3.SURFACE]);

        int[][] texts = {{R.id.label_current, M3.ON_SURFACE_VARIANT},
                {R.id.label_latest, M3.ON_SURFACE_VARIANT},
                {R.id.label_notes, M3.ON_SURFACE_VARIANT},
                {R.id.cur_version, M3.ON_SURFACE},
                {R.id.latest_version, M3.ON_SURFACE},
                {R.id.update_status, M3.ON_SURFACE_VARIANT},
                {R.id.notes, M3.ON_SURFACE_VARIANT}};
        for (int[] pair : texts) {
            ((TextView) findViewById(pair[0])).setTextColor(c[pair[1]]);
        }

        filled(check, c);
        filled(updateNow, c);
        outlined(openPage, c);
        outlined(rollback, c);

        progress.setProgressTintList(ColorStateList.valueOf(c[M3.PRIMARY]));
        progress.setProgressBackgroundTintList(
                ColorStateList.valueOf(c[M3.SURFACE_CONTAINER_HIGHEST]));
    }

    private void filled(Button b, int[] c) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c[M3.PRIMARY]);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        b.setTextColor(c[M3.ON_PRIMARY]);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setAlpha(b.isEnabled() ? 1f : 0.45f);
    }

    private void outlined(Button b, int[] c) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), c[M3.OUTLINE]);
        b.setBackground(bg);
        b.setTextColor(c[M3.PRIMARY]);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Updater.isRunning() || busy) {
            ui.post(tick);
        } else {
            paint();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------

    private void showLocalVersion() {
        String cached = Prefs.installedVersion(this);
        if (cached.length() > 0) {
            curVersion.setText(cached);
        }
        new Thread(() -> {
            String v = Updater.localVersion(this);
            if (v.length() == 0) {
                return;
            }
            ui.post(() -> curVersion.setText(v));
        }, "magpie-version").start();
    }

    private void checkForUpdates(boolean loud) {
        if (busy || Updater.isRunning()) {
            return;
        }
        busy = true;
        status.setText(R.string.update_checking);
        updateNow.setEnabled(false);
        ui.post(tick);
        new Thread(() -> {
            try {
                Updater.Release rel = Updater.latest(this);
                String local = Updater.localVersion(this);
                if (local.length() == 0) {
                    local = Prefs.installedVersion(this);
                }
                boolean newer = Updater.newer(rel.version, local);
                Prefs.noteChecked(this, newer ? rel.version : "");
                final String localFinal = local;
                ui.post(() -> {
                    busy = false;
                    showRelease(rel);
                    curVersion.setText(localFinal.length() > 0 ? localFinal : "?");
                    updateNow.setEnabled(newer && rel.hasAsset());
                    status.setText(newer
                            ? getString(R.string.update_available)
                            : getString(R.string.update_up_to_date));
                    if (!rel.hasAsset() && newer) {
                        status.setText("这一版没有 " + Updater.assetName());
                    }
                    paint();
                });
            } catch (IOException e) {
                final String why = e.getMessage();
                ui.post(() -> {
                    busy = false;
                    status.setText("检查失败：" + why);
                    paint();
                    if (loud) {
                        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "magpie-check").start();
    }

    private void showRelease(Updater.Release rel) {
        latestVersion.setText(rel.version);
        if (rel.notes != null && rel.notes.trim().length() > 0) {
            notes.setText(rel.notes.trim());
        } else {
            notes.setText("（这一版没有说明）");
        }
    }

    private void installNow() {
        if (busy || Updater.isRunning()) {
            Toast.makeText(this, R.string.update_working, Toast.LENGTH_SHORT).show();
            return;
        }
        Updater.Release rel = Updater.lastChecked();
        if (rel == null) {
            checkForUpdates(true);
            return;
        }
        busy = true;
        Updater.markRunning("准备下载…");
        updateNow.setEnabled(false);
        check.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        ui.post(tick);

        new Thread(() -> {
            String installed = null;
            String failure = null;
            try {
                installed = Updater.install(this, rel, new Updater.Step() {
                    @Override
                    public void status(String text) {
                        Updater.setStage(text);
                    }

                    @Override
                    public void progress(long done, long total) {
                        Updater.setProgress(done, total);
                    }
                });
            } catch (IOException e) {
                failure = e.getMessage();
                Log.e(TAG, "更新失败", e);
            }
            final String ok = installed;
            final String bad = failure;
            ui.post(() -> {
                busy = false;
                check.setEnabled(true);
                if (ok != null) {
                    Updater.markDone();
                    curVersion.setText(ok);
                    latestVersion.setText(ok);
                    status.setText(R.string.update_done);
                    progress.setProgress(100);
                    // 换上的二进制要重启才跑得起来；重启完 WebView 会自己重新加载
                    GatewayService.restart(UpdateActivity.this);
                    Toast.makeText(this, "已更新到 " + ok, Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    ui.postDelayed(this::finish, 1200);
                } else {
                    Updater.markFailed(bad);
                    status.setText("更新失败：" + bad);
                    progress.setVisibility(View.GONE);
                    updateNow.setEnabled(true);
                }
                paint();
            });
        }, "magpie-install").start();
    }

    private void confirmRollback() {
        if (busy || Updater.isRunning()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.update_confirm_rollback)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> doRollback())
                .show();
    }

    private void doRollback() {
        busy = true;
        status.setText("回滚中…");
        new Thread(() -> {
            String failure = null;
            try {
                Updater.rollback(this);
            } catch (IOException e) {
                failure = e.getMessage();
            }
            final String bad = failure;
            ui.post(() -> {
                busy = false;
                if (bad == null) {
                    showLocalVersion();
                    status.setText(R.string.update_rollback_done);
                    GatewayService.restart(UpdateActivity.this);
                    setResult(RESULT_OK);
                } else {
                    status.setText("回滚失败：" + bad);
                }
            });
        }, "magpie-rollback").start();
    }

    // ------------------------------------------------------------------

    /** 把静态状态画到界面上；下载中重进这一页也能接着看。 */
    private void paint() {
        if (Updater.isRunning()) {
            progress.setVisibility(View.VISIBLE);
            String s = Updater.stage();
            long d = Updater.done();
            long t = Updater.total();
            if (t > 0 && d >= 0) {
                int pct = (int) Math.min(100, Math.max(0, d * 100 / t));
                progress.setProgress(pct);
                status.setText(String.format(Locale.ROOT, "%s  %s / %s", s, mb(d), mb(t)));
            } else {
                status.setText(s);
            }
            updateNow.setEnabled(false);
            check.setEnabled(false);
            return;
        }
        String err = Updater.error();
        if (err != null && err.length() > 0) {
            progress.setVisibility(View.GONE);
            status.setText("失败：" + err);
        }
    }

    private static String mb(long n) {
        if (n < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", n / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024.0));
    }
}
