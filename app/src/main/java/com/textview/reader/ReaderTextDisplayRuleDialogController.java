package com.textview.reader;

import android.graphics.Color;
import android.widget.ScrollView;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.textview.reader.util.TextDisplayRule;
import com.textview.reader.util.TextDisplayRuleManager;

import java.io.File;
import java.util.ArrayList;

final class ReaderTextDisplayRuleDialogController {
    private static final int TXT_TOOLBAR_POPUP_Y_DP = 74;

    private final ReaderActivity activity;

    ReaderTextDisplayRuleDialogController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    private android.content.Context getApplicationContext() { return activity.getApplicationContext(); }
    private String getString(int resId) { return activity.getString(resId); }
    private String getString(int resId, Object... args) { return activity.getString(resId, args); }
    private void syncReaderDialogThemeSnapshot() { activity.dialogStyler().syncReaderDialogThemeSnapshot(); }
    private int readerDialogBgColor() { return activity.dialogStyler().readerDialogBgColor(); }
    private int readerDialogTextColor(int bg) { return activity.dialogStyler().readerDialogTextColor(bg); }
    private int readerDialogSubTextColor(int bg) { return activity.dialogStyler().readerDialogSubTextColor(bg); }
    private TextView makeReaderDialogTitle(String text, int bg, int fg) { return activity.dialogStyler().makeReaderDialogTitle(text, bg, fg); }
    private EditText makeReaderDialogEditText(String hint, int bg, int fg, int sub) { return activity.dialogStyler().makeReaderDialogEditText(hint, bg, fg, sub); }
    private TextView makeReaderCenteredActionButton(String text, int fg) { return activity.dialogStyler().makeReaderCenteredActionButton(text, fg); }
    private android.graphics.drawable.Drawable positionedActionPanelBackground(int fill, int line) { return activity.dialogStyler().positionedActionPanelBackground(fill, line); }
    private int dialogActionPanelFillColor(int bg) { return activity.dialogStyler().dialogActionPanelFillColor(bg); }
    private int dialogActionPanelLineColor(int bg) { return activity.dialogStyler().dialogActionPanelLineColor(bg); }
    private TextView makeReaderDialogActionText(String label, int textColor, int gravity) { return activity.dialogStyler().makeReaderDialogActionText(label, textColor, gravity); }
    private android.app.Dialog createNarrowPositionedReaderDialog(@NonNull View content, int bgColor, int gravity, int yDp, float widthFraction, int maxWidthDp, boolean adjustResize) {
        return activity.dialogStyler().createNarrowPositionedReaderDialog(content, bgColor, gravity, yDp, widthFraction, maxWidthDp, adjustResize);
    }
    private String currentTextDisplayRuleSignature() { return activity.currentTextDisplayRuleSignature(); }
    private void requestTextDisplayRuleContentRefreshOnWindowClose() { activity.requestTextDisplayRuleContentRefreshOnWindowClose(); }
    private void acknowledgeTextDisplayRuleWindowNoContentChange() { activity.acknowledgeTextDisplayRuleWindowNoContentChange(); }
    private void applyPendingTextDisplayRuleWindowRefresh() { activity.applyPendingTextDisplayRuleWindowRefresh(); }
    private boolean sameTextDisplayRuleValue(String a, String b) { return activity.sameTextDisplayRuleValue(a, b); }
    private boolean isLightColor(int color) { return activity.isLightColor(color); }
    private int blendColors(int bottomColor, int topColor, float topAlpha) { return activity.dialogStyler().blendColors(bottomColor, topColor, topAlpha); }
    private int dpToPx(int dp) { return activity.dpToPx(dp); }
    private int dpToPx(float dp) { return activity.dpToPx(dp); }
    private int currentVisibleWindowHeightPx() { return activity.dialogStyler().currentVisibleWindowHeightPx(); }
    void showQuickTextDisplayRuleDialog(String prefillFind, boolean defaultCurrentFileOnly) {
        showReaderTextDisplayRuleEditDialog(prefillFind, defaultCurrentFileOnly, -1);
    }

    void showReaderTextDisplayRuleEditDialog(String prefillFind, boolean defaultCurrentFileOnly, int editIndex) {
        showReaderTextDisplayRuleEditDialog(prefillFind, defaultCurrentFileOnly, editIndex, null);
    }

    void showReaderTextDisplayRuleEditDialog(String prefillFind, boolean defaultCurrentFileOnly, int editIndex, @Nullable Runnable onSaved) {
        syncReaderDialogThemeSnapshot();
        final int bg = readerDialogBgColor();
        final int fg = readerDialogTextColor(bg);
        final int sub = readerDialogSubTextColor(bg);
        ArrayList<TextDisplayRule> rules = new ArrayList<>(TextDisplayRuleManager.getRules(getApplicationContext()));
        TextDisplayRule editingRule = editIndex >= 0 && editIndex < rules.size() ? rules.get(editIndex) : null;
        boolean editingExistingRule = editingRule != null;

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        TextView title = makeReaderDialogTitle(
                editingExistingRule ? getString(R.string.edit) : getString(R.string.txt_display_rule_quick_add),
                bg,
                fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        box.setPadding(pad, dpToPx(2), pad, dpToPx(10));
        box.setBackgroundColor(Color.TRANSPARENT);

        TextView tip = new TextView(activity);
        tip.setText(R.string.txt_display_rule_quick_tip);
        tip.setTextSize(12f);
        tip.setTextColor(sub);
        tip.setLineSpacing(0, 1.08f);
        tip.setPadding(0, 0, 0, dpToPx(8));
        box.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText findInput = makeReaderDialogEditText(getString(R.string.txt_display_rule_find_hint), bg, fg, sub);
        findInput.setSingleLine(true);
        findInput.setTextSize(14f);
        findInput.setPadding(dpToPx(12), 0, dpToPx(12), 0);
        findInput.setText(editingExistingRule ? editingRule.findText : (prefillFind != null ? prefillFind : ""));
        findInput.setSelectAllOnFocus(true);
        box.addView(findInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(46)));

        EditText replaceInput = makeReaderDialogEditText(getString(R.string.txt_display_rule_replace_hint), bg, fg, sub);
        replaceInput.setSingleLine(true);
        replaceInput.setTextSize(14f);
        replaceInput.setPadding(dpToPx(12), 0, dpToPx(12), 0);
        if (editingExistingRule) replaceInput.setText(editingRule.replacementText);
        LinearLayout.LayoutParams replaceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(46));
        replaceLp.setMargins(0, dpToPx(6), 0, 0);
        box.addView(replaceInput, replaceLp);

        CompoundButton enabledBox = new SwitchCompat(activity);
        enabledBox.setText(R.string.enabled);
        enabledBox.setChecked(editingExistingRule ? editingRule.enabled : true);
        styleQuickTextDisplayRuleOption(enabledBox, fg);
        box.addView((View) enabledBox);

        CompoundButton caseBox = new SwitchCompat(activity);
        caseBox.setText(R.string.txt_display_rule_case_sensitive);
        caseBox.setChecked(editingExistingRule && editingRule.caseSensitive);
        styleQuickTextDisplayRuleOption(caseBox, fg);
        box.addView((View) caseBox);

        CompoundButton regexBox = new SwitchCompat(activity);
        regexBox.setText(R.string.txt_display_rule_use_regex);
        regexBox.setChecked(editingExistingRule && editingRule.useRegex);
        styleQuickTextDisplayRuleOption(regexBox, fg);
        box.addView((View) regexBox);

        CompoundButton fileOnlyBox = new SwitchCompat(activity);
        fileOnlyBox.setText(R.string.txt_display_rule_current_file_only);
        fileOnlyBox.setChecked(editingExistingRule
                ? TextDisplayRule.SCOPE_FILE.equals(editingRule.scope)
                : (defaultCurrentFileOnly && activity.filePath != null && !activity.filePath.isEmpty()));
        fileOnlyBox.setEnabled(activity.filePath != null && !activity.filePath.isEmpty());
        styleQuickTextDisplayRuleOption(fileOnlyBox, fg);
        box.addView((View) fileOnlyBox);

        TextView manageButton = makeReaderCenteredActionButton(getString(R.string.txt_display_rule_manage), fg);
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48));
        manageLp.setMargins(0, dpToPx(10), 0, 0);
        box.addView(manageButton, manageLp);

        panel.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(positionedActionPanelBackground(
                dialogActionPanelFillColor(bg),
                dialogActionPanelLineColor(bg)));
        actionRow.setPadding(dpToPx(8), 0, dpToPx(8), 0);

        TextView cancelButton = makeReaderDialogActionText(getString(R.string.cancel), sub, Gravity.CENTER);
        TextView saveButton = makeReaderDialogActionText(getString(R.string.save), fg, Gravity.CENTER);
        actionRow.addView(saveButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        actionRow.addView(cancelButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(54)));

        android.app.Dialog dialog = createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.80f,
                420,
                true);

        manageButton.setOnClickListener(v -> {
            dialog.dismiss();
            showReaderTextDisplayRulesManagerDialog();
        });
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        saveButton.setOnClickListener(v -> {
            String find = findInput.getText() != null ? findInput.getText().toString() : "";
            if (find.trim().isEmpty()) {
                ShortToast.show(activity, R.string.txt_display_rule_find_required);
                return;
            }
            String beforeSignature = currentTextDisplayRuleSignature();
            boolean oldEnabled = editingExistingRule && editingRule.enabled;
            String oldFindText = editingExistingRule ? editingRule.findText : "";
            String oldReplacementText = editingExistingRule ? editingRule.replacementText : "";
            boolean oldCaseSensitive = editingExistingRule && editingRule.caseSensitive;
            boolean oldUseRegex = editingExistingRule && editingRule.useRegex;
            String oldScope = editingExistingRule ? editingRule.scope : TextDisplayRule.SCOPE_ALL_TXT;
            String oldFilePath = editingExistingRule ? (editingRule.filePath != null ? editingRule.filePath : "") : "";
            boolean oldAppliesToCurrentFile = editingExistingRule && editingRule.appliesTo(activity.filePath);

            TextDisplayRule rule = editingExistingRule ? editingRule : new TextDisplayRule();
            rule.enabled = enabledBox.isChecked();
            rule.findText = find;
            rule.replacementText = replaceInput.getText() != null ? replaceInput.getText().toString() : "";
            rule.caseSensitive = caseBox.isChecked();
            rule.useRegex = regexBox.isChecked();
            if ((rule.sourceFilePath == null || rule.sourceFilePath.isEmpty()) && activity.filePath != null && !activity.filePath.isEmpty()) {
                rule.sourceFilePath = activity.filePath;
            }
            if (fileOnlyBox.isChecked() && activity.filePath != null && !activity.filePath.isEmpty()) {
                rule.scope = TextDisplayRule.SCOPE_FILE;
                if (editingExistingRule && TextDisplayRule.SCOPE_FILE.equals(oldScope) && !oldFilePath.isEmpty()) {
                    // Editing a rule that was made for another TXT file must keep that original
                    // file binding. Otherwise merely opening the rule editor from activity viewer
                    // would silently retarget the rule to the currently opened file and cause
                    // an unnecessary TXT reload.
                    rule.filePath = oldFilePath;
                } else {
                    rule.filePath = activity.filePath;
                }
            } else {
                rule.scope = TextDisplayRule.SCOPE_ALL_TXT;
                rule.filePath = "";
            }

            boolean newAppliesToCurrentFile = rule.appliesTo(activity.filePath);
            boolean enabledChanged = editingExistingRule && oldEnabled != rule.enabled;
            boolean textOrModeChanged = editingExistingRule
                    && (!sameTextDisplayRuleValue(oldFindText, rule.findText)
                    || !sameTextDisplayRuleValue(oldReplacementText, rule.replacementText)
                    || oldCaseSensitive != rule.caseSensitive
                    || oldUseRegex != rule.useRegex);
            boolean shouldRefreshTxtContent = editingExistingRule
                    ? ((enabledChanged || textOrModeChanged) && (oldAppliesToCurrentFile || newAppliesToCurrentFile))
                    : newAppliesToCurrentFile;

            if (editingExistingRule && editIndex >= 0 && editIndex < rules.size()) {
                rules.set(editIndex, rule);
            } else {
                rules.add(rule);
            }
            TextDisplayRuleManager.saveRules(getApplicationContext(), rules);
            String afterSignature = currentTextDisplayRuleSignature();
            dialog.dismiss();
            if (onSaved != null) activity.handler.post(onSaved);
            ShortToast.show(activity, editingExistingRule ? R.string.txt_display_rule_saved : R.string.txt_display_rule_added);
            if (!beforeSignature.equals(afterSignature)) {
                if (shouldRefreshTxtContent) {
                    requestTextDisplayRuleContentRefreshOnWindowClose();
                    if (onSaved == null) {
                        // Direct quick-add/edit dialog: apply after activity dialog has closed.
                        // When opened from the rule manager, defer until the manager closes.
                        activity.handler.post(activity::applyPendingTextDisplayRuleWindowRefresh);
                    }
                } else {
                    // Scope-only changes such as All files <-> Current file only should update
                    // the rule list immediately, but they should not reload the opened TXT.
                    acknowledgeTextDisplayRuleWindowNoContentChange();
                }
            }
        });
        dialog.show();
    }

    void styleQuickTextDisplayRuleOption(CompoundButton option, int textColor) {
        option.setTextColor(textColor);
        option.setTextSize(14f);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinHeight(dpToPx(38));
        option.setPadding(dpToPx(12), 0, dpToPx(8), 0);
        option.setCompoundDrawablePadding(dpToPx(8));
    }

    void showReaderTextDisplayRulesManagerDialog() {
        syncReaderDialogThemeSnapshot();
        final int bg = readerDialogBgColor();
        final int fg = readerDialogTextColor(bg);
        final int sub = readerDialogSubTextColor(bg);
        final int outline = blendColors(bg, fg, isLightColor(bg) ? 0.12f : 0.18f);
        final ArrayList<TextDisplayRule> rules = new ArrayList<>(TextDisplayRuleManager.getRules(getApplicationContext()));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        TextView title = makeReaderDialogTitle(getString(R.string.txt_display_rules), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dpToPx(14), dpToPx(4), dpToPx(14), dpToPx(8));
        body.setBackgroundColor(Color.TRANSPARENT);

        TextView guide = new TextView(activity);
        guide.setText(R.string.txt_display_rules_dialog_description);
        guide.setTextColor(sub);
        guide.setTextSize(12f);
        guide.setGravity(Gravity.CENTER);
        guide.setLineSpacing(0, 1.08f);
        guide.setPadding(0, 0, 0, dpToPx(8));
        body.addView(guide, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(dpToPx(150), Math.min(dpToPx(300), currentVisibleWindowHeightPx() - dpToPx(320)))));

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            if (rules.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText(R.string.txt_display_rules_empty);
                empty.setTextColor(sub);
                empty.setTextSize(14f);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dpToPx(20), 0, dpToPx(20));
                list.addView(empty, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                return;
            }
            for (int i = 0; i < rules.size(); i++) {
                TextDisplayRule rule = rules.get(i);
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setColor(textDisplayRuleCardBackgroundColor(bg, fg));
                rowBg.setStroke(dpToPx(1), outline);
                rowBg.setCornerRadius(dpToPx(12));
                row.setBackground(rowBg);

                TextView name = new TextView(activity);
                name.setText((rule.enabled ? "✓ " : "○ ")
                        + safeRulePreview(rule.findText) + " → " + safeRulePreview(rule.replacementText));
                name.setTextColor(fg);
                name.setTextSize(14f);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                String scope = TextDisplayRule.SCOPE_FILE.equals(rule.scope)
                        ? getString(R.string.txt_display_rule_scope_current_file)
                        : getString(R.string.txt_display_rule_scope_all_txt);
                String caseMode = rule.caseSensitive
                        ? getString(R.string.txt_display_rule_case_sensitive)
                        : getString(R.string.txt_display_rule_case_insensitive);
                String mode = rule.useRegex
                        ? getString(R.string.txt_display_rule_regex_mode)
                        : getString(R.string.txt_display_rule_plain_mode);
                TextView meta = new TextView(activity);
                meta.setText(scope + " · " + caseMode + " · " + mode);
                meta.setTextColor(sub);
                meta.setTextSize(11f);
                meta.setSingleLine(true);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                meta.setPadding(0, dpToPx(3), 0, 0);
                row.addView(meta, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                String sourceFileLabel = makeTextDisplayRuleSourceFileLabel(rule);
                if (!sourceFileLabel.isEmpty()) {
                    TextView source = new TextView(activity);
                    source.setText(sourceFileLabel);
                    source.setTextColor(sub);
                    source.setTextSize(11f);
                    source.setSingleLine(true);
                    source.setEllipsize(TextUtils.TruncateAt.END);
                    source.setPadding(0, dpToPx(2), 0, 0);
                    row.addView(source, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                }

                LinearLayout controls = new LinearLayout(activity);
                controls.setOrientation(LinearLayout.HORIZONTAL);
                controls.setGravity(Gravity.CENTER_VERTICAL);
                controls.setPadding(0, dpToPx(6), 0, 0);
                TextView up = makeReaderMiniTextButton(getString(R.string.move_up), fg, outline, bg);
                TextView down = makeReaderMiniTextButton(getString(R.string.move_down), fg, outline, bg);
                TextView toggle = makeReaderMiniTextButton(rule.enabled ? getString(R.string.disable) : getString(R.string.enable), fg, outline, bg);
                TextView delete = makeReaderMiniTextButton(getString(R.string.delete), fg, outline, bg);
                controls.addView(up, new LinearLayout.LayoutParams(0, dpToPx(32), 1f));
                controls.addView(down, new LinearLayout.LayoutParams(0, dpToPx(32), 1f));
                controls.addView(toggle, new LinearLayout.LayoutParams(0, dpToPx(32), 1f));
                controls.addView(delete, new LinearLayout.LayoutParams(0, dpToPx(32), 1f));
                row.addView(controls, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                final int index = i;
                up.setOnClickListener(v -> {
                    if (index <= 0) return;
                    TextDisplayRule moved = rules.remove(index);
                    rules.add(index - 1, moved);
                    TextDisplayRuleManager.saveRules(getApplicationContext(), rules);
                    refresh[0].run();
                    // Reordering only changes display priority in the manager. Do not reload TXT.
                    acknowledgeTextDisplayRuleWindowNoContentChange();
                });
                down.setOnClickListener(v -> {
                    if (index >= rules.size() - 1) return;
                    TextDisplayRule moved = rules.remove(index);
                    rules.add(index + 1, moved);
                    TextDisplayRuleManager.saveRules(getApplicationContext(), rules);
                    refresh[0].run();
                    // Reordering only changes display priority in the manager. Do not reload TXT.
                    acknowledgeTextDisplayRuleWindowNoContentChange();
                });
                toggle.setOnClickListener(v -> {
                    String beforeSignature = currentTextDisplayRuleSignature();
                    rule.enabled = !rule.enabled;
                    TextDisplayRuleManager.saveRules(getApplicationContext(), rules);
                    refresh[0].run();
                    if (!beforeSignature.equals(currentTextDisplayRuleSignature())) {
                        requestTextDisplayRuleContentRefreshOnWindowClose();
                    } else {
                        acknowledgeTextDisplayRuleWindowNoContentChange();
                    }
                });
                delete.setOnClickListener(v -> showReaderDeleteTextDisplayRuleConfirmDialog(rule, () -> {
                    if (index < 0 || index >= rules.size()) return;
                    String beforeSignature = currentTextDisplayRuleSignature();
                    rules.remove(index);
                    TextDisplayRuleManager.saveRules(getApplicationContext(), rules);
                    refresh[0].run();
                    if (!beforeSignature.equals(currentTextDisplayRuleSignature())) {
                        requestTextDisplayRuleContentRefreshOnWindowClose();
                    } else {
                        acknowledgeTextDisplayRuleWindowNoContentChange();
                    }
                }));
                row.setOnLongClickListener(v -> {
                    showReaderTextDisplayRuleEditDialog("", true, index, () -> {
                        rules.clear();
                        rules.addAll(TextDisplayRuleManager.getRules(getApplicationContext()));
                        refresh[0].run();
                    });
                    return true;
                });

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dpToPx(8));
                list.addView(row, rowLp);
            }
        };
        refresh[0].run();

        panel.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(positionedActionPanelBackground(
                dialogActionPanelFillColor(bg),
                dialogActionPanelLineColor(bg)));
        actionRow.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        TextView addButton = makeReaderDialogActionText(getString(R.string.add), fg, Gravity.CENTER);
        TextView closeButton = makeReaderDialogActionText(getString(R.string.close), sub, Gravity.CENTER);
        actionRow.addView(addButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        actionRow.addView(closeButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(54)));

        android.app.Dialog dialog = createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP,
                0.80f,
                420,
                true);
        addButton.setOnClickListener(v -> showReaderTextDisplayRuleEditDialog("", true, -1, () -> {
            rules.clear();
            rules.addAll(TextDisplayRuleManager.getRules(getApplicationContext()));
            refresh[0].run();
        }));
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> applyPendingTextDisplayRuleWindowRefresh());
        dialog.show();
    }

    void showReaderDeleteTextDisplayRuleConfirmDialog(@NonNull TextDisplayRule rule, @NonNull Runnable onDelete) {
        syncReaderDialogThemeSnapshot();
        final int bg = readerDialogBgColor();
        final int fg = readerDialogTextColor(bg);
        final int sub = readerDialogSubTextColor(bg);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        TextView title = makeReaderDialogTitle(getString(R.string.delete), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(10));
        body.setBackgroundColor(Color.TRANSPARENT);

        TextView message = new TextView(activity);
        String preview = safeRulePreview(rule.findText) + " → " + safeRulePreview(rule.replacementText);
        message.setText(getString(R.string.txt_display_rule_delete_confirm, preview));
        message.setTextColor(sub);
        message.setTextSize(13f);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(0, 1.08f);
        body.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        panel.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(positionedActionPanelBackground(
                dialogActionPanelFillColor(bg),
                dialogActionPanelLineColor(bg)));
        actionRow.setPadding(dpToPx(8), 0, dpToPx(8), 0);

        TextView cancelButton = makeReaderDialogActionText(getString(R.string.cancel), sub, Gravity.CENTER);
        TextView deleteButton = makeReaderDialogActionText(getString(R.string.delete), fg, Gravity.CENTER);
        actionRow.addView(cancelButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        actionRow.addView(deleteButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(54)));

        android.app.Dialog dialog = createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                TXT_TOOLBAR_POPUP_Y_DP + 104,
                0.72f,
                340,
                true);
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        deleteButton.setOnClickListener(v -> {
            dialog.dismiss();
            onDelete.run();
        });
        dialog.show();
    }

    int textDisplayRuleCardBackgroundColor(int bg, int fg) {
        // TXT viewer rule cards must follow the active reading theme, not the
        // main/UI theme.  Use the actual reading background/text pair as the
        // tonal anchor, then nudge the card slightly darker for light reading
        // themes or slightly lighter for dark reading themes.
        syncReaderDialogThemeSnapshot();
        int readingBg = activity.currentReaderBackgroundColor;
        int readingFg = activity.currentReaderTextColor;
        float mix = isLightColor(readingBg) ? 0.045f : 0.050f;
        int blended = blendColors(readingBg, readingFg, mix);
        return Color.rgb(Color.red(blended), Color.green(blended), Color.blue(blended));
    }

    TextView makeReaderMiniTextButton(String label, int fg, int outline, int bg) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(fg);
        button.setTextSize(11f);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dpToPx(3), 0, dpToPx(3), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    String makeTextDisplayRuleSourceFileLabel(@NonNull TextDisplayRule rule) {
        String sourcePath = rule.sourceFilePath;
        if ((sourcePath == null || sourcePath.isEmpty()) && rule.filePath != null && !rule.filePath.isEmpty()) {
            sourcePath = rule.filePath;
        }
        if (sourcePath == null || sourcePath.isEmpty()) return "";
        String fileName = new File(sourcePath).getName();
        if (fileName == null || fileName.isEmpty()) fileName = sourcePath;
        return getString(R.string.txt_display_rule_source_file, fileName);
    }

    String safeRulePreview(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 24 ? oneLine.substring(0, 24) + "…" : oneLine;
    }
}
