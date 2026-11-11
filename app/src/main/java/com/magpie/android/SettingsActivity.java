package com.magpie.android;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The three things worth touching on a phone: which port the gateway listens
 * on, whether it is reachable from other devices on the same Wi-Fi, and
 * where magpie looks for its own updates.
 *
 * <p>外观部分只有一处真来源：{@link M3} 令牌表。莫奈开关只决定令牌从系统取
 * 还是从八套种子色生成，页面自己不留任何硬编码颜色。
 *
 * Nothing here is written to magpie's own settings.json — the port and the
 * bind address are handed over as MAGPIE_ADDR when GatewayService starts
 * magpie, and everything else lives in magpie's portable data directory.
 */
public class SettingsActivity extends Activity {

    private EditText port;
    private Switch lan;
    private EditText feed;
    private Switch monet;
    private Switch stableChannel;
    private LinearLayout seedBar;
    private Switch shellAutoCheck;
    private TextView shellMirrorValue;
    private RadioGroup webReleaseGroup;
    private RadioButton webReleaseNever;
    private RadioButton webRelease15;
    private RadioButton webRelease30;
    private RadioButton webReleaseCustom;
    private EditText webReleaseCustomMinutes;

    private int[] c;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        c = M3.current(this);

        // 沉浸模式下自己让出状态栏，否则第一行被时间和电量压住
        int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbId > 0) {
            View page = findViewById(R.id.settings_root);
            page.setPadding(page.getPaddingLeft(),
                    getResources().getDimensionPixelSize(sbId) + page.getPaddingTop(),
                    page.getPaddingRight(), page.getPaddingBottom());
        }

        port = findViewById(R.id.port);
        lan = findViewById(R.id.lan);
        feed = findViewById(R.id.feed);
        webReleaseGroup = findViewById(R.id.web_release_group);
        webReleaseNever = findViewById(R.id.web_release_never);
        webRelease15 = findViewById(R.id.web_release_15);
        webRelease30 = findViewById(R.id.web_release_30);
        webReleaseCustom = findViewById(R.id.web_release_custom);
        webReleaseCustomMinutes = findViewById(R.id.web_release_custom_minutes);
        setupWebReleasePicker();
        findViewById(R.id.open_notification_settings).setOnClickListener(v -> openNotificationSettings());
        findViewById(R.id.open_battery_settings).setOnClickListener(v -> openBatterySettings());
        findViewById(R.id.open_background_guide).setOnClickListener(v ->
                BackgroundGuide.show(SettingsActivity.this, false));

        port.setText(String.valueOf(Prefs.port(this)));
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        lan.setChecked(Prefs.lan(this));
        lan.setOnCheckedChangeListener((button, checked) -> Prefs.setLan(SettingsActivity.this, checked));

        feed.setText(Prefs.updateFeed(this));
        stableChannel = findViewById(R.id.stable_channel);
        stableChannel.setChecked(Prefs.stableChannel(this));

        monet = findViewById(R.id.monet);
        seedBar = findViewById(R.id.colors_seed);
        boolean canMonet = Build.VERSION.SDK_INT >= 31;
        monet.setChecked(Prefs.monet(this) && canMonet);
        monet.setEnabled(canMonet);
        if (!canMonet) {
            monet.setText(R.string.settings_monet_unavailable);
        }
        monet.setOnCheckedChangeListener((button, checked) -> {
            if (checked == Prefs.monet(SettingsActivity.this)) {
                return;
            }
            Prefs.setMonet(SettingsActivity.this, checked);
            recreate();
        });

        // 保存后**不再拉起网关**：设置落盘 → 停掉前台服务 → 关闭自身 App。
        // 下次手动打开 App 时才用新设置启动，端口 / 局域网 / 更新源 / 稳定渠道
        // 一并生效，也不会出现「反复恢复网关（上次退出码 1）」的循环。
        Button save = findViewById(R.id.save);
        save.setOnClickListener(v -> {
            if (!saveWebReleasePicker()) {
                return;
            }
            Prefs.setPort(SettingsActivity.this, port.getText().toString());
            Prefs.setUpdateFeed(SettingsActivity.this, feed.getText().toString());
            Prefs.setStableChannel(SettingsActivity.this, stableChannel.isChecked());
            GatewayService.stop(SettingsActivity.this);
            exitApp();
        });

        // 「立即重启网关」同样改成：停网关 + 关闭自身 App，不自动拉起。
        Button restart = findViewById(R.id.restart);
        restart.setOnClickListener(v -> {
            GatewayService.stop(SettingsActivity.this);
            exitApp();
        });

        // magpie 本体是那个 ELF，不是这个 APK：更新在这里，更新完网关重启就位
        Button checkUpdate = findViewById(R.id.check_update);
        checkUpdate.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, UpdateActivity.class)));

        // 壳自己的更新：查 GitHub Release，只提示 + 跳页，不碰 APK 安装
        Button checkShell = findViewById(R.id.check_shell);
        checkShell.setOnClickListener(v -> checkShellUpdate());

        // App 自更新：自动检查开关 + 下载镜像选择 + 蓝奏云入口
        shellAutoCheck = findViewById(R.id.shell_auto_check);
        shellAutoCheck.setChecked(Prefs.shellAutoCheck(this));
        shellAutoCheck.setOnCheckedChangeListener((button, checked) ->
                Prefs.setShellAutoCheck(SettingsActivity.this, checked));
        shellMirrorValue = findViewById(R.id.shell_mirror_value);
        shellMirrorValue.setOnClickListener(v -> showMirrorDialog());
        findViewById(R.id.shell_add_mirror).setOnClickListener(v -> showAddMirrorDialog());
        findViewById(R.id.shell_lanzou).setOnClickListener(v -> openLanzou());
        ((TextView) findViewById(R.id.hint_shell_lanzou)).setText(
                getString(R.string.shell_lanzou_hint, getString(R.string.shell_lanzou_password)));
        refreshMirrorLabel();

        paint();
    }

    /** 后台 WebView 释放时间：0/15/30/用户自定义分钟。 */
    private void setupWebReleasePicker() {
        int minutes = Prefs.webReleaseMinutes(this);
        if (minutes == 15) {
            webRelease15.setChecked(true);
        } else if (minutes == 30) {
            webRelease30.setChecked(true);
        } else if (minutes > 0) {
            webReleaseCustom.setChecked(true);
            webReleaseCustomMinutes.setText(String.valueOf(minutes));
            webReleaseCustomMinutes.setVisibility(View.VISIBLE);
        } else {
            webReleaseNever.setChecked(true);
        }
        webReleaseGroup.setOnCheckedChangeListener((group, id) -> {
            webReleaseCustomMinutes.setVisibility(id == R.id.web_release_custom
                    ? View.VISIBLE : View.GONE);
        });
    }

    private boolean saveWebReleasePicker() {
        int minutes = 0;
        if (webRelease15.isChecked()) {
            minutes = 15;
        } else if (webRelease30.isChecked()) {
            minutes = 30;
        } else if (webReleaseCustom.isChecked()) {
            try {
                minutes = Integer.parseInt(webReleaseCustomMinutes.getText().toString().trim());
            } catch (Exception ignored) {
                minutes = 0;
            }
            if (minutes < 1 || minutes > 1440) {
                Toast.makeText(this, R.string.settings_web_custom_invalid, Toast.LENGTH_LONG).show();
                return false;
            }
        }
        Prefs.setWebReleaseMinutes(this, minutes);
        return true;
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    /** 查这个 App 在 GitHub 开源仓库里的新版本，只提示、跳到 Release 页。 */
    private void checkShellUpdate() {
        final String local = ShellUpdater.localVersion(this);
        ShellUpdater.check(this, r -> {
            if (!r.ok()) {
                Toast.makeText(this, getString(R.string.shell_update_failed, r.error),
                        Toast.LENGTH_LONG).show();
                return;
            }
            final boolean newer = ShellUpdater.newer(r.latestTag, local);
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(R.string.shell_update_title)
                    .setMessage(newer
                            ? getString(R.string.shell_update_available, r.latestTag)
                            : getString(R.string.shell_update_latest, local))
                    .setPositiveButton(newer ? R.string.shell_update_go : android.R.string.ok,
                            (d, w) -> {
                                if (newer) {
                                    openUrl(r.page);
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            styleDialog(dlg);
            dlg.show();
        });
    }

    /** 给系统 AlertDialog 换 M3 底色，把标题/正文/按钮刷成对应前景。 */
    private void styleDialog(android.app.AlertDialog dlg) {
        int[] c = M3.current(this);
        dlg.setOnShowListener(x -> {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(c[M3.SURFACE_CONTAINER_HIGH]);
                bg.setCornerRadius(dp(28));
                w.setBackgroundDrawable(bg);
            }
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView title = titleId > 0 ? dlg.findViewById(titleId) : null;
            if (title != null) {
                title.setTextColor(c[M3.ON_SURFACE]);
            }
            TextView msg = dlg.findViewById(android.R.id.message);
            if (msg != null) {
                msg.setTextColor(c[M3.ON_SURFACE_VARIANT]);
            }
            for (int id : new int[]{android.app.AlertDialog.BUTTON_POSITIVE,
                    android.app.AlertDialog.BUTTON_NEUTRAL,
                    android.app.AlertDialog.BUTTON_NEGATIVE}) {
                Button b = dlg.getButton(id);
                if (b != null) {
                    b.setTextColor(c[M3.PRIMARY]);
                }
            }
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // M3 主题

    private void paint() {
        int surface = c[M3.SURFACE];
        findViewById(R.id.settings_root).setBackgroundColor(surface);
        ((TextView) findViewById(R.id.settings_title)).setTextColor(c[M3.ON_SURFACE]);

        int[] cards = {R.id.card_port, R.id.card_lan, R.id.card_background,
                R.id.card_background_guide, R.id.card_feed, R.id.card_appearance,
                R.id.card_seed, R.id.card_shell_update};
        for (int id : cards) {
            View card = findViewById(id);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(c[M3.SURFACE_CONTAINER_LOW]);
            bg.setCornerRadius(dp(20));
            card.setBackground(bg);
        }

        int[] labels = {R.id.label_port, R.id.label_web_memory, R.id.label_background_guide,
                R.id.label_feed, R.id.label_seed, R.id.label_shell_update,
                R.id.label_shell_mirror};
        for (int id : labels) {
            ((TextView) findViewById(id)).setTextColor(c[M3.ON_SURFACE]);
        }
        int[] hints = {R.id.hint_port, R.id.hint_lan, R.id.hint_web_memory,
                R.id.hint_background_guide, R.id.hint_stable_channel,
                R.id.monet_hint, R.id.hint_seed, R.id.hint_shell_auto_check,
                R.id.hint_shell_mirror, R.id.hint_shell_lanzou};
        for (int id : hints) {
            ((TextView) findViewById(id)).setTextColor(c[M3.ON_SURFACE_VARIANT]);
        }

        field(port);
        field(feed);
        field(webReleaseCustomMinutes);
        tintSwitch(lan);
        tintSwitch(monet);
        tintSwitch(stableChannel);
        ((Switch) stableChannel).setTextColor(c[M3.ON_SURFACE]);
        tintRadio(webReleaseNever);
        tintRadio(webRelease15);
        tintRadio(webRelease30);
        tintRadio(webReleaseCustom);
        ((Switch) lan).setTextColor(c[M3.ON_SURFACE]);
        ((Switch) monet).setTextColor(c[M3.ON_SURFACE]);
        ((Switch) shellAutoCheck).setTextColor(c[M3.ON_SURFACE]);
        tintSwitch(shellAutoCheck);

        // 镜像选择框：和输入框一个样
        GradientDrawable mvBg = new GradientDrawable();
        mvBg.setColor(c[M3.SURFACE_CONTAINER_HIGHEST]);
        mvBg.setCornerRadius(dp(12));
        mvBg.setStroke(dp(1), c[M3.OUTLINE_VARIANT]);
        shellMirrorValue.setBackground(mvBg);
        shellMirrorValue.setTextColor(c[M3.ON_SURFACE]);

        // check update：outlined tonal —— surface 底 + outline 边 + primary 字
        Button check = findViewById(R.id.check_update);
        GradientDrawable outlined = new GradientDrawable();
        outlined.setColor(c[M3.SURFACE]);
        outlined.setCornerRadius(dp(20));
        outlined.setStroke(dp(1), c[M3.OUTLINE]);
        check.setBackground(outlined);
        check.setTextColor(c[M3.PRIMARY]);
        check.setAllCaps(false);
        outlined(findViewById(R.id.check_shell));
        outlined(findViewById(R.id.open_notification_settings));
        outlined(findViewById(R.id.open_battery_settings));
        outlined(findViewById(R.id.open_background_guide));
        outlined(findViewById(R.id.shell_add_mirror));
        outlined(findViewById(R.id.shell_lanzou));

        filled(findViewById(R.id.save));
        tonal(findViewById(R.id.restart));

        paintSeeds();
    }

    /** 输入框：M3 filled text field 的简化版。 */
    private void field(EditText e) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c[M3.SURFACE_CONTAINER_HIGHEST]);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), c[M3.OUTLINE_VARIANT]);
        e.setBackground(bg);
        e.setTextColor(c[M3.ON_SURFACE]);
        e.setHintTextColor(c[M3.ON_SURFACE_VARIANT]);
    }

    private void tintSwitch(Switch sw) {
        ColorStateList thumb = new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {-android.R.attr.state_checked}},
                new int[]{c[M3.ON_PRIMARY], c[M3.OUTLINE]});
        ColorStateList track = new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {-android.R.attr.state_checked}},
                new int[]{c[M3.PRIMARY], c[M3.SURFACE_CONTAINER_HIGHEST]});
        sw.setThumbTintList(thumb);
        sw.setTrackTintList(track);
    }

    private void tintRadio(RadioButton rb) {
        ColorStateList tint = new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {-android.R.attr.state_checked}},
                new int[]{c[M3.PRIMARY], c[M3.OUTLINE]});
        rb.setButtonTintList(tint);
        rb.setTextColor(c[M3.ON_SURFACE]);
    }

    private void outlined(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c[M3.SURFACE]);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), c[M3.OUTLINE]);
        b.setBackground(bg);
        b.setTextColor(c[M3.PRIMARY]);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
    }

    /** M3 filled：primary 底 + on-primary 字。 */
    private void filled(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c[M3.PRIMARY]);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        b.setTextColor(c[M3.ON_PRIMARY]);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
    }

    /** M3 filled tonal：secondary container 底。 */
    private void tonal(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c[M3.SECONDARY_CONTAINER]);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        b.setTextColor(c[M3.ON_SECONDARY_CONTAINER]);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
    }

    /** 八套种子色：均分一排圆点，选中的加一圈 on-surface 描边。 */
    private void paintSeeds() {
        seedBar.removeAllViews();
        int size = dp(40);
        int gap = dp(6);
        int selected = Prefs.seed(this);
        for (int i = 0; i < M3.SEEDS.length; i++) {
            final int index = i;
            TextView dot = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, size, 1f);
            lp.rightMargin = i == M3.SEEDS.length - 1 ? 0 : gap;
            dot.setLayoutParams(lp);
            dot.setGravity(Gravity.CENTER);
            dot.setContentDescription(M3.SEED_NAMES[i]);
            dot.setText(selected == i ? "\u2713" : "");
            dot.setTextSize(16);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(M3.seedColor(this, i));
            if (selected == i) {
                bg.setStroke(dp(3), c[M3.ON_SURFACE]);
                dot.setTextColor(M3.onSeed(this, i));
            }
            dot.setBackground(bg);
            dot.setOnClickListener(v -> {
                if (Prefs.seed(SettingsActivity.this) == index
                        && !Prefs.monet(SettingsActivity.this)) {
                    return;
                }
                Prefs.setSeed(SettingsActivity.this, index);
                Prefs.setMonet(SettingsActivity.this, false);
                recreate();
            });
            seedBar.addView(dot);
        }
    }

    // ------------------------------------------------------------------
    // App 自更新：镜像选择 + 蓝奏云
    // ------------------------------------------------------------------

    /** 显示/刷新当前镜像名。 */
    private void refreshMirrorLabel() {
        String cur = Prefs.shellMirror(this);
        if (cur == null || cur.isEmpty()) {
            shellMirrorValue.setText(R.string.shell_mirror_official);
        } else {
            shellMirrorValue.setText(cur);
        }
    }

    /** 弹镜像选择：内置几条 + 用户手动添加的。 */
    private void showMirrorDialog() {
        List<String> prefixes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ShellUpdater.MIRROR_PREFIXES.length; i++) {
            prefixes.add(ShellUpdater.MIRROR_PREFIXES[i]);
            names.add(ShellUpdater.MIRROR_NAMES[i]);
        }
        for (String p : Prefs.shellCustomMirrors(this)) {
            prefixes.add(p);
            names.add(p);
        }
        final String[] items = names.toArray(new String[0]);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.shell_mirror_dialog_title)
                .setItems(items, (d, which) -> {
                    Prefs.setShellMirror(this, prefixes.get(which));
                    refreshMirrorLabel();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 添加自定义镜像前缀。 */
    private void showAddMirrorDialog() {
        final EditText input = new EditText(this);
        input.setHint("https://example.com/");
        input.setSingleLine(true);
        input.setTextColor(c[M3.ON_SURFACE]);
        input.setHintTextColor(c[M3.ON_SURFACE_VARIANT]);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.shell_mirror_add_title)
                .setMessage(R.string.shell_mirror_add_hint)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String p = input.getText().toString().trim();
                    if (!p.isEmpty()) {
                        if (!p.endsWith("/")) {
                            p = p + "/";
                        }
                        Prefs.addShellCustomMirror(this, p);
                        Prefs.setShellMirror(this, p);
                        refreshMirrorLabel();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 蓝奏云：复制密码到剪贴板 + 打开分享页。 */
    private void openLanzou() {
        String pwd = getString(R.string.shell_lanzou_password);
        copyText(pwd);
        openUrl(getString(R.string.shell_lanzou_url));
        Toast.makeText(this, getString(R.string.lanzou_pwd_copied, pwd), Toast.LENGTH_LONG).show();
    }

    private void copyText(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("magpie", text));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 关闭自身 App：结束 Activity 再杀掉进程，前台服务、通知、恢复循环一起停。 */
    private void exitApp() {
        setResult(RESULT_OK);
        finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
