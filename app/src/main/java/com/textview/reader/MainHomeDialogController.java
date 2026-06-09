package com.textview.reader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.textview.reader.util.PrefsManager;

/**
 * Owns the small MainActivity popups that are unrelated to file traversal or
 * viewer launch state. Keeping these menus out of MainActivity makes the file
 * manager path easier to audit while preserving the existing visual behavior.
 */
final class MainHomeDialogController {
    private final MainActivity activity;

    MainHomeDialogController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void showMainOverflowDialog() {
        if (activity.homeMode || activity.searchMode || activity.mainOverflowButton == null) return;
        final boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        final int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int line = activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        final int popupWidth = activity.dpToPx(170);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, activity.dpToPx(3), 0, activity.dpToPx(3));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(panel);
        bgShape.setCornerRadius(activity.dpToPx(8));
        box.setBackground(bgShape);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            box.setClipToOutline(true);
        }

        PopupWindow popup = new PopupWindow(
                box,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(activity.dpToPx(3));
        }

        addMainOverflowPopupRow(box, activity.getString(R.string.new_folder), fg, () -> {
            popup.dismiss();
            activity.showNewFolderDialog();
        });

        final TextView[] hiddenCheckRef = new TextView[1];
        hiddenCheckRef[0] = addMainOverflowHiddenRow(box, fg, line, () -> {
            boolean newVal = activity.prefs == null || !activity.prefs.getShowHiddenFiles();
            if (activity.prefs != null) activity.prefs.setShowHiddenFiles(newVal);
            updateHiddenFilesIndicator(hiddenCheckRef[0], line);
            if (activity.currentDirectory != null) activity.refreshCurrentDirectoryWithoutClearing(activity.currentDirectory);
        });

        int xoff = -(popupWidth - activity.mainOverflowButton.getWidth());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            popup.showAsDropDown(activity.mainOverflowButton, xoff, 0, Gravity.NO_GRAVITY);
        } else {
            popup.showAsDropDown(activity.mainOverflowButton, xoff, 0);
        }
    }

    private TextView addMainOverflowHiddenRow(@NonNull LinearLayout box, int textColor, int outlineColor, @NonNull Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(12), 0, activity.dpToPx(10), 0);
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());

        TextView label = new TextView(activity);
        label.setText(activity.getString(R.string.show_hidden_files));
        label.setTextColor(textColor);
        label.setTextSize(15f);
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        // Keep the O/X indicator in the same horizontal slot for English and Korean.
        // The Korean label is shorter, so a fixed label column prevents the marker
        // from jumping left while still leaving enough room for "Show hidden files".
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(activity.dpToPx(124), LinearLayout.LayoutParams.MATCH_PARENT);
        row.addView(label, labelLp);

        TextView indicator = new TextView(activity);
        indicator.setText("");
        indicator.setGravity(Gravity.CENTER);
        indicator.setIncludeFontPadding(false);
        indicator.setMinWidth(0);
        indicator.setMinHeight(0);
        LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams(activity.dpToPx(14), LinearLayout.LayoutParams.MATCH_PARENT);
        indicatorLp.setMargins(activity.dpToPx(3), 0, 0, 0);
        row.addView(indicator, indicatorLp);

        Space tailSpace = new Space(activity);
        row.addView(tailSpace, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        updateHiddenFilesIndicator(indicator, outlineColor);

        box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(44)));
        return indicator;
    }

    private void updateHiddenFilesIndicator(TextView indicator, int outlineColor) {
        if (indicator == null) return;
        boolean showHidden = activity.prefs != null && activity.prefs.getShowHiddenFiles();
        int inactive = activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : outlineColor;
        indicator.setBackgroundColor(Color.TRANSPARENT);
        indicator.setText(showHidden ? "O" : "X");
        indicator.setTextColor(inactive);
        indicator.setTextSize(14f);
        indicator.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        indicator.setGravity(Gravity.CENTER);
        indicator.setIncludeFontPadding(false);
    }

    private TextView addMainOverflowPopupRow(@NonNull LinearLayout box, @NonNull String label, int textColor, @NonNull Runnable action) {
        TextView row = new TextView(activity);
        row.setText(label);
        row.setTextColor(textColor);
        row.setTextSize(15f);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setSingleLine(true);
        row.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);
        row.setBackgroundColor(Color.TRANSPARENT);
        box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(44)));
        row.setOnClickListener(v -> action.run());
        return row;
    }


    void showSortDialog() {
        final boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        final int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        final int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int sub = activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        final int line = activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(18), activity.dpToPx(16), activity.dpToPx(18), activity.dpToPx(10));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(activity.dpToPx(18));
        bgShape.setStroke(Math.max(1, activity.dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.sort_by));
        title.setTextColor(fg);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(12));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, 0, 0, activity.dpToPx(2));
        box.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        boolean sortingRecentHome = activity.homeMode && !activity.searchMode;
        final int recentReadId = View.generateViewId();

        if (sortingRecentHome) {
            group.addView(makeSortRadioButton(recentReadId, activity.getString(R.string.sort_recent_read), fg, panel, line));
        }

        int[] ids = new int[7];
        for (int i = 0; i < ids.length; i++) ids[i] = View.generateViewId();

        CharSequence[] labels = new CharSequence[] {
                activity.getString(R.string.sort_name_asc),
                activity.getString(R.string.sort_name_desc),
                activity.getString(R.string.sort_date_new),
                activity.getString(R.string.sort_date_old),
                activity.getString(R.string.sort_size_large),
                activity.getString(R.string.sort_size_small),
                activity.getString(R.string.sort_type)
        };

        for (int i = 0; i < labels.length; i++) {
            group.addView(makeSortRadioButton(ids[i], labels[i], fg, panel, line));
        }

        int current = sortingRecentHome
                ? (activity.prefs != null ? activity.prefs.getRecentSortMode() : PrefsManager.SORT_RECENT_READ)
                : (activity.prefs != null ? activity.prefs.getSortMode() : PrefsManager.SORT_NAME_ASC);
        if (sortingRecentHome && current == PrefsManager.SORT_RECENT_READ) {
            group.check(recentReadId);
        } else if (current >= 0 && current < ids.length) {
            group.check(ids[current]);
        } else {
            group.check(ids[PrefsManager.SORT_NAME_ASC]);
        }

        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cancel));
        cancel.setTextColor(sub);
        cancel.setTextSize(16f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(48)));

        android.app.Dialog dialog = activity.createStableBottomDialog(box, activity.dpToPx(74), 0.22f);

        group.setOnCheckedChangeListener((g, checkedId) -> {
            if (sortingRecentHome && checkedId == recentReadId) {
                if (activity.prefs != null) activity.prefs.setRecentSortMode(PrefsManager.SORT_RECENT_READ);
                activity.loadRecentFiles();
                activity.scrollListToTop(activity.recentRecyclerView);
                dialog.dismiss();
                return;
            }

            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId) {
                    if (sortingRecentHome) {
                        if (activity.prefs != null) activity.prefs.setRecentSortMode(i);
                        activity.loadRecentFiles();
                        activity.scrollListToTop(activity.recentRecyclerView);
                    } else {
                        if (activity.prefs != null) activity.prefs.setSortMode(i);

                        // Sorting changes should not re-enumerate the current folder.
                        // Keep the visible/current result set and only reorder it in memory.
                        activity.resortVisibleFileListAsync(i);
                    }
                    dialog.dismiss();
                    break;
                }
            }
        });
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private RadioButton makeSortRadioButton(int id, CharSequence label, int fg, int panel, int line) {
        RadioButton radio = new RadioButton(activity);
        radio.setId(id);
        radio.setText(label);
        radio.setTextColor(fg);
        radio.setTextSize(16f);
        radio.setGravity(Gravity.CENTER_VERTICAL);

        // Draw the radio circle as a normal compound drawable instead of using
        // the platform button slot. The platform slot hugs the far-left edge on
        // some devices; this keeps the bubble naturally inset inside the row.
        radio.setButtonDrawable(null);
        radio.setCompoundDrawablesWithIntrinsicBounds(makeSortRadioCircleDrawable(fg, line, panel), null, null, null);
        radio.setCompoundDrawablePadding(activity.dpToPx(12));
        radio.setPadding(activity.dpToPx(18), 0, activity.dpToPx(14), 0);

        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(panel);
        rowBg.setCornerRadius(activity.dpToPx(12));
        rowBg.setStroke(Math.max(1, activity.dpToPx(1)), line);
        radio.setBackground(rowBg);

        RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                activity.dpToPx(48));
        lp.setMargins(0, 0, 0, activity.dpToPx(8));
        radio.setLayoutParams(lp);
        return radio;
    }

    private Drawable makeSortRadioCircleDrawable(int fg, int line, int panel) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_checked }, makeSortRadioCircle(true, fg, line, panel));
        states.addState(new int[] {}, makeSortRadioCircle(false, fg, line, panel));
        return states;
    }

    private Drawable makeSortRadioCircle(boolean checked, int fg, int line, int panel) {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(Color.TRANSPARENT);
        outer.setStroke(Math.max(1, activity.dpToPx(2)), checked ? fg : line);
        outer.setSize(activity.dpToPx(20), activity.dpToPx(20));

        if (!checked) return outer;

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(fg);
        inner.setSize(activity.dpToPx(10), activity.dpToPx(10));

        LayerDrawable layer = new LayerDrawable(new Drawable[] { outer, inner });
        int inset = activity.dpToPx(5);
        layer.setLayerInset(1, inset, inset, inset, inset);
        return layer;
    }



}
