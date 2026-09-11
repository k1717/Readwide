package com.readwide.manager;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.adapter.FileAdapter;
import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.EdgeToEdgeUtil;

final class MainActivityStartupController {
    private static final int FILE_TILE_SPAN_COUNT = 2;

    private final MainActivity activity;

    MainActivityStartupController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void onCreateAfterSuper() {
        activity.setContentView(R.layout.activity_main);
        activity.drawerSwipeTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { activity.handleMainBackPressed(); }
        });

        EdgeToEdgeUtil.applyStandardInsets(
                activity,
                activity.findViewById(R.id.main_root),
                activity.findViewById(R.id.main_appbar),
                activity.findViewById(R.id.file_search_bar));

        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        activity.mainToolbar = toolbar;
        activity.setSupportActionBar(toolbar);

        bindDrawer(toolbar);
        bindMainViews();
        bindFileLists();

        activity.bookmarkManager = BookmarkManager.getInstance(activity);
        activity.setupRecentHeaderActions();
        activity.setupDrawerStorageList();
        activity.setupDrawerBottomActions();
        activity.setupFileSearch();
        activity.applyMainReadableTheme(toolbar);

        Intent intent = activity.getIntent();
        Uri viewUri = (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()))
                ? intent.getData() : null;

        if (activity.prefs.isLockEnabled() && !activity.lockChecked) {
            // App lock gates everything, including an external "open with Readwide".
            // Launch the lock and return WITHOUT preparing the home/recent UI, so it is
            // not built (and can't flash or appear in the recents snapshot) behind the
            // lock screen. The unlock callback opens the pending file or initialises the
            // main UI.
            activity.pendingExternalUri = viewUri;
            Intent lockIntent = new Intent(activity, LockActivity.class);
            lockIntent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_UNLOCK);
            activity.lockLauncher.launch(lockIntent);
            return;
        }
        if (viewUri != null) {
            activity.openFileFromUri(viewUri);
            return;
        }
        activity.checkPermissionsAndInit();
        activity.showInitialMainMode();
    }

    private void bindDrawer(Toolbar toolbar) {
        activity.drawerLayout = activity.findViewById(R.id.drawer_layout);
        activity.drawerToggle = new ActionBarDrawerToggle(
                activity,
                activity.drawerLayout,
                toolbar,
                R.string.drawer_open,
                R.string.drawer_close);
        activity.drawerLayout.addDrawerListener(activity.drawerToggle);
        activity.drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                activity.drawerSlideOffset = slideOffset;
            }

            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                activity.drawerSlideOffset = 1f;
                activity.drawerClosePartialOnRelease = false;
                activity.drawerForceSettling = false;
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                activity.drawerSlideOffset = 0f;
                activity.drawerForceSettling = false;
                activity.drawerClosePartialOnRelease = false;
                activity.consumePendingDrawerNavigation();
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                if (newState == DrawerLayout.STATE_IDLE) {
                    activity.settleHalfOpenedDrawer();
                }
            }
        });
        activity.drawerToggle.syncState();
        activity.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START);
        activity.installReliableDrawerEdgeDrag();
        activity.installToolbarMenuButton(toolbar);
    }

    private void bindMainViews() {
        activity.fileRecyclerView = activity.findViewById(R.id.file_list);
        activity.fileListRefresh = activity.findViewById(R.id.file_list_refresh);
        if (activity.fileListRefresh != null) {
            activity.fileListRefresh.setOnRefreshListener(activity::onFileListPullToRefresh);
            if (activity.prefs != null) {
                activity.fileListRefresh.setProgressBackgroundColorSchemeColor(activity.prefs.getMainBgColor(activity));
                activity.fileListRefresh.setColorSchemeColors(activity.prefs.getMainTextColor(activity));
            }
        }
        activity.pathBar = activity.findViewById(R.id.path_bar);
        activity.pathText = activity.findViewById(R.id.current_path);
        activity.parentFolderButton = activity.findViewById(R.id.parent_folder_button);
        activity.setupParentFolderButton();
        activity.emptyText = activity.findViewById(R.id.empty_text);
        activity.fileFastScrollRail = activity.findViewById(R.id.file_fast_scroll_rail);
        activity.fileFastScrollThumb = activity.findViewById(R.id.file_fast_scroll_thumb);
        activity.recentFastScrollRail = activity.findViewById(R.id.recent_fast_scroll_rail);
        activity.recentFastScrollThumb = activity.findViewById(R.id.recent_fast_scroll_thumb);
        activity.recentSection = activity.findViewById(R.id.recent_section);
        activity.browserSection = activity.findViewById(R.id.main_content_container);
        activity.recentRecyclerView = activity.findViewById(R.id.recent_list);
        activity.recentEmptyText = activity.findViewById(R.id.recent_empty_text);
        activity.recentClearAllButton = activity.findViewById(R.id.recent_clear_all);
    }

    private void bindFileLists() {
        boolean tileMode = activity.prefs.getFileDisplayMode()
                == com.readwide.manager.util.PrefsManager.FILE_DISPLAY_TILES;

        activity.fileAdapter = new FileAdapter(activity);
        activity.fileAdapter.setListener(activity);
        activity.fileAdapter.setSortMode(activity.prefs.getSortMode());
        activity.fileAdapter.setShowThumbnails(activity.prefs.getFileThumbnailsEnabled());
        activity.fileAdapter.setTileMode(tileMode);
        activity.fileRecyclerView.setLayoutManager(
                createFileLayoutManager(activity.fileRecyclerView, tileMode));
        activity.fileRecyclerView.setItemAnimator(null);
        activity.fileRecyclerView.setAdapter(activity.fileAdapter);
        installFastScrollIfReady(
                activity.fileRecyclerView,
                activity.fileAdapter,
                activity.fileFastScrollRail,
                activity.fileFastScrollThumb);

        activity.recentAdapter = new FileAdapter(activity);
        activity.recentAdapter.setListener(activity);
        activity.recentAdapter.setSortEnabled(false);
        activity.recentAdapter.setShowReadingProgress(true);
        activity.recentAdapter.setShowThumbnails(activity.prefs.getFileThumbnailsEnabled());
        activity.recentAdapter.setTileMode(tileMode);
        activity.recentRecyclerView.setLayoutManager(
                createFileLayoutManager(activity.recentRecyclerView, tileMode));
        activity.recentRecyclerView.setItemAnimator(null);
        activity.recentRecyclerView.setAdapter(activity.recentAdapter);
        installFastScrollIfReady(
                activity.recentRecyclerView,
                activity.recentAdapter,
                activity.recentFastScrollRail,
                activity.recentFastScrollThumb);
        activity.attachRecentSwipeToDismiss();
    }

    void applyFileDisplayMode(int mode) {
        boolean tileMode = mode == com.readwide.manager.util.PrefsManager.FILE_DISPLAY_TILES;
        applyFileDisplayMode(activity.fileRecyclerView, activity.fileAdapter, tileMode);
        applyFileDisplayMode(activity.recentRecyclerView, activity.recentAdapter, tileMode);
    }

    void refreshFileDisplayLayoutForConfiguration() {
        if (activity.prefs == null) return;
        int mode = activity.prefs.getFileDisplayMode();
        // onConfigurationChanged() can run before RecyclerView reports its new
        // width. Recalculate grid spans after the new layout pass.
        if (activity.fileRecyclerView != null) {
            activity.fileRecyclerView.post(() -> applyFileDisplayMode(mode));
        } else {
            applyFileDisplayMode(mode);
        }
    }

    private void applyFileDisplayMode(RecyclerView recyclerView,
                                      FileAdapter adapter,
                                      boolean tileMode) {
        if (recyclerView == null || adapter == null) return;

        RecyclerView.LayoutManager oldManager = recyclerView.getLayoutManager();
        boolean oldTile = oldManager instanceof GridLayoutManager;
        int desiredSpan = tileMode ? resolveTileSpanCount() : 1;
        boolean sameManager = oldManager != null && oldTile == tileMode;
        if (tileMode && oldManager instanceof GridLayoutManager) {
            sameManager = ((GridLayoutManager) oldManager).getSpanCount() == desiredSpan;
        }
        if (sameManager && adapter.isTileMode() == tileMode) return;

        int firstPosition = RecyclerView.NO_POSITION;
        int firstOffset = 0;
        if (oldManager instanceof LinearLayoutManager) {
            LinearLayoutManager linear = (LinearLayoutManager) oldManager;
            firstPosition = linear.findFirstVisibleItemPosition();
            View firstView = firstPosition != RecyclerView.NO_POSITION
                    ? linear.findViewByPosition(firstPosition)
                    : null;
            if (firstView != null) {
                firstOffset = firstView.getTop() - recyclerView.getPaddingTop();
            }
        }

        recyclerView.stopScroll();
        adapter.setTileMode(tileMode);
        LinearLayoutManager newManager = (LinearLayoutManager) createFileLayoutManager(
                recyclerView,
                tileMode);
        recyclerView.setLayoutManager(newManager);
        if (firstPosition != RecyclerView.NO_POSITION && adapter.getItemCount() > 0) {
            newManager.scrollToPositionWithOffset(
                    Math.min(firstPosition, adapter.getItemCount() - 1),
                    firstOffset);
        }
        recyclerView.requestLayout();
    }

    @NonNull
    private RecyclerView.LayoutManager createFileLayoutManager(RecyclerView recyclerView,
                                                               boolean tileMode) {
        if (!tileMode) return new LinearLayoutManager(activity);
        return new GridLayoutManager(activity, resolveTileSpanCount());
    }

    private int resolveTileSpanCount() {
        // Keep browser and Recent tiles at a predictable two-column width.
        // The previous density-based calculation produced three narrow columns
        // on ordinary phones, which reduced cover and filename readability.
        return FILE_TILE_SPAN_COUNT;
    }

    private void installFastScrollIfReady(RecyclerView recyclerView,
                                          RecyclerView.Adapter<?> adapter,
                                          View rail,
                                          View thumb) {
        if (recyclerView == null || adapter == null || rail == null || thumb == null) return;
        new MainFileFastScrollController(recyclerView, adapter, rail, thumb).install();
    }
}
