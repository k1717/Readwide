package com.textview.reader;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
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
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.adapter.BookmarkFolderAdapter;
import com.textview.reader.model.Bookmark;
import com.textview.reader.model.ReaderState;
import com.textview.reader.model.Theme;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;
import com.textview.reader.util.ThemeManager;

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

    // Match toolbar-triggered PDF popups to the Go to Page bottom offset.
    static final int PDF_TOOLBAR_POPUP_Y_DP = 74;

    // In horizontal PDF swipe mode, a zoomed page needs to pan around the enlarged
    // bitmap before the next edge-start swipe can turn the page. Accelerate only
    // zoomed in-page panning so original-size movement and page-turn thresholds
    // stay unchanged.
    private static final float PDF_ZOOMED_HORIZONTAL_PAN_ACCELERATION = 1.62f;
    private static final float PDF_ZOOMED_VERTICAL_PAN_ACCELERATION = 1.45f;

    View root;
    View pdfAppBar;
    View pdfBottomBar;
    boolean pdfChromeVisible = true;
    ImageView pageImage;
    RecyclerView pdfContinuousList;
    PdfContinuousPageAdapter pdfContinuousAdapter;
    private RecyclerView.OnScrollListener continuousScrollListener;
    private boolean suppressContinuousScrollSync = false;
    ProgressBar progressBar;
    TextView pageStatus;
    TextView prevButton;
    TextView nextButton;
    TextView slideModeButton;
    TextView pageButton;
    TextView bookmarkButton;
    TextView zoomMoreButton;
    View pdfViewport;
    HorizontalScrollView pdfHScroll;
    ScrollView pdfVScroll;
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
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

    private boolean pendingZoomFocus = false;
    private float pendingZoomFocusXRatio = 0.5f;
    private float pendingZoomFocusYRatio = 0.5f;
    private float pendingZoomViewportX = 0.5f;
    private float pendingZoomViewportY = 0.5f;
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
    volatile boolean activityDestroyed = false;
    final Object rendererLock = new Object();

    private ParcelFileDescriptor parcelFileDescriptor;
    PdfRenderer pdfRenderer;
    private Bitmap currentBitmap;
    private File localFile;
    String filePath;
    String fileName;
    int pageCount = 0;
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
        pdfContinuousList.setBackgroundColor(readerPanel);
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
        resetViewportGesture();

        if (pdfContinuousList != null) {
            pdfContinuousList.stopScroll();
            pdfContinuousList.setVisibility(continuous ? View.VISIBLE : View.GONE);
        }
        if (pdfHScroll != null) {
            pdfHScroll.setVisibility(continuous ? View.GONE : View.VISIBLE);
        }

        if (continuous) {
            releaseSinglePageBitmap();
            renderContinuousPages();
        } else {
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
        scrollContinuousListToCurrentPage(false);
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

        int viewportWidth = pdfViewport.getWidth();
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

        pdfContinuousList.post(() -> {
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

            pdfContinuousList.postDelayed(() -> {
                suppressContinuousScrollSync = false;
                updatePageStatus();
            }, smooth ? 360L : 220L);
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handlePdfViewportGesture(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    private boolean handlePdfViewportGesture(@NonNull MotionEvent event) {
        if (pdfViewport == null || pageCount <= 0) return false;
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
        if (handlePdfTapGesture(event, insideViewport)) {
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
        if (handlePdfTapGesture(event, insideViewport)) {
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
        gestureStartedWithHorizontalScrollable = false;
        gestureStartedWithVerticalScrollable = false;
        gestureStartedAtLeftEdge = true;
        gestureStartedAtRightEdge = true;
        gestureStartedAtTopEdge = true;
        gestureStartedAtBottomEdge = true;
    }

    private boolean handlePdfTapGesture(@NonNull MotionEvent event, boolean insideViewport) {
        if (!insideViewport || gestureDetector == null) return false;
        boolean handled = gestureDetector.onTouchEvent(event);
        // Keep ACTION_DOWN available for the pan/swipe tracker. GestureDetector may
        // return true on down once onDown() is enabled for reliable single-tap
        // confirmation, but consuming down here breaks page swipes and zoom panning.
        return handled && event.getActionMasked() != MotionEvent.ACTION_DOWN;
    }

    private void togglePdfChrome() {
        setPdfChromeVisible(!pdfChromeVisible);
    }

    private void setPdfChromeVisible(boolean visible) {
        pdfChromeVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (pdfAppBar != null && pdfAppBar.getVisibility() != visibility) {
            pdfAppBar.setVisibility(visibility);
        }
        if (pdfBottomBar != null && pdfBottomBar.getVisibility() != visibility) {
            pdfBottomBar.setVisibility(visibility);
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        if (pdfViewport != null) pdfViewport.requestLayout();
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
            int maxX = Math.max(0, pdfHScroll.getChildAt(0).getWidth() - pdfHScroll.getWidth());
            int nextX = Math.max(0, Math.min(maxX, pdfHScroll.getScrollX() + Math.round(deltaX)));
            pdfHScroll.scrollTo(nextX, pdfHScroll.getScrollY());
        }
        if (pdfVScroll != null && isPdfVerticallyScrollable()) {
            int maxY = Math.max(0, pdfVScroll.getChildAt(0).getHeight() - pdfVScroll.getHeight());
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
        int bg = readerBg;
        int toolbarBg = readerToolbarBg;
        getWindow().setStatusBarColor(toolbarBg);
        getWindow().setNavigationBarColor(bg);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(bg);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkColor(toolbarBg));
            controller.setAppearanceLightNavigationBars(!isDarkColor(bg));
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (!isDarkColor(bg)) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    boolean isDarkColor(int color) {
        return UiColorUtils.isDarkColor(color);
    }

    int blendColors(int base, int overlay, float overlayAlpha) {
        return UiColorUtils.blendColors(base, overlay, overlayAlpha);
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
            toolbar.setBackgroundColor(readerToolbarBg);
            toolbar.setTitleTextColor(readerFg);
            toolbar.setNavigationIcon(tintedBackIcon());
        }
        if (pdfBottomBar == null) pdfBottomBar = findViewById(R.id.pdf_bottom_bar);
        if (pdfBottomBar != null) pdfBottomBar.setBackgroundColor(readerPanel);
        if (pdfViewport != null) pdfViewport.setBackgroundColor(readerPanel);
        if (pageStatus != null) pageStatus.setTextColor(readerFg);
        updateLoadingIndicatorTheme();

        TextView[] buttons = {prevButton, nextButton, slideModeButton, pageButton, bookmarkButton, zoomMoreButton};
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
        if (zoomMoreButton != null) zoomMoreButton.setOnClickListener(v -> showMoreDialog());
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
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
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
                togglePdfChrome();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleDoubleTapZoom(e);
                return true;
            }
        });
    }

    private void setZoomSmooth(float targetZoom) {
        setZoomSmooth(targetZoom, null);
    }

    private void setZoomSmooth(float targetZoom, MotionEvent focusEvent) {
        zoom = Math.max(0.55f, Math.min(4.5f, targetZoom));
        if (verticalPageSlideMode) {
            if (pdfContinuousAdapter != null) pdfContinuousAdapter.clearBitmaps();
            renderContinuousPages();
            return;
        }
        if (focusEvent != null) {
            prepareDoubleTapZoomFocus(focusEvent);
            applyPageImagePivotFromRaw(focusEvent.getRawX(), focusEvent.getRawY());
        } else {
            prepareZoomFocusFromViewportCenter();
            applyPageImagePivotFromPreparedFocus();
        }
        if (pageImage != null) {
            float displayScale = Math.max(0.55f, Math.min(4.5f, zoom / Math.max(0.1f, renderedZoom)));
            pageImage.animate().cancel();
            pageImage.animate()
                    .scaleX(displayScale)
                    .scaleY(displayScale)
                    .setDuration(90)
                    .withEndAction(() -> renderCurrentPage(false))
                    .start();
        } else {
            renderCurrentPage(false);
        }
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
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track));
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

            int restored = 0;
            if (jumpPage >= 0) {
                restored = jumpPage;
            } else if (prefs.getAutoSavePosition()) {
                ReaderState state = bookmarkManager.getReadingState(filePath);
                if (state != null) restored = state.getCharPosition();
            }
            currentPage = clampPage(restored);
            saveReadingState();
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
        if (pageImage != null) {
            pageImage.animate().cancel();
            pageImage.setImageDrawable(null);
        }
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
        currentBitmap = null;
        renderedZoom = 1.0f;
        pendingZoomFocus = false;
    }

    private void renderCurrentPage() {
        renderCurrentPage(currentBitmap == null);
    }

    private void renderCurrentPage(boolean showLoadingIndicator) {
        if (verticalPageSlideMode) {
            renderContinuousPages();
            return;
        }
        if (pdfRenderer == null || pageCount <= 0 || pdfViewport == null) return;
        final int pageToRender = currentPage;
        final float zoomToRender = zoom;
        final int generation = ++renderGeneration;

        if (showLoadingIndicator) {
            updateLoadingIndicatorTheme();
            progressBar.setVisibility(View.VISIBLE);
        } else {
            progressBar.setVisibility(View.GONE);
        }
        updatePageStatus();

        int viewportWidth = pdfViewport.getWidth();
        if (viewportWidth <= 0) {
            pdfViewport.post(() -> renderCurrentPage(showLoadingIndicator));
            return;
        }

        executor.execute(() -> {
            Bitmap bitmap = null;
            try {
                synchronized (rendererLock) {
                    if (pdfRenderer == null) return;
                    PdfRenderer.Page page = pdfRenderer.openPage(pageToRender);
                    try {
                        int baseWidth = Math.max(1, viewportWidth - dpToPx(24));
                        float fitScale = baseWidth / (float) page.getWidth();
                        float renderScale = Math.max(0.2f, fitScale * zoomToRender);
                        int width = Math.max(1, Math.round(page.getWidth() * renderScale));
                        int height = Math.max(1, Math.round(page.getHeight() * renderScale));

                        long pixels = (long) width * (long) height;
                        long maxPixels = 22000000L;
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
                        if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
                        return;
                    }
                    Bitmap old = currentBitmap;
                    currentBitmap = finalBitmap;
                    renderedZoom = zoomToRender;
                    pageImage.animate().cancel();
                    pageImage.setScaleX(1.0f);
                    pageImage.setScaleY(1.0f);
                    pageImage.setImageBitmap(finalBitmap);
                    if (pendingPageSlideDirection != 0) {
                        positionPdfPageAfterPageTurn();
                    } else {
                        restoreDoubleTapZoomFocusAfterRender();
                    }
                    runPageSlideInAnimation();
                    if (old != null && old != finalBitmap && !old.isRecycled()) old.recycle();
                    progressBar.setVisibility(View.GONE);
                    updatePageStatus();
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

        pageImage.post(() -> {
            if (pageImage == null || pdfViewport == null) return;
            int targetX = Math.round(pageImage.getWidth() * xRatio - viewportX);
            int targetY = Math.round(pageImage.getHeight() * yRatio - viewportY);

            if (pdfHScroll != null) {
                int maxX = 0;
                if (pdfHScroll.getChildCount() > 0) {
                    maxX = Math.max(0, pdfHScroll.getChildAt(0).getWidth() - pdfHScroll.getWidth());
                }
                pdfHScroll.scrollTo(Math.max(0, Math.min(maxX, targetX)), pdfHScroll.getScrollY());
            }
            if (pdfVScroll != null) {
                int maxY = 0;
                if (pdfVScroll.getChildCount() > 0) {
                    maxY = Math.max(0, pdfVScroll.getChildAt(0).getHeight() - pdfVScroll.getHeight());
                }
                pdfVScroll.scrollTo(pdfVScroll.getScrollX(), Math.max(0, Math.min(maxY, targetY)));
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
        if (pageImage == null) return;
        int direction = pendingPageSlideDirection;
        pendingPageSlideDirection = 0;
        if (direction == 0) {
            pageImage.setAlpha(1.0f);
            pageImage.setTranslationX(0f);
            pageImage.setTranslationY(0f);
            return;
        }
        float distance = Math.max(dpToPx(56),
                (verticalPageSlideMode ? pageImage.getHeight() : pageImage.getWidth()) * 0.18f);
        pageImage.setTranslationX(0f);
        pageImage.setTranslationY(0f);
        if (verticalPageSlideMode) {
            pageImage.setTranslationY(direction > 0 ? distance : -distance);
        } else {
            pageImage.setTranslationX(direction > 0 ? distance : -distance);
        }
        pageImage.setAlpha(0.72f);
        pageImage.animate()
                .translationX(0f)
                .translationY(0f)
                .alpha(1.0f)
                .setDuration(150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    void updatePageStatus() {
        if (pageStatus == null) return;
        if (pageCount <= 0) {
            pageStatus.setText("");
            return;
        }
        pageStatus.setText(String.format(Locale.getDefault(), "%d / %d", currentPage + 1, pageCount));
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

    void saveReadingState() {
        if (filePath == null || !prefs.getAutoSavePosition()) return;
        ReaderState state = new ReaderState(filePath);
        state.setCharPosition(currentPage);
        state.setScrollY(0);
        state.setPageNumber(currentPage + 1);
        state.setTotalPages(pageCount);
        state.setFileLength(fileSizeBytes(filePath));
        state.setEncoding("PDF_PAGE");
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
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
                currentBitmap = null;
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
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cancelPdfBackgroundMemoryTrim();
            trimPdfBitmapsForBackground(true);
        }
    }


    int calculatePdfContinuousCacheKb() {
        long maxMemoryKb = Runtime.getRuntime().maxMemory() / 1024L;
        long targetKb = Math.max(12L * 1024L, Math.min(64L * 1024L, maxMemoryKb / 8L));
        return (int) Math.max(8L * 1024L, targetKb);
    }

    long getContinuousPageMaxPixels() {
        return 12000000L;
    }

    @Override
    protected void onDestroy() {
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
            if (continuousScrollListener != null) {
                pdfContinuousList.removeOnScrollListener(continuousScrollListener);
            }
            pdfContinuousList.setAdapter(null);
        }
        if (pdfContinuousAdapter != null) {
            pdfContinuousAdapter.release();
        }
        continuousScrollListener = null;
        closeRenderer();
        executor.shutdownNow();
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
