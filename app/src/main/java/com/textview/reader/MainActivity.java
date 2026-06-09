package com.textview.reader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.adapter.DrawerEntryAdapter;
import com.textview.reader.adapter.FileAdapter;
import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.model.DrawerEntry;
import com.textview.reader.model.ReaderState;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.EdgeToEdgeUtil;
import com.textview.reader.util.FileClipboardController;
import com.textview.reader.util.FileOperationProgress;
import com.textview.reader.util.FileTypeFilter;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;

/**
 * Main entry. Two-mode UI:
 *
 * - HOME mode: Recent files only. The default state on launch.
 * - BROWSE mode: File browser at a particular folder, picked from
 *                the navigation drawer (Internal storage, External SD, Downloads, /storage).
 *
 * The drawer slides in from the left edge and contains the storage shortcuts at the
 * top and the toolbar's old action buttons (Open File, Bookmarks, Settings) at the bottom.
 */
public class MainActivity extends AppCompatActivity implements FileAdapter.OnFileClickListener {

    public static final String EXTRA_RETURN_TO_VIEWER = "return_to_viewer";
    public static final String EXTRA_START_DIRECTORY = "start_directory";
    private static final int PERMISSION_REQUEST_CODE = 100;

    DrawerLayout drawerLayout;
    ActionBarDrawerToggle drawerToggle;
    RecyclerView fileRecyclerView;
    FileAdapter fileAdapter;
    View pathBar;
    TextView pathText;
    TextView parentFolderButton;
    TextView emptyText;
    View fileFastScrollRail;
    View fileFastScrollThumb;
    View recentFastScrollRail;
    View recentFastScrollThumb;
    View recentSection;
    View browserSection;
    RecyclerView recentRecyclerView;
    TextView recentEmptyText;
    TextView recentClearAllButton;
    RecyclerView drawerFixedList;
    RecyclerView drawerStorageList;
    RecyclerView drawerShortcutList;
    View drawerRecentFoldersHeader;
    TextView drawerRecentFoldersTitle;
    TextView drawerRecentFoldersClearButton;
    int drawerTopInsetPx = 0;
    int drawerBottomInsetPx = 0;
    DrawerEntryAdapter drawerFixedEntryAdapter;
    DrawerEntryAdapter drawerShortcutEntryAdapter;
    DrawerEntryAdapter drawerEntryAdapter;
    FileAdapter recentAdapter;
    EditText fileSearchInput;
    TextView fileSearchClearButton;
    ProgressBar fileSearchProgress;
    ImageButton fileSearchScopeButton;
    ImageButton fileSortButton;
    View fileSearchBar;
    View fileTypeFilterScroll;
    int fileTypeFilterStepPx;
    TextView filterAllChip;
    TextView filterTxtChip;
    TextView filterGeneralChip;
    TextView filterArchiveChip;
    TextView filterPdfChip;
    TextView filterEpubChip;
    TextView filterWordChip;
    TextView filterImageChip;
    static final int FILTER_ALL = FileTypeFilter.ALL;
    static final int FILTER_GENERAL = FileTypeFilter.GENERAL;
    static final int FILTER_TXT = FileTypeFilter.TXT;
    static final int FILTER_ARCHIVE = FileTypeFilter.ARCHIVE;
    static final int FILTER_PDF = FileTypeFilter.PDF;
    static final int FILTER_EPUB = FileTypeFilter.EPUB;
    static final int FILTER_WORD = FileTypeFilter.WORD;
    static final int FILTER_IMAGE = FileTypeFilter.IMAGE;
    int activeFileFilter = FILTER_ALL;
    final ExecutorService fileSearchExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService fileOperationExecutor = Executors.newSingleThreadExecutor();
    final Handler fileSearchHandler = new Handler(Looper.getMainLooper());
    Runnable pendingFileSearchRunnable;
    volatile boolean activityDestroyed = false;
    final AtomicInteger fileSearchGeneration = new AtomicInteger(0);
    boolean searchMode = false;
    boolean searchReturnToHome = true;
    File searchReturnDirectory = null;
    File fileTypeFilterActivatedDirectory = null;
    boolean fileSearchAllFolders = false;

    float drawerSwipeStartX;
    float drawerSwipeStartY;
    boolean drawerSwipeTracking = false;
    boolean drawerManualDragging = false;
    boolean drawerSwipeOpened = false;
    boolean drawerDismissTracking = false;
    int drawerSwipeTouchSlop;
    float drawerSlideOffset = 0f;
    float drawerDismissStartOffset = 1f;
    boolean drawerForceSettling = false;
    boolean drawerClosePartialOnRelease = false;
    Method drawerMoveToOffsetMethod;

    File currentDirectory;
    /** True when the home (recent) view is active; false when browsing a folder. */
    boolean homeMode = true;
    PrefsManager prefs;
    BookmarkManager bookmarkManager;
    boolean lockChecked = false;
    boolean returnToViewerMode = false;
    File initialBrowseDirectory;
    final FileClipboardController fileClipboardController = FileClipboardController.getShared();
    private long lastBackPressedTime = 0L;
    /** Toolbar reference cached so onResume can re-apply the theme cheaply. */
    Toolbar mainToolbar;
    ImageButton mainOverflowButton;
    ImageButton mainOperationProgressButton;
    ImageButton mainPendingActionButton;
    private LinearLayout mainToolbarActionContainer;
    boolean fileSelectionMode = false;
    final LinkedHashSet<String> selectedFilePaths = new LinkedHashSet<>();
    File pendingExtractArchive;
    final List<File> pendingExtractArchives = new ArrayList<>();
    MainPendingArchiveCreation pendingArchiveCreation;
    final List<MainPendingArchiveCreation> pendingArchiveCreations = new ArrayList<>();
    boolean archiveExtractInProgress = false;
    boolean archiveCreateInProgress = false;
    private DrawerEntry pendingDrawerNavigationEntry;
    private boolean drawerNavigationPending = false;
    private MainBrowseStateController mainBrowseStateController;
    /**
     * Cached SD card detection. The previous code re-scanned /storage and called
     * getExternalFilesDirs() once per recent-folder candidate (up to 50 times per
     * onResume), which was the dominant slowdown after the drawer redesign.
     */
    List<File> cachedSdCards;
    private MainHomeDialogController mainHomeDialogController;
    private MainFileActionDialogController mainFileActionDialogController;
    private MainSearchFilterController mainSearchFilterController;
    private MainArchiveExtractController mainArchiveExtractController;
    private MainArchiveCreateController mainArchiveCreateController;
    private MainClipboardController mainClipboardController;
    private MainShareController mainShareController;
    private MainConfirmDialogController mainConfirmDialogController;
    private MainImageOpenController mainImageOpenController;
    private MainArchiveImageOpenController mainArchiveImageOpenController;
    private MainDrawerGestureController mainDrawerGestureController;
    private MainActivityStartupController mainActivityStartupController;
    private MainRecentFilesController mainRecentFilesController;
    private MainFolderLoadController mainFolderLoadController;
    private MainFileOperationProgressController mainFileOperationProgressController;

    private MainActivityStartupController startup() {
        if (mainActivityStartupController == null) {
            mainActivityStartupController = new MainActivityStartupController(this);
        }
        return mainActivityStartupController;
    }

    private MainDrawerGestureController mainDrawerGesture() {
        if (mainDrawerGestureController == null) {
            mainDrawerGestureController = new MainDrawerGestureController(this);
        }
        return mainDrawerGestureController;
    }

    private MainDrawerController mainDrawerController;

    private MainDrawerController mainDrawer() {
        if (mainDrawerController == null) {
            mainDrawerController = new MainDrawerController(this);
        }
        return mainDrawerController;
    }

    private MainSelectionModeController mainSelectionModeController;

    private MainSelectionModeController mainSelectionMode() {
        if (mainSelectionModeController == null) {
            mainSelectionModeController = new MainSelectionModeController(this);
        }
        return mainSelectionModeController;
    }


    private MainHomeDialogController mainDialogs() {
        if (mainHomeDialogController == null) {
            mainHomeDialogController = new MainHomeDialogController(this);
        }
        return mainHomeDialogController;
    }

    private MainFileActionDialogController mainFileDialogs() {
        if (mainFileActionDialogController == null) {
            mainFileActionDialogController = new MainFileActionDialogController(this);
        }
        return mainFileActionDialogController;
    }

    private MainSearchFilterController mainSearch() {
        if (mainSearchFilterController == null) {
            mainSearchFilterController = new MainSearchFilterController(this);
        }
        return mainSearchFilterController;
    }

    private MainArchiveExtractController mainArchiveExtract() {
        if (mainArchiveExtractController == null) {
            mainArchiveExtractController = new MainArchiveExtractController(this);
        }
        return mainArchiveExtractController;
    }

    private MainArchiveCreateController mainArchiveCreate() {
        if (mainArchiveCreateController == null) {
            mainArchiveCreateController = new MainArchiveCreateController(this);
        }
        return mainArchiveCreateController;
    }

    private MainClipboardController mainClipboard() {
        if (mainClipboardController == null) {
            mainClipboardController = new MainClipboardController(this);
        }
        return mainClipboardController;
    }

    private MainShareController mainShare() {
        if (mainShareController == null) {
            mainShareController = new MainShareController(this);
        }
        return mainShareController;
    }

    private MainConfirmDialogController mainConfirmDialogs() {
        if (mainConfirmDialogController == null) {
            mainConfirmDialogController = new MainConfirmDialogController(this);
        }
        return mainConfirmDialogController;
    }

    MainImageOpenController mainImageOpen() {
        if (mainImageOpenController == null) {
            mainImageOpenController = new MainImageOpenController(this);
        }
        return mainImageOpenController;
    }

    private MainArchiveImageOpenController mainArchiveImageOpen() {
        if (mainArchiveImageOpenController == null) {
            mainArchiveImageOpenController = new MainArchiveImageOpenController(this);
        }
        return mainArchiveImageOpenController;
    }

    private MainRecentFilesController mainRecentFiles() {
        if (mainRecentFilesController == null) {
            mainRecentFilesController = new MainRecentFilesController(this);
        }
        return mainRecentFilesController;
    }

    private MainFolderLoadController mainFolderLoad() {
        if (mainFolderLoadController == null) {
            mainFolderLoadController = new MainFolderLoadController(this);
        }
        return mainFolderLoadController;
    }

    private MainFileOperationProgressController mainFileOperationProgress() {
        if (mainFileOperationProgressController == null) {
            mainFileOperationProgressController = new MainFileOperationProgressController(this);
        }
        return mainFileOperationProgressController;
    }

    private MainBrowseStateController browseState() {
        if (mainBrowseStateController == null) {
            mainBrowseStateController = new MainBrowseStateController(this);
        }
        return mainBrowseStateController;
    }

    final ActivityResultLauncher<String[]> openFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    openFileFromUri(uri);
                }
            });

    private final ActivityResultLauncher<Intent> manageStorageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    onPermissionsGranted();
                }
            });

    final ActivityResultLauncher<Intent> lockLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    lockChecked = true;
                    checkPermissionsAndInit();
                } else {
                    finishAffinity();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        returnToViewerMode = getIntent().getBooleanExtra(EXTRA_RETURN_TO_VIEWER, false);
        initialBrowseDirectory = resolveInitialBrowseDirectory(getIntent());
        prefs.applyLanguage(prefs.getLanguageMode());
        prefs.applyDarkMode(prefs.getDarkMode());
        super.onCreate(savedInstanceState);
        startup().onCreateAfterSuper();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleProportionalDrawerEdgeDrag(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean handleProportionalDrawerEdgeDrag(@NonNull MotionEvent event) {
        return mainDrawerGesture().handleProportionalDrawerEdgeDrag(event);
    }

    void resetDrawerSwipeState() { mainDrawerGesture().resetDrawerSwipeState(); }

    void closeDrawerAfterSelection() { mainDrawerGesture().closeDrawerAfterSelection(); }

    void settleHalfOpenedDrawer() { mainDrawerGesture().settleHalfOpenedDrawer(); }

    @Override
    public void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (drawerToggle != null) {
            drawerToggle.syncState();
            installToolbarMenuButton(findViewById(R.id.toolbar));
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (drawerToggle != null) {
            drawerToggle.onConfigurationChanged(newConfig);
            installToolbarMenuButton(findViewById(R.id.toolbar));
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        activityDestroyed = false;
        if (drawerLayout != null) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START);
            updateDrawerGestureExclusion();
        }

        if (mainToolbar != null) applyMainReadableTheme(mainToolbar);
        if (mainSearchFilterController != null) {
            mainSearchFilterController.applySavedFileTypeOrder();
            mainSearchFilterController.updateFileTypeChips();
        }

        final boolean restoreViewerBrowseState = shouldPreserveBrowseStateOnResume();
        final boolean keepVisibleBrowseState = !restoreViewerBrowseState && shouldKeepCurrentBrowseStateOnResume();
        loadRecentFiles();
        rebuildDrawerStorageEntries();
        if (!homeMode && !searchMode && currentDirectory != null) {
            if (restoreViewerBrowseState || keepVisibleBrowseState) {
                refreshVisibleBrowseStateWithoutReload();
            } else {
                loadDirectory(currentDirectory);
            }
        }
        clearPreservedBrowseStateAfterResume();
        updateMainOverflowButtonVisibility();
    }

    void markPreserveBrowseStateForViewerReturn(@Nullable File openedFile) {
        browseState().markPreserveForViewerReturn(openedFile);
    }

    private boolean shouldPreserveBrowseStateOnResume() {
        return browseState().shouldPreserveViewerStateOnResume();
    }

    private boolean shouldKeepCurrentBrowseStateOnResume() {
        return browseState().shouldKeepCurrentStateOnResume();
    }

    private void refreshVisibleBrowseStateWithoutReload() {
        browseState().refreshVisibleStateWithoutReload();
    }

    private void clearPreservedBrowseStateAfterResume() {
        browseState().clearPreservedResumeState();
    }

    void markCurrentBrowseFolderLoadStarted(@Nullable File directory) {
        browseState().markLoadStarted(directory);
    }

    void markCurrentBrowseFolderLoadComplete(@NonNull File directory,
                                             @NonNull List<File> files,
                                             int sortMode) {
        browseState().markLoadComplete(directory, files, sortMode);
    }

    void markCurrentBrowseFolderLoadFailed(@Nullable File directory) {
        browseState().markLoadFailed(directory);
    }

    private void saveCurrentBrowseFolderStateIfComplete() {
        browseState().saveCurrentIfComplete();
    }

    private void saveCurrentBrowseFolderStateFastIfComplete() {
        browseState().saveCurrentFastIfComplete();
    }

    boolean restoreCachedBrowseFolderStateForFilterReturn(@NonNull File directory) {
        return browseState().restoreForFilterReturn(directory);
    }

    private boolean restoreCachedBrowseFolderState(@NonNull File directory) {
        return browseState().restore(directory);
    }

    private boolean restoreCachedBrowseFolderStateOptimisticForDrawer(@NonNull File directory) {
        return browseState().restoreOptimisticForDrawer(directory);
    }

    private void loadDirectoryWithoutOutgoingStateSave(@NonNull File dir) {
        mainFolderLoad().loadDirectory(dir);
    }

    private boolean isSameBrowseDirectory(@Nullable File left, @Nullable File right) {
        return browseState().sameDirectory(left, right);
    }

    @Override
    protected void onDestroy() {
        hideImageOpenLoadingWindow();
        activityDestroyed = true;
        pendingDrawerNavigationEntry = null;
        drawerNavigationPending = false;
        clearSearchDebounce();
        fileSearchHandler.removeCallbacksAndMessages(null);
        fileSearchGeneration.incrementAndGet();

        if (fileAdapter != null) fileAdapter.release();
        if (recentAdapter != null) recentAdapter.release();
        if (drawerFixedEntryAdapter != null) {
            drawerFixedEntryAdapter.setListener(null);
            drawerFixedEntryAdapter.setLongClickListener(null);
        }
        if (drawerEntryAdapter != null) {
            drawerEntryAdapter.setListener(null);
            drawerEntryAdapter.setLongClickListener(null);
        }

        if (fileRecyclerView != null) fileRecyclerView.setAdapter(null);
        if (recentRecyclerView != null) recentRecyclerView.setAdapter(null);
        if (drawerFixedList != null) drawerFixedList.setAdapter(null);
        if (drawerStorageList != null) drawerStorageList.setAdapter(null);

        fileSearchExecutor.shutdownNow();
        fileOperationExecutor.shutdownNow();
        if (mainFolderLoadController != null) mainFolderLoadController.shutdownNow();
        super.onDestroy();
    }

    void setupParentFolderButton() {
        if (parentFolderButton != null) {
            parentFolderButton.setOnClickListener(v -> navigateToParentFolderFromButton());
        }
        updateParentFolderButtonState();
    }

    void setPathBarVisible(boolean visible) {
        if (pathBar != null) {
            pathBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (pathText != null) {
            pathText.setVisibility(View.VISIBLE);
        }
        updateParentFolderButtonState();
    }

    void updateParentFolderButtonState() {
        if (parentFolderButton == null) return;

        boolean show = !homeMode
                && !searchMode
                && currentDirectory != null
                && currentDirectory.exists()
                && currentDirectory.isDirectory();
        parentFolderButton.setVisibility(show ? View.VISIBLE : View.GONE);

        if (show) {
            boolean canGoUpOrHome = isRootStorage(currentDirectory)
                    || (currentDirectory.getParentFile() != null
                    && currentDirectory.getParentFile().canRead());
            parentFolderButton.setEnabled(canGoUpOrHome);
            parentFolderButton.setAlpha(canGoUpOrHome ? 1.0f : 0.42f);
        } else {
            parentFolderButton.setEnabled(false);
            parentFolderButton.setAlpha(1.0f);
        }
    }

    private void navigateToParentFolderFromButton() {
        if (homeMode || searchMode || currentDirectory == null) return;
        if (isRootStorage(currentDirectory)) {
            showHomeMode();
            return;
        }

        File parent = currentDirectory.getParentFile();
        if (parent != null && parent.exists() && parent.isDirectory() && parent.canRead()) {
            loadDirectory(parent);
        }
    }

    void setupRecentHeaderActions() {
        if (recentClearAllButton != null) {
            recentClearAllButton.setOnClickListener(v -> showClearAllRecentFilesDialog());
        }
    }

    // -------------------------------------------------------------------------
    // Bottom file search
    // -------------------------------------------------------------------------

    void setupFileSearch() { mainSearch().setupFileSearch(); }

    void scrollListToTop(RecyclerView recyclerView) {
        if (recyclerView == null) return;

        recyclerView.stopScroll();
        recyclerView.post(() -> {
            RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(0, 0);
            } else {
                recyclerView.scrollToPosition(0);
            }
        });
    }

    void resetRecentRecyclerBeforeReload() {
        mainRecentFiles().resetRecyclerBeforeReload();
    }

    void updateFileSearchClearButtonVisibility() { mainSearch().updateFileSearchClearButtonVisibility(); }

    void updateSearchScopeButton() { mainSearch().updateSearchScopeButton(); }

    void restoreAllFilterLocation() { mainSearch().restoreAllFilterLocation(); }

    void resetFileFilterForNavigation() { mainSearch().resetFileFilterForNavigation(); }

    void clearSearchDebounce() { mainSearch().clearSearchDebounce(); }

    void runLiveFileSearchNow() { mainSearch().runLiveFileSearchNow(); }

    void restorePreSearchLocation() { mainSearch().restorePreSearchLocation(); }

    void showFilteredCurrentFolder(File dir) { mainSearch().showCurrentFolderFilterResults(dir); }

    boolean matchesActiveFileFilter(@NonNull String name, int filter) {
        return FileTypeFilter.matches(name, filter);
    }

    // -------------------------------------------------------------------------
    // Drawer setup
    // -------------------------------------------------------------------------

    void setupDrawerStorageList() { mainDrawer().setupDrawerStorageList(); }

    void rebuildDrawerStorageEntries() { mainDrawer().rebuildDrawerStorageEntries(); }

    boolean isBuiltInDrawerPath(@NonNull String path) { return mainDrawer().isBuiltInDrawerPath(path); }

    List<File> detectExternalSdCards() { return mainDrawer().detectExternalSdCards(); }

    void setupDrawerBottomActions() { mainDrawer().setupDrawerBottomActions(); }

    void installReliableDrawerEdgeDrag() {
        if (drawerLayout == null) return;

        drawerLayout.post(() -> {
            widenDrawerEdgeDragArea(drawerLayout, dpToPx(48));
            updateDrawerGestureExclusion();
        });

        drawerLayout.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) ->
                updateDrawerGestureExclusion());
    }

    void installToolbarMenuButton(Toolbar toolbar) {
        if (toolbar == null) return;

        if (drawerToggle != null) {
            drawerToggle.setDrawerIndicatorEnabled(false);
        }

        ensureMainOverflowButton(toolbar);
        if (fileSelectionMode) {
            mainSelectionMode().applyFileSelectionToolbar();
            return;
        }

        Drawable menuIcon = ContextCompat.getDrawable(this, R.drawable.ic_menu);
        if (menuIcon != null) {
            Drawable wrapped = DrawableCompat.wrap(menuIcon.mutate());
            DrawableCompat.setTint(wrapped, Color.WHITE);
            toolbar.setNavigationIcon(wrapped);
        }
        toolbar.setNavigationContentDescription(R.string.drawer_open);
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout != null) drawerLayout.openDrawer(GravityCompat.START);
        });

        updateMainOverflowButtonVisibility();
    }

    private void ensureMainOverflowButton(@NonNull Toolbar toolbar) {
        if (mainToolbarActionContainer != null && mainToolbarActionContainer.getParent() == toolbar
                && mainOperationProgressButton != null && mainPendingActionButton != null && mainOverflowButton != null) {
            tintMainOperationProgressButton();
            tintMainPendingActionButton();
            tintMainOverflowButton();
            updateMainOverflowButtonVisibility();
            return;
        }

        if (mainToolbarActionContainer != null && mainToolbarActionContainer.getParent() instanceof ViewGroup) {
            ((ViewGroup) mainToolbarActionContainer.getParent()).removeView(mainToolbarActionContainer);
        }
        if (mainPendingActionButton != null && mainPendingActionButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) mainPendingActionButton.getParent()).removeView(mainPendingActionButton);
        }
        if (mainOperationProgressButton != null && mainOperationProgressButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) mainOperationProgressButton.getParent()).removeView(mainOperationProgressButton);
        }
        if (mainOverflowButton != null && mainOverflowButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) mainOverflowButton.getParent()).removeView(mainOverflowButton);
        }

        mainToolbarActionContainer = new LinearLayout(this);
        mainToolbarActionContainer.setOrientation(LinearLayout.HORIZONTAL);
        mainToolbarActionContainer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mainToolbarActionContainer.setBackgroundColor(Color.TRANSPARENT);
        mainToolbarActionContainer.setClipChildren(false);
        mainToolbarActionContainer.setClipToPadding(false);

        mainOperationProgressButton = new ImageButton(this);
        mainOperationProgressButton.setBackgroundColor(Color.TRANSPARENT);
        mainOperationProgressButton.setContentDescription(getString(R.string.operation_progress_open));
        mainOperationProgressButton.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));
        mainOperationProgressButton.setScaleType(ImageView.ScaleType.CENTER);
        mainOperationProgressButton.setOnClickListener(v -> showActiveFileOperationProgress());
        tintMainOperationProgressButton();
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(48));
        mainToolbarActionContainer.addView(mainOperationProgressButton, progressLp);

        mainPendingActionButton = new ImageButton(this);
        mainPendingActionButton.setBackgroundColor(Color.TRANSPARENT);
        mainPendingActionButton.setContentDescription(getString(R.string.pending_actions));
        mainPendingActionButton.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));
        mainPendingActionButton.setScaleType(ImageView.ScaleType.CENTER);
        mainPendingActionButton.setOnClickListener(v -> showPendingActionQueueDialog());
        tintMainPendingActionButton();
        LinearLayout.LayoutParams pendingLp = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(48));
        mainToolbarActionContainer.addView(mainPendingActionButton, pendingLp);

        mainOverflowButton = new ImageButton(this);
        mainOverflowButton.setBackgroundColor(Color.TRANSPARENT);
        mainOverflowButton.setContentDescription(getString(R.string.more));
        mainOverflowButton.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        mainOverflowButton.setScaleType(ImageView.ScaleType.CENTER);
        mainOverflowButton.setOnClickListener(v -> {
            if (fileSelectionMode) showSelectedFileActionsDialog();
            else showMainOverflowDialog();
        });
        tintMainOverflowButton();
        LinearLayout.LayoutParams overflowLp = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
        mainToolbarActionContainer.addView(mainOverflowButton, overflowLp);

        Toolbar.LayoutParams lp = new Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        lp.setMarginEnd(dpToPx(2));
        toolbar.addView(mainToolbarActionContainer, lp);
        updateMainOverflowButtonVisibility();
    }

    private void tintMainOverflowButton() {
        if (mainOverflowButton == null) return;
        Drawable icon = ContextCompat.getDrawable(this, R.drawable.ic_more_vert);
        if (icon != null) {
            Drawable wrapped = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(wrapped, Color.WHITE);
            mainOverflowButton.setImageDrawable(wrapped);
        }
    }

    private void tintMainOperationProgressButton() {
        if (mainOperationProgressButton == null) return;
        Drawable icon = ContextCompat.getDrawable(this, R.drawable.ic_operation_progress);
        if (icon != null) {
            Drawable wrapped = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(wrapped, Color.WHITE);
            mainOperationProgressButton.setImageDrawable(wrapped);
        }
    }

    private void tintMainPendingActionButton() {
        if (mainPendingActionButton == null) return;
        Drawable icon = ContextCompat.getDrawable(this, R.drawable.ic_pending_actions);
        if (icon != null) {
            Drawable wrapped = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(wrapped, Color.WHITE);
            mainPendingActionButton.setImageDrawable(wrapped);
        }
    }

    void updateMainOverflowButtonVisibility() {
        if (fileSelectionMode) {
            if (mainOverflowButton != null) {
                mainOverflowButton.setVisibility(View.VISIBLE);
                mainOverflowButton.setEnabled(!selectedFilePaths.isEmpty());
                mainOverflowButton.setAlpha(selectedFilePaths.isEmpty() ? 0.55f : 1.0f);
            }
            if (mainPendingActionButton != null) {
                mainPendingActionButton.setVisibility(View.GONE);
                mainPendingActionButton.setEnabled(false);
            }
            if (mainOperationProgressButton != null) {
                mainOperationProgressButton.setVisibility(View.GONE);
                mainOperationProgressButton.setEnabled(false);
            }
            return;
        }

        boolean inBrowse = !homeMode && !searchMode;
        if (mainOverflowButton != null) {
            mainOverflowButton.setVisibility(inBrowse ? View.VISIBLE : View.GONE);
            mainOverflowButton.setEnabled(inBrowse);
            mainOverflowButton.setAlpha(1.0f);
        }
        if (mainPendingActionButton != null) {
            boolean hasPendingExtract = !pendingExtractArchives.isEmpty();
            boolean hasPendingCreate = !pendingArchiveCreations.isEmpty();
            boolean showPending = inBrowse && (fileClipboardController.hasPending() || hasPendingExtract || hasPendingCreate);
            // Keep the pending queue inspectable even while a file/archive operation is
            // running in the background. The dropdown itself disables execution controls
            // during active work so a second operation cannot replace the active progress
            // dialog or mutate a queue being consumed by a worker thread.
            mainPendingActionButton.setContentDescription(getString(R.string.pending_actions));
            mainPendingActionButton.setVisibility(showPending ? View.VISIBLE : View.GONE);
            mainPendingActionButton.setEnabled(showPending);
            mainPendingActionButton.setAlpha(showPending ? 1.0f : 0.55f);
        }
        if (mainOperationProgressButton != null) {
            boolean showProgress = mainFileOperationProgress().hasBackgroundProgress();
            mainOperationProgressButton.setContentDescription(getString(R.string.operation_progress_open));
            mainOperationProgressButton.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            mainOperationProgressButton.setEnabled(showProgress);
            mainOperationProgressButton.setAlpha(showProgress ? 1.0f : 0.55f);
        }
    }


    private void showPendingActionQueueDialog() {
        mainFileDialogs().showPendingActionQueueDialog();
    }

    private void showActiveFileOperationProgress() {
        if (!mainFileOperationProgress().showActiveProgressDialog()) {
            updateMainOverflowButtonVisibility();
        }
    }

    private void widenDrawerEdgeDragArea(@NonNull DrawerLayout layout, int desiredPx) {
        try {
            Field leftDraggerField = DrawerLayout.class.getDeclaredField("mLeftDragger");
            leftDraggerField.setAccessible(true);
            Object leftDragger = leftDraggerField.get(layout);
            if (leftDragger == null) return;

            Field edgeSizeField = leftDragger.getClass().getDeclaredField("mEdgeSize");
            edgeSizeField.setAccessible(true);
            int current = edgeSizeField.getInt(leftDragger);
            edgeSizeField.setInt(leftDragger, Math.max(current, desiredPx));

        } catch (Throwable ignored) {
            // Best-effort compatibility path. If AndroidX internals change, the drawer
            // still works through the hamburger button and normal DrawerLayout handling.
        }
    }

    private void updateDrawerGestureExclusion() {
        if (drawerLayout == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        int height = drawerLayout.getHeight();
        if (height <= 0) return;
        int edge = dpToPx(18);
        drawerLayout.setSystemGestureExclusionRects(
                Collections.singletonList(new Rect(0, 0, edge, height)));
    }

    int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int txtReaderDialogWidthPx() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dpToPx(220), Math.min(Math.round(screenWidth * 0.85f), dpToPx(460)));
    }

    int compactDeleteConfirmDialogWidthPx() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dpToPx(220), Math.min(Math.round(screenWidth * 0.75f), dpToPx(400)));
    }

    void overrideDialogWidth(@NonNull android.app.Dialog dialog, int widthPx) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
        lp.copyFrom(window.getAttributes());
        lp.width = widthPx;
        window.setAttributes(lp);
    }

    private android.app.Dialog createStablePositionedDialog(@NonNull View content, int gravity, int yPx, float dimAmount) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Color.TRANSPARENT);
        frame.setClipChildren(true);
        frame.setClipToPadding(true);
        ScrollView adaptiveScroll = wrapAdaptiveDialogContent(content, frame);
        dialog.setContentView(frame);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setGravity(gravity);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = txtReaderDialogWidthPx();
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.y = yPx;
            lp.dimAmount = dimAmount;
            window.setAttributes(lp);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setWindowAnimations(0);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        applyAdaptiveDialogMaxHeight(dialog, adaptiveScroll);
        return dialog;
    }

    private ScrollView wrapAdaptiveDialogContent(@NonNull View content, @NonNull ViewGroup outerFrame) {
        return AdaptiveDialogLayoutHelper.wrapAdaptiveContent(this, content, outerFrame);
    }

    private void applyAdaptiveDialogMaxHeight(@NonNull android.app.Dialog dialog, @NonNull View adaptiveView) {
        AdaptiveDialogLayoutHelper.applyAdaptiveMaxHeight(this, adaptiveView, txtReaderDialogWidthPx());
    }

    private boolean shouldApplyAdaptiveDialogMaxHeight(int availableHeightPx) {
        return AdaptiveDialogLayoutHelper.shouldApplyAdaptiveMaxHeight(this, availableHeightPx);
    }

    private int currentVisibleWindowHeightPx() {
        return AdaptiveDialogLayoutHelper.currentVisibleWindowHeightPx(this);
    }

    android.app.Dialog createStableBottomDialog(@NonNull View content, int yPx, float dimAmount) {
        return createStablePositionedDialog(content, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, yPx, dimAmount);
    }

    int mainFileTypeAlignedDialogYOffsetPx() {
        return dpToPx(92);
    }

    android.app.Dialog createStableCenterDialog(@NonNull View content, int yPx, float dimAmount) {
        return createStablePositionedDialog(content, Gravity.CENTER, yPx, dimAmount);
    }

    void queueDrawerNavigation(@NonNull DrawerEntry entry) {
        // Folder loading is asynchronous now, so navigation can start immediately.
        // This makes drawer taps feel responsive instead of waiting for the close
        // animation to finish before changing the main screen underneath.
        pendingDrawerNavigationEntry = null;
        drawerNavigationPending = false;
        handleDrawerEntryClick(entry, true);

        closeDrawerAfterSelection();
    }

    void consumePendingDrawerNavigation() {
        if (!drawerNavigationPending) return;

        DrawerEntry entry = pendingDrawerNavigationEntry;
        pendingDrawerNavigationEntry = null;
        drawerNavigationPending = false;

        if (entry != null && !activityDestroyed) {
            handleDrawerEntryClick(entry, true);
        }
    }

    private void handleDrawerEntryClick(@NonNull DrawerEntry entry) {
        handleDrawerEntryClick(entry, false);
    }

    private void handleDrawerEntryClick(@NonNull DrawerEntry entry, boolean fromDrawerShortcut) {
        switch (entry.getActionType()) {
            case DrawerEntry.ACTION_RECENT:
                resetFileFilterForNavigation();
                showHomeMode();
                break;
            case DrawerEntry.ACTION_INTERNAL:
            case DrawerEntry.ACTION_EXTERNAL_SD:
            case DrawerEntry.ACTION_DOWNLOADS:
            case DrawerEntry.ACTION_STORAGE_ROOT:
            case DrawerEntry.ACTION_FOLDER_SHORTCUT:
            case DrawerEntry.ACTION_RECENT_FOLDER:
                if (entry.getPath() != null) {
                    File target = new File(entry.getPath());
                    if (target.exists() && target.canRead()) {
                        resetFileFilterForNavigation();
                        if (fromDrawerShortcut) showBrowseModeFromDrawerShortcut(target);
                        else showBrowseMode(target);
                    } else {
                        ShortToast.show(this, R.string.containing_folder_unavailable);
                    }
                }
                break;
        }
    }

    boolean handleDrawerEntryLongClick(@NonNull DrawerEntry entry) {
        if (entry.getActionType() == DrawerEntry.ACTION_FOLDER_SHORTCUT && entry.getPath() != null) {
            showShortcutRemoveDialog(new File(entry.getPath()));
            return true;
        }
        if (entry.getActionType() == DrawerEntry.ACTION_RECENT_FOLDER && entry.getPath() != null) {
            showRecentFolderClearDialog(new File(entry.getPath()));
            return true;
        }
        return false;
    }

    void addFolderShortcut(@NonNull File folder) {
        if (prefs == null || !folder.isDirectory()) return;
        prefs.addFolderShortcut(folder.getAbsolutePath());
        rebuildDrawerStorageEntries();
        ShortToast.show(this, getString(R.string.shortcut_added));
    }

    void removeFolderShortcut(@NonNull File folder) {
        if (prefs == null) return;
        prefs.removeFolderShortcut(folder.getAbsolutePath());
        rebuildDrawerStorageEntries();
        ShortToast.show(this, getString(R.string.shortcut_removed));
    }

    private void showShortcutRemoveDialog(@NonNull File folder) {
        final boolean dark = prefs == null || prefs.shouldUseDarkColors(this);
        final int bg = prefs != null ? prefs.getMainBgColor(this) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        final int panel = prefs != null ? prefs.getMainPanelColor(this) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        final int danger = dark ? Color.rgb(255, 170, 170) : Color.rgb(176, 0, 32);
        final int line = prefs != null ? prefs.getMainOutlineColor(this) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpToPx(18), dpToPx(16), dpToPx(18), dpToPx(10));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(dpToPx(18));
        bgShape.setStroke(Math.max(1, dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(this);
        title.setText(getString(R.string.remove_shortcut));
        title.setTextColor(fg);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(6));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText(folder.getAbsolutePath());
        hint.setTextColor(sub);
        hint.setTextSize(13f);
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        hint.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(12));
        box.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        addFileOpsRow(box, getString(R.string.remove_shortcut), danger, panel, () -> {
            if (ref[0] != null) ref[0].dismiss();
            removeFolderShortcut(folder);
        });

        TextView cancel = new TextView(this);
        cancel.setText(getString(R.string.cancel));
        cancel.setTextColor(sub);
        cancel.setTextSize(16f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cancel.setPadding(dpToPx(12), 0, dpToPx(12), 0);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50));
        cancelLp.setMargins(0, dpToPx(4), 0, 0);
        box.addView(cancel, cancelLp);

        android.app.Dialog dialog = createStableBottomDialog(box, mainFileTypeAlignedDialogYOffsetPx(), 0.22f);
        ref[0] = dialog;
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showClearAllRecentFilesDialog() {
        if (bookmarkManager == null || !bookmarkManager.hasRecentFiles()) {
            ShortToast.show(this, getString(R.string.no_recent_files_to_clear));
            return;
        }
        showSimpleDangerDialog(
                getString(R.string.clear_recent_files),
                getString(R.string.clear_recent_files_confirm),
                getString(R.string.clear_recent_files),
                () -> {
                    if (bookmarkManager != null) bookmarkManager.clearReadingStates();
                    loadRecentFiles();
                    rebuildDrawerStorageEntries();
                    ShortToast.show(this, getString(R.string.recent_files_cleared));
                });
    }

    void showClearAllRecentFoldersDialog() {
        List<String> visibleRecentFolders = collectVisibleRecentFolderPaths();
        if (visibleRecentFolders.isEmpty()) {
            ShortToast.show(this, getString(R.string.no_recent_folders_to_clear));
            return;
        }
        showSimpleDangerDialog(
                getString(R.string.clear_recent_folders),
                getString(R.string.clear_recent_folders_confirm),
                getString(R.string.clear_recent_folders),
                () -> {
                    if (prefs != null) prefs.clearRecentFolders(visibleRecentFolders);
                    rebuildDrawerStorageEntries();
                    ShortToast.show(this, getString(R.string.recent_folders_cleared));
                });
    }

    private void showRecentFolderClearDialog(@NonNull File folder) {
        showSimpleDangerDialog(
                getString(R.string.clear_recent_folder),
                getString(R.string.clear_recent_folder_confirm, folder.getAbsolutePath()),
                getString(R.string.clear_recent_folder),
                () -> {
                    if (prefs != null) prefs.removeRecentFolder(folder.getAbsolutePath());
                    rebuildDrawerStorageEntries();
                    ShortToast.show(this, getString(R.string.recent_folder_cleared));
                });
    }

    private List<String> collectVisibleRecentFolderPaths() {
        List<String> result = new ArrayList<>();
        if (bookmarkManager == null && prefs == null) return result;

        LinkedHashSet<String> recentPaths = new LinkedHashSet<>();
        String lastDirectory = prefs != null ? prefs.getLastDirectory() : null;
        if (lastDirectory != null && !lastDirectory.trim().isEmpty()) recentPaths.add(lastDirectory.trim());

        if (prefs != null) recentPaths.addAll(prefs.getRecentFolders(64));

        if (bookmarkManager != null) {
            for (ReaderState state : bookmarkManager.getRecentFiles(200)) {
                File file = new File(state.getFilePath());
                File parent = file.isDirectory() ? file : file.getParentFile();
                if (parent != null) recentPaths.add(parent.getAbsolutePath());
            }
        }

        for (String path : recentPaths) {
            if (path == null || path.trim().isEmpty()) continue;
            File folder = new File(path.trim());
            if (!folder.exists() || !folder.isDirectory() || !folder.canRead()) continue;
            if (isBuiltInDrawerPath(folder.getAbsolutePath())) continue;
            if (prefs != null && prefs.isRecentFolderHidden(folder.getAbsolutePath())) continue;
            if (prefs != null && prefs.isFolderShortcut(folder.getAbsolutePath())) continue;
            result.add(folder.getAbsolutePath());
            if (result.size() >= 10) break;
        }
        return result;
    }

    private void showSimpleDangerDialog(@NonNull String titleText,
                                        @NonNull String messageText,
                                        @NonNull String actionText,
                                        @NonNull Runnable action) {
        final boolean dark = prefs == null || prefs.shouldUseDarkColors(this);
        final int bg = prefs != null ? prefs.getMainBgColor(this) : (dark ? Color.rgb(33, 33, 33) : Color.rgb(255, 255, 255));
        final int panel = prefs != null ? prefs.getMainPanelColor(this) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        final int fg = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        final int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        final int danger = dark ? Color.rgb(255, 170, 170) : Color.rgb(176, 0, 32);
        final int line = prefs != null ? prefs.getMainOutlineColor(this) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpToPx(18), dpToPx(16), dpToPx(18), dpToPx(10));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(dpToPx(18));
        bgShape.setStroke(Math.max(1, dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(fg);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(8));
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextColor(sub);
        message.setTextSize(14f);
        message.setLineSpacing(dpToPx(2), 1.0f);
        message.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(14));
        box.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        addFileOpsRow(box, actionText, danger, panel, () -> {
            if (ref[0] != null) ref[0].dismiss();
            action.run();
        });

        TextView cancel = new TextView(this);
        cancel.setText(getString(R.string.cancel));
        cancel.setTextColor(sub);
        cancel.setTextSize(16f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cancel.setPadding(dpToPx(12), 0, dpToPx(12), 0);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50));
        cancelLp.setMargins(0, dpToPx(4), 0, 0);
        box.addView(cancel, cancelLp);

        android.app.Dialog dialog = createStableBottomDialog(box, mainFileTypeAlignedDialogYOffsetPx(), 0.22f);
        ref[0] = dialog;
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // -------------------------------------------------------------------------
    // Home / Browse mode switching
    // -------------------------------------------------------------------------

    void showHomeMode() {
        // Save the visible browse folder before leaving Browse mode so returning
        // to it can reuse the adapter list and RecyclerView position when the
        // folder contents are unchanged.
        saveCurrentBrowseFolderStateIfComplete();
        exitFileSelectionMode(false);
        cancelPendingFolderLoad();
        if (fileSearchProgress != null) fileSearchProgress.setVisibility(View.GONE);
        searchMode = false;
        searchReturnToHome = true;
        searchReturnDirectory = null;
        homeMode = true;
        updateFileTypeChips();
        recentSection.setVisibility(View.VISIBLE);
        browserSection.setVisibility(View.GONE);
        setPathBarVisible(false);
        updateFileSearchClearButtonVisibility();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }
        loadRecentFiles();
        updateMainOverflowButtonVisibility();
        invalidateOptionsMenu();
    }

    void showBrowseMode(@NonNull File dir) {
        // Capture the current browse folder even when switching through Home or
        // drawer shortcuts. This enables A -> B -> A -> B folder-state reuse.
        saveCurrentBrowseFolderStateIfComplete();
        exitFileSelectionMode(false);
        searchMode = false;
        searchReturnToHome = false;
        searchReturnDirectory = dir;
        homeMode = false;
        updateFileTypeChips();
        recentSection.setVisibility(View.GONE);
        browserSection.setVisibility(View.VISIBLE);
        setPathBarVisible(true);
        updateFileSearchClearButtonVisibility();
        if (prefs != null) prefs.addRecentFolder(dir.getAbsolutePath());
        if (!restoreCachedBrowseFolderState(dir)) {
            loadDirectory(dir);
        }
        updateMainOverflowButtonVisibility();
        invalidateOptionsMenu();
    }

    void showBrowseModeFromDrawerShortcut(@NonNull File dir) {
        // Drawer shortcut taps should never block on a full directory signature scan.
        // Save the outgoing visible list with the already-known load signature, then
        // restore the target cache optimistically and validate it in the background.
        saveCurrentBrowseFolderStateFastIfComplete();
        exitFileSelectionMode(false);
        searchMode = false;
        searchReturnToHome = false;
        searchReturnDirectory = dir;
        homeMode = false;
        updateFileTypeChips();
        recentSection.setVisibility(View.GONE);
        browserSection.setVisibility(View.VISIBLE);
        setPathBarVisible(true);
        updateFileSearchClearButtonVisibility();
        if (prefs != null) prefs.addRecentFolder(dir.getAbsolutePath());
        if (!restoreCachedBrowseFolderStateOptimisticForDrawer(dir)) {
            loadDirectoryWithoutOutgoingStateSave(dir);
        }
        updateMainOverflowButtonVisibility();
        invalidateOptionsMenu();
    }

    void showInitialMainMode() {
        if (initialBrowseDirectory != null
                && initialBrowseDirectory.exists()
                && initialBrowseDirectory.isDirectory()
                && initialBrowseDirectory.canRead()) {
            File target = initialBrowseDirectory;
            initialBrowseDirectory = null;
            showBrowseMode(target);
        } else {
            initialBrowseDirectory = null;
            showHomeMode();
        }
    }

    File resolveInitialBrowseDirectory(Intent intent) {
        if (intent == null) return null;
        String dirPath = intent.getStringExtra(EXTRA_START_DIRECTORY);
        if (dirPath == null || dirPath.trim().isEmpty()) return null;
        File dir = new File(dirPath.trim());
        if (dir.isFile()) dir = dir.getParentFile();
        return dir;
    }

    // -------------------------------------------------------------------------
    // Theming
    // -------------------------------------------------------------------------

    private MainThemeController mainThemeController;

    private MainThemeController mainTheme() {
        if (mainThemeController == null) {
            mainThemeController = new MainThemeController(this);
        }
        return mainThemeController;
    }

    void applyMainReadableTheme(Toolbar toolbar) {
        mainTheme().applyMainReadableTheme(toolbar);
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    void checkPermissionsAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                onPermissionsGranted();
            } else {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    manageStorageLauncher.launch(intent);
                } catch (Exception e) {
                    manageStorageLauncher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
            } else {
                onPermissionsGranted();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onPermissionsGranted();
        }
    }

    private void onPermissionsGranted() {
        rebuildDrawerStorageEntries();
        loadRecentFiles();
    }

    // -------------------------------------------------------------------------
    // File list / recent list
    // -------------------------------------------------------------------------

    void cancelPendingFolderLoad() {
        mainFolderLoad().cancelPendingFolderLoad();
    }

    void loadDirectory(File dir) {
        if (dir == null) return;

        boolean activelyBrowsingSameDirectory = !homeMode
                && !searchMode
                && isSameBrowseDirectory(currentDirectory, dir);
        if (!activelyBrowsingSameDirectory) {
            saveCurrentBrowseFolderStateIfComplete();
            if (restoreCachedBrowseFolderState(dir)) {
                return;
            }
        }

        mainFolderLoad().loadDirectory(dir);
    }

    void refreshCurrentDirectoryWithoutClearing(File dir) {
        mainFolderLoad().refreshCurrentDirectoryWithoutClearing(dir);
    }

    void resortVisibleFileListAsync(int sortMode) {
        mainFolderLoad().resortVisibleFileListAsync(sortMode);
    }

    void executeFolderBackgroundTask(@NonNull Runnable task) {
        fileOperationExecutor.execute(task);
    }

    @NonNull
    FileOperationProgress showFileOperationProgress(@NonNull String title, @Nullable String detail) {
        return mainFileOperationProgress().show(title, detail);
    }

    void finishFileOperationProgress(@Nullable FileOperationProgress progress) {
        mainFileOperationProgress().finish(progress);
    }

    private boolean isRootStorage(File dir) {
        if (dir == null) return true;
        String path = dir.getAbsolutePath();
        return path.equals(Environment.getExternalStorageDirectory().getAbsolutePath())
                || path.equals("/storage") || path.equals("/");
    }

    void loadRecentFiles() {
        mainRecentFiles().loadRecentFiles();
    }

    @Override public void onFileClick(@NonNull File file) {
        if (drawerLayout != null && drawerLayout.isDrawerVisible(GravityCompat.START)) {
            closeDrawerAfterSelection();
        }
        if (fileSelectionMode) {
            toggleFileSelection(file);
            return;
        }
        if (file.isDirectory()) {
            // Stepping into a subfolder from home or search results promotes the view to browse mode.
            if (shouldKeepFileTypeFilterForFolderNavigation()) showFilteredCurrentFolder(file);
            else if (homeMode || searchMode) showBrowseMode(file);
            else loadDirectory(file);
        } else {
            openFile(file);
        }
    }

    private boolean shouldKeepFileTypeFilterForFolderNavigation() {
        return activeFileFilter != FILTER_ALL
                && !homeMode
                && fileSearchInput != null
                && fileSearchInput.getText() != null
                && fileSearchInput.getText().toString().trim().isEmpty();
    }

    private boolean isAtFileTypeFilterActivationDirectory() {
        if (currentDirectory == null || fileTypeFilterActivatedDirectory == null) return false;
        try {
            return currentDirectory.getCanonicalFile().equals(fileTypeFilterActivatedDirectory.getCanonicalFile());
        } catch (IOException ignored) {
            return currentDirectory.getAbsolutePath().equals(fileTypeFilterActivatedDirectory.getAbsolutePath());
        }
    }

    private void clearFileTypeFilterAndMoveToParent() {
        File parent = currentDirectory != null ? currentDirectory.getParentFile() : null;
        activeFileFilter = FILTER_ALL;
        fileTypeFilterActivatedDirectory = null;
        updateFileTypeChips();
        if (parent != null && parent.canRead()) {
            searchMode = false;
            homeMode = false;
            loadDirectory(parent);
        } else {
            restoreAllFilterLocation();
        }
    }

    @Override public void onFileLongClick(@NonNull File file) {
        if (fileSelectionMode) {
            toggleFileSelection(file);
            return;
        }
        showFileOpsDialog(file);
    }

    @Override public void onFileMultiSelectLongClick(@NonNull File file) {
        enterFileSelectionMode(file);
    }

    private void enterFileSelectionMode(@NonNull File firstFile) { mainSelectionMode().enterFileSelectionMode(firstFile); }

    private void toggleFileSelection(@NonNull File file) { mainSelectionMode().toggleFileSelection(file); }

    void exitFileSelectionMode(boolean restoreToolbar) { mainSelectionMode().exitFileSelectionMode(restoreToolbar); }

    @NonNull
    ArrayList<File> getSelectedShareableFilesSnapshot() { return mainSelectionMode().getSelectedShareableFilesSnapshot(); }

    @NonNull
    ArrayList<File> getSelectedArchiveFilesSnapshot() { return mainSelectionMode().getSelectedArchiveFilesSnapshot(); }

    @NonNull
    ArrayList<File> getSelectedFilesSnapshot() { return mainSelectionMode().getSelectedFilesSnapshot(); }

    @Nullable
    File getSingleSelectedFile() { return mainSelectionMode().getSingleSelectedFile(); }

    private void showSelectedFileActionsDialog() {
        mainFileDialogs().showSelectedFileActionsDialog();
    }

    void selectAllVisibleFiles() { mainSelectionMode().selectAllVisibleFiles(); }

    void startSelectedClipboardOperation(boolean copy) { mainSelectionMode().startSelectedClipboardOperation(copy); }

    void startSelectedArchiveExtraction() { mainSelectionMode().startSelectedArchiveExtraction(); }

    boolean isRecentFileSelectionContext() { return mainSelectionMode().isRecentFileSelectionContext(); }

    void removeSelectedFilesFromRecentList() { mainSelectionMode().removeSelectedFilesFromRecentList(); }

    void startSelectedArchiveCreation() {
        ArrayList<File> selected = getSelectedFilesSnapshot();
        exitFileSelectionMode(true);
        mainArchiveCreate().startArchiveCreation(selected);
    }

    void showSelectedDeleteConfirm() { mainSelectionMode().showSelectedDeleteConfirm(); }

    void navigateToContainingFolder(@NonNull File file) {
        File parent = file.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory() || !parent.canRead()) {
            ShortToast.show(this, R.string.containing_folder_unavailable);
            return;
        }

        resetMainBrowseFiltersAndShow(parent, file.getAbsolutePath());
    }

    void resetMainBrowseFiltersAndShow(@NonNull File directory, @Nullable String revealPath) {
        mainFolderLoad().setPendingRevealPath(revealPath);
        activeFileFilter = FILTER_ALL;
        clearSearchDebounce();
        fileSearchGeneration.incrementAndGet();
        if (fileSearchInput != null && fileSearchInput.getText() != null
                && fileSearchInput.getText().length() > 0) {
            fileSearchInput.setText("");
        }
        updateFileTypeChips();
        updateFileSearchClearButtonVisibility();
        showBrowseMode(directory);
    }

    void updateFileTypeChips() {
        mainSearch().updateFileTypeChips();
    }

    void showSimpleConfirmDialog(@NonNull String titleText,
                                 @NonNull String messageText,
                                 @NonNull String confirmText,
                                 @NonNull Runnable onConfirm) {
        mainConfirmDialogs().showSimpleConfirmDialog(titleText, messageText, confirmText, onConfirm);
    }

    private void saveArchiveRecentState(@NonNull File file) {
        if (bookmarkManager == null || file == null || !file.exists()) return;
        try {
            ReaderState state = new ReaderState(file.getAbsolutePath());
            state.setFileLength(file.length());
            // Archives have no page progress; this entry is only for recency.
            state.setPageNumber(0);
            state.setTotalPages(0);
            bookmarkManager.saveReadingState(state);
        } catch (Exception ignored) {
            // Recent registration should not block opening the archive.
        }
    }

    void openFile(File file) {
        File parent = file.getParentFile();
        if (parent != null && prefs != null) {
            prefs.addRecentFolder(parent.getAbsolutePath());
            rebuildDrawerStorageEntries();
        }

        Intent intent;
        boolean image = false;
        if (ArchiveSupport.isSupportedArchive(file)) {
            saveArchiveRecentState(file);
            if (mainArchiveImageOpen().openDirectImageViewerIfComicArchive(file)) {
                return;
            }
            intent = new Intent(this, ArchiveBrowserActivity.class);
            intent.putExtra(ArchiveBrowserActivity.EXTRA_ARCHIVE_PATH, file.getAbsolutePath());
        } else if (FileUtils.isPdfFile(file.getName())) {
            intent = new Intent(this, PdfReaderActivity.class);
            intent.putExtra(PdfReaderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        } else if (FileUtils.isEpubFile(file.getName()) || FileUtils.isWordFile(file.getName())) {
            intent = new Intent(this, DocumentPageActivity.class);
            intent.putExtra(DocumentPageActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        } else if (FileUtils.isImageFile(file.getName())) {
            image = true;
            intent = new Intent(this, ImageReaderActivity.class);
            intent.putExtra(ImageReaderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
            mainImageOpen().attachDeferredImageViewerSequence(intent, file);
            intent.putExtra(ImageReaderActivity.EXTRA_ALLOW_FILE_OPS, true);
        } else if (FileUtils.isExternalOpenableFile(file.getName())) {
            openExternalFile(file);
            if (returnToViewerMode) finish();
            return;
        } else {
            intent = new Intent(this, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        markPreserveBrowseStateForViewerReturn(file);
        if (image) {
            mainImageOpen().startWithLoading(intent);
            return;
        }
        startActivity(intent);

        if (returnToViewerMode) finish();
    }

    private void openExternalFile(@NonNull File file) {
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            ShortToast.show(this, R.string.external_open_failed);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, externalMimeTypeFor(file));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            markPreserveBrowseStateForViewerReturn(file);
            startActivity(Intent.createChooser(view, getString(R.string.open)));
        } catch (ActivityNotFoundException e) {
            clearPreservedBrowseStateAfterResume();
            ShortToast.show(this, R.string.external_open_failed);
        } catch (Exception e) {
            clearPreservedBrowseStateAfterResume();
            ShortToast.show(this, R.string.external_open_failed);
        }
    }

    @NonNull
    private String externalMimeTypeFor(@NonNull File file) {
        if (FileUtils.isVideoFile(file.getName())) return mimeTypeFromExtension(file, "video/*");
        return mimeTypeFromExtension(file, "application/octet-stream");
    }

    @NonNull
    private String mimeTypeFromExtension(@NonNull File file, @NonNull String fallback) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null && mime.trim().length() > 0) return mime;
        }
        return fallback;
    }

    void openFileFromUri(Uri uri) {
        String displayName = FileUtils.getFileNameFromUri(this, uri);
        String mime = getContentResolver().getType(uri);
        boolean pdf = FileUtils.isPdfFile(displayName) || "application/pdf".equalsIgnoreCase(mime);
        boolean epub = FileUtils.isEpubFile(displayName) || "application/epub+zip".equalsIgnoreCase(mime);
        boolean word = FileUtils.isWordFile(displayName)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(mime)
                || "application/vnd.ms-word.document.macroEnabled.12".equalsIgnoreCase(mime)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.template".equalsIgnoreCase(mime)
                || "application/vnd.ms-word.template.macroEnabled.12".equalsIgnoreCase(mime);
        boolean image = FileUtils.isImageFile(displayName)
                || (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/"));

        Intent intent;
        if (pdf) {
            intent = new Intent(this, PdfReaderActivity.class);
            intent.putExtra(PdfReaderActivity.EXTRA_FILE_URI, uri.toString());
        } else if (epub || word) {
            intent = new Intent(this, DocumentPageActivity.class);
            intent.putExtra(DocumentPageActivity.EXTRA_FILE_URI, uri.toString());
        } else if (image) {
            intent = new Intent(this, ImageReaderActivity.class);
            intent.putExtra(ImageReaderActivity.EXTRA_FILE_URI, uri.toString());
            intent.putExtra(ImageReaderActivity.EXTRA_ALLOW_FILE_OPS, false);
        } else {
            intent = new Intent(this, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_FILE_URI, uri.toString());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (image) {
            mainImageOpen().startWithLoading(intent);
            return;
        }
        startActivity(intent);

        if (returnToViewerMode) finish();
    }

    void hideImageOpenLoadingWindow() {
        if (mainImageOpenController != null) {
            mainImageOpenController.hideImageOpenLoadingWindow();
        }
    }

    void finishIfReturnToViewerMode() {
        if (returnToViewerMode) finish();
    }

    // -------------------------------------------------------------------------
    // File operations (move / copy / extract / rename / delete / new folder / info / sort)
    // -------------------------------------------------------------------------

    private void showFileOpsDialog(File file) {
        mainFileDialogs().showFileOpsDialog(file);
    }

    boolean isSupportedArchive(@NonNull File file) {
        return mainArchiveExtract().isSupportedArchive(file);
    }

    void openArchiveFolderPreview(@NonNull File file) {
        if (!ArchiveSupport.isSupportedArchive(file) || !file.exists() || !file.isFile()) {
            ShortToast.show(this, R.string.archive_open_failed);
            return;
        }
        saveArchiveRecentState(file);
        Intent intent = new Intent(this, ArchiveBrowserActivity.class);
        intent.putExtra(ArchiveBrowserActivity.EXTRA_ARCHIVE_PATH, file.getAbsolutePath());
        intent.putExtra(ArchiveBrowserActivity.EXTRA_FORCE_FOLDER_PREVIEW, true);
        markPreserveBrowseStateForViewerReturn(file);
        startActivity(intent);
    }

    void startArchiveExtraction(@NonNull File archive) {
        mainArchiveExtract().startArchiveExtraction(archive);
    }

    void startArchiveExtractions(@NonNull List<File> archives) {
        mainArchiveExtract().startArchiveExtractions(archives);
    }

    void startArchiveCreation(@NonNull File source) {
        mainArchiveCreate().startArchiveCreation(source);
    }

    boolean setActivePendingArchiveCreation(@Nullable MainPendingArchiveCreation task) {
        return mainArchiveCreate().setActivePendingArchiveCreation(task);
    }

    void cancelPendingArchiveCreation(@Nullable MainPendingArchiveCreation task) {
        mainArchiveCreate().cancelPendingArchiveCreation(task);
    }

    void confirmPendingArchiveCreation() {
        mainArchiveCreate().confirmPendingArchiveCreation();
    }

    void confirmAllPendingArchiveCreations() {
        mainArchiveCreate().confirmAllPendingArchiveCreations();
    }

    boolean setActivePendingArchiveExtraction(@Nullable File archive) {
        return mainArchiveExtract().setActivePendingArchiveExtraction(archive);
    }

    void cancelPendingArchiveExtraction(@Nullable File archive) {
        mainArchiveExtract().cancelPendingArchiveExtraction(archive);
    }

    void confirmPendingArchiveExtractionToCurrentDirectory() {
        mainArchiveExtract().confirmPendingArchiveExtractionToCurrentDirectory();
    }

    void confirmAllPendingArchiveExtractionsToCurrentDirectory() {
        mainArchiveExtract().confirmAllPendingArchiveExtractionsToCurrentDirectory();
    }

    void shareSelectedFiles() {
        mainShare().shareSelectedFiles();
    }

    void shareFile(@NonNull File file) {
        mainShare().shareFile(file);
    }

    void addFileOpsRow(@NonNull LinearLayout box, @NonNull String label, int textColor, int panelColor, @NonNull Runnable action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(textColor);
        row.setTextSize(17f);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setPadding(dpToPx(16), 0, dpToPx(16), 0);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(panelColor);
        rowBg.setCornerRadius(dpToPx(12));
        row.setBackground(rowBg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50));
        lp.setMargins(0, 0, 0, dpToPx(8));
        box.addView(row, lp);
        row.setOnClickListener(v -> action.run());
    }

    void startFileClipboardOperation(@NonNull File source, boolean copy) {
        mainClipboard().startFileClipboardOperation(source, copy);
    }

    void clearPendingActionQueue() {
        mainClipboard().clearPendingActionQueue();
    }

    void cancelPendingClipboardOperation(long pendingId) {
        mainClipboard().cancelPendingClipboardOperation(pendingId);
    }

    void pastePendingClipboardItemToCurrentDirectory() {
        mainClipboard().pastePendingClipboardItemToCurrentDirectory();
    }

    void pasteAllPendingClipboardItemsToCurrentDirectory() {
        mainClipboard().pasteAllPendingClipboardItemsToCurrentDirectory();
    }

    void showRenameDialog(File file) {
        mainFileDialogs().showRenameDialog(file);
    }

    void showDeleteConfirm(File file) {
        mainFileDialogs().showDeleteConfirm(file);
    }

    void refreshVisibleFileListAfterDelete() {
        if (homeMode) {
            loadRecentFiles();
        } else if (searchMode) {
            runLiveFileSearchNow();
        } else if (currentDirectory != null) {
            loadDirectory(currentDirectory);
        }
        rebuildDrawerStorageEntries();
    }

    void cleanupNavigationStateAfterDelete(String deletedPath, boolean deletedDirectory) {
        if (prefs == null || deletedPath == null || deletedPath.trim().isEmpty()) return;

        if (!deletedDirectory) return;

        for (String recentFolder : new ArrayList<>(prefs.getRecentFolders(64))) {
            if (isSameOrChildPath(recentFolder, deletedPath)) {
                prefs.removeRecentFolder(recentFolder);
            }
        }

        for (String shortcut : new ArrayList<>(prefs.getFolderShortcuts(64))) {
            if (isSameOrChildPath(shortcut, deletedPath)) {
                prefs.removeFolderShortcut(shortcut);
            }
        }

        String lastDirectory = prefs.getLastDirectory();
        if (isSameOrChildPath(lastDirectory, deletedPath)) {
            prefs.setLastDirectory(null);
        }
    }

    private boolean isSameOrChildPath(String candidatePath, String rootPath) {
        if (candidatePath == null || rootPath == null) return false;
        String candidate = candidatePath.trim();
        String root = rootPath.trim();
        if (candidate.isEmpty() || root.isEmpty()) return false;

        try {
            candidate = new File(candidate).getCanonicalPath();
            root = new File(root).getCanonicalPath();
        } catch (IOException ignored) {
            candidate = new File(candidate).getAbsolutePath();
            root = new File(root).getAbsolutePath();
        }

        if (candidate.equals(root)) return true;
        String normalizedRoot = root.endsWith(File.separator) ? root : root + File.separator;
        return candidate.startsWith(normalizedRoot);
    }

    boolean deleteRecursive(File file) {
        return file != null && com.textview.reader.util.FileSystemOps.delete(file);
    }

    void showFileInfo(File file) {
        mainFileDialogs().showFileInfo(file);
    }



    private void showMainOverflowDialog() {
        mainDialogs().showMainOverflowDialog();
    }

    void showNewFolderDialog() {
        mainFileDialogs().showNewFolderDialog();
    }

    void showSortDialog() {
        mainDialogs().showSortDialog();
    }

    // Menu
    // -------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        // The folder actions use a themed custom overflow dialog instead of the
        // platform overflow popup so Deep Navy / Custom main colors apply.
        menu.clear();
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateMainOverflowButtonVisibility();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (drawerToggle != null && drawerToggle.onOptionsItemSelected(item)) return true;

        return super.onOptionsItemSelected(item);
    }

    // -------------------------------------------------------------------------
    // Back handling
    // -------------------------------------------------------------------------

    void handleMainBackPressed() {
        // 1. If multi-select mode is active, Back cancels selection first.
        if (fileSelectionMode) {
            exitFileSelectionMode(true);
            return;
        }

        // 2. If the drawer is open, close it.
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }

        // 3. In filtered folder browsing, Back keeps the filter only for folders
        // entered after the filter was already active. At the folder where the
        // filter was turned on, Back clears it and returns to the parent.
        if (activeFileFilter != FILTER_ALL) {
            if (shouldKeepFileTypeFilterForFolderNavigation()
                    && currentDirectory != null
                    && !isRootStorage(currentDirectory)) {
                File parent = currentDirectory.getParentFile();
                if (parent != null && parent.canRead()) {
                    if (isAtFileTypeFilterActivationDirectory()) {
                        clearFileTypeFilterAndMoveToParent();
                        return;
                    }
                    showFilteredCurrentFolder(parent);
                    return;
                }
            }
            activeFileFilter = FILTER_ALL;
            fileTypeFilterActivatedDirectory = null;
            updateFileTypeChips();
            if (searchMode) {
                restorePreSearchLocation();
            } else if (homeMode) {
                loadRecentFiles();
            } else if (currentDirectory != null && currentDirectory.exists() && currentDirectory.isDirectory()) {
                loadDirectory(currentDirectory);
            } else {
                restoreAllFilterLocation();
            }
            return;
        }

        // 4. Search results return to home first.
        if (searchMode) {
            restorePreSearchLocation();
            return;
        }

        // 5. If browsing, navigate up. When at the storage root, drop back to home.
        if (!homeMode) {
            if (currentDirectory != null && !isRootStorage(currentDirectory)) {
                File parent = currentDirectory.getParentFile();
                if (parent != null && parent.canRead()) {
                    loadDirectory(parent);
                    return;
                }
            }
            // At root or nowhere to go up: return to home (Recent).
            showHomeMode();
            return;
        }

        // 6. From home: special "return to viewer" behavior, or double-tap-back to exit.
        if (returnToViewerMode) {
            finish();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBackPressedTime < 2000) {
            finish();
        } else {
            lastBackPressedTime = now;
            ShortToast.show(this, getString(R.string.press_back_again_exit));
        }
    }

}
