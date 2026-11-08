package com.readwide.manager;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.TapZoneProfile;
import com.readwide.manager.util.TapZoneProfile.Viewer;

final class SettingsTapZonesController {
    private final SettingsActivity activity;
    private final PrefsManager prefs;
    private Dialog dialog;
    private LinearLayout activePanel;

    SettingsTapZonesController(SettingsActivity activity) {
        this.activity = activity;
        prefs = activity.prefs;
        activity.getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onPause(androidx.lifecycle.LifecycleOwner owner) { dismiss(); }
            @Override public void onDestroy(androidx.lifecycle.LifecycleOwner owner) { dismiss(); }
        });
    }

    void bind(View entry) { entry.setOnClickListener(v -> showList()); }

    void showList() {
        LinearLayout panel = activity.createRoundedSettingsDialogPanel();
        panel.addView(activity.makeSettingsDialogTitle(activity.getString(R.string.settings_tap_zones), activity.dialogTextColor()));
        for (Viewer viewer : Viewer.values()) {
            TapZoneProfile profile = prefs.getViewerTapZones(viewer);
            String proportions = viewer != Viewer.TXT && prefs.usesTxtTapZones(viewer)
                    ? activity.getString(R.string.tap_profile_follow_txt)
                    : profile.leading + "/" + profile.middle() + "/" + profile.trailing;
            String orientation = activity.getString(profile.mode == PrefsManager.TAP_ZONE_HORIZONTAL
                    ? R.string.tap_profile_horizontal : R.string.tap_profile_vertical);
            profileRow(panel, viewer, proportions, orientation);
        }
        button(panel, activity.getString(R.string.close), this::dismiss);
        show(panel);
    }

    void showEditor(Viewer viewer) {
        LinearLayout panel = activity.createRoundedSettingsDialogPanel();
        panel.addView(activity.makeSettingsDialogTitle(name(viewer), activity.dialogTextColor()));
        Switch enabled = toggle(activity.getString(R.string.tap_profile_enabled), prefs.getViewerTapPagingEnabled(viewer));
        panel.addView(enabled);
        enabled.setOnCheckedChangeListener((v, value) -> {
            if (owns(panel)) prefs.setViewerTapPagingEnabled(viewer, value);
        });
        boolean inherited = prefs.usesTxtTapZones(viewer);
        if (viewer != Viewer.TXT) {
            Switch follow = toggle(activity.getString(R.string.tap_profile_follow_txt), inherited);
            panel.addView(follow);
            follow.setOnCheckedChangeListener((v, value) -> {
                if (!owns(panel)) return;
                prefs.setUsesTxtTapZones(viewer, value);
                showEditor(viewer);
            });
        }
        panel.addView(activity.makeSettingsDialogMessage(activity.getString(
                R.string.tap_profile_hint), activity.dialogSubTextColor()));
        TapZoneProfile initial = prefs.getViewerTapZones(viewer);
        Spinner mode = new Spinner(activity);
        mode.setContentDescription(activity.getString(R.string.tap_zone_mode));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                new String[]{activity.getString(R.string.tap_zone_vertical), activity.getString(R.string.tap_zone_horizontal)}) {
            private View style(View view) {
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(activity.dialogTextColor());
                    view.setBackgroundColor(activity.dialogPanelBackgroundColor());
                }
                return view;
            }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                return style(super.getView(position, recycled, parent));
            }
            @Override public View getDropDownView(int position, View recycled, android.view.ViewGroup parent) {
                return style(super.getDropDownView(position, recycled, parent));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(adapter);
        mode.setSelection(initial.mode);
        mode.setEnabled(!inherited);
        panel.addView(mode);

        LinearLayout preview = new LinearLayout(activity);
        preview.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panel.addView(preview);
        TextView leadingLabel = activity.makeSettingsDialogMessage("", activity.dialogTextColor());
        LinearLayout.LayoutParams leadingLabelParams = (LinearLayout.LayoutParams) leadingLabel.getLayoutParams();
        leadingLabelParams.topMargin = dp(12);
        leadingLabel.setLayoutParams(leadingLabelParams);
        TextView trailingLabel = activity.makeSettingsDialogMessage("", activity.dialogTextColor());
        SeekBar leading = new SeekBar(activity);
        SeekBar trailing = new SeekBar(activity);
        leading.setMax(75);
        trailing.setMax(75);
        leading.setProgress(initial.leading - 5);
        trailing.setProgress(initial.trailing - 5);
        leading.setEnabled(!inherited);
        trailing.setEnabled(!inherited);
        panel.addView(leadingLabel);
        panel.addView(leading);
        panel.addView(trailingLabel);
        panel.addView(trailing);
        Runnable refresh = () -> {
            TapZoneProfile value = prefs.getViewerTapZones(viewer);
            renderPreview(preview, value);
            String first = edgeLabel(value, true), last = edgeLabel(value, false);
            leadingLabel.setText(first);
            trailingLabel.setText(last);
            leading.setContentDescription(first);
            trailing.setContentDescription(last);
        };
        refresh.run();
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private int previousSelection = initial.mode;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) ((TextView) view).setTextColor(activity.dialogTextColor());
                if (position == previousSelection) return;
                previousSelection = position;
                if (!owns(panel) || prefs.usesTxtTapZones(viewer)) return;
                TapZoneProfile value = prefs.getViewerTapZones(viewer);
                if (value.mode == position) return;
                prefs.setViewerTapZones(viewer, new TapZoneProfile(position, value.leading, value.trailing));
                refresh.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        SeekBar.OnSeekBarChangeListener ratios = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || !owns(panel) || prefs.usesTxtTapZones(viewer)) return;
                int first = leading.getProgress() + 5, last = trailing.getProgress() + 5;
                if (first + last > 90) {
                    if (bar == leading) last = 90 - first;
                    else first = 90 - last;
                }
                TapZoneProfile value = new TapZoneProfile(prefs.getViewerTapZones(viewer).mode, first, last);
                prefs.setViewerTapZones(viewer, value);
                leading.setProgress(value.leading - 5);
                trailing.setProgress(value.trailing - 5);
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
        leading.setOnSeekBarChangeListener(ratios);
        trailing.setOnSeekBarChangeListener(ratios);
        activity.styleSettingsDialogControls(panel);
        button(panel, activity.getString(R.string.tap_profiles_back), this::showList);
        SettingsPreferenceViewState.useStoredValues(panel);
        show(panel);
    }

    private String edgeLabel(TapZoneProfile value, boolean first) {
        boolean horizontal = value.mode == PrefsManager.TAP_ZONE_HORIZONTAL;
        int label = horizontal ? (first ? R.string.tap_profile_left : R.string.tap_profile_right)
                : (first ? R.string.tap_profile_top : R.string.tap_profile_bottom);
        return activity.getString(R.string.tap_profile_edge, activity.getString(label), first ? value.leading : value.trailing);
    }

    private void renderPreview(LinearLayout preview, TapZoneProfile value) {
        boolean horizontal = value.mode == PrefsManager.TAP_ZONE_HORIZONTAL;
        preview.removeAllViews();
        preview.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(horizontal ? 84 : 168));
        previewParams.topMargin = dp(12);
        preview.setLayoutParams(previewParams);
        String[] labels = {edgeLabel(value, true), activity.getString(R.string.tap_profile_edge,
                activity.getString(R.string.tap_profile_middle), value.middle()), edgeLabel(value, false)};
        int[] weights = {value.leading, value.middle(), value.trailing};
        for (int i = 0; i < 3; i++) {
            TextView cell = new TextView(activity);
            cell.setText(labels[i]);
            cell.setTextSize(12f);
            cell.setTextColor(activity.dialogTextColor());
            cell.setGravity(Gravity.CENTER);
            GradientDrawable background = new GradientDrawable();
            background.setColor(activity.dialogPanelBackgroundColor());
            background.setCornerRadius(dp(8));
            background.setStroke(dp(1), activity.dialogOutlineColor());
            cell.setBackground(background);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(horizontal ? 0 : -1,
                    horizontal ? -1 : 0, weights[i]);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            preview.addView(cell, lp);
        }
    }

    private Switch toggle(String label, boolean checked) {
        Switch view = new Switch(activity);
        view.setText(label);
        view.setTextColor(activity.dialogTextColor());
        view.setMinHeight(dp(48));
        view.setChecked(checked);
        return view;
    }

    private void profileRow(LinearLayout panel, Viewer viewer, String proportions, String orientation) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(36));
        row.setPaddingRelative(dp(12), dp(4), dp(12), dp(4));
        row.setFocusable(true);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setContentDescription(activity.getString(R.string.tap_profile_summary,
                name(viewer), proportions, orientation));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        GradientDrawable background = new GradientDrawable();
        background.setColor(activity.dialogRowBackgroundColor());
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), activity.dialogOutlineColor());
        row.setBackground(background);
        String[] labels = {name(viewer), proportions, orientation};
        float[] weights = {1.1f, 1.3f, 1.1f};
        for (int i = 0; i < labels.length; i++) {
            TextView cell = new TextView(activity);
            cell.setText(labels[i]);
            cell.setTextSize(14f);
            cell.setTextColor(activity.dialogTextColor());
            cell.setIncludeFontPadding(false);
            cell.setSingleLine(false);
            cell.setMaxLines(2);
            cell.setEllipsize(TextUtils.TruncateAt.END);
            int horizontalGravity = i == 1 ? Gravity.CENTER_HORIZONTAL : i == 2 ? Gravity.END : Gravity.START;
            cell.setGravity(horizontalGravity | Gravity.CENTER_VERTICAL);
            cell.setTextAlignment(i == 1 ? View.TEXT_ALIGNMENT_CENTER
                    : i == 2 ? View.TEXT_ALIGNMENT_VIEW_END : View.TEXT_ALIGNMENT_VIEW_START);
            if (i == 2) cell.setPaddingRelative(0, 0, dp(6), 0);
            cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams column = new LinearLayout.LayoutParams(0, -2, weights[i]);
            if (i > 0) column.setMarginStart(dp(8));
            row.addView(cell, column);
        }
        row.setOnClickListener(v -> { if (owns(panel)) showEditor(viewer); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        panel.addView(row, params);
    }

    private MaterialButton button(LinearLayout panel, String label, Runnable action) {
        MaterialButton button = activity.makeSettingsDialogButtonNoShade(
                label, activity.dialogTextColor(), activity.dialogOutlineColor());
        button.setSingleLine(false);
        button.setOnClickListener(v -> { if (owns(panel)) action.run(); });
        panel.addView(button);
        return button;
    }

    private String name(Viewer viewer) {
        if (viewer == Viewer.IMAGE) return activity.getString(R.string.tap_viewer_image);
        if (viewer == Viewer.WORD) return "Word / HWP";
        if (viewer == Viewer.MARKDOWN) return "Markdown";
        return viewer.name();
    }

    private boolean owns(LinearLayout panel) {
        return activePanel == panel && dialog != null && dialog.isShowing()
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void show(LinearLayout panel) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) {
            activePanel = panel;
            activity.replaceRoundedSettingsDialogContent(dialog, panel);
            return;
        }
        dismiss();
        Dialog next = activity.createRoundedSettingsDialog();
        dialog = next;
        activePanel = panel;
        next.setOnDismissListener(d -> { if (dialog == next) { dialog = null; activePanel = null; } });
        activity.showRoundedSettingsDialog(next, panel);
    }

    void dismiss() {
        Dialog old = dialog;
        dialog = null;
        activePanel = null;
        if (old != null) old.dismiss();
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
