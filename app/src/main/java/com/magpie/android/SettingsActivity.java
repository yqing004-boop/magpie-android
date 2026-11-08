package com.magpie.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

/**
 * The three things worth touching on a phone: which port the gateway listens
 * on, whether it is reachable from other devices on the same Wi-Fi, and
 * where magpie looks for its own updates.
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
    private LinearLayout colorsHeader;
    private LinearLayout colorsPage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

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

        port.setText(String.valueOf(Prefs.port(this)));
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        lan.setChecked(Prefs.lan(this));
        lan.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                // remembered now; the running gateway comes back up on the new
                // bind address when Save is pressed
                Prefs.setLan(SettingsActivity.this, checked);
            }
        });

        feed.setText(Prefs.updateFeed(this));
        final String beforeNetwork = Prefs.port(this) + "|" + Prefs.lan(this) + "|"
                + Prefs.updateFeed(this);

        // 外观：莫奈取色（Android 12+）与自定义背景色
        monet = findViewById(R.id.monet);
        colorsHeader = findViewById(R.id.colors_header);
        colorsPage = findViewById(R.id.colors_page);
        boolean canMonet = Build.VERSION.SDK_INT >= 31;
        monet.setChecked(Prefs.monet(this) && canMonet);
        monet.setEnabled(canMonet);
        if (!canMonet) {
            monet.setText(R.string.settings_monet_unavailable);
        }
        monet.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Prefs.setMonet(SettingsActivity.this, checked);
                if (checked) {
                    Prefs.setHeaderColor(SettingsActivity.this, 0);
                    Prefs.setPageColor(SettingsActivity.this, 0);
                }
                paintSwatches();
            }
        });
        paintSwatches();

        Button save = findViewById(R.id.save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setPort(SettingsActivity.this, port.getText().toString());
                Prefs.setUpdateFeed(SettingsActivity.this, feed.getText().toString());
                // 只有端口 / 局域网 / 更新源变了才需要重启网关；换个背景色不该
                // 把 agent 的连接掐断
                String now = Prefs.port(SettingsActivity.this) + "|" + Prefs.lan(SettingsActivity.this)
                        + "|" + Prefs.updateFeed(SettingsActivity.this);
                if (!now.equals(beforeNetwork)) {
                    GatewayService.restart(SettingsActivity.this);
                }
                Toast.makeText(SettingsActivity.this, R.string.settings_save,
                        Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        Button restart = findViewById(R.id.restart);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GatewayService.restart(SettingsActivity.this);
                setResult(RESULT_OK);
                finish();
            }
        });

        // magpie 本体是那个 ELF，不是这个 APK：更新在这里，更新完网关重启就位
        Button checkUpdate = findViewById(R.id.check_update);
        checkUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, UpdateActivity.class));
            }
        });
    }

    /** 背景色板：第一块是「跟随系统」，剩下的都是柔和底色，最后一块深色。 */
    private static final int[] PALETTE = {
            0, 0xFFF4F4F8, 0xFFFBF6EF, 0xFFEAF2FB,
            0xFFEAF6EC, 0xFFF3EDFA, 0xFFFBEFF2, 0xFF272430
    };

    private void paintSwatches() {
        if (colorsHeader == null || colorsPage == null) {
            return;
        }
        int accent = MainActivity.accentOf(this);
        fillSwatches(colorsHeader, Prefs.headerColor(this), accent, true);
        fillSwatches(colorsPage, Prefs.pageColor(this), accent, false);
    }

    /** 一组色板：第一块是「跟随系统」，点了就把这组清回 0。 */
    private void fillSwatches(LinearLayout box, int current, int accent, final boolean header) {
        box.removeAllViews();
        int size = Math.round(42 * getResources().getDisplayMetrics().density);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, size, 1f);
            lp.rightMargin = i == PALETTE.length - 1 ? 0 : gap;
            sw.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Math.round(10 * getResources().getDisplayMetrics().density));
            bg.setColor(color == 0 ? 0xFFFFFFFF : color);
            boolean picked = current == color;
            bg.setStroke(Math.round((picked ? 3 : 1) * getResources().getDisplayMetrics().density),
                    picked ? (accent != 0 ? accent : 0xFF8A4B32) : 0xFFDCD6D2);
            sw.setBackground(bg);

            if (color == 0) {
                sw.setContentDescription(getString(R.string.settings_bg_default));
            }
            sw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (header) {
                        Prefs.setHeaderColor(SettingsActivity.this, color);
                    } else {
                        Prefs.setPageColor(SettingsActivity.this, color);
                    }
                    if (color != 0) {
                        Prefs.setMonet(SettingsActivity.this, false);
                        monet.setChecked(false);
                    }
                    paintSwatches();
                }
            });
            box.addView(sw);
        }
    }
}
