package com.textview.reader;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;
import com.textview.reader.util.FileUtils;

final class MainFileOperationProgressController {
    private final MainActivity activity;
    @Nullable private android.app.Dialog dialog;
    @Nullable private FileOperationProgress activeProgress;

    MainFileOperationProgressController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    @NonNull
    FileOperationProgress show(@NonNull String titleText, @Nullable String initialDetail) {
        dismissActiveDialogOnly();
        FileOperationProgress progress = new FileOperationProgress(titleText, null);
        activeProgress = progress;
        if (initialDetail != null) progress.setDetail(initialDetail);
        showDialogFor(progress);
        activity.updateMainOverflowButtonVisibility();
        return progress;
    }

    boolean showActiveProgressDialog() {
        FileOperationProgress progress = activeProgress;
        if (progress == null || progress.isComplete()) return false;
        dismissActiveDialogOnly();
        showDialogFor(progress);
        activity.updateMainOverflowButtonVisibility();
        return true;
    }

    boolean hasBackgroundProgress() {
        return activeProgress != null
                && !activeProgress.isComplete()
                && (dialog == null || !dialog.isShowing());
    }

    void finish(@Nullable FileOperationProgress progress) {
        if (progress == null || progress == activeProgress) {
            if (progress != null) progress.markComplete();
            dismissActiveDialogOnly();
            activeProgress = null;
            activity.updateMainOverflowButtonVisibility();
        }
    }

    private void showDialogFor(@NonNull FileOperationProgress progress) {
        final boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        final int bg = activity.prefs != null ? activity.prefs.getMainBgColor(activity) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        final int panel = activity.prefs != null ? activity.prefs.getMainPanelColor(activity) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int sub = activity.prefs != null ? activity.prefs.getMainSubTextColor(activity) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        final int line = activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));
        final int accent = activity.prefs != null ? activity.prefs.getMainSelectedColor(activity) : (dark ? Color.rgb(68, 110, 190) : Color.rgb(76, 118, 180));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(18), activity.dpToPx(14), activity.dpToPx(18), activity.dpToPx(14));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(activity.dpToPx(8));
        bgShape.setStroke(Math.max(1, activity.dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(activity);
        title.setText(progress.snapshot().title);
        title.setTextColor(fg);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(activity.dpToPx(2), 0, activity.dpToPx(2), activity.dpToPx(8));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout detailRow = makeMetaRow();
        TextView detail = makeMetaText(fg, 14f, true);
        detail.setPadding(activity.dpToPx(2), 0, activity.dpToPx(6), activity.dpToPx(5));
        TextView detailCount = makeCountText(sub, 12.5f);
        detailRow.addView(detail, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));
        detailRow.addView(detailCount, new LinearLayout.LayoutParams(
                activity.dpToPx(74),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(detailRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42)));

        LinearLayout folderRow = makeMetaRow();
        TextView folder = makeMetaText(sub, 12.5f, true);
        folder.setMaxLines(3);
        folder.setEllipsize(android.text.TextUtils.TruncateAt.END);
        folder.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        folder.setIncludeFontPadding(true);
        folder.setLineSpacing(0f, 1.0f);
        folder.setPadding(activity.dpToPx(2), activity.dpToPx(2), activity.dpToPx(6), activity.dpToPx(2));
        TextView folderCount = makeCountText(sub, 12.5f);
        folderRow.addView(folder, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));
        folderRow.addView(folderCount, new LinearLayout.LayoutParams(
                activity.dpToPx(74),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(folderRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(72)));

        LinearLayout progressRow = new LinearLayout(activity);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(progressRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(38)));

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(line));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0,
                activity.dpToPx(6),
                1f);
        barLp.setMargins(activity.dpToPx(2), 0, activity.dpToPx(10), 0);
        progressRow.addView(bar, barLp);

        PauseResumeIconButton pause = makeIconActionButton(fg, panel);
        pause.setContentDescription(activity.getString(R.string.operation_pause));
        progressRow.addView(pause, new LinearLayout.LayoutParams(
                activity.dpToPx(38),
                activity.dpToPx(34)));

        LinearLayout stats = new LinearLayout(activity);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setPadding(activity.dpToPx(2), activity.dpToPx(6), activity.dpToPx(2), activity.dpToPx(8));
        box.addView(stats, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(40)));

        TextView percent = makeMetaText(fg, 13f, false);
        percent.setTypeface(Typeface.DEFAULT_BOLD);
        stats.addView(percent, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0.32f));

        TextView bytes = makeMetaText(sub, 13f, false);
        bytes.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bytes.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        stats.addView(bytes, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0.68f));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        box.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42)));

        TextView background = makeActionButton(activity.getString(R.string.operation_background), fg, panel);
        TextView cancel = makeActionButton(activity.getString(R.string.cancel), sub, panel);
        addAction(actions, background);
        addAction(actions, cancel);

        progress.setListener(snapshot ->
                activity.fileSearchHandler.post(() -> {
                    if (activity.activityDestroyed) return;
                    updateProgressViews(snapshot, detail, detailCount, folder, folderCount, percent, bytes, bar, pause);
                }));

        pause.setOnClickListener(v -> progress.setPaused(!progress.isPaused()));
        background.setOnClickListener(v -> {
            dismissActiveDialogOnly();
            activity.updateMainOverflowButtonVisibility();
        });
        cancel.setOnClickListener(v -> {
            progress.cancel();
            dismissActiveDialogOnly();
            activity.updateMainOverflowButtonVisibility();
        });

        updateProgressViews(progress.snapshot(), detail, detailCount, folder, folderCount, percent, bytes, bar, pause);

        dialog = activity.createStableCenterDialog(box, 0, 0.18f);
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int dialogWidth = Math.max(activity.dpToPx(290), Math.min(Math.round(screenWidth * 0.88f), activity.dpToPx(540)));
        box.setMinimumWidth(dialogWidth);
        activity.overrideDialogWidth(dialog, dialogWidth);
        android.view.Window preShowWindow = dialog.getWindow();
        if (preShowWindow != null) preShowWindow.setWindowAnimations(0);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> {
            dismissActiveDialogOnly();
            activity.updateMainOverflowButtonVisibility();
        });
        dialog.setOnDismissListener(d -> activity.updateMainOverflowButtonVisibility());
        dialog.show();
        android.view.Window postShowWindow = dialog.getWindow();
        if (postShowWindow != null) {
            postShowWindow.setWindowAnimations(0);
            activity.overrideDialogWidth(dialog, dialogWidth);
        }
        updateProgressViews(progress.snapshot(), detail, detailCount, folder, folderCount, percent, bytes, bar, pause);
    }

    private void updateProgressViews(@NonNull FileOperationProgress.Snapshot snapshot,
                                     @NonNull TextView detail,
                                     @NonNull TextView detailCount,
                                     @NonNull TextView folder,
                                     @NonNull TextView folderCount,
                                     @NonNull TextView percent,
                                     @NonNull TextView bytes,
                                     @NonNull ProgressBar bar,
                                     @NonNull PauseResumeIconButton pause) {
        String detailText = snapshot.detail == null || snapshot.detail.length() == 0
                ? snapshot.title
                : snapshot.detail;
        detail.setText(detailText);
        detailCount.setText(formatProgressCount(snapshot.itemIndex, snapshot.itemTotal));

        String folderName = snapshot.folder == null || snapshot.folder.length() == 0
                ? "-"
                : snapshot.folder;
        String folderText = snapshot.folder == null || snapshot.folder.length() == 0
                ? activity.getString(R.string.operation_folder_unknown)
                : activity.getString(R.string.operation_folder_format, folderName);
        folder.setText(folderText);
        String folderCounter = formatProgressCount(snapshot.folderIndex, snapshot.folderTotal);
        if (folderCounter.length() == 0
                && snapshot.itemTotal > 0
                && snapshot.folder != null
                && snapshot.folder.length() > 0) {
            folderCounter = formatProgressCount(1, 1);
        }
        folderCount.setText(folderCounter);
        pause.setPausedState(snapshot.paused);
        pause.setContentDescription(activity.getString(snapshot.paused
                ? R.string.operation_resume
                : R.string.operation_pause));

        if (snapshot.indeterminate) {
            bar.setIndeterminate(true);
            percent.setText(snapshot.paused
                    ? activity.getString(R.string.operation_paused)
                    : activity.getString(R.string.operation_working));
            bytes.setText("");
            return;
        }
        bar.setIndeterminate(false);
        bar.setProgress(snapshot.percent() * 10);
        percent.setText(activity.getString(snapshot.paused
                ? R.string.operation_percent_paused_format
                : R.string.operation_percent_format,
                snapshot.percent()));
        bytes.setText(activity.getString(R.string.operation_bytes_format,
                FileUtils.formatFileSize(snapshot.doneBytes),
                FileUtils.formatFileSize(snapshot.totalBytes)));
    }

    private LinearLayout makeMetaRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView makeMetaText(int textColor, float textSize, boolean twoLines) {
        TextView view = new TextView(activity);
        view.setTextColor(textColor);
        view.setTextSize(textSize);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        view.setSingleLine(!twoLines);
        view.setMaxLines(twoLines ? 2 : 1);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView makeCountText(int textColor, float textSize) {
        TextView view = makeMetaText(textColor, textSize, false);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        view.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setSingleLine(true);
        view.setIncludeFontPadding(false);
        return view;
    }

    @NonNull
    private String formatProgressCount(int index, int total) {
        if (total <= 0) return "";
        int safeIndex = Math.max(1, Math.min(index, total));
        return "(" + safeIndex + "/" + total + ")";
    }

    private PauseResumeIconButton makeIconActionButton(int textColor, int panelColor) {
        PauseResumeIconButton button = new PauseResumeIconButton(activity, textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panelColor);
        bg.setCornerRadius(activity.dpToPx(6));
        button.setBackground(bg);
        button.setPadding(0, 0, 0, 0);
        button.setPausedState(false);
        return button;
    }

    private TextView makeActionButton(@NonNull String label, int textColor, int panelColor) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(13f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setSingleLine(true);
        button.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panelColor);
        bg.setCornerRadius(activity.dpToPx(6));
        button.setBackground(bg);
        return button;
    }

    private void addAction(@NonNull LinearLayout actions, @NonNull TextView button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(activity.dpToPx(4), 0, 0, 0);
        actions.addView(button, lp);
    }

    private static final class PauseResumeIconButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path playPath = new Path();
        private final int iconColor;
        private boolean pausedState;

        PauseResumeIconButton(@NonNull android.content.Context context, int iconColor) {
            super(context);
            this.iconColor = iconColor;
            paint.setColor(iconColor);
            paint.setStyle(Paint.Style.FILL);
            setClickable(true);
            setFocusable(true);
        }

        void setPausedState(boolean pausedState) {
            if (this.pausedState == pausedState) return;
            this.pausedState = pausedState;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(iconColor);
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            if (pausedState) {
                float height = 12.0f * density;
                float width = 10.0f * density;
                float left = cx - width * 0.38f;
                playPath.reset();
                playPath.moveTo(left, cy - height * 0.5f);
                playPath.lineTo(left, cy + height * 0.5f);
                playPath.lineTo(left + width, cy);
                playPath.close();
                canvas.drawPath(playPath, paint);
            } else {
                float barWidth = 3.2f * density;
                float gap = 3.6f * density;
                float height = 13.0f * density;
                float top = cy - height * 0.5f;
                float bottom = cy + height * 0.5f;
                float left1 = cx - gap * 0.5f - barWidth;
                float left2 = cx + gap * 0.5f;
                canvas.drawRoundRect(left1, top, left1 + barWidth, bottom, barWidth * 0.45f, barWidth * 0.45f, paint);
                canvas.drawRoundRect(left2, top, left2 + barWidth, bottom, barWidth * 0.45f, barWidth * 0.45f, paint);
            }
        }
    }

    private void dismissActiveDialogOnly() {
        if (activeProgress != null) activeProgress.setListener(null);
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        dialog = null;
    }
}
