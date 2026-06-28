package com.readwide.manager.adapter;

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
import com.readwide.manager.R;
import com.readwide.manager.model.FileListItem;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.FileSortUtils;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;
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
    private static final int MAIN_ROW_TEXT_END_PADDING_DP = 6;
    private static final Object SELECTION_PAYLOAD = "selection_payload";

    public interface OnFileClickListener {
        void onFileClick(File file);
        void onFileLongClick(File file);
        void onFileMultiSelectLongClick(File file);
    }

    private final List<FileListItem> items = new ArrayList<>();
    private final Context context;
    private OnFileClickListener listener;
    private int sortMode = PrefsManager.SORT_NAME_ASC;
    private boolean sortEnabled = true;
    private boolean showFilePath = false;
    // Canonical search-root paths for the active search; used to show file
    // locations relative to the searched folder instead of repeating the full
    // absolute path on every row.
    private java.util.List<String> searchRootPaths = new java.util.ArrayList<>();
    private boolean searchSpansMultipleRoots = false;
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

    /**
     * Provide the search roots so file locations can be shown relative to the
     * searched folder. With a single root, a file directly in that root shows no
     * location; files in subfolders show the path below the root. With multiple
     * roots (all-storage search) the matched root's name is kept as a prefix so
     * results from different storages stay distinguishable.
     */
    public void setSearchRoots(@Nullable List<File> roots) {
        searchRootPaths = new java.util.ArrayList<>();
        if (roots != null) {
            for (File r : roots) {
                if (r == null) continue;
                try {
                    searchRootPaths.add(r.getCanonicalFile().getAbsolutePath());
                } catch (Exception e) {
                    searchRootPaths.add(r.getAbsolutePath());
                }
            }
        }
        searchSpansMultipleRoots = searchRootPaths.size() > 1;
    }

    /**
     * Computes the search-row location label for a file: the canonical parent
     * folder, expressed relative to the matched search root. Static and pure
     * (apart from one {@code getCanonicalFile()} resolution) so the file-search
     * thread can precompute it per result and store it on the FileListItem,
     * keeping this resolution off the UI thread during bind/scroll. The result
     * matches what {@link ViewHolder#folderPathFor(File)} produces.
     */
    public static String computeLocationLabel(@NonNull File file,
                                       @Nullable java.util.List<String> canonicalRootPaths,
                                       boolean spansMultipleRoots) {
        File parentFile = file.getParentFile();
        String parent = parentFile != null ? parentFile.getAbsolutePath() : file.getAbsolutePath();
        try {
            parent = (parentFile != null ? parentFile.getCanonicalFile() : file.getCanonicalFile()).getAbsolutePath();
        } catch (Exception ignored) { /* keep absolute */ }

        if (canonicalRootPaths == null || canonicalRootPaths.isEmpty()) {
            return parent; // not a rooted search; show full parent path
        }

        // Find the deepest matching root (handles nested roots safely).
        String bestRoot = null;
        for (String root : canonicalRootPaths) {
            if (root == null || root.isEmpty()) continue;
            if (parent.equals(root) || parent.startsWith(root + "/")) {
                if (bestRoot == null || root.length() > bestRoot.length()) bestRoot = root;
            }
        }
        if (bestRoot == null) {
            return parent; // outside known roots; show full path
        }

        // Path of the parent relative to the matched root ("" if directly in root).
        String rel = parent.equals(bestRoot) ? "" : parent.substring(bestRoot.length() + 1);

        if (spansMultipleRoots) {
            // Keep the root's folder name so different storages stay distinct.
            String rootName = bestRoot.substring(bestRoot.lastIndexOf('/') + 1);
            if (rootName.isEmpty()) rootName = bestRoot;
            return rel.isEmpty() ? rootName : rootName + "/" + rel;
        }
        // Single root: directly-in-root files show no location; subfolder files
        // are prefixed with ".../" to read as a path below the root.
        return rel.isEmpty() ? "" : ".../" + rel;
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
        for (int i = 0; i < items.size(); i++) {
            FileListItem item = items.get(i);
            if (item != null && path.equals(item.getAbsolutePath())) {
                if (payload == null) notifyItemChanged(i); else notifyItemChanged(i, payload);
                return;
            }
        }
    }

    @NonNull
    public ArrayList<File> getFilesSnapshot() {
        ArrayList<File> out = new ArrayList<>(items.size());
        for (FileListItem item : items) out.add(item.getFile());
        return out;
    }

    /**
     * Snapshot of the current rows as items, with their metadata already
     * computed. Unlike {@link #getFilesSnapshot()}, a caller that stores this for
     * later restoration avoids re-stat-ing every file: the browse-state cache
     * keeps the items so a back-navigation restore can rebind without touching
     * the disk on the UI thread.
     */
    @NonNull
    public ArrayList<FileListItem> getItemsSnapshot() {
        return new ArrayList<>(items);
    }

    /** Item at a binding-adapter position, or null if out of range. */
    @Nullable
    public FileListItem getItemAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    /**
     * Remove a single row in place (swipe-to-dismiss on the recent list).
     * Deleting the backing store entry is the caller's responsibility; this
     * only updates the adapter so the swiped row animates out without a full
     * rebind. Safe because items is a stable list mutated in place everywhere.
     */
    public void removeItemAt(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        notifyItemRemoved(position);
    }


    public void cancelPendingPresses() {
        touchCancelGeneration++;
    }

    public void setFiles(List<File> fileList) {
        replaceFiles(toItems(fileList));
    }

    /**
     * Item-based equivalent of {@link #setFiles(List)}: sorts (if enabled) and
     * diff-updates without stat-ing any file on the UI thread. Used by callers
     * (search, recent) that already built the items off the UI thread.
     */
    public void setItems(@NonNull List<FileListItem> itemList) {
        replaceFiles(new ArrayList<>(itemList));
    }

    /**
     * Fast replacement used when navigating to a different folder. Directory
     * switches do not need item-by-item DiffUtil animation, and very large
     * folders can make DiffUtil noticeably block the UI thread.
     */
    public void setFilesFastPresorted(List<File> fileList) {
        setItemsFastPresorted(toItems(fileList));
    }

    /** Item-based fast replacement: metadata already computed off the UI thread. */
    public void setItemsFastPresorted(@NonNull List<FileListItem> itemList) {
        items.clear();
        items.addAll(itemList);
        notifyDataSetChanged();
    }

    /**
     * Incrementally append already-ordered items (used while a file search is
     * still running). Only the newly inserted range is notified, so a result
     * set that grows to many thousands of entries does not re-bind or re-copy
     * the entire list on every batch. The caller is responsible for ordering;
     * during search this is discovery order, replaced by a sorted list when the
     * search finishes.
     */
    public void appendFilesPresorted(@NonNull List<File> moreFiles) {
        if (moreFiles.isEmpty()) return;
        appendItemsPresorted(toItems(moreFiles));
    }

    /** Item-based incremental append: metadata already computed off the UI thread. */
    public void appendItemsPresorted(@NonNull List<FileListItem> moreItems) {
        if (moreItems.isEmpty()) return;
        int start = items.size();
        items.addAll(moreItems);
        notifyItemRangeInserted(start, moreItems.size());
    }

    /**
     * Insert presorted items at a specific position without re-sorting. Used by
     * progressive folder loading to place newly discovered folders at the
     * folder/file boundary while files are appended at the end, so the growing
     * list stays in "folders then files" order without a full list replace.
     */
    public void insertItemsPresorted(int index, @NonNull List<FileListItem> moreItems) {
        if (moreItems.isEmpty()) return;
        if (index < 0) index = 0;
        if (index > items.size()) index = items.size();
        items.addAll(index, moreItems);
        notifyItemRangeInserted(index, moreItems.size());
    }

    @NonNull
    private static List<FileListItem> toItems(@NonNull List<File> fileList) {
        List<FileListItem> out = new ArrayList<>(fileList.size());
        for (File f : fileList) {
            if (f != null) out.add(FileListItem.from(f));
        }
        return out;
    }

    /** Number of items currently held, so a search can append only the delta. */
    public int getItemCountInternal() {
        return items.size();
    }

    /** Updates the stored sort mode without re-sorting the currently visible list. */
    public void setSortModeSilently(int mode) {
        this.sortMode = mode;
    }

    public void setSortMode(int mode) {
        if (this.sortMode == mode) return;
        this.sortMode = mode;
        replaceFiles(new ArrayList<>(items));
    }

    public void setSortEnabled(boolean enabled) {
        if (this.sortEnabled == enabled) return;
        this.sortEnabled = enabled;
        replaceFiles(new ArrayList<>(items));
    }

    private void replaceFiles(@NonNull List<FileListItem> next) {
        if (sortEnabled) sortItems(next);
        if (items.equals(next)) return;

        List<FileListItem> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }

            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).getAbsolutePath()
                        .equals(next.get(newItemPosition).getAbsolutePath());
            }

            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                FileListItem a = old.get(oldItemPosition);
                FileListItem b = next.get(newItemPosition);
                // Precomputed metadata only — no filesystem stat on the UI thread.
                return a.getName().equals(b.getName())
                        && a.isDirectory() == b.isDirectory()
                        && a.getSize() == b.getSize()
                        && a.getLastModified() == b.getLastModified();
            }
        });

        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    private void sortItems(@NonNull List<FileListItem> target) {
        // Reuse the shared File sorter, then map the items back into the sorted
        // order. Items wrap Files 1:1 (keyed by absolute path), so we sort the
        // unwrapped Files and rebuild the item list from a path->item index.
        ArrayList<File> fileView = new ArrayList<>(target.size());
        Map<String, FileListItem> byPath = new HashMap<>(target.size() * 2);
        for (FileListItem item : target) {
            fileView.add(item.getFile());
            byPath.put(item.getAbsolutePath(), item);
        }
        FileSortUtils.sortMainFiles(context, fileView, sortMode);
        target.clear();
        for (File f : fileView) {
            FileListItem item = byPath.get(f.getAbsolutePath());
            target.add(item != null ? item : FileListItem.from(f));
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) { holder.bind(items.get(position)); }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            holder.bindSelectionState(items.get(position).getFile());
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() { return items.size(); }

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
        items.clear();
    }

    private static int iconResForFile(@NonNull String fileName) {
        if (FileUtils.isPdfFile(fileName)) return R.drawable.ic_file_pdf;
        if (FileUtils.isEpubFile(fileName)) return R.drawable.ic_file_epub;
        if (FileUtils.isWordOrHwpFile(fileName)) return R.drawable.ic_file_document;
        if (FileUtils.isArchiveFile(fileName)) return R.drawable.ic_file_archive;
        if (FileUtils.isImageFile(fileName)) return R.drawable.ic_file_image;
        if (FileUtils.isVideoFile(fileName)) return R.drawable.ic_file_video;
        if (FileUtils.isAudioFile(fileName)) return R.drawable.ic_file_audio;
        if (FileUtils.isApkFile(fileName)) return R.drawable.ic_file_apk;
        return R.drawable.ic_text_file;
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
        com.readwide.manager.view.ExtensionEllipsisTextView name;
        TextView info, path, progress, selectionMarker;

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
                                    listener.onFileMultiSelectLongClick(items.get(pos).getFile());
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
                                    listener.onFileLongClick(items.get(pos).getFile());
                                } else {
                                    view.performClick();
                                    listener.onFileClick(items.get(pos).getFile());
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

        void bind(FileListItem item) {
            cancelPendingPress();
            File file = item.getFile();
            PrefsManager prefs = PrefsManager.getInstance(itemView.getContext());
            boolean dark = prefs.shouldUseDarkColors(itemView.getContext());
            int primaryText = prefs.getMainTextColor(itemView.getContext());
            int secondaryText = prefs.getMainSubTextColor(itemView.getContext());
            int iconTint = dark ? prefs.getMainTextColor(itemView.getContext()) : Color.rgb(72, 76, 82);

            name.setMaxLines(2);
            name.setTextColor(primaryText);
            name.setTailContextChars(20);
            name.setFullName(item.getName());
            info.setSingleLine(true);
            info.setEllipsize(TextUtils.TruncateAt.END);
            info.setTextColor(secondaryText);
            if (path != null) {
                String loc = showFilePath ? locationLabelFor(item) : "";
                path.setSingleLine(true);
                path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                path.setTextColor(secondaryText);
                boolean showLoc = showFilePath && !loc.isEmpty();
                path.setVisibility(showLoc ? View.VISIBLE : View.GONE);
                path.setText(showLoc ? loc : "");
            }
            if (progress != null) {
                progress.setTextColor(secondaryText);
                progress.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
                progress.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
            }

            if (item.isDirectory()) {
                icon.setImageResource(R.drawable.ic_folder);
                // Metadata is precomputed off the UI thread; no listFiles()/stat here.
                info.setText(R.string.folder);
            } else {
                icon.setImageResource(iconResForFile(item.getName()));
                String size = FileUtils.formatFileSize(item.getSize());
                String date = dateFormat.format(new Date(item.getSortDate()));
                String type = FileUtils.getReadableFileType(item.getName());
                info.setText(String.format(Locale.getDefault(), "%s  •  %s  •  %s", type, size, date));
            }
            updateReadingProgressBadge(item);
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

        private void updateReadingProgressBadge(@NonNull FileListItem item) {
            if (progress == null) return;
            // Use the precomputed directory flag and path rather than re-stating the
            // File on the UI thread (item.isDirectory()/getAbsolutePath() are cached).
            Integer pct = showReadingProgress && !item.isDirectory()
                    ? readingProgressByPath.get(item.getAbsolutePath())
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
            int progressReserve = badgeWidth + marginEnd;
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
        /**
         * Resolves the location label for a row, preferring the value the
         * search thread precomputed onto the item (so search scrolling does no
         * canonical-path I/O on the UI thread). Falls back to resolving on
         * demand only for items that carry no precomputed label.
         */
        private String locationLabelFor(@NonNull FileListItem item) {
            String precomputed = item.getDisplayLocation();
            if (precomputed != null) return precomputed;
            return folderPathFor(item.getFile());
        }

        private String folderPathFor(@NonNull File file) {
            return computeLocationLabel(file, searchRootPaths, searchSpansMultipleRoots);
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
