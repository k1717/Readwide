package com.textview.reader;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.annotation.SuppressLint;
import android.text.InputType;
import android.view.MenuItem;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.widget.TextViewCompat;

import com.textview.reader.adapter.BookmarkFolderAdapter;
import com.textview.reader.model.Bookmark;
import com.textview.reader.model.ReaderState;
import com.textview.reader.model.Theme;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.FontManager;
import com.textview.reader.util.PrefsManager;
import com.textview.reader.util.ThemeManager;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Page-style viewer for EPUB and OOXML Word files.
 *
 * EPUB: renders each original spine XHTML/HTML document inside WebView, preserving
 * the EPUB's markup/CSS/images as much as Android WebView allows.
 *
 * Word: renders OOXML document content as formatted HTML pages. Explicit Word page
 * breaks are respected; when a document has no page breaks, the content is split
 * into readable page-sized chunks instead of flattened into plain text.
 */
public class DocumentPageActivity extends AppCompatActivity {
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_URI = "file_uri";
    public static final String EXTRA_JUMP_TO_PAGE = "jump_page";

    static final String LOCAL_HOST = "textview.local";
    static final String EPUB_PREFIX = "/epub/";
    private static final String WORD_PREFIX = "/word/";
    private static final String FONT_PREFIX = "/font/";
    private static final String DOCUMENT_FONT_DEFAULT = "document_default";
    private static final String FONT_OPTION_SYSTEM_CURRENT = "system_current";
    private static final int WORD_PARAGRAPHS_PER_PAGE = 28;
    // Match toolbar-triggered document popups to the Go to Page bottom offset.
    static final int DOCUMENT_TOOLBAR_POPUP_Y_DP = 74;

    Toolbar toolbar;
    View documentAppBar;
    View documentBottomChrome;
    boolean documentChromeVisible = true;
    WebView webView;
    ProgressBar progressBar;
    TextView pageStatus;
    TextView prevButton;
    TextView nextButton;
    TextView searchButton;
    TextView pageButton;
    TextView bookmarkButton;
    TextView moreButton;
    int readerBg = Color.rgb(18, 18, 18);
    int readerFg = Color.rgb(232, 234, 237);
    int readerToolbarBg = Color.rgb(18, 18, 18);
    int readerSub = Color.rgb(176, 176, 176);
    int readerPanel = Color.rgb(32, 33, 36);
    int readerLine = Color.rgb(84, 86, 90);
    String lastAppliedDocumentThemeSignature = null;
    boolean restoreDocumentScrollAfterThemeRefresh = false;
    int pendingThemeRefreshScrollX = 0;
    int pendingThemeRefreshScrollY = 0;

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final List<Page> pages = new ArrayList<>();
    BookmarkManager bookmarkManager;
    PrefsManager prefs;
    private ZipFile resourceZip;
    File localFile;
    String filePath;
    String fileName;
    String docType = "Document";
    private int lastAppliedEpubLeftPaddingDp = Integer.MIN_VALUE;
    private int lastAppliedEpubRightPaddingDp = Integer.MIN_VALUE;
    private int lastAppliedEpubTopPaddingDp = Integer.MIN_VALUE;
    private int lastAppliedEpubBottomPaddingDp = Integer.MIN_VALUE;
    private int lastAppliedEpubBottomToolbarHeightPx = Integer.MIN_VALUE;
    private int lastAppliedEpubEffectiveBottomMarginPx = Integer.MIN_VALUE;
    int currentPage = 0;
    int pendingSlideDirection = 0;
    private int wordSwipeTouchSlop = 0;
    private float wordSwipeStartX = 0f;
    private float wordSwipeStartY = 0f;
    private boolean wordSwipeTriggered = false;
    private boolean wordSwipeMovedEnoughForParentDisallow = false;
    boolean pageTurnInFlight = false;
    private GestureDetector documentGestureDetector;
    private boolean documentDoubleTapResetSequence = false;
    private int armedDocumentEdgeDirection = 0;
    private long armedDocumentEdgeTimeMs = 0L;
    private boolean wordGestureStartedAtLeftEdge = true;
    private boolean wordGestureStartedAtRightEdge = true;
    volatile boolean wordSelectionActive = false;
    volatile boolean activityDestroyed = false;
    int loadGeneration = 0;
    File selectedDocumentFontFile = null;
    boolean epubHasDocumentFont = false;
    boolean epubFixedLayoutLike = false;
    boolean fixedLayoutFindOffsetActive = false;
    boolean wordHasDocumentFont = false;
    String wordDefaultFontFamily = null;
    String documentFontOverride = null;
    String activeDocumentSearchQuery = "";
    int activeDocumentSearchPage = -1;
    int activeDocumentSearchOrdinal = 0;
    int activeDocumentSearchCountOnPage = 0;
    int activeDocumentSearchTotal = 0;
    boolean documentSearchSelectLastAfterCount = false;
    TextView documentSearchStatusView = null;
    FrameLayout documentSearchPanelContainer = null;
    FrameLayout documentSearchOverlayContainer = null;
    EditText documentSearchInputView = null;
    final Runnable checkWordSelectionAfterScrollRunnable = this::checkWordSelectionAfterScroll;
    final Runnable releasePageTurnRunnable = () -> pageTurnInFlight = false;
    final Map<String, String> wordRelationships = new LinkedHashMap<>();
    private DocumentPageStartupController startupController;
    private DocumentPageTurnController pageTurnController;
    private DocumentWebViewController documentWebViewController;
    private DocumentPageLoadController documentPageLoadController;
    private DocumentPageDisplayController documentPageDisplayController;

    static class Page {
        final String title;
        final String html;
        final String sourcePath;

        Page(String title, String html, String sourcePath) {
            this.title = title;
            this.html = html;
            this.sourcePath = sourcePath;
        }
    }

    private DocumentPageStartupController startup() {
        if (startupController == null) {
            startupController = new DocumentPageStartupController(this);
        }
        return startupController;
    }

    private DocumentPageTurnController pageTurns() {
        if (pageTurnController == null) {
            pageTurnController = new DocumentPageTurnController(this);
        }
        return pageTurnController;
    }

    private DocumentWebViewController documentWebViews() {
        if (documentWebViewController == null) {
            documentWebViewController = new DocumentWebViewController(this);
        }
        return documentWebViewController;
    }

    private DocumentPageLoadController pageLoader() {
        if (documentPageLoadController == null) {
            documentPageLoadController = new DocumentPageLoadController(this);
        }
        return documentPageLoadController;
    }

    private DocumentPageDisplayController pageDisplay() {
        if (documentPageDisplayController == null) {
            documentPageDisplayController = new DocumentPageDisplayController(this);
        }
        return documentPageDisplayController;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        prefs.applyLanguage(prefs.getLanguageMode());

        // Do NOT force MODE_NIGHT_YES here. The previous attempt to keep the
        // floating selection toolbar in "dark bubble" style by forcing night mode
        // caused the WebView to resolve its text-selection handle drawables and
        // its floating action-mode layout against a dark Material configuration
        // while the actual document content kept the user's reader background
        // (often a light Cream / Sepia). The mismatch produced the malformed
        // teardrop handle and the toolbar bubble appearing pinned to the top of
        // the screen instead of next to the selection. The Samsung / system UI
        // already renders its own dark floating toolbar on Android 13+, so the
        // dark-bubble look is preserved even without forcing night mode.
        super.onCreate(savedInstanceState);
        startup().onCreateAfterSuper(savedInstanceState);
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        startup().onNewIntent(intent);
    }


    void updateLoadingIndicatorTheme() {
        if (progressBar == null) return;
        progressBar.setBackgroundColor(Color.TRANSPARENT);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(readerFg));
    }

    @Override
    protected void onResume() {
        super.onResume();
        startup().onResume();
    }

    @Override
    protected void onPause() {
        startup().onPause();
        super.onPause();
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

    String documentThemeSignature() {
        Theme theme = ThemeManager.getInstance(this).getActiveTheme();
        if (theme == null) {
            return "theme:null:" + readerBg + ":" + readerFg + ":" + readerLine
                    + "|epubFontSize=" + (("EPUB".equals(docType) && prefs != null)
                    ? prefs.getFontSize() : PrefsManager.DEFAULT_FONT_SIZE);
        }
        String backgroundImagePath = theme.getBackgroundImagePath();
        return theme.getId()
                + "|fg=" + theme.getTextColor()
                + "|bg=" + theme.getBackgroundColor()
                + "|toolbar=" + theme.getToolbarColor()
                + "|link=" + theme.getLinkColor()
                + "|img=" + (backgroundImagePath != null ? backgroundImagePath : "")
                + "|alpha=" + theme.getBackgroundImageAlpha()
                + "|epubFontSize=" + (("EPUB".equals(docType) && prefs != null)
                ? prefs.getFontSize() : PrefsManager.DEFAULT_FONT_SIZE);
    }

    void refreshDocumentPageThemeIfNeeded(String currentThemeSignature, boolean pageThemeChanged) {
        if (!pageThemeChanged) {
            lastAppliedDocumentThemeSignature = currentThemeSignature;
            return;
        }
        lastAppliedDocumentThemeSignature = currentThemeSignature;
        if (webView == null || pages.isEmpty() || currentPage < 0 || currentPage >= pages.size()) return;
        pendingThemeRefreshScrollX = webView.getScrollX();
        pendingThemeRefreshScrollY = webView.getScrollY();
        restoreDocumentScrollAfterThemeRefresh = true;
        clearDocumentEdgeArm();
        showPage(currentPage, 0);
    }

    void restoreDocumentScrollAfterThemeRefreshIfNeeded(@NonNull WebView view) {
        if (!restoreDocumentScrollAfterThemeRefresh) return;
        final int restoreX = pendingThemeRefreshScrollX;
        final int restoreY = pendingThemeRefreshScrollY;
        restoreDocumentScrollAfterThemeRefresh = false;
        view.postDelayed(() -> {
            if (!activityDestroyed && webView != null) {
                webView.scrollTo(restoreX, restoreY);
            }
        }, 60);
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
        controller.setAppearanceLightStatusBars(!isDarkColor(toolbarBg));
        controller.setAppearanceLightNavigationBars(!isDarkColor(bg));
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

    void applyDocumentThemeToViews() {
        resolveReaderThemeColors();
        View root = findViewById(R.id.document_root);
        View viewport = findViewById(R.id.document_viewport);
        View appbar = findViewById(R.id.document_appbar);
        View bottom = findViewById(R.id.document_bottom_scroller);
        if (root != null) root.setBackgroundColor(readerBg);
        if (viewport != null) viewport.setBackgroundColor(readerBg);
        if (documentSearchPanelContainer != null) documentSearchPanelContainer.setBackgroundColor(readerBg);
        if (documentSearchOverlayContainer != null) documentSearchOverlayContainer.setBackgroundColor(Color.TRANSPARENT);
        if (appbar != null) appbar.setBackgroundColor(readerToolbarBg);
        if (bottom != null) bottom.setBackgroundColor(readerPanel);
        if (toolbar != null) {
            toolbar.setBackgroundColor(readerToolbarBg);
            toolbar.setTitleTextColor(readerFg);
            android.graphics.drawable.Drawable nav = toolbar.getNavigationIcon();
            if (nav != null) {
                android.graphics.drawable.Drawable wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(nav.mutate());
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, readerFg);
                toolbar.setNavigationIcon(wrapped);
            }
        }
        if (webView != null) webView.setBackgroundColor(readerBg);
        if (pageStatus != null) pageStatus.setTextColor(readerFg);
        updateLoadingIndicatorTheme();
        TextView[] buttons = {prevButton, nextButton, searchButton, pageButton, bookmarkButton, moreButton};
        for (TextView b : buttons) {
            if (b == null) continue;
            b.setTextColor(readerFg);
            TextViewCompat.setCompoundDrawableTintList(b, android.content.res.ColorStateList.valueOf(readerFg));
        }
    }

    @Override
    protected void onDestroy() {
        ViewerRegistry.unregister(this);
        activityDestroyed = true;
        loadGeneration++;
        saveReadingState();
        clearDocumentSearchState(true);
        destroyDocumentWebView();
        closeResourceZip();
        pages.clear();
        wordRelationships.clear();
        executor.shutdownNow();
        super.onDestroy();
    }

    private boolean handleDocumentTapGesture(@NonNull MotionEvent event) {
        if (documentGestureDetector == null) return false;
        boolean handled = documentGestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();

        if (documentDoubleTapResetSequence) {
            // A double tap is handled by this Activity as a zoom reset.  Consume the
            // second tap sequence so Android WebView's own double-tap zoom does not
            // race against the reset and immediately zoom the EPUB back in/out.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                documentDoubleTapResetSequence = false;
            }
            return true;
        }

        // Do not consume normal ACTION_DOWN; the WebView still needs the original
        // down event for scrolling, text selection, and edge-swipe tracking.
        return handled && action != MotionEvent.ACTION_DOWN;
    }

    private void toggleDocumentChrome() {
        setDocumentChromeVisible(!documentChromeVisible);
    }

    private void setDocumentChromeVisible(boolean visible) {
        documentChromeVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (documentAppBar != null && documentAppBar.getVisibility() != visibility) {
            documentAppBar.setVisibility(visibility);
        }
        if (documentBottomChrome != null && documentBottomChrome.getVisibility() != visibility) {
            documentBottomChrome.setVisibility(visibility);
        }
        View viewport = findViewById(R.id.document_viewport);
        androidx.core.view.ViewCompat.requestApplyInsets(findViewById(R.id.document_root));
        if (viewport != null) viewport.requestLayout();
        applyEpubBoundaryMarginsIfNeeded();
    }

    private void destroyDocumentWebView() {
        if (webView == null) return;
        try {
            webView.removeCallbacks(checkWordSelectionAfterScrollRunnable);
            webView.removeCallbacks(releasePageTurnRunnable);
            webView.animate().cancel();
            webView.setOnTouchListener(null);
            webView.setOnScrollChangeListener(null);
            webView.setWebViewClient(null);
            webView.removeJavascriptInterface("TextViewSelectionBridge");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        } catch (Throwable ignored) {
            // WebView teardown should never crash the Activity during system cleanup.
        } finally {
            webView = null;
        }
    }

    void closeResourceZip() {
        if (resourceZip != null) {
            try { resourceZip.close(); } catch (IOException ignored) {}
            resourceZip = null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (isDocumentSearchPanelVisible()) {
            hideDocumentSearchPanel(true, true);
            return;
        }
        super.onBackPressed();
    }

    void setupWebView() {
        documentWebViews().setupWebView();
    }

    void configureWebViewForCurrentPage() {
        documentWebViews().configureForCurrentPage();
    }

    void setupButtons() {
        prevButton.setOnClickListener(v -> {
            if (currentPage > 0) showPage(currentPage - 1, -1);
        });
        nextButton.setOnClickListener(v -> {
            if (currentPage < pages.size() - 1) showPage(currentPage + 1, 1);
        });
        if (searchButton != null) searchButton.setOnClickListener(v -> showDocumentSearchDialog());
        if (pageButton != null) pageButton.setOnClickListener(v -> showGoToPageDialog());
        bookmarkButton.setOnClickListener(v -> showBookmarksDialog());
        if (moreButton != null) {
            moreButton.setOnClickListener(v -> showMoreDialog());
        }
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

    @SuppressLint("ClickableViewAccessibility")
    void installSwipePaging() {
        wordSwipeTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        documentGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                toggleDocumentChrome();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                documentDoubleTapResetSequence = true;
                resetDocumentZoom();
                clearDocumentEdgeArm();
                if (webView != null) {
                    webView.postDelayed(() -> documentDoubleTapResetSequence = false, 360);
                }
                return true;
            }
        });

        webView.setOnTouchListener((v, event) -> {
            if (handleDocumentTapGesture(event)) {
                return true;
            }

            if (!isPagedWebDocument() || pages.size() <= 1 || pageTurnInFlight) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    wordSwipeStartX = event.getX();
                    wordSwipeStartY = event.getY();
                    wordSwipeTriggered = false;
                    wordSwipeMovedEnoughForParentDisallow = false;
                    wordGestureStartedAtLeftEdge = !webView.canScrollHorizontally(-1);
                    wordGestureStartedAtRightEdge = !webView.canScrollHorizontally(1);
                    webView.removeCallbacks(checkWordSelectionAfterScrollRunnable);
                    webView.removeCallbacks(releasePageTurnRunnable);
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (wordSelectionActive) return false;
                    float dx = event.getX() - wordSwipeStartX;
                    float dy = event.getY() - wordSwipeStartY;
                    if (!wordSwipeMovedEnoughForParentDisallow
                            && Math.abs(dx) > wordSwipeTouchSlop
                            && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        wordSwipeMovedEnoughForParentDisallow = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (!wordSwipeTriggered && shouldTurnDocumentPageBySwipe(event)) {
                        wordSwipeTriggered = true;
                        turnDocumentPageBySwipe(pageDeltaForHorizontalSwipe(event.getX() - wordSwipeStartX));
                        clearDocumentEdgeArm();
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                    if (Math.abs(event.getX() - wordSwipeStartX) < wordSwipeTouchSlop
                            && Math.abs(event.getY() - wordSwipeStartY) < wordSwipeTouchSlop) {
                        v.performClick();
                    }
                    if (!wordSwipeTriggered && shouldTurnDocumentPageBySwipe(event)) {
                        wordSwipeTriggered = true;
                        turnDocumentPageBySwipe(pageDeltaForHorizontalSwipe(event.getX() - wordSwipeStartX));
                        clearDocumentEdgeArm();
                        return true;
                    }
                    resetWordSwipeTracking();
                    webView.postDelayed(checkWordSelectionAfterScrollRunnable, 120);
                    return false;

                case MotionEvent.ACTION_CANCEL:
                    resetWordSwipeTracking();
                    webView.postDelayed(checkWordSelectionAfterScrollRunnable, 120);
                    return false;

                default:
                    return false;
            }
        });
    }

    private void resetWordSwipeTracking() {
        wordSwipeTriggered = false;
        wordSwipeMovedEnoughForParentDisallow = false;
        wordGestureStartedAtLeftEdge = true;
        wordGestureStartedAtRightEdge = true;
        if (webView != null && webView.getParent() != null) {
            webView.getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    private boolean isPagedWebDocument() {
        return "Word".equals(docType) || "EPUB".equals(docType);
    }

    void clearDocumentEdgeArm() {
        armedDocumentEdgeDirection = 0;
        armedDocumentEdgeTimeMs = 0L;
    }

    private boolean shouldTurnDocumentPageBySwipe(@NonNull MotionEvent event) {
        if (activityDestroyed || wordSelectionActive || webView == null || pages.size() <= 1 || pageTurnInFlight) return false;

        float dx = event.getX() - wordSwipeStartX;
        float dy = event.getY() - wordSwipeStartY;
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);
        long duration = event.getEventTime() - event.getDownTime();

        // Slightly lighter than before so zoomed Word/EPUB pages do not feel
        // like they need multiple hard swipes. The edge rule below still prevents
        // accidental page turns while the WebView can pan horizontally.
        float threshold = Math.max(dpToPx(28), webView.getWidth() * 0.06f);
        if (!(absX >= threshold
                && absX > absY * 1.28f
                && absY <= dpToPx(78)
                && duration <= 850)) {
            return false;
        }

        int horizontalScrollDirection = dx < 0 ? 1 : -1;

        // Non-zoomed / normally wrapped pages should turn immediately on the first
        // swipe. The two-step edge threshold is used only when WebView actually has
        // horizontal scroll range, matching the PDF behavior for zoomed pages.
        if (!webView.canScrollHorizontally(-1) && !webView.canScrollHorizontally(1)) {
            return true;
        }

        if (webView.canScrollHorizontally(horizontalScrollDirection)) {
            clearDocumentEdgeArm();
            return false;
        }

        // If this is a fresh gesture that already started at the matching WebView
        // edge, allow page turn immediately. This keeps the first swipe from
        // jumping while panning to the edge, but avoids needing another arm+turn
        // cycle after the user is already resting at that edge.
        if ((horizontalScrollDirection > 0 && wordGestureStartedAtRightEdge)
                || (horizontalScrollDirection < 0 && wordGestureStartedAtLeftEdge)) {
            return true;
        }

        long now = event.getEventTime();

        // When the WebView is zoomed/expanded and the user reaches the horizontal
        // edge, do not turn the document page during the same drag. The old logic
        // could arm the edge on one ACTION_MOVE and then satisfy the armed-edge
        // condition on a later ACTION_MOVE from the same finger gesture, which made
        // the Word viewer jump to the next/previous page instead of stopping at the
        // WebView edge. Only a fresh gesture that starts after the edge was armed is
        // allowed to turn the page.
        boolean armedFromPreviousGesture = armedDocumentEdgeTimeMs > 0L
                && armedDocumentEdgeTimeMs < event.getDownTime();
        if (armedDocumentEdgeDirection == horizontalScrollDirection
                && armedFromPreviousGesture
                && now - armedDocumentEdgeTimeMs <= 600L) {
            return true;
        }

        armedDocumentEdgeDirection = horizontalScrollDirection;
        armedDocumentEdgeTimeMs = now;
        return false;
    }

    private int pageDeltaForHorizontalSwipe(float dx) {
        // Default EPUB direction follows Korean/Western books: swipe left = next.
        // The optional RTL mode supports Japanese-style right-to-left books: swipe right = next.
        if ("EPUB".equals(docType)
                && prefs != null
                && prefs.getEpubPageDirection() == PrefsManager.EPUB_PAGE_DIRECTION_RTL) {
            return dx > 0 ? 1 : -1;
        }
        return dx < 0 ? 1 : -1;
    }

    int visualSlideDirectionForPageDelta(int pageDelta) {
        if (pageDelta == 0) return 0;
        if ("EPUB".equals(docType)
                && prefs != null
                && prefs.getEpubPageEffect() == PrefsManager.EPUB_PAGE_EFFECT_NONE) {
            return 0;
        }
        if ("EPUB".equals(docType)
                && prefs != null
                && prefs.getEpubPageDirection() == PrefsManager.EPUB_PAGE_DIRECTION_RTL) {
            return -pageDelta;
        }
        return pageDelta;
    }

    private void turnDocumentPageBySwipe(int direction) {
        if (webView == null || pageTurnInFlight) return;
        if (direction > 0 && currentPage < pages.size() - 1) {
            showPage(currentPage + 1, 1);
        } else if (direction < 0 && currentPage > 0) {
            showPage(currentPage - 1, -1);
        }
    }

    private void checkWordSelectionAfterScroll() {
        if (activityDestroyed || !"Word".equals(docType) || webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__textviewClearSelectionIfOffscreen&&window.__textviewClearSelectionIfOffscreen());}catch(e){return false;}})()",
                value -> {
                    if ("true".equals(value)) {
                        wordSelectionActive = false;
                    }
                });
    }

    void installWordSelectionCleanupScript() {
        if (activityDestroyed || !"Word".equals(docType) || webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{"
                        + "if(window.__textviewSelectionCleanupInstalled){return true;}"
                        + "window.__textviewSelectionCleanupInstalled=true;"
                        + "function sel(){return window.getSelection?window.getSelection():null;}"
                        + "function active(){var s=sel();return !!(s&&!s.isCollapsed&&s.rangeCount>0&&String(s).length>0);}"
                        + "function notify(){try{if(window.TextViewSelectionBridge){window.TextViewSelectionBridge.onSelectionChanged(active());}}catch(e){}}"
                        + "window.__textviewClearSelectionIfOffscreen=function(){"
                        + "try{var s=sel();if(!s||s.isCollapsed||s.rangeCount===0||String(s).length===0){notify();return false;}"
                        + "var r=s.getRangeAt(0);var rects=Array.prototype.slice.call(r.getClientRects()).filter(function(x){return x&&x.width>0&&x.height>0;});"
                        + "if(!rects.length){s.removeAllRanges();notify();return true;}"
                        + "var w=window.innerWidth||document.documentElement.clientWidth||0;var h=window.innerHeight||document.documentElement.clientHeight||0;var m=8;"
                        + "var visible=rects.some(function(x){return x.bottom>=m&&x.top<=h-m&&x.right>=m&&x.left<=w-m;});"
                        + "if(!visible){s.removeAllRanges();notify();return true;}notify();return false;}catch(e){return false;}};"
                        + "document.addEventListener('selectionchange',function(){setTimeout(notify,0);},true);"
                        + "document.addEventListener('touchend',function(){setTimeout(notify,70);},true);"
                        + "document.addEventListener('mouseup',function(){setTimeout(notify,70);},true);"
                        + "window.addEventListener('scroll',function(){clearTimeout(window.__textviewScrollCleanupTimer);window.__textviewScrollCleanupTimer=setTimeout(window.__textviewClearSelectionIfOffscreen,80);},{passive:true});"
                        + "setTimeout(notify,120);return true;}catch(e){return false;}})()",
                null);
    }

    class WordSelectionBridge {
        @JavascriptInterface
        public void onSelectionChanged(boolean active) {
            wordSelectionActive = active;
        }
    }

    private void showMoreDialog() {
        ThemeManager.getInstance(this).reloadFromStorage();
        resolveReaderThemeColors();
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.more)));
        box.addView(makeDialogActionRow(getString(R.string.font), () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showDocumentFontDialog();
        }));
        if ("EPUB".equals(docType)) {
            box.addView(makeDialogActionRow(getString(R.string.increase_font), () -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                changeEpubFontSize(2f);
            }));
            box.addView(makeDialogActionRow(getString(R.string.decrease_font), () -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                changeEpubFontSize(-2f);
            }));
            box.addView(makeDialogActionRow(getString(R.string.reset_font_size), () -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                resetEpubFontSize();
            }));
        }
        box.addView(makeDialogActionRow(getString(R.string.settings), () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            startActivity(new Intent(this, SettingsActivity.class));
        }));
        box.addView(makeDialogActionRow(getString(R.string.file_info), () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showFileInfoDialog();
        }));
        addDialogBottomActions(box,
                getString(R.string.action_open_file), () -> {
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                    openFileBrowserFromViewer();
                },
                getString(R.string.close), () -> {
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                });
        dialogRef[0] = createStablePositionedDialog(box, DOCUMENT_TOOLBAR_POPUP_Y_DP, false, false);
        dialogRef[0].show();
    }

    private void openFileBrowserFromViewer() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_RETURN_TO_VIEWER, true);
        File current = filePath != null ? new File(filePath) : null;
        File parent = current != null ? current.getParentFile() : null;
        if (parent != null && parent.exists() && parent.isDirectory()) {
            intent.putExtra(MainActivity.EXTRA_START_DIRECTORY, parent.getAbsolutePath());
        }
        startActivity(intent);
    }

    private int getEpubLeftPaddingDp() {
        return prefs != null ? prefs.getEpubLeftPaddingDp() : 30;
    }

    private int getEpubRightPaddingDp() {
        return prefs != null ? prefs.getEpubRightPaddingDp() : 30;
    }

    private int getEpubTopPaddingDp() {
        return prefs != null ? prefs.getEpubTopPaddingDp() : 0;
    }

    private int getEpubBottomPaddingDp() {
        return prefs != null ? prefs.getEpubBottomPaddingDp() : 0;
    }

    void refreshEpubSpacingIfNeeded() {
        applyEpubBoundaryMarginsIfNeeded();
    }

    private int clampEpubBoundaryPx(int px) {
        int clamped = Math.max(0, Math.min(240, px));
        return Math.round(clamped / 5f) * 5;
    }

    private int getVisibleDocumentBottomToolbarHeightPx() {
        if (documentBottomChrome == null || documentBottomChrome.getVisibility() != View.VISIBLE) {
            return 0;
        }
        int height = documentBottomChrome.getHeight();
        if (height <= 0) height = documentBottomChrome.getMeasuredHeight();
        return Math.max(0, height);
    }

    private boolean isDocumentBottomToolbarHeightPending() {
        return documentBottomChrome != null
                && documentBottomChrome.getVisibility() == View.VISIBLE
                && documentBottomChrome.getHeight() <= 0
                && documentBottomChrome.getMeasuredHeight() <= 0;
    }

    private int getEffectiveEpubBottomMarginPx(int requestedBottomBoundaryPx, int bottomToolbarHeightPx) {
        if (!"EPUB".equals(docType) || requestedBottomBoundaryPx <= 0) return 0;
        if (isDocumentBottomToolbarHeightPending()) {
            return 0;
        }
        return Math.max(0, requestedBottomBoundaryPx - Math.max(0, bottomToolbarHeightPx));
    }

    void applyEpubBoundaryMarginsIfNeeded() {
        if (webView == null) return;
        if ("EPUB".equals(docType) && epubFixedLayoutLike) {
            lastAppliedEpubLeftPaddingDp = 0;
            lastAppliedEpubRightPaddingDp = 0;
            lastAppliedEpubTopPaddingDp = 0;
            lastAppliedEpubBottomPaddingDp = 0;
            lastAppliedEpubBottomToolbarHeightPx = 0;
            lastAppliedEpubEffectiveBottomMarginPx = 0;
            ViewGroup.LayoutParams rawLp = webView.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
                if (lp.leftMargin != 0 || lp.topMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0) {
                    lp.setMargins(0, 0, 0, 0);
                    webView.setLayoutParams(lp);
                }
            }
            return;
        }
        int left = "EPUB".equals(docType) ? clampEpubBoundaryPx(getEpubLeftPaddingDp()) : 0;
        int right = "EPUB".equals(docType) ? clampEpubBoundaryPx(getEpubRightPaddingDp()) : 0;
        int top = "EPUB".equals(docType) ? clampEpubBoundaryPx(getEpubTopPaddingDp()) : 0;
        int bottom = "EPUB".equals(docType) ? clampEpubBoundaryPx(getEpubBottomPaddingDp()) : 0;
        int bottomToolbarHeightPx = "EPUB".equals(docType) ? getVisibleDocumentBottomToolbarHeightPx() : 0;
        int effectiveBottomMarginPx = getEffectiveEpubBottomMarginPx(bottom, bottomToolbarHeightPx);
        if (left == lastAppliedEpubLeftPaddingDp
                && right == lastAppliedEpubRightPaddingDp
                && top == lastAppliedEpubTopPaddingDp
                && bottom == lastAppliedEpubBottomPaddingDp
                && bottomToolbarHeightPx == lastAppliedEpubBottomToolbarHeightPx
                && effectiveBottomMarginPx == lastAppliedEpubEffectiveBottomMarginPx) {
            return;
        }
        lastAppliedEpubLeftPaddingDp = left;
        lastAppliedEpubRightPaddingDp = right;
        lastAppliedEpubTopPaddingDp = top;
        lastAppliedEpubBottomPaddingDp = bottom;
        lastAppliedEpubBottomToolbarHeightPx = bottomToolbarHeightPx;
        lastAppliedEpubEffectiveBottomMarginPx = effectiveBottomMarginPx;

        ViewGroup.LayoutParams rawLp = webView.getLayoutParams();
        if (rawLp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
            int leftPx = left;
            int rightPx = right;
            int topPx = top;
            int bottomPx = effectiveBottomMarginPx;
            if (lp.leftMargin != leftPx || lp.rightMargin != rightPx
                    || lp.topMargin != topPx || lp.bottomMargin != bottomPx) {
                lp.setMargins(leftPx, topPx, rightPx, bottomPx);
                webView.setLayoutParams(lp);
            }
        }
    }

    private void resetDocumentZoom() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setTextZoom(documentTextZoomPercent());

        // WebView zoomOut() is step-based.  Use a bounded loop instead of relying
        // on an unbounded canZoomOut() loop so double-tap reset cannot overshoot or
        // get stuck on device-specific WebView implementations.
        for (int i = 0; i < 18 && webView.canZoomOut(); i++) {
            if (!webView.zoomOut()) break;
        }

        stabilizeDocumentAfterZoomReset();
        clearDocumentEdgeArm();
    }

    private void stabilizeDocumentAfterZoomReset() {
        if (webView == null || !"EPUB".equals(docType)) return;

        Runnable stabilize = () -> {
            if (activityDestroyed || webView == null || !"EPUB".equals(docType)) return;
            if (epubFixedLayoutLike) {
                applyFixedLayoutFindOffsetCssIfNeeded();
                webView.scrollTo(0, 0);
            }
            clearDocumentEdgeArm();
        };

        webView.post(stabilize);
        webView.postDelayed(stabilize, 90);
        webView.postDelayed(stabilize, 240);
    }

    int documentTextZoomPercent() {
        if ("EPUB".equals(docType) && epubFixedLayoutLike) return 100;
        if (!"EPUB".equals(docType) || prefs == null) return 100;
        float size = Math.max(8f, Math.min(48f, prefs.getFontSize()));
        return Math.max(50, Math.min(267, Math.round(size / PrefsManager.DEFAULT_FONT_SIZE * 100f)));
    }

    private void applyDocumentTextZoom() {
        if (webView == null) return;
        webView.getSettings().setTextZoom(documentTextZoomPercent());
    }

    private void changeEpubFontSize(float delta) {
        if (prefs == null) return;
        float newSize = Math.max(8f, Math.min(48f, prefs.getFontSize() + delta));
        prefs.setFontSize(newSize);
        refreshCurrentEpubTextSize();
    }

    private void resetEpubFontSize() {
        if (prefs == null) return;
        prefs.setFontSize(PrefsManager.DEFAULT_FONT_SIZE);
        refreshCurrentEpubTextSize();
    }

    private void refreshCurrentEpubTextSize() {
        if ("EPUB".equals(docType) && epubFixedLayoutLike) {
            ShortToast.show(this, localizedText("Fixed-layout EPUB keeps its original page layout.", "고정 레이아웃 EPUB은 원본 페이지 배치를 유지합니다."));
            return;
        }
        applyDocumentTextZoom();
        clearDocumentEdgeArm();
        if (!pages.isEmpty() && currentPage >= 0 && currentPage < pages.size()) {
            showPage(currentPage, 0);
        }
    }

    private DocumentFontDialogController documentFontController() {
        return new DocumentFontDialogController(this);
    }

    private void showDocumentFontDialog() {
        documentFontController().showDocumentFontDialog();
    }

    private String buildDocumentFontCss() {
        return documentFontController().buildDocumentFontCss();
    }

    private WebResourceResponse interceptSelectedDocumentFont() {
        return documentFontController().interceptSelectedDocumentFont();
    }

    private String localizedText(String english, String korean) {
        return "ko".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? korean : english;
    }


    private DocumentSearchController documentSearchController() {
        return new DocumentSearchController(this);
    }

    private void showDocumentSearchDialog() {
        documentSearchController().showDocumentSearchDialog();
    }

    void hideDocumentSearchPanel(boolean saveQuery, boolean clearWebView) {
        documentSearchController().hideDocumentSearchPanel(saveQuery, clearWebView);
    }

    private boolean isDocumentSearchPanelVisible() {
        return documentSearchController().isDocumentSearchPanelVisible();
    }

    void applyDocumentSearchHighlightAfterPageLoad() {
        documentSearchController().applyDocumentSearchHighlightAfterPageLoad();
    }

    void clearDocumentSearchState(boolean clearWebView) {
        documentSearchController().clearDocumentSearchState(clearWebView);
    }

    void updateDocumentSearchStatus(TextView matchStatus) {
        documentSearchController().updateDocumentSearchStatus(matchStatus);
    }

    void scheduleDocumentSearchReveal() {
        documentSearchController().scheduleDocumentSearchReveal();
    }

    private void showFileInfoDialog() {
        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.file_info)));
        addInfoRow(box, getString(R.string.file_info_name), fileName != null ? fileName : "");
        addInfoRow(box, getString(R.string.file_info_type), docType);
        addInfoRow(box, getString(R.string.file_info_path), filePath != null ? filePath : "");
        if (localFile != null) {
            addInfoRow(box, getString(R.string.file_info_size), FileUtils.formatFileSize(localFile.length()));
            addInfoRow(box, getString(R.string.file_info_modified), DateFormat.getDateTimeInstance().format(new Date(localFile.lastModified())));
        }
        addInfoRow(box, getString(R.string.bottom_page), String.format(Locale.getDefault(), "%d / %d", currentPage + 1, pages.size()));
        showFileInfoDialogWithCenteredClose(box);
    }

    private void showGoToPageDialog() {
        if (pages.isEmpty()) return;

        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.page_move)));

        TextView label = new TextView(this);
        label.setTextColor(dialogFg());
        label.setTextSize(17f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setGravity(android.view.Gravity.CENTER);
        label.setText(formatPageMoveLabel(currentPage + 1, pages.size()));
        label.setPadding(0, dpToPx(4), 0, dpToPx(8));
        box.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar slider = new SeekBar(this);
        slider.setMax(Math.max(0, pages.size() - 1));
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

        EditText input = makeDialogInput("1 - " + pages.size());
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
                label.setText(formatPageMoveLabel(progress + 1, pages.size()));
                input.setText(String.valueOf(progress + 1));
                input.setSelection(input.getText().length());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                showPage(pending[0], Integer.compare(pending[0], currentPage));
            }
        });

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        addCenteredDialogBottomAction(box, getString(R.string.go), () -> {
            try {
                int target = Integer.parseInt(input.getText().toString().trim());
                if (target < 1 || target > pages.size()) {
                    ShortToast.show(this, getString(R.string.page_range_error, pages.size()));
                    return;
                }
                showPage(target - 1, Integer.compare(target - 1, currentPage));
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            } catch (Exception ignored) {
                ShortToast.show(this, getString(R.string.invalid_page_number));
            }
        });
        dialogRef[0] = createStablePositionedDialog(box, DOCUMENT_TOOLBAR_POPUP_Y_DP, true, false);
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

    int dialogBg() { return readerBg; }
    int dialogPanel() { return readerPanel; }
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

    TextView makeDialogActionRow(String text, Runnable action) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(dialogFg());
        row.setTextSize(16f);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dialogPanel());
        bg.setCornerRadius(dpToPx(10));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48));
        lp.setMargins(0, 0, 0, dpToPx(8));
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> {
            if (action != null) action.run();
        });
        return row;
    }

    EditText makeDialogInput(String hint) {
        int overlay = !isDarkColor(dialogBg())
                ? R.style.ThemeOverlay_TextView_ReaderDialogLight
                : R.style.ThemeOverlay_TextView_ReaderDialogDark;
        android.view.ContextThemeWrapper themed = new android.view.ContextThemeWrapper(this, overlay);

        EditText input = new EditText(themed);
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
        tintDocumentDialogEditHandles(input);
        return input;
    }

    private void tintDocumentDialogEditHandles(EditText input) {
        if (input == null) return;

        boolean lightDialog = !isDarkColor(dialogBg());
        int accent = lightDialog ? Color.rgb(34, 34, 34) : Color.WHITE;
        input.setHighlightColor(blendColors(dialogBg(), accent, lightDialog ? 0.24f : 0.42f));

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(accent);
            cursor.setSize(Math.max(2, dpToPx(2)), dpToPx(28));
            input.setTextCursorDrawable(cursor);
        }
    }

    private void tintSeekBar(SeekBar seekBar) {
        int accent = readerFg;
        int track = readerLine;
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track));
    }

    private void addInfoRow(LinearLayout box, String label, String value) {
        TextView row = new TextView(this);
        String safeValue = value != null ? value : "";
        row.setText(safeValue.isEmpty()
                ? label
                : String.format(Locale.getDefault(), "%s\n%s", label, safeValue));
        row.setTextColor(dialogFg());
        row.setTextSize(14f);
        row.setPadding(0, dpToPx(5), 0, dpToPx(7));
        box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void showFileInfoDialogWithCenteredClose(LinearLayout box) {
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        addCenteredDialogBottomAction(box, getString(R.string.close), () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        dialogRef[0] = createStablePositionedDialog(box, DOCUMENT_TOOLBAR_POPUP_Y_DP, false, false);
        dialogRef[0].show();
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


    void addDialogBottomActions(LinearLayout box, String primaryText, Runnable primaryAction) {
        addDialogBottomActions(box, null, null, primaryText, primaryAction);
    }

    private void addDialogBottomActions(LinearLayout box,
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
            secondary.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
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
        primary.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        primary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        primary.setPadding(dpToPx(18), 0, dpToPx(18), 0);
        actions.addView(primary, new LinearLayout.LayoutParams(0, dpToPx(46), 1f));
        box.addView(actions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        primary.setOnClickListener(v -> primaryAction.run());
    }


    void loadFromIntent(Intent intent) {
        pageLoader().loadFromIntent(intent);
    }

    String applyReaderThemeCss(String html) {
        if ("EPUB".equals(docType) && epubFixedLayoutLike) {
            // Fixed-layout EPUB pages already received only the centering CSS in
            // prepareEpubHtml(). Do not add reader-theme/reflow CSS here.
            return html != null ? html : "";
        }
        int linkColor = ThemeManager.getInstance(this).getActiveTheme().getLinkColor();

        // Keep CSS minimal.  Do not force user-select/caret/handle behavior here:
        // WebView must own selection geometry or selection handles drift while the
        // document scrolls.
        String css = "<style id=\"textview-reader-theme\">" +
                "html,body{background:" + cssColor(readerBg) + " !important;color:" + cssColor(readerFg) +
                " !important;}" +
                "a{color:" + cssColor(linkColor) + " !important;}" +
                "table,td,th{border-color:" + cssColor(readerLine) + " !important;}" +
                "html,body{max-width:100%;overflow-x:hidden;}" +
                "*{box-sizing:border-box;}" +
                "body,.page,p,div,span,td,th,li{max-width:100%;overflow-wrap:anywhere;word-break:break-word;}" +
                "img,svg,video,.word-img,.textbox,table{max-width:100%;}" +
                "pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;}" +
                buildDocumentFontCss() +
                "</style>";
        if (html == null) return css;
        int head = html.toLowerCase(Locale.US).indexOf("</head>");
        if (head >= 0) return html.substring(0, head) + css + html.substring(head);
        return css + html;
    }



    private String cssColor(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    private String cssQuote(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }

    void showPage(int page, int direction) {
        pageDisplay().showPage(page, direction);
    }

    void runDocumentSlideInAnimation() {
        pageDisplay().runSlideInAnimation();
    }

    void addBookmarkForCurrentPage() {
        if (filePath == null || pages.isEmpty()) return;

        String excerpt = String.format(Locale.getDefault(), "%s %d / %d", docType, currentPage + 1, pages.size());
        for (Bookmark b : bookmarkManager.getBookmarksForFile(filePath)) {
            if (b.getCharPosition() == currentPage) {
                b.setLineNumber(currentPage + 1);
                b.setPageNumber(currentPage + 1);
                b.setTotalPages(pages.size());
                b.setExcerpt(excerpt);
                b.setEndPosition(currentPage);
                bookmarkManager.updateBookmark(b);
                ShortToast.show(this, getString(R.string.bookmark_updated));
                return;
            }
        }

        Bookmark bookmark = new Bookmark(filePath, fileName, currentPage, currentPage + 1, excerpt);
        bookmark.setPageNumber(currentPage + 1);
        bookmark.setTotalPages(pages.size());
        bookmark.setEndPosition(currentPage);
        bookmarkManager.addBookmark(bookmark);
        ShortToast.show(this, getString(R.string.bookmark_saved));
    }

    private void showBookmarksDialog() {
        new DocumentBookmarkDialogController(this).showBookmarksDialog();
    }

    int documentPageCount() {
        return pages.size();
    }

    boolean hasValidCurrentDocumentPage() {
        return !pages.isEmpty() && currentPage >= 0 && currentPage < pages.size();
    }

    String documentPageHtml(int index) {
        if (index < 0 || index >= pages.size()) return "";
        return pages.get(index).html;
    }

    void saveReadingState() {
        if (filePath == null || prefs == null || !prefs.getAutoSavePosition()) return;
        ReaderState state = new ReaderState(filePath);
        state.setCharPosition(currentPage);
        state.setScrollY(0);
        state.setPageNumber(currentPage + 1);
        state.setTotalPages(pages.size());
        state.setFileLength(fileSizeBytes(filePath));
        state.setEncoding(docType + "_PAGE");
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

    void loadEpubPages(File file) throws Exception {
        closeResourceZip();
        resourceZip = new ZipFile(file);
        epubFixedLayoutLike = detectEpubFixedLayoutLike(resourceZip);
        epubHasDocumentFont = detectEpubDeclaredFont(resourceZip);
        List<String> spine = findEpubSpinePaths(resourceZip);
        if (spine.isEmpty()) spine = findEpubHtmlEntries(resourceZip);

        for (String path : spine) {
            ZipEntry entry = resourceZip.getEntry(path);
            if (entry == null || entry.isDirectory()) continue;
            String html = readZipEntryString(resourceZip, entry);
            String title = titleFromHtml(html);
            if (title.isEmpty()) title = fileNameFromPath(path);
            pages.add(new Page(title, epubFixedLayoutLike ? html : prepareEpubHtml(html), path));
        }
    }


    private boolean detectEpubFixedLayoutLike(@NonNull ZipFile zip) {
        return DocumentArchiveUtils.detectEpubFixedLayoutLike(zip);
    }

    private boolean detectEpubDeclaredFont(@NonNull ZipFile zip) {
        return DocumentArchiveUtils.detectEpubDeclaredFont(zip);
    }

    private String detectWordDefaultFontFamily(@NonNull ZipFile zip) {
        return DocumentWordUtils.detectDefaultFontFamily(zip);
    }

    void loadWordPages(File file) throws Exception {
        closeResourceZip();
        resourceZip = new ZipFile(file);
        wordRelationships.clear();
        loadWordRelationships(resourceZip);
        wordDefaultFontFamily = detectWordDefaultFontFamily(resourceZip);
        wordHasDocumentFont = wordDefaultFontFamily != null && !wordDefaultFontFamily.trim().isEmpty();
        ZipEntry documentXml = resourceZip.getEntry("word/document.xml");
        if (documentXml == null) throw new IOException("Unsupported Word file");

        Document doc;
        try (InputStream is = resourceZip.getInputStream(documentXml)) {
            doc = secureDocumentBuilder().parse(is);
        }

        Node body = firstNodeByLocalName(doc, "body");
        if (body == null) body = doc.getDocumentElement();

        List<String> pageBodies = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int paragraphCount = 0;

        NodeList children = body.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String local = child.getLocalName();
            String name = child.getNodeName();
            boolean paragraph = "p".equals(local) || "w:p".equals(name);
            boolean table = "tbl".equals(local) || "w:tbl".equals(name);
            if (!paragraph && !table) continue;

            if (table) {
                current.append(renderWordTable(child));
                paragraphCount += 4;
            } else {
                current.append(renderWordParagraph(child));
                paragraphCount++;
            }

            if (containsWordPageBreak(child) || paragraphCount >= WORD_PARAGRAPHS_PER_PAGE) {
                if (current.length() > 0) pageBodies.add(current.toString());
                current.setLength(0);
                paragraphCount = 0;
            }
        }

        if (current.length() > 0 || pageBodies.isEmpty()) pageBodies.add(current.toString());

        for (int i = 0; i < pageBodies.size(); i++) {
            pages.add(new Page("Word page " + (i + 1), wrapWordPage(pageBodies.get(i), i + 1), null));
        }
    }

    int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    WebResourceResponse interceptLocalResource(Uri uri) {
        if (uri == null) return null;
        if (!LOCAL_HOST.equalsIgnoreCase(uri.getHost())) {
            return new WebResourceResponse("text/plain", "UTF-8",
                    new ByteArrayInputStream(new byte[0]));
        }
        String path = uri.getPath();
        if (path == null) return null;

        if (path.startsWith(FONT_PREFIX)) {
            return interceptSelectedDocumentFont();
        }

        if (resourceZip == null) return null;

        String zipPath;
        if (path.startsWith(EPUB_PREFIX)) {
            zipPath = path.substring(EPUB_PREFIX.length());
        } else if (path.startsWith(WORD_PREFIX)) {
            zipPath = path.substring(1);
        } else {
            return null;
        }

        try {
            zipPath = URLDecoder.decode(zipPath, "UTF-8");
        } catch (Exception ignored) {}
        zipPath = normalizeZipPath(zipPath);

        ZipEntry entry = resourceZip.getEntry(zipPath);
        if (entry == null || entry.isDirectory()) return null;
        try {
            byte[] data;
            try (InputStream is = resourceZip.getInputStream(entry)) {
                data = readAllBytes(is);
            }
            return new WebResourceResponse(
                    mimeForPath(zipPath),
                    "UTF-8",
                    new ByteArrayInputStream(data));
        } catch (IOException e) {
            return null;
        }
    }

    String prepareFixedLayoutEpubHtml(String html) {
        if (html == null) html = "";
        int[] viewport = extractFixedLayoutViewportSize(html);
        String css;
        if (viewport[0] > 0 && viewport[1] > 0) {
            int[] margins = computeFixedLayoutCenterMarginsCssPx(viewport[0], viewport[1]);
            int leftRight = margins[0];
            int topMargin = margins[1];
            int bottomMargin = margins[2];
            int minWidth = viewport[0] + (leftRight * 2);
            int minHeight = viewport[1] + topMargin + bottomMargin;
            css = "<style id=\"textview-fixed-layout-center\">"
                    + "html{margin:0 !important;padding:0 !important;width:auto !important;"
                    + "min-width:" + minWidth + "px !important;min-height:" + minHeight + "px !important;"
                    + "background:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                    + "body{width:" + viewport[0] + "px !important;min-width:" + viewport[0] + "px !important;"
                    + "height:" + viewport[1] + "px !important;min-height:" + viewport[1] + "px !important;"
                    + "margin:" + topMargin + "px " + leftRight + "px " + bottomMargin + "px " + leftRight + "px !important;"
                    + "padding:0 !important;box-sizing:border-box !important;position:relative !important;"
                    + "overflow:visible !important;background:transparent !important;}"
                    + "body>img:only-child,body>svg:only-child{display:block;margin:0 auto;}"
                    + "</style>";
        } else {
            css = "<style id=\"textview-fixed-layout-center\">"
                    + "html{margin:0 !important;padding:0 !important;width:100vw !important;height:100vh !important;"
                    + "display:flex !important;align-items:center !important;justify-content:center !important;"
                    + "background:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                    + "body{margin:0 !important;padding:0 !important;flex:0 0 auto;box-sizing:border-box !important;}"
                    + "body>img:only-child,body>svg:only-child{display:block;margin:0 auto;}"
                    + "</style>";
        }
        return injectIntoHtmlHead(html, css);
    }

    private int[] computeFixedLayoutCenterMarginsCssPx(int pageWidthCssPx, int pageHeightCssPx) {
        return computeFixedLayoutCenterMarginsCssPx(pageWidthCssPx, pageHeightCssPx, false);
    }

    private int[] computeFixedLayoutCenterMarginsCssPx(int pageWidthCssPx, int pageHeightCssPx, boolean applyFindOffset) {
        int[] result = new int[]{0, 0, 0};
        if (pageWidthCssPx <= 0 || pageHeightCssPx <= 0 || webView == null) return result;
        int viewWidthPx = webView.getWidth() - webView.getPaddingLeft() - webView.getPaddingRight();
        int viewHeightPx = webView.getHeight() - webView.getPaddingTop() - webView.getPaddingBottom();
        if (viewWidthPx <= 0 || viewHeightPx <= 0) return result;

        float pageScale = viewWidthPx / (float) pageWidthCssPx;
        if (pageScale <= 0f || Float.isNaN(pageScale) || Float.isInfinite(pageScale)) pageScale = 1f;
        int leftRight = Math.max(0, Math.round(((viewWidthPx / pageScale) - pageWidthCssPx) / 2f));
        int topBottom = Math.max(0, Math.round(((viewHeightPx / pageScale) - pageHeightCssPx) / 2f));
        int topMargin = topBottom;
        int bottomMargin = topBottom;

        if (applyFindOffset) {
            // Fixed-layout EPUB Find uses an overlay panel.  The page should not
            // merely move slightly; its visible top edge should begin below the
            // overlay so the search panel does not cover the page header/content.
            int requiredVisualTopPx = getFixedLayoutFindOverlayBottomPx();
            int currentVisualTopPx = Math.max(0, Math.round(topMargin * pageScale));
            if (requiredVisualTopPx > currentVisualTopPx) {
                int cssDown = Math.max(0, (int) Math.ceil((requiredVisualTopPx - currentVisualTopPx) / pageScale));
                topMargin += cssDown;
                bottomMargin = Math.max(0, bottomMargin - cssDown);
            }
        }

        result[0] = leftRight;
        result[1] = topMargin;
        result[2] = bottomMargin;
        return result;
    }

    private int getFixedLayoutFindOverlayBottomPx() {
        int fallbackGap = dpToPx(8f);
        if (documentSearchOverlayContainer == null
                || documentSearchOverlayContainer.getVisibility() != View.VISIBLE) {
            return fallbackGap;
        }

        int overlayHeight = documentSearchOverlayContainer.getHeight();
        if (overlayHeight <= 0 && documentSearchOverlayContainer.getChildCount() > 0) {
            View child = documentSearchOverlayContainer.getChildAt(0);
            int availableWidth = documentSearchOverlayContainer.getWidth()
                    - documentSearchOverlayContainer.getPaddingLeft()
                    - documentSearchOverlayContainer.getPaddingRight();
            if (availableWidth <= 0 && webView != null) {
                availableWidth = webView.getWidth()
                        - documentSearchOverlayContainer.getPaddingLeft()
                        - documentSearchOverlayContainer.getPaddingRight();
            }
            if (availableWidth > 0) {
                int widthSpec = View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST);
                int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                child.measure(widthSpec, heightSpec);
                overlayHeight = child.getMeasuredHeight()
                        + documentSearchOverlayContainer.getPaddingTop()
                        + documentSearchOverlayContainer.getPaddingBottom();
            }
        }

        return Math.max(fallbackGap, overlayHeight + fallbackGap);
    }

    void setFixedLayoutFindOffsetActive(boolean active) {
        fixedLayoutFindOffsetActive = active;
        applyFixedLayoutFindOffsetCssIfNeeded();
    }

    void applyFixedLayoutFindOffsetCssIfNeeded() {
        if (!"EPUB".equals(docType) || !epubFixedLayoutLike || webView == null) return;
        if (activityDestroyed) return;
        if (!fixedLayoutFindOffsetActive) {
            evaluateFixedLayoutCssJavascript(
                    "(function(){var s=document.getElementById('textview-fixed-layout-find-offset');if(s&&s.parentNode){s.parentNode.removeChild(s);}})();");
            return;
        }
        if (currentPage < 0 || currentPage >= pages.size()) return;
        Page page = pages.get(currentPage);
        int[] viewport = extractFixedLayoutViewportSize(page.html);
        if (viewport[0] <= 0 || viewport[1] <= 0) return;

        int[] margins = computeFixedLayoutCenterMarginsCssPx(viewport[0], viewport[1], true);
        int leftRight = margins[0];
        int topMargin = margins[1];
        int bottomMargin = margins[2];
        int minWidth = viewport[0] + (leftRight * 2);
        int minHeight = viewport[1] + topMargin + bottomMargin;
        String css = "html{margin:0 !important;padding:0 !important;width:auto !important;"
                + "min-width:" + minWidth + "px !important;min-height:" + minHeight + "px !important;"
                + "background:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                + "body{width:" + viewport[0] + "px !important;min-width:" + viewport[0] + "px !important;"
                + "height:" + viewport[1] + "px !important;min-height:" + viewport[1] + "px !important;"
                + "margin:" + topMargin + "px " + leftRight + "px " + bottomMargin + "px " + leftRight + "px !important;"
                + "padding:0 !important;box-sizing:border-box !important;position:relative !important;"
                + "overflow:visible !important;background:transparent !important;}";
        String js = "(function(){var css='" + cssQuote(css) + "';"
                + "var s=document.getElementById('textview-fixed-layout-find-offset');"
                + "if(!s){s=document.createElement('style');s.id='textview-fixed-layout-find-offset';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent=css;})();";
        evaluateFixedLayoutCssJavascript(js);
    }

    private void evaluateFixedLayoutCssJavascript(String js) {
        if (webView == null || js == null || js.isEmpty()) return;
        WebSettings settings = webView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        webView.evaluateJavascript(js, value -> {
            if (!activityDestroyed && webView != null && restoreJavascriptOff
                    && "EPUB".equals(docType) && epubFixedLayoutLike) {
                webView.getSettings().setJavaScriptEnabled(false);
            }
        });
    }

    private int[] extractFixedLayoutViewportSize(String html) {
        int[] result = new int[]{0, 0};
        if (html == null) return result;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?is)<meta[^>]+name\\s*=\\s*['\\\"]viewport['\\\"][^>]+content\\s*=\\s*['\\\"]([^'\\\"]*)['\\\"]")
                    .matcher(html);
            if (!m.find()) return result;
            String content = m.group(1);
            java.util.regex.Matcher w = java.util.regex.Pattern.compile("(?i)(?:^|[,;\\s])width\\s*=\\s*([0-9]{2,5})").matcher(content);
            java.util.regex.Matcher h = java.util.regex.Pattern.compile("(?i)(?:^|[,;\\s])height\\s*=\\s*([0-9]{2,5})").matcher(content);
            if (w.find()) result[0] = Integer.parseInt(w.group(1));
            if (h.find()) result[1] = Integer.parseInt(h.group(1));
        } catch (Throwable ignored) {
            result[0] = 0;
            result[1] = 0;
        }
        return result;
    }

    private String injectIntoHtmlHead(String html, String injection) {
        if (html == null) html = "";
        if (injection == null || injection.isEmpty()) return html;
        String lower = html.toLowerCase(Locale.ROOT);
        int headEnd = lower.indexOf("</head>");
        if (headEnd >= 0) return html.substring(0, headEnd) + injection + html.substring(headEnd);
        java.util.regex.Matcher headStart = java.util.regex.Pattern.compile("(?i)<head[^>]*>").matcher(html);
        if (headStart.find()) {
            int insert = headStart.end();
            return html.substring(0, insert) + injection + html.substring(insert);
        }
        int htmlStartEnd = lower.indexOf("<html");
        if (htmlStartEnd >= 0) {
            int tagEnd = html.indexOf('>', htmlStartEnd);
            if (tagEnd >= 0) return html.substring(0, tagEnd + 1) + "<head>" + injection + "</head>" + html.substring(tagEnd + 1);
        }
        return "<!doctype html><html><head>" + injection + "</head><body>" + html + "</body></html>";
    }

    private String prepareEpubHtml(String html) {
        if (html == null) html = "";
        if (epubFixedLayoutLike) {
            // Preserve the fixed-layout page geometry, but center the fixed page in
            // the available WebView area instead of leaving it pinned to the top.
            return prepareFixedLayoutEpubHtml(html);
        }
        String css = "<style>" +
                "html,body{margin:0;padding:0;background:#121212;color:#e8eaed;}" +
                "body{line-height:1.55;padding:22px;box-sizing:border-box;}" +
                "img,svg,video{max-width:100%;height:auto;}" +
                "a{color:#8ab4f8;}" +
                "pre{white-space:pre-wrap;}" +
                "</style>";
        String viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">";
        if (html.toLowerCase(Locale.ROOT).contains("<head")) {
            return html.replaceFirst("(?i)<head[^>]*>", "$0" + viewport + css);
        }
        return "<!doctype html><html><head>" + viewport + css + "</head><body>" + html + "</body></html>";
    }

    private String wrapWordPage(String body, int pageNumber) {
        return "<!doctype html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=yes\">" +
                "<style>" +
                "html,body{margin:0;padding:0;background:#202124;overflow-x:hidden;-webkit-text-size-adjust:100%;max-width:100%;}" +
                "body{font-family:Arial,Helvetica,sans-serif;}" +
                "*{box-sizing:border-box;max-width:100%;}" +
                ".page{background:#fff;color:#111;margin:12px auto;padding:26px 24px;width:calc(100% - 24px);max-width:816px;" +
                "min-height:92vh;box-shadow:0 2px 14px rgba(0,0,0,.35);overflow-x:hidden;box-sizing:border-box;}" +
                ".pageNo{color:#777;text-align:right;font-size:12px;margin-bottom:14px;}" +
                "p{margin:0 0 8px 0;line-height:1.28;font-size:15.5px;white-space:normal;overflow-wrap:anywhere;word-break:break-word;}" +
                "div,span,li,td,th{overflow-wrap:anywhere;word-break:break-word;}" +
                "pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;}" +
                ".textbox{border:1px solid #ddd;background:#fff;margin:8px 0;padding:8px;box-sizing:border-box;overflow:hidden;}" +
                ".word-img,img,svg,video{max-width:100%;height:auto;display:block;margin:8px auto;}" +
                "table{width:100%;max-width:100%;table-layout:fixed;border-collapse:collapse;margin:10px 0;}" +
                "td,th{border:1px solid #777;padding:5px;vertical-align:top;min-width:0;}" +
                "b,strong{font-weight:700;}i,em{font-style:italic;}u{text-decoration:underline;}" +
                "</style></head><body><div class=\"page\"><div class=\"pageNo\">Word page " + pageNumber +
                "</div>" + body + "</div></body></html>";
    }

    private String renderWordParagraph(Node p) {
        return DocumentWordUtils.renderParagraph(p, wordRelationships, LOCAL_HOST);
    }

    private String renderWordTable(Node table) {
        return DocumentWordUtils.renderTable(table, wordRelationships, LOCAL_HOST);
    }

    private void loadWordRelationships(ZipFile zip) {
        wordRelationships.clear();
        wordRelationships.putAll(DocumentWordUtils.loadRelationships(zip));
    }

    private boolean containsWordPageBreak(Node node) {
        return DocumentWordUtils.containsPageBreak(node);
    }

    private List<String> findEpubSpinePaths(ZipFile zip) {
        return DocumentArchiveUtils.findEpubSpinePaths(zip);
    }

    private List<String> findEpubHtmlEntries(ZipFile zip) {
        return DocumentArchiveUtils.findEpubHtmlEntries(zip);
    }

    private String readZipEntryString(ZipFile zip, ZipEntry entry) throws IOException {
        return DocumentArchiveUtils.readZipEntryString(zip, entry);
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        return DocumentArchiveUtils.readAllBytes(is);
    }

    private DocumentBuilder secureDocumentBuilder() throws Exception {
        return DocumentArchiveUtils.secureDocumentBuilder();
    }

    private Node firstNodeByLocalName(Document doc, String localName) {
        return DocumentArchiveUtils.firstNodeByLocalName(doc, localName);
    }

    private String titleFromHtml(String html) {
        return DocumentArchiveUtils.titleFromHtml(html);
    }

    private String fileNameFromPath(String path) {
        return DocumentArchiveUtils.fileNameFromPath(path);
    }


    String htmlToText(String raw) {
        return DocumentArchiveUtils.htmlToText(raw);
    }

    String parentPath(String path) {
        return DocumentArchiveUtils.parentPath(path);
    }

    private String normalizeZipPath(String path) {
        return DocumentArchiveUtils.normalizeZipPath(path);
    }

    String mimeForPath(String path) {
        return DocumentArchiveUtils.mimeForPath(path);
    }

}
