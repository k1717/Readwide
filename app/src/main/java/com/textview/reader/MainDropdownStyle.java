package com.textview.reader;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.NonNull;

/**
 * Shared color/background policy for compact main-toolbar dropdown menus.
 * Keeps pending-action and multi-selection popups from duplicating theme math.
 */
final class MainDropdownStyle {
    final boolean dark;
    final int panel;
    final int rowPanel;
    final int fg;
    final int sub;
    final int danger;
    final int line;

    private MainDropdownStyle(boolean dark, int panel, int rowPanel, int fg, int sub, int danger, int line) {
        this.dark = dark;
        this.panel = panel;
        this.rowPanel = rowPanel;
        this.fg = fg;
        this.sub = sub;
        this.danger = danger;
        this.line = line;
    }

    static MainDropdownStyle from(@NonNull MainActivity activity) {
        boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        int panel = activity.prefs != null
                ? activity.prefs.getMainPanelColor(activity)
                : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        int rowPanel = activity.prefs != null
                ? activity.prefs.getMainBgColor(activity)
                : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        int fg = activity.prefs != null
                ? activity.prefs.getMainTextColor(activity)
                : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        int sub = activity.prefs != null
                ? activity.prefs.getMainSubTextColor(activity)
                : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        int line = activity.prefs != null
                ? activity.prefs.getMainOutlineColor(activity)
                : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));
        int danger = dark ? Color.rgb(255, 170, 170) : Color.rgb(176, 0, 32);
        return new MainDropdownStyle(dark, panel, rowPanel, fg, sub, danger, line);
    }

    GradientDrawable makePanelBackground(@NonNull MainActivity activity, int radiusDp, boolean includeStroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panel);
        bg.setCornerRadius(activity.dpToPx(radiusDp));
        if (includeStroke) {
            bg.setStroke(Math.max(1, activity.dpToPx(1)), line);
        }
        return bg;
    }
}
