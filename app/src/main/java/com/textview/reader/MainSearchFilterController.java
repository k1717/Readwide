package com.textview.reader;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.textview.reader.util.FileTypeFilter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the main-screen search box and file-type filter strip so MainActivity can
 * stay focused on navigation, lifecycle, and file operations.
 */
final class MainSearchFilterController {
    private final MainActivity activity;
    private final MainFileSearchRoots searchRoots;
    private final MainFileSearchWalker searchWalker;
    private final Runnable fileTypeFilterSnapRunnable = this::snapFileTypeFilterToSlot;

    MainSearchFilterController(@NonNull MainActivity activity) {
        this.activity = activity;
        this.searchRoots = new MainFileSearchRoots(activity);
        this.searchWalker = new MainFileSearchWalker(activity, this::handleSearchProgress);
    }

    void setupFileSearch() {
        activity.fileSearchBar = activity.findViewById(R.id.file_search_bar);
        activity.fileSearchInput = activity.findViewById(R.id.file_search_input);
        activity.fileSearchClearButton = activity.findViewById(R.id.file_search_clear_button);
        activity.fileSearchProgress = activity.findViewById(R.id.file_search_progress);
        activity.fileSearchScopeButton = activity.findViewById(R.id.file_search_scope_button);
        activity.fileSortButton = activity.findViewById(R.id.file_sort_button);
        activity.fileTypeFilterScroll = activity.findViewById(R.id.file_type_filter_scroll);
        activity.filterAllChip = activity.findViewById(R.id.filter_all);
        activity.filterTxtChip = activity.findViewById(R.id.filter_txt);
        activity.filterGeneralChip = activity.findViewById(R.id.filter_general);
        activity.filterArchiveChip = activity.findViewById(R.id.filter_archive);
        activity.filterPdfChip = activity.findViewById(R.id.filter_pdf);
        activity.filterEpubChip = activity.findViewById(R.id.filter_epub);
        activity.filterWordChip = activity.findViewById(R.id.filter_word);
        activity.filterImageChip = activity.findViewById(R.id.filter_img);

        setupFileTypeChip(activity.filterAllChip, MainActivity.FILTER_ALL);
        setupFileTypeChip(activity.filterGeneralChip, MainActivity.FILTER_GENERAL);
        setupFileTypeChip(activity.filterTxtChip, MainActivity.FILTER_TXT);
        setupFileTypeChip(activity.filterArchiveChip, MainActivity.FILTER_ARCHIVE);
        setupFileTypeChip(activity.filterPdfChip, MainActivity.FILTER_PDF);
        setupFileTypeChip(activity.filterEpubChip, MainActivity.FILTER_EPUB);
        setupFileTypeChip(activity.filterWordChip, MainActivity.FILTER_WORD);
        setupFileTypeChip(activity.filterImageChip, MainActivity.FILTER_IMAGE);
        applySavedFileTypeOrder();
        updateFileTypeChips();
        setupFileTypeFilterScrollBehavior();

        if (activity.fileSearchInput != null) {
            activity.fileSearchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    scheduleLiveFileSearch();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            activity.fileSearchInput.setOnEditorActionListener((v, actionId, event) -> {
                boolean enterKey = event != null
                        && event.getAction() == android.view.KeyEvent.ACTION_UP
                        && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER;
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enterKey) {
                    runLiveFileSearchNow();
                    return true;
                }
                return false;
            });
        }

        if (activity.fileSearchClearButton != null) {
            activity.fileSearchClearButton.setOnClickListener(v -> {
                if (activity.fileSearchInput != null) activity.fileSearchInput.setText("");
                if (activity.searchMode) restorePreSearchLocation();
            });
            activity.fileSearchClearButton.setVisibility(View.GONE);
        }

        setupSearchScopeButton();

        if (activity.fileSortButton != null) {
            activity.fileSortButton.setOnClickListener(v -> activity.showSortDialog());
        }
    }


    void applySavedFileTypeOrder() {
        ButtonOrderManager.applyOrder(activity, activity.prefs, ButtonOrderManager.GROUP_MAIN_FILTERS);
        applyFileTypeFilterSlotWidths();
    }

    private void setupSearchScopeButton() {
        if (activity.prefs != null) {
            activity.fileSearchAllFolders = activity.prefs.getFileSearchAllFolders();
        }
        updateSearchScopeButton();
        if (activity.fileSearchScopeButton == null) return;
        activity.fileSearchScopeButton.setOnClickListener(v -> {
            activity.fileSearchAllFolders = !activity.fileSearchAllFolders;
            if (activity.prefs != null) {
                activity.prefs.setFileSearchAllFolders(activity.fileSearchAllFolders);
            }
            updateSearchScopeButton();
            showBriefSearchScopeToast();
            String query = activity.fileSearchInput != null
                    ? activity.fileSearchInput.getText().toString().trim()
                    : "";
            if (activity.searchMode || !query.isEmpty() || activity.activeFileFilter != MainActivity.FILTER_ALL) {
                runLiveFileSearchNow();
            }
        });
    }


    private void showBriefSearchScopeToast() {
        int messageRes = activity.fileSearchAllFolders
                ? R.string.search_scope_all_folders
                : R.string.search_scope_current_folder_only;
        ShortToast.show(activity, messageRes);
    }

    private void setupFileTypeFilterScrollBehavior() {
        if (activity.fileTypeFilterScroll == null) return;

        activity.fileTypeFilterScroll.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                                oldLeft, oldTop, oldRight, oldBottom) ->
                applyFileTypeFilterSlotWidths());
        activity.fileTypeFilterScroll.post(this::applyFileTypeFilterSlotWidths);

        activity.fileTypeFilterScroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (activity.drawerLayout != null) {
                            activity.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
                        }
                        activity.resetDrawerSwipeState();
                        v.removeCallbacks(fileTypeFilterSnapRunnable);
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        v.postDelayed(fileTypeFilterSnapRunnable, 110L);
                        v.postDelayed(() -> {
                            if (activity.drawerLayout != null && !activity.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                                activity.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START);
                            }
                        }, 160L);
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private void applyFileTypeFilterSlotWidths() {
        if (activity.fileTypeFilterScroll == null || activity.fileTypeFilterScroll.getWidth() <= 0) return;
        final int visibleSlots = 5;
        final int gap = activity.dpToPx(4);
        final int viewport = activity.fileTypeFilterScroll.getWidth();
        final int slotWidth = Math.max(activity.dpToPx(50),
                (viewport - (gap * (visibleSlots - 1))) / visibleSlots);
        activity.fileTypeFilterStepPx = slotWidth + gap;
        View row = activity.findViewById(R.id.file_type_filter_row);
        if (row instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout chipRow = (android.widget.LinearLayout) row;
            int visibleIndex = 0;
            for (int i = 0; i < chipRow.getChildCount(); i++) {
                View chip = chipRow.getChildAt(i);
                if (chip == null) continue;
                ViewGroup.LayoutParams rawLp = chip.getLayoutParams();
                if (rawLp == null) continue;
                rawLp.width = slotWidth;
                if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) rawLp;
                    mlp.setMargins(visibleIndex == 0 ? 0 : gap, mlp.topMargin, 0, mlp.bottomMargin);
                }
                chip.setLayoutParams(rawLp);
                if (chip.getVisibility() != View.GONE) visibleIndex++;
            }
            chipRow.setPadding(0, 0, gap, 0);
        }
        trimFileTypeFilterEdge();
    }

    private void snapFileTypeFilterToSlot() {
        if (!(activity.fileTypeFilterScroll instanceof HorizontalScrollView)) return;
        HorizontalScrollView scroll = (HorizontalScrollView) activity.fileTypeFilterScroll;
        if (scroll.getChildCount() == 0 || scroll.getWidth() <= 0) return;
        int slotWidth = Math.max(1, activity.fileTypeFilterStepPx > 0 ? activity.fileTypeFilterStepPx : scroll.getWidth() / 5);
        int maxScroll = Math.max(0, scroll.getChildAt(0).getWidth() - scroll.getWidth());
        if (maxScroll <= 1) {
            scroll.smoothScrollTo(0, 0);
            return;
        }
        int current = scroll.getScrollX();
        int target = Math.round(current / (float) slotWidth) * slotWidth;
        target = Math.max(0, Math.min(target, maxScroll));
        if (Math.abs(target - current) > 1) {
            scroll.smoothScrollTo(target, 0);
        }
    }

    private void trimFileTypeFilterEdge() {
        if (!(activity.fileTypeFilterScroll instanceof HorizontalScrollView)) return;
        HorizontalScrollView scroll = (HorizontalScrollView) activity.fileTypeFilterScroll;
        scroll.post(() -> {
            int maxScroll = 0;
            if (scroll.getChildCount() > 0) {
                maxScroll = Math.max(0, scroll.getChildAt(0).getWidth() - scroll.getWidth());
            }
            if (maxScroll <= 1) {
                scroll.scrollTo(0, 0);
            } else if (scroll.getScrollX() > maxScroll) {
                scroll.scrollTo(maxScroll, 0);
            }
        });
    }

    void updateFileSearchClearButtonVisibility() {
        if (activity.fileSearchClearButton == null) return;

        boolean hasTypedQuery = activity.fileSearchInput != null
                && activity.fileSearchInput.getText() != null
                && activity.fileSearchInput.getText().toString().trim().length() > 0;

        activity.fileSearchClearButton.setVisibility(hasTypedQuery ? View.VISIBLE : View.GONE);
    }

    private void setupFileTypeChip(TextView chip, int filter) {
        if (chip == null) return;
        chip.setOnClickListener(v -> {
            String query = activity.fileSearchInput != null
                    ? activity.fileSearchInput.getText().toString().trim()
                    : "";

            if (query.isEmpty() && (activity.homeMode || (activity.searchMode && activity.searchReturnToHome))) {
                activity.activeFileFilter = filter;
                clearSearchDebounce();
                activity.fileSearchGeneration.incrementAndGet();
                activity.searchMode = false;
                activity.homeMode = true;
                activity.searchReturnToHome = true;
                activity.searchReturnDirectory = null;
                setFileSearchLoading(false);
                updateFileSearchClearButtonVisibility();
                if (activity.recentSection != null) activity.recentSection.setVisibility(View.VISIBLE);
                if (activity.browserSection != null) activity.browserSection.setVisibility(View.GONE);
                activity.setPathBarVisible(false);
                if (activity.getSupportActionBar() != null) {
                    activity.getSupportActionBar().setTitle(R.string.app_name);
                }
                updateFileTypeChips();
                activity.loadRecentFiles();
                activity.updateMainOverflowButtonVisibility();
                activity.invalidateOptionsMenu();
                return;
            }

            if (filter == MainActivity.FILTER_ALL && query.isEmpty()) {
                activity.activeFileFilter = MainActivity.FILTER_ALL;
                activity.fileTypeFilterActivatedDirectory = null;
                updateFileTypeChips();
                restoreAllFilterLocation();
                return;
            }

            captureFilterReturnLocationIfNeeded();
            if (activity.activeFileFilter == MainActivity.FILTER_ALL && filter != MainActivity.FILTER_ALL) {
                activity.fileTypeFilterActivatedDirectory = !activity.homeMode && activity.currentDirectory != null
                        ? activity.currentDirectory
                        : null;
            }
            activity.activeFileFilter = filter;
            updateFileTypeChips();
            runLiveFileSearchNow();
        });
    }

    private void captureFilterReturnLocationIfNeeded() {
        if (activity.searchMode) return;
        boolean allStorage = shouldSearchAllStorage();
        activity.searchReturnToHome = allStorage;
        activity.searchReturnDirectory = allStorage ? null : activity.currentDirectory;
    }

    void restoreAllFilterLocation() {
        clearSearchDebounce();
        activity.fileSearchGeneration.incrementAndGet();
        setFileSearchLoading(false);
        updateFileSearchClearButtonVisibility();

        File visibleFolder = activity.currentDirectory;
        if (visibleFolder != null
                && visibleFolder.exists()
                && visibleFolder.isDirectory()
                && visibleFolder.canRead()
                && (!activity.searchReturnToHome || activity.searchMode || !activity.homeMode)) {
            if (activity.restoreCachedBrowseFolderStateForFilterReturn(visibleFolder)) {
                activity.rebuildDrawerStorageEntries();
                return;
            }
            activity.searchMode = false;
            activity.homeMode = false;
            activity.recentSection.setVisibility(View.GONE);
            activity.browserSection.setVisibility(View.VISIBLE);
            activity.setPathBarVisible(true);
            activity.loadDirectory(visibleFolder);
            activity.rebuildDrawerStorageEntries();
            activity.updateMainOverflowButtonVisibility();
            activity.invalidateOptionsMenu();
            return;
        }

        restorePreSearchLocation();
    }

    void updateFileTypeChips() {
        boolean hideImageChipInRecent = shouldHideImageChipInRecentList();
        if (hideImageChipInRecent && activity.activeFileFilter == MainActivity.FILTER_IMAGE) {
            activity.activeFileFilter = MainActivity.FILTER_ALL;
        }

        styleFileTypeChip(activity.filterAllChip, activity.activeFileFilter == MainActivity.FILTER_ALL);
        styleFileTypeChip(activity.filterGeneralChip, activity.activeFileFilter == MainActivity.FILTER_GENERAL);
        styleFileTypeChip(activity.filterTxtChip, activity.activeFileFilter == MainActivity.FILTER_TXT);
        styleFileTypeChip(activity.filterArchiveChip, activity.activeFileFilter == MainActivity.FILTER_ARCHIVE);
        styleFileTypeChip(activity.filterPdfChip, activity.activeFileFilter == MainActivity.FILTER_PDF);
        styleFileTypeChip(activity.filterEpubChip, activity.activeFileFilter == MainActivity.FILTER_EPUB);
        styleFileTypeChip(activity.filterWordChip, activity.activeFileFilter == MainActivity.FILTER_WORD);
        if (activity.filterImageChip != null) {
            activity.filterImageChip.setVisibility(hideImageChipInRecent ? View.GONE : View.VISIBLE);
        }
        styleFileTypeChip(activity.filterImageChip, activity.activeFileFilter == MainActivity.FILTER_IMAGE);
        applyFileTypeFilterSlotWidths();
    }

    private boolean shouldHideImageChipInRecentList() {
        return activity.homeMode && !activity.searchMode;
    }

    void resetFileFilterForNavigation() {
        activity.activeFileFilter = MainActivity.FILTER_ALL;
        activity.fileTypeFilterActivatedDirectory = null;

        if (activity.fileSearchInput != null && activity.fileSearchInput.length() > 0) {
            activity.fileSearchInput.setText("");
        }

        clearSearchDebounce();
        activity.fileSearchGeneration.incrementAndGet();

        setFileSearchLoading(false);
        if (activity.fileSearchClearButton != null) {
            activity.fileSearchClearButton.setVisibility(View.GONE);
        }

        activity.searchReturnToHome = true;
        activity.searchReturnDirectory = null;
        updateFileTypeChips();
    }

    private void styleFileTypeChip(TextView chip, boolean selected) {
        if (chip == null) return;
        boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        int bg = selected
                ? (activity.prefs != null ? activity.prefs.getMainFileTypeChipSelectedColor(activity) : (dark ? Color.rgb(72, 72, 72) : Color.rgb(32, 33, 36)))
                : (activity.prefs != null ? activity.prefs.getMainFileTypeChipColor(activity) : (dark ? Color.rgb(30, 30, 30) : Color.rgb(238, 238, 238)));
        int stroke = selected
                ? (activity.prefs != null ? activity.prefs.getMainControlColor(activity) : (dark ? Color.rgb(220, 220, 220) : Color.rgb(32, 33, 36)))
                : (activity.prefs != null ? activity.prefs.getMainOutlineColor(activity) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(190, 190, 190)));
        int fg = selected
                ? UiColorUtils.readableChipTextColorForBackground(bg)
                : (activity.prefs != null ? activity.prefs.getMainTextColor(activity) : (dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36)));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(activity.dpToPx(15));
        shape.setStroke(Math.max(1, activity.dpToPx(1)), stroke);
        chip.setBackground(shape);
        chip.setTextColor(fg);
    }

    void updateSearchScopeButton() {
        ImageButton button = activity.fileSearchScopeButton;
        if (button == null) return;

        boolean allFolders = activity.fileSearchAllFolders;
        button.setContentDescription(activity.getString(allFolders
                ? R.string.search_scope_all_folders
                : R.string.search_scope_current_folder_only));
        int iconRes = allFolders ? R.drawable.ic_search_scope_all : R.drawable.ic_folder;
        Drawable icon = ContextCompat.getDrawable(activity, iconRes);
        if (icon != null) {
            Drawable wrapped = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(wrapped, searchScopeIconColor());
            button.setImageDrawable(wrapped);
        } else {
            button.setImageResource(iconRes);
        }
        button.setSelected(false);
    }

    private int searchScopeIconColor() {
        boolean dark = activity.prefs == null || activity.prefs.shouldUseDarkColors(activity);
        return activity.prefs != null
                ? activity.prefs.getMainSubTextColor(activity)
                : (dark ? Color.rgb(176, 176, 176) : Color.rgb(95, 99, 104));
    }

    private boolean shouldSearchAllStorage() {
        return activity.fileSearchAllFolders || activity.homeMode || activity.currentDirectory == null;
    }

    private void setFileSearchLoading(boolean loading) {
        if (activity.fileSearchProgress != null) {
            activity.fileSearchProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    void clearSearchDebounce() {
        if (activity.pendingFileSearchRunnable != null) {
            activity.fileSearchHandler.removeCallbacks(activity.pendingFileSearchRunnable);
            activity.pendingFileSearchRunnable = null;
        }
    }

    private void scheduleLiveFileSearch() {
        if (activity.activityDestroyed) return;
        clearSearchDebounce();
        activity.pendingFileSearchRunnable = this::runLiveFileSearchNow;
        activity.fileSearchHandler.postDelayed(activity.pendingFileSearchRunnable, 180);
    }

    void runLiveFileSearchNow() {
        clearSearchDebounce();
        if (activity.activityDestroyed || activity.fileSearchInput == null) return;
        String query = activity.fileSearchInput.getText().toString().trim();

        if (query.isEmpty() && activity.homeMode) {
            activity.fileSearchGeneration.incrementAndGet();
            setFileSearchLoading(false);
            updateFileSearchClearButtonVisibility();
            if (activity.activeFileFilter == MainActivity.FILTER_ALL) {
                if (activity.searchMode) restorePreSearchLocation();
            } else {
                activity.searchMode = false;
                activity.loadRecentFiles();
            }
            return;
        }

        boolean allStorage = shouldSearchAllStorage();
        if (query.isEmpty()
                && activity.activeFileFilter == MainActivity.FILTER_ALL
                && (!allStorage || activity.homeMode)) {
            activity.fileSearchGeneration.incrementAndGet();
            updateFileSearchClearButtonVisibility();
            if (activity.searchMode && !allStorage && activity.currentDirectory != null) {
                setFileSearchLoading(true);
                activity.showBrowseMode(activity.currentDirectory);
            } else {
                setFileSearchLoading(false);
                if (activity.searchMode) restorePreSearchLocation();
            }
            return;
        }

        if (query.isEmpty()
                && !allStorage
                && activity.activeFileFilter != MainActivity.FILTER_ALL) {
            showCurrentFolderFilterResults();
            return;
        }
        if (!activity.searchMode) {
            activity.searchReturnToHome = allStorage;
            activity.searchReturnDirectory = allStorage ? null : activity.currentDirectory;
        }
        startFileSearch(query, allStorage);
    }

    void showCurrentFolderFilterResults() {
        showCurrentFolderFilterResults(activity.currentDirectory);
    }

    void showCurrentFolderFilterResults(@Nullable File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            startFileSearch("", false);
            return;
        }

        activity.cancelPendingFolderLoad();
        final int generation = activity.fileSearchGeneration.incrementAndGet();
        final int filter = activity.activeFileFilter;
        final boolean showHidden = activity.prefs == null || activity.prefs.getShowHiddenFiles();
        final int sortMode = activity.prefs != null
                ? activity.prefs.getSortMode()
                : com.textview.reader.util.PrefsManager.SORT_NAME_ASC;
        setFileSearchLoading(true);
        updateFileSearchClearButtonVisibility();

        activity.fileSearchExecutor.execute(() -> {
            List<File> results = new ArrayList<>();
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (activity.activityDestroyed || generation != activity.fileSearchGeneration.get()) return;
                    if (child == null) continue;
                    String name = child.getName();
                    if (!showHidden && name.startsWith(".")) continue;
                    if (child.isDirectory() || (child.isFile() && FileTypeFilter.matches(name, filter))) {
                        results.add(child);
                    }
                }
            }
            com.textview.reader.util.FileSortUtils.sortMainFiles(activity, results, sortMode);

            activity.runOnUiThread(() -> {
                if (activity.activityDestroyed || generation != activity.fileSearchGeneration.get()) return;
                activity.exitFileSelectionMode(false);
                activity.searchMode = true;
                activity.homeMode = false;
                updateFileTypeChips();
                activity.currentDirectory = dir;
                activity.searchReturnToHome = false;
                activity.searchReturnDirectory = dir;
                activity.recentSection.setVisibility(View.GONE);
                activity.browserSection.setVisibility(View.VISIBLE);
                activity.setPathBarVisible(true);
                if (activity.pathText != null) activity.pathText.setText(dir.getAbsolutePath());
                if (activity.getSupportActionBar() != null) {
                    activity.getSupportActionBar().setTitle(filterLabelFor(filter));
                }
                activity.fileAdapter.setShowFilePath(false);
                activity.fileAdapter.setSortModeSilently(sortMode);
                activity.fileAdapter.setFilesFastPresorted(results);
                activity.scrollListToTop(activity.fileRecyclerView);
                activity.emptyText.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
                if (results.isEmpty()) activity.emptyText.setText(activity.getString(R.string.no_file_search_results));
                activity.updateParentFolderButtonState();
                activity.updateMainOverflowButtonVisibility();
                activity.invalidateOptionsMenu();
                setFileSearchLoading(false);
            });
        });
    }

    void restorePreSearchLocation() {
        activity.fileSearchGeneration.incrementAndGet();
        setFileSearchLoading(false);
        activity.searchMode = false;
        activity.activeFileFilter = MainActivity.FILTER_ALL;
        activity.fileTypeFilterActivatedDirectory = null;
        updateFileTypeChips();

        File target = activity.searchReturnDirectory;
        if (!activity.searchReturnToHome
                && target != null
                && target.exists()
                && target.isDirectory()
                && target.canRead()) {
            activity.showBrowseMode(target);
        } else {
            activity.showHomeMode();
        }
    }

    private void startFileSearch(@NonNull String query, boolean allStorage) {
        activity.cancelPendingFolderLoad();
        final int generation = activity.fileSearchGeneration.incrementAndGet();
        setFileSearchLoading(true);
        if (activity.fileSearchClearButton != null) {
            activity.fileSearchClearButton.setEnabled(true);
        }
        updateFileSearchClearButtonVisibility();

        List<File> roots = searchRoots.build(allStorage);
        final int filter = activity.activeFileFilter;
        final int sortMode = activity.prefs != null
                ? activity.prefs.getSortMode()
                : com.textview.reader.util.PrefsManager.SORT_NAME_ASC;
        prepareFileSearchResultsView(query);
        activity.fileSearchExecutor.execute(() -> {
            if (activity.activityDestroyed || generation != activity.fileSearchGeneration.get()) return;
            List<File> results = new ArrayList<>();
            boolean showHidden = activity.prefs == null || activity.prefs.getShowHiddenFiles();
            searchWalker.search(query, roots, filter, showHidden, generation, results);
            if (activity.activityDestroyed || generation != activity.fileSearchGeneration.get()) return;

            com.textview.reader.util.FileSortUtils.sortMainFiles(activity, results, sortMode);
            activity.runOnUiThread(() -> {
                if (activity.activityDestroyed || generation != activity.fileSearchGeneration.get()) return;
                setFileSearchLoading(false);
                showFileSearchResults(query, results, true);
            });
        });
    }

    private void handleSearchProgress(@NonNull String query,
                                      int generation,
                                      @NonNull List<File> snapshot) {
        activity.runOnUiThread(() -> {
            if (activity.activityDestroyed
                    || generation != activity.fileSearchGeneration.get()
                    || !activity.searchMode) return;
            showFileSearchResults(query, snapshot, false);
        });
    }

    private String filterLabelFor(int filter) {
        return activity.getString(FileTypeFilter.labelResId(filter));
    }

    private void prepareFileSearchResultsView(@NonNull String query) {
        activity.exitFileSelectionMode(false);
        activity.searchMode = true;
        activity.homeMode = false;
        updateFileTypeChips();
        activity.recentSection.setVisibility(View.GONE);
        activity.browserSection.setVisibility(View.VISIBLE);
        activity.setPathBarVisible(true);
        activity.pathText.setText(activity.getString(R.string.file_search_results_for,
                query.isEmpty() ? filterLabelFor(activity.activeFileFilter) : query,
                0));
        if (activity.getSupportActionBar() != null) activity.getSupportActionBar().setTitle(R.string.file_search);
        updateFileSearchClearButtonVisibility();

        activity.fileAdapter.setShowFilePath(true);
        activity.fileAdapter.setSortModeSilently(activity.prefs != null
                ? activity.prefs.getSortMode()
                : com.textview.reader.util.PrefsManager.SORT_NAME_ASC);
        activity.fileAdapter.setFilesFastPresorted(new ArrayList<>());
        if (activity.emptyText != null) {
            activity.emptyText.setVisibility(View.VISIBLE);
            activity.emptyText.setText(activity.getString(R.string.loading));
        }
        activity.updateMainOverflowButtonVisibility();
        activity.invalidateOptionsMenu();
    }

    private void showFileSearchResults(@NonNull String query,
                                       @NonNull List<File> results,
                                       boolean finalResult) {
        if (!activity.searchMode) prepareFileSearchResultsView(query);
        activity.pathText.setText(activity.getString(R.string.file_search_results_for,
                query.isEmpty() ? filterLabelFor(activity.activeFileFilter) : query,
                results.size()));
        activity.fileAdapter.setShowFilePath(true);
        activity.fileAdapter.setFilesFastPresorted(results);
        if (finalResult) activity.scrollListToTop(activity.fileRecyclerView);
        activity.emptyText.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        if (results.isEmpty()) {
            activity.emptyText.setText(activity.getString(finalResult
                    ? R.string.no_file_search_results
                    : R.string.loading));
        }
        updateFileSearchClearButtonVisibility();
        activity.updateMainOverflowButtonVisibility();
        activity.invalidateOptionsMenu();
    }

}
