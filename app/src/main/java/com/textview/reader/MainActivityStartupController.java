package com.textview.reader;

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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.adapter.FileAdapter;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.EdgeToEdgeUtil;

final class MainActivityStartupController {
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
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) {
                activity.openFileFromUri(uri);
                return;
            }
        }

        if (activity.prefs.isLockEnabled() && !activity.lockChecked) {
            Intent lockIntent = new Intent(activity, LockActivity.class);
            lockIntent.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_UNLOCK);
            activity.lockLauncher.launch(lockIntent);
        } else {
            activity.checkPermissionsAndInit();
        }

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
        activity.fileAdapter = new FileAdapter(activity);
        activity.fileAdapter.setListener(activity);
        activity.fileAdapter.setSortMode(activity.prefs.getSortMode());
        activity.fileRecyclerView.setLayoutManager(new LinearLayoutManager(activity));
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
        activity.recentRecyclerView.setLayoutManager(new LinearLayoutManager(activity));
        activity.recentRecyclerView.setItemAnimator(null);
        activity.recentRecyclerView.setAdapter(activity.recentAdapter);
        installFastScrollIfReady(
                activity.recentRecyclerView,
                activity.recentAdapter,
                activity.recentFastScrollRail,
                activity.recentFastScrollThumb);
    }

    private void installFastScrollIfReady(RecyclerView recyclerView,
                                          RecyclerView.Adapter<?> adapter,
                                          View rail,
                                          View thumb) {
        if (recyclerView == null || adapter == null || rail == null || thumb == null) return;
        new MainFileFastScrollController(recyclerView, adapter, rail, thumb).install();
    }
}
