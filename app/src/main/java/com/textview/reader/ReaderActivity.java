package com.textview.reader;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Button;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.controller.AutoPageTurnController;
import com.textview.reader.controller.ReaderToolbarController;
import com.textview.reader.model.Bookmark;
import com.textview.reader.model.LargeTextLinePartitionResult;
import com.textview.reader.search.LargeTextSearchEngine;
import com.textview.reader.search.LargeTextSearchTotalCache;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.FontManager;
import com.textview.reader.util.PrefsManager;
import com.textview.reader.util.ThemeManager;
import com.textview.reader.util.LargeTextExactPageIndexState;
import com.textview.reader.util.LargeTextContinuityMath;
import com.textview.reader.util.LargeTextPageDirectionState;
import com.textview.reader.util.LargeTextPartitionSwitchState;
import com.textview.reader.util.LargeTextPartitionCache;
import com.textview.reader.view.CustomReaderView;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ReaderActivity extends AppCompatActivity {

    static final long LARGE_TEXT_FAST_OPEN_THRESHOLD_BYTES = 3L * 1024L * 1024L;
    private static final long HUGE_TEXT_PREVIEW_ONLY_THRESHOLD_BYTES = 32L * 1024L * 1024L;
    private static final int LARGE_TEXT_EXACT_INDEX_CHUNK_CHARS = 256 * 1024;
    // Kept only for sampling/legacy estimate paths; active large-TXT windows are line-based.
    private static final int LARGE_TEXT_PARTITION_BYTES = 3 * 1024 * 1024;
    static final int LARGE_TEXT_PREVIEW_BYTES = LARGE_TEXT_PARTITION_BYTES;
    private static final int TXT_TOOLBAR_POPUP_Y_DP = 74;
    static final String TXT_ACTUAL_FILE_EDIT_PREFS = "txt_actual_file_edit";
    static final String KEY_TXT_ACTUAL_FILE_EDIT_PATH = "modified_path";
    static final String KEY_TXT_ACTUAL_FILE_EDIT_TOKEN = "modified_token";
    static final String KEY_TXT_ACTUAL_FILE_EDIT_LENGTH = "modified_length";
    static final String KEY_TXT_ACTUAL_FILE_EDIT_LAST_MODIFIED = "modified_last_modified";
    private static final int TXT_BOOKMARK_POPUP_Y_DP = 34;
    private static final int TXT_BOOKMARK_HINT_POPUP_Y_DP = 112;
    private static final String FONT_OPTION_SYSTEM_CURRENT = "system_current";
    String currentTextEncodingLabel = "";
    boolean currentTextEncodingManual = false;

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_URI = "file_uri";
    public static final String EXTRA_JUMP_TO_POSITION = "jump_position";
    public static final String EXTRA_JUMP_DISPLAY_PAGE = "jump_display_page";
    public static final String EXTRA_JUMP_TOTAL_PAGES = "jump_total_pages";
    public static final String EXTRA_JUMP_PARTITION_START_BYTE = "jump_partition_start_byte";
    public static final String EXTRA_JUMP_PARTITION_START_LINE = "jump_partition_start_line";
    public static final String EXTRA_JUMP_ANCHOR_BEFORE = "jump_anchor_before";
    public static final String EXTRA_JUMP_ANCHOR_AFTER = "jump_anchor_after";

    static final String STATE_RESTORE_FROM_MEMORY = "restore_txt_from_memory";
    static final long BACKGROUND_MEMORY_TRIM_DELAY_MS = 420_000L;

    View readerRoot;
    CustomReaderView readerView;
    ProgressBar progressBar;
    TextView progressText;
    View loadingBox;
    View bottomBar;
    View navBarSpacer;
    View pageDragPanel;
    SeekBar seekBar;
    TextView positionLabel;
    TextView readerPageStatus;
    TextView readerFileTitle;
    Toolbar toolbar;

    String filePath;
    String fileName;
    String fileContent = "";
    int totalChars;
    int totalLines;

    BookmarkManager bookmarkManager;
    PrefsManager prefs;
    ThemeManager themeManager;

    boolean toolbarVisible = false;
    int currentReaderBackgroundColor = Color.BLACK;
    int currentReaderTextColor = Color.rgb(224, 224, 224);
    int currentReaderToolbarColor = Color.rgb(32, 32, 32);
    int lastReaderTopInset = 0;
    int lastReaderBottomInset = 0;
    int lastStatusOffExtraTopPadding = 0;
    private boolean scrollUpdateScheduled = false;
    int loadingWindowPartitionJumpGeneration = -1;
    String activeSearchQuery = "";
    int activeSearchIndex = -1;
    int activeSearchOrdinal = 0;
    final LargeTextSearchTotalCache largeTextSearchTotalCache = new LargeTextSearchTotalCache();
    LargeTextSearchEngine largeTextSearchEngine;
    ReaderToolbarController readerToolbarController;
    private ReaderDialogStyleController readerDialogStyleController;

    ReaderDialogStyleController dialogStyler() {
        if (readerDialogStyleController == null) {
            readerDialogStyleController = new ReaderDialogStyleController(this);
        }
        return readerDialogStyleController;
    }

    private ReaderBottomControlsController readerBottomControlsController;

    private ReaderBottomControlsController bottomControls() {
        if (readerBottomControlsController == null) {
            readerBottomControlsController = new ReaderBottomControlsController(this);
        }
        return readerBottomControlsController;
    }

    ReaderSeekController readerSeekController;

    ReaderSeekController readerSeek() {
        if (readerSeekController == null) {
            readerSeekController = new ReaderSeekController(this);
        }
        return readerSeekController;
    }

    ReaderShellController readerShellController;

    private ReaderShellController readerShell() {
        if (readerShellController == null) {
            readerShellController = new ReaderShellController(this);
        }
        return readerShellController;
    }

    private ReaderSearchController readerSearchController;

    private ReaderSearchController readerSearch() {
        if (readerSearchController == null) {
            readerSearchController = new ReaderSearchController(this);
        }
        return readerSearchController;
    }

    private ReaderLoadingWindowController readerLoadingWindowController;

    private ReaderLoadingWindowController loadingWindow() {
        if (readerLoadingWindowController == null) {
            readerLoadingWindowController = new ReaderLoadingWindowController(this);
        }
        return readerLoadingWindowController;
    }

    private ReaderChromeController readerChromeController;

    private ReaderChromeController chrome() {
        if (readerChromeController == null) {
            readerChromeController = new ReaderChromeController(this);
        }
        return readerChromeController;
    }


    private ReaderReloadController readerReloadController;

    private ReaderReloadController readerReload() {
        if (readerReloadController == null) {
            readerReloadController = new ReaderReloadController(this);
        }
        return readerReloadController;
    }

    private ReaderEncodingController readerEncodingController;

    private ReaderEncodingController readerEncoding() {
        if (readerEncodingController == null) {
            readerEncodingController = new ReaderEncodingController(this);
        }
        return readerEncodingController;
    }

    private ReaderBookmarkActionController readerBookmarkActionController;

    private ReaderBookmarkActionController readerBookmarkActions() {
        if (readerBookmarkActionController == null) {
            readerBookmarkActionController = new ReaderBookmarkActionController(this);
        }
        return readerBookmarkActionController;
    }

    private ReaderBookmarkPageModelController readerBookmarkPageModelController;

    ReaderBookmarkPageModelController bookmarkPageModel() {
        if (readerBookmarkPageModelController == null) {
            readerBookmarkPageModelController = new ReaderBookmarkPageModelController(this);
        }
        return readerBookmarkPageModelController;
    }

    private ReaderBookmarkNavigator readerBookmarkNavigator;

    private ReaderBookmarkNavigator bookmarkNavigator() {
        if (readerBookmarkNavigator == null) {
            readerBookmarkNavigator = new ReaderBookmarkNavigator(this);
        }
        return readerBookmarkNavigator;
    }

    private ReaderMemoryController readerMemoryController;

    private ReaderMemoryController readerMemory() {
        if (readerMemoryController == null) {
            readerMemoryController = new ReaderMemoryController(this);
        }
        return readerMemoryController;
    }

    private ReaderLifecycleController readerLifecycleController;

    private ReaderLifecycleController readerLifecycle() {
        if (readerLifecycleController == null) {
            readerLifecycleController = new ReaderLifecycleController(this);
        }
        return readerLifecycleController;
    }

    private ReaderTextLocator readerTextLocator;

    private ReaderTextLocator textLocator() {
        if (readerTextLocator == null) {
            readerTextLocator = new ReaderTextLocator(this);
        }
        return readerTextLocator;
    }

    private ReaderActionController readerActionController;

    private ReaderActionController readerActions() {
        if (readerActionController == null) {
            readerActionController = new ReaderActionController(this);
        }
        return readerActionController;
    }

    private ReaderTtsController readerTtsController;

    private ReaderTtsController readerTts() {
        if (readerTtsController == null) {
            readerTtsController = new ReaderTtsController(this);
        }
        return readerTtsController;
    }

    private ReaderPageJumpController readerPageJumpController;

    private ReaderPageJumpController pageJumps() {
        if (readerPageJumpController == null) {
            readerPageJumpController = new ReaderPageJumpController(this);
        }
        return readerPageJumpController;
    }

    private ReaderLargeTextPartitionNavigator readerLargeTextPartitionNavigator;

    private ReaderLargeTextPartitionNavigator largeTextPartitionNavigator() {
        if (readerLargeTextPartitionNavigator == null) {
            readerLargeTextPartitionNavigator = new ReaderLargeTextPartitionNavigator(this);
        }
        return readerLargeTextPartitionNavigator;
    }

    private ReaderLargeTextPartitionApplier readerLargeTextPartitionApplier;

    private ReaderLargeTextPartitionApplier largeTextPartitionApplier() {
        if (readerLargeTextPartitionApplier == null) {
            readerLargeTextPartitionApplier = new ReaderLargeTextPartitionApplier(this);
        }
        return readerLargeTextPartitionApplier;
    }

    private ReaderLargeTextExactPageIndexController readerLargeTextExactPageIndexController;

    private ReaderLargeTextExactPageIndexController exactPageIndex() {
        if (readerLargeTextExactPageIndexController == null) {
            readerLargeTextExactPageIndexController = new ReaderLargeTextExactPageIndexController(this);
        }
        return readerLargeTextExactPageIndexController;
    }

    private ReaderTapNavigationController readerTapNavigationController;

    ReaderTapNavigationController tapNavigation() {
        if (readerTapNavigationController == null) {
            readerTapNavigationController = new ReaderTapNavigationController(this);
        }
        return readerTapNavigationController;
    }

    private ReaderFileLoadController readerFileLoadController;
    private ReaderFileApplyController readerFileApplyController;
    private ReaderLargeTextBoundaryHandoffController readerLargeTextBoundaryHandoffController;
    private ReaderLargeTextPagingController readerLargeTextPagingController;
    private ReaderLargeTextPartitionPrefetchController readerLargeTextPartitionPrefetchController;
    private ReaderLargeTextExactAnchorBuildController readerLargeTextExactAnchorBuildController;
    private ReaderLargeTextPartitionReadController readerLargeTextPartitionReadController;
    private ReaderLoadedTextSnapshotController readerLoadedTextSnapshotController;
    private ReaderLargeTextJumpController readerLargeTextJumpController;
    private ReaderPagePositionController readerPagePositionController;
    private ReaderActivityStartupController readerActivityStartupController;
    private ReaderLargeTextCacheController readerLargeTextCacheController;
    private ReaderLargeTextStateController readerLargeTextStateController;

    private ReaderFileLoadController fileLoader() {
        if (readerFileLoadController == null) {
            readerFileLoadController = new ReaderFileLoadController(this);
        }
        return readerFileLoadController;
    }

    ReaderFileApplyController fileApplier() {
        if (readerFileApplyController == null) {
            readerFileApplyController = new ReaderFileApplyController(this);
        }
        return readerFileApplyController;
    }

    private ReaderLargeTextBoundaryHandoffController boundaryHandoff() {
        if (readerLargeTextBoundaryHandoffController == null) {
            readerLargeTextBoundaryHandoffController = new ReaderLargeTextBoundaryHandoffController(this);
        }
        return readerLargeTextBoundaryHandoffController;
    }

    private ReaderLargeTextPagingController largeTextPaging() {
        if (readerLargeTextPagingController == null) {
            readerLargeTextPagingController = new ReaderLargeTextPagingController(this);
        }
        return readerLargeTextPagingController;
    }

    private ReaderLargeTextPartitionPrefetchController partitionPrefetch() {
        if (readerLargeTextPartitionPrefetchController == null) {
            readerLargeTextPartitionPrefetchController =
                    new ReaderLargeTextPartitionPrefetchController(this);
        }
        return readerLargeTextPartitionPrefetchController;
    }

    ReaderLargeTextExactAnchorBuildController exactAnchorBuilder() {
        if (readerLargeTextExactAnchorBuildController == null) {
            readerLargeTextExactAnchorBuildController =
                    new ReaderLargeTextExactAnchorBuildController(this);
        }
        return readerLargeTextExactAnchorBuildController;
    }

    private ReaderLargeTextPartitionReadController partitionReader() {
        if (readerLargeTextPartitionReadController == null) {
            readerLargeTextPartitionReadController =
                    new ReaderLargeTextPartitionReadController(this);
        }
        return readerLargeTextPartitionReadController;
    }

    private ReaderLoadedTextSnapshotController loadedTextSnapshots() {
        if (readerLoadedTextSnapshotController == null) {
            readerLoadedTextSnapshotController = new ReaderLoadedTextSnapshotController(this);
        }
        return readerLoadedTextSnapshotController;
    }

    private ReaderLargeTextJumpController largeTextJumps() {
        if (readerLargeTextJumpController == null) {
            readerLargeTextJumpController = new ReaderLargeTextJumpController(this);
        }
        return readerLargeTextJumpController;
    }

    private ReaderPagePositionController pagePositions() {
        if (readerPagePositionController == null) {
            readerPagePositionController = new ReaderPagePositionController(this);
        }
        return readerPagePositionController;
    }

    private ReaderActivityStartupController startup() {
        if (readerActivityStartupController == null) {
            readerActivityStartupController = new ReaderActivityStartupController(this);
        }
        return readerActivityStartupController;
    }

    private ReaderLargeTextCacheController largeTextCaches() {
        if (readerLargeTextCacheController == null) {
            readerLargeTextCacheController = new ReaderLargeTextCacheController(this);
        }
        return readerLargeTextCacheController;
    }

    private ReaderLargeTextStateController largeTextState() {
        if (readerLargeTextStateController == null) {
            readerLargeTextStateController = new ReaderLargeTextStateController(this);
        }
        return readerLargeTextStateController;
    }

    int appliedLargeTextPartitionMode = Integer.MIN_VALUE;
    final Handler handler = new Handler(Looper.getMainLooper());
    boolean backgroundTextMemoryReleased = false;
    Intent backgroundTextRestoreIntent = null;
    final Runnable backgroundMemoryTrimRunnable = () -> trimReaderMemoryForBackground(false);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final ExecutorService largeTextPartitionExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService largeTextSearchExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService largeTextSearchCountExecutor = Executors.newSingleThreadExecutor();
    volatile boolean activityDestroyed = false;
    final AtomicInteger loadGeneration = new AtomicInteger(0);
    final AtomicInteger largeTextSearchGeneration = new AtomicInteger(0);
    final AtomicInteger largeTextSearchCountGeneration = new AtomicInteger(0);
    final AtomicInteger largeTextPartitionSwitchGeneration = new AtomicInteger(0);
    final LargeTextPartitionCache largeTextPartitionCache = new LargeTextPartitionCache();
    boolean largeTextEstimateActive = false;
    int largeTextEstimatedTotalPages = 0;
    final Runnable largeTextRestartIndexingRunnable = () -> exactPageIndex().handleRestartIndexingTick();
    final LargeTextPartitionSwitchState largeTextPartitionSwitchState = new LargeTextPartitionSwitchState();
    int pendingLargeTextRestorePosition = -1;
    int largeTextPreviewBaseCharOffset = 0;
    int largeTextEstimatedBasePageOffset = 0;
    int largeTextEstimatedTotalChars = 0;
    boolean hugeTextPreviewOnly = false;
    int pendingLargeTextCachedDisplayPage = 0;
    int pendingLargeTextCachedTotalPages = 0;
    long largeTextPartitionStartByte = 0L;
    long largeTextPartitionEndByte = 0L;
    long largeTextFileByteLength = 0L;
    float largeTextEstimatedBytesPerChar = 1f;
    int largeTextPartitionBodyStartCharCount = 0;
    int largeTextPartitionBodyCharCount = 0;
    int largeTextPartitionWindowStartLine = 1;
    boolean largeTextActivePartitionUsesLookbehind = false;
    int largeTextPartitionStartLine = 1;
    int largeTextPartitionEndLine = 1;
    int largeTextTotalLogicalLines = 1;
    final LargeTextExactPageIndexState largeTextExactPageIndexState =
            new LargeTextExactPageIndexState();
    String appliedTextDisplayRuleSignature = "none";
    boolean pendingTxtDisplayRuleContentRefresh = false;
    final LargeTextPageDirectionState largeTextPageDirectionState = new LargeTextPageDirectionState();
    final Runnable largeTextManualScrollBoundaryHandoffRunnable =
            this::handleLargeTextManualScrollBoundaryHandoff;
    AutoPageTurnController autoPageTurnController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        appliedLargeTextPartitionMode = prefs.getLargeTextPartitionMode();
        prefs.applyLanguage(prefs.getLanguageMode());
        super.onCreate(savedInstanceState);
        startup().onCreateAfterSuper(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startup().onResume();
    }

    boolean maybeReloadForPhysicallyEditedOriginalTxtFile() {
        return readerReload().maybeReloadForPhysicallyEditedOriginalTxtFile();
    }

    boolean maybeReloadForLargeTextPartitionModeChange() {
        return readerReload().maybeReloadForLargeTextPartitionModeChange();
    }

    void maybeReloadForTextDisplayRuleChange() {
        readerReload().maybeReloadForTextDisplayRuleChange();
    }

    String currentTextDisplayRuleSignature() { return readerReload().currentTextDisplayRuleSignature(); }

    void requestTextDisplayRuleContentRefreshOnWindowClose() {
        readerReload().requestTextDisplayRuleContentRefreshOnWindowClose();
    }

    void acknowledgeTextDisplayRuleWindowNoContentChange() {
        readerReload().acknowledgeTextDisplayRuleWindowNoContentChange();
    }

    void applyPendingTextDisplayRuleWindowRefresh() {
        readerReload().applyPendingTextDisplayRuleWindowRefresh();
    }

    boolean sameTextDisplayRuleValue(String a, String b) { return readerReload().sameTextDisplayRuleValue(a, b); }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        startup().onNewIntent(intent);
    }

    void cacheLoadedTextSnapshot() {
        loadedTextSnapshots().cacheLoadedTextSnapshot();
    }

    void clearLoadedTextSnapshot() {
        loadedTextSnapshots().clearLoadedTextSnapshot();
    }

    boolean restoreLoadedTextSnapshotIfAvailable(@NonNull Intent intent, Bundle savedInstanceState) {
        return loadedTextSnapshots().restoreLoadedTextSnapshotIfAvailable(intent, savedInstanceState);
    }

    void previewToolbarSeekPage(int page, int totalPages) {
        readerSeek().previewToolbarSeekPage(page, totalPages);
    }

    void beginPendingToolbarSeekJump(int page, int totalPages) {
        readerSeek().beginPendingToolbarSeekJump(page, totalPages);
    }

    void clearPendingToolbarSeekJump() {
        if (readerSeekController != null) readerSeekController.clearPendingToolbarSeekJump();
    }

    void applyPreferences() {
        new ReaderPreferencesController(this).applyPreferences();
    }

    void applyStatusBarVisibilityPreference() {
        chrome().applyStatusBarVisibilityPreference();
    }


    void applyReaderSystemBarColors(int backgroundColor, int textColor, int toolbarColor) {
        chrome().applyReaderSystemBarColors(backgroundColor, textColor, toolbarColor);
    }

     boolean isLightColor(int color) {
        return dialogStyler().isLightColor(color);
    }

    boolean isDarkColor(int color) {
        return dialogStyler().isDarkColor(color);
    }


    void updateLoadingIndicatorColors(int backgroundColor) {
        loadingWindow().updateLoadingIndicatorColors(backgroundColor);
    }

    void showLoadingWindow() {
        loadingWindow().showLoadingWindow();
    }

    void hideLoadingWindow() {
        loadingWindow().hideLoadingWindow();
    }

    void showLoadingWindowForPartitionJump(int switchGeneration) {
        loadingWindow().showLoadingWindowForPartitionJump(switchGeneration);
    }

    void hideLoadingWindowForPartitionJumpIfCurrent(boolean shouldHide, int switchGeneration) {
        loadingWindow().hideLoadingWindowForPartitionJumpIfCurrent(shouldHide, switchGeneration);
    }

    void updateBottomMenuBackground() {
        chrome().updateBottomMenuBackground();
    }

    void updateNavigationBarForBottomMenu() {
        chrome().updateNavigationBarForBottomMenu();
    }

    void applyTheme() {
        chrome().applyTheme();
    }

    void applyReaderInsets() {
        chrome().applyReaderInsets();
    }

    void updateReaderFileTitleMaskBounds() {
        chrome().updateReaderFileTitleMaskBounds();
    }


    void updateReaderContentTopPadding() {
        chrome().updateReaderContentTopPadding();
    }

    int getStableStatusOffTopPaddingPx() {
        return chrome().getStableStatusOffTopPaddingPx();
    }

    /**
     * Canonical bottom padding for the readerView in pixels. Independent of the
     * live system navigation bar inset AND of the user's current font size.
     *
     * <p>This is the central invariant that keeps the large-TXT exact page count
     * deterministic across runs: viewport height feeds page anchors, and page
     * anchors decide the total page count. If viewport varies by even 1px because
     * the OS navigation bar settled to a slightly different inset between runs,
     * a multi-thousand-page TXT can accumulate that drift into a different total.
     *
     * <p>The actual navigation bar is still visually covered by {@code navBarSpacer},
     * which sits in the FrameLayout at bottom-gravity with an opaque background;
     * text painted within this canonical bottom band is hidden by that spacer.
     * The constant ~60dp is chosen to match what 3-button-nav users already saw
     * (their old "bottomInset + 12dp" ~= 60dp). Gesture-nav users get a slightly
     * larger bottom gap, but it sits behind navBarSpacer's opaque band.
     */
    int getStableReaderBottomPaddingPx() {
        return chrome().getStableReaderBottomPaddingPx();
    }

    int getLargeTextPartitionLines() {
        return largeTextState().partitionLines();
    }

    int getLargeTextPartitionBufferLines() {
        return largeTextState().partitionBufferLines();
    }

    int getLargeTextPartitionLookaheadLines() {
        return largeTextState().partitionLookaheadLines();
    }

    int getLargeTextPartitionLookbehindLines() {
        return largeTextState().partitionLookbehindLines();
    }

    int getLargeTextPartitionMode() {
        return largeTextState().partitionMode();
    }

    int getReaderPageStatusBaseHeight() {
        return chrome().getReaderPageStatusBaseHeight();
    }

    int getReaderPageStatusVisualHeight() {
        return chrome().getReaderPageStatusVisualHeight();
    }

    int getReaderContentTopPadding() {
        return chrome().getReaderContentTopPadding();
    }

    void applyPageStatusAlignment(int topInset) {
        chrome().applyPageStatusAlignment(topInset);
    }

    void updateReaderFileTitle() {
        chrome().updateReaderFileTitle();
    }

    void updateReaderFileTitleVisibility() {
        chrome().updateReaderFileTitleVisibility();
    }


    void openFileBrowserFromViewer() {
        readerActions().openFileBrowserFromViewer();
    }

    void openHomeFromViewer() {
        readerActions().openHomeFromViewer();
    }

    void setupBottomControls() {
        bottomControls().setupBottomControls();
    }

    void showPageMoveBubble() {
        bottomControls().showPageMoveBubble();
    }

    String formatPageMoveLabel(int page, int totalPages) {
        return bottomControls().formatPageMoveLabel(page, totalPages,
                getLineOffsetWithinDisplayedPage(page, totalPages));
    }

    String formatPageMoveLabel(int page, String totalPagesText, int lineOffset) {
        return bottomControls().formatPageMoveLabel(page, totalPagesText, lineOffset);
    }

    void showMoreDialog() {
        new ReaderToolsDialogController(this).showMoreDialog();
    }

    String resolveTextEncodingForFile(@NonNull File file) {
        return readerEncoding().resolveTextEncodingForFile(file);
    }

    void showTextEncodingDialog() {
        new ReaderToolsDialogController(this).showTextEncodingDialog();
    }

    void applyTextEncodingSelection(@NonNull File file, @NonNull String option) {
        readerEncoding().applyTextEncodingSelection(file, option);
    }

    void showQuickTextDisplayRuleDialog(String prefillFind, boolean defaultCurrentFileOnly) {
        new ReaderTextDisplayRuleDialogController(this).showQuickTextDisplayRuleDialog(prefillFind, defaultCurrentFileOnly);
    }

    void showReaderTextDisplayRulesManagerDialog() {
        new ReaderTextDisplayRuleDialogController(this).showReaderTextDisplayRulesManagerDialog();
    }

    void showAutoPageTurnDialog() {
        new ReaderToolsDialogController(this).showAutoPageTurnDialog();
    }

    void showTtsDialog() {
        readerTts().showDialog();
    }

    void handleTtsPlaybackCommand(@NonNull String action) {
        readerTts().handlePlaybackCommand(action);
    }

    boolean isTtsActive() {
        return readerTtsController != null && readerTtsController.isActive();
    }

    void startAutoPageTurn() {
        readerActions().startAutoPageTurn();
    }

    void stopAutoPageTurn(boolean showToast) {
        readerActions().stopAutoPageTurn(showToast);
    }

    void stopAutoPageTurnForManualNavigation() {
        readerActions().stopAutoPageTurnForManualNavigation();
        stopTtsForManualNavigation();
    }

    void stopTts(boolean showToast) {
        if (readerTtsController != null) readerTtsController.stop(showToast);
    }

    void stopTtsForManualNavigation() {
        if (readerTtsController != null) readerTtsController.stopForManualNavigation();
    }

    void releaseTts() {
        if (readerTtsController != null) {
            readerTtsController.release();
            readerTtsController = null;
        }
    }

    void loadFileFromIntent(@NonNull Intent sourceIntent) {
        fileLoader().loadFileFromIntent(sourceIntent);
    }

    void recordLargeTextCacheAccess(@NonNull File file) {
        largeTextCaches().recordFileAccess(file);
    }

    boolean shouldUseLargeTextFastOpen(@NonNull File file) {
        return largeTextCaches().shouldUseFastOpen(file);
    }

    void resetLargeTextExactPageIndex() {
        exactPageIndex().reset();
    }

    void invalidateLargeTextExactPageIndexBuild() {
        exactPageIndex().invalidateBuild();
    }

    String buildCurrentLargeTextExactPageIndexSignature(@NonNull String loadedFilePath) {
        return exactPageIndex().buildCurrentSignature(loadedFilePath);
    }
    void clearLargeTextPartitionCache() {
        largeTextCaches().clearPartitionCache();
    }

    void cacheLargeTextPartition(@NonNull LargeTextLinePartitionResult partition) {
        largeTextCaches().cachePartition(partition);
    }

    LargeTextLinePartitionResult getCachedLargeTextPartitionByStartLine(int startLine) {
        return largeTextCaches().getPartitionByStartLine(startLine);
    }

    LargeTextLinePartitionResult getCachedLargeTextManualHandoffPartitionByStartLine(int startLine) {
        return largeTextCaches().getManualHandoffPartitionByStartLine(startLine);
    }

    LargeTextLinePartitionResult getCachedLargeTextPartitionForChar(int absoluteCharPosition) {
        return largeTextCaches().getPartitionForChar(absoluteCharPosition);
    }

    boolean markLargeTextPartitionPrefetchPending(int startLine) {
        return largeTextCaches().markPartitionPrefetchPending(startLine);
    }

    boolean markLargeTextManualHandoffPartitionPrefetchPending(int startLine) {
        return largeTextCaches().markManualHandoffPartitionPrefetchPending(startLine);
    }

    void unmarkLargeTextPartitionPrefetchPending(int startLine) {
        largeTextCaches().unmarkPartitionPrefetchPending(startLine);
    }

    void unmarkLargeTextManualHandoffPartitionPrefetchPending(int startLine) {
        largeTextCaches().unmarkManualHandoffPartitionPrefetchPending(startLine);
    }

    boolean isLargeTextExactPageIndexReady() {
        return exactPageIndex().isReady();
    }

    int getLargeTextExactPageCountIfReady() {
        return exactPageIndex().readyPageCount();
    }

    int findExactLargeTextPageForChar(int charPosition) {
        return exactPageIndex().findPageForChar(charPosition);
    }

    int findExactLargeTextPageForChar(@NonNull ArrayList<CustomReaderView.PageTextAnchor> anchors,
                                              int charPosition) {
        return exactPageIndex().findPageForChar(anchors, charPosition);
    }
    @NonNull
    String buildCurrentLargeTextBookmarkPageSignature() {
        return bookmarkPageModel().buildCurrentLargeTextBookmarkPageSignature();
    }

    @Nullable
    ArrayList<CustomReaderView.PageTextAnchor> copyCurrentLargeTextExactPageAnchorsIfUsable(@NonNull String currentSignature) {
        return largeTextExactPageIndexState.copyAnchorsIfUsable(currentSignature);
    }

    @NonNull
    String trustedCurrentBookmarkPageSignature() {
        return bookmarkPageModel().trustedCurrentBookmarkPageSignature();
    }

    void syncCurrentFileBookmarksToLargeTextExactPageModel() {
        bookmarkPageModel().syncCurrentFileBookmarksToLargeTextExactPageModel();
    }

    int[] resolveBookmarkDisplayPageTarget(@NonNull Bookmark bookmark, int absoluteCharPosition) {
        return bookmarkPageModel().resolveBookmarkDisplayPageTarget(bookmark, absoluteCharPosition);
    }

    CustomReaderView.PageTextAnchor getExactLargeTextAnchorForPage(int page) {
        return exactPageIndex().getAnchorForPage(page);
    }

    boolean isLargeTextExactPageIndexFailed() {
        return exactPageIndex().isFailed();
    }
    int estimateCharPositionForLargeTextLine(int targetLine) {
        return exactPageIndex().estimateCharPositionForLine(targetLine);
    }

    boolean jumpToEstimatedLargeTextPage(int page, int totalPages, boolean showLoadingForAsyncPartitionJump) {
        return pageJumps().jumpToEstimatedLargeTextPage(page, totalPages, showLoadingForAsyncPartitionJump);
    }

    boolean jumpToFinalLargeTextPage(int totalPages, boolean showLoadingForAsyncPartitionJump) {
        return pageJumps().jumpToFinalLargeTextPage(totalPages, showLoadingForAsyncPartitionJump);
    }

    void scheduleLargeTextExactPageIndexingRestart() {
        exactPageIndex().scheduleRestart();
    }

    void scheduleLargeTextExactPageIndexingRestartForUserPageModelChange() {
        exactPageIndex().scheduleRestartForUserPageModelChange();
    }

    void startLargeTextExactPageIndexing(@NonNull String loadedFilePath) {
        exactPageIndex().startIndexing(loadedFilePath);
    }
    float estimateBytesPerChar(@NonNull File file) {
        return partitionReader().estimateBytesPerChar(file);
    }

    int getLargeTextPartitionStartLineForLine(int line) {
        return largeTextState().partitionStartLineForLine(line);
    }

    BufferedReader openLargeTextReader(@NonNull File file) throws IOException {
        return partitionReader().openReader(file);
    }

    LargeTextLinePartitionResult readLargeTextLinePartitionForChar(@NonNull File file,
                                                                           int targetCharPosition) throws IOException {
        return partitionReader().readForChar(file, targetCharPosition);
    }

    LargeTextLinePartitionResult readLargeTextLinePartitionAtStartLine(@NonNull File file,
                                                                               int requestedStartLine) throws IOException {
        return partitionReader().readAtStartLine(file, requestedStartLine);
    }

    LargeTextLinePartitionResult readLargeTextLinePartitionAtStartLine(@NonNull File file,
                                                                               int requestedStartLine,
                                                                               int knownTotalLines,
                                                                               int knownTotalChars) throws IOException {
        return readLargeTextLinePartitionAtStartLine(file, requestedStartLine, knownTotalLines, knownTotalChars, false);
    }

    LargeTextLinePartitionResult readLargeTextLinePartitionAtStartLine(@NonNull File file,
                                                                               int requestedStartLine,
                                                                               int knownTotalLines,
                                                                               int knownTotalChars,
                                                                               boolean includeLookbehind) throws IOException {
        return partitionReader().readAtStartLine(
                file, requestedStartLine, knownTotalLines, knownTotalChars, includeLookbehind);
    }

    int estimateDisplayedPageForLargeTextLine(int lineNumber, int totalPages) {
        return largeTextState().estimateDisplayedPageForLine(lineNumber, totalPages);
    }

    // --- Scroll & position ---

    void onScrollChanged() {
        updateReaderFileTitleMaskBounds();
        schedulePositionUpdate();
        scheduleLargeTextManualScrollBoundaryHandoff();
    }

    private void schedulePositionUpdate() {
        if (scrollUpdateScheduled) return;
        scrollUpdateScheduled = true;
        handler.postDelayed(() -> {
            scrollUpdateScheduled = false;
            updatePositionLabel();
        }, 80);
    }

    private void scheduleLargeTextManualScrollBoundaryHandoff() {
        boundaryHandoff().scheduleManualScrollBoundaryHandoff();
    }

    private void handleLargeTextManualScrollBoundaryHandoff() {
        boundaryHandoff().handleManualScrollBoundaryHandoff();
    }

    void handleLargeTextManualOverscroll(int direction) {
        boundaryHandoff().handleManualOverscroll(direction);
    }


    boolean hasNextLargeTextPartition() {
        return largeTextState().hasNextPartition();
    }

    boolean hasPreviousLargeTextPartition() {
        return largeTextState().hasPreviousPartition();
    }

    int getLastLocalPageStartingInsideLargeTextPartition() {
        return largeTextState().lastLocalPageStartingInsidePartition();
    }

    void reloadLargeTextPartitionByStartLine(int startLine, int displayPage, int totalPages) {
        partitionPrefetch().reloadPartitionByStartLine(startLine, displayPage, totalPages);
    }

    void prefetchNeighborLargeTextPartitions() {
        partitionPrefetch().prefetchNeighborPartitions();
    }

    void prefetchLargeTextManualHandoffPartitionByStartLine(int requestedStartLine) {
        partitionPrefetch().prefetchManualHandoffPartitionByStartLine(requestedStartLine);
    }

    void beginLargeTextPartitionSwitchPending(int displayPage, int totalPages) {
        largeTextState().beginPartitionSwitchPending(displayPage, totalPages);
    }

    void clearLargeTextPartitionSwitchPending() {
        largeTextState().clearPartitionSwitchPending();
    }

    void switchLargeTextPartitionInPlace(int charPosition,
                                                int displayPage,
                                                int totalPages,
                                                String anchorBefore,
                                                String anchorAfter,
                                                int partitionStartLine,
                                                boolean showLoadingForAsyncPartitionJump,
                                                boolean useManualLookbehind) {
        largeTextPartitionNavigator().switchInPlace(
                charPosition,
                displayPage,
                totalPages,
                anchorBefore,
                anchorAfter,
                partitionStartLine,
                showLoadingForAsyncPartitionJump,
                useManualLookbehind);
    }

    void applyLargeTextPartitionInPlace(@NonNull LargeTextLinePartitionResult partition,
                                                int targetCharPosition,
                                                int displayPage,
                                                int totalPages,
                                                String anchorBefore,
                                                String anchorAfter) {
        applyLargeTextPartitionInPlace(partition, targetCharPosition, displayPage, totalPages,
                anchorBefore, anchorAfter, false, largeTextPartitionSwitchGeneration.get());
    }

    void applyLargeTextPartitionInPlace(@NonNull LargeTextLinePartitionResult partition,
                                                int targetCharPosition,
                                                int displayPage,
                                                int totalPages,
                                                String anchorBefore,
                                                String anchorAfter,
                                                boolean hideLoadingAfterApply,
                                                int switchGeneration) {
        largeTextPartitionApplier().apply(
                partition,
                targetCharPosition,
                displayPage,
                totalPages,
                anchorBefore,
                anchorAfter,
                hideLoadingAfterApply,
                switchGeneration);
    }

    void recomputeLargeTextDisplayPageOffset(int displayPage, int totalPages) {
        pagePositions().recomputeLargeTextDisplayPageOffset(displayPage, totalPages);
    }

    int getTotalPageCount() { return pagePositions().getTotalPageCount(); }

    int getDisplayedTotalPageCount() {
        return pagePositions().getDisplayedTotalPageCount();
    }

    int getCurrentPageNumber() { return pagePositions().getCurrentPageNumber(); }

    int getDisplayedCurrentPageNumber() {
        return pagePositions().getDisplayedCurrentPageNumber();
    }

    int getLineOffsetWithinDisplayedPage(int page, int totalPages) {
        if (readerView == null) return 1;

        int total = Math.max(1, totalPages);
        int target = Math.max(1, Math.min(total, page));
        int currentPage = Math.max(1, Math.min(total, getDisplayedCurrentPageNumber()));

        // The parenthesized value in the TXT page label is an in-page row offset,
        // not the document-wide logical line number.  Use the rendered page
        // anchors from CustomReaderView so large-TXT partitions cannot leak
        // absolute lines such as 1975/2000 into the UI.  Non-current target
        // pages in the jump dialog are shown at their page start.
        if (target != currentPage) return 1;
        return Math.max(1, readerView.getCurrentLineOffsetWithinPage());
    }

    int getCurrentVisibleLineNumber() {
        return Math.max(1, countLinesUntilChar(getCurrentCharPosition()));
    }

    boolean scrollToPageNumber(int page) {
        return pageJumps().scrollToPageNumber(page);
    }

    boolean scrollToPageNumber(int page, boolean showLoadingForAsyncPartitionJump) {
        return pageJumps().scrollToPageNumber(page, showLoadingForAsyncPartitionJump);
    }

    boolean scrollToPageNumber(int page, boolean showLoadingForAsyncPartitionJump, boolean manualNavigation) {
        return pageJumps().scrollToPageNumber(page, showLoadingForAsyncPartitionJump, manualNavigation);
    }

    void updatePositionLabel() {
        pagePositions().updatePositionLabel();
    }

    void setPageLabels(int currentPage, int totalPages) {
        pagePositions().setPageLabels(currentPage, totalPages);
    }

    void scrollToPercent(float percent) { pagePositions().scrollToPercent(percent); }

    void scrollToCharPosition(int charPosition) {
        pagePositions().scrollToCharPosition(charPosition);
    }

    void scrollToSearchResultPosition(int charPosition) {
        pagePositions().scrollToSearchResultPosition(charPosition);
    }

    boolean isActiveSearchTarget(int absolutePosition) {
        return pagePositions().isActiveSearchTarget(absolutePosition);
    }

    void jumpToAbsoluteCharPosition(int charPosition) {
        jumpToAbsoluteCharPosition(charPosition, 0, 0, null, null);
    }

    void jumpToAbsoluteCharPosition(int charPosition, int displayPage, int totalPages) {
        jumpToAbsoluteCharPosition(charPosition, displayPage, totalPages, null, null);
    }

    void jumpToAbsoluteCharPosition(int charPosition,
                                            int displayPage,
                                            int totalPages,
                                            String anchorBefore,
                                            String anchorAfter,
                                            boolean showLoadingForAsyncPartitionJump) {
        largeTextJumps().jumpToAbsoluteCharPosition(
                charPosition, displayPage, totalPages, anchorBefore, anchorAfter,
                showLoadingForAsyncPartitionJump);
    }

    boolean isAbsoluteCharPositionInCurrentLargeTextBody(int absolutePosition) {
        return largeTextJumps().isAbsoluteCharPositionInCurrentLargeTextBody(absolutePosition);
    }

    void jumpToAbsoluteCharPosition(int charPosition,
                                            int displayPage,
                                            int totalPages,
                                            String anchorBefore,
                                            String anchorAfter) {
        largeTextJumps().jumpToAbsoluteCharPosition(
                charPosition,
                displayPage,
                totalPages,
                anchorBefore,
                anchorAfter,
                false);
    }

    void reloadLargeTextPreviewAround(int charPosition, int displayPage, int totalPages) {
        reloadLargeTextPreviewAround(charPosition, displayPage, totalPages, null, null);
    }

    void reloadLargeTextPreviewAround(int charPosition,
                                              int displayPage,
                                              int totalPages,
                                              String anchorBefore,
                                              String anchorAfter) {
        reloadLargeTextPreviewAround(charPosition, displayPage, totalPages, anchorBefore, anchorAfter, false);
    }

    void reloadLargeTextPreviewAround(int charPosition,
                                              int displayPage,
                                              int totalPages,
                                              String anchorBefore,
                                              String anchorAfter,
                                              boolean showLoadingForAsyncPartitionJump) {
        reloadLargeTextPreviewAround(charPosition, displayPage, totalPages, anchorBefore, anchorAfter, -1,
                showLoadingForAsyncPartitionJump);
    }

    void reloadLargeTextPreviewAround(int charPosition,
                                              int displayPage,
                                              int totalPages,
                                              String anchorBefore,
                                              String anchorAfter,
                                              int partitionStartLine) {
        reloadLargeTextPreviewAround(charPosition, displayPage, totalPages, anchorBefore, anchorAfter,
                partitionStartLine, false);
    }

    void reloadLargeTextPreviewAround(int charPosition,
                                              int displayPage,
                                              int totalPages,
                                              String anchorBefore,
                                              String anchorAfter,
                                              int partitionStartLine,
                                              boolean showLoadingForAsyncPartitionJump) {
        largeTextJumps().reloadLargeTextPreviewAround(
                charPosition,
                displayPage,
                totalPages,
                anchorBefore,
                anchorAfter,
                partitionStartLine,
                showLoadingForAsyncPartitionJump);
    }

    void applySearchHighlight() {
        readerSearch().applySearchHighlight();
    }

    void pageDown() { pageBy(+1, false); }
    void pageUp() { pageBy(-1, false); }

    void recordLargeTextPageDirection(int direction) {
        largeTextPageDirectionState.record(largeTextEstimateActive, direction);
    }

    void resetLargeTextPageDirectionTracking() {
        largeTextPageDirectionState.reset();
    }

    void clearLargeTextQueuedPageDelta() {
        largeTextPartitionSwitchState.clearQueuedDelta();
    }

    void processQueuedLargeTextPageDeltaAfterPartitionApply() {
        largeTextPaging().processQueuedLargeTextPageDeltaAfterPartitionApply();
    }

    void pageBy(int direction) {
        pageBy(direction, false);
    }

    void pageBy(int direction, boolean fromAutoPageTurn) {
        largeTextPaging().pageBy(direction, fromAutoPageTurn);
    }

    int getCurrentCharPosition() {
        return textLocator().getCurrentCharPosition();
    }

    int getBookmarkSaveCharPosition() {
        return textLocator().getBookmarkSaveCharPosition();
    }

    String getExcerpt(int charPosition) {
        return textLocator().getExcerpt(charPosition);
    }

    String getAnchorTextBefore(int charPosition) {
        return textLocator().getAnchorTextBefore(charPosition);
    }

    String getAnchorTextAfter(int charPosition) {
        return textLocator().getAnchorTextAfter(charPosition);
    }

    int resolveAnchoredAbsolutePosition(String content,
                                                int baseCharOffset,
                                                int fallbackAbsolutePosition,
                                                String anchorBefore,
                                                String anchorAfter) {
        return textLocator().resolveAnchoredAbsolutePosition(content, baseCharOffset,
                fallbackAbsolutePosition, anchorBefore, anchorAfter);
    }

    int countLines(String s) {
        return textLocator().countLines(s);
    }

    int countLinesUntilChar(int charPosition) {
        return textLocator().countLinesUntilChar(charPosition);
    }

    int findText(String query, int startPosition) {
        return textLocator().findText(query, startPosition);
    }

    int findTextBackward(String query, int startPosition) {
        return textLocator().findTextBackward(query, startPosition);
    }

    int countTextMatches(String query) {
        return textLocator().countTextMatches(query);
    }

    int findNthText(String query, int occurrence) {
        return textLocator().findNthText(query, occurrence);
    }

    int matchIndexForPosition(String query, int position) {
        return textLocator().matchIndexForPosition(query, position);
    }

    int findCharForLine(int targetLine) {
        return textLocator().findCharForLine(targetLine);
    }

    // --- Toolbar toggle ---

    void toggleToolbar() {
        readerShell().toggleToolbar();
    }

    void showToolbar() {
        readerShell().showToolbar();
    }

    // --- Hardware page-turn keys ---

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (readerShell().handleReaderPageTurnKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Fallback for devices that route hardware keys through onKeyDown() instead
        // of dispatchKeyEvent(). dispatchKeyEvent() normally consumes these first.
        if (readerShell().handleReaderPageTurnKey(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // --- Bookmark operations ---
    //
    // Tek View-style behavior:
    // - The 북마크 button is one bookmark manager, not separate save/load modes.
    // - It always shows "save current position" and the existing bookmark list together.
    // - A saved bookmark uses the nearby text excerpt as the main display text.
    // - Tapping an existing bookmark jumps there.
    // - Long press edits the optional memo/label.
    // - Delete button removes it.

    void addBookmark() { readerBookmarkActions().addBookmark(); }

    void saveCurrentBookmarkTekStyle(Runnable afterSave) {
        readerBookmarkActions().saveCurrentBookmarkTekStyle(afterSave);
    }

    void jumpToBookmark(Bookmark bookmark) { bookmarkNavigator().jumpToBookmark(bookmark); }

    void showBookmarksForFile() {
        new ReaderBookmarkDialogController(this).showBookmarksForFile();
    }


    // --- Brightness ---


    void applyReaderBrightnessOverride(float brightness) {
        readerActions().applyReaderBrightnessOverride(brightness);
    }

    void clearReaderBrightnessOverride() {
        readerActions().clearReaderBrightnessOverride();
    }

    void showBrightnessDialog() {
        new ReaderAppearanceDialogController(this).showBrightnessDialog();
    }


    // --- Font selection ---

    void showFontDialog() {
        new ReaderFontDialogController(this).showFontDialog();
    }

    // --- Go To / Search ---

    void showGoToDialog() {
        new ReaderToolsDialogController(this).showGoToDialog();
    }

    void showTextSearch() {
        new ReaderToolsDialogController(this).showTextSearch();
    }

    void resetActiveSearchState() {
        readerSearch().resetActiveSearchState();
    }

    int getCachedLargeTextSearchTotal(@NonNull String query) {
        return readerSearch().getCachedLargeTextSearchTotal(query);
    }

    void clearLargeTextSearchTotalCache() {
        readerSearch().clearLargeTextSearchTotalCache();
    }

    void updateLargeTextSearchStatus(@Nullable TextView matchStatus,
                                             int ordinal,
                                             int knownTotal) {
        readerSearch().updateLargeTextSearchStatus(matchStatus, ordinal, knownTotal);
    }

    void performTextSearchMove(String rawQuery, boolean forward, TextView matchStatus) {
        readerSearch().performTextSearchMove(rawQuery, forward, matchStatus);
    }

    void performTextSearchMove(String rawQuery, boolean forward, TextView matchStatus, int targetOccurrence) {
        readerSearch().performTextSearchMove(rawQuery, forward, matchStatus, targetOccurrence);
    }

    void showFileInfoDialog() {
        new ReaderAppearanceDialogController(this).showFileInfoDialog();
    }


    void handleViewerBackPressed() {
        readerShell().handleViewerBackPressed();
    }

    void cancelBackgroundMemoryTrim() { readerMemory().cancelBackgroundMemoryTrim(); }

    void scheduleBackgroundMemoryTrim() { readerMemory().scheduleBackgroundMemoryTrim(); }

    boolean restoreReaderAfterBackgroundMemoryTrimIfNeeded() {
        return readerMemory().restoreReaderAfterBackgroundMemoryTrimIfNeeded();
    }

    void trimReaderMemoryForBackground(boolean force) { readerMemory().trimReaderMemoryForBackground(force); }

    // --- Lifecycle ---

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        readerLifecycle().onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        readerLifecycle().onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        readerLifecycle().onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        readerLifecycle().onTrimMemory(level);
    }

    @Override protected void onDestroy() {
        readerLifecycle().onDestroy();
        super.onDestroy();
    }

    void releaseReaderMemory() { readerMemory().releaseReaderMemory(); }

    void saveReadingState() { readerMemory().saveReadingState(); }

    // --- Menu ---

    @Override public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_reader, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (readerActions().onOptionsItemSelected(item)) return true;
        return super.onOptionsItemSelected(item);
    }

    void changeFontSize(float delta) {
        readerActions().changeFontSize(delta);
    }

    void resetFontSize() {
        readerActions().resetFontSize();
    }

     int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()));
    }

     int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
