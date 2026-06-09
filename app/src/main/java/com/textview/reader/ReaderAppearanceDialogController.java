package com.textview.reader;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.textview.reader.model.Theme;
import com.textview.reader.util.FileUtils;

import java.io.File;

/**
 * Owns reader appearance/info popups that do not participate in TXT page
 * continuity.  Keeping these UI-only dialogs out of ReaderActivity lets the
 * exact TXT page/partition path remain easier to audit.
 */
final class ReaderAppearanceDialogController {
    private static final int TXT_TOOLBAR_POPUP_Y_DP = 74;

    private final ReaderActivity activity;

    ReaderAppearanceDialogController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void showBrightnessDialog() {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.brightness), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(activity.dpToPx(24), activity.dpToPx(12), activity.dpToPx(24), activity.dpToPx(10));

        TextView label = activity.dialogStyler().makeReaderDialogLabel(activity.getString(R.string.screen_brightness), fg, 14f);
        label.setPadding(0, 0, 0, activity.dpToPx(8));
        box.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar seekbar = new SeekBar(activity);
        seekbar.setMax(100);
        seekbar.setProgress((int) (activity.prefs.getBrightnessValue() * 100));
        activity.dialogStyler().styleSeekBarForReaderDialog(seekbar, bg, sub);
        boolean brightnessOverrideEnabled = activity.prefs.getBrightnessOverride();
        seekbar.setEnabled(brightnessOverrideEnabled);
        seekbar.setAlpha(brightnessOverrideEnabled ? 1f : 0.55f);
        box.addView(seekbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(44)));

        SwitchCompat switchOverride = new SwitchCompat(activity);
        switchOverride.setText(activity.getString(R.string.override_system_brightness));
        switchOverride.setTextSize(14f);
        switchOverride.setChecked(brightnessOverrideEnabled);
        activity.dialogStyler().styleCompoundForReaderDialog(switchOverride, bg, fg);
        box.addView(switchOverride, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(48)));

        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(activity.dialogStyler().positionedActionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg),
                activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actionRow.setPadding(activity.dpToPx(30), 0, activity.dpToPx(30), 0);

        TextView ok = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.ok), fg,
                Gravity.CENTER);
        actionRow.addView(ok, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(54)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.85f,
                360,
                false);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    float brightness = progress / 100f;
                    activity.prefs.setBrightnessValue(brightness);
                    if (switchOverride.isChecked()) {
                        activity.applyReaderBrightnessOverride(brightness);
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        switchOverride.setOnCheckedChangeListener((v, checked) -> {
            activity.prefs.setBrightnessOverride(checked);
            seekbar.setEnabled(checked);
            seekbar.setAlpha(checked ? 1f : 0.55f);
            if (checked) {
                activity.applyReaderBrightnessOverride(activity.prefs.getBrightnessValue());
            } else {
                activity.clearReaderBrightnessOverride();
            }
        });

        ok.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    void showReaderCustomThemeActionsDialog(@NonNull Theme theme) {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.TRANSPARENT);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(8), pad, activity.dpToPx(8));

        TextView message = new TextView(activity);
        message.setText(activity.getString(R.string.custom_theme_options_message));
        message.setTextColor(sub);
        message.setTextSize(14f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(12));
        list.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView edit = activity.dialogStyler().makeReaderCenteredActionButton(activity.getString(R.string.edit_theme), fg);
        TextView delete = activity.dialogStyler().makeReaderCenteredActionButton(activity.getString(R.string.delete_theme), fg);
        TextView cancel = activity.dialogStyler().makeReaderCenteredActionButton(activity.getString(R.string.cancel), sub);
        list.addView(edit);
        list.addView(delete);
        list.addView(cancel);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(theme.getName(), bg, fg);
        final AlertDialog[] ref = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setCustomTitle(title)
                .setView(list)
                .create();
        ref[0] = dialog;

        edit.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            Intent editIntent = new Intent(activity, ThemeEditorActivity.class);
            editIntent.putExtra(ThemeEditorActivity.EXTRA_THEME_ID, theme.getId());
            activity.startActivity(editIntent);
        });
        delete.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            showReaderDeleteCustomThemeDialog(theme);
        });
        cancel.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });

        activity.dialogStyler().prepareReaderCustomThemeDialogWindowNoJump(dialog, true);
        dialog.setOnShowListener(d -> {
            activity.dialogStyler().styleReaderDialogWindow(dialog, bg, fg, sub);
            activity.dialogStyler().prepareReaderCustomThemeDialogWindowNoJump(dialog, false);
            if (dialog.getWindow() != null) {
                View decor = dialog.getWindow().getDecorView();
                decor.post(() -> decor.setAlpha(1f));
            }
        });
        dialog.show();
        activity.dialogStyler().prepareReaderCustomThemeDialogWindowNoJump(dialog, false);
    }

    void showReaderDeleteCustomThemeDialog(@NonNull Theme theme) {
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.TRANSPARENT);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(8), pad, activity.dpToPx(8));

        TextView message = new TextView(activity);
        message.setText(activity.getString(R.string.delete_theme_confirm, theme.getName()));
        message.setTextColor(sub);
        message.setTextSize(14f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(12));
        list.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView delete = activity.dialogStyler().makeReaderCenteredActionButton(activity.getString(R.string.delete), fg);
        TextView cancel = activity.dialogStyler().makeReaderCenteredActionButton(activity.getString(R.string.cancel), sub);
        list.addView(delete);
        list.addView(cancel);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.delete_theme), bg, fg);
        final AlertDialog[] ref = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setCustomTitle(title)
                .setView(list)
                .create();
        ref[0] = dialog;

        delete.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            activity.themeManager.deleteCustomTheme(theme.getId());
            activity.themeManager.reloadFromStorage();
            activity.applyTheme();
            ShortToast.show(activity, activity.getString(R.string.theme_deleted));
        });
        cancel.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });

        activity.dialogStyler().prepareReaderCustomThemeDialogWindowNoJump(dialog, true);
        dialog.setOnShowListener(d -> {
            activity.dialogStyler().styleReaderDialogWindow(dialog, bg, fg, sub);
            activity.dialogStyler().prepareReaderCustomThemeDialogWindowNoJump(dialog, false);
            if (dialog.getWindow() != null) {
                View decor = dialog.getWindow().getDecorView();
                decor.post(() -> decor.setAlpha(1f));
            }
        });
        dialog.show();
        activity.dialogStyler().prepareReaderCustomThemeDialogWindowNoJump(dialog, false);
    }

    void showFileInfoDialog() {
        if (activity.filePath == null) return;

        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);

        File file = new File(activity.filePath);
        String info = activity.getString(R.string.file_label) + ": " + activity.fileName
                + "\n" + activity.getString(R.string.file_info_path) + ": " + activity.filePath
                + "\n" + activity.getString(R.string.file_info_size) + ": " + FileUtils.formatFileSize(file.length())
                + "\n" + activity.getString(R.string.file_info_type) + ": " + FileUtils.getReadableFileType(activity.fileName);
        if (FileUtils.isTextFile(activity.fileName)) {
            String encodingInfo = activity.currentTextEncodingLabel;
            if (encodingInfo == null || encodingInfo.trim().isEmpty()) {
                String manualEncoding = activity.prefs != null ? activity.prefs.getManualTextEncodingForFile(file) : null;
                String normalizedManual = FileUtils.normalizeManualEncodingName(manualEncoding);
                encodingInfo = normalizedManual != null
                        ? normalizedManual + " (manual)"
                        : "auto";
            }
            // File Info must stay a pure display path. Do not run charset detection
            // here: detection can scan a 192 KiB/512 KiB sample and run every
            // legacy candidate, which caused visible freezes on large TXT files.
            info += "\n" + activity.getString(R.string.file_info_encoding) + ": " + encodingInfo;
        }
        info += "\n" + activity.getString(R.string.characters) + ": " + activity.totalChars
                + "\n" + activity.getString(R.string.lines) + ": " + activity.totalLines
                + "\n" + activity.getString(R.string.pages) + ": " + activity.getTotalPageCount();

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(activity.getString(R.string.file_info), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(activity);
        message.setText(info);
        message.setTextColor(fg);
        message.setTextSize(14f);
        message.setLineSpacing(0f, 1.15f);
        message.setPadding(activity.dpToPx(24), activity.dpToPx(12), activity.dpToPx(24), activity.dpToPx(12));
        message.setBackgroundColor(Color.TRANSPARENT);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(message);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(activity.dialogStyler().positionedActionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg),
                activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actionRow.setPadding(activity.dpToPx(30), 0, activity.dpToPx(30), 0);

        TextView ok = activity.dialogStyler().makeReaderDialogActionText(activity.getString(R.string.ok), fg,
                Gravity.CENTER);
        actionRow.addView(ok, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(54)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.85f,
                360,
                false);

        ok.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
}
