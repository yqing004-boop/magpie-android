package com.magpie.android;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/**
 * Material 3 色彩令牌。
 *
 * <p>八套固定色板由 Material Color Utilities 的 {@code SchemeTonalSpot}
 * (SPEC 2025, contrast 0) 生成：主色沿用种子色的色相，饱和度中等；
 * 次级、三级各自降饱和 / 偏 60° 色相，中性色只留极低的彩度。
 * 生成脚本不进 App，运行时只查表 —— 手机上没有色彩空间换算的负担。
 */
final class M3 {

    static final int PRIMARY = 0;
    static final int ON_PRIMARY = 1;
    static final int PRIMARY_CONTAINER = 2;
    static final int ON_PRIMARY_CONTAINER = 3;
    static final int SECONDARY = 4;
    static final int ON_SECONDARY = 5;
    static final int SECONDARY_CONTAINER = 6;
    static final int ON_SECONDARY_CONTAINER = 7;
    static final int TERTIARY = 8;
    static final int ON_TERTIARY = 9;
    static final int TERTIARY_CONTAINER = 10;
    static final int ON_TERTIARY_CONTAINER = 11;
    static final int ERROR = 12;
    static final int ON_ERROR = 13;
    static final int ERROR_CONTAINER = 14;
    static final int ON_ERROR_CONTAINER = 15;
    static final int SURFACE = 16;
    static final int ON_SURFACE = 17;
    static final int SURFACE_VARIANT = 18;
    static final int ON_SURFACE_VARIANT = 19;
    static final int OUTLINE = 20;
    static final int OUTLINE_VARIANT = 21;
    static final int SURFACE_DIM = 22;
    static final int SURFACE_BRIGHT = 23;
    static final int SURFACE_CONTAINER_LOWEST = 24;
    static final int SURFACE_CONTAINER_LOW = 25;
    static final int SURFACE_CONTAINER = 26;
    static final int SURFACE_CONTAINER_HIGH = 27;
    static final int SURFACE_CONTAINER_HIGHEST = 28;
    static final int INVERSE_SURFACE = 29;
    static final int INVERSE_ON_SURFACE = 30;
    static final int INVERSE_PRIMARY = 31;
    static final int SCRIM = 32;
    static final int COUNT = 33;

    static final String[] ROLE_NAMES = {"primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer", "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer", "error", "onError", "errorContainer", "onErrorContainer", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant", "surfaceDim", "surfaceBright", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "inverseSurface", "inverseOnSurface", "inversePrimary", "scrim"};

    /** 种子色：设为「跟随系统」以外的八种风格。 */
    static final String[] SEED_NAMES = {"陶土", "海蓝", "苔绿", "紫藤", "青瓷", "蔷薇", "琥珀", "石墨"};
    static final int[] SEEDS = {0xFF8A4B32, 0xFF2E6DA4, 0xFF3A7D44, 0xFF6D5A9E, 0xFF1F7A78, 0xFFA84B6B, 0xFFA07428, 0xFF5C5F62};

    static final int[][] LIGHT = {
        {0xFF8B4F38, 0xFFFFF7F5, 0xFFFFB194, 0xFF63301B, 0xFF78584C, 0xFFFFF7F5, 0xFFFFDBCE, 0xFF694B3F, 0xFF735C25, 0xFFFFF8F0, 0xFFFFDF9B, 0xFF644F19, 0xFFA83836, 0xFFFFF7F6, 0xFFFA746F, 0xFF6E0A12, 0xFFFFF8F6, 0xFF3E2F2A, 0xFFF6DDD5, 0xFF6D5B55, 0xFF8A7770, 0xFFC3ADA6, 0xFFEDD5CD, 0xFFFFF8F6, 0xFFFFFFFF, 0xFFFFF1EC, 0xFFFEEAE3, 0xFFFAE4DC, 0xFFF6DDD5, 0xFF130D0A, 0xFFA79A96, 0xFFFFB194, 0xFF000000},
        {0xFF3C6288, 0xFFF7F9FF, 0xFFADD2FE, 0xFF20486C, 0xFF536070, 0xFFF7F9FF, 0xFFD6E4F7, 0xFF465363, 0xFF5F5A84, 0xFFFCF7FF, 0xFFD3CCFD, 0xFF47426B, 0xFFA83836, 0xFFFFF7F6, 0xFFFA746F, 0xFF6E0A12, 0xFFF8F9FE, 0xFF2E333A, 0xFFDEE3EC, 0xFF5A5F67, 0xFF767B83, 0xFFADB2BB, 0xFFD5DAE3, 0xFFF8F9FE, 0xFFFFFFFF, 0xFFF1F3FA, 0xFFEBEEF5, 0xFFE5E8F0, 0xFFDEE3EC, 0xFF0C0E12, 0xFF9B9DA1, 0xFFADD2FE, 0xFF000000},
        {0xFF3E6942, 0xFFE9FFE5, 0xFFBEEFBD, 0xFF315B35, 0xFF526451, 0xFFEAFFE6, 0xFFD4E8D0, 0xFF455644, 0xFF5D632F, 0xFFF8FEBB, 0xFFF8FFBC, 0xFF5D632F, 0xFFA73B21, 0xFFFFF7F6, 0xFFFD795A, 0xFF6E1400, 0xFFF8FAF3, 0xFF2D342C, 0xFFDDE5D9, 0xFF596158, 0xFF757D73, 0xFFACB4A9, 0xFFD5DCD0, 0xFFF8FAF3, 0xFFFFFFFF, 0xFFF1F5EC, 0xFFEAF0E5, 0xFFE4EADF, 0xFFDDE5D9, 0xFF0C0F0B, 0xFF9B9E98, 0xFFC4F5C3, 0xFF000000},
        {0xFF655789, 0xFFFDF7FF, 0xFFD2C1FA, 0xFF473A6A, 0xFF635C71, 0xFFFDF7FF, 0xFFE8DEF8, 0xFF554E63, 0xFF7C5270, 0xFFFFF7F9, 0xFFFAC5E9, 0xFF633C59, 0xFFA8364B, 0xFFFFF7F7, 0xFFF97386, 0xFF6E0523, 0xFFFDF7FE, 0xFF34313A, 0xFFE7E0EC, 0xFF615D67, 0xFF7D7983, 0xFFB6B0BB, 0xFFDED8E4, 0xFFFDF7FE, 0xFFFFFFFF, 0xFFF8F1FA, 0xFFF2ECF5, 0xFFECE6F0, 0xFFE7E0EC, 0xFF0F0D12, 0xFFA09BA1, 0xFFD2C1FA, 0xFF000000},
        {0xFF1B6968, 0xFFE0FFFD, 0xFFA8EFEC, 0xFF005C5A, 0xFF4A6463, 0xFFE2FFFD, 0xFFCCE8E6, 0xFF3D5655, 0xFF38647C, 0xFFF4FAFF, 0xFFB7E3FF, 0xFF26536A, 0xFFA83836, 0xFFFFF7F6, 0xFFFA746F, 0xFF6E0A12, 0xFFF6FAF9, 0xFF2A3434, 0xFFD9E5E3, 0xFF566160, 0xFF727D7C, 0xFFA9B4B3, 0xFFD1DCDB, 0xFFF6FAF9, 0xFFFFFFFF, 0xFFEEF5F4, 0xFFE7F0EE, 0xFFE1EAE9, 0xFFD9E5E3, 0xFF0A0F0F, 0xFF999E9D, 0xFFB0F8F5, 0xFF000000},
        {0xFF884D5F, 0xFFFFF7F7, 0xFFFAAFC4, 0xFF622D3F, 0xFF75575F, 0xFFFFF7F7, 0xFFFFD9E2, 0xFF674A52, 0xFF72557B, 0xFFFFF7FB, 0xFFF0CBF8, 0xFF5D4166, 0xFFA8364B, 0xFFFFF7F7, 0xFFF97386, 0xFF6E0523, 0xFFFFF8F8, 0xFF3C2F32, 0xFFF3DDE1, 0xFF6B5B5F, 0xFF88767A, 0xFFC1ADB1, 0xFFEAD5D9, 0xFFFFF8F8, 0xFFFFFFFF, 0xFFFFF0F2, 0xFFFCE9EC, 0xFFF7E3E7, 0xFFF3DDE1, 0xFF130D0E, 0xFFA69A9C, 0xFFFAAFC4, 0xFF000000},
        {0xFF7C591C, 0xFFFFF8F2, 0xFFFDCD85, 0xFF634305, 0xFF6F5C40, 0xFFFFF8F2, 0xFFF9DFBB, 0xFF614F34, 0xFF855338, 0xFFFFF7F5, 0xFFF1AF8F, 0xFF5A2F17, 0xFFA73B21, 0xFFFFF7F6, 0xFFFD795A, 0xFF6E1400, 0xFFFFF8F3, 0xFF3A3125, 0xFFF0E0CE, 0xFF685D4F, 0xFF85796A, 0xFFBEB09F, 0xFFE7D8C6, 0xFFFFF8F3, 0xFFFFFFFF, 0xFFFEF2E4, 0xFFF9ECDD, 0xFFF4E6D6, 0xFFF0E0CE, 0xFF120E08, 0xFFA49B92, 0xFFFDCD85, 0xFF000000},
        {0xFF2F6482, 0xFFF5F9FF, 0xFF9DD0F2, 0xFF044763, 0xFF4F616E, 0xFFF5F9FF, 0xFFD2E5F4, 0xFF425460, 0xFF555D85, 0xFFFAF8FF, 0xFFC8CFFE, 0xFF3D456C, 0xFFA83836, 0xFFFFF7F6, 0xFFFA746F, 0xFF6E0A12, 0xFFF7F9FD, 0xFF2C3339, 0xFFDCE3EA, 0xFF596066, 0xFF747C82, 0xFFABB3BA, 0xFFD4DBE2, 0xFFF7F9FD, 0xFFFFFFFF, 0xFFF0F4F9, 0xFFE9EEF4, 0xFFE3E9EF, 0xFFDCE3EA, 0xFF0B0F11, 0xFF9A9DA0, 0xFF9DD0F2, 0xFF000000},
    };

    static final int[][] DARK = {
        {0xFFF8B8A0, 0xFF603524, 0xFF764634, 0xFFFFDCD0, 0xFFE7BEAF, 0xFF56392F, 0xFF50352A, 0xFFDFB6A8, 0xFFFFEFD3, 0xFF6D5721, 0xFFFFDF9B, 0xFF644F19, 0xFFFA746F, 0xFF490006, 0xFF871F21, 0xFFFF9993, 0xFF130D0A, 0xFFF9E0D8, 0xFF30231E, 0xFFBCA69F, 0xFF84716B, 0xFF55443F, 0xFF130D0A, 0xFF372924, 0xFF000000, 0xFF1A110E, 0xFF221713, 0xFF281D19, 0xFF30231E, 0xFFFFF8F6, 0xFF5D534F, 0xFF845340, 0xFF000000},
        {0xFFADC9EB, 0xFF26425E, 0xFF395571, 0xFFD1E5FF, 0xFFBAC8DB, 0xFF344151, 0xFF2F3D4C, 0xFFB3C0D3, 0xFFE2DBFF, 0xFF504B74, 0xFFD3CCFD, 0xFF47426B, 0xFFFA746F, 0xFF490006, 0xFF871F21, 0xFFFF9993, 0xFF0C0E12, 0xFFE1E6EF, 0xFF21262D, 0xFFA6ABB4, 0xFF71767E, 0xFF434850, 0xFF0C0E12, 0xFF272D33, 0xFF000000, 0xFF101418, 0xFF161A1F, 0xFF1C2026, 0xFF21262D, 0xFFF8F9FE, 0xFF535559, 0xFF46617E, 0xFF000000},
        {0xFFACD0AA, 0xFF28472B, 0xFF3A5A3C, 0xFFC8EDC5, 0xFFB8CCB5, 0xFF344433, 0xFF2F402F, 0xFFB1C5AE, 0xFFF8FFBC, 0xFF5D632F, 0xFFEAF0AF, 0xFF555A28, 0xFFF97758, 0xFF450900, 0xFF85230A, 0xFFFF9B82, 0xFF0C0F0B, 0xFFE0E8DB, 0xFF202820, 0xFFA5ADA2, 0xFF70786D, 0xFF424A41, 0xFF0C0F0B, 0xFF272E26, 0xFF000000, 0xFF10150F, 0xFF161B15, 0xFF1B211B, 0xFF202820, 0xFFF8FAF3, 0xFF535651, 0xFF466747, 0xFF000000},
        {0xFFCEC0EC, 0xFF453A5F, 0xFF574D72, 0xFFEADEFF, 0xFFCCC2DB, 0xFF433D51, 0xFF3E384C, 0xFFC4BBD4, 0xFFFFD7F0, 0xFF6D4562, 0xFFFAC5E9, 0xFF633C59, 0xFFF97386, 0xFF490013, 0xFF871C34, 0xFFFF97A3, 0xFF0F0D12, 0xFFEAE3EF, 0xFF27242D, 0xFFAFA9B4, 0xFF78737E, 0xFF4A4650, 0xFF0F0D12, 0xFF2E2B34, 0xFF000000, 0xFF141218, 0xFF1B181F, 0xFF211E26, 0xFF27242D, 0xFFFDF7FE, 0xFF575459, 0xFF64597F, 0xFF000000},
        {0xFF9BD0CE, 0xFF0D4846, 0xFF255A59, 0xFFB8EDEB, 0xFFB0CCCA, 0xFF2B4544, 0xFF27403F, 0xFFA9C5C3, 0xFFD7EEFF, 0xFF2F5C73, 0xFFB7E3FF, 0xFF26536A, 0xFFFA746F, 0xFF490006, 0xFF871F21, 0xFFFF9993, 0xFF0A0F0F, 0xFFDCE8E6, 0xFF1D2827, 0xFFA2ADAC, 0xFF6D7877, 0xFF3F4A49, 0xFF0A0F0F, 0xFF242E2D, 0xFF000000, 0xFF0E1514, 0xFF131B1B, 0xFF192121, 0xFF1D2827, 0xFFF6FAF9, 0xFF515655, 0xFF336766, 0xFF000000},
        {0xFFF3B6C6, 0xFF5E3340, 0xFF734553, 0xFFFFDAE2, 0xFFE3BDC6, 0xFF543940, 0xFF4E343B, 0xFFDBB6BF, 0xFFFADCFF, 0xFF66496F, 0xFFF0CBF8, 0xFF5D4166, 0xFFF97386, 0xFF490013, 0xFF871C34, 0xFFFF97A3, 0xFF130D0E, 0xFFF6E0E4, 0xFF2F2226, 0xFFBAA6AA, 0xFF827175, 0xFF534448, 0xFF130D0E, 0xFF36292C, 0xFF000000, 0xFF191113, 0xFF211719, 0xFF271D1F, 0xFF2F2226, 0xFFFFF8F8, 0xFF5C5354, 0xFF81515F, 0xFF000000},
        {0xFFE7C088, 0xFF553B0F, 0xFF694D20, 0xFFFFDEB1, 0xFFDCC3A1, 0xFF4E3D24, 0xFF493920, 0xFFD4BC9A, 0xFFFFBD9D, 0xFF653820, 0xFFF1AF8F, 0xFF5A2F17, 0xFFF97758, 0xFF450900, 0xFF85230A, 0xFFFF9B82, 0xFF120E08, 0xFFF3E3D1, 0xFF2D2419, 0xFFB7A998, 0xFF7F7465, 0xFF504639, 0xFF120E08, 0xFF332B1F, 0xFF000000, 0xFF18130B, 0xFF1F1910, 0xFF251E15, 0xFF2D2419, 0xFFFFF8F3, 0xFF5B544C, 0xFF775A2B, 0xFF000000},
        {0xFFA6CBE6, 0xFF1D445A, 0xFF31576D, 0xFFC7E8FF, 0xFFB6C9D8, 0xFF30424E, 0xFF2C3E49, 0xFFAFC2D0, 0xFFDADEFF, 0xFF464E75, 0xFFC8CFFE, 0xFF3D456C, 0xFFFA746F, 0xFF490006, 0xFF871F21, 0xFFFF9993, 0xFF0B0F11, 0xFFDFE6ED, 0xFF1F272C, 0xFFA5ACB3, 0xFF6F767D, 0xFF41494F, 0xFF0B0F11, 0xFF262D32, 0xFF000000, 0xFF0F1417, 0xFF151A1E, 0xFF1A2025, 0xFF1F272C, 0xFFF7F9FD, 0xFF525558, 0xFF3E637A, 0xFF000000},
    };

    private M3() {
    }

    static boolean dark(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 当前生效的令牌表：莫奈优先，取不到就退回八套种子色里的那一套。 */
    static int[] current(Context ctx) {
        if (Prefs.monet(ctx)) {
            int[] s = fromSystem(ctx);
            if (s != null) {
                return s;
            }
        }
        return of(ctx, Prefs.seed(ctx));
    }

    /** 种子色圆点的显示色：深色模式略微提亮，否则原色。 */
    static int seedColor(Context ctx, int index) {
        int base = SEEDS[Math.max(0, Math.min(SEEDS.length - 1, index))];
        if (!dark(ctx)) {
            return base;
        }
        return Color.rgb(
                Math.round(Color.red(base) + (255 - Color.red(base)) * 0.18f),
                Math.round(Color.green(base) + (255 - Color.green(base)) * 0.18f),
                Math.round(Color.blue(base) + (255 - Color.blue(base)) * 0.18f));
    }

    /** 选中的种子色圆点上，勾的对比色。 */
    static int onSeed(Context ctx, int index) {
        int color = seedColor(ctx, index);
        double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return lum > 0.55 ? 0xFF1B1B1B : 0xFFFFFFFF;
    }

    /** 8 套固定色板里选中的那一套；浅色/深色各一份。 */
    static int[] of(Context ctx, int index) {
        int i = Math.max(0, Math.min(SEEDS.length - 1, index));
        return dark(ctx) ? DARK[i] : LIGHT[i];
    }

    /**
     * 莫奈取色：Android 12 起系统自己就按 Material You（TonalSpot）出了
     * accent1/2/3 与 neutral1/2 五条色阶，直接按色调取就等价于一套
     * 系统算好的方案，不用在手机上重算 HCT。
     */
    static int[] fromSystem(Context ctx) {
        boolean dk = dark(ctx);
        int[] out = new int[COUNT];
        int[] fill = {0};
        String[] a1 = {"system_accent1_%d", "system_accent2_%d", "system_accent3_%d"};
        // primary / secondary / tertiary 三组
        int[][] groups = {{PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER},
                {SECONDARY, ON_SECONDARY, SECONDARY_CONTAINER, ON_SECONDARY_CONTAINER},
                {TERTIARY, ON_TERTIARY, TERTIARY_CONTAINER, ON_TERTIARY_CONTAINER}};
        int[] tones = dk ? new int[]{80, 20, 30, 90} : new int[]{40, 100, 90, 10};
        for (int g = 0; g < 3; g++) {
            for (int k = 0; k < 4; k++) {
                int c = sys(ctx, a1[g].replace("%d", String.valueOf(tones[k])));
                if (c == 0) {
                    return null; // 系统没给这套色阶（Android 11 及以下）
                }
                out[groups[g][k]] = c;
            }
        }
        int[] tonesSurface = dk ? new int[]{6, 90, 80, 60, 30, 12, 17, 22, 24, 4, 10}
                : new int[]{99, 10, 30, 50, 80, 96, 94, 92, 90, 100, 87};
        int[] roles = {SURFACE, ON_SURFACE, ON_SURFACE_VARIANT, OUTLINE, OUTLINE_VARIANT,
                SURFACE_CONTAINER_LOW, SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH,
                SURFACE_CONTAINER_HIGHEST, SURFACE_CONTAINER_LOWEST, SURFACE_DIM};
        for (int k = 0; k < roles.length; k++) {
            int c = sys(ctx, (k == 2 || k == 3 || k == 4 ? "system_neutral2_%d" : "system_neutral1_%d")
                    .replace("%d", String.valueOf(tonesSurface[k])));
            if (c == 0) {
                return null;
            }
            out[roles[k]] = c;
        }
        out[SURFACE_VARIANT] = out[SURFACE_CONTAINER_HIGH];
        out[SURFACE_BRIGHT] = sys(ctx, (dk ? "system_neutral1_24" : "system_neutral1_98"));
        out[INVERSE_SURFACE] = out[ON_SURFACE];
        out[INVERSE_ON_SURFACE] = out[SURFACE];
        out[INVERSE_PRIMARY] = sys(ctx, (dk ? "system_accent1_40" : "system_accent1_80"));
        out[ERROR] = sys(ctx, (dk ? "system_error_80" : "system_error_40"));
        out[ON_ERROR] = sys(ctx, (dk ? "system_error_20" : "system_error_100"));
        out[ERROR_CONTAINER] = sys(ctx, (dk ? "system_error_30" : "system_error_90"));
        out[ON_ERROR_CONTAINER] = sys(ctx, (dk ? "system_error_90" : "system_error_10"));
        out[SCRIM] = 0xFF000000;
        fill[0]++;
        return out;
    }

    private static int sys(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "color", "android");
        if (id == 0) {
            return 0;
        }
        try {
            return ctx.getResources().getColor(id);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 文字压在某个底色上时，挑对比度更高的那个前景令牌。 */
    static int on(int color) {
        double lum = (0.299 * android.graphics.Color.red(color)
                + 0.587 * android.graphics.Color.green(color)
                + 0.114 * android.graphics.Color.blue(color)) / 255.0;
        return lum < 0.5 ? 0xFFFFFFFF : 0xFF000000;
    }
}
