package com.textview.reader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.content.Context;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileClipboardController;

import java.io.File;
import java.util.ArrayList;

/**
 * Owns the pending copy/move/extract/compress dropdown under the main toolbar.
 * The menu now dismisses as soon as the queue becomes empty instead of
 * leaving a blank shell behind.
 */
final class MainPendingActionDropdownController {
    private final MainActivity activity;

    MainPendingActionDropdownController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void show() {
        boolean hasClipboard = activity.fileClipboardController.hasPending();
        boolean hasExtract = !activity.pendingExtractArchives.isEmpty();
        boolean hasCreate = !activity.pendingArchiveCreations.isEmpty();
        if ((!hasClipboard && !hasExtract && !hasCreate) || activity.mainPendingActionButton == null) return;

        final MainDropdownStyle style = MainDropdownStyle.from(activity);

        final int screenWidth = Math.max(activity.dpToPx(320), activity.getResources().getDisplayMetrics().widthPixels);
        final int popupWidth = Math.min(screenWidth - activity.dpToPx(24), activity.dpToPx(430));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, activity.dpToPx(6), 0, activity.dpToPx(6));
        box.setBackground(style.makePanelBackground(activity, 8, true));
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

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.pending_actions_title));
        title.setTextColor(style.sub);
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        title.setPadding(activity.dpToPx(14), 0, activity.dpToPx(14), 0);
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(34)));

        TextView runClipboardAllView = makeRunAllButton(style);
        LinearLayout.LayoutParams runAllLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42));
        runAllLp.setMargins(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(6));
        box.addView(runClipboardAllView, runAllLp);
        runClipboardAllView.setOnClickListener(v -> {
            popup.dismiss();
            activity.pasteAllPendingClipboardItemsToCurrentDirectory();
        });

        TextView runExtractAllView = makeRunAllButton(style);
        LinearLayout.LayoutParams extractAllLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42));
        extractAllLp.setMargins(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(6));
        box.addView(runExtractAllView, extractAllLp);
        runExtractAllView.setOnClickListener(v -> {
            popup.dismiss();
            activity.confirmAllPendingArchiveExtractionsToCurrentDirectory();
        });

        TextView runCreateAllView = makeRunAllButton(style);
        LinearLayout.LayoutParams createAllLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42));
        createAllLp.setMargins(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(6));
        box.addView(runCreateAllView, createAllLp);
        runCreateAllView.setOnClickListener(v -> {
            popup.dismiss();
            activity.confirmAllPendingArchiveCreations();
        });

        MaxHeightScrollView queueScroller = new MaxHeightScrollView(activity);
        queueScroller.setFillViewport(false);
        queueScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        queueScroller.setVerticalScrollBarEnabled(true);
        queueScroller.setMaxHeight(Math.min(
                activity.dpToPx(310),
                Math.max(activity.dpToPx(150), activity.getResources().getDisplayMetrics().heightPixels / 3)));

        LinearLayout rowsContainer = new LinearLayout(activity);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        queueScroller.addView(rowsContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        box.addView(queueScroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView clearView = new TextView(activity);
        clearView.setText(activity.getString(R.string.clear_pending_actions));
        clearView.setTextColor(style.danger);
        clearView.setTextSize(14f);
        clearView.setTypeface(Typeface.DEFAULT_BOLD);
        clearView.setGravity(Gravity.CENTER);
        clearView.setSingleLine(true);
        clearView.setIncludeFontPadding(false);
        clearView.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        box.addView(clearView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(42)));

        final Runnable[] refreshRows = new Runnable[1];
        refreshRows[0] = () -> {
            rowsContainer.removeAllViews();
            boolean hasAny = false;
            boolean queueExecutionBusy = isQueueExecutionBusy();
            int clipboardCount = activity.fileClipboardController.getPendingItems().size();
            int extractCount = activity.pendingExtractArchives.size();
            int createCount = activity.pendingArchiveCreations.size();
            runClipboardAllView.setText(activity.getString(R.string.paste_all_here));
            runClipboardAllView.setVisibility(clipboardCount > 0 ? View.VISIBLE : View.GONE);
            setExecutionControlEnabled(runClipboardAllView, clipboardCount > 0 && !queueExecutionBusy);
            runExtractAllView.setText(activity.getString(R.string.extract_all_here));
            runExtractAllView.setVisibility(extractCount > 0 ? View.VISIBLE : View.GONE);
            setExecutionControlEnabled(runExtractAllView, extractCount > 0 && !queueExecutionBusy);
            runCreateAllView.setText(activity.getString(R.string.archive_create_all));
            runCreateAllView.setVisibility(createCount > 0 ? View.VISIBLE : View.GONE);
            setExecutionControlEnabled(runCreateAllView, createCount > 0 && !queueExecutionBusy);
            setExecutionControlEnabled(clearView, (clipboardCount > 0 || extractCount > 0 || createCount > 0)
                    && !queueExecutionBusy);

            for (FileClipboardController.PendingItem item : activity.fileClipboardController.getPendingItems()) {
                hasAny = true;
                File source = item.getSource();
                String name = source.getName();
                String path = source.getAbsolutePath();
                String label = activity.getString(item.isCopy()
                        ? R.string.pending_copy_action
                        : R.string.pending_move_action, name);
                addPendingActionDropdownRow(rowsContainer, label, path, style.fg, style.sub, style.danger, style.rowPanel,
                        !queueExecutionBusy,
                        !queueExecutionBusy,
                        () -> {
                            popup.dismiss();
                            if (activity.fileClipboardController.setActive(item.getId())) {
                                activity.pastePendingClipboardItemToCurrentDirectory();
                            }
                        },
                        () -> {
                            activity.cancelPendingClipboardOperation(item.getId());
                            refreshRows[0].run();
                        });
            }

            for (File archive : new ArrayList<>(activity.pendingExtractArchives)) {
                hasAny = true;
                String name = archive != null ? archive.getName() : "";
                String path = archive != null ? archive.getAbsolutePath() : "";
                addPendingActionDropdownRow(rowsContainer, activity.getString(R.string.pending_extract_action, name), path,
                        style.fg, style.sub, style.danger, style.rowPanel,
                        !queueExecutionBusy,
                        !queueExecutionBusy,
                        () -> {
                            popup.dismiss();
                            if (activity.setActivePendingArchiveExtraction(archive)) {
                                activity.confirmPendingArchiveExtractionToCurrentDirectory();
                            }
                        },
                        () -> {
                            activity.cancelPendingArchiveExtraction(archive);
                            refreshRows[0].run();
                        });
            }

            for (MainPendingArchiveCreation task : new ArrayList<>(activity.pendingArchiveCreations)) {
                hasAny = true;
                String name = task != null ? task.displayName() : "";
                String path = activity.getString(R.string.pending_create_destination_hint);
                int count = task != null ? task.sources.size() : 0;
                addPendingActionDropdownRow(rowsContainer, activity.getString(R.string.pending_create_action, count, name), path,
                        style.fg, style.sub, style.danger, style.rowPanel,
                        !queueExecutionBusy,
                        !queueExecutionBusy,
                        () -> {
                            popup.dismiss();
                            if (activity.setActivePendingArchiveCreation(task)) {
                                activity.confirmPendingArchiveCreation();
                            }
                        },
                        () -> {
                            activity.cancelPendingArchiveCreation(task);
                            refreshRows[0].run();
                        });
            }

            activity.updateMainOverflowButtonVisibility();
            if (!hasAny) {
                popup.dismiss();
            }
        };

        clearView.setOnClickListener(v -> {
            if (isQueueExecutionBusy()) return;
            activity.clearPendingActionQueue();
            activity.updateMainOverflowButtonVisibility();
            popup.dismiss();
        });

        refreshRows[0].run();

        int xoff = activity.mainPendingActionButton.getWidth() - popupWidth;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            popup.showAsDropDown(activity.mainPendingActionButton, xoff, 0, Gravity.NO_GRAVITY);
        } else {
            popup.showAsDropDown(activity.mainPendingActionButton, xoff, 0);
        }
    }

    private void addPendingActionDropdownRow(@NonNull LinearLayout box,
                                             @NonNull String label,
                                             @NonNull String fullPath,
                                             int textColor,
                                             int subTextColor,
                                             int dangerColor,
                                             int rowPanelColor,
                                             boolean openEnabled,
                                             boolean cancelEnabled,
                                             @NonNull Runnable openAction,
                                             @NonNull Runnable cancelAction) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(12), activity.dpToPx(9), activity.dpToPx(4), activity.dpToPx(9));
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(rowPanelColor);
        rowBg.setCornerRadius(activity.dpToPx(10));
        row.setBackground(rowBg);
        row.setClickable(openEnabled);
        row.setEnabled(openEnabled);
        row.setAlpha(openEnabled ? 1.0f : 0.55f);
        row.setOnClickListener(v -> {
            if (openEnabled) openAction.run();
        });
        row.setMinimumHeight(activity.dpToPx(58));

        LinearLayout textBox = new LinearLayout(activity);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setGravity(Gravity.CENTER_VERTICAL);
        textBox.setPadding(0, 0, activity.dpToPx(8), 0);

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(textColor);
        labelView.setTextSize(15f);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        labelView.setSingleLine(true);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelView.setIncludeFontPadding(false);
        textBox.addView(labelView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView pathView = new TextView(activity);
        pathView.setText(fullPath);
        pathView.setTextColor(subTextColor);
        pathView.setTextSize(12.5f);
        pathView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        pathView.setSingleLine(false);
        pathView.setHorizontallyScrolling(false);
        pathView.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pathView.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            pathView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pathLp.setMargins(0, activity.dpToPx(4), 0, 0);
        textBox.addView(pathView, pathLp);

        row.addView(textBox, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));

        TextView cancelView = new TextView(activity);
        cancelView.setText("X");
        cancelView.setTextColor(dangerColor);
        cancelView.setTextSize(17f);
        cancelView.setTypeface(Typeface.DEFAULT_BOLD);
        cancelView.setGravity(Gravity.CENTER);
        cancelView.setIncludeFontPadding(false);
        cancelView.setContentDescription(activity.getString(R.string.cancel_pending_action));
        cancelView.setEnabled(cancelEnabled);
        cancelView.setAlpha(cancelEnabled ? 1.0f : 0.45f);
        cancelView.setOnClickListener(v -> {
            if (!cancelEnabled) return;
            v.setSelected(true);
            cancelAction.run();
        });
        row.addView(cancelView, new LinearLayout.LayoutParams(
                activity.dpToPx(32),
                LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(activity.dpToPx(6), 0, activity.dpToPx(6), activity.dpToPx(6));
        box.addView(row, lp);
    }

    private boolean isQueueExecutionBusy() {
        return activity.fileClipboardController.isInProgress()
                || activity.archiveExtractInProgress
                || activity.archiveCreateInProgress;
    }

    private void setExecutionControlEnabled(@NonNull View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.45f);
        view.setClickable(enabled);
    }

    @NonNull
    private TextView makeRunAllButton(@NonNull MainDropdownStyle style) {
        TextView button = new TextView(activity);
        button.setTextColor(style.fg);
        button.setTextSize(14f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setPadding(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(style.rowPanel);
        bg.setCornerRadius(activity.dpToPx(8));
        button.setBackground(bg);
        return button;
    }
    private static final class MaxHeightScrollView extends ScrollView {
        private int maxHeight;

        MaxHeightScrollView(@NonNull Context context) {
            super(context);
        }

        void setMaxHeight(int maxHeight) {
            this.maxHeight = Math.max(0, maxHeight);
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int limitedHeightSpec = heightMeasureSpec;
            if (maxHeight > 0) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int size = MeasureSpec.getSize(heightMeasureSpec);
                int height = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(size, maxHeight);
                limitedHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, limitedHeightSpec);
        }
    }
}
