package com.textview.reader.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.textview.reader.R;
import com.textview.reader.model.ReaderState;
import com.textview.reader.util.FileSortUtils;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private static final int MAIN_ACTION_SHORT_HOLD_MS = 200;
    private static final int MULTI_SELECT_LONG_PRESS_MS = 800;
    private static final int MAIN_ROW_TEXT_END_PADDING_DP = 10;
    private static final Object SELECTION_PAYLOAD = "selection_payload";

    public interface OnFileClickListener {
        void onFileClick(File file);
        void onFileLongClick(File file);
        void onFileMultiSelectLongClick(File file);
    }

    private final List<File> files = new ArrayList<>();
    private final Context context;
    private OnFileClickListener listener;
    private int sortMode = PrefsManager.SORT_NAME_ASC;
    private boolean sortEnabled = true;
    private boolean showFilePath = false;
    private int touchCancelGeneration = 0;
    private boolean showReadingProgress = false;
    private boolean selectionMode = false;
    private final Set<String> selectedPaths = new LinkedHashSet<>();
    private final Map<String, Integer> readingProgressByPath = new HashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public FileAdapter() {
        this.context = null;
    }

    public FileAdapter(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(OnFileClickListener listener) { this.listener = listener; }

    public void setShowReadingProgress(boolean enabled) {
        if (this.showReadingProgress == enabled) return;
        this.showReadingProgress = enabled;
        if (!enabled) readingProgressByPath.clear();
        if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
    }

    public void setShowFilePath(boolean enabled) {
        if (this.showFilePath == enabled) return;
        this.showFilePath = enabled;
        if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
    }

    public void setReadingProgressStates(List<ReaderState> states) {
        readingProgressByPath.clear();
        if (states != null) {
            for (ReaderState state : states) {
                if (state == null || state.getFilePath() == null) continue;
                int pct = calculateReadingProgressPercent(state);
                if (pct >= 0) readingProgressByPath.put(state.getFilePath(), pct);
            }
        }
    }

    public void refreshReadingProgress() {
        if (showReadingProgress && getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount());
        }
    }
    public void setSelectionState(boolean active, @NonNull Set<String> paths) {
        boolean modeChanged = selectionMode != active;
        Set<String> oldSelection = new LinkedHashSet<>(selectedPaths);
        selectionMode = active;
        selectedPaths.clear();
        selectedPaths.addAll(paths);
        if (getItemCount() <= 0) return;

        if (modeChanged) {
            notifyItemRangeChanged(0, getItemCount(), SELECTION_PAYLOAD);
            return;
        }

        LinkedHashSet<String> changed = new LinkedHashSet<>(oldSelection);
        changed.addAll(selectedPaths);
        for (String path : changed) {
            boolean wasSelected = oldSelection.contains(path);
            boolean isSelected = selectedPaths.contains(path);
            if (wasSelected != isSelected) notifyPathChanged(path, SELECTION_PAYLOAD);
        }
    }

    private void notifyPathChanged(@NonNull String path, @Nullable Object payload) {
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            if (file != null && path.equals(file.getAbsolutePath())) {
                if (payload == null) notifyItemChanged(i); else notifyItemChanged(i, payload);
                return;
            }
        }
    }

    @NonNull
    public ArrayList<File> getFilesSnapshot() {
        return new ArrayList<>(files);
    }


    public void cancelPendingPresses() {
        touchCancelGeneration++;
    }

    public void setFiles(List<File> fileList) {
        replaceFiles(new ArrayList<>(fileList));
    }

    /**
     * Fast replacement used when navigating to a different folder. Directory
     * switches do not need item-by-item DiffUtil animation, and very large
     * folders can make DiffUtil noticeably block the UI thread.
     */
    public void setFilesFastPresorted(List<File> fileList) {
        files.clear();
        files.addAll(fileList);
        notifyDataSetChanged();
    }

    /** Updates the stored sort mode without re-sorting the currently visible list. */
    public void setSortModeSilently(int mode) {
        this.sortMode = mode;
    }

    public void setSortMode(int mode) {
        if (this.sortMode == mode) return;
        this.sortMode = mode;
        replaceFiles(new ArrayList<>(files));
    }

    public void setSortEnabled(boolean enabled) {
        if (this.sortEnabled == enabled) return;
        this.sortEnabled = enabled;
        replaceFiles(new ArrayList<>(files));
    }

    private void replaceFiles(@NonNull List<File> next) {
        if (sortEnabled) sortFiles(next);
        if (files.equals(next)) return;

        List<File> old = new ArrayList<>(files);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }

            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).getAbsolutePath()
                        .equals(next.get(newItemPosition).getAbsolutePath());
            }

            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                File a = old.get(oldItemPosition);
                File b = next.get(newItemPosition);
                return a.getName().equals(b.getName())
                        && a.isDirectory() == b.isDirectory()
                        && a.length() == b.length()
                        && a.lastModified() == b.lastModified();
            }
        });

        files.clear();
        files.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    private void sortFiles(@NonNull List<File> target) {
        FileSortUtils.sortMainFiles(context, target, sortMode);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) { holder.bind(files.get(position)); }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            holder.bindSelectionState(files.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() { return files.size(); }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        holder.cancelPendingPress();
        super.onViewRecycled(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        holder.cancelPendingPress();
        super.onViewDetachedFromWindow(holder);
    }

    public void refreshTheme() {
        if (getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    public void release() {
        listener = null;
        files.clear();
    }

    private static int calculateReadingProgressPercent(@NonNull ReaderState state) {
        int current = state.getPageNumber();
        int total = state.getTotalPages();
        if (total > 1 && current > 0) {
            int clampedCurrent = Math.max(1, Math.min(total, current));
            return Math.max(0, Math.min(100, Math.round((clampedCurrent * 100f) / total)));
        }

        long length = state.getFileLength();
        int charPosition = state.getCharPosition();
        if (length > 0 && charPosition > 0) {
            return Math.max(0, Math.min(100, Math.round((charPosition * 100f) / length)));
        }
        return -1;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        LinearLayout textContainer;
        TextView name, info, path, progress, selectionMarker;

        private final Handler touchHandler = new Handler(Looper.getMainLooper());
        private float downX;
        private float downY;
        private boolean tapCancelled;
        private boolean longPressed;
        private boolean multiSelectPressed;
        private boolean consumeUpAfterMultiSelect;
        private final int tapSlop;
        private Runnable pendingLongPress;
        private Runnable pendingMultiSelectPress;

        ViewHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.file_icon);
            textContainer = v.findViewById(R.id.file_text_container);
            name = v.findViewById(R.id.file_name);
            info = v.findViewById(R.id.file_info);
            path = v.findViewById(R.id.file_path);
            progress = v.findViewById(R.id.file_progress);
            selectionMarker = v.findViewById(R.id.file_selection_marker);
            tapSlop = Math.max(10, ViewConfiguration.get(v.getContext()).getScaledTouchSlop());

            v.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        tapCancelled = false;
                        longPressed = false;
                        multiSelectPressed = false;
                        consumeUpAfterMultiSelect = false;
                        view.setPressed(true);
                        clearPendingTouchCallbacks();

                        final int longPressGeneration = touchCancelGeneration;
                        pendingLongPress = () -> {
                            if (longPressGeneration == touchCancelGeneration
                                    && !tapCancelled
                                    && !multiSelectPressed
                                    && listener != null) {
                                longPressed = true;
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                            }
                        };
                        pendingMultiSelectPress = () -> {
                            if (longPressGeneration == touchCancelGeneration
                                    && !tapCancelled
                                    && listener != null) {
                                int pos = getBindingAdapterPosition();
                                if (pos != RecyclerView.NO_POSITION) {
                                    multiSelectPressed = true;
                                    consumeUpAfterMultiSelect = true;
                                    longPressed = false;
                                    view.setPressed(false);
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                                    listener.onFileMultiSelectLongClick(files.get(pos));
                                }
                            }
                        };
                        touchHandler.postDelayed(pendingLongPress, MAIN_ACTION_SHORT_HOLD_MS);
                        touchHandler.postDelayed(pendingMultiSelectPress, MULTI_SELECT_LONG_PRESS_MS);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (Math.hypot(dx, dy) > tapSlop) {
                            tapCancelled = true;
                            view.setPressed(false);
                            clearPendingTouchCallbacks();
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        clearPendingTouchCallbacks();
                        if (consumeUpAfterMultiSelect) {
                            consumeUpAfterMultiSelect = false;
                            return true;
                        }
                        if (!tapCancelled && listener != null) {
                            int pos = getBindingAdapterPosition();
                            if (pos != RecyclerView.NO_POSITION) {
                                if (multiSelectPressed) {
                                    return true;
                                } else if (longPressed) {
                                    listener.onFileLongClick(files.get(pos));
                                } else {
                                    view.performClick();
                                    listener.onFileClick(files.get(pos));
                                }
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        tapCancelled = true;
                        clearPendingTouchCallbacks();
                        return false;

                    default:
                        return false;
                }
            });
        }

        void cancelPendingPress() {
            tapCancelled = true;
            longPressed = false;
            multiSelectPressed = false;
            itemView.setPressed(false);
            clearPendingTouchCallbacks();
        }

        private void clearPendingTouchCallbacks() {
            if (pendingLongPress != null) {
                touchHandler.removeCallbacks(pendingLongPress);
                pendingLongPress = null;
            }
            if (pendingMultiSelectPress != null) {
                touchHandler.removeCallbacks(pendingMultiSelectPress);
                pendingMultiSelectPress = null;
            }
        }

        void bind(File file) {
            cancelPendingPress();
            PrefsManager prefs = PrefsManager.getInstance(itemView.getContext());
            boolean dark = prefs.shouldUseDarkColors(itemView.getContext());
            int primaryText = prefs.getMainTextColor(itemView.getContext());
            int secondaryText = prefs.getMainSubTextColor(itemView.getContext());
            int iconTint = dark ? prefs.getMainTextColor(itemView.getContext()) : Color.rgb(72, 76, 82);

            name.setText(file.getName());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setHorizontallyScrolling(false);
            name.setTextColor(primaryText);
            info.setSingleLine(true);
            info.setEllipsize(TextUtils.TruncateAt.END);
            info.setTextColor(secondaryText);
            if (path != null) {
                path.setSingleLine(true);
                path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                path.setTextColor(secondaryText);
                path.setVisibility(showFilePath ? View.VISIBLE : View.GONE);
                path.setText(showFilePath ? folderPathFor(file) : "");
            }
            if (progress != null) {
                progress.setTextColor(secondaryText);
                progress.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
                progress.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
            }

            if (file.isDirectory()) {
                icon.setImageResource(R.drawable.ic_folder);
                // Avoid file.listFiles() here: it is synchronous disk I/O and was
                // running on every scroll for every visible directory row, which
                // hung the UI thread on large storage roots.
                info.setText(R.string.folder);
            } else {
                icon.setImageResource(R.drawable.ic_text_file);
                String size = FileUtils.formatFileSize(file.length());
                String date = dateFormat.format(new Date(FileSortUtils.fileSortDate(itemView.getContext(), file)));
                String type = FileUtils.getReadableFileType(file.getName());
                info.setText(String.format(Locale.getDefault(), "%s  •  %s  •  %s", type, size, date));
            }
            updateReadingProgressBadge(file);
            icon.setImageTintList(ColorStateList.valueOf(iconTint));
            itemView.setPressed(false);
            itemView.setBackground(makeFileRowBackground(prefs));
            bindSelectionState(file);
        }

        void bindSelectionState(@NonNull File file) {
            PrefsManager prefs = PrefsManager.getInstance(itemView.getContext());
            boolean selected = selectionMode && selectedPaths.contains(file.getAbsolutePath());
            itemView.setPressed(false);
            itemView.setSelected(selected);
            itemView.setActivated(selected);
            if (selectionMarker != null) {
                selectionMarker.setVisibility(selected ? View.VISIBLE : View.GONE);
                if (selected) {
                    boolean dark = prefs.shouldUseDarkColors(itemView.getContext());
                    int markerBg = prefs.getMainFileLongHoldColor(itemView.getContext());
                    int markerFg = dark ? Color.WHITE : Color.rgb(24, 24, 24);
                    selectionMarker.setTextColor(markerFg);
                    selectionMarker.setBackground(makeSelectionMarkerBackground(markerBg, markerFg));
                    selectionMarker.bringToFront();
                }
            }
        }

        private void updateReadingProgressBadge(@NonNull File file) {
            if (progress == null) return;
            Integer pct = showReadingProgress && !file.isDirectory()
                    ? readingProgressByPath.get(file.getAbsolutePath())
                    : null;
            if (pct == null) {
                setTextReserveEnd(0);
                progress.setVisibility(View.GONE);
                progress.setText("");
                progress.setBackgroundColor(Color.TRANSPARENT);
                return;
            }

            progress.setBackgroundColor(Color.TRANSPARENT);
            progress.setText(String.format(Locale.getDefault(), "%d%%", Math.max(0, Math.min(100, pct))));
            progress.setVisibility(View.VISIBLE);

            int badgeWidth = getMaxProgressBadgeWidth();
            ViewGroup.LayoutParams layoutParams = progress.getLayoutParams();
            if (layoutParams != null && layoutParams.width != badgeWidth) {
                layoutParams.width = badgeWidth;
                progress.setLayoutParams(layoutParams);
            }

            int marginEnd = 0;
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                marginEnd = ((ViewGroup.MarginLayoutParams) layoutParams).getMarginEnd();
            }
            int progressReserve = badgeWidth + marginEnd + dpToPx(5);
            setTextReserveEnd(progressReserve);
        }

        private void setTextReserveEnd(int reservePx) {
            int baseEndPadding = dpToPx(MAIN_ROW_TEXT_END_PADDING_DP);
            int endPadding = baseEndPadding + Math.max(0, reservePx);
            if (textContainer != null) {
                textContainer.setPadding(
                        textContainer.getPaddingLeft(),
                        textContainer.getPaddingTop(),
                        endPadding,
                        textContainer.getPaddingBottom());
            }
            name.setPadding(name.getPaddingLeft(), name.getPaddingTop(), 0, name.getPaddingBottom());
            info.setPadding(info.getPaddingLeft(), info.getPaddingTop(), 0, info.getPaddingBottom());
            if (path != null) path.setPadding(path.getPaddingLeft(), path.getPaddingTop(), 0, path.getPaddingBottom());
        }

        private int getMaxProgressBadgeWidth() {
            int textWidth = (int) Math.ceil(progress.getPaint().measureText("100%"));
            return textWidth + progress.getPaddingLeft() + progress.getPaddingRight();
        }

        @NonNull
        private String folderPathFor(@NonNull File file) {
            File parent = file.getParentFile();
            return parent != null ? parent.getAbsolutePath() : file.getAbsolutePath();
        }

        private int dpToPx(int dp) {
            return Math.round(dp * itemView.getResources().getDisplayMetrics().density);
        }

        private GradientDrawable makeSelectionMarkerBackground(int bg, int fg) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(bg);
            drawable.setStroke(Math.max(1, dpToPx(1)), fg);
            return drawable;
        }

        private StateListDrawable makeFileRowBackground(@NonNull PrefsManager prefs) {
            int pressed = prefs.getMainFileLongHoldColor(itemView.getContext());
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(pressed));
            states.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(pressed));
            states.addState(new int[]{android.R.attr.state_activated}, new ColorDrawable(pressed));
            states.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(pressed));
            states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            return states;
        }
    }
}
