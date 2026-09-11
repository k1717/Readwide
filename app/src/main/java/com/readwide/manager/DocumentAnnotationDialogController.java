package com.readwide.manager;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.readwide.manager.model.DocumentAnnotation;
import com.readwide.manager.util.DocumentAnnotationManager;

import java.util.List;
import java.util.Locale;

/** Shared note/highlight editor and per-file annotation list for TXT and Markdown. */
final class DocumentAnnotationDialogController {
    interface Navigator {
        void open(@NonNull DocumentAnnotation annotation);
        void annotationsChanged();
    }

    private final Activity activity;
    private final DocumentAnnotationManager manager;
    private final String filePath;
    private final int background;
    private final int foreground;
    private final Navigator navigator;
    private Dialog activeListDialog;

    DocumentAnnotationDialogController(@NonNull Activity activity,
                                       @NonNull DocumentAnnotationManager manager,
                                       String filePath,
                                       int background,
                                       int foreground,
                                       @NonNull Navigator navigator) {
        this.activity = activity;
        this.manager = manager;
        this.filePath = filePath != null ? filePath : "";
        this.background = background;
        this.foreground = foreground;
        this.navigator = navigator;
    }

    void showList() {
        dismissActiveList();
        List<DocumentAnnotation> annotations = manager.getForFile(filePath);
        LinearLayout box = dialogBox();
        box.addView(title(activity.getString(R.string.annotations_title)));

        TextView hint = text(activity.getString(R.string.annotation_list_hint), 13f, false);
        hint.setAlpha(0.72f);
        hint.setPadding(0, 0, 0, dp(8));
        box.addView(hint);

        if (annotations.isEmpty()) {
            TextView empty = text(activity.getString(R.string.annotation_empty), 15f, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(30), dp(8), dp(30));
            box.addView(empty);
        } else {
            LinearLayout rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            for (DocumentAnnotation annotation : annotations) {
                TextView row = text(summary(annotation), 15f, false);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackground(roundedBackground(withAlpha(foreground, 18), dp(10),
                        withAlpha(foreground, 42)));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 0, 0, dp(6));
                rows.addView(row, rowParams);
                row.setOnClickListener(v -> {
                    dismissActiveList();
                    navigator.open(annotation);
                });
                row.setOnLongClickListener(v -> {
                    showRowActions(annotation);
                    return true;
                });
            }
            box.addView(rows, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        final Dialog[] ref = new Dialog[1];
        addBottomActions(box, null, null, activity.getString(R.string.close),
                () -> ref[0].dismiss());
        Dialog dialog = createDialog(box, false);
        ref[0] = dialog;
        activeListDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (activeListDialog == dialog) activeListDialog = null;
        });
        dialog.show();
    }

    void showNoteEditor(@NonNull DocumentAnnotation annotation, boolean isNew) {
        LinearLayout box = dialogBox();
        box.addView(title(activity.getString(isNew
                ? R.string.annotation_add_note : R.string.annotation_edit_note)));

        TextView quote = text(quotedPreview(annotation.getSelectedText()), 14f, false);
        quote.setAlpha(0.76f);
        quote.setPadding(0, 0, 0, dp(10));
        box.addView(quote);

        EditText input = new EditText(activity);
        input.setText(annotation.getNote());
        input.setHint(R.string.annotation_note_hint);
        input.setTextColor(foreground);
        input.setHintTextColor(withAlpha(foreground, 150));
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(roundedBackground(withAlpha(foreground, 14), dp(9),
                withAlpha(foreground, 64)));
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final Dialog[] ref = new Dialog[1];
        addBottomActions(box,
                activity.getString(R.string.cancel), () -> ref[0].dismiss(),
                activity.getString(R.string.save), () -> {
                    String note = input.getText() != null ? input.getText().toString().trim() : "";
                    if (note.isEmpty()) {
                        input.setError(activity.getString(R.string.annotation_note_required));
                        return;
                    }
                    annotation.setNote(note);
                    boolean stored = !isNew || manager.add(annotation);
                    if (!isNew) manager.update(annotation);
                    navigator.annotationsChanged();
                    ShortToast.show(activity, stored
                            ? R.string.annotation_saved : R.string.annotation_already_saved);
                    ref[0].dismiss();
                    refreshVisibleList();
                });
        Dialog dialog = createDialog(box, true);
        ref[0] = dialog;
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
        });
        dialog.show();
    }

    private void showRowActions(DocumentAnnotation annotation) {
        LinearLayout box = dialogBox();
        TextView heading = title(summary(annotation));
        heading.setTextSize(17f);
        heading.setMaxLines(4);
        box.addView(heading);
        final Dialog[] ref = new Dialog[1];
        box.addView(actionRow(activity.getString(R.string.open), () -> {
            ref[0].dismiss();
            dismissActiveList();
            navigator.open(annotation);
        }));
        box.addView(actionRow(activity.getString(R.string.annotation_edit_note), () -> {
            ref[0].dismiss();
            showNoteEditor(annotation, false);
        }));
        box.addView(actionRow(activity.getString(R.string.delete), () -> {
            ref[0].dismiss();
            confirmDelete(annotation);
        }));
        addBottomActions(box, null, null, activity.getString(R.string.cancel),
                () -> ref[0].dismiss());
        ref[0] = createDialog(box, false);
        ref[0].show();
    }

    private void confirmDelete(DocumentAnnotation annotation) {
        LinearLayout box = dialogBox();
        box.addView(title(activity.getString(R.string.annotation_delete_title)));
        TextView message = text(activity.getString(R.string.annotation_delete_message), 15f, false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(6), dp(4), dp(6), dp(10));
        box.addView(message);
        final Dialog[] ref = new Dialog[1];
        addBottomActions(box,
                activity.getString(R.string.cancel), () -> ref[0].dismiss(),
                activity.getString(R.string.delete), () -> {
                    manager.delete(annotation.getId());
                    navigator.annotationsChanged();
                    ShortToast.show(activity, R.string.annotation_deleted);
                    ref[0].dismiss();
                    refreshVisibleList();
                });
        ref[0] = createDialog(box, false);
        ref[0].show();
    }

    private void refreshVisibleList() {
        if (activeListDialog == null || !activeListDialog.isShowing()) return;
        activeListDialog.dismiss();
        showList();
    }

    private void dismissActiveList() {
        Dialog dialog = activeListDialog;
        activeListDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(10));
        box.setBackground(roundedBackground(background, dp(14), withAlpha(foreground, 70)));
        box.setClipChildren(true);
        box.setClipToPadding(true);
        return box;
    }

    private TextView title(String value) {
        TextView title = text(value, 21f, true);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(0, 0, 0, dp(12));
        return title;
    }

    private TextView actionRow(String label, Runnable action) {
        TextView row = text(label, 16f, false);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setBackground(roundedBackground(withAlpha(foreground, 18), dp(10),
                withAlpha(foreground, 42)));
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        return row;
    }

    private void addBottomActions(LinearLayout box,
                                  String secondaryLabel,
                                  Runnable secondaryAction,
                                  String primaryLabel,
                                  Runnable primaryAction) {
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(6), 0, 0);
        if (secondaryLabel != null && secondaryAction != null) {
            TextView secondary = bottomAction(secondaryLabel, false, secondaryAction);
            actions.addView(secondary, new LinearLayout.LayoutParams(0, dp(46), 1f));
        }
        TextView primary = bottomAction(primaryLabel, true, primaryAction);
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        if (actions.getChildCount() > 0) primaryParams.setMarginStart(dp(8));
        actions.addView(primary, primaryParams);
        box.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private TextView bottomAction(String label, boolean bold, Runnable action) {
        TextView actionView = text(label, 16f, bold);
        actionView.setGravity(Gravity.CENTER);
        actionView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        actionView.setBackground(roundedBackground(withAlpha(foreground, bold ? 28 : 12),
                dp(9), withAlpha(foreground, bold ? 70 : 40)));
        actionView.setOnClickListener(v -> action.run());
        return actionView;
    }

    private Dialog createDialog(LinearLayout content, boolean adjustResize) {
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(240), Math.min(screenWidth - dp(24), dp(460)));
        if (activity instanceof AppCompatActivity) {
            return AdaptiveDialogLayoutHelper.createStableBottomDialog(
                    (AppCompatActivity) activity, content, 28, adjustResize, width);
        }
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(content, new ViewGroup.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT));
        return dialog;
    }

    private GradientDrawable roundedBackground(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
    }

    private String summary(DocumentAnnotation annotation) {
        String type = annotation.isHighlight()
                ? activity.getString(R.string.annotation_highlight)
                : activity.getString(R.string.annotation_note);
        String quote = cleanPreview(annotation.getSelectedText(), 90);
        String note = cleanPreview(annotation.getNote(), 90);
        String position = annotation.getLineNumber() > 0
                ? activity.getString(R.string.annotation_line, annotation.getLineNumber())
                : String.format(Locale.getDefault(), "%d", annotation.getStartPosition());
        StringBuilder text = new StringBuilder(type).append(" · ").append(position);
        if (!quote.isEmpty()) text.append("\n“").append(quote).append("”");
        if (!note.isEmpty()) text.append("\n").append(note);
        return text.toString();
    }

    private String quotedPreview(String value) {
        String clean = cleanPreview(value, 180);
        return clean.isEmpty() ? "" : "“" + clean + "”";
    }

    private String cleanPreview(String value, int limit) {
        String clean = value != null ? value.replaceAll("\\s+", " ").trim() : "";
        if (clean.length() > limit) clean = clean.substring(0, limit).trim() + "…";
        return clean;
    }

    private TextView text(String value, float sizeSp, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(foreground);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
