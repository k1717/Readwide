package com.readwide.manager;

import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.model.FileListItem;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class MainRecentFilesController {
    // The recent list shows up to DISPLAY_LIMIT rows. The full read-history is
    // loaded with no scan cap and held in memory so the home-screen search can
    // filter across every read file, including ones past the display cap.
    private static final int DISPLAY_LIMIT = 5000;

    // A recent row must be dragged past this fraction of its width to be
    // dismissed; a shorter swipe snaps the card back. Velocity-triggered
    // dismissal is disabled (see getSwipeEscapeVelocity), so only crossing this
    // position threshold commits, mirroring the drawer's move-then-commit feel.
    private static final float SWIPE_DISMISS_FRACTION = 0.45f;

    // A dismiss swipe must be clearly horizontal before it can start: the
    // finger's total horizontal travel since touch-down has to be at least
    // this multiple of its vertical travel (2x is about 26.6 degrees off the
    // horizontal). Steeper, diagonal drags never grab the row, so a sloppy
    // vertical scroll cannot slide a recent card sideways. Once a swipe has
    // legitimately started, later finger wobble does not cancel it because
    // ItemTouchHelper only consults getSwipeDirs before selection.
    private static final float SWIPE_HORIZONTAL_DOMINANCE = 2f;

    private final MainActivity activity;

    // Cumulative finger travel of the current touch gesture on the recent
    // list, recorded by an observing OnItemTouchListener registered before the
    // ItemTouchHelper so it sees every event first.
    private float swipeGestureDownX;
    private float swipeGestureDownY;
    private float swipeGestureDx;
    private float swipeGestureDy;

    // Full visible recent list (the search corpus) and the states behind it,
    // kept in sync with the store so search and swipe-delete operate on
    // everything, not just the displayed rows.
    private List<FileListItem> fullRecentItems = new ArrayList<>();
    private List<ReaderState> fullRecentStates = new ArrayList<>();

    MainRecentFilesController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void resetRecyclerBeforeReload() {
        if (activity.recentRecyclerView == null) return;
        activity.recentRecyclerView.stopScroll();
        activity.recentRecyclerView.clearAnimation();
        RecyclerView.ItemAnimator animator = activity.recentRecyclerView.getItemAnimator();
        if (animator != null) animator.endAnimations();
    }

    void loadRecentFiles() {
        loadRecentFiles(false);
    }

    void loadRecentFiles(boolean preserveScroll) {
        preserveScrollOnApply = preserveScroll;
        if (preserveScroll) {
            captureRecentScrollPosition();
        } else {
            savedFirstVisiblePosition = RecyclerView.NO_POSITION;
        }
        resetRecyclerBeforeReload();
        if (activity.bookmarkManager == null) {
            applyRecentFiles(new ArrayList<>(), new ArrayList<>());
            return;
        }
        final long token = ++recentLoadToken;
        activity.executeFolderBackgroundTask(() -> {
            // Load the whole read-history (no scan cap). visibleRecentFileFor
            // still drops missing/image/preview-cache entries, so this is a
            // single existence pass over the store; the result is cached in
            // fullRecentItems for search until the next reload.
            List<ReaderState> recent = activity.bookmarkManager.getRecentFiles(Integer.MAX_VALUE);
            List<File> visibleFiles = new ArrayList<>();
            for (ReaderState state : recent) {
                File file = visibleRecentFileFor(state);
                if (file != null) visibleFiles.add(file);
            }
            final java.util.List<FileListItem> allItems = FileListItem.fromList(visibleFiles);
            activity.runOnUiThread(() -> {
                // Drop stale results if another reload started or the activity is gone.
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (token != recentLoadToken) return;
                fullRecentStates = recent;
                fullRecentItems = allItems;
                // The live search box is authoritative: a reload while the home
                // search has text (e.g. file-type filter change) re-applies the
                // filter; otherwise show the capped display. Reading the box here
                // avoids any stale search-state after navigation.
                String liveQuery = activity.homeMode ? currentSearchBoxQuery() : "";
                if (!liveQuery.isEmpty()) {
                    applyRecentSearchInternal(liveQuery);
                } else {
                    hideRecentSearchBanner();
                    applyRecentFiles(recent, displayItems(allItems));
                }
            });
        });
    }

    private volatile long recentLoadToken = 0;

    private int savedFirstVisiblePosition = RecyclerView.NO_POSITION;
    private int savedFirstVisibleOffset = 0;
    private boolean preserveScrollOnApply = false;

    private void captureRecentScrollPosition() {
        savedFirstVisiblePosition = RecyclerView.NO_POSITION;
        savedFirstVisibleOffset = 0;
        if (activity.recentRecyclerView == null) return;
        RecyclerView.LayoutManager lm = activity.recentRecyclerView.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            LinearLayoutManager llm = (LinearLayoutManager) lm;
            int pos = llm.findFirstVisibleItemPosition();
            if (pos != RecyclerView.NO_POSITION) {
                savedFirstVisiblePosition = pos;
                View first = llm.findViewByPosition(pos);
                savedFirstVisibleOffset = first != null ? first.getTop() : 0;
            }
        }
    }

    // Re-anchor near the row the user was on before opening a file. The opened file
    // moves to the top of the recent list, so the same offset lands within a row of
    // the prior spot rather than snapping to the very top.
    private void restoreRecentScrollPosition(int itemCount) {
        final int pos = savedFirstVisiblePosition;
        final int offset = savedFirstVisibleOffset;
        savedFirstVisiblePosition = RecyclerView.NO_POSITION;
        if (activity.recentRecyclerView == null || pos < 0 || itemCount <= 0) return;
        final RecyclerView rv = activity.recentRecyclerView;
        rv.post(() -> {
            RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                int clamped = Math.min(pos, itemCount - 1);
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(clamped, offset);
            }
        });
    }

    private void applyRecentFiles(@NonNull List<ReaderState> recent,
                                  @NonNull List<FileListItem> recentItems) {
        if (activity.recentAdapter != null) {
            activity.recentAdapter.setReadingProgressStates(recent);
            int recentSort = activity.prefs != null
                    ? activity.prefs.getRecentSortMode()
                    : PrefsManager.SORT_RECENT_READ;
            if (recentSort == PrefsManager.SORT_RECENT_READ) {
                // BookmarkManager already returns newest first. Avoid DiffUtil
                // holder reuse here because recent rows carry progress badges.
                activity.recentAdapter.setSortEnabled(false);
                activity.recentAdapter.setItemsFastPresorted(recentItems);
            } else {
                activity.recentAdapter.setSortEnabled(true);
                activity.recentAdapter.setSortMode(recentSort);
                activity.recentAdapter.setItems(recentItems);
            }
            activity.recentAdapter.refreshReadingProgress();
            if (preserveScrollOnApply && savedFirstVisiblePosition != RecyclerView.NO_POSITION) {
                restoreRecentScrollPosition(recentItems.size());
            } else {
                activity.scrollListToTop(activity.recentRecyclerView);
            }
        }
        if (activity.recentEmptyText != null) {
            activity.recentEmptyText.setVisibility(recentItems.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (activity.recentClearAllButton != null) {
            boolean hasAnyRecent = activity.bookmarkManager != null && activity.bookmarkManager.hasRecentFiles();
            activity.recentClearAllButton.setVisibility(hasAnyRecent ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ---- Recent-list display cap + in-memory search over the full history ----

    private List<FileListItem> displayItems(@NonNull List<FileListItem> all) {
        if (all.size() <= DISPLAY_LIMIT) return all;
        return new ArrayList<>(all.subList(0, DISPLAY_LIMIT));
    }

    private String currentSearchBoxQuery() {
        if (activity.fileSearchInput == null || activity.fileSearchInput.getText() == null) {
            return "";
        }
        return activity.fileSearchInput.getText().toString().trim();
    }

    // Filter the recent read-history by file name. Driven by the home-screen
    // search box, which intentionally does not walk the filesystem. Matches span
    // the full loaded history, so files past the display cap are still found.
    void applyRecentSearch(@Nullable String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            clearRecentSearch();
            return;
        }
        applyRecentSearchInternal(q);
    }

    private void applyRecentSearchInternal(@NonNull String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        List<FileListItem> matches = new ArrayList<>();
        for (FileListItem item : fullRecentItems) {
            if (item.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                matches.add(item);
            }
        }
        showRecentSearchBanner(query, matches.size());
        preserveScrollOnApply = false;
        applyRecentFiles(fullRecentStates, matches);
    }

    // Leave recent search and restore the capped display.
    void clearRecentSearch() {
        hideRecentSearchBanner();
        preserveScrollOnApply = false;
        applyRecentFiles(fullRecentStates, displayItems(fullRecentItems));
    }

    // While searching, a banner below the "Recently Read" header shows
    // "Search: <query> (<count>)" via the shared file_search_results_for string.
    // The count is the number of matches across the full (unlimited) history.
    private void showRecentSearchBanner(@NonNull String query, int count) {
        TextView banner = activity.findViewById(R.id.recent_search_banner);
        if (banner != null) {
            banner.setText(activity.getString(R.string.file_search_results_for, query, count));
            banner.setVisibility(View.VISIBLE);
        }
    }

    private void hideRecentSearchBanner() {
        TextView banner = activity.findViewById(R.id.recent_search_banner);
        if (banner != null) {
            banner.setVisibility(View.GONE);
        }
    }

    // ---- Swipe-left to dismiss a single recent row (full delete of its state) ----

    void attachSwipeToDismiss() {
        if (activity.recentRecyclerView == null) return;
        // Observe-only touch listener (always returns false) that tracks the
        // gesture's travel from ACTION_DOWN. Registered before the
        // ItemTouchHelper attaches its own listener, so the travel is up to
        // date whenever getSwipeDirs is consulted for swipe selection.
        activity.recentRecyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    swipeGestureDownX = e.getX();
                    swipeGestureDownY = e.getY();
                    swipeGestureDx = 0f;
                    swipeGestureDy = 0f;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    swipeGestureDx = e.getX() - swipeGestureDownX;
                    swipeGestureDy = e.getY() - swipeGestureDownY;
                }
                return false;
            }
        });
        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback callback =
                new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                        0, androidx.recyclerview.widget.ItemTouchHelper.LEFT) {
                    @Override
                    public boolean onMove(@NonNull RecyclerView rv,
                            @NonNull RecyclerView.ViewHolder vh,
                            @NonNull RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public int getSwipeDirs(@NonNull RecyclerView rv,
                            @NonNull RecyclerView.ViewHolder vh) {
                        // No row deletion mid multi-select; a swipe there is
                        // ambiguous against selection toggling.
                        if (activity.fileSelectionMode) return 0;
                        // Diagonal drags never start a dismiss: require the
                        // gesture so far to be clearly horizontal.
                        if (!isSwipeGestureHorizontal()) return 0;
                        return super.getSwipeDirs(rv, vh);
                    }

                    @Override
                    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) {
                        // Commit a dismiss only after the card passes 45% of its
                        // width; below that the card slides back to rest.
                        return SWIPE_DISMISS_FRACTION;
                    }

                    @Override
                    public float getSwipeEscapeVelocity(float defaultValue) {
                        // Disable velocity-triggered dismissal so a fast flick that
                        // stops short of the threshold snaps back instead of
                        // deleting. Only the position threshold commits.
                        return Float.MAX_VALUE;
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                        removeRecentItemAt(vh.getBindingAdapterPosition());
                    }
                };
        new androidx.recyclerview.widget.ItemTouchHelper(callback)
                .attachToRecyclerView(activity.recentRecyclerView);
    }

    private boolean isSwipeGestureHorizontal() {
        float absDx = Math.abs(swipeGestureDx);
        float absDy = Math.abs(swipeGestureDy);
        return absDx >= absDy * SWIPE_HORIZONTAL_DOMINANCE;
    }

    private void removeRecentItemAt(int position) {
        if (activity.recentAdapter == null) return;
        if (position < 0 || position >= activity.recentAdapter.getItemCount()) return;
        FileListItem item = activity.recentAdapter.getItemAt(position);
        if (item == null) return;
        String path = item.getFile().getAbsolutePath();
        if (activity.bookmarkManager != null) {
            activity.bookmarkManager.deleteReadingState(path);
        }
        activity.recentAdapter.removeItemAt(position);
        // Keep the in-memory full list in sync so the row does not reappear via
        // search or a search-preserving reload.
        removeFromFullRecent(path);
        refreshRecentChromeAfterRemoval();
    }

    private void removeFromFullRecent(@NonNull String path) {
        for (int i = 0; i < fullRecentItems.size(); i++) {
            if (fullRecentItems.get(i).getFile().getAbsolutePath().equals(path)) {
                fullRecentItems.remove(i);
                break;
            }
        }
    }

    private void refreshRecentChromeAfterRemoval() {
        if (activity.recentEmptyText != null) {
            boolean empty = activity.recentAdapter == null
                    || activity.recentAdapter.getItemCount() == 0;
            activity.recentEmptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (activity.recentClearAllButton != null) {
            boolean hasAnyRecent = activity.bookmarkManager != null
                    && activity.bookmarkManager.hasRecentFiles();
            activity.recentClearAllButton.setVisibility(
                    hasAnyRecent ? View.VISIBLE : View.INVISIBLE);
        }
        // If a delete happened while the search banner is showing, keep its count
        // in sync with the now-smaller result list.
        String liveQuery = activity.homeMode ? currentSearchBoxQuery() : "";
        if (!liveQuery.isEmpty()) {
            int count = activity.recentAdapter == null ? 0 : activity.recentAdapter.getItemCount();
            showRecentSearchBanner(liveQuery, count);
        }
    }

    @Nullable
    private File visibleRecentFileFor(@Nullable ReaderState state) {
        String statePath = state == null ? null : state.getFilePath();
        if (statePath == null || statePath.trim().isEmpty()) return null;

        if (isArchivePreviewCacheStatePath(statePath)) {
            // Internal archive-preview files are temporary cache outputs. Keep
            // the host archive as Recent instead of ranking extracted cache files.
            activity.bookmarkManager.deleteReadingState(statePath);
            return null;
        }

        File file = new File(statePath);
        if (!file.exists()) return null;
        if ((activity.prefs == null || !activity.prefs.getShowHiddenFiles())
                && file.getName().startsWith(".")) {
            return null;
        }
        if (FileUtils.isImageFile(file.getName())) {
            // Images are viewable, but they should not occupy reading history.
            activity.bookmarkManager.deleteReadingState(file.getAbsolutePath());
            return null;
        }
        if (activity.activeFileFilter != MainActivity.FILTER_ALL
                && !activity.matchesActiveFileFilter(file.getName(), activity.activeFileFilter)) {
            return null;
        }
        return file;
    }

    private boolean isArchivePreviewCacheStatePath(@NonNull String path) {
        File previewRoot = new File(activity.getCacheDir(), "archive_preview");
        return isSameOrChildPath(path, previewRoot.getAbsolutePath());
    }

    private boolean isSameOrChildPath(@Nullable String candidatePath, @Nullable String rootPath) {
        return com.readwide.manager.util.FileUtils.isSameOrChildPath(candidatePath, rootPath);
    }
}
