package com.readwide.manager;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.LruCache;
import android.util.SparseIntArray;
import android.view.MenuItem;
import android.view.KeyEvent;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.OverScroller;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.adapter.BookmarkFolderAdapter;
import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.model.Theme;
import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.TapZoneMath;
import com.readwide.manager.util.ThemeManager;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

/**
 * Native PDF page viewer.
 *
 * This intentionally renders the original PDF page using Android's PdfRenderer
 * instead of extracting plain text into the text reader. Bookmarks and recent-file
 * state store the current page index in ReaderState.charPosition / Bookmark.charPosition.
 */
public class PdfReaderActivity extends AppCompatActivity {

    private static final long BACKGROUND_MEMORY_TRIM_DELAY_MS = 420_000L;

    public static final String EXTRA_FILE_PATH = ReaderActivity.EXTRA_FILE_PATH;
    public static final String EXTRA_FILE_URI = ReaderActivity.EXTRA_FILE_URI;
    public static final String EXTRA_JUMP_TO_PAGE = ReaderActivity.EXTRA_JUMP_TO_POSITION;
    public static final String EXTRA_CONTENT_ANCHOR_JSON = "pdf_content_anchor_json";

    // Match toolbar-triggered PDF popups to the Go to Page bottom offset.
    static final int PDF_TOOLBAR_POPUP_Y_DP = 74;
    // Render PDF pages above 1:1 screen density and let the ImageView downscale,
    // which noticeably sharpens text. Bounded by the per-page max-pixel cap.
    static final float PDF_SUPERSAMPLE = 1.4f;

    // In horizontal PDF swipe mode, a zoomed page needs to pan around the enlarged
    // bitmap before the next edge-start swipe can turn the page. Accelerate only
    // zoomed in-page panning so original-size movement and page-turn thresholds
    // stay unchanged.
    private static final float PDF_ZOOMED_HORIZONTAL_PAN_ACCELERATION = 1.62f;
    private static final float PDF_ZOOMED_VERTICAL_PAN_ACCELERATION = 1.45f;
    private static final float PDF_ZOOMED_FLING_VELOCITY_SCALE = 0.92f;
    private static final float PDF_CONTINUOUS_HORIZONTAL_FLING_VELOCITY_SCALE = 0.82f;


    View root;
    View pdfAppBar;
    View pdfToolbar;
    TextView pdfTopPageStatus;
    View pdfBottomBar;
    private int lastPdfBottomBarHeight = 0;
    View pdfNavBarSpacer;
    boolean pdfChromeVisible = true;
    ImageView pageImage;
    RecyclerView pdfContinuousList;
    PdfContinuousPageAdapter pdfContinuousAdapter;
    private RecyclerView.OnScrollListener continuousScrollListener;
    private boolean suppressContinuousScrollSync = false;
    private Runnable pendingContinuousScrollRunnable = null;
    private Runnable pendingContinuousSettleRunnable = null;
    ProgressBar progressBar;
    TextView pageStatus;
    SeekBar pdfPageSeekBar;
    boolean pdfPageSeekBarUserTracking = false;
    TextView prevButton;
    TextView nextButton;
    TextView slideModeButton;
    TextView pageButton;
    TextView bookmarkButton;
    TextView zoomMoreButton;
    View pdfViewport;
    HorizontalScrollView pdfHScroll;
    ScrollView pdfVScroll;
    PdfPageView pdfPageMatrixView;
    private volatile int lastRenderedPageWidthPts = 1;
    private volatile int lastRenderedPageHeightPts = 1;
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private OverScroller pdfFlingScroller;
    private final Runnable pdfFlingRunnable = this::continuePdfViewportFling;
    private int lastPdfFlingX = 0;
    private int lastPdfFlingY = 0;
    private boolean pdfContinuousHorizontalFling = false;
    private float touchStartX;
    private float touchStartY;
    private boolean pinchZoomChanged = false;
    private float gestureStartRawX;
    private float gestureStartRawY;
    private boolean gestureStartedInViewport = false;
    private boolean gestureSawMultiTouch = false;
    private boolean viewportPanConsumed = false;
    private float lastPanRawX;
    private float lastPanRawY;
    int touchSlop;
    private boolean gestureStartedWithHorizontalScrollable = false;
    private boolean gestureStartedWithVerticalScrollable = false;
    private boolean gestureStartedAtLeftEdge = true;
    private boolean gestureStartedAtRightEdge = true;
    private boolean gestureStartedAtTopEdge = true;
    private boolean gestureStartedAtBottomEdge = true;
    boolean verticalPageSlideMode = false;
    private boolean pdfTapPagingSequence = false;

    private boolean pendingZoomFocus = false;
    private float pendingZoomFocusXRatio = 0.5f;
    private float pendingZoomFocusYRatio = 0.5f;
    private float pendingZoomViewportX = 0.5f;
    private float pendingZoomViewportY = 0.5f;
    private boolean deferZoomBitmapReveal = false;
    private int pendingZoomRevealWidth = -1;
    private float activePinchFocusRawX = -1f;
    private float activePinchFocusRawY = -1f;

    int readerBg = Color.rgb(18, 18, 18);
    int readerFg = Color.rgb(232, 234, 237);
    int readerToolbarBg = Color.rgb(18, 18, 18);
    int readerSub = Color.rgb(176, 176, 176);
    int readerPanel = Color.rgb(32, 33, 36);
    int readerLine = Color.rgb(84, 86, 90);

    PrefsManager prefs;
    BookmarkManager bookmarkManager;
    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Separate single-thread executor for neighbor prefetch renders. Kept apart
    // from `executor` so a queued prefetch never delays the on-demand render of
    // the page the user just turned to (which caused rapid taps to feel slow).
    // PDF page access is still serialized by rendererLock, so the visible render
    // takes the lock first and prefetch waits behind it rather than the reverse.
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();
    volatile boolean activityDestroyed = false;
    com.readwide.manager.controller.ReaderToolbarController pdfToolbarController;
    final Object rendererLock = new Object();
    // A second, independent PdfRenderer over the same file, used only by the
    // prefetch thread. PdfRenderer can't render two pages concurrently on one
    // instance, so a separate instance + lock lets neighbor prefetch render in
    // true parallel with the on-demand render instead of queuing behind it.
    final Object prefetchRendererLock = new Object();

    private ParcelFileDescriptor parcelFileDescriptor;
    PdfRenderer pdfRenderer;
    private ParcelFileDescriptor prefetchParcelFileDescriptor;
    private PdfRenderer prefetchRenderer;
    private Bitmap currentBitmap;
    // Single-page (horizontal) mode prerender cache: neighbor pages rendered
    // ahead at the current fit width so a page turn shows instantly instead of
    // rendering on demand (which made horizontal turns slower than vertical,
    // where the continuous adapter already prefetches). Keyed by page index.
    // The cache owns its bitmaps; they're recycled only on eviction/clear, never
    // by the display swap. Invalidated when zoom or viewport width changes.
    private final android.util.LruCache<Integer, Bitmap> singlePageCache =
            new android.util.LruCache<Integer, Bitmap>((int) Math.max(
                    24L * 1024L * 1024L,
                    Math.min(96L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 6L))) {
                @Override protected int sizeOf(Integer key, Bitmap value) {
                    return value == null || value.isRecycled() ? 0 : value.getByteCount();
                }
                @Override protected void entryRemoved(boolean evicted, Integer key,
                                                      Bitmap oldValue, Bitmap newValue) {
                    if (oldValue != null && oldValue != newValue
                            && oldValue != currentBitmap && !oldValue.isRecycled()) {
                        oldValue.recycle();
                    }
                }
            };
    private float singlePageCacheZoom = 1.0f;
    private int singlePageCacheWidth = -1;
    private int singlePageCacheHeight = -1;
    private Runnable pendingSinglePagePrefetch = null;
    private final java.util.Set<Integer> singlePagePrefetchInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private File localFile;
    String filePath;
    String fileName;
    int pageCount = 0;
    String pendingPdfContentAnchorJson = "";
    int currentPage = 0;
    private float zoom = 1.0f;
    private float renderedZoom = 1.0f;
    private int pendingPageSlideDirection = 0;
    private int renderGeneration = 0;
    private boolean backgroundPdfBitmapsReleased = false;
    private final Runnable backgroundPdfMemoryTrimRunnable = () -> trimPdfBitmapsForBackground(false);
    private PdfReaderStartupController startupController;
    private PdfPageTurnController pageTurnController;

    private PdfReaderStartupController startup() {
        if (startupController == null) {
            startupController = new PdfReaderStartupController(this);
        }
        return startupController;
    }

    private PdfPageTurnController pageTurns() {
        if (pageTurnController == null) {
            pageTurnController = new PdfPageTurnController(this);
        }
        return pageTurnController;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        prefs.applyLanguage(prefs.getLanguageMode());
        super.onCreate(savedInstanceState);
        startup().onCreateAfterSuper(savedInstanceState);
    }


    void setupContinuousPdfList() {
        if (pdfContinuousList == null) return;

        pdfContinuousAdapter = new PdfContinuousPageAdapter(this);
        LinearLayoutManager lm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        pdfContinuousList.setLayoutManager(lm);
        pdfContinuousList.setAdapter(pdfContinuousAdapter);
        pdfContinuousList.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        pdfContinuousList.setBackgroundColor(readerBg);
        continuousScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                syncCurrentPageFromContinuousList(false);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    syncCurrentPageFromContinuousList(true);
                }
            }
        };
        pdfContinuousList.addOnScrollListener(continuousScrollListener);
    }

    private void syncCurrentPageFromContinuousList(boolean force) {
        if (suppressContinuousScrollSync || !verticalPageSlideMode || pageCount <= 0 || pdfContinuousList == null) {
            return;
        }

        RecyclerView.LayoutManager manager = pdfContinuousList.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return;

        LinearLayoutManager lm = (LinearLayoutManager) manager;
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;

        int viewportCenter = pdfContinuousList.getHeight() / 2;
        int bestPage = first;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            View child = lm.findViewByPosition(i);
            if (child == null) continue;
            int childCenter = (child.getTop() + child.getBottom()) / 2;
            int distance = Math.abs(childCenter - viewportCenter);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPage = i;
            }
        }

        int nextPage = clampPage(bestPage);
        if (force || nextPage != currentPage) {
            currentPage = nextPage;
            saveReadingState();
            updatePageStatus();
        }
    }

    private void applyPdfDisplayMode() {
        boolean continuous = verticalPageSlideMode;
        pendingPageSlideDirection = 0;
        stopPdfViewportFling();
        resetViewportGesture();

        if (pdfContinuousList != null) {
            pdfContinuousList.stopScroll();
            pdfContinuousList.setVisibility(continuous ? View.VISIBLE : View.GONE);
        }
        // Stage-1 Matrix zoom: single-page mode uses the Matrix view, not the legacy
        // scroll/image stack. Keep the scroll stack hidden so it can't cover the
        // Matrix view; show the Matrix view for single-page, hide it for continuous.
        boolean useMatrix = pdfPageMatrixView != null;
        if (pdfHScroll != null) {
            pdfHScroll.setVisibility((continuous || useMatrix) ? View.GONE : View.VISIBLE);
        }
        if (pdfPageMatrixView != null) {
            pdfPageMatrixView.setVisibility(continuous ? View.GONE : View.VISIBLE);
        }

        if (continuous) {
            // Drop the Matrix view's references before recycling single-page
            // bitmaps, so it can't draw a freed bitmap during the transition.
            if (pdfPageMatrixView != null) pdfPageMatrixView.detachBitmaps();
            releaseSinglePageBitmap();
            renderContinuousPages();
        } else {
            // Single-page mode: the Matrix view owns zoom via its own transform, so
            // the page must be rendered at fit. Drop any zoom carried over from
            // continuous mode (otherwise the fit render would be scaled up to that
            // zoom and allocate an oversized bitmap, crashing the viewer).
            zoom = 1.0f;
            renderedZoom = 1.0f;
            if (pdfPageMatrixView != null) pdfPageMatrixView.detachBitmaps();
            singlePageCache.evictAll();
            singlePageCacheZoom = 1.0f;
            singlePageCacheWidth = -1;
            singlePageCacheHeight = -1;
            resetContinuousPageViews(true);
            renderCurrentPage(currentBitmap == null);
        }
    }

    private void resetContinuousPageViews(boolean keepAdapterAttached) {
        if (pdfContinuousList != null) {
            pdfContinuousList.stopScroll();
            pdfContinuousList.setAdapter(null);
        }
        if (pdfContinuousAdapter != null) {
            pdfContinuousAdapter.clearBitmaps();
        }
        if (keepAdapterAttached && !activityDestroyed && pdfContinuousList != null && pdfContinuousAdapter != null) {
            pdfContinuousList.setAdapter(pdfContinuousAdapter);
        }
    }

    private void renderContinuousPages() {
        if (!ensureContinuousPagesConfigured()) return;
        progressBar.setVisibility(View.GONE);
        updatePageStatus();
        if (pendingPdfContentAnchorJson != null && !pendingPdfContentAnchorJson.trim().isEmpty()) {
            restorePendingPdfContentAnchorIfNeeded();
        } else {
            scrollContinuousListToCurrentPage(false);
        }
        prefetchContinuousPagesAround(currentPage);
    }

    private boolean ensureContinuousPagesConfigured() {
        if (pdfContinuousList == null || pdfContinuousAdapter == null
                || pdfRenderer == null || pageCount <= 0 || pdfViewport == null) {
            return false;
        }

        if (pdfContinuousList.getAdapter() != pdfContinuousAdapter) {
            pdfContinuousList.setAdapter(pdfContinuousAdapter);
        }

        int viewportWidth = pdfViewport.getWidth()
                - pdfViewport.getPaddingLeft() - pdfViewport.getPaddingRight();
        if (viewportWidth <= 0) {
            pdfViewport.post(this::renderContinuousPages);
            return false;
        }

        pdfContinuousAdapter.configure(pageCount, viewportWidth, zoom);
        return true;
    }

    private void prefetchContinuousPagesAround(int pageIndex) {
        if (pdfContinuousAdapter == null || !verticalPageSlideMode || pageCount <= 0) return;
        int center = clampPage(pageIndex);
        for (int page = Math.max(0, center - 1); page <= Math.min(pageCount - 1, center + 1); page++) {
            pdfContinuousAdapter.prefetchPage(page);
        }
    }

    private void scrollContinuousListToCurrentPage(boolean smooth) {
        if (pdfContinuousList == null || pageCount <= 0) return;
        final int target = clampPage(currentPage);
        suppressContinuousScrollSync = true;
        pdfContinuousList.stopScroll();

        // Cancel any still-pending scroll/settle work from a previous (rapid) tap
        // so only the latest target is honored. Without this, stacked timers fire
        // out of order and re-assert stale pages, making the counter bounce around
        // the target before settling.
        if (pendingContinuousScrollRunnable != null) {
            pdfContinuousList.removeCallbacks(pendingContinuousScrollRunnable);
        }
        if (pendingContinuousSettleRunnable != null) {
            pdfContinuousList.removeCallbacks(pendingContinuousSettleRunnable);
        }

        pendingContinuousScrollRunnable = () -> {
            pendingContinuousScrollRunnable = null;
            if (activityDestroyed || pdfContinuousList == null) return;

            pdfContinuousList.stopScroll();
            RecyclerView.LayoutManager manager = pdfContinuousList.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                if (smooth) {
                    pdfContinuousList.smoothScrollToPosition(target);
                } else {
                    ((LinearLayoutManager) manager).scrollToPositionWithOffset(target, 0);
                }
            } else {
                pdfContinuousList.scrollToPosition(target);
            }

            pendingContinuousSettleRunnable = () -> {
                pendingContinuousSettleRunnable = null;
                if (activityDestroyed || pdfContinuousList == null) return;
                // Re-assert the target as the current page AFTER the scroll has
                // settled. scrollToPositionWithOffset lays out over several frames
                // and can emit late onScrolled callbacks; without re-asserting,
                // one of those can overwrite currentPage with an intermediate page.
                // Pinning currentPage = target keeps the displayed number correct.
                suppressContinuousScrollSync = false;
                currentPage = target;
                updatePageStatus();
            };
            pdfContinuousList.postDelayed(pendingContinuousSettleRunnable, smooth ? 360L : 220L);
        };
        pdfContinuousList.post(pendingContinuousScrollRunnable);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Stage-1 Matrix zoom: in single-page mode the Matrix view owns all page
        // gestures (zoom/pan/double-tap/tap). Let touches reach it directly instead
        // of the legacy scroll-based handler. Touches on visible chrome bars still
        // go through the legacy path so toolbar/seekbar controls keep working.
        if (pdfPageMatrixView != null && pdfPageMatrixView.getVisibility() == View.VISIBLE
                && !verticalPageSlideMode) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                boolean onChrome = pdfChromeVisible
                        && (isEventInsideView(pdfBottomBar, event) || isEventInsideView(pdfAppBar, event));
                matrixGestureOnChrome = onChrome;
            }
            if (!matrixGestureOnChrome) {
                return super.dispatchTouchEvent(event);
            }
        }
        if (handlePdfViewportGesture(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    private boolean matrixGestureOnChrome = false;

    // True while the current touch sequence started on a visible chrome bar
    // (page seekbar / toolbar). Such sequences must be left entirely to those
    // controls so a seekbar drag isn't also interpreted as a page swipe/pan.
    private boolean gestureStartedOnChrome = false;

    private boolean handlePdfViewportGesture(@NonNull MotionEvent event) {
        if (pdfViewport == null || pageCount <= 0) return false;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            gestureStartedOnChrome = pdfChromeVisible
                    && (isEventInsideView(pdfBottomBar, event) || isEventInsideView(pdfAppBar, event));
        }
        // Don't run any viewport gesture logic for a sequence that began on the
        // chrome; let the seekbar/toolbar handle their own touches.
        if (gestureStartedOnChrome) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gestureStartedOnChrome = false;
            }
            return false;
        }

        boolean insideViewport = isEventInsideView(pdfViewport, event);
        return verticalPageSlideMode
                ? handleContinuousPdfViewportGesture(event, insideViewport)
                : handleSinglePagePdfViewportGesture(event, insideViewport);
    }

    private boolean handleContinuousPdfViewportGesture(@NonNull MotionEvent event, boolean insideViewport) {
        // Continuous vertical PDF mode is still vertically scrolled by the
        // RecyclerView. When pages are zoomed wider than the viewport, horizontal
        // drags are intercepted here so the user can pan across the enlarged
        // page instead of being stuck at the centered crop.
        if (insideViewport && scaleGestureDetector != null) {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1 || scaleGestureDetector.isInProgress()) {
                return true;
            }
        }
        if (handleFastPdfTapPaging(event, insideViewport)) {
            resetViewportGesture();
            return true;
        }
        if (!pdfTapPagingSequence && handlePdfTapGesture(event, insideViewport)) {
            resetViewportGesture();
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginViewportGesture(event, insideViewport, false);
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (gestureStartedInViewport) gestureSawMultiTouch = true;
                return false;

            case MotionEvent.ACTION_MOVE:
                return handleContinuousPdfHorizontalPan(event);

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean consumed = viewportPanConsumed;
                resetViewportGesture();
                return consumed;

            default:
                return false;
        }
    }

    private boolean handleContinuousPdfHorizontalPan(@NonNull MotionEvent event) {
        if (isViewportGestureBlockedForPan()) return false;
        if (pdfContinuousAdapter == null || !pdfContinuousAdapter.canPanVisiblePageHorizontally()) return false;

        float rawX = event.getRawX();
        float rawY = event.getRawY();
        float stepDx = rawX - lastPanRawX;
        float totalDx = rawX - gestureStartRawX;
        float totalDy = rawY - gestureStartRawY;
        boolean horizontalPanGesture = viewportPanConsumed
                || (Math.abs(totalDx) > touchSlop && Math.abs(totalDx) > Math.abs(totalDy) * 1.12f);
        if (!horizontalPanGesture) return false;

        boolean moved = pdfContinuousAdapter.panVisiblePageHorizontally(-stepDx * 1.65f);
        viewportPanConsumed = true;
        lastPanRawX = rawX;
        lastPanRawY = rawY;
        return moved || Math.abs(stepDx) > 0.5f;
    }

    private boolean handleSinglePagePdfViewportGesture(@NonNull MotionEvent event, boolean insideViewport) {
        if (handleFastPdfTapPaging(event, insideViewport)) {
            resetViewportGesture();
            return true;
        }
        if (!pdfTapPagingSequence && handlePdfTapGesture(event, insideViewport)) {
            resetViewportGesture();
            return true;
        }
        if (insideViewport && scaleGestureDetector != null) {
            scaleGestureDetector.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginViewportGesture(event, insideViewport, true);
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (gestureStartedInViewport) gestureSawMultiTouch = true;
                return false;

            case MotionEvent.ACTION_MOVE:
                return handleSinglePagePdfPan(event);

            case MotionEvent.ACTION_UP:
                return handleSinglePagePdfSwipeRelease(event);

            case MotionEvent.ACTION_CANCEL:
                resetViewportGesture();
                return false;

            default:
                return false;
        }
    }

    private void beginViewportGesture(@NonNull MotionEvent event, boolean insideViewport, boolean trackScrollableEdges) {
        stopPdfViewportFling();
        gestureStartRawX = event.getRawX();
        gestureStartRawY = event.getRawY();
        lastPanRawX = gestureStartRawX;
        lastPanRawY = gestureStartRawY;
        viewportPanConsumed = false;
        gestureStartedInViewport = insideViewport;
        gestureSawMultiTouch = event.getPointerCount() > 1;
        if (!trackScrollableEdges) return;

        gestureStartedWithHorizontalScrollable = insideViewport && isPdfHorizontallyScrollable();
        gestureStartedWithVerticalScrollable = insideViewport && isPdfVerticallyScrollable();
        gestureStartedAtLeftEdge = !gestureStartedWithHorizontalScrollable || isPdfAtLeftEdge(dpToPx(3));
        gestureStartedAtRightEdge = !gestureStartedWithHorizontalScrollable || isPdfAtRightEdge(dpToPx(3));
        gestureStartedAtTopEdge = !gestureStartedWithVerticalScrollable || isPdfAtTopEdge(dpToPx(3));
        gestureStartedAtBottomEdge = !gestureStartedWithVerticalScrollable || isPdfAtBottomEdge(dpToPx(3));
    }

    private boolean isViewportGestureBlockedForPan() {
        return !gestureStartedInViewport || gestureSawMultiTouch
                || (scaleGestureDetector != null && scaleGestureDetector.isInProgress());
    }

    private boolean handleSinglePagePdfPan(@NonNull MotionEvent event) {
        if (isViewportGestureBlockedForPan() || !isPdfContentScrollable()) return false;

        float rawX = event.getRawX();
        float rawY = event.getRawY();
        float stepDx = rawX - lastPanRawX;
        float stepDy = rawY - lastPanRawY;
        float totalDx = rawX - gestureStartRawX;
        float totalDy = rawY - gestureStartRawY;
        if (!viewportPanConsumed && Math.hypot(totalDx, totalDy) <= touchSlop) return false;

        float horizontalPan = -stepDx;
        float verticalPan = -stepDy;
        if (!isPdfAtOriginalZoom()) {
            if (isPdfHorizontallyScrollable()) {
                horizontalPan *= PDF_ZOOMED_HORIZONTAL_PAN_ACCELERATION;
            }
            if (isPdfVerticallyScrollable()) {
                verticalPan *= PDF_ZOOMED_VERTICAL_PAN_ACCELERATION;
            }
        }
        panPdfContent(horizontalPan, verticalPan);
        viewportPanConsumed = true;
        lastPanRawX = rawX;
        lastPanRawY = rawY;
        return true;
    }

    private boolean handleSinglePagePdfSwipeRelease(@NonNull MotionEvent event) {
        if (isViewportGestureBlockedForPan()) {
            resetViewportGesture();
            return false;
        }
        float dx = event.getRawX() - gestureStartRawX;
        float dy = event.getRawY() - gestureStartRawY;
        boolean strongPageSwipe = Math.abs(dx) >= getPdfHorizontalPageSwipeThresholdPx()
                && Math.abs(dx) > Math.abs(dy) * getPdfHorizontalPageSwipeDominanceRatio();
        if (strongPageSwipe && canTurnPdfPageFromSwipe(dx)) {
            if (dx < 0) goToPage(currentPage + 1, 1);
            else goToPage(currentPage - 1, -1);
            resetViewportGesture();
            return true;
        }
        boolean consumed = viewportPanConsumed;
        resetViewportGesture();
        return consumed;
    }

    private void resetViewportGesture() {
        gestureStartedInViewport = false;
        gestureSawMultiTouch = false;
        viewportPanConsumed = false;
        pdfTapPagingSequence = false;
        gestureStartedWithHorizontalScrollable = false;
        gestureStartedWithVerticalScrollable = false;
        gestureStartedAtLeftEdge = true;
        gestureStartedAtRightEdge = true;
        gestureStartedAtTopEdge = true;
        gestureStartedAtBottomEdge = true;
    }

    private boolean startPdfViewportFling(float velocityX, float velocityY) {
        if (activityDestroyed || pdfFlingScroller == null || isPdfAtOriginalZoom()) return false;
        if (verticalPageSlideMode) {
            return startContinuousPdfHorizontalFling(velocityX, velocityY);
        }
        return startSinglePagePdfFling(velocityX, velocityY);
    }

    private boolean startSinglePagePdfFling(float velocityX, float velocityY) {
        if (!isPdfContentScrollable()) return false;
        int startX = pdfHScroll != null ? pdfHScroll.getScrollX() : 0;
        int startY = pdfVScroll != null ? pdfVScroll.getScrollY() : 0;
        int maxX = getPdfHorizontalScrollRange();
        int maxY = getPdfVerticalScrollRange();
        if (maxX <= 0 && maxY <= 0) return false;

        int flingVelocityX = maxX > 0
                ? Math.round(-velocityX * PDF_ZOOMED_FLING_VELOCITY_SCALE)
                : 0;
        int flingVelocityY = maxY > 0
                ? Math.round(-velocityY * PDF_ZOOMED_FLING_VELOCITY_SCALE)
                : 0;
        if (Math.abs(flingVelocityX) < ViewConfiguration.get(this).getScaledMinimumFlingVelocity()
                && Math.abs(flingVelocityY) < ViewConfiguration.get(this).getScaledMinimumFlingVelocity()) {
            return false;
        }

        pdfContinuousHorizontalFling = false;
        lastPdfFlingX = startX;
        lastPdfFlingY = startY;
        pdfFlingScroller.fling(startX, startY, flingVelocityX, flingVelocityY, 0, maxX, 0, maxY);
        postPdfFlingStep();
        return true;
    }

    private boolean startContinuousPdfHorizontalFling(float velocityX, float velocityY) {
        if (pdfContinuousAdapter == null || !pdfContinuousAdapter.canPanVisiblePageHorizontally()) return false;
        if (Math.abs(velocityX) < Math.abs(velocityY) * 1.15f) return false;

        int current = pdfContinuousAdapter.getVisiblePageHorizontalPanOffset();
        int max = pdfContinuousAdapter.getVisiblePageHorizontalPanRange();
        if (max <= 0) return false;
        int flingVelocityX = Math.round(-velocityX * PDF_CONTINUOUS_HORIZONTAL_FLING_VELOCITY_SCALE);
        if (Math.abs(flingVelocityX) < ViewConfiguration.get(this).getScaledMinimumFlingVelocity()) return false;

        pdfContinuousHorizontalFling = true;
        lastPdfFlingX = current;
        lastPdfFlingY = 0;
        pdfFlingScroller.fling(current, 0, flingVelocityX, 0, 0, max, 0, 0);
        postPdfFlingStep();
        return true;
    }

    private void continuePdfViewportFling() {
        if (activityDestroyed || pdfFlingScroller == null) return;
        if (!pdfFlingScroller.computeScrollOffset()) return;

        int currentX = pdfFlingScroller.getCurrX();
        int currentY = pdfFlingScroller.getCurrY();
        if (pdfContinuousHorizontalFling) {
            if (pdfContinuousAdapter != null) {
                pdfContinuousAdapter.setVisiblePageHorizontalPanOffset(currentX);
            }
        } else {
            if (pdfHScroll != null) {
                pdfHScroll.scrollTo(currentX, pdfHScroll.getScrollY());
            }
            if (pdfVScroll != null) {
                pdfVScroll.scrollTo(pdfVScroll.getScrollX(), currentY);
            }
        }
        lastPdfFlingX = currentX;
        lastPdfFlingY = currentY;

        if (!pdfFlingScroller.isFinished()) {
            postPdfFlingStep();
        }
    }

    private void postPdfFlingStep() {
        View target = pdfViewport != null ? pdfViewport : root;
        if (target != null) {
            target.removeCallbacks(pdfFlingRunnable);
            target.postOnAnimation(pdfFlingRunnable);
        } else {
            handler.removeCallbacks(pdfFlingRunnable);
            handler.post(pdfFlingRunnable);
        }
    }

    private void stopPdfViewportFling() {
        if (pdfFlingScroller != null && !pdfFlingScroller.isFinished()) {
            pdfFlingScroller.forceFinished(true);
        }
        if (pdfViewport != null) pdfViewport.removeCallbacks(pdfFlingRunnable);
        if (root != null) root.removeCallbacks(pdfFlingRunnable);
        handler.removeCallbacks(pdfFlingRunnable);
    }

    private int getPdfHorizontalScrollRange() {
        if (pdfHScroll == null || pdfHScroll.getChildCount() == 0) return 0;
        return Math.max(0, pdfHScroll.getChildAt(0).getWidth() - pdfHScroll.getWidth());
    }

    private int getPdfVerticalScrollRange() {
        if (pdfVScroll == null || pdfVScroll.getChildCount() == 0) return 0;
        return Math.max(0, pdfVScroll.getChildAt(0).getHeight() - pdfVScroll.getHeight());
    }

    private boolean handlePdfTapGesture(@NonNull MotionEvent event, boolean insideViewport) {
        if (!insideViewport || gestureDetector == null) return false;
        boolean handled = gestureDetector.onTouchEvent(event);
        // Keep ACTION_DOWN available for the pan/swipe tracker. GestureDetector may
        // return true on down once onDown() is enabled for reliable single-tap
        // confirmation, but consuming down here breaks page swipes and zoom panning.
        return handled && event.getActionMasked() != MotionEvent.ACTION_DOWN;
    }

    private boolean handleFastPdfTapPaging(@NonNull MotionEvent event, boolean insideViewport) {
        int eventAction = event.getActionMasked();
        if (eventAction == MotionEvent.ACTION_CANCEL) {
            pdfTapPagingSequence = false;
            return false;
        }
        if (!insideViewport || prefs == null || !prefs.getPdfTapPagingEnabled()
                || pdfViewport == null || pageCount <= 1) {
            pdfTapPagingSequence = false;
            return false;
        }

        if (eventAction == MotionEvent.ACTION_DOWN) {
            pdfTapPagingSequence = getPdfTapPagingAction(event) != TapZoneMath.ACTION_MENU;
            return false;
        }

        if (!pdfTapPagingSequence) return false;

        if (eventAction == MotionEvent.ACTION_MOVE) {
            if (Math.abs(event.getRawX() - gestureStartRawX) > touchSlop
                    || Math.abs(event.getRawY() - gestureStartRawY) > touchSlop) {
                pdfTapPagingSequence = false;
            }
            return false;
        }

        if (eventAction != MotionEvent.ACTION_UP) return false;
        boolean stillTap = !gestureSawMultiTouch && !viewportPanConsumed
                && Math.abs(event.getRawX() - gestureStartRawX) <= touchSlop
                && Math.abs(event.getRawY() - gestureStartRawY) <= touchSlop;
        int action = stillTap ? getPdfTapPagingAction(event) : TapZoneMath.ACTION_MENU;
        pdfTapPagingSequence = false;
        if (action == TapZoneMath.ACTION_PREVIOUS) {
            goToPage(currentPage - 1, -1);
            return true;
        }
        if (action == TapZoneMath.ACTION_NEXT) {
            goToPage(currentPage + 1, 1);
            return true;
        }
        return false;
    }

    private int getPdfTapPagingAction(@NonNull MotionEvent event) {
        if (prefs == null || !prefs.getPdfTapPagingEnabled()) return TapZoneMath.ACTION_MENU;
        if (pdfViewport == null || pageCount <= 1) return TapZoneMath.ACTION_MENU;
        int[] loc = new int[2];
        pdfViewport.getLocationOnScreen(loc);
        float x = event.getRawX() - loc[0];
        float y = event.getRawY() - loc[1];
        // When the toolbar is visible the viewport reserves bottom padding for it.
        // Exclude that reserved strip (and the top padding) from the tap zones so
        // a tap on the floating toolbar does not page the document underneath.
        int usableWidth = pdfViewport.getWidth() - pdfViewport.getPaddingLeft() - pdfViewport.getPaddingRight();
        int usableHeight = pdfViewport.getHeight() - pdfViewport.getPaddingTop() - pdfViewport.getPaddingBottom();
        float zoneX = x - pdfViewport.getPaddingLeft();
        float zoneY = y - pdfViewport.getPaddingTop();
        if (zoneX < 0 || zoneY < 0 || zoneX > usableWidth || zoneY > usableHeight) {
            return TapZoneMath.ACTION_MENU;
        }
        return TapZoneMath.actionForTap(
                zoneX,
                zoneY,
                usableWidth,
                usableHeight,
                true,
                true,
                prefs.getTapZoneMode(),
                prefs.getTapLeadingZonePercent(),
                prefs.getTapTrailingZonePercent());
    }

    private boolean handlePdfTapPaging(@NonNull MotionEvent event) {
        int action = getPdfTapPagingAction(event);
        if (action == TapZoneMath.ACTION_PREVIOUS) {
            goToPage(currentPage - 1, -1);
            return true;
        }
        if (action == TapZoneMath.ACTION_NEXT) {
            goToPage(currentPage + 1, 1);
            return true;
        }
        return false;
    }

    void togglePdfChrome() {
        setPdfChromeVisible(!pdfChromeVisible);
    }

    /** True if the tap lies inside a currently-visible PDF chrome bar. */
    private boolean tapIntersectsVisiblePdfChrome(@NonNull MotionEvent e) {
        if (!pdfChromeVisible) return false;
        float x = e.getRawX();
        float y = e.getRawY();
        return pdfViewContainsRawPoint(pdfAppBar, x, y)
                || pdfViewContainsRawPoint(pdfBottomBar, x, y);
    }

    private boolean pdfViewContainsRawPoint(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + view.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + view.getHeight();
    }

    private void setPdfChromeVisible(boolean visible) {
        pdfChromeVisible = visible;
        applyPdfChromeFillColors();
        int toolbarVisibility = visible ? View.VISIBLE : View.GONE;
        int bottomVisibility = visible ? View.VISIBLE : View.GONE;
        int topStatusVisibility = visible ? View.GONE : View.VISIBLE;

        if (pdfAppBar != null && pdfAppBar.getVisibility() != View.VISIBLE) {
            pdfAppBar.setVisibility(View.VISIBLE);
        }
        if (pdfToolbar == null) pdfToolbar = findViewById(R.id.toolbar);
        if (pdfToolbar != null && pdfToolbar.getVisibility() != toolbarVisibility) {
            pdfToolbar.setVisibility(toolbarVisibility);
        }
        if (pdfTopPageStatus == null) pdfTopPageStatus = findViewById(R.id.pdf_top_page_status);
        if (pdfTopPageStatus != null && pdfTopPageStatus.getVisibility() != topStatusVisibility) {
            pdfTopPageStatus.setVisibility(topStatusVisibility);
        }
        if (pdfBottomBar != null && pdfBottomBar.getVisibility() != bottomVisibility) {
            pdfBottomBar.setVisibility(bottomVisibility);
        }
        applyDocumentSystemBarColors();
        updatePageStatus();
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        applyPdfViewportBottomInset();
        if (pdfViewport != null) pdfViewport.requestLayout();
        if (!verticalPageSlideMode && pdfViewport != null && currentBitmap != null) {
            fitCurrentSinglePageBitmapToViewport();
            pdfViewport.post(() -> {
                fitCurrentSinglePageBitmapToViewport();
                renderCurrentPage(false);
            });
        }
    }

    /**
     * Reserve the visible bottom toolbar at fit zoom so a single PDF page never
     * sits under it. When chrome is hidden, release the reserve and fit into the
     * larger viewport.
     */
    void applyPdfViewportBottomInset() {
        if (pdfViewport == null) return;
        // When zoomed in, the page is meant to be panned/scrolled over the whole
        // viewport — including the area the toolbar would reserve. Drop the reserve
        // so the bottom of an enlarged page is reachable instead of being cut off
        // by dead padding. The reserve only matters at fit zoom (to keep the whole
        // page above the toolbar).
        if (zoom > 1.05f || !pdfChromeVisible) {
            setPdfViewportBottomPadding(0);
            return;
        }
        // Remember the actual overlay area covered by the bottom bar. Computing
        // it from screen coordinates avoids guessing which parts of the bar's
        // inset padding are visually covering the PDF.
        if (pdfBottomBar != null && pdfBottomBar.getVisibility() == View.VISIBLE
                && pdfBottomBar.getHeight() > 0) {
            lastPdfBottomBarHeight = calculateVisiblePdfBottomBarReserve();
            setPdfViewportBottomPadding(lastPdfBottomBarHeight);
            return;
        }
        if (lastPdfBottomBarHeight > 0) {
            setPdfViewportBottomPadding(lastPdfBottomBarHeight);
            return;
        }
        // First time, before the bar has ever been measured: measure then apply.
        if (pdfBottomBar != null) {
            pdfBottomBar.post(() -> {
                if (pdfBottomBar != null && pdfBottomBar.getHeight() > 0) {
                    lastPdfBottomBarHeight = calculateVisiblePdfBottomBarReserve();
                    setPdfViewportBottomPadding(lastPdfBottomBarHeight);
                }
            });
        }
    }

    private int calculateVisiblePdfBottomBarReserve() {
        if (pdfViewport == null || pdfBottomBar == null || pdfBottomBar.getHeight() <= 0) return 0;
        return pdfBottomBar.getHeight();
    }

    private void setPdfViewportBottomPadding(int bottomPx) {
        if (pdfViewport == null) return;
        if (pdfViewport.getPaddingBottom() == bottomPx) return;
        if (!verticalPageSlideMode) {
            ++renderGeneration;
            singlePageCache.evictAll();
            singlePagePrefetchInFlight.clear();
            singlePageCacheWidth = -1;
            singlePageCacheHeight = -1;
        }
        pdfViewport.setPadding(
                pdfViewport.getPaddingLeft(),
                pdfViewport.getPaddingTop(),
                pdfViewport.getPaddingRight(),
                bottomPx);
        if (pdfViewport != null) pdfViewport.requestLayout();
        if (pageImage != null) pageImage.post(this::applySinglePageVerticalOffset);
        if (!verticalPageSlideMode && pdfViewport != null) {
            pdfViewport.post(() -> renderCurrentPage(false));
        }
    }

    private void fitCurrentSinglePageBitmapToViewport() {
        if (verticalPageSlideMode || pageImage == null || pdfViewport == null
                || currentBitmap == null || currentBitmap.isRecycled()) {
            return;
        }
        if (zoom > 1.05f) return;

        int viewportWidth = pdfViewport.getWidth()
                - pdfViewport.getPaddingLeft() - pdfViewport.getPaddingRight();
        int viewportHeight = pdfViewport.getHeight()
                - pdfViewport.getPaddingTop() - pdfViewport.getPaddingBottom();
        if (viewportWidth <= 0 || currentBitmap.getWidth() <= 0 || currentBitmap.getHeight() <= 0) {
            return;
        }

        int baseWidth = Math.max(1, viewportWidth - dpToPx(24));
        int displayWidth = baseWidth;
        if (viewportHeight > 0) {
            int baseHeight = Math.max(1, viewportHeight - dpToPx(16));
            float bitmapAspect = currentBitmap.getWidth() / (float) currentBitmap.getHeight();
            displayWidth = Math.min(baseWidth, Math.max(1, Math.round(baseHeight * bitmapAspect)));
        }

        android.view.ViewGroup.LayoutParams params = pageImage.getLayoutParams();
        if (params != null) {
            params.width = displayWidth;
            params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            pageImage.setLayoutParams(params);
        }
        pageImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        applySinglePageVerticalOffset();
    }

    private void applySinglePageVerticalOffset() {
        if (verticalPageSlideMode || pageImage == null || pdfVScroll == null || pdfViewport == null) return;

        int availableHeight = pdfVScroll.getHeight();
        if (availableHeight <= 0) {
            availableHeight = pdfViewport.getHeight()
                    - pdfViewport.getPaddingTop() - pdfViewport.getPaddingBottom();
        }

        int pageHeight = 0;
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            int imageWidth = pageImage.getLayoutParams() != null ? pageImage.getLayoutParams().width : 0;
            if (imageWidth > 0 && currentBitmap.getWidth() > 0) {
                pageHeight = Math.max(1,
                        Math.round(imageWidth * (currentBitmap.getHeight() / (float) currentBitmap.getWidth())));
            }
        }
        if (pageHeight <= 0) {
            pageHeight = pageImage.getHeight();
        }

        int verticalInset = 0;
        if (zoom <= 1.05f && availableHeight > 0 && pageHeight > 0 && pageHeight < availableHeight) {
            verticalInset = Math.max(0, (availableHeight - pageHeight) / 2);
        }
        android.view.ViewGroup.LayoutParams rawParams = pageImage.getLayoutParams();
        if (!(rawParams instanceof android.view.ViewGroup.MarginLayoutParams)) return;

        android.view.ViewGroup.MarginLayoutParams params =
                (android.view.ViewGroup.MarginLayoutParams) rawParams;
        if (params.topMargin == verticalInset && params.bottomMargin == verticalInset) return;
        params.topMargin = verticalInset;
        params.bottomMargin = verticalInset;
        pageImage.setLayoutParams(params);
    }

    private boolean isPdfContentScrollable() {
        return isPdfHorizontallyScrollable() || isPdfVerticallyScrollable();
    }

    private boolean isPdfVerticallyScrollable() {
        if (pdfVScroll == null || pdfVScroll.getChildCount() == 0) return false;
        int childHeight = pdfVScroll.getChildAt(0).getHeight();
        int viewportHeight = pdfVScroll.getHeight();
        return childHeight > viewportHeight + dpToPx(4);
    }

    private void panPdfContent(float deltaX, float deltaY) {
        if (pdfHScroll != null && isPdfHorizontallyScrollable()) {
            int maxX = getPdfHorizontalScrollRange();
            int nextX = Math.max(0, Math.min(maxX, pdfHScroll.getScrollX() + Math.round(deltaX)));
            pdfHScroll.scrollTo(nextX, pdfHScroll.getScrollY());
        }
        if (pdfVScroll != null && isPdfVerticallyScrollable()) {
            int maxY = getPdfVerticalScrollRange();
            int nextY = Math.max(0, Math.min(maxY, pdfVScroll.getScrollY() + Math.round(deltaY)));
            pdfVScroll.scrollTo(pdfVScroll.getScrollX(), nextY);
        }
    }

    private boolean isPdfAtOriginalZoom() {
        return zoom <= 1.08f;
    }

    private int getPdfHorizontalPageSwipeThresholdPx() {
        return dpToPx(isPdfAtOriginalZoom() ? 46 : 68);
    }

    private float getPdfHorizontalPageSwipeDominanceRatio() {
        return isPdfAtOriginalZoom() ? 1.08f : 1.22f;
    }

    private int getPdfVerticalPageSwipeThresholdPx() {
        return dpToPx(isPdfAtOriginalZoom() ? 52 : 82);
    }

    private float getPdfVerticalPageSwipeDominanceRatio() {
        return isPdfAtOriginalZoom() ? 1.10f : 1.25f;
    }

    /**
     * When the PDF is zoomed wider than the viewport, horizontal swipes should pan
     * around the enlarged page first. Page turn is allowed only after the scroll
     * position reaches the matching horizontal edge. When the page fits the screen,
     * swipes behave as normal page turns.
     */
    private boolean canTurnPdfPageFromSwipe(float dx) {
        if (pageCount <= 1 || pdfHScroll == null) return false;
        if (!gestureStartedWithHorizontalScrollable) return true;

        // If this gesture merely panned the zoomed page into the edge, stop there.
        // Turn the page only when the gesture started while already resting on that edge.
        if (dx < 0) {
            return gestureStartedAtRightEdge && currentPage < pageCount - 1;
        } else {
            return gestureStartedAtLeftEdge && currentPage > 0;
        }
    }

    private boolean isPdfHorizontallyScrollable() {
        if (pdfHScroll == null || pdfHScroll.getChildCount() == 0) return false;
        int childWidth = pdfHScroll.getChildAt(0).getWidth();
        int viewportWidth = pdfHScroll.getWidth();
        return childWidth > viewportWidth + dpToPx(4);
    }

    private boolean isPdfAtLeftEdge(int tolerancePx) {
        return pdfHScroll == null || pdfHScroll.getScrollX() <= tolerancePx;
    }

    private boolean isPdfAtRightEdge(int tolerancePx) {
        if (pdfHScroll == null || pdfHScroll.getChildCount() == 0) return true;
        int maxScroll = Math.max(0, pdfHScroll.getChildAt(0).getWidth() - pdfHScroll.getWidth());
        return pdfHScroll.getScrollX() >= maxScroll - tolerancePx;
    }

    private boolean isPdfAtTopEdge(int tolerancePx) {
        return pdfVScroll == null || pdfVScroll.getScrollY() <= tolerancePx;
    }

    private boolean isPdfAtBottomEdge(int tolerancePx) {
        if (pdfVScroll == null || pdfVScroll.getChildCount() == 0) return true;
        int maxScroll = Math.max(0, pdfVScroll.getChildAt(0).getHeight() - pdfVScroll.getHeight());
        return pdfVScroll.getScrollY() >= maxScroll - tolerancePx;
    }

    private boolean canTurnPdfPageFromVerticalSwipe(float dy) {
        if (pageCount <= 1 || pdfVScroll == null) return false;
        if (!gestureStartedWithVerticalScrollable) return true;

        // Same edge rule as horizontal zoomed-page panning:
        // first drag pans the enlarged page to the edge; a drag that starts at the
        // edge turns to the neighboring page.
        if (dy < 0) {
            return gestureStartedAtBottomEdge && currentPage < pageCount - 1;
        } else {
            return gestureStartedAtTopEdge && currentPage > 0;
        }
    }

    private boolean isEventInsideView(View view, @NonNull MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= loc[0] && x <= loc[0] + view.getWidth()
                && y >= loc[1] && y <= loc[1] + view.getHeight();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startup().onResume();
    }

    void resolveReaderThemeColors() {
        Theme theme = ThemeManager.getInstance(this).getActiveTheme();
        if (theme != null) {
            readerBg = theme.getBackgroundColor();
            readerFg = theme.getTextColor();
            readerToolbarBg = theme.getToolbarColor();
        }
        readerSub = blendColors(readerBg, readerFg, isDarkColor(readerBg) ? 0.72f : 0.64f);
        readerPanel = blendColors(readerBg, readerFg, isDarkColor(readerBg) ? 0.10f : 0.08f);
        readerLine = blendColors(readerBg, readerFg, isDarkColor(readerBg) ? 0.28f : 0.20f);
    }

    void applyDocumentSystemBarColors() {
        resolveReaderThemeColors();
        int statusBg = pdfChromeVisible ? readerToolbarBg : readerBg;
        int navBg = pdfChromeVisible ? readerPanel : readerBg;
        getWindow().setStatusBarColor(statusBg);
        getWindow().setNavigationBarColor(navBg);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(navBg);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkColor(statusBg));
            controller.setAppearanceLightNavigationBars(!isDarkColor(navBg));
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (!isDarkColor(navBg)) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!isDarkColor(statusBg)) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        applyPdfChromeFillColors();
    }

    void applyPdfChromeFillColors() {
        int appbarBg = readerToolbarBg;
        if (pdfAppBar != null) {
            pdfAppBar.setBackgroundColor(appbarBg);
        }
        if (pdfToolbar != null) {
            pdfToolbar.setBackgroundColor(readerToolbarBg);
        }
        if (pdfTopPageStatus != null) {
            pdfTopPageStatus.setBackgroundColor(readerToolbarBg);
        }
        if (pdfBottomBar != null) {
            pdfBottomBar.setBackground(pdfBottomChromeBackground(readerPanel, readerBg));
        }
        if (pdfNavBarSpacer != null) {
            pdfNavBarSpacer.setBackgroundColor(readerBg);
        }
        if (root != null) {
            root.setBackgroundColor(readerBg);
        }
    }

    boolean isDarkColor(int color) {
        return UiColorUtils.isDarkColor(color);
    }

    int blendColors(int base, int overlay, float overlayAlpha) {
        return UiColorUtils.blendColors(base, overlay, overlayAlpha);
    }

    private Drawable pdfBottomChromeBackground(int color, int cornerFillColor) {
        // Match the document/TXT bottom chrome: the toolbar is an overlay and the rounded
        // top corners remain transparent, so the page behind the toolbar shows through.
        // Do not fill the outside-corner area with black or the reader background.
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(color);
        float r = dpToPx(12);
        panel.setCornerRadii(new float[]{
                r, r,   // top-left
                r, r,   // top-right
                0, 0,   // bottom-right
                0, 0    // bottom-left
        });
        return panel;
    }

    @Override
    protected void onNewIntent(@NonNull android.content.Intent intent) {
        super.onNewIntent(intent);
        startup().onNewIntent(intent);
    }

    android.graphics.drawable.Drawable tintedBackIcon() {
        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(
                this, androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        if (icon == null) return null;
        android.graphics.drawable.Drawable wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate());
        androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, readerFg);
        return wrapped;
    }

    void styleControls() {
        resolveReaderThemeColors();
        if (root != null) root.setBackgroundColor(readerBg);
        if (pdfAppBar == null) pdfAppBar = findViewById(R.id.pdf_appbar);
        if (pdfAppBar != null) pdfAppBar.setBackgroundColor(readerToolbarBg);
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            pdfToolbar = toolbar;
            toolbar.setBackgroundColor(readerToolbarBg);
            toolbar.setTitleTextColor(readerFg);
            toolbar.setNavigationIcon(tintedBackIcon());
        }
        if (pdfTopPageStatus == null) pdfTopPageStatus = findViewById(R.id.pdf_top_page_status);
        if (pdfTopPageStatus != null) {
            pdfTopPageStatus.setTextColor(readerFg);
            pdfTopPageStatus.setBackgroundColor(readerToolbarBg);
        }
        if (pdfBottomBar == null) pdfBottomBar = findViewById(R.id.pdf_bottom_bar);
        if (pdfBottomBar != null) pdfBottomBar.setBackground(pdfBottomChromeBackground(readerPanel, readerBg));
        if (pdfNavBarSpacer == null) pdfNavBarSpacer = findViewById(R.id.pdf_nav_bar_spacer);
        if (pdfNavBarSpacer != null) pdfNavBarSpacer.setBackgroundColor(readerBg);
        if (pdfViewport != null) pdfViewport.setBackgroundColor(readerBg);
        if (pageStatus != null) pageStatus.setTextColor(readerFg);
        if (pdfPageSeekBar != null) tintSeekBar(pdfPageSeekBar);
        applyPdfChromeFillColors();
        updateLoadingIndicatorTheme();

        TextView[] buttons = {prevButton, nextButton, slideModeButton, pageButton, bookmarkButton,
                findViewById(R.id.pdf_screen_rotation), findViewById(R.id.pdf_settings), zoomMoreButton};
        for (TextView b : buttons) {
            if (b == null) continue;
            b.setTextColor(readerFg);
            b.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(readerFg));
        }
    }


    void updateLoadingIndicatorTheme() {
        if (progressBar == null) return;
        progressBar.setBackgroundColor(Color.TRANSPARENT);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(readerFg));
    }

    void setupControls() {
        prevButton.setOnClickListener(v -> goToPage(currentPage - 1, -1));
        nextButton.setOnClickListener(v -> goToPage(currentPage + 1, 1));
        if (slideModeButton != null) {
            slideModeButton.setOnClickListener(v -> togglePdfSlideMode());
            updatePdfSlideModeButton();
        }
        if (pageButton != null) pageButton.setOnClickListener(v -> showGoToPageDialog());
        bookmarkButton.setOnClickListener(v -> showBookmarksDialog());
        View pdfRotationButton = findViewById(R.id.pdf_screen_rotation);
        if (pdfRotationButton != null) {
            pdfRotationButton.setOnClickListener(v ->
                    com.readwide.manager.util.ScreenOrientationToggle.toggle(this));
        }
        updateRotationButtonIcon();
        View pdfSettingsButton = findViewById(R.id.pdf_settings);
        if (pdfSettingsButton != null) {
            pdfSettingsButton.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, SettingsActivity.class)));
        }
        if (zoomMoreButton != null) zoomMoreButton.setOnClickListener(v -> showMoreDialog());
        setupPdfPageSeekBar();
    }

    private void updateRotationButtonIcon() {
        com.readwide.manager.util.ScreenOrientationToggle.applyButtonIcon(
                this,
                findViewById(R.id.pdf_screen_rotation),
                R.drawable.ic_bottom_screen_rotation,
                R.drawable.ic_bottom_screen_portrait);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotationButtonIcon();
        styleControls();
        View pdfRoot = findViewById(R.id.pdf_root);
        if (pdfRoot != null) androidx.core.view.ViewCompat.requestApplyInsets(pdfRoot);
    }

    private void setupPdfPageSeekBar() {
        if (pdfPageSeekBar == null) return;
        tintSeekBar(pdfPageSeekBar);
        pdfPageSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int total = Math.max(1, pageCount);
                int safe = Math.max(0, Math.min(total - 1, progress));
                pageStatus.setText(String.format(Locale.getDefault(), "%d / %d", safe + 1, total));
                if (prevButton != null) prevButton.setEnabled(safe > 0);
                if (nextButton != null) nextButton.setEnabled(safe < total - 1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pdfPageSeekBarUserTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int total = Math.max(1, pageCount);
                int target = Math.max(0, Math.min(total - 1, seekBar.getProgress()));
                pdfPageSeekBarUserTracking = false;
                goToPage(target, Integer.compare(target, currentPage));
            }
        });
    }

    // --- Hardware page-turn keys ---

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (pageTurns().handlePageTurnKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Fallback for devices that route hardware keys through onKeyDown() instead
        // of dispatchKeyEvent(). dispatchKeyEvent() normally consumes these first.
        if (pageTurns().handlePageTurnKey(event)) return true;
        return super.onKeyDown(keyCode, event);
    }


    void installPdfGestures() {
        pdfFlingScroller = new OverScroller(this);
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                stopPdfViewportFling();
                capturePinchZoomFocus(detector);
                applyPageImagePivotFromRaw(activePinchFocusRawX, activePinchFocusRawY);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float nextZoom = Math.max(0.55f, Math.min(4.5f, zoom * detector.getScaleFactor()));
                if (Math.abs(nextZoom - zoom) > 0.004f) {
                    zoom = nextZoom;
                    pinchZoomChanged = true;
                    if (!verticalPageSlideMode && pageImage != null) {
                        float displayScale = Math.max(0.55f, Math.min(4.5f, zoom / Math.max(0.1f, renderedZoom)));
                        pageImage.setScaleX(displayScale);
                        pageImage.setScaleY(displayScale);
                    }
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                if (pinchZoomChanged) {
                    pinchZoomChanged = false;
                    // Keep the focus captured at onScaleBegin. Re-capturing here from
                    // the detector is wrong: when the fingers lift, the detector's
                    // focus point jumps, which would scroll the page to a completely
                    // wrong location. The begin-time focus already matches the
                    // pivot the page was scaled around during the gesture.
                    activePinchFocusRawX = -1f;
                    activePinchFocusRawY = -1f;
                    renderCurrentPage(false);
                }
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (handlePdfTapPaging(e)) {
                    return true;
                }
                // The toolbar/appbar float over the full-screen viewport. A tap on
                // a visible chrome bar (e.g. pressing a toolbar icon) must not also
                // toggle chrome here, which would hide the toolbar while leaving the
                // icon's popup on screen.
                if (tapIntersectsVisiblePdfChrome(e)) {
                    return true;
                }
                togglePdfChrome();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                stopPdfViewportFling();
                toggleDoubleTapZoom(e);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                return startPdfViewportFling(velocityX, velocityY);
            }
        });
    }

    private void setZoomSmooth(float targetZoom) {
        setZoomSmooth(targetZoom, null);
    }

    private void setZoomSmooth(float targetZoom, MotionEvent focusEvent) {
        // Stage-1 Matrix zoom: the Matrix view owns zoom in single-page mode, so the
        // legacy scroll/render zoom path is disabled while it is active.
        if (pdfPageMatrixView != null && pdfPageMatrixView.getVisibility() == View.VISIBLE
                && !verticalPageSlideMode) {
            return;
        }
        zoom = Math.max(0.55f, Math.min(4.5f, targetZoom));
        if (verticalPageSlideMode) {
            if (pdfContinuousAdapter != null) pdfContinuousAdapter.clearBitmaps();
            renderContinuousPages();
            return;
        }
        // Zoomed pages are large; release cached neighbor bitmaps now so they
        // don't coexist with the big zoomed bitmap and exhaust the heap. The
        // currently shown bitmap is protected from recycle by entryRemoved.
        if (zoom > 1.05f && singlePageCache.size() > 0) {
            singlePageCache.evictAll();
            singlePagePrefetchInFlight.clear();
            singlePageCacheWidth = -1;
            singlePageCacheHeight = -1;
        }
        // Update the bottom reserve for the new zoom: dropped when zoomed in so the
        // page can scroll into the full height, restored at fit zoom.
        applyPdfViewportBottomInset();
        if (focusEvent != null) {
            prepareDoubleTapZoomFocus(focusEvent);
            applyPageImagePivotFromRaw(focusEvent.getRawX(), focusEvent.getRawY());
        } else {
            prepareZoomFocusFromViewportCenter();
            applyPageImagePivotFromPreparedFocus();
        }
        // Single-stage zoom: render the zoomed bitmap directly. We do NOT animate
        // a scale on the old bitmap first. That animation scaled around an
        // ImageView-space pivot while the final position is set via viewport-space
        // scrolling; with toolbar reserve and vertical-centering offsets those two
        // coordinate systems disagree, so the page visibly jumped at the hand-off
        // from animation to render. The render path keeps the new bitmap hidden
        // (alpha 0) until the focus scroll is in place, then reveals it — so the
        // zoomed page simply appears, correctly positioned, with no jump.
        // (Single-page mode now uses PdfPageView; this legacy path remains for
        // safety/fallback.)
        if (pageImage != null) {
            pageImage.animate().cancel();
        }
        renderCurrentPage(false);
    }

    private void prepareDoubleTapZoomFocus(MotionEvent focusEvent) {
        if (focusEvent == null) {
            pendingZoomFocus = false;
            return;
        }
        prepareZoomFocusFromRaw(focusEvent.getRawX(), focusEvent.getRawY());
    }

    private void prepareZoomFocusFromViewportCenter() {
        if (pdfViewport == null) {
            pendingZoomFocus = false;
            return;
        }
        int[] viewportLoc = new int[2];
        pdfViewport.getLocationOnScreen(viewportLoc);
        prepareZoomFocusFromRaw(
                viewportLoc[0] + pdfViewport.getWidth() * 0.5f,
                viewportLoc[1] + pdfViewport.getHeight() * 0.5f);
    }

    private void prepareZoomFocusFromRaw(float rawX, float rawY) {
        if (rawX < 0f || rawY < 0f || pageImage == null || pdfViewport == null) {
            pendingZoomFocus = false;
            return;
        }

        int imageWidth = Math.max(1, pageImage.getWidth());
        int imageHeight = Math.max(1, pageImage.getHeight());

        int[] imageLoc = new int[2];
        int[] viewportLoc = new int[2];
        pageImage.getLocationOnScreen(imageLoc);
        pdfViewport.getLocationOnScreen(viewportLoc);

        float localX = Math.max(0f, Math.min(imageWidth, rawX - imageLoc[0]));
        float localY = Math.max(0f, Math.min(imageHeight, rawY - imageLoc[1]));

        pendingZoomFocusXRatio = localX / imageWidth;
        pendingZoomFocusYRatio = localY / imageHeight;
        pendingZoomViewportX = Math.max(0f, Math.min(pdfViewport.getWidth(), rawX - viewportLoc[0]));
        pendingZoomViewportY = Math.max(0f, Math.min(pdfViewport.getHeight(), rawY - viewportLoc[1]));
        pendingZoomFocus = true;
    }

    private void capturePinchZoomFocus(ScaleGestureDetector detector) {
        if (detector == null || root == null) {
            activePinchFocusRawX = -1f;
            activePinchFocusRawY = -1f;
            pendingZoomFocus = false;
            return;
        }
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        activePinchFocusRawX = rootLoc[0] + detector.getFocusX();
        activePinchFocusRawY = rootLoc[1] + detector.getFocusY();
        prepareZoomFocusFromRaw(activePinchFocusRawX, activePinchFocusRawY);
    }

    private void applyPageImagePivotFromPreparedFocus() {
        if (!pendingZoomFocus || pdfViewport == null) return;
        int[] viewportLoc = new int[2];
        pdfViewport.getLocationOnScreen(viewportLoc);
        applyPageImagePivotFromRaw(
                viewportLoc[0] + pendingZoomViewportX,
                viewportLoc[1] + pendingZoomViewportY);
    }

    private void applyPageImagePivotFromRaw(float rawX, float rawY) {
        if (rawX < 0f || rawY < 0f || pageImage == null) return;
        pageImage.animate().cancel();
        int[] imageLoc = new int[2];
        pageImage.getLocationOnScreen(imageLoc);
        pageImage.setPivotX(Math.max(0f, Math.min(pageImage.getWidth(), rawX - imageLoc[0])));
        pageImage.setPivotY(Math.max(0f, Math.min(pageImage.getHeight(), rawY - imageLoc[1])));
    }

    private void showMoreDialog() {
        ThemeManager.getInstance(this).reloadFromStorage();
        resolveReaderThemeColors();

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.more)));

        final TextView[] slideModeRowRef = new TextView[1];
        slideModeRowRef[0] = addDialogActionView(box, pdfSlideModeDialogLabel(), () -> {
            togglePdfSlideMode();
            refreshPdfSlideModeDialogRow(slideModeRowRef[0]);
        });

        addDialogAction(box, getString(R.string.zoom_out), () -> setZoomSmooth(Math.max(0.55f, zoom - 0.2f)));
        addDialogAction(box, getString(R.string.zoom_in), () -> setZoomSmooth(Math.min(4.5f, zoom + 0.2f)));
        addDialogAction(box, getString(R.string.reset_zoom), this::resetZoomToOriginal);
        addDialogAction(box, getString(R.string.settings), () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            startActivity(new android.content.Intent(this, SettingsActivity.class));
        });
        addDialogAction(box, getString(R.string.file_info), () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showFileInfoDialog();
        });
        addDialogBottomActions(box,
                getString(R.string.action_open_file), () -> {
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                    openFileBrowserFromViewer();
                },
                getString(R.string.close), () -> {
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                });
        dialogRef[0] = createStablePositionedDialog(box, PDF_TOOLBAR_POPUP_Y_DP, false, false);
        dialogRef[0].show();
    }

    private void openFileBrowserFromViewer() {
        android.content.Intent intent = new android.content.Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_RETURN_TO_VIEWER, true);
        File current = filePath != null ? new File(filePath) : null;
        File parent = current != null ? current.getParentFile() : null;
        if (parent != null && parent.exists() && parent.isDirectory()) {
            intent.putExtra(MainActivity.EXTRA_START_DIRECTORY, parent.getAbsolutePath());
        }
        startActivity(intent);
    }

    private void refreshPdfSlideModeDialogRow(TextView slideModeRow) {
        if (slideModeRow == null) return;
        slideModeRow.setText(pdfSlideModeDialogLabel());
        slideModeRow.setContentDescription(pdfSlideModeDialogLabel());
    }

    private void toggleDoubleTapZoom(MotionEvent focusEvent) {
        if (zoom <= 1.08f) {
            setZoomSmooth(2.35f, focusEvent);
        } else {
            resetZoomToOriginal();
        }
    }

    private void togglePdfSlideMode() {
        verticalPageSlideMode = !verticalPageSlideMode;
        getSharedPreferences("pdf_reader", MODE_PRIVATE)
                .edit()
                .putBoolean("vertical_page_slide_mode", verticalPageSlideMode)
                .apply();
        updatePdfSlideModeButton();
        applyPdfDisplayMode();
    }

    private void updatePdfSlideModeButton() {
        if (slideModeButton == null) return;

        slideModeButton.setText(pdfSlideModeButtonLabel());
        slideModeButton.setCompoundDrawablesWithIntrinsicBounds(
                0,
                verticalPageSlideMode
                        ? R.drawable.ic_pdf_slide_up_down
                        : R.drawable.ic_pdf_slide_left_right,
                0,
                0);
        slideModeButton.setContentDescription(pdfSlideModeDialogLabel());
    }

    private boolean isKoreanUi() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private String pdfSlideModeButtonLabel() {
        if (verticalPageSlideMode) return isKoreanUi() ? "세로" : "V";
        return isKoreanUi() ? "가로" : "H";
    }

    private String pdfSlideModeDialogLabel() {
        if (verticalPageSlideMode) {
            return isKoreanUi()
                    ? "읽기 방식: 세로 연속 스크롤"
                    : "Read mode: Vertical continuous scroll";
        }
        return isKoreanUi()
                ? "읽기 방식: 가로로 다음/이전 페이지"
                : "Read mode: Horizontal next/previous page";
    }


    private void resetZoomToOriginal() {
        setZoomSmooth(1.0f);
    }

    private boolean isPdfZoomedForPageNavigation() {
        return zoom > 1.08f || renderedZoom > 1.08f;
    }

    private boolean resetPdfZoomForPageNavigationIfNeeded() {
        if (!isPdfZoomedForPageNavigation()) return false;

        zoom = 1.0f;
        renderedZoom = 1.0f;
        pendingZoomFocus = false;
        pinchZoomChanged = false;
        activePinchFocusRawX = -1f;
        activePinchFocusRawY = -1f;

        if (pageImage != null) {
            pageImage.animate().cancel();
            pageImage.setScaleX(1.0f);
            pageImage.setScaleY(1.0f);
            pageImage.setPivotX(pageImage.getWidth() * 0.5f);
            pageImage.setPivotY(pageImage.getHeight() * 0.5f);
        }
        return true;
    }

    private void showFileInfoDialog() {
        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.file_info)));
        addInfoRow(box, getString(R.string.file_info_name), fileName != null ? fileName : "");
        addInfoRow(box, getString(R.string.file_info_type), "PDF");
        addInfoRow(box, getString(R.string.file_info_path), filePath != null ? filePath : "");
        if (localFile != null) {
            addInfoRow(box, getString(R.string.file_info_size), FileUtils.formatFileSize(localFile.length()));
            addInfoRow(box, getString(R.string.file_info_modified), DateFormat.getDateTimeInstance().format(new Date(localFile.lastModified())));
        }
        addInfoRow(box, getString(R.string.bottom_page), String.format(Locale.getDefault(), "%d / %d", currentPage + 1, pageCount));
        showFileInfoDialogWithCenteredClose(box);
    }

    private void showGoToPageDialog() {
        if (pageCount <= 0) return;

        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.page_move)));

        TextView label = new TextView(this);
        label.setTextColor(dialogFg());
        label.setTextSize(17f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setGravity(android.view.Gravity.CENTER);
        label.setText(formatPageMoveLabel(currentPage + 1, pageCount));
        label.setPadding(0, dpToPx(4), 0, dpToPx(8));
        box.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar slider = new SeekBar(this);
        slider.setMax(Math.max(0, pageCount - 1));
        slider.setProgress(currentPage);
        tintSeekBar(slider);
        box.addView(slider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)));

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.exact_page_number));
        hint.setTextColor(blendColors(dialogBg(), dialogFg(), 0.78f));
        hint.setTextSize(13f);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(0, dpToPx(4), 0, dpToPx(6));
        box.addView(hint);

        EditText input = makeDialogInput("1 - " + pageCount);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setGravity(android.view.Gravity.CENTER);
        input.setText(String.valueOf(currentPage + 1));
        input.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(dpToPx(132), dpToPx(52));
        inputLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        box.addView(input, inputLp);

        final int[] pending = new int[]{currentPage};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                pending[0] = progress;
                label.setText(formatPageMoveLabel(progress + 1, pageCount));
                input.setText(String.valueOf(progress + 1));
                input.setSelection(input.getText().length());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                goToPage(pending[0], Integer.compare(pending[0], currentPage));
            }
        });

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        addCenteredDialogBottomAction(box, getString(R.string.go), () -> {
            try {
                int target = Integer.parseInt(input.getText().toString().trim());
                if (target < 1 || target > pageCount) {
                    ShortToast.show(this, getString(R.string.page_range_error, pageCount));
                    return;
                }
                goToPage(target - 1, Integer.compare(target - 1, currentPage));
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            } catch (Exception ignored) {
                ShortToast.show(this, getString(R.string.invalid_page_number));
            }
        });
        dialogRef[0] = createStablePositionedDialog(box, PDF_TOOLBAR_POPUP_Y_DP, true, false);
        dialogRef[0].show();
    }

    private int txtReaderDialogWidthPx() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dpToPx(220), Math.min(Math.round(screenWidth * 0.85f), dpToPx(460)));
    }

    private int legacyBookmarkDialogWidthPx() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.min(screenWidth - dpToPx(14), dpToPx(460));
    }



    private String formatPageMoveLabel(int page, int totalPages) {
        return String.format(Locale.getDefault(), "Page %d / %d", page, Math.max(1, totalPages));
    }

    private int dialogBg() { return readerBg; }
    private int dialogPanel() { return readerPanel; }
    int dialogFg() { return readerFg; }
    int dialogSub() { return readerSub; }

    LinearLayout makeDialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dialogBg());
        bg.setCornerRadius(dpToPx(14));
        bg.setStroke(Math.max(1, dpToPx(1)), readerLine);
        box.setBackground(bg);
        return box;
    }

    TextView makeDialogTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(dialogFg());
        title.setTextSize(22f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(0, 0, 0, dpToPx(12));
        return title;
    }

    EditText makeDialogInput(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextColor(dialogFg());
        input.setHintTextColor(dialogSub());
        input.setPadding(dpToPx(14), 0, dpToPx(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dialogPanel());
        bg.setCornerRadius(dpToPx(8));
        bg.setStroke(Math.max(1, dpToPx(1)), readerLine);
        input.setBackground(bg);
        return input;
    }

    private void tintSeekBar(SeekBar seekBar) {
        int accent = readerFg;
        int track = readerLine;
        // Match the TXT reader slider: keep the platform/default thumb size and only tint it.
        // A previously forced 14–20dp oval thumb made PDF/document viewers look larger.
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track));
        seekBar.setBackgroundColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            seekBar.setStateListAnimator(null);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            seekBar.setForeground(null);
        }
    }


    private void addDialogAction(LinearLayout box, String text, Runnable action) {
        addDialogActionView(box, text, action);
    }

    private TextView addDialogActionView(LinearLayout box, String text, Runnable action) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setContentDescription(text);
        row.setTextColor(dialogFg());
        row.setTextSize(16f);
        row.setGravity(android.view.Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dialogPanel());
        bg.setCornerRadius(dpToPx(10));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48));
        lp.setMargins(0, 0, 0, dpToPx(8));
        box.addView(row, lp);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void addInfoRow(LinearLayout box, String label, String value) {
        TextView row = new TextView(this);
        row.setText(label + "\n" + (value != null ? value : ""));
        row.setTextColor(dialogFg());
        row.setTextSize(14f);
        row.setPadding(0, dpToPx(5), 0, dpToPx(7));
        box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }


    private android.app.Dialog showFileInfoDialogWithCenteredClose(LinearLayout box) {
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        addCenteredDialogBottomAction(box, getString(R.string.close), () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        dialogRef[0] = createStablePositionedDialog(box, PDF_TOOLBAR_POPUP_Y_DP, false, false);
        dialogRef[0].show();
        return dialogRef[0];
    }

    private void addCenteredDialogBottomAction(LinearLayout box, String primaryText, Runnable primaryAction) {
        if (box.findViewWithTag("dialog_actions") != null) return;

        LinearLayout actions = new LinearLayout(this);
        actions.setTag("dialog_actions");
        actions.setGravity(android.view.Gravity.CENTER);
        actions.setPadding(0, dpToPx(8), 0, 0);

        TextView primary = new TextView(this);
        primary.setText(primaryText);
        primary.setTextColor(dialogFg());
        primary.setTextSize(16f);
        primary.setGravity(android.view.Gravity.CENTER);
        primary.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        primary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        primary.setPadding(dpToPx(18), 0, dpToPx(18), 0);
        actions.addView(primary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(46)));
        box.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        primary.setOnClickListener(v -> primaryAction.run());
    }

    private android.app.Dialog showCustomDialog(LinearLayout box, String closeText) {
        return showCustomDialog(box, closeText, false);
    }

    private android.app.Dialog showCustomDialog(LinearLayout box, String closeText, boolean oneHandLower) {
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        addDialogBottomActions(box, null, closeText, () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        dialogRef[0] = createStablePositionedDialog(box, PDF_TOOLBAR_POPUP_Y_DP, false, false);
        dialogRef[0].show();
        return dialogRef[0];
    }

    android.app.Dialog createStablePositionedDialog(@NonNull View content,
                                                    int yDp,
                                                    boolean adjustResize,
                                                    boolean legacyBookmarkWidth) {
        int widthPx = legacyBookmarkWidth ? legacyBookmarkDialogWidthPx() : txtReaderDialogWidthPx();
        return AdaptiveDialogLayoutHelper.createStableBottomDialog(this, content, yDp, adjustResize, widthPx);
    }

    ScrollView wrapAdaptiveDialogContent(@NonNull View content, @NonNull ViewGroup outerFrame) {
        return AdaptiveDialogLayoutHelper.wrapAdaptiveContent(this, content, outerFrame);
    }

    void applyAdaptiveDialogMaxHeight(@NonNull android.app.Dialog dialog, @NonNull View adaptiveView, int widthPx) {
        AdaptiveDialogLayoutHelper.applyAdaptiveMaxHeight(this, adaptiveView, widthPx);
    }


    void addDialogBottomActions(LinearLayout box, android.app.Dialog dialog, String primaryText, Runnable primaryAction) {
        addDialogBottomActions(box, null, null, primaryText, primaryAction);
    }

    void addDialogBottomActions(LinearLayout box,
                                        String secondaryText,
                                        Runnable secondaryAction,
                                        String primaryText,
                                        Runnable primaryAction) {
        if (box.findViewWithTag("dialog_actions") != null) return;
        LinearLayout actions = new LinearLayout(this);
        actions.setTag("dialog_actions");
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dpToPx(8), 0, 0);

        if (secondaryText != null && secondaryAction != null) {
            TextView secondary = new TextView(this);
            secondary.setText(secondaryText);
            secondary.setTextColor(dialogFg());
            secondary.setTextSize(16f);
            secondary.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
            secondary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            secondary.setPadding(dpToPx(18), 0, dpToPx(18), 0);
            actions.addView(secondary, new LinearLayout.LayoutParams(0, dpToPx(46), 1f));
            secondary.setOnClickListener(v -> secondaryAction.run());
        } else {
            Space spacer = new Space(this);
            actions.addView(spacer, new LinearLayout.LayoutParams(0, dpToPx(46), 1f));
        }

        TextView primary = new TextView(this);
        primary.setText(primaryText);
        primary.setTextColor(dialogFg());
        primary.setTextSize(16f);
        primary.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT);
        primary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        primary.setPadding(dpToPx(18), 0, dpToPx(18), 0);
        actions.addView(primary, new LinearLayout.LayoutParams(0, dpToPx(46), 1f));
        box.addView(actions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        primary.setOnClickListener(v -> primaryAction.run());
    }


    void loadPdfFromIntent() {
        if (activityDestroyed) return;
        updateLoadingIndicatorTheme();
        progressBar.setVisibility(View.VISIBLE);
        pageStatus.setText(getString(R.string.loading));

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        String uriStr = getIntent().getStringExtra(EXTRA_FILE_URI);
        int jumpPage = getIntent().getIntExtra(EXTRA_JUMP_TO_PAGE, -1);
        pendingPdfContentAnchorJson = getIntent().getStringExtra(EXTRA_CONTENT_ANCHOR_JSON);
        if (pendingPdfContentAnchorJson == null) pendingPdfContentAnchorJson = "";

        executor.execute(() -> {
            try {
                File pdfFile;
                String loadedName;
                if (path != null) {
                    pdfFile = new File(path);
                    loadedName = pdfFile.getName();
                } else if (uriStr != null) {
                    Uri uri = Uri.parse(uriStr);
                    loadedName = FileUtils.getFileNameFromUri(this, uri);
                    if (loadedName == null || loadedName.trim().isEmpty()) loadedName = "opened.pdf";
                    pdfFile = FileUtils.copyUriToLocal(this, uri, loadedName);
                } else {
                    throw new IllegalArgumentException("No PDF selected");
                }

                final File finalFile = pdfFile;
                final String finalName = loadedName;
                handler.post(() -> {
                    if (!activityDestroyed) openPdfFile(finalFile, finalName, jumpPage);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (!activityDestroyed) showLoadError(e);
                });
            }
        });
    }

    private void openPdfFile(@NonNull File pdfFile, String loadedName, int jumpPage) {
        try {
            closeRenderer();
            localFile = pdfFile;
            filePath = pdfFile.getAbsolutePath();
            fileName = loadedName != null ? loadedName : pdfFile.getName();

            if (getSupportActionBar() != null) getSupportActionBar().setTitle(fileName);

            parcelFileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(parcelFileDescriptor);
            pageCount = pdfRenderer.getPageCount();
            if (pageCount <= 0) throw new IllegalStateException("PDF has no pages");

            // Second renderer over the same file for parallel neighbor prefetch.
            // Best-effort: if it can't open, prefetch falls back to skipping.
            try {
                prefetchParcelFileDescriptor =
                        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                prefetchRenderer = new PdfRenderer(prefetchParcelFileDescriptor);
            } catch (Exception prefetchOpenFailure) {
                prefetchRenderer = null;
                if (prefetchParcelFileDescriptor != null) {
                    try { prefetchParcelFileDescriptor.close(); } catch (Exception ignored) {}
                    prefetchParcelFileDescriptor = null;
                }
            }

            int restored = 0;
            if (jumpPage >= 0) {
                restored = jumpPage;
            } else if (prefs.getAutoSavePosition()) {
                ReaderState state = bookmarkManager.getReadingState(filePath);
                if (state != null) {
                    restored = state.getCharPosition();
                    String anchor = state.getContentAnchorJson();
                    if (anchor != null && !anchor.trim().isEmpty()) pendingPdfContentAnchorJson = anchor;
                }
            }
            currentPage = clampPage(restored);
            // Do not save immediately here. If this open is restoring an old
            // content anchor, saving before the page/view has rendered would
            // overwrite the precise PDF coordinate with a page-only fallback.
            applyPdfDisplayMode();
        } catch (Exception e) {
            showLoadError(e);
        }
    }

    private void showLoadError(Exception e) {
        if (activityDestroyed) return;
        progressBar.setVisibility(View.GONE);
        String message = getString(R.string.error_prefix) + (e.getMessage() != null ? e.getMessage() : e.toString());
        pageStatus.setText(message);
        ShortToast.show(this, message);
    }

    private int clampPage(int page) {
        if (page < 0) return 0;
        if (pageCount <= 0) return 0;
        return Math.min(page, pageCount - 1);
    }

    void goToPage(int page) {
        goToPage(page, Integer.compare(page, currentPage));
    }

    void goToPage(int page, int direction) {
        int target = clampPage(page);
        if (target == currentPage) {
            if (verticalPageSlideMode) {
                scrollContinuousListToCurrentPage(false);
            }
            return;
        }

        boolean zoomReset = !verticalPageSlideMode && resetPdfZoomForPageNavigationIfNeeded();
        pendingPageSlideDirection = direction == 0 ? Integer.compare(target, currentPage) : direction;
        currentPage = target;
        saveReadingState();
        updatePageStatus();

        if (verticalPageSlideMode) {
            // In continuous vertical mode, Go-to-page must jump the RecyclerView to
            // the target page position. Re-rendering the whole continuous list and
            // then smooth-scrolling can leave the list at an approximate/intermediate
            // position, especially while page bitmaps are still binding.
            ensureContinuousPagesConfigured();
            scrollContinuousListToCurrentPage(false);
            pendingPageSlideDirection = 0;
        } else {
            renderCurrentPage(zoomReset || currentBitmap == null);
        }
    }

    private void releaseSinglePageBitmap() {
        ++renderGeneration;
        if (pdfPageMatrixView != null) pdfPageMatrixView.detachBitmaps();
        if (pendingSinglePagePrefetch != null) {
            handler.removeCallbacks(pendingSinglePagePrefetch);
            pendingSinglePagePrefetch = null;
        }
        if (pageImage != null) {
            pageImage.animate().cancel();
            pageImage.setImageDrawable(null);
        }
        Bitmap shown = currentBitmap;
        currentBitmap = null; // clear before evict so entryRemoved may recycle it
        singlePageCache.evictAll();
        singlePagePrefetchInFlight.clear();
        singlePageCacheWidth = -1;
        singlePageCacheHeight = -1;
        if (shown != null && !shown.isRecycled()) {
            shown.recycle();
        }
        renderedZoom = 1.0f;
        pendingZoomFocus = false;
    }

    private void renderCurrentPage() {
        renderCurrentPage(currentBitmap == null);
    }

    private boolean isBitmapInSinglePageCache(@Nullable Bitmap bitmap) {
        if (bitmap == null) return false;
        return singlePageCache.snapshot().containsValue(bitmap);
    }

    private void invalidateSinglePageCacheIfNeeded(float zoomNow, int widthNow, int heightNow) {
        if (widthNow <= 0) return;
        if (Math.abs(zoomNow - singlePageCacheZoom) > 0.001f
                || widthNow != singlePageCacheWidth
                || heightNow != singlePageCacheHeight) {
            // Cached bitmaps were rendered at a different scale; drop them. evictAll
            // recycles each (except the on-screen one, guarded in entryRemoved).
            singlePageCache.evictAll();
            singlePagePrefetchInFlight.clear();
            singlePageCacheZoom = zoomNow;
            singlePageCacheWidth = widthNow;
            singlePageCacheHeight = heightNow;
        }
    }

    /** Displays an already-rendered single-page bitmap (cache hit) instantly. */
    private void showSinglePageBitmap(@NonNull Bitmap cached, float zoomForDisplay) {
        Bitmap old = currentBitmap;
        currentBitmap = cached;
        renderedZoom = zoomForDisplay;
        // Stage-1 Matrix zoom: feed the Matrix view and skip the legacy ImageView
        // path (which produced the flicker/jump). The Matrix view owns zoom/pan.
        if (pdfPageMatrixView != null && !verticalPageSlideMode) {
            boolean newPage = (old != cached);
            pdfPageMatrixView.setFitBitmap(cached, PDF_SUPERSAMPLE,
                    lastRenderedPageWidthPts, lastRenderedPageHeightPts, newPage);
            showMatrixPageView();
            updatePageStatus();
            if (old != null && old != cached && !old.isRecycled()
                    && !isBitmapInSinglePageCache(old)) {
                old.recycle();
            }
            return;
        }
        pageImage.animate().cancel();
        // Apply the new bitmap AND its new display width first, then reset scale to
        // 1.0 in the same layout pass. If we reset scale before the new width is in
        // place, one frame is drawn as (new bitmap x old small width x scale 1.0) —
        // a brief shrink that reads as a flicker right after the double-tap zoom
        // animation. Setting size first keeps the visual size continuous.
        if (pendingZoomFocus) {
            deferZoomBitmapReveal = true;
            pageImage.setAlpha(0.0f);
        }
        pageImage.setImageBitmap(cached);
        android.view.ViewGroup.LayoutParams ip = pageImage.getLayoutParams();
        int displayWidth = Math.max(1, Math.round(cached.getWidth() / PDF_SUPERSAMPLE));
        if (pendingZoomFocus) {
            pendingZoomRevealWidth = displayWidth;
        }
        if (ip != null) {
            ip.width = displayWidth;
            ip.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            pageImage.setLayoutParams(ip);
        }
        pageImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        pageImage.setScaleX(1.0f);
        pageImage.setScaleY(1.0f);
        applySinglePageVerticalOffset();
        if (pendingPageSlideDirection != 0) {
            positionPdfPageAfterPageTurn();
        } else {
            restoreDoubleTapZoomFocusAfterRender();
        }
        runPageSlideInAnimation();
        if (old != null && old != cached && !old.isRecycled()
                && !isBitmapInSinglePageCache(old)) {
            old.recycle();
        }
        restorePendingPdfContentAnchorIfNeeded();
    }

    /**
     * Schedules neighbor prefetch after a short idle delay. Rapid taps keep
     * rescheduling (cancelling the previous), so prefetch only runs once the user
     * pauses — it never competes with the on-demand render during fast paging.
     */
    private void scheduleAdjacentSinglePagePrefetch(int centerPage, float zoomForRender, int viewportWidth, int viewportHeight) {
        if (pendingSinglePagePrefetch != null) {
            handler.removeCallbacks(pendingSinglePagePrefetch);
        }
        final int dir = pendingPageSlideDirection;
        pendingSinglePagePrefetch = () -> {
            pendingSinglePagePrefetch = null;
            if (activityDestroyed || verticalPageSlideMode) return;
            if (currentPage != centerPage) return; // user moved on
            pendingPageSlideDirection = dir;
            prefetchAdjacentSinglePages(centerPage, zoomForRender, viewportWidth, viewportHeight);
        };
        handler.postDelayed(pendingSinglePagePrefetch, 16L);
    }

    /** Renders the next/previous page into the cache off the main thread. */
    private void prefetchAdjacentSinglePages(int centerPage, float zoomForRender, int viewportWidth, int viewportHeight) {
        if (verticalPageSlideMode || prefetchRenderer == null || pageCount <= 1 || viewportWidth <= 0) return;
        // Only prefetch at (or near) the fit zoom. Zoomed-in pages produce very
        // large bitmaps; caching several of them at once can exhaust the heap and
        // get the activity killed. Zoom is for examining one page anyway.
        if (zoomForRender > 1.05f) return;
        // Buffer equally in both directions so going back is as fast as going
        // forward. Two pages each way (interleaved so the nearest neighbors on
        // both sides are rendered first).
        int[] neighbors = {
                centerPage + 1, centerPage - 1,
                centerPage + 2, centerPage - 2,
        };
        for (int p : neighbors) {
            if (p < 0 || p >= pageCount) continue;
            if (singlePageCache.get(p) != null) continue;
            final int page = p;
            if (!singlePagePrefetchInFlight.add(page)) continue;
            final float zoomSnap = zoomForRender;
            final int widthSnap = viewportWidth;
            final int heightSnap = viewportHeight;
            prefetchExecutor.execute(() -> {
                Bitmap bmp = null;
                try {
                    // Skip if the user has already moved on or zoom/width changed,
                    // so prefetch work never competes with the visible render for
                    // pages that no longer matter.
                    if (destroyedOrZoomChanged(zoomSnap, widthSnap)
                            || Math.abs(currentPage - centerPage) > 2) {
                        return;
                    }
                    synchronized (prefetchRendererLock) {
                        if (prefetchRenderer == null) return;
                        PdfRenderer.Page pdfPage = prefetchRenderer.openPage(page);
                        try {
                            int baseWidth = Math.max(1, widthSnap - dpToPx(24));
                            float fitScale = baseWidth / (float) pdfPage.getWidth();
                            // Match the visible render: when not zoomed, also bound
                            // by available height so prefetched pages fit between the
                            // title bar and toolbar instead of being too tall.
                            if (zoomSnap <= 1.05f && heightSnap > 0) {
                                float baseHeight = Math.max(1, heightSnap - dpToPx(16));
                                fitScale = Math.min(fitScale, baseHeight / (float) pdfPage.getHeight());
                            }
                            float renderScale = Math.max(0.2f, fitScale * zoomSnap * PDF_SUPERSAMPLE);
                            int w = Math.max(1, Math.round(pdfPage.getWidth() * renderScale));
                            int h = Math.max(1, Math.round(pdfPage.getHeight() * renderScale));
                            long pixels = (long) w * (long) h;
                            long maxPixels = 22000000L;
                            if (pixels > maxPixels) {
                                float shrink = (float) Math.sqrt(maxPixels / (double) pixels);
                                w = Math.max(1, Math.round(w * shrink));
                                h = Math.max(1, Math.round(h * shrink));
                            }
                            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            pdfPage.close();
                        }
                    }
                    final Bitmap done = bmp;
                    handler.post(() -> {
                        if (activityDestroyed || done == null || done.isRecycled()
                                || destroyedOrZoomChanged(zoomSnap, widthSnap)
                                || singlePageCache.get(page) != null) {
                            if (done != null && !done.isRecycled()) done.recycle();
                            return;
                        }
                        singlePageCache.put(page, done);
                    });
                } catch (Exception ignored) {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                } finally {
                    singlePagePrefetchInFlight.remove(page);
                }
            });
        }
    }

    /** Page-turn requested by a horizontal swipe in the Matrix page view. */
    boolean onMatrixPageSwipe(int direction) {
        if (verticalPageSlideMode || pageCount <= 0) return false;
        int target = currentPage + (direction > 0 ? 1 : -1);
        if (target < 0 || target >= pageCount) return false;
        goToPage(target, direction);
        return true;
    }

    /** True if a tap at x/y (Matrix-view local coords) is a page-turn zone. */
    boolean isMatrixPageTurnZone(float x, float y) {
        if (verticalPageSlideMode || prefs == null || !prefs.getPdfTapPagingEnabled()
                || pdfPageMatrixView == null || pageCount <= 1) {
            return false;
        }
        int w = pdfPageMatrixView.getWidth();
        int h = pdfPageMatrixView.getHeight();
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x > w || y > h) return false;
        int action = TapZoneMath.actionForTap(
                x, y, w, h, true, true,
                prefs.getTapZoneMode(),
                prefs.getTapLeadingZonePercent(),
                prefs.getTapTrailingZonePercent());
        return action == TapZoneMath.ACTION_PREVIOUS || action == TapZoneMath.ACTION_NEXT;
    }

    /**
     * Tap inside the Matrix page view. Left/right tap zones turn the page (when tap
     * paging is enabled); anything else toggles the toolbar. x/y are local to the
     * Matrix view, which fills the viewport, so they map directly to tap zones.
     */
    void onMatrixTap(float x, float y) {
        if (!verticalPageSlideMode && prefs != null && prefs.getPdfTapPagingEnabled()
                && pdfPageMatrixView != null && pageCount > 1) {
            int w = pdfPageMatrixView.getWidth();
            int h = pdfPageMatrixView.getHeight();
            if (w > 0 && h > 0 && x >= 0 && y >= 0 && x <= w && y <= h) {
                int action = TapZoneMath.actionForTap(
                        x, y, w, h, true, true,
                        prefs.getTapZoneMode(),
                        prefs.getTapLeadingZonePercent(),
                        prefs.getTapTrailingZonePercent());
                if (action == TapZoneMath.ACTION_PREVIOUS) {
                    if (currentPage > 0) goToPage(currentPage - 1, -1);
                    return;
                }
                if (action == TapZoneMath.ACTION_NEXT) {
                    if (currentPage < pageCount - 1) goToPage(currentPage + 1, 1);
                    return;
                }
            }
        }
        togglePdfChrome();
    }

    /** Show the Matrix-based page view and hide the legacy scroll/image stack. */
    void showMatrixPageView() {
        if (pdfPageMatrixView == null) return;
        pdfPageMatrixView.setVisibility(View.VISIBLE);
        if (pdfHScroll != null) pdfHScroll.setVisibility(View.GONE);
    }

    /**
     * Render the visible region (page-normalized rect) of the current page at high
     * resolution and hand the crisp patch back to {@link PdfPageView}. Called by the
     * view when zoom/pan settles. Stage-1 sharpness path for the Matrix zoom rework.
     */
    void renderSharpenPatch(float nl, float nt, float nr, float nb, float displayScale) {
        if (verticalPageSlideMode || pdfRenderer == null || pdfPageMatrixView == null) return;
        final int pageIndex = currentPage;
        final int pageCountSnap = pageCount;
        if (pageIndex < 0 || pageIndex >= pageCountSnap) return;
        // Cap the patch resolution so we never allocate a huge bitmap.
        final long maxPatchPixels = 8_000_000L;
        executor.execute(() -> {
            Bitmap patch = null;
            try {
                synchronized (rendererLock) {
                    if (pdfRenderer == null || activityDestroyed) return;
                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    try {
                        int pw = page.getWidth();
                        int ph = page.getHeight();
                        float left = Math.max(0f, Math.min(1f, nl)) * pw;
                        float top = Math.max(0f, Math.min(1f, nt)) * ph;
                        float right = Math.max(0f, Math.min(1f, nr)) * pw;
                        float bottom = Math.max(0f, Math.min(1f, nb)) * ph;
                        float regionWpts = Math.max(1f, right - left);
                        float regionHpts = Math.max(1f, bottom - top);
                        // Pixels = region(points) * displayScale, capped.
                        float scale = Math.max(0.2f, displayScale);
                        int outW = Math.max(1, Math.round(regionWpts * scale));
                        int outH = Math.max(1, Math.round(regionHpts * scale));
                        long px = (long) outW * outH;
                        if (px > maxPatchPixels) {
                            float shrink = (float) Math.sqrt(maxPatchPixels / (double) px);
                            outW = Math.max(1, Math.round(outW * shrink));
                            outH = Math.max(1, Math.round(outH * shrink));
                        }
                        Bitmap bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        // Transform: map the region (in page points) onto the output
                        // bitmap. Scale region->output and translate region origin to 0.
                        Matrix m = new Matrix();
                        float sx = outW / regionWpts;
                        float sy = outH / regionHpts;
                        m.postTranslate(-left, -top);
                        m.postScale(sx, sy);
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        patch = bmp;
                    } finally {
                        page.close();
                    }
                }
            } catch (Exception ignored) {
                if (patch != null && !patch.isRecycled()) { patch.recycle(); patch = null; }
            }
            final Bitmap result = patch;
            final float fnl = nl, fnt = nt, fnr = nr, fnb = nb;
            handler.post(() -> {
                if (activityDestroyed || verticalPageSlideMode || result == null || result.isRecycled()
                        || pdfPageMatrixView == null || currentPage != pageIndex) {
                    if (result != null && !result.isRecycled()) result.recycle();
                    return;
                }
                pdfPageMatrixView.setSharpenPatch(result, fnl, fnt, fnr, fnb);
            });
        });
    }

    private boolean destroyedOrZoomChanged(float zoomSnap, int widthSnap) {
        return activityDestroyed || verticalPageSlideMode
                || Math.abs(zoom - zoomSnap) > 0.001f
                || (pdfViewport != null && pdfViewport.getWidth() != widthSnap);
    }

    private void renderCurrentPage(boolean showLoadingIndicator) {
        stopPdfViewportFling();
        if (verticalPageSlideMode) {
            renderContinuousPages();
            return;
        }
        if (pdfRenderer == null || pageCount <= 0 || pdfViewport == null) return;
        final int pageToRender = currentPage;
        final float zoomToRender = zoom;

        int viewportWidthNow = pdfViewport.getWidth()
                - pdfViewport.getPaddingLeft() - pdfViewport.getPaddingRight();
        int viewportHeightNow = pdfViewport.getHeight()
                - pdfViewport.getPaddingTop() - pdfViewport.getPaddingBottom();
        // Invalidate the prerender cache if zoom or width changed; otherwise try an
        // instant cache hit before kicking off a fresh render.
        invalidateSinglePageCacheIfNeeded(zoomToRender, viewportWidthNow, viewportHeightNow);
        if (viewportWidthNow > 0) {
            Bitmap cached = singlePageCache.get(pageToRender);
            if (cached != null && !cached.isRecycled()) {
                ++renderGeneration; // cancel any in-flight render for the old page
                progressBar.setVisibility(View.GONE);
                showSinglePageBitmap(cached, zoomToRender);
                updatePageStatus();
                scheduleAdjacentSinglePagePrefetch(pageToRender, zoomToRender, viewportWidthNow, viewportHeightNow);
                return;
            }
        }

        final int generation = ++renderGeneration;

        if (showLoadingIndicator) {
            updateLoadingIndicatorTheme();
            progressBar.setVisibility(View.VISIBLE);
        } else {
            progressBar.setVisibility(View.GONE);
        }
        updatePageStatus();

        int viewportWidth = pdfViewport.getWidth()
                - pdfViewport.getPaddingLeft() - pdfViewport.getPaddingRight();
        final int viewportHeight = pdfViewport.getHeight()
                - pdfViewport.getPaddingTop() - pdfViewport.getPaddingBottom();
        if (viewportWidth <= 0) {
            pdfViewport.post(() -> renderCurrentPage(showLoadingIndicator));
            return;
        }

        executor.execute(() -> {
            Bitmap bitmap = null;
            final int[] intendedDisplayWidthHolder = {0};
            try {
                // Abandon this render if the user already moved past this page.
                // Without this, rapid taps queue a full render per page and the
                // single render thread works through all of them, so the page you
                // actually stopped on appears only after the backlog clears.
                if (activityDestroyed || generation != renderGeneration) return;
                synchronized (rendererLock) {
                    if (pdfRenderer == null || generation != renderGeneration) return;
                    PdfRenderer.Page page = pdfRenderer.openPage(pageToRender);
                    try {
                        lastRenderedPageWidthPts = Math.max(1, page.getWidth());
                        lastRenderedPageHeightPts = Math.max(1, page.getHeight());
                        int baseWidth = Math.max(1, viewportWidth - dpToPx(24));
                        float fitScale = baseWidth / (float) page.getWidth();
                        // When not zoomed, also bound by the available height so the
                        // entire page fits between the title bar and the bottom
                        // toolbar instead of overflowing under the toolbar (this was
                        // visible on tablets, whose aspect ratio differs from phones).
                        if (zoomToRender <= 1.05f && viewportHeight > 0) {
                            float baseHeight = Math.max(1, viewportHeight - dpToPx(16));
                            float heightFit = baseHeight / (float) page.getHeight();
                            fitScale = Math.min(fitScale, heightFit);
                        }
                        // Supersample so text is rendered above 1:1 screen density
                        // and downscaled by the ImageView, which sharpens edges.
                        // The max-pixel cap below still bounds memory for big pages.
                        float renderScale = Math.max(0.2f, fitScale * zoomToRender * PDF_SUPERSAMPLE);
                        // The on-screen display width is fit x zoom, INDEPENDENT of
                        // supersample and of the maxPixels cap. Capping the bitmap
                        // only reduces resolution (sharpness); it must not shrink the
                        // displayed page, otherwise high zoom on large (tablet)
                        // viewports never actually grows the page.
                        intendedDisplayWidthHolder[0] = Math.max(1,
                                Math.round(page.getWidth() * fitScale * zoomToRender));
                        int width = Math.max(1, Math.round(page.getWidth() * renderScale));
                        int height = Math.max(1, Math.round(page.getHeight() * renderScale));
                        long pixels = (long) width * (long) height;
                        // Zoomed pages would otherwise produce very large bitmaps
                        // (e.g. ~70MB) that risk OOM. The screen can't show that
                        // much detail at once anyway, so cap zoomed renders lower.
                        long maxPixels = zoomToRender > 1.05f ? 16000000L : 22000000L;
                        if (pixels > maxPixels) {
                            float shrink = (float) Math.sqrt(maxPixels / (double) pixels);
                            width = Math.max(1, Math.round(width * shrink));
                            height = Math.max(1, Math.round(height * shrink));
                        }

                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        page.close();
                    }
                }

                Bitmap finalBitmap = bitmap;
                handler.post(() -> {
                    if (activityDestroyed || generation != renderGeneration) {
                        if (finalBitmap != null && !finalBitmap.isRecycled()
                                && !isBitmapInSinglePageCache(finalBitmap)) {
                            finalBitmap.recycle();
                        }
                        return;
                    }
                    // Cache this freshly rendered page for instant re-display.
                    if (finalBitmap != null && !finalBitmap.isRecycled() && viewportWidth > 0) {
                        singlePageCacheZoom = zoomToRender;
                        singlePageCacheWidth = viewportWidth;
                        singlePageCacheHeight = viewportHeight;
                        singlePageCache.put(pageToRender, finalBitmap);
                    }
                    Bitmap old = currentBitmap;
                    currentBitmap = finalBitmap;
                    renderedZoom = zoomToRender;
                    pageImage.animate().cancel();
                    // Apply bitmap + new width BEFORE resetting scale, so the visual
                    // size stays continuous coming out of the zoom animation (see
                    // showSinglePageBitmap for the same ordering and rationale).
                    if (pendingZoomFocus) {
                        deferZoomBitmapReveal = true;
                        pageImage.setAlpha(0.0f);
                    }
                    pageImage.setImageBitmap(finalBitmap);
                    // The bitmap is supersampled (≈PDF_SUPERSAMPLE× the fit size).
                    // Pin only the display WIDTH to the fit size and let
                    // adjustViewBounds derive the height from the bitmap's aspect
                    // ratio. This keeps the extra pixels as detail (sharper text)
                    // without ever stretching the page — including when the
                    // viewport gets taller after the toolbar is hidden.
                    if (finalBitmap != null) {
                        android.view.ViewGroup.LayoutParams ip = pageImage.getLayoutParams();
                        int displayWidth = intendedDisplayWidthHolder[0] > 0
                                ? intendedDisplayWidthHolder[0]
                                : Math.max(1, Math.round(finalBitmap.getWidth() / PDF_SUPERSAMPLE));
                        if (pendingZoomFocus) {
                            pendingZoomRevealWidth = displayWidth;
                        }
                        if (ip != null) {
                            // Display at the intended fit x zoom width. Fall back to
                            // the supersample-derived width if the intended value is
                            // missing for any reason.
                            ip.width = displayWidth;
                            ip.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                            pageImage.setLayoutParams(ip);
                        }
                        pageImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                        pageImage.setScaleX(1.0f);
                        pageImage.setScaleY(1.0f);
                        applySinglePageVerticalOffset();
                    }
                    if (pendingPageSlideDirection != 0) {
                        positionPdfPageAfterPageTurn();
                    } else {
                        restoreDoubleTapZoomFocusAfterRender();
                    }
                    // Stage-1 Matrix zoom: when the Matrix view is active, hand it the
                    // fit bitmap and let it own zoom/pan. The bitmap is rendered at
                    // fit (zoom≈1); the view scales it and asks us for sharp patches.
                    if (pdfPageMatrixView != null && finalBitmap != null && !verticalPageSlideMode) {
                        boolean newPage = (old != finalBitmap);
                        pdfPageMatrixView.setFitBitmap(finalBitmap, PDF_SUPERSAMPLE,
                                lastRenderedPageWidthPts, lastRenderedPageHeightPts, newPage);
                        showMatrixPageView();
                    }
                    runPageSlideInAnimation();
                    // Don't recycle the previous page if the cache still owns it.
                    if (old != null && old != finalBitmap && !old.isRecycled()
                            && !isBitmapInSinglePageCache(old)) {
                        old.recycle();
                    }
                    progressBar.setVisibility(View.GONE);
                    restorePendingPdfContentAnchorIfNeeded();
                    updatePageStatus();
                    scheduleAdjacentSinglePagePrefetch(pageToRender, zoomToRender, viewportWidth, viewportHeight);
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                handler.post(() -> {
                    if (!activityDestroyed) showLoadError(e);
                });
            }
        });
    }

    private void restoreDoubleTapZoomFocusAfterRender() {
        if (!pendingZoomFocus || pageImage == null || pdfViewport == null) return;

        final float xRatio = pendingZoomFocusXRatio;
        final float yRatio = pendingZoomFocusYRatio;
        final float viewportX = pendingZoomViewportX;
        final float viewportY = pendingZoomViewportY;
        pendingZoomFocus = false;

        // Scroll to the zoom focus BEFORE the new (enlarged) bitmap is drawn.
        final View target = pageImage;
        final int[] revealAttempts = {0};
        target.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        if (pageImage == null || pdfViewport == null) {
                            target.getViewTreeObserver().removeOnPreDrawListener(this);
                            deferZoomBitmapReveal = false;
                            pendingZoomRevealWidth = -1;
                            return true;
                        }
                        revealAttempts[0]++;
                        int targetX = Math.round(pageImage.getWidth() * xRatio - viewportX);
                        int targetY = Math.round(pageImage.getHeight() * yRatio - viewportY);
                        int beforeX = pdfHScroll != null ? pdfHScroll.getScrollX() : -1;
                        int beforeY = pdfVScroll != null ? pdfVScroll.getScrollY() : -1;
                        int appliedX = pdfHScroll != null ? pdfHScroll.getScrollX() : -1;
                        int appliedY = pdfVScroll != null ? pdfVScroll.getScrollY() : -1;
                        if (pdfHScroll != null) {
                            int maxX = 0;
                            if (pdfHScroll.getChildCount() > 0) {
                                maxX = Math.max(0, pdfHScroll.getChildAt(0).getWidth() - pdfHScroll.getWidth());
                            }
                            appliedX = Math.max(0, Math.min(maxX, targetX));
                            pdfHScroll.scrollTo(appliedX, pdfHScroll.getScrollY());
                        }
                        if (pdfVScroll != null) {
                            int maxY = 0;
                            if (pdfVScroll.getChildCount() > 0) {
                                maxY = Math.max(0, pdfVScroll.getChildAt(0).getHeight() - pdfVScroll.getHeight());
                            }
                            appliedY = Math.max(0, Math.min(maxY, targetY));
                            pdfVScroll.scrollTo(pdfVScroll.getScrollX(), appliedY);
                        }
                        if (appliedX != beforeX || appliedY != beforeY) {
                            pageImage.postInvalidateOnAnimation();
                            return false;
                        }
                        // Layout-settled guard: the zoom-out case needs no scroll
                        // (target clamps to 0), so it would reveal on the very first
                        // preDraw — but the ImageView may still be laid out at the
                        // old (larger) size for one frame, which shows as a flicker
                        // when the toolbar is off. Wait until the view has actually
                        // taken the new width before revealing.
                        if (pendingZoomRevealWidth > 0
                                && pageImage.getWidth() != pendingZoomRevealWidth
                                && revealAttempts[0] < 8) {
                            pageImage.postInvalidateOnAnimation();
                            return false;
                        }
                        target.getViewTreeObserver().removeOnPreDrawListener(this);
                        pendingZoomRevealWidth = -1;
                        if (deferZoomBitmapReveal) {
                            deferZoomBitmapReveal = false;
                            pageImage.setAlpha(1.0f);
                        }
                        return true;
                    }
                });
    }

    private void positionPdfPageAfterPageTurn() {
        if (pageImage == null) return;
        pageImage.post(() -> {
            if (pageImage == null) return;

            if (renderedZoom <= 1.08f) {
                // Keep the current scroll offset on page turns instead of forcing
                // the next page back to the top-left. Existing ScrollView bounds
                // will clamp the offset if the newly rendered page is smaller.
                return;
            }
            if (pdfHScroll != null) {
                int maxX = 0;
                if (pdfHScroll.getChildCount() > 0) {
                    maxX = Math.max(0, pdfHScroll.getChildAt(0).getWidth() - pdfHScroll.getWidth());
                }
                pdfHScroll.scrollTo(maxX / 2, pdfHScroll.getScrollY());
            }
            if (pdfVScroll != null) {
                int maxY = 0;
                if (pdfVScroll.getChildCount() > 0) {
                    maxY = Math.max(0, pdfVScroll.getChildAt(0).getHeight() - pdfVScroll.getHeight());
                }
                pdfVScroll.scrollTo(pdfVScroll.getScrollX(), maxY / 2);
            }
        });
    }

    private void runPageSlideInAnimation() {
        pendingPageSlideDirection = 0;
        if (pageImage == null) return;
        pageImage.animate().cancel();
        pageImage.setTranslationX(0f);
        pageImage.setTranslationY(0f);
        if (!deferZoomBitmapReveal) {
            pageImage.setAlpha(1.0f);
        }
    }

    void updatePageStatus() {
        if (pageStatus == null && pdfTopPageStatus == null) return;
        if (pageCount <= 0) {
            if (pageStatus != null) pageStatus.setText("");
            if (pdfTopPageStatus != null) pdfTopPageStatus.setText("");
            return;
        }
        String statusText = String.format(Locale.getDefault(), "%d / %d", currentPage + 1, pageCount);
        if (pageStatus != null) pageStatus.setText(statusText);
        if (pdfTopPageStatus != null) pdfTopPageStatus.setText(statusText);
        if (pdfPageSeekBar != null && !pdfPageSeekBarUserTracking) {
            int max = Math.max(0, pageCount - 1);
            if (pdfPageSeekBar.getMax() != max) pdfPageSeekBar.setMax(max);
            int progress = Math.max(0, Math.min(max, currentPage));
            if (pdfPageSeekBar.getProgress() != progress) {
                pdfPageSeekBar.setProgress(progress);
            }
            pdfPageSeekBar.setEnabled(pageCount > 1);
        }
        updatePdfSlideModeButton();
        prevButton.setEnabled(currentPage > 0);
        nextButton.setEnabled(currentPage < pageCount - 1);
    }

    private PdfBookmarkDialogController pdfBookmarkDialogs;

    private PdfBookmarkDialogController pdfBookmarkDialogs() {
        if (pdfBookmarkDialogs == null) {
            pdfBookmarkDialogs = new PdfBookmarkDialogController(this);
        }
        return pdfBookmarkDialogs;
    }

    private void showBookmarksDialog() {
        pdfBookmarkDialogs().showBookmarksDialog();
    }


    String currentPdfContentAnchorJson() {
        if (verticalPageSlideMode) {
            return currentContinuousPdfContentAnchorJson();
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("kind", "PDF_PAGE_COORD_v2");
            obj.put("mode", "single");
            obj.put("page", currentPage);
            obj.put("pageNumber", currentPage + 1);
            float xRatio = 0f;
            float yRatio = 0f;
            if (pageImage != null) {
                int imageWidth = Math.max(1, pageImage.getWidth());
                int imageHeight = Math.max(1, pageImage.getHeight());
                int visibleX = pdfHScroll != null ? Math.max(0, pdfHScroll.getScrollX()) : 0;
                int visibleY = pdfVScroll != null ? Math.max(0, pdfVScroll.getScrollY()) : 0;
                // Store a coordinate in the rendered PDF page, not a viewport percent.
                // Do not add arbitrary offsets here: bookmarks should restore to the
                // same page-internal point at the top/left edge on devices with
                // different screen sizes.
                xRatio = Math.max(0f, Math.min(1f, visibleX / (float) imageWidth));
                yRatio = Math.max(0f, Math.min(1f, visibleY / (float) imageHeight));
            }
            obj.put("xRatio", xRatio);
            obj.put("yRatio", yRatio);
            obj.put("zoom", zoom);
            return obj.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String currentContinuousPdfContentAnchorJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("kind", "PDF_PAGE_COORD_v2");
            obj.put("mode", "continuous");
            int anchorPage = clampPage(currentPage);
            float xRatio = 0f;
            float yRatio = 0f;
            if (pdfContinuousList != null) {
                RecyclerView.LayoutManager manager = pdfContinuousList.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    LinearLayoutManager lm = (LinearLayoutManager) manager;
                    int first = lm.findFirstVisibleItemPosition();
                    int last = lm.findLastVisibleItemPosition();
                    if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
                        int bestPage = first;
                        int bestTopDistance = Integer.MAX_VALUE;
                        for (int i = first; i <= last; i++) {
                            View child = lm.findViewByPosition(i);
                            if (child == null || child.getBottom() <= 0 || child.getTop() >= pdfContinuousList.getHeight()) continue;
                            int distance = Math.abs(child.getTop());
                            if (distance < bestTopDistance) {
                                bestTopDistance = distance;
                                bestPage = i;
                            }
                        }
                        View child = lm.findViewByPosition(bestPage);
                        if (child != null) {
                            anchorPage = clampPage(bestPage);
                            int pageHeight = Math.max(1, child.getHeight());
                            int visibleY = Math.max(0, -child.getTop());
                            yRatio = Math.max(0f, Math.min(1f, visibleY / (float) pageHeight));
                        }
                    }
                }
            }
            if (pdfContinuousAdapter != null) {
                int pageWidth = Math.max(1, pdfContinuousAdapter.getRenderedWidthForPage(anchorPage));
                int panX = Math.max(0, pdfContinuousAdapter.getPageHorizontalPanOffset(anchorPage));
                xRatio = Math.max(0f, Math.min(1f, panX / (float) pageWidth));
            }
            obj.put("page", anchorPage);
            obj.put("pageNumber", anchorPage + 1);
            obj.put("xRatio", xRatio);
            obj.put("yRatio", yRatio);
            obj.put("zoom", zoom);
            return obj.toString();
        } catch (Exception e) {
            return "";
        }
    }

    int pdfAnchorPageFromJson(String anchorJson, int fallbackPage) {
        if (anchorJson == null || anchorJson.trim().isEmpty()) return clampPage(fallbackPage);
        try {
            JSONObject obj = new JSONObject(anchorJson);
            return clampPage(obj.optInt("page", obj.optInt("pageNumber", fallbackPage + 1) - 1));
        } catch (Exception ignored) {
            return clampPage(fallbackPage);
        }
    }

    void restorePendingPdfContentAnchorIfNeeded() {
        if (pendingPdfContentAnchorJson == null || pendingPdfContentAnchorJson.trim().isEmpty()) return;
        final String anchor = pendingPdfContentAnchorJson;
        pendingPdfContentAnchorJson = "";
        restorePdfContentAnchor(anchor);
    }

    void restorePdfContentAnchor(String anchorJson) {
        if (anchorJson == null || anchorJson.trim().isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            int page = clampPage(obj.optInt("page", obj.optInt("pageNumber", currentPage + 1) - 1));
            final float xRatio = (float) Math.max(0.0d, Math.min(1.0d, obj.optDouble("xRatio", 0.0d)));
            final float yRatio = (float) Math.max(0.0d, Math.min(1.0d, obj.optDouble("yRatio", 0.0d)));
            if (verticalPageSlideMode) {
                restorePdfContinuousContentAnchor(page, xRatio, yRatio);
                return;
            }
            if (pageImage == null) {
                pendingPdfContentAnchorJson = anchorJson;
                return;
            }
            if (page != currentPage) {
                pendingPdfContentAnchorJson = anchorJson;
                goToPage(page, Integer.compare(page, currentPage));
                return;
            }
            pageImage.post(() -> {
                if (pageImage == null) return;
                if (pdfHScroll != null) {
                    int maxX = 0;
                    if (pdfHScroll.getChildCount() > 0) maxX = Math.max(0, pdfHScroll.getChildAt(0).getWidth() - pdfHScroll.getWidth());
                    int targetX = Math.max(0, Math.min(maxX, Math.round(pageImage.getWidth() * xRatio)));
                    pdfHScroll.scrollTo(targetX, pdfHScroll.getScrollY());
                }
                if (pdfVScroll != null) {
                    int maxY = 0;
                    if (pdfVScroll.getChildCount() > 0) maxY = Math.max(0, pdfVScroll.getChildAt(0).getHeight() - pdfVScroll.getHeight());
                    int targetY = Math.max(0, Math.min(maxY, Math.round(pageImage.getHeight() * yRatio)));
                    pdfVScroll.scrollTo(pdfVScroll.getScrollX(), targetY);
                }
            });
        } catch (Exception ignored) {}
    }

    private void restorePdfContinuousContentAnchor(int page, float xRatio, float yRatio) {
        if (pageCount <= 0) return;
        final int targetPage = clampPage(page);
        currentPage = targetPage;
        updatePageStatus();
        if (!ensureContinuousPagesConfigured() || pdfContinuousList == null) {
            pendingPdfContentAnchorJson = makePdfAnchorJsonForPending(targetPage, xRatio, yRatio, true);
            return;
        }
        final float clampedX = Math.max(0f, Math.min(1f, xRatio));
        final float clampedY = Math.max(0f, Math.min(1f, yRatio));
        suppressContinuousScrollSync = true;
        pdfContinuousList.stopScroll();
        pdfContinuousList.post(() -> {
            if (activityDestroyed || pdfContinuousList == null) return;
            int pageHeight = pdfContinuousAdapter != null
                    ? Math.max(1, pdfContinuousAdapter.getRenderedHeightForPage(targetPage))
                    : Math.max(1, pdfContinuousList.getHeight());
            int offsetY = Math.round(pageHeight * clampedY);
            RecyclerView.LayoutManager manager = pdfContinuousList.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(targetPage, -offsetY);
            } else {
                pdfContinuousList.scrollToPosition(targetPage);
            }
            pdfContinuousList.post(() -> {
                if (activityDestroyed || pdfContinuousList == null) return;
                if (pdfContinuousAdapter != null) {
                    int pageWidth = Math.max(1, pdfContinuousAdapter.getRenderedWidthForPage(targetPage));
                    pdfContinuousAdapter.setPageHorizontalPanOffset(targetPage, Math.round(pageWidth * clampedX));
                }
                suppressContinuousScrollSync = false;
                currentPage = targetPage;
                updatePageStatus();
                prefetchContinuousPagesAround(targetPage);
            });
        });
    }

    private String makePdfAnchorJsonForPending(int page, float xRatio, float yRatio, boolean continuous) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("kind", "PDF_PAGE_COORD_v2");
            obj.put("mode", continuous ? "continuous" : "single");
            obj.put("page", clampPage(page));
            obj.put("pageNumber", clampPage(page) + 1);
            obj.put("xRatio", Math.max(0f, Math.min(1f, xRatio)));
            obj.put("yRatio", Math.max(0f, Math.min(1f, yRatio)));
            obj.put("zoom", zoom);
            return obj.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    void saveReadingState() {
        if (filePath == null || !prefs.getAutoSavePosition()) return;
        String anchor = currentPdfContentAnchorJson();
        int anchorPage = pdfAnchorPageFromJson(anchor, currentPage);
        ReaderState state = new ReaderState(filePath);
        state.setCharPosition(anchorPage);
        state.setScrollY(0);
        state.setPageNumber(anchorPage + 1);
        state.setTotalPages(pageCount);
        state.setFileLength(fileSizeBytes(filePath));
        state.setContentAnchorJson(anchor);
        state.setEncoding(anchor != null && !anchor.isEmpty() ? "PDF_PAGE_COORD_v2" : "PDF_PAGE");
        bookmarkManager.saveReadingState(state);
    }

    private long fileSizeBytes(String path) {
        if (path == null || path.trim().isEmpty() || path.startsWith("content://")) return 0L;
        try {
            File file = new File(path);
            return file.exists() && file.isFile() ? file.length() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    void cancelPdfBackgroundMemoryTrim() {
        handler.removeCallbacks(backgroundPdfMemoryTrimRunnable);
    }

    void schedulePdfBackgroundMemoryTrim() {
        if (activityDestroyed || backgroundPdfBitmapsReleased || pdfRenderer == null) return;
        handler.removeCallbacks(backgroundPdfMemoryTrimRunnable);
        handler.postDelayed(backgroundPdfMemoryTrimRunnable, BACKGROUND_MEMORY_TRIM_DELAY_MS);
    }

    private void trimPdfBitmapsForBackground(boolean force) {
        if (activityDestroyed || backgroundPdfBitmapsReleased || pdfRenderer == null) return;
        if (!force && !isFinishing() && !isChangingConfigurations() && hasWindowFocus()) return;
        saveReadingState();
        if (pdfContinuousList != null) pdfContinuousList.stopScroll();
        releaseSinglePageBitmap();
        if (pdfContinuousAdapter != null) {
            pdfContinuousAdapter.clearBitmaps();
        }
        backgroundPdfBitmapsReleased = true;
    }

    void restorePdfBitmapsAfterBackgroundTrimIfNeeded() {
        if (!backgroundPdfBitmapsReleased) return;
        backgroundPdfBitmapsReleased = false;
        if (pdfRenderer == null || pageCount <= 0) return;
        applyPdfDisplayMode();
    }

    private void closeRenderer() {
        renderGeneration++;
        resetContinuousPageViews(false);
        synchronized (rendererLock) {
            Bitmap shown = currentBitmap;
            currentBitmap = null;
            singlePageCache.evictAll();
            singlePagePrefetchInFlight.clear();
            singlePageCacheWidth = -1;
            singlePageCacheHeight = -1;
            if (shown != null && !shown.isRecycled()) {
                shown.recycle();
            }
            if (pageImage != null) {
                pageImage.setImageDrawable(null);
            }
            if (pdfRenderer != null) {
                pdfRenderer.close();
                pdfRenderer = null;
            }
            if (parcelFileDescriptor != null) {
                try { parcelFileDescriptor.close(); } catch (Exception ignored) {}
                parcelFileDescriptor = null;
            }
        }
        // Close the prefetch renderer under its own lock so an in-flight prefetch
        // finishes (or sees null) before the instance is torn down.
        synchronized (prefetchRendererLock) {
            if (prefetchRenderer != null) {
                prefetchRenderer.close();
                prefetchRenderer = null;
            }
            if (prefetchParcelFileDescriptor != null) {
                try { prefetchParcelFileDescriptor.close(); } catch (Exception ignored) {}
                prefetchParcelFileDescriptor = null;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveReadingState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        schedulePdfBackgroundMemoryTrim();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            schedulePdfBackgroundMemoryTrim();
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // TRIM_MEMORY_RUNNING_LOW / RUNNING_CRITICAL fire while the app is still
            // foregrounded; only trim once it is actually backgrounded so we never
            // blank the PDF page the user is currently viewing.
            cancelPdfBackgroundMemoryTrim();
            trimPdfBitmapsForBackground(true);
        }
    }


    int calculatePdfContinuousCacheKb() {
        long maxMemoryKb = Runtime.getRuntime().maxMemory() / 1024L;
        long targetKb = Math.max(12L * 1024L, Math.min(96L * 1024L, maxMemoryKb / 8L));
        return (int) Math.max(8L * 1024L, targetKb);
    }

    long getContinuousPageMaxPixels() {
        // Headroom for supersampled pages; the LRU cache still bounds total memory.
        // When zoomed, several visible pages must re-render at once on the single
        // render thread, so use a smaller cap to keep each one fast and the zoom
        // responsive. The screen can't show that much detail per page anyway.
        return zoomActive() ? 12000000L : 18000000L;
    }

    private boolean zoomActive() {
        return zoom > 1.05f;
    }

    @Override
    protected void onDestroy() {
        stopPdfViewportFling();
        cancelPdfBackgroundMemoryTrim();
        ViewerRegistry.unregister(this);
        activityDestroyed = true;
        ++renderGeneration;
        handler.removeCallbacksAndMessages(null);
        saveReadingState();
        if (pageImage != null) {
            pageImage.animate().cancel();
            pageImage.setImageDrawable(null);
        }
        if (pdfContinuousList != null) {
            pdfContinuousList.stopScroll();
            if (pendingContinuousScrollRunnable != null) {
                pdfContinuousList.removeCallbacks(pendingContinuousScrollRunnable);
            }
            if (pendingContinuousSettleRunnable != null) {
                pdfContinuousList.removeCallbacks(pendingContinuousSettleRunnable);
            }
            if (continuousScrollListener != null) {
                pdfContinuousList.removeOnScrollListener(continuousScrollListener);
            }
            pdfContinuousList.setAdapter(null);
        }
        pendingContinuousScrollRunnable = null;
        pendingContinuousSettleRunnable = null;
        if (pdfContinuousAdapter != null) {
            pdfContinuousAdapter.release();
        }
        continuousScrollListener = null;
        closeRenderer();
        if (pendingSinglePagePrefetch != null) {
            handler.removeCallbacks(pendingSinglePagePrefetch);
            pendingSinglePagePrefetch = null;
        }
        if (pdfToolbarController != null) {
            pdfToolbarController.release();
            pdfToolbarController = null;
        }
        executor.shutdownNow();
        prefetchExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
