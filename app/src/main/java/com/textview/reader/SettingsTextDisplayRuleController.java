package com.textview.reader;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.textview.reader.util.TextDisplayRule;
import com.textview.reader.util.TextDisplayRuleManager;

import java.io.File;
import java.util.ArrayList;

final class SettingsTextDisplayRuleController {

    private final SettingsActivity activity;

    SettingsTextDisplayRuleController(@NonNull SettingsActivity activity) {
        this.activity = activity;
    }

    void showTextDisplayRulesDialog() {
        final ArrayList<TextDisplayRule> rules = new ArrayList<>(TextDisplayRuleManager.getRules(activity));
        final android.app.Dialog dialog = activity.createRoundedSettingsDialog();
        LinearLayout panel = activity.createRoundedSettingsDialogPanel();

        int text = activity.dialogTextColor();
        int sub = activity.dialogSubTextColor();
        int outline = activity.dialogOutlineColor();

        TextView title = activity.makeSettingsDialogTitle(activity.getString(R.string.txt_display_rules), text);
        panel.addView(title);

        TextView guide = activity.makeSettingsDialogMessage(
                activity.getString(R.string.txt_display_rules_dialog_description), sub);
        guide.setGravity(Gravity.CENTER);
        panel.addView(guide);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int listHeight = Math.max(activity.dpToPx(160),
                Math.min(activity.dpToPx(360), activity.currentVisibleWindowHeightPx() - activity.dpToPx(260)));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight);
        scrollLp.setMargins(0, 0, 0, activity.dpToPx(8));
        panel.addView(scroll, scrollLp);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> refreshRulesList(rules, list, text, sub, outline, refresh[0]);
        refresh[0].run();

        MaterialButton add = activity.makeTextRuleDialogButton(activity.getString(R.string.add), text);
        add.setOnClickListener(v -> showEditTextDisplayRuleDialog(rules, -1, () -> {
            TextDisplayRuleManager.saveRules(activity, rules);
            refresh[0].run();
        }));
        panel.addView(add);

        MaterialButton clear = activity.makeTextRuleDialogButton(activity.getString(R.string.clear_all), text);
        clear.setOnClickListener(v -> {
            if (!rules.isEmpty()) {
                showClearTextDisplayRulesConfirmDialog(() -> {
                    rules.clear();
                    TextDisplayRuleManager.saveRules(activity, rules);
                    refresh[0].run();
                });
            }
        });
        panel.addView(clear);

        MaterialButton close = activity.makeTextRuleDialogButton(activity.getString(R.string.close), text);
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close);

        activity.showRoundedSettingsDialog(dialog, panel);
    }

    private void refreshRulesList(@NonNull ArrayList<TextDisplayRule> rules,
                                  @NonNull LinearLayout list,
                                  int text,
                                  int sub,
                                  int outline,
                                  @NonNull Runnable refresh) {
        list.removeAllViews();
        if (rules.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(activity.getString(R.string.txt_display_rules_empty));
            empty.setTextColor(sub);
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, activity.dpToPx(16), 0, activity.dpToPx(16));
            list.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        for (int i = 0; i < rules.size(); i++) {
            TextDisplayRule rule = rules.get(i);
            RuleRow ruleRow = makeRuleRow(rule, text, sub, outline);
            attachRuleRowActions(ruleRow, rules, rule, i, refresh);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, activity.dpToPx(8));
            list.addView(ruleRow.row, lp);
        }
    }

    @NonNull
    private RuleRow makeRuleRow(@NonNull TextDisplayRule rule, int text, int sub, int outline) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(activity.dpToPx(12), activity.dpToPx(10), activity.dpToPx(12), activity.dpToPx(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.dialogRowBackgroundColor());
        bg.setStroke(activity.dpToPx(1), outline);
        bg.setCornerRadius(activity.dpToPx(14));
        row.setBackground(bg);

        TextView rowTitle = new TextView(activity);
        rowTitle.setText((rule.enabled ? "✓ " : "○ ")
                + safePreview(rule.findText) + " → " + safePreview(rule.replacementText));
        rowTitle.setTextColor(text);
        rowTitle.setTextSize(15f);
        rowTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(rowTitle);

        TextView meta = new TextView(activity);
        String scope = TextDisplayRule.SCOPE_FILE.equals(rule.scope)
                ? activity.getString(R.string.txt_display_rule_scope_current_file)
                : activity.getString(R.string.txt_display_rule_scope_all_txt);
        String caseMode = rule.caseSensitive
                ? activity.getString(R.string.txt_display_rule_case_sensitive)
                : activity.getString(R.string.txt_display_rule_case_insensitive);
        String mode = rule.useRegex
                ? activity.getString(R.string.txt_display_rule_regex_mode)
                : activity.getString(R.string.txt_display_rule_plain_mode);
        meta.setText(scope + " · " + caseMode + " · " + mode);
        meta.setTextColor(sub);
        meta.setTextSize(12f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, activity.dpToPx(4), 0, 0);
        row.addView(meta);

        String sourceFileLabel = makeTextDisplayRuleSourceFileLabel(rule);
        if (!sourceFileLabel.isEmpty()) {
            TextView source = new TextView(activity);
            source.setText(sourceFileLabel);
            source.setTextColor(sub);
            source.setTextSize(12f);
            source.setSingleLine(true);
            source.setEllipsize(TextUtils.TruncateAt.END);
            source.setPadding(0, activity.dpToPx(2), 0, 0);
            row.addView(source);
        }

        LinearLayout orderRow = new LinearLayout(activity);
        orderRow.setOrientation(LinearLayout.HORIZONTAL);
        orderRow.setGravity(Gravity.CENTER_VERTICAL);
        orderRow.setPadding(0, activity.dpToPx(8), 0, 0);

        TextView up = makeSmallTextButton(activity.getString(R.string.move_up));
        TextView down = makeSmallTextButton(activity.getString(R.string.move_down));
        TextView toggle = makeSmallTextButton(rule.enabled
                ? activity.getString(R.string.disable)
                : activity.getString(R.string.enable));
        TextView delete = makeSmallTextButton(activity.getString(R.string.delete));
        orderRow.addView(up, new LinearLayout.LayoutParams(0, activity.dpToPx(34), 1f));
        orderRow.addView(down, new LinearLayout.LayoutParams(0, activity.dpToPx(34), 1f));
        orderRow.addView(toggle, new LinearLayout.LayoutParams(0, activity.dpToPx(34), 1f));
        orderRow.addView(delete, new LinearLayout.LayoutParams(0, activity.dpToPx(34), 1f));
        row.addView(orderRow);
        return new RuleRow(row, up, down, toggle, delete);
    }

    private void attachRuleRowActions(@NonNull RuleRow ruleRow,
                                      @NonNull ArrayList<TextDisplayRule> rules,
                                      @NonNull TextDisplayRule rule,
                                      int index,
                                      @NonNull Runnable refresh) {
        TextView up = ruleRow.up;
        TextView down = ruleRow.down;
        TextView toggle = ruleRow.toggle;
        TextView delete = ruleRow.delete;

        ruleRow.row.setOnClickListener(v -> showEditTextDisplayRuleDialog(rules, index, () -> {
            TextDisplayRuleManager.saveRules(activity, rules);
            refresh.run();
        }));
        ruleRow.row.setOnLongClickListener(v -> {
            showEditTextDisplayRuleDialog(rules, index, () -> {
                TextDisplayRuleManager.saveRules(activity, rules);
                refresh.run();
            });
            return true;
        });
        if (up != null) {
            up.setOnClickListener(v -> {
                if (index <= 0) return;
                TextDisplayRule moved = rules.remove(index);
                rules.add(index - 1, moved);
                TextDisplayRuleManager.saveRules(activity, rules);
                refresh.run();
            });
        }
        if (down != null) {
            down.setOnClickListener(v -> {
                if (index >= rules.size() - 1) return;
                TextDisplayRule moved = rules.remove(index);
                rules.add(index + 1, moved);
                TextDisplayRuleManager.saveRules(activity, rules);
                refresh.run();
            });
        }
        if (toggle != null) {
            toggle.setOnClickListener(v -> {
                rule.enabled = !rule.enabled;
                TextDisplayRuleManager.saveRules(activity, rules);
                refresh.run();
            });
        }
        if (delete != null) {
            delete.setOnClickListener(v -> showDeleteTextDisplayRuleConfirmDialog(rule, () -> {
                if (index < 0 || index >= rules.size()) return;
                rules.remove(index);
                TextDisplayRuleManager.saveRules(activity, rules);
                refresh.run();
            }));
        }
    }

    private void showDeleteTextDisplayRuleConfirmDialog(@NonNull TextDisplayRule rule,
                                                        @NonNull Runnable onDelete) {
        final android.app.Dialog dialog = activity.createRoundedSettingsDialog();
        LinearLayout panel = activity.createRoundedSettingsDialogPanel();

        int text = activity.dialogTextColor();
        int sub = activity.dialogSubTextColor();

        panel.addView(activity.makeSettingsDialogTitle(activity.getString(R.string.delete), text));

        String preview = safePreview(rule.findText) + " → " + safePreview(rule.replacementText);
        TextView message = activity.makeSettingsDialogMessage(
                activity.getString(R.string.txt_display_rule_delete_confirm, preview), sub);
        message.setGravity(Gravity.CENTER);
        panel.addView(message);

        MaterialButton delete = activity.makeTextRuleDialogButton(activity.getString(R.string.delete), text);
        delete.setOnClickListener(v -> {
            dialog.dismiss();
            onDelete.run();
        });
        panel.addView(delete);

        MaterialButton cancel = activity.makeTextRuleDialogButton(activity.getString(R.string.cancel), text);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        activity.showRoundedSettingsDialog(dialog, panel, true, 0.58f, 220);
    }

    private void showClearTextDisplayRulesConfirmDialog(@NonNull Runnable onClear) {
        final android.app.Dialog dialog = activity.createRoundedSettingsDialog();
        LinearLayout panel = activity.createRoundedSettingsDialogPanel();

        int text = activity.dialogTextColor();
        int sub = activity.dialogSubTextColor();

        panel.addView(activity.makeSettingsDialogTitle(activity.getString(R.string.clear_all), text));
        panel.addView(activity.makeSettingsDialogMessage(activity.getString(R.string.txt_display_rules_clear_confirm), sub));

        MaterialButton clear = activity.makeTextRuleDialogButton(activity.getString(R.string.clear_all), text);
        clear.setOnClickListener(v -> {
            dialog.dismiss();
            onClear.run();
        });
        panel.addView(clear);

        MaterialButton cancel = activity.makeTextRuleDialogButton(activity.getString(R.string.cancel), text);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        activity.showRoundedSettingsDialog(dialog, panel, true);
    }

    private void showEditTextDisplayRuleDialog(ArrayList<TextDisplayRule> rules,
                                               int editIndex,
                                               Runnable onSaved) {
        TextDisplayRule editing = editIndex >= 0 && editIndex < rules.size()
                ? rules.get(editIndex)
                : new TextDisplayRule();
        final android.app.Dialog dialog = activity.createRoundedSettingsDialog();
        LinearLayout panel = activity.createRoundedSettingsDialogPanel();

        int text = activity.dialogTextColor();
        int sub = activity.dialogSubTextColor();
        int outline = activity.dialogOutlineColor();

        TextView title = activity.makeSettingsDialogTitle(
                activity.getString(editIndex >= 0 ? R.string.edit : R.string.add), text);
        panel.addView(title);

        EditText findInput = new EditText(activity);
        findInput.setHint(R.string.txt_display_rule_find_hint);
        findInput.setSingleLine(true);
        findInput.setText(editing.findText);
        styleSettingsEditText(findInput, text, sub, outline);
        panel.addView(findInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dpToPx(52)));

        EditText replaceInput = new EditText(activity);
        replaceInput.setHint(R.string.txt_display_rule_replace_hint);
        replaceInput.setSingleLine(true);
        replaceInput.setText(editing.replacementText);
        styleSettingsEditText(replaceInput, text, sub, outline);
        LinearLayout.LayoutParams replaceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dpToPx(52));
        replaceLp.setMargins(0, activity.dpToPx(8), 0, 0);
        panel.addView(replaceInput, replaceLp);

        LinearLayout optionGroup = new LinearLayout(activity);
        optionGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams optionGroupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optionGroupLp.setMargins(0, activity.dpToPx(12), 0, activity.dpToPx(12));

        CheckBox enabledBox = makeSettingsCheckBox(activity.getString(R.string.enabled), text);
        enabledBox.setChecked(editing.enabled);
        optionGroup.addView(enabledBox);

        CheckBox caseBox = makeSettingsCheckBox(activity.getString(R.string.txt_display_rule_case_sensitive), text);
        caseBox.setChecked(editing.caseSensitive);
        optionGroup.addView(caseBox);

        CheckBox regexBox = makeSettingsCheckBox(activity.getString(R.string.txt_display_rule_use_regex), text);
        regexBox.setChecked(editing.useRegex);
        optionGroup.addView(regexBox);

        CheckBox fileOnlyBox = makeSettingsCheckBox(
                activity.getString(R.string.txt_display_rule_current_file_only), text);
        fileOnlyBox.setChecked(TextDisplayRule.SCOPE_FILE.equals(editing.scope));
        fileOnlyBox.setEnabled(activity.currentTxtFilePath != null && !activity.currentTxtFilePath.isEmpty());
        optionGroup.addView(fileOnlyBox);

        panel.addView(optionGroup, optionGroupLp);

        TextView warning = activity.makeSettingsDialogMessage(
                activity.getString(R.string.txt_display_rule_length_warning), sub);
        warning.setGravity(Gravity.START);
        panel.addView(warning);

        MaterialButton save = activity.makeTextRuleDialogButton(activity.getString(R.string.save), text);
        save.setOnClickListener(v -> saveEditedRule(
                dialog,
                rules,
                editIndex,
                editing,
                findInput,
                replaceInput,
                enabledBox,
                caseBox,
                regexBox,
                fileOnlyBox,
                onSaved));
        panel.addView(save);

        MaterialButton cancel = activity.makeTextRuleDialogButton(activity.getString(R.string.cancel), text);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel);

        activity.showRoundedSettingsDialog(dialog, panel);
    }

    private void saveEditedRule(@NonNull android.app.Dialog dialog,
                                @NonNull ArrayList<TextDisplayRule> rules,
                                int editIndex,
                                @NonNull TextDisplayRule editing,
                                @NonNull EditText findInput,
                                @NonNull EditText replaceInput,
                                @NonNull CheckBox enabledBox,
                                @NonNull CheckBox caseBox,
                                @NonNull CheckBox regexBox,
                                @NonNull CheckBox fileOnlyBox,
                                Runnable onSaved) {
        String find = findInput.getText() != null ? findInput.getText().toString() : "";
        if (find.isEmpty()) {
            ShortToast.show(activity, R.string.txt_display_rule_find_required);
            return;
        }
        String oldScope = editIndex >= 0 ? editing.scope : TextDisplayRule.SCOPE_ALL_TXT;
        String oldFilePath = editIndex >= 0 && editing.filePath != null ? editing.filePath : "";

        editing.findText = find;
        editing.replacementText = replaceInput.getText() != null ? replaceInput.getText().toString() : "";
        editing.enabled = enabledBox.isChecked();
        editing.caseSensitive = caseBox.isChecked();
        editing.useRegex = regexBox.isChecked();
        if ((editing.sourceFilePath == null || editing.sourceFilePath.isEmpty())
                && activity.currentTxtFilePath != null
                && !activity.currentTxtFilePath.isEmpty()) {
            editing.sourceFilePath = activity.currentTxtFilePath;
        }
        if (fileOnlyBox.isChecked()
                && activity.currentTxtFilePath != null
                && !activity.currentTxtFilePath.isEmpty()) {
            editing.scope = TextDisplayRule.SCOPE_FILE;
            if (editIndex >= 0
                    && TextDisplayRule.SCOPE_FILE.equals(oldScope)
                    && oldFilePath != null
                    && !oldFilePath.isEmpty()) {
                editing.filePath = oldFilePath;
            } else {
                editing.filePath = activity.currentTxtFilePath;
            }
        } else {
            editing.scope = TextDisplayRule.SCOPE_ALL_TXT;
            editing.filePath = "";
        }
        if (editIndex < 0) rules.add(editing);
        if (onSaved != null) onSaved.run();
        dialog.dismiss();
    }

    private void styleSettingsEditText(@NonNull EditText input, int text, int hint, int outline) {
        input.setTextColor(text);
        input.setHintTextColor(hint);
        input.setTextSize(15f);
        input.setSingleLine(true);
        input.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.dialogRowBackgroundColor());
        bg.setCornerRadius(activity.dpToPx(12));
        bg.setStroke(activity.dpToPx(1), outline);
        input.setBackground(bg);
    }

    private CheckBox makeSettingsCheckBox(String label, int text) {
        CheckBox box = new CheckBox(activity);
        box.setText(label);
        box.setTextColor(text);
        box.setTextSize(14f);
        box.setButtonTintList(ColorStateList.valueOf(text));
        return box;
    }

    private TextView makeSmallTextButton(String text) {
        MaterialButton button = new MaterialButton(activity);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(activity.dialogTextColor());
        button.setTextSize(12f);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setIncludeFontPadding(false);
        button.setSingleLine(true);
        button.setPadding(activity.dpToPx(4), 0, activity.dpToPx(4), 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setCornerRadius(activity.dpToPx(10));
        button.setStrokeWidth(0);
        button.setStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setElevation(0f);
        button.setTranslationZ(0f);
        button.setStateListAnimator(null);
        button.setRippleColor(ColorStateList.valueOf(Color.TRANSPARENT));
        return button;
    }

    private String makeTextDisplayRuleSourceFileLabel(@NonNull TextDisplayRule rule) {
        String sourcePath = rule.sourceFilePath;
        if ((sourcePath == null || sourcePath.isEmpty()) && rule.filePath != null && !rule.filePath.isEmpty()) {
            sourcePath = rule.filePath;
        }
        if (sourcePath == null || sourcePath.isEmpty()) return "";
        String fileName = new File(sourcePath).getName();
        if (fileName == null || fileName.isEmpty()) fileName = sourcePath;
        return activity.getString(R.string.txt_display_rule_source_file, fileName);
    }

    private String safePreview(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 32 ? oneLine.substring(0, 32) + "…" : oneLine;
    }

    private static final class RuleRow {
        final LinearLayout row;
        final TextView up;
        final TextView down;
        final TextView toggle;
        final TextView delete;

        RuleRow(@NonNull LinearLayout row,
                @NonNull TextView up,
                @NonNull TextView down,
                @NonNull TextView toggle,
                @NonNull TextView delete) {
            this.row = row;
            this.up = up;
            this.down = down;
            this.toggle = toggle;
            this.delete = delete;
        }
    }
}
