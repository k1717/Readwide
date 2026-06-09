package com.textview.reader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Compact bottom action-sheet builder for main-screen long-hold menus.
 * The title and cancel button stay fixed while the middle card list scrolls vertically.
 */
final class MainVerticalActionSheet {
    private final MainActivity activity;

    MainVerticalActionSheet(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    Colors readColors() {
        boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        int sub = activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        int line = activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));
        int danger = dark ? Color.rgb(255, 170, 170) : Color.rgb(176, 0, 32);
        return new Colors(bg, panel, fg, sub, line, danger);
    }

    LinearLayout makeBox(@NonNull Colors colors) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(16), activity.dpToPx(12), activity.dpToPx(16), activity.dpToPx(8));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(colors.bg);
        bgShape.setCornerRadius(activity.dpToPx(18));
        bgShape.setStroke(Math.max(1, activity.dpToPx(1)), colors.line);
        box.setBackground(bgShape);
        return box;
    }

    void addTitle(@NonNull LinearLayout box,
                  @NonNull String text,
                  int textColor,
                  boolean allowTwoLines) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextColor(textColor);
        title.setTextSize(allowTwoLines ? 18f : 19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setSingleLine(false);
        title.setHorizontallyScrolling(false);
        title.setEllipsize(null);
        title.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        title.setIncludeFontPadding(false);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(10));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    void addScrollableActions(@NonNull LinearLayout box,
                              @NonNull List<Action> actions,
                              int panelColor,
                              boolean selectionSheet) {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Action action : actions) {
            addActionCard(list, action.label, action.textColor, panelColor, action.action);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setVerticalScrollBarEnabled(actions.size() > 5);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        int rowHeight = activity.dpToPx(48);
        int desiredHeight = actions.size() * rowHeight;
        int maxHeight = Math.min(activity.dpToPx(selectionSheet ? 244 : 286),
                Math.max(activity.dpToPx(180), activity.getResources().getDisplayMetrics().heightPixels - activity.dpToPx(330)));
        int minHeight = Math.min(desiredHeight, activity.dpToPx(selectionSheet ? 150 : 170));
        int scrollHeight = Math.max(minHeight, Math.min(desiredHeight, maxHeight));
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                scrollHeight));
    }

    private void addActionCard(@NonNull LinearLayout box,
                               @NonNull String label,
                               int textColor,
                               int panelColor,
                               @NonNull Runnable action) {
        TextView row = new TextView(activity);
        row.setText(label);
        row.setTextColor(textColor);
        row.setTextSize(16f);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setIncludeFontPadding(false);
        row.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(panelColor);
        rowBg.setCornerRadius(activity.dpToPx(12));
        row.setBackground(rowBg);
        row.setOnClickListener(v -> action.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42));
        lp.setMargins(0, 0, 0, activity.dpToPx(6));
        box.addView(row, lp);
    }

    TextView addCancel(@NonNull LinearLayout box, int textColor) {
        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cancel));
        cancel.setTextColor(textColor);
        cancel.setTextSize(15.5f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setIncludeFontPadding(false);
        cancel.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(44));
        cancelLp.setMargins(0, activity.dpToPx(4), 0, 0);
        box.addView(cancel, cancelLp);
        return cancel;
    }

    int compactDialogWidthPx() {
        return MainActionPopupSizing.longHoldSheetWidth(activity);
    }

    void dismiss(android.app.Dialog[] ref) {
        if (ref[0] != null) ref[0].dismiss();
    }

    static final class Colors {
        final int bg;
        final int panel;
        final int fg;
        final int sub;
        final int line;
        final int danger;

        Colors(int bg, int panel, int fg, int sub, int line, int danger) {
            this.bg = bg;
            this.panel = panel;
            this.fg = fg;
            this.sub = sub;
            this.line = line;
            this.danger = danger;
        }
    }

    static final class Action {
        final String label;
        final int textColor;
        final Runnable action;

        Action(@NonNull String label, int textColor, @NonNull Runnable action) {
            this.label = label;
            this.textColor = textColor;
            this.action = action;
        }
    }
}
