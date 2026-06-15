package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.PrefsManager;

/**
 * Shared styling for the centered loading boxes used before/inside readers.
 * Keep these boxes tied to the active theme panel instead of deriving a loose
 * gray overlay from the background, otherwise dark custom themes look detached.
 */
final class LoadingWindowTheme {
    static final class Colors {
        final int bg;
        final int panel;
        final int fg;
        final int line;
        final int box;
        final int border;

        Colors(int bg, int panel, int fg, int line, int box, int border) {
            this.bg = bg;
            this.panel = panel;
            this.fg = fg;
            this.line = line;
            this.box = box;
            this.border = border;
        }
    }

    private LoadingWindowTheme() {}

    @NonNull
    static Colors main(@NonNull Context context, @Nullable PrefsManager prefs) {
        final boolean dark = prefs == null || prefs.shouldUseDarkColors(context);
        final int bg = prefs != null
                ? prefs.getMainBgColor(context)
                : (dark ? Color.rgb(33, 33, 33) : Color.WHITE);
        final int panel = prefs != null
                ? prefs.getMainPanelColor(context)
                : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = prefs != null
                ? prefs.getMainTextColor(context)
                : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int line = prefs != null
                ? prefs.getMainOutlineColor(context)
                : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));
        return fromPalette(bg, panel, fg, line);
    }

    @NonNull
    static Colors reader(int readerBg, int readerFg) {
        final boolean dark = !UiColorUtils.isLightColor(readerBg);
        final int panel = UiColorUtils.blendColors(readerBg, readerFg, dark ? 0.08f : 0.05f);
        final int line = UiColorUtils.blendColors(readerBg, readerFg, dark ? 0.24f : 0.18f);
        return fromPalette(readerBg, panel, readerFg, line);
    }

    @NonNull
    static Colors fromPalette(int bg, int panel, int fg, int line) {
        final boolean lightPanel = UiColorUtils.isLightColor(panel);
        final int box = UiColorUtils.blendColors(panel, bg, lightPanel ? 0.04f : 0.06f);
        final int border = UiColorUtils.blendColors(line, fg, lightPanel ? 0.06f : 0.10f);
        return new Colors(bg, panel, fg, line, box, border);
    }

    @NonNull
    static GradientDrawable boxDrawable(@NonNull Context context, @NonNull Colors colors) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colors.box);
        drawable.setCornerRadius(dp(context, 24));
        drawable.setStroke(Math.max(1, dp(context, 1)), colors.border);
        return drawable;
    }


    /**
     * Dialog-backed loading boxes must not inherit platform dialog enter animations.
     * Some OEM builds animate a small dialog from a slightly higher y-position into
     * the center, which makes the loading box appear to drop down. Apply this both
     * before and after show(): before show() prevents the enter animation from being
     * selected, after show() reasserts the final centered layout once the window is
     * attached.
     */
    static void configureCenteredDialogWindow(@NonNull Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;

        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0f);
        window.setGravity(Gravity.CENTER);
        window.setWindowAnimations(0);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        lp.dimAmount = 0f;
        lp.horizontalMargin = 0f;
        lp.verticalMargin = 0f;
        lp.windowAnimations = 0;
        window.setAttributes(lp);
    }

    private static int dp(@NonNull Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
