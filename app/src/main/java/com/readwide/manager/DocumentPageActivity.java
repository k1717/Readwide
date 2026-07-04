package com.readwide.manager;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.widget.TextViewCompat;

import com.readwide.manager.adapter.BookmarkFolderAdapter;
import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.model.Theme;
import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.FontManager;
import com.readwide.manager.util.HwpTextExtractor;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.TapZoneMath;
import com.readwide.manager.util.MarkdownVisualPageMath;
import com.readwide.manager.util.ThemeManager;
import com.readwide.manager.document.doc.DocLegacyLayoutExtractor;
import com.readwide.manager.document.render.FixedHtmlRenderer;
import com.readwide.manager.document.render.RenderedDocument;
import com.readwide.manager.document.render.RenderedPage;

import org.json.JSONObject;

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
 * Page-style viewer for EPUB, Markdown, and OOXML Word files.
 *
 * EPUB: renders each original spine XHTML/HTML document inside WebView, preserving
 * the EPUB's markup/CSS/images as much as Android WebView allows.
 *
 * Markdown: renders .md/.markdown as a dedicated document WebView page.  This path
 * intentionally does not use the TXT exact-page model, so Markdown tables and
 * rendered syntax can use normal HTML layout while the plain TXT reader remains exact.
 *
 * Word: renders OOXML document content as formatted HTML pages. Explicit Word page
 * breaks are respected; when a document has no page breaks, the content is split
 * into readable page-sized chunks instead of flattened into plain text.
 */
public class DocumentPageActivity extends AppCompatActivity implements TtsHost, ReaderDialogStyleHost {
    public static final String EXTRA_FILE_PATH = "file_path";
    /** When true, begin read-aloud automatically once the document is ready. */
    public static final String EXTRA_AUTOSTART_TTS = "autostart_tts";
    public static final String EXTRA_FILE_URI = "file_uri";
    public static final String EXTRA_JUMP_TO_PAGE = "jump_page";
    public static final String EXTRA_MARKDOWN_SOURCE_OFFSET = "markdown_source_offset";
    public static final String EXTRA_CONTENT_ANCHOR_JSON = "content_anchor_json";

    static final String LOCAL_HOST = "readwide.local";
    static final String EPUB_PREFIX = "/epub/";
    private static final String WORD_PREFIX = "/word/";
    private static final String FONT_PREFIX = "/font/";
    private static final String DOCUMENT_FONT_DEFAULT = "document_default";
    private static final String FONT_OPTION_SYSTEM_CURRENT = "system_current";
    private static final int WORD_PARAGRAPHS_PER_PAGE = 28;
    private static final int HWP_PARAGRAPHS_PER_PAGE = 34;
    private static final int HWP_TARGET_CHARS_PER_PAGE = 3600;
    // Match toolbar-triggered document popups to the Go to Page bottom offset.
    static final int DOCUMENT_TOOLBAR_POPUP_Y_DP = 74;
    private static final long DOCUMENT_CHROME_TRANSITION_MS = 145L;
    // Cap per in-document WebView resource (EPUB/Word image/font/entry) so a crafted
    // oversized entry can't blow up memory while being served. See interceptLocalResource.
    private static final long MAX_DOCUMENT_RESOURCE_BYTES = 64L * 1024L * 1024L;

    Toolbar toolbar;
    View documentAppBar;
    View documentBottomChrome;
    View documentNavBarSpacer;
    boolean documentChromeVisible = true;
    WebView webView;
    View ttsFloatingCard;
    android.widget.ImageButton ttsFloatingPlayPause;
    android.widget.ImageButton ttsFloatingStop;
    LinearLayout loadingBox;
    ProgressBar progressBar;
    TextView progressText;
    TextView pageStatus;
    TextView topPageStatus;
    TextView prevButton;
    TextView nextButton;
    TextView searchButton;
    TextView pageButton;
    TextView bookmarkButton;
    TextView moreButton;
    com.readwide.manager.controller.ReaderToolbarController documentToolbarController;
    SeekBar documentPageSeekBar;
    boolean documentPageSeekBarUserTracking = false;
    int lastMarkdownMaxScrollYPx = 0;
    int lastMarkdownCurrentRawScrollYPx = 0;
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

    // Submit to the document executor defensively: after onDestroy it is shut
    // down, and a late task would otherwise throw RejectedExecutionException.
    public void submitDocumentTask(@NonNull Runnable task) {
        if (activityDestroyed || executor.isShutdown()) return;
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor shut down between check and submit; nothing to do.
        }
    }
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
    int markdownVisualCurrentPage = 0;
    int markdownVisualTotalPages = 1;
    int lastStableMarkdownViewportHeightPx = 0;
    int lastStableMarkdownContentHeightPx = 0;
    int lastExpandedDocumentTopChromeHeightPx = 0;
    int lastExpandedDocumentBottomChromeHeightPx = 0;
    int pendingMarkdownRestoreScrollY = -1;
    int pendingMarkdownRestorePage = -1;
    int pendingMarkdownRestoreSourceOffset = -1;
    String markdownSourceText = "";
    volatile int lastMarkdownSourceOffset = 0;
    volatile int lastMarkdownSourceLine = 1;
    volatile String lastMarkdownAnchorText = "";
    String pendingDocumentRestoreAnchorJson = "";
    volatile String lastDocumentContentAnchorJson = "";
    boolean snapDocumentPageTopAfterLoad = false;
    int pendingSlideDirection = 0;
    private int wordSwipeTouchSlop = 0;
    private int markdownSelectionCancelSlopPx = 0;
    private boolean markdownNativeLongPressCanceledForGesture = false;
    private float wordSwipeStartX = 0f;
    private float wordSwipeStartY = 0f;
    private boolean wordSwipeTriggered = false;
    private boolean wordSwipeMovedEnoughForParentDisallow = false;
    boolean pageTurnInFlight = false;
    private GestureDetector documentGestureDetector;
    private boolean documentDoubleTapResetSequence = false;
    private boolean documentTapPagingSequence = false;
    private int armedDocumentEdgeDirection = 0;
    private long armedDocumentEdgeTimeMs = 0L;
    private boolean wordGestureStartedAtLeftEdge = true;
    private boolean wordGestureStartedAtRightEdge = true;
    volatile boolean wordSelectionActive = false;
    volatile boolean activityDestroyed = false;

    // SAF picker for importing a custom .ttf/.otf font into the document font list.
    // The result Uri is handled by importDocumentFontFromUri.
    private final ActivityResultLauncher<String[]> documentFontImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importDocumentFontFromUri(uri); });
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
    android.app.Dialog documentSearchDialog = null;
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


    private GradientDrawable loadingBoxBackground() {
        return LoadingWindowTheme.boxDrawable(this, LoadingWindowTheme.reader(readerBg, readerFg));
    }

    void updateLoadingIndicatorTheme() {
        if (loadingBox != null) loadingBox.setBackground(loadingBoxBackground());
        if (progressBar != null) {
            progressBar.setBackgroundColor(Color.TRANSPARENT);
            progressBar.setIndeterminateTintList(ColorStateList.valueOf(readerFg));
        }
        if (progressText != null) {
            progressText.setTextColor(readerFg);
            progressText.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    void showLoadingWindow() {
        updateLoadingIndicatorTheme();
        if (loadingBox != null) {
            loadingBox.setVisibility(View.VISIBLE);
            loadingBox.bringToFront();
        }
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (progressText != null) {
            progressText.setText(getString(R.string.loading));
            progressText.setVisibility(View.VISIBLE);
        }
    }

    void hideLoadingWindow() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (progressText != null) progressText.setVisibility(View.GONE);
        if (loadingBox != null) loadingBox.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If read-aloud is running (or paused) in this viewer, coming back to
        // the foreground reclaims the remote-command bridge, mirroring the text
        // reader's register-on-resume so notification buttons keep landing here.
        if (documentTtsController != null && documentTtsController.isActive()) {
            TtsPlaybackBridge.register(this);
        }
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
                    + "|docFontSize=" + ((("EPUB".equals(docType) || "Markdown".equals(docType)) && prefs != null)
                    ? prefs.getFontSize() : PrefsManager.DEFAULT_FONT_SIZE)
                + "|epubThemeColors=" + (("EPUB".equals(docType) && prefs != null)
                    ? prefs.getEpubForceReaderThemeColors() : false);
        }
        String backgroundImagePath = theme.getBackgroundImagePath();
        return theme.getId()
                + "|fg=" + theme.getTextColor()
                + "|bg=" + theme.getBackgroundColor()
                + "|toolbar=" + theme.getToolbarColor()
                + "|link=" + theme.getLinkColor()
                + "|img=" + (backgroundImagePath != null ? backgroundImagePath : "")
                + "|alpha=" + theme.getBackgroundImageAlpha()
                + "|docFontSize=" + ((("EPUB".equals(docType) || "Markdown".equals(docType)) && prefs != null)
                ? prefs.getFontSize() : PrefsManager.DEFAULT_FONT_SIZE)
                + "|epubThemeColors=" + (("EPUB".equals(docType) && prefs != null)
                ? prefs.getEpubForceReaderThemeColors() : false);
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
        if (isDocumentSearchActiveOnCurrentPage()) {
            restoreDocumentScrollAfterThemeRefresh = false;
            return;
        }
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
        int bodyBg = readerBg;
        int statusBg = documentChromeVisible ? readerToolbarBg : bodyBg;
        getWindow().setStatusBarColor(statusBg);
        getWindow().setNavigationBarColor(bodyBg);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(bodyBg);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkColor(statusBg));
            controller.setAppearanceLightNavigationBars(!isDarkColor(bodyBg));
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (!isDarkColor(bodyBg)) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!isDarkColor(statusBg)) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        applyDocumentChromeFillColors();
    }

    void applyDocumentChromeFillColors() {
        int topFillerBg = readerToolbarBg;
        if (documentAppBar != null) {
            documentAppBar.setBackgroundColor(topFillerBg);
        }
        if (toolbar != null) {
            toolbar.setBackgroundColor(readerToolbarBg);
        }
        if (topPageStatus != null) {
            topPageStatus.setBackgroundColor(readerToolbarBg);
        }
        if (documentNavBarSpacer != null) {
            documentNavBarSpacer.setBackgroundColor(readerBg);
        }
    }

    boolean isDarkColor(int color) {
        return UiColorUtils.isDarkColor(color);
    }

    int blendColors(int base, int overlay, float overlayAlpha) {
        return UiColorUtils.blendColors(base, overlay, overlayAlpha);
    }


    private GradientDrawable documentBottomChromeBackground(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        float r = dpToPx(12);
        bg.setCornerRadii(new float[]{
                r, r,   // top-left
                r, r,   // top-right
                0, 0,   // bottom-right
                0, 0    // bottom-left
        });
        return bg;
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
        applyDocumentChromeFillColors();
        if (documentNavBarSpacer != null) {
            documentNavBarSpacer.setBackgroundColor(readerBg);
            androidx.core.view.ViewCompat.requestApplyInsets(documentNavBarSpacer);
        }
        if (documentSearchOverlayContainer != null) documentSearchOverlayContainer.setBackgroundColor(Color.TRANSPARENT);
        if (appbar != null) appbar.setBackgroundColor(readerToolbarBg);
        if (bottom != null) bottom.setBackground(documentBottomChromeBackground(readerPanel));
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
        if (topPageStatus != null) {
            topPageStatus.setTextColor(readerFg);
            topPageStatus.setBackgroundColor(readerToolbarBg);
        }
        if (documentPageSeekBar != null) tintSeekBar(documentPageSeekBar);
        updateLoadingIndicatorTheme();
        TextView[] buttons = {prevButton, nextButton, searchButton, pageButton, bookmarkButton,
                findViewById(R.id.btn_screen_rotation), findViewById(R.id.btn_document_settings),
                findViewById(R.id.btn_document_tts), moreButton};
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
        if (documentTtsController != null) {
            documentTtsController.release();
            documentTtsController = null;
        }
        TtsPlaybackBridge.unregister(this);
        saveReadingState();
        clearDocumentSearchState(true);
        destroyDocumentWebView();
        closeResourceZip();
        pages.clear();
        wordRelationships.clear();
        if (documentToolbarController != null) {
            documentToolbarController.release();
            documentToolbarController = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private void cancelMarkdownPendingWebTouch(@NonNull MotionEvent source) {
        if (webView == null || !isMarkdownDocument()) return;
        webView.cancelLongPress();
        MotionEvent cancel = MotionEvent.obtain(
                source.getDownTime(),
                source.getEventTime(),
                MotionEvent.ACTION_CANCEL,
                source.getX(),
                source.getY(),
                source.getMetaState());
        try {
            webView.onTouchEvent(cancel);
        } catch (Throwable ignored) {
            // Native WebView selection state differs by Android System WebView
            // version. Failing to deliver this synthetic cancel should not break
            // the page turn; the DOM selection clear below is still attempted.
        } finally {
            cancel.recycle();
        }
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

    private boolean handleFastDocumentTapPaging(@NonNull MotionEvent e) {
        int eventAction = e.getActionMasked();
        if (eventAction == MotionEvent.ACTION_CANCEL) {
            documentTapPagingSequence = false;
            return false;
        }
        if (!isPagedWebDocument() || webView == null || prefs == null
                || ("EPUB".equals(docType) && epubFixedLayoutLike)
                || !prefs.getDocumentTapPagingEnabled(docType)
                || documentPageCount() <= 1 || pageTurnInFlight) {
            documentTapPagingSequence = false;
            return false;
        }

        if (eventAction == MotionEvent.ACTION_DOWN) {
            wordSwipeStartX = e.getX();
            wordSwipeStartY = e.getY();
            documentTapPagingSequence = getDocumentTapPagingAction(e) != TapZoneMath.ACTION_MENU;
            if (isMarkdownDocument() && documentTapPagingSequence) {
                // Do not consume DOWN: Markdown still needs normal WebView touch
                // delivery for selection and vertical scroll if this gesture is
                // later cancelled as a page tap.  The stale-selection bug is
                // caused by consuming UP for the page turn, so we send WebView a
                // synthetic CANCEL immediately before turning the page instead.
                webView.cancelLongPress();
            }
            return false;
        }

        if (!documentTapPagingSequence) return false;

        if (eventAction == MotionEvent.ACTION_MOVE) {
            if (Math.abs(e.getX() - wordSwipeStartX) > wordSwipeTouchSlop
                    || Math.abs(e.getY() - wordSwipeStartY) > wordSwipeTouchSlop) {
                documentTapPagingSequence = false;
            }
            return false;
        }

        if (eventAction != MotionEvent.ACTION_UP) return false;
        boolean stillTap = !wordSelectionActive && !wordSwipeTriggered
                && Math.abs(e.getX() - wordSwipeStartX) <= wordSwipeTouchSlop
                && Math.abs(e.getY() - wordSwipeStartY) <= wordSwipeTouchSlop;
        int action = stillTap ? getDocumentTapPagingAction(e) : TapZoneMath.ACTION_MENU;
        documentTapPagingSequence = false;
        if (action == TapZoneMath.ACTION_PREVIOUS) {
            if (isMarkdownDocument()) {
                cancelMarkdownPendingWebTouch(e);
                clearMarkdownWebSelection();
            }
            turnDocumentPageByTap(-1);
            return true;
        }
        if (action == TapZoneMath.ACTION_NEXT) {
            if (isMarkdownDocument()) {
                cancelMarkdownPendingWebTouch(e);
                clearMarkdownWebSelection();
            }
            turnDocumentPageByTap(1);
            return true;
        }
        return false;
    }

    private int getDocumentTapPagingAction(@NonNull MotionEvent e) {
        if (!isPagedWebDocument() || webView == null || prefs == null) return TapZoneMath.ACTION_MENU;
        if (!prefs.getDocumentTapPagingEnabled(docType)) return TapZoneMath.ACTION_MENU;
        if (documentPageCount() <= 1 || pageTurnInFlight) return TapZoneMath.ACTION_MENU;
        // The chrome (top app bar / bottom controls) floats over the WebView, so a
        // tap landing on a visible chrome bar must not also page the document
        // underneath it. Skip paging when the tap falls within a shown bar.
        if (tapIntersectsVisibleChrome(e)) return TapZoneMath.ACTION_MENU;
        return TapZoneMath.actionForTap(
                e.getX(),
                e.getY(),
                webView.getWidth(),
                webView.getHeight(),
                true,
                true,
                prefs.getTapZoneMode(),
                prefs.getTapLeadingZonePercent(),
                prefs.getTapTrailingZonePercent());
    }

    /** True if the touch point lies inside a currently-visible chrome bar. */
    private boolean tapIntersectsVisibleChrome(@NonNull MotionEvent e) {
        if (!documentChromeVisible || webView == null) return false;
        // Touch coordinates are WebView-local; convert to screen space to compare
        // against the chrome bars, which are siblings overlaying the WebView.
        int[] webLoc = new int[2];
        webView.getLocationOnScreen(webLoc);
        float screenX = e.getX() + webLoc[0];
        float screenY = e.getY() + webLoc[1];
        return viewContainsScreenPoint(documentAppBar, screenX, screenY)
                || viewContainsScreenPoint(documentBottomChrome, screenX, screenY);
    }

    private boolean viewContainsScreenPoint(View view, float screenX, float screenY) {
        if (view == null || view.getVisibility() != View.VISIBLE
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return screenX >= loc[0] && screenX <= loc[0] + view.getWidth()
                && screenY >= loc[1] && screenY <= loc[1] + view.getHeight();
    }

    private boolean handleDocumentTapPaging(@NonNull MotionEvent e) {
        int action = getDocumentTapPagingAction(e);
        if (action == TapZoneMath.ACTION_PREVIOUS) {
            if (isMarkdownDocument()) {
                cancelMarkdownPendingWebTouch(e);
                clearMarkdownWebSelection();
            }
            turnDocumentPageByTap(-1);
            return true;
        }
        if (action == TapZoneMath.ACTION_NEXT) {
            if (isMarkdownDocument()) {
                cancelMarkdownPendingWebTouch(e);
                clearMarkdownWebSelection();
            }
            turnDocumentPageByTap(1);
            return true;
        }
        return false;
    }

    private void turnDocumentPageByTap(int direction) {
        if (direction == 0 || documentPageCount() <= 1) return;
        if (isMarkdownDocument()) {
            pageMarkdownBy(direction);
            return;
        }
        int target = currentPage + direction;
        if (target >= 0 && target < pages.size()) {
            showPage(target, direction);
        }
    }

    private void toggleDocumentChrome() {
        setDocumentChromeVisible(!documentChromeVisible);
    }

    private void setDocumentChromeVisible(boolean visible) {
        if (documentChromeVisible == visible
                && documentAppBar != null
                && documentBottomChrome != null
                && topPageStatus != null
                && ((visible && documentAppBar.getVisibility() == View.VISIBLE
                && documentBottomChrome.getVisibility() == View.VISIBLE
                && topPageStatus.getVisibility() == View.INVISIBLE)
                || (!visible && documentAppBar.getVisibility() == View.GONE
                && documentBottomChrome.getVisibility() == View.GONE
                && topPageStatus.getVisibility() == View.VISIBLE))) {
            return;
        }

        documentChromeVisible = visible;
        applyDocumentChromeFillColors();
        if (visible) {
            // Document chrome is an overlay, like the TXT reader. Showing the
            // toolbar/bottom controls must never resize or translate the WebView.
            if (documentAppBar != null && documentAppBar.getVisibility() != View.VISIBLE) {
                documentAppBar.setVisibility(View.VISIBLE);
            }
            if (toolbar != null && toolbar.getVisibility() != View.VISIBLE) {
                toolbar.setVisibility(View.VISIBLE);
            }
            if (topPageStatus != null && topPageStatus.getVisibility() != View.INVISIBLE) {
                topPageStatus.setVisibility(View.INVISIBLE);
            }
            if (pageStatus != null && pageStatus.getVisibility() != View.VISIBLE) {
                pageStatus.setVisibility(View.VISIBLE);
            }
            if (documentBottomChrome != null && documentBottomChrome.getVisibility() != View.VISIBLE) {
                documentBottomChrome.setVisibility(View.VISIBLE);
            }
        } else {
            // Collapsed state hides only the toolbar/bottom overlay. The compact
            // page indicator is a separate overlay, not part of the toolbar mask,
            // and the WebView remains fixed underneath both states.
            if (documentAppBar != null && documentAppBar.getVisibility() != View.GONE) {
                documentAppBar.setVisibility(View.GONE);
            }
            if (topPageStatus != null && topPageStatus.getVisibility() != View.VISIBLE) {
                topPageStatus.setVisibility(View.VISIBLE);
            }
            if (documentBottomChrome != null && documentBottomChrome.getVisibility() != View.GONE) {
                documentBottomChrome.setVisibility(View.GONE);
            }
        }
        applyDocumentSystemBarColors();
        androidx.core.view.ViewCompat.requestApplyInsets(findViewById(R.id.document_root));
        androidx.core.view.ViewCompat.requestApplyInsets(topPageStatus);
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
            webView.removeJavascriptInterface("ReadwideSelectionBridge");
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
        setupDocumentPageSeekBar();
        prevButton.setOnClickListener(v -> {
            if (isMarkdownDocument()) pageMarkdownBy(-1);
            else if (currentPage > 0) showPage(currentPage - 1, -1);
        });
        nextButton.setOnClickListener(v -> {
            if (isMarkdownDocument()) pageMarkdownBy(1);
            else if (currentPage < pages.size() - 1) showPage(currentPage + 1, 1);
        });
        if (searchButton != null) searchButton.setOnClickListener(v -> showDocumentSearchDialog());
        if (pageButton != null) pageButton.setOnClickListener(v -> showGoToPageDialog());
        bookmarkButton.setOnClickListener(v -> showBookmarksDialog());
        View rotationButton = findViewById(R.id.btn_screen_rotation);
        if (rotationButton != null) {
            rotationButton.setOnClickListener(v ->
                    com.readwide.manager.util.ScreenOrientationToggle.toggle(this));
        }
        updateRotationButtonIcon();
        View documentSettingsButton = findViewById(R.id.btn_document_settings);
        if (documentSettingsButton != null) {
            documentSettingsButton.setOnClickListener(v ->
                    startActivity(new Intent(this, SettingsActivity.class)));
        }
        View documentTtsButton = findViewById(R.id.btn_document_tts);
        if (documentTtsButton != null) {
            // Same entry point as the More-dialog row; the row stays for
            // muscle memory, this button is the direct path (mirroring the
            // text reader's bottom toolbar).
            documentTtsButton.setOnClickListener(v -> showDocumentTtsDialog());
        }
        updateDocumentTtsButtonVisibility();
        if (moreButton != null) {
            moreButton.setOnClickListener(v -> showMoreDialog());
        }
    }

    private void updateRotationButtonIcon() {
        com.readwide.manager.util.ScreenOrientationToggle.applyButtonIcon(
                this,
                findViewById(R.id.btn_screen_rotation),
                R.drawable.ic_bottom_screen_rotation,
                R.drawable.ic_bottom_screen_portrait);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotationButtonIcon();
        applyDocumentThemeToViews();
        View documentRoot = findViewById(R.id.document_root);
        if (documentRoot != null) androidx.core.view.ViewCompat.requestApplyInsets(documentRoot);
    }

    private void setupDocumentPageSeekBar() {
        if (documentPageSeekBar == null) return;
        tintSeekBar(documentPageSeekBar);
        documentPageSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int total = Math.max(1, documentPageCount());
                int safe = Math.max(0, Math.min(total - 1, progress));
                if (isMarkdownDocument()) {
                    markdownVisualCurrentPage = safe;
                } else {
                    currentPage = Math.max(0, Math.min(Math.max(0, pages.size() - 1), safe));
                }
                updateDocumentPageStatusViews(false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                documentPageSeekBarUserTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int total = Math.max(1, documentPageCount());
                int target = Math.max(0, Math.min(total - 1, seekBar.getProgress()));
                documentPageSeekBarUserTracking = false;
                if (isMarkdownDocument()) {
                    scrollMarkdownToVisualPage(target, false);
                } else if (!pages.isEmpty()) {
                    showPage(target, Integer.compare(target, currentPage));
                } else {
                    updateDocumentPageStatusViews();
                }
            }
        });
        updateDocumentPageStatusViews();
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
        markdownSelectionCancelSlopPx = Math.max(1, dpToPx(2));
        documentGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (handleDocumentTapPaging(e)) {
                    return true;
                }
                toggleDocumentChrome();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                // Inside a page-turn zone a double tap must page, not zoom: a
                // quick double tap to flip pages must not be captured as a zoom
                // toggle. Mirrors the PDF viewer. Non-fixed-layout documents
                // never reach here for side taps (their fast tap-paging path
                // consumes the sequence first and bypasses this detector), so in
                // practice this covers fixed-layout EPUB, whose side taps fall
                // through to the gesture detector. Outside the page-turn zones
                // the double tap still toggles zoom below.
                int tapAction = getDocumentTapPagingAction(e);
                if (tapAction == TapZoneMath.ACTION_PREVIOUS || tapAction == TapZoneMath.ACTION_NEXT) {
                    // Consume the rest of this tap sequence so the WebView's own
                    // double-tap zoom does not also fire.
                    documentDoubleTapResetSequence = true;
                    turnDocumentPageByTap(tapAction == TapZoneMath.ACTION_PREVIOUS ? -1 : 1);
                    clearDocumentEdgeArm();
                    if (webView != null) {
                        webView.postDelayed(() -> {
                            if (!activityDestroyed) documentDoubleTapResetSequence = false;
                        }, 360);
                    }
                    return true;
                }
                documentDoubleTapResetSequence = true;
                if (webView != null && webView.canZoomOut()) {
                    // Already zoomed in (pinch or a previous double-tap): reset to fit.
                    resetDocumentZoom();
                } else if (webView != null) {
                    // At fit size: zoom in a couple of native steps for a quick magnify.
                    webView.zoomIn();
                    webView.zoomIn();
                }
                clearDocumentEdgeArm();
                if (webView != null) {
                    webView.postDelayed(() -> {
                        if (!activityDestroyed) documentDoubleTapResetSequence = false;
                    }, 360);
                }
                return true;
            }
        });

        webView.setOnTouchListener((v, event) -> {
            if (handleFastDocumentTapPaging(event)) {
                resetWordSwipeTracking();
                clearDocumentEdgeArm();
                return true;
            }
            if (!documentTapPagingSequence && handleDocumentTapGesture(event)) {
                return true;
            }

            if (!isPagedWebDocument() || documentPageCount() <= 1 || pageTurnInFlight) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    wordSwipeStartX = event.getX();
                    wordSwipeStartY = event.getY();
                    wordSwipeTriggered = false;
                    wordSwipeMovedEnoughForParentDisallow = false;
                    markdownNativeLongPressCanceledForGesture = false;
                    wordGestureStartedAtLeftEdge = !webView.canScrollHorizontally(-1);
                    wordGestureStartedAtRightEdge = !webView.canScrollHorizontally(1);
                    webView.removeCallbacks(checkWordSelectionAfterScrollRunnable);
                    webView.removeCallbacks(releasePageTurnRunnable);
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (wordSelectionActive) return false;
                    float dx = event.getX() - wordSwipeStartX;
                    float dy = event.getY() - wordSwipeStartY;
                    // Markdown uses WebView's native selection.  Do not replace
                    // that long-click pipeline, because synthetic replay breaks
                    // handles and page anchors on some WebView builds.  Instead,
                    // cancel the pending native long-press as soon as the finger
                    // becomes a scroll/page gesture.  This threshold is deliberately
                    // much smaller than the normal scroll slop: slow Markdown
                    // scrolling was still producing stray word selections before
                    // it crossed Android's default long-press boundary.
                    if (isMarkdownDocument()
                            && !markdownNativeLongPressCanceledForGesture
                            && (Math.abs(dx) > markdownSelectionCancelSlopPx
                            || Math.abs(dy) > markdownSelectionCancelSlopPx)) {
                        webView.cancelLongPress();
                        markdownNativeLongPressCanceledForGesture = true;
                    }
                    if (!wordSwipeMovedEnoughForParentDisallow
                            && Math.abs(dx) > wordSwipeTouchSlop
                            && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        wordSwipeMovedEnoughForParentDisallow = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (!wordSwipeTriggered && shouldTurnDocumentPageBySwipe(event)) {
                        wordSwipeTriggered = true;
                        if (isMarkdownDocument()) webView.cancelLongPress();
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
                        if (isMarkdownDocument()) webView.cancelLongPress();
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
        markdownNativeLongPressCanceledForGesture = false;
        documentTapPagingSequence = false;
        wordGestureStartedAtLeftEdge = true;
        wordGestureStartedAtRightEdge = true;
        if (webView != null && webView.getParent() != null) {
            webView.getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    private boolean isPagedWebDocument() {
        return "Word".equals(docType) || "HWP".equals(docType) || "EPUB".equals(docType) || "Markdown".equals(docType);
    }

    void clearDocumentEdgeArm() {
        armedDocumentEdgeDirection = 0;
        armedDocumentEdgeTimeMs = 0L;
    }

    private boolean shouldTurnDocumentPageBySwipe(@NonNull MotionEvent event) {
        if (activityDestroyed || wordSelectionActive || webView == null || documentPageCount() <= 1 || pageTurnInFlight) return false;

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
        // WebView-backed document pages now snap immediately. The old EPUB
        // transition-effect setting was removed because slide/fade animation is
        // no longer part of the document-page model. Keep this method as the
        // single gate used by older call sites, but always report no visual slide.
        return 0;
    }

    private void turnDocumentPageBySwipe(int direction) {
        if (webView == null || pageTurnInFlight) return;
        if (isMarkdownDocument()) {
            pageMarkdownBy(direction);
            return;
        }
        if (direction > 0 && currentPage < pages.size() - 1) {
            showPage(currentPage + 1, 1);
        } else if (direction < 0 && currentPage > 0) {
            showPage(currentPage - 1, -1);
        }
    }

    private void checkWordSelectionAfterScroll() {
        if (activityDestroyed || !"Word".equals(docType) || webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__readwideClearSelectionIfOffscreen&&window.__readwideClearSelectionIfOffscreen());}catch(e){return false;}})()",
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
                        + "if(window.__readwideSelectionCleanupInstalled){return true;}"
                        + "window.__readwideSelectionCleanupInstalled=true;"
                        + "function sel(){return window.getSelection?window.getSelection():null;}"
                        + "function active(){var s=sel();return !!(s&&!s.isCollapsed&&s.rangeCount>0&&String(s).length>0);}"
                        + "function notify(){try{if(window.ReadwideSelectionBridge){window.ReadwideSelectionBridge.onSelectionChanged(active());}}catch(e){}}"
                        + "window.__readwideClearSelectionIfOffscreen=function(){"
                        + "try{var s=sel();if(!s||s.isCollapsed||s.rangeCount===0||String(s).length===0){notify();return false;}"
                        + "var r=s.getRangeAt(0);var rects=Array.prototype.slice.call(r.getClientRects()).filter(function(x){return x&&x.width>0&&x.height>0;});"
                        + "if(!rects.length){s.removeAllRanges();notify();return true;}"
                        + "var w=window.innerWidth||document.documentElement.clientWidth||0;var h=window.innerHeight||document.documentElement.clientHeight||0;var m=8;"
                        + "var visible=rects.some(function(x){return x.bottom>=m&&x.top<=h-m&&x.right>=m&&x.left<=w-m;});"
                        + "if(!visible){s.removeAllRanges();notify();return true;}notify();return false;}catch(e){return false;}};"
                        + "document.addEventListener('selectionchange',function(){setTimeout(notify,0);},true);"
                        + "document.addEventListener('touchend',function(){setTimeout(notify,70);},true);"
                        + "document.addEventListener('mouseup',function(){setTimeout(notify,70);},true);"
                        + "window.addEventListener('scroll',function(){clearTimeout(window.__readwideScrollCleanupTimer);window.__readwideScrollCleanupTimer=setTimeout(window.__readwideClearSelectionIfOffscreen,80);},{passive:true});"
                        + "setTimeout(notify,120);return true;}catch(e){return false;}})()",
                null);
    }

    /**
     * The only JavaScript-callable bridge exposed to document WebView content.
     *
     * Threat model: JavaScript is disabled by default and turned on only for Word
     * documents -- whose HTML the app generates itself -- and fixed-layout EPUB,
     * which may carry untrusted scripts. Even when reachable, this interface has a
     * single method that takes a boolean and only flips an internal "word is
     * selected" flag: no file or content access, no reflection, no navigation, no
     * eval, no data returned. On targetSdk 17 or higher only methods annotated with
     * JavascriptInterface are exposed, so nothing else such as getClass is callable.
     * The WebView also disables file access, content access, and DOM storage, and
     * serves only readwide.local resources bound to the current document archive --
     * see interceptLocalResource. Keep this surface boolean-only: do not add String
     * or Object parameters, or methods that touch the filesystem or app state.
     */
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
        if (documentSupportsTts()) {
            box.addView(makeDialogActionRow(getString(R.string.tts_title), () -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                showDocumentTtsDialog();
            }));
        }
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
        return Math.max(0, requestedBottomBoundaryPx);
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
        int bottomToolbarHeightPx = 0;
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
        if (!("EPUB".equals(docType) || "Markdown".equals(docType)) || prefs == null) return 100;
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
        if (isMarkdownDocument()) {
            lastStableMarkdownContentHeightPx = 0;
            lastStableMarkdownViewportHeightPx = 0;
        }
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

    void launchDocumentFontImport() {
        try {
            documentFontImportLauncher.launch(new String[] {
                    "font/ttf", "font/otf", "font/sfnt",
                    "application/x-font-ttf", "application/x-font-otf",
                    "application/font-sfnt", "application/vnd.ms-opentype",
                    "application/octet-stream"
            });
        } catch (Exception e) {
            ShortToast.show(this, localizedText(
                    "Could not open the file picker.",
                    "파일 선택기를 열 수 없습니다."));
        }
    }

    private void importDocumentFontFromUri(Uri uri) {
        ShortToast.show(this, localizedText("Importing font\u2026", "글꼴 가져오는 중\u2026"));
        submitDocumentTask(() -> {
            String imported;
            try {
                imported = FontManager.getInstance().importFont(this, uri);
            } catch (Throwable t) {
                imported = null;
            }
            final String result = imported;
            runOnUiThread(() -> {
                if (activityDestroyed) return;
                if (result != null && !result.trim().isEmpty()) {
                    documentFontController().applyImportedDocumentFont(result);
                    ShortToast.show(this, localizedText("Font added", "글꼴을 추가했습니다"));
                } else {
                    ShortToast.show(this, localizedText(
                            "Could not import the font file.",
                            "글꼴 파일을 가져오지 못했습니다."));
                }
            });
        });
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

    boolean isDocumentSearchActiveOnCurrentPage() {
        return documentSearchController().isDocumentSearchActiveOnCurrentPage();
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

    String applyDocumentSearchMarkupForDisplay(String html, int pageIndex) {
        return documentSearchController().applyDocumentSearchMarkupForDisplay(html, pageIndex);
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
        addInfoRow(box, getString(R.string.bottom_page), String.format(Locale.getDefault(), "%d / %d", currentDisplayDocumentPageNumber(), documentPageCount()));
        showFileInfoDialogWithCenteredClose(box);
    }

    private void showGoToPageDialog() {
        if (pages.isEmpty()) return;
        if (isMarkdownDocument()) updateMarkdownVisualPageModel(false);
        final int totalPagesForDialog = Math.max(1, documentPageCount());
        final int currentPageForDialog = Math.max(0, Math.min(totalPagesForDialog - 1, currentDisplayDocumentPageIndex()));

        LinearLayout box = makeDialogBox();
        box.addView(makeDialogTitle(getString(R.string.page_move)));

        TextView label = new TextView(this);
        label.setTextColor(dialogFg());
        label.setTextSize(17f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setGravity(android.view.Gravity.CENTER);
        label.setText(formatPageMoveLabel(currentPageForDialog + 1, totalPagesForDialog));
        label.setPadding(0, dpToPx(4), 0, dpToPx(8));
        box.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar slider = new SeekBar(this);
        slider.setMax(Math.max(0, totalPagesForDialog - 1));
        slider.setProgress(currentPageForDialog);
        tintSeekBar(slider);
        box.addView(slider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)));

        TextView hint = new TextView(this);
        hint.setText(isMarkdownDocument()
                ? localizedText("Rendered page based on the current layout.", "현재 표시 레이아웃 기준 페이지입니다.")
                : getString(R.string.exact_page_number));
        hint.setTextColor(blendColors(dialogBg(), dialogFg(), 0.78f));
        hint.setTextSize(13f);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(0, dpToPx(4), 0, dpToPx(6));
        box.addView(hint);

        EditText input = makeDialogInput("1 - " + totalPagesForDialog);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setGravity(android.view.Gravity.CENTER);
        input.setText(String.valueOf(currentPageForDialog + 1));
        input.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(dpToPx(132), dpToPx(52));
        inputLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        box.addView(input, inputLp);

        final int[] pending = new int[]{currentPageForDialog};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                pending[0] = progress;
                label.setText(formatPageMoveLabel(progress + 1, totalPagesForDialog));
                input.setText(String.valueOf(progress + 1));
                input.setSelection(input.getText().length());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (isMarkdownDocument()) scrollMarkdownToVisualPage(pending[0], false);
                else showPage(pending[0], Integer.compare(pending[0], currentPage));
            }
        });

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        addCenteredDialogBottomAction(box, getString(R.string.go), () -> {
            try {
                int target = Integer.parseInt(input.getText().toString().trim());
                if (target < 1 || target > totalPagesForDialog) {
                    ShortToast.show(this, getString(R.string.page_range_error, totalPagesForDialog));
                    return;
                }
                if (isMarkdownDocument()) scrollMarkdownToVisualPage(target - 1, false);
                else showPage(target - 1, Integer.compare(target - 1, currentPage));
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
        // A (re)load replaces the pages list, so the read-aloud text buffer and
        // any running playback belong to the previous document.
        resetDocumentTts();
        pageLoader().loadFromIntent(intent);
        documentTtsIntegration().onLoadFromIntent(intent);
    }

    // ---- Read-aloud (TTS) for the document viewer -------------------------
    //
    // The playback controller is the same ReaderTtsController the text reader
    // uses; this activity supplies it a DocumentTtsTextSource (a page-indexed
    // plain-text buffer over `pages`) through the TtsHost interface below.
    // Markdown-as-document is supported too: it has a single rendered page, so
    // the text source is one buffer, and read-aloud follows the spoken position
    // by mapping it back to a raw-source offset and scrolling there
    // (approximate following - see DocumentTtsIntegrationController),
    // since Markdown's visual paging (markdownVisualCurrentPage over one long
    // page) has no page list to advance.

    ReaderTtsController documentTtsController;
    DocumentTtsTextSource documentTtsTextSource;
    private DocumentTtsIntegrationController documentTtsIntegrationController;
    private final android.os.Handler documentTtsHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private ReaderDialogStyleController documentDialogStyleController;
    private int documentDialogSnapshotBg = Color.rgb(18, 18, 18);
    private int documentDialogSnapshotFg = Color.rgb(232, 234, 237);

    ReaderTtsController documentTts() {
        if (documentTtsController == null) {
            documentTtsController = new ReaderTtsController(this);
        }
        return documentTtsController;
    }

    /** Read-aloud integration (dialog/autostart/build/Markdown following). */
    DocumentTtsIntegrationController documentTtsIntegration() {
        if (documentTtsIntegrationController == null) {
            documentTtsIntegrationController = new DocumentTtsIntegrationController(this);
        }
        return documentTtsIntegrationController;
    }

    boolean documentSupportsTts() {
        return !pages.isEmpty();
    }

    /** Stop playback and drop the text buffer; the next open rebuilds both. */
    void resetDocumentTts() {
        documentTtsIntegration().reset();
    }

    /** Entry point from the toolbar button and the More dialog. */
    void showDocumentTtsDialog() {
        documentTtsIntegration().showDialogEntry();
    }

    // ---- TtsHost ----------------------------------------------------------

    @Override
    @NonNull
    public AppCompatActivity ttsHostActivity() {
        return this;
    }

    @Override
    public PrefsManager ttsHostPrefs() {
        return prefs;
    }

    @Override
    @NonNull
    public ReaderDialogStyleController ttsHostDialogStyler() {
        if (documentDialogStyleController == null) {
            documentDialogStyleController = new ReaderDialogStyleController(this);
        }
        return documentDialogStyleController;
    }

    @Override
    public int ttsHostDpToPx(int dp) {
        return dpToPx(dp);
    }

    @Override
    public TtsTextSource ttsTextSource() {
        return documentTtsTextSource;
    }

    @Override
    public boolean isTtsHostDestroyed() {
        return activityDestroyed;
    }

    @Override
    public String ttsHostFilePath() {
        return filePath;
    }

    @Override
    @NonNull
    public android.os.Handler ttsHostHandler() {
        return documentTtsHandler;
    }

    @Override
    public boolean isTtsTextFullyResident() {
        // The whole buffer exists as soon as the source does, so cross-page
        // prefetch is always available here.
        return documentTtsTextSource != null;
    }

    @Override
    public boolean isTtsTextTemporarilyUnavailable() {
        return documentTtsIntegration().isTextBuilding();
    }

    @Override
    public int ttsDisplayedCurrentPageNumber() {
        return currentDisplayDocumentPageNumber();
    }

    @Override
    public int ttsDisplayedTotalPageCount() {
        return documentPageCount();
    }

    @Override
    public int ttsCurrentCharPosition() {
        return documentTtsTextSource != null
                ? documentTtsTextSource.getCurrentCharPosition() : 0;
    }

    @Override
    public void ttsHostPageBy(int direction) {
        if (direction == 0) return;
        if (isMarkdownDocument()) {
            // Markdown is one long WebView page scrolled by visual page; there is
            // no page list to advance. Follow by mapping the spoken position back
            // to a source offset and scrolling there (approximate following).
            documentTtsIntegration().followToCurrentSpokenPosition();
            return;
        }
        if (pages.isEmpty()) return;
        int target = Math.max(0, Math.min(pages.size() - 1, currentPage + direction));
        if (target != currentPage) {
            showPage(target, Integer.signum(direction));
        }
    }

    @Override
    public void ttsJumpToAbsoluteCharPosition(int charPosition) {
        if (documentTtsTextSource == null) return;
        if (isMarkdownDocument()) {
            // A jump (resume, boundary catch-up) both repositions the speech
            // anchor - so the next (re)start actually speaks from here, not the
            // top - and scrolls the view to match.
            String plain = documentTtsTextSource.getTextContent();
            int len = plain != null ? plain.length() : 0;
            markdownTtsAnchorCharPosition = Math.max(0, Math.min(len, charPosition));
            documentTtsIntegration().followToSourceOffsetForChar(charPosition);
            return;
        }
        if (pages.isEmpty()) return;
        int target = Math.max(0, Math.min(pages.size() - 1,
                documentTtsTextSource.pageIndexForChar(charPosition)));
        if (target != currentPage) {
            showPage(target, Integer.signum(target - currentPage));
        }
    }

    /**
     * Scrolls the Markdown viewer to approximately the currently spoken position.
     * Uses the TTS controller's current char position in the plain-text buffer.
     */
    /**
     * Per-segment callback from {@link DocumentTtsTextSource}; drives Markdown's
     * approximate following and speech-anchor tracking in the integration
     * controller. No-op for paged documents.
     */
    void onDocumentTtsSegmentSpoken(int charPosition, int segmentEndChar) {
        documentTtsIntegration().onSegmentSpoken(charPosition, segmentEndChar);
    }

    /**
     * Read-aloud's position in the plain-text buffer for Markdown (which has no
     * page list to derive it from): -1 = unset (fresh start reads from the
     * current scroll position), otherwise the end of the last spoken segment or
     * the target of the last resume jump. Package-private for
     * {@link DocumentTtsTextSource#getCurrentCharPosition}.
     */
    int markdownTtsAnchorCharPosition = -1;

    @Override
    public void ttsJumpToAbsoluteCharPosition(int charPosition, int displayPage, int totalPages) {
        // The char position is authoritative; the saved display page is only a
        // hint and the buffer maps positions to pages exactly.
        ttsJumpToAbsoluteCharPosition(charPosition);
    }

    @Override
    public void ttsUpdateFloatingCard() {
        TtsFloatingCardController.update(this, ttsFloatingCard, ttsFloatingPlayPause,
                ttsFloatingCardControls());
    }

    /** Binds the floating playback card's buttons and drag/tap handling. */
    void setupTtsFloatingCard() {
        TtsFloatingCardController.setup(this, ttsFloatingCard, ttsFloatingPlayPause,
                ttsFloatingStop, ttsFloatingCardControls());
    }

    private TtsFloatingCardController.Controls ttsFloatingCardControls() {
        return new TtsFloatingCardController.Controls() {
            @Override public boolean isActive() {
                return documentTtsController != null && documentTtsController.isActive();
            }
            @Override public boolean isPaused() {
                return documentTtsController != null && documentTtsController.isPaused();
            }
            @Override public void togglePlayPause() {
                if (documentTtsController == null) return;
                if (documentTtsController.isPaused()) documentTtsController.resumePlayback();
                else documentTtsController.pausePlayback();
                ttsUpdateFloatingCard();
            }
            @Override public void stop() {
                if (documentTtsController != null) documentTtsController.stop(true);
                ttsUpdateFloatingCard();
            }
        };
    }

    @Override
    public void ttsStopAutoPageTurn() {
        // No auto page-turn feature in the document viewer.
    }

    @Override
    public void ttsHandlePlaybackCommand(@NonNull String action) {
        documentTts().handlePlaybackCommand(action);
    }

    @Override
    @NonNull
    public String ttsHostKind() {
        return TtsPlaybackService.HOST_DOCUMENT;
    }

    // ---- ReaderDialogStyleHost --------------------------------------------

    @Override
    @NonNull
    public AppCompatActivity dialogStyleHostActivity() {
        return this;
    }

    @Override
    public int dialogStyleDpToPx(int dp) {
        return dpToPx(dp);
    }

    @Override
    @NonNull
    public ThemeManager dialogStyleThemeManager() {
        return ThemeManager.getInstance(this);
    }

    @Override
    public int dialogSnapshotBackgroundColor() {
        return documentDialogSnapshotBg;
    }

    @Override
    public int dialogSnapshotTextColor() {
        return documentDialogSnapshotFg;
    }

    @Override
    public void setDialogSnapshotColors(int backgroundColor, int textColor) {
        documentDialogSnapshotBg = backgroundColor;
        documentDialogSnapshotFg = textColor;
    }

    @Override
    public BookmarkManager dialogStyleBookmarkManager() {
        // The styler's bookmark dialog is only reachable from the text reader.
        return null;
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
        boolean forceEpubThemeColors = "EPUB".equals(docType)
                && prefs != null
                && prefs.getEpubForceReaderThemeColors();
        String epubThemeColorCss = forceEpubThemeColors
                ? "body,body *:not(img):not(svg):not(video):not(canvas){color:" + cssColor(readerFg) + " !important;}"
                + "body *:not(pre):not(code):not(th):not(td):not(table):not(img):not(svg):not(video):not(canvas){background-color:transparent !important;}"
                + "body a,body a *{color:" + cssColor(linkColor) + " !important;}"
                + "body table,body th,body td{border-color:" + cssColor(readerLine) + " !important;color:" + cssColor(readerFg) + " !important;}"
                + "body th{background-color:" + cssColor(readerPanel) + " !important;}"
                + "body pre,body code{color:" + cssColor(readerFg) + " !important;background-color:" + cssColor(readerPanel) + " !important;}"
                + "body blockquote{color:" + cssColor(readerFg) + " !important;border-color:" + cssColor(readerLine) + " !important;}"
                + "body *{text-decoration-color:" + cssColor(readerFg) + " !important;}"
                : "";
        String markdownThemeCss = isMarkdownDocument()
                ? ".markdown-doc code,.markdown-doc pre{background:" + cssColor(readerPanel) + " !important;}"
                + ".markdown-doc blockquote{border-left-color:" + cssColor(readerLine) + " !important;color:" + cssColor(readerFg) + " !important;}"
                + ".markdown-doc th{background:" + cssColor(readerPanel) + " !important;}"
                + ".markdown-doc tr:nth-child(even) td{background:" + cssColor(blendColors(readerBg, readerFg, isDarkColor(readerBg) ? 0.045f : 0.035f)) + " !important;}"
                + ".markdown-doc .md-diagram{background:" + cssColor(readerPanel) + " !important;border-color:" + cssColor(readerLine) + " !important;}"
                : "";
        // Reflowable EPUB: images must never exceed the visible viewport. Books
        // routinely ship covers and illustrations wider/taller than a phone or
        // tablet screen with their own sizing CSS, so width alone is not enough:
        // an image capped to 100% width can still be several screens tall (the
        // reported tablet case). Cap both axes and keep the aspect ratio
        // (height:auto for raster images with width/height attributes,
        // object-fit for anything force-sized by book CSS). Injected after the
        // book's stylesheets, so equal-specificity book rules lose; !important
        // on the caps also beats the common `img{max-width:none}` reset.
        // Fixed-layout EPUB never reaches this method (early return above).
        String epubImageFitCss = "EPUB".equals(docType)
                ? "img,svg,video{max-width:100% !important;max-height:98vh !important;height:auto;object-fit:contain;}"
                + "svg{width:auto;}"
                : "";
        String documentSearchCss =
                ".rw-document-search-hit{background-color:#ffeb3b !important;color:#111 !important;border-radius:2px;padding:0 1px;}"
                + ".rw-document-search-current{background-color:#ff9800 !important;color:#111 !important;}";
        String renderedPaperCss = isRenderedContentAnchorDocument()
                ? "body.rw-rendered-doc{background:" + cssColor(readerBg) + " !important;color:#111 !important;}"
                + "body.rw-rendered-doc .rw-page{background:#fff !important;color:#111 !important;}"
                + "body.rw-rendered-doc .rw-page-inner{background:#fff !important;color:#111 !important;}"
                + "body.rw-rendered-doc .rw-table,body.rw-rendered-doc .rw-table td{border-color:#777;}"
                + "body.rw-rendered-doc,body.rw-rendered-doc *{word-break:normal !important;overflow-wrap:normal !important;}"
                + "body.rw-rendered-doc .rw-p,body.rw-rendered-doc .rw-list-content{overflow-wrap:break-word !important;}"
                + "body.rw-rendered-doc .rw-table td{min-width:0 !important;overflow:visible !important;overflow-wrap:break-word !important;word-break:normal !important;}"
                + "body.rw-rendered-doc .rw-table td *{max-width:100% !important;overflow-wrap:break-word !important;word-break:normal !important;}"
                + "body.rw-rendered-doc .rw-table .rw-p{max-width:100% !important;white-space:normal !important;overflow-wrap:break-word !important;word-break:normal !important;}"
                + "body.rw-rendered-doc a{color:#0645ad !important;}"
                : "";
        String css = "<style id=\"textview-reader-theme\">" +
                "html,body{background:" + cssColor(readerBg) + " !important;color:" + cssColor(readerFg) +
                " !important;}" +
                "a{color:" + cssColor(linkColor) + " !important;}" +
                "body:not(.rw-rendered-doc) table,body:not(.rw-rendered-doc) td,body:not(.rw-rendered-doc) th{border-color:" + cssColor(readerLine) + " !important;}" +
                "html,body{max-width:100%;overflow-x:hidden;}" +
                "*{box-sizing:border-box;}" +
                "body,.page,p,div,span,td,th,li{max-width:100%;overflow-wrap:anywhere;word-break:break-word;}" +
                "img,svg,video,.word-img,.textbox,table{max-width:100%;}" +
                "pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;}" +
                renderedPaperCss +
                epubImageFitCss +
                epubThemeColorCss +
                markdownThemeCss +
                documentSearchCss +
                buildDocumentFontCss() +
                "</style>";
        if (html == null) return css;
        int head = html.toLowerCase(Locale.US).indexOf("</head>");
        if (head >= 0) return html.substring(0, head) + css + html.substring(head);
        return css + html;
    }



    private String cssColor(int color) {
        return CssUtils.cssColor(color);
    }

    private String cssQuote(String text) {
        return CssUtils.cssQuote(text);
    }


    boolean isRenderedContentAnchorDocument() {
        return ("EPUB".equals(docType) && !epubFixedLayoutLike)
                || "Word".equals(docType)
                || "HWP".equals(docType);
    }

    boolean isDocumentContentAnchorSignature(String signature) {
        return signature != null && signature.startsWith(docType + "_CONTENT_ANCHOR_v1");
    }

    String buildDocumentContentAnchorJson(int pageIndex, int blockIndex, int scrollY, int maxScrollY, String text) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("kind", docType + "_CONTENT_ANCHOR_v1");
            obj.put("docType", docType);
            obj.put("pageIndex", Math.max(0, pageIndex));
            obj.put("blockIndex", Math.max(0, blockIndex));
            obj.put("scrollY", Math.max(0, scrollY));
            obj.put("scrollRatio", maxScrollY > 0 ? Math.max(0.0d, Math.min(1.0d, scrollY / (double) maxScrollY)) : 0.0d);
            obj.put("text", text != null ? text : "");
            return obj.toString();
        } catch (Exception e) {
            return "";
        }
    }

    void installDocumentContentAnchorScript() {
        if (!isRenderedContentAnchorDocument() || webView == null) return;
        evaluateDocumentAnchorJavascript(
                "(function(){try{"
                        + "window.__rwDocBlocks=function(){var raw=Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,th,table'));return raw.filter(function(e){var t=(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim();var r=e.getBoundingClientRect();return t.length>0&&r.height>0&&r.width>0;});};"
                        + "window.__rwDocAnchorAtTop=function(){var blocks=window.__rwDocBlocks();var max=Math.max(0,document.documentElement.scrollHeight-window.innerHeight);if(!blocks.length)return {blockIndex:0,scrollY:window.scrollY||0,maxScrollY:max,text:''};var best=blocks[0],bestIndex=0,threshold=8;for(var i=0;i<blocks.length;i++){var r=blocks[i].getBoundingClientRect();if(r.bottom>=threshold){best=blocks[i];bestIndex=i;break;}if(r.top<=threshold){best=blocks[i];bestIndex=i;}}var text=(best.innerText||best.textContent||'').replace(/\\s+/g,' ').trim();if(text.length>180)text=text.substring(0,180);return {blockIndex:bestIndex,scrollY:window.scrollY||0,maxScrollY:max,text:text};};"
                        + "window.__rwDocScrollToAnchor=function(anchor){var blocks=window.__rwDocBlocks();if(!blocks.length){if(anchor&&typeof anchor.scrollY==='number')window.scrollTo(0,anchor.scrollY);return false;}anchor=anchor||{};var text=(anchor.text||'').replace(/\\s+/g,' ').trim();var idx=parseInt(anchor.blockIndex||0,10)||0;var target=null;if(text.length>=12){var needle=text.length>80?text.substring(0,80):text;for(var i=0;i<blocks.length;i++){var bt=(blocks[i].innerText||blocks[i].textContent||'').replace(/\\s+/g,' ').trim();if(bt.indexOf(needle)>=0){target=blocks[i];break;}}}if(!target){idx=Math.max(0,Math.min(blocks.length-1,idx));target=blocks[idx];}if(target){target.scrollIntoView(true);return true;}return false;};"
                        + "return true;}catch(e){return false;}})()",
                value -> updateDocumentContentAnchorFromWebView());
    }

    void evaluateDocumentAnchorJavascript(String js, android.webkit.ValueCallback<String> callback) {
        if (webView == null || js == null || js.isEmpty()) return;
        WebSettings settings = webView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        webView.evaluateJavascript(js, value -> {
            if (!activityDestroyed && webView != null && restoreJavascriptOff && isRenderedContentAnchorDocument()) {
                webView.getSettings().setJavaScriptEnabled(false);
            }
            if (callback != null) callback.onReceiveValue(value);
        });
    }

    void updateDocumentContentAnchorFromWebView() {
        updateDocumentContentAnchorFromWebView(null);
    }

    void updateDocumentContentAnchorFromWebView(Runnable afterUpdate) {
        if (!isRenderedContentAnchorDocument() || webView == null) {
            if (afterUpdate != null) afterUpdate.run();
            return;
        }
        evaluateDocumentAnchorJavascript(
                "(function(){try{return window.__rwDocAnchorAtTop?window.__rwDocAnchorAtTop():{blockIndex:0,scrollY:window.scrollY||0,maxScrollY:Math.max(0,document.documentElement.scrollHeight-window.innerHeight),text:''};}catch(e){return {blockIndex:0,scrollY:window.scrollY||0,maxScrollY:0,text:''};}})()",
                value -> {
                    try {
                        if (value != null && !value.trim().isEmpty() && !"null".equals(value)) {
                            JSONObject obj = new JSONObject(value);
                            lastDocumentContentAnchorJson = buildDocumentContentAnchorJson(
                                    currentPage,
                                    obj.optInt("blockIndex", 0),
                                    obj.optInt("scrollY", webView != null ? webView.getScrollY() : 0),
                                    obj.optInt("maxScrollY", 0),
                                    obj.optString("text", ""));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (afterUpdate != null) afterUpdate.run();
                    }
                });
    }

    void scheduleDocumentContentAnchorUpdate() {
        if (!isRenderedContentAnchorDocument() || webView == null) return;
        webView.postDelayed(this::updateDocumentContentAnchorFromWebView, 40);
    }

    void restoreDocumentContentAnchorAfterLoadIfNeeded(@NonNull WebView view) {
        if (!isRenderedContentAnchorDocument()) return;
        if (isDocumentSearchActiveOnCurrentPage()) {
            pendingDocumentRestoreAnchorJson = "";
            return;
        }
        final String anchorJson = pendingDocumentRestoreAnchorJson;
        pendingDocumentRestoreAnchorJson = "";
        view.postDelayed(() -> {
            if (activityDestroyed || webView == null || !isRenderedContentAnchorDocument()) return;
            installDocumentContentAnchorScript();
            if (anchorJson != null && !anchorJson.trim().isEmpty()) {
                String escaped = cssQuote(anchorJson);
                evaluateDocumentAnchorJavascript(
                        "(function(){try{var a=JSON.parse('" + escaped + "');if(window.__rwDocScrollToAnchor){return !!window.__rwDocScrollToAnchor(a);}return false;}catch(e){return false;}})()",
                        value -> {
                            boolean ok = "true".equals(value);
                            if (!ok) restoreDocumentContentAnchorFallback(anchorJson);
                            webView.postDelayed(this::updateDocumentContentAnchorFromWebView, 80);
                        });
            } else {
                updateDocumentContentAnchorFromWebView();
            }
        }, 90);
    }

    void restoreDocumentContentAnchorFallback(String anchorJson) {
        if (webView == null || anchorJson == null || anchorJson.trim().isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            int scrollY = obj.optInt("scrollY", -1);
            if (scrollY >= 0) {
                webView.scrollTo(0, Math.max(0, scrollY));
                return;
            }
            double ratio = obj.optDouble("scrollRatio", -1.0d);
            if (ratio >= 0.0d) {
                int maxY = Math.max(0, webView.getContentHeight() * Math.max(1, Math.round(webView.getScale())) - webView.getHeight());
                webView.scrollTo(0, Math.max(0, Math.min(maxY, (int) Math.round(maxY * ratio))));
            }
        } catch (Exception ignored) {}
    }

    boolean isMarkdownDocument() {
        return "Markdown".equals(docType);
    }


    boolean isMarkdownSourceAnchorSignature(String signature) {
        return signature != null && signature.startsWith("Markdown_SOURCE_ANCHOR_v1");
    }

    boolean isMarkdownSourceBookmark(@NonNull Bookmark bookmark) {
        return FileUtils.isMarkdownFile(bookmark.getFileName())
                && isMarkdownSourceAnchorSignature(bookmark.getPageLayoutSignature());
    }

    int clampMarkdownSourceOffset(int offset) {
        if (markdownSourceText == null || markdownSourceText.isEmpty()) return Math.max(0, offset);
        return Math.max(0, Math.min(offset, markdownSourceText.length()));
    }

    int markdownSourceLineForOffset(int offset) {
        if (markdownSourceText == null || markdownSourceText.isEmpty()) return 1;
        int safe = clampMarkdownSourceOffset(offset);
        int line = 1;
        for (int i = 0; i < safe && i < markdownSourceText.length(); i++) {
            if (markdownSourceText.charAt(i) == '\n') line++;
        }
        return line;
    }

    String markdownAnchorTextAround(int offset, boolean before) {
        if (markdownSourceText == null || markdownSourceText.isEmpty()) return "";
        int safe = clampMarkdownSourceOffset(offset);
        if (before) {
            int start = Math.max(0, safe - 96);
            return markdownSourceText.substring(start, safe);
        }
        int end = Math.min(markdownSourceText.length(), safe + 128);
        return markdownSourceText.substring(safe, end);
    }

    String markdownBookmarkExcerpt(int sourceOffset, int visualPage, int totalPages) {
        String after = markdownAnchorTextAround(sourceOffset, false).replace('\n', ' ').trim();
        if (after.length() > 90) after = after.substring(0, 90).trim();
        if (after.isEmpty()) after = String.format(Locale.getDefault(), "Line %d", markdownSourceLineForOffset(sourceOffset));
        return after;
    }

    void installMarkdownSourceAnchorScript() {
        if (!isMarkdownDocument() || webView == null) return;
        evaluateMarkdownAnchorJavascript(
                "(function(){try{"
                        + "window.__rwMdBlocks=function(){return Array.prototype.slice.call(document.querySelectorAll('[data-rw-src-offset]'));};"
                        + "window.__rwMdAnchorAtTop=function(){"
                        + "var blocks=window.__rwMdBlocks();if(!blocks.length)return {offset:0,line:1,text:''};"
                        + "var threshold=10,best=blocks[0];"
                        + "for(var i=0;i<blocks.length;i++){var r=blocks[i].getBoundingClientRect();if(r.bottom>=threshold){best=blocks[i];break;}if(r.top<=threshold){best=blocks[i];}}"
                        + "var text=(best.innerText||best.textContent||'').replace(/\\s+/g,' ').trim();if(text.length>160)text=text.substring(0,160);"
                        + "return {offset:parseInt(best.getAttribute('data-rw-src-offset')||'0',10)||0,line:parseInt(best.getAttribute('data-rw-src-line')||'1',10)||1,text:text};};"
                        + "window.__rwMdScrollToOffset=function(target){"
                        + "var blocks=window.__rwMdBlocks();if(!blocks.length){window.scrollTo(0,0);return false;}"
                        + "target=parseInt(target||0,10)||0;var chosen=blocks[0],chosenOffset=parseInt(chosen.getAttribute('data-rw-src-offset')||'0',10)||0;"
                        + "for(var i=0;i<blocks.length;i++){var off=parseInt(blocks[i].getAttribute('data-rw-src-offset')||'0',10)||0;if(off<=target){chosen=blocks[i];chosenOffset=off;}else{break;}}"
                        + "chosen.scrollIntoView(true);return true;};"
                        + "return true;}catch(e){return false;}})()",
                value -> updateMarkdownSourceAnchorFromWebView());
    }

    void evaluateMarkdownAnchorJavascript(String js, android.webkit.ValueCallback<String> callback) {
        if (webView == null || js == null || js.isEmpty()) {
            if (callback != null) callback.onReceiveValue(null);
            return;
        }
        WebSettings settings = webView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        webView.evaluateJavascript(js, value -> {
            if (!activityDestroyed && webView != null && restoreJavascriptOff && isMarkdownDocument()) {
                webView.getSettings().setJavaScriptEnabled(false);
            }
            if (callback != null) callback.onReceiveValue(value);
        });
    }

    void updateMarkdownSourceAnchorFromWebView() {
        updateMarkdownSourceAnchorFromWebView(null);
    }

    void updateMarkdownSourceAnchorFromWebView(Runnable afterUpdate) {
        if (!isMarkdownDocument() || webView == null) {
            if (afterUpdate != null) afterUpdate.run();
            return;
        }
        evaluateMarkdownAnchorJavascript(
                "(function(){try{return window.__rwMdAnchorAtTop?window.__rwMdAnchorAtTop():{offset:0,line:1,text:''};}catch(e){return {offset:0,line:1,text:''};}})()",
                value -> {
                    try {
                        if (value != null && !value.trim().isEmpty() && !"null".equals(value)) {
                            JSONObject obj = new JSONObject(value);
                            lastMarkdownSourceOffset = clampMarkdownSourceOffset(obj.optInt("offset", 0));
                            lastMarkdownSourceLine = Math.max(1, obj.optInt("line", markdownSourceLineForOffset(lastMarkdownSourceOffset)));
                            lastMarkdownAnchorText = obj.optString("text", "");
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (afterUpdate != null) afterUpdate.run();
                    }
                });
    }

    void scheduleMarkdownSourceAnchorUpdate() {
        if (!isMarkdownDocument() || webView == null) return;
        webView.postDelayed(this::updateMarkdownSourceAnchorFromWebView, 40);
    }

    void scrollMarkdownToSourceOffset(int sourceOffset, boolean fallbackToVisualPage, int fallbackVisualPage) {
        if (!isMarkdownDocument() || webView == null) return;
        final int safeOffset = clampMarkdownSourceOffset(sourceOffset);
        evaluateMarkdownAnchorJavascript(
                "(function(){try{if(window.__rwMdScrollToOffset){return !!window.__rwMdScrollToOffset(" + safeOffset + ");}return false;}catch(e){return false;}})()",
                value -> {
                    boolean ok = "true".equals(value);
                    if (!ok && fallbackToVisualPage) {
                        scrollMarkdownToVisualPage(fallbackVisualPage, false);
                        return;
                    }
                    webView.postDelayed(() -> {
                        updateMarkdownVisualPageModel(false);
                        updateMarkdownSourceAnchorFromWebView();
                    }, 80);
                });
    }

    int currentDisplayDocumentPageIndex() {
        return isMarkdownDocument() ? markdownVisualCurrentPage : currentPage;
    }

    int currentDisplayDocumentPageNumber() {
        return currentDisplayDocumentPageIndex() + 1;
    }

    String documentPageStatusLabel(int page, int total) {
        return String.format(Locale.getDefault(), "%d / %d", page, total);
    }

    void updateDocumentPageStatusViews() {
        updateDocumentPageStatusViews(true);
    }

    void updateDocumentPageStatusViews(boolean updateSlider) {
        int total = Math.max(1, documentPageCount());
        int page = Math.max(1, Math.min(total, currentDisplayDocumentPageNumber()));
        String label = documentPageStatusLabel(page, total);
        if (pageStatus != null) {
            pageStatus.setText(label);
        }
        if (topPageStatus != null) {
            topPageStatus.setText(label);
        }
        if (documentPageSeekBar != null && updateSlider && !documentPageSeekBarUserTracking) {
            int max = Math.max(0, total - 1);
            if (documentPageSeekBar.getMax() != max) documentPageSeekBar.setMax(max);
            int progress = Math.max(0, Math.min(max, page - 1));
            if (documentPageSeekBar.getProgress() != progress) {
                documentPageSeekBar.setProgress(progress);
            }
            // Keep the document seekbar visually enabled even for a single-page
            // document. Some Android skins render a disabled SeekBar with a tiny
            // hollow thumb, which made HWP/HWPX look different from the existing
            // DOCX/EPUB viewer controls. With max=0 it remains non-draggable, but
            // it keeps the same normal thumb/track appearance.
            documentPageSeekBar.setEnabled(true);
            documentPageSeekBar.setAlpha(1f);
        }
        if (prevButton != null) prevButton.setEnabled(page > 1);
        if (nextButton != null) nextButton.setEnabled(page < total);
    }

    /** Toolbar read-aloud button visibility; see the integration controller. */
    void updateDocumentTtsButtonVisibility() {
        documentTtsIntegration().updateButtonVisibility();
    }

    void showPage(int page, int direction) {
        updateDocumentTtsButtonVisibility();
        pageDisplay().showPage(page, direction);
    }

    void snapDocumentWebViewToPageTopIfNeeded(@NonNull WebView view) {
        if (!snapDocumentPageTopAfterLoad || isMarkdownDocument()) return;
        if (isDocumentSearchActiveOnCurrentPage()) {
            snapDocumentPageTopAfterLoad = false;
            return;
        }
        snapDocumentPageTopAfterLoad = false;
        view.post(() -> {
            if (activityDestroyed || webView == null || webView != view) return;
            webView.scrollTo(0, 0);
            updateDocumentPageStatusViews(false);
            if (isRenderedContentAnchorDocument()) {
                webView.postDelayed(this::updateDocumentContentAnchorFromWebView, 80);
            }
        });
    }

    void pageMarkdownBy(int direction) {
        if (!isMarkdownDocument() || webView == null || direction == 0) return;
        updateMarkdownVisualPageModel(false);
        int target = Math.max(0, Math.min(markdownVisualTotalPages - 1, markdownVisualCurrentPage + direction));
        scrollMarkdownToVisualPage(target, false);
    }

    /**
     * Clears any active WebView text selection in the Markdown reader. Markdown
     * paging is a scroll within the same WebView, so a page-turn swipe can leave
     * a stray word selected (and its selection bubble) after the turn. Markdown
     * runs with JavaScript disabled, so this briefly enables JS, drops the
     * selection via the DOM Selection API, finishes any floating selection
     * ActionMode, and restores JS to off.
     */
    void clearMarkdownWebSelection() {
        if (webView == null || !isMarkdownDocument()) return;
        webView.clearFocus();
        WebSettings settings = webView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        webView.evaluateJavascript(
                "(function(){try{var s=window.getSelection&&window.getSelection();"
                        + "if(s){s.removeAllRanges();}}catch(e){}return true;})()",
                value -> {
                    if (!activityDestroyed && webView != null && restoreJavascriptOff) {
                        webView.getSettings().setJavaScriptEnabled(false);
                    }
                });
        wordSelectionActive = false;
    }

    void scrollMarkdownToVisualPage(int page, boolean smooth) {
        if (!isMarkdownDocument() || webView == null) return;
        clearMarkdownWebSelection();
        updateMarkdownVisualPageModel(false);
        int safePage = Math.max(0, Math.min(Math.max(0, markdownVisualTotalPages - 1), page));
        int targetY = MarkdownVisualPageMath.targetScrollYForPage(
                safePage, markdownViewportHeightPx(), markdownMaxScrollY(), markdownVisualTotalPages,
                markdownPageTopOverlapPx());
        scrollMarkdownWebViewTo(targetY, smooth);
        markdownVisualCurrentPage = safePage;
        updateDocumentPageStatusViews();
        webView.postDelayed(() -> updateMarkdownVisualPageModel(true), 180);
        webView.postDelayed(this::updateMarkdownSourceAnchorFromWebView, 190);
        // A selection sometimes finalizes a beat after the swipe that turned the
        // page. Clear once more after the turn settles so no stray word stays
        // selected on the new page.
        webView.postDelayed(this::clearMarkdownWebSelection, 220);
    }

    void scrollMarkdownWebViewTo(int targetY, boolean smooth) {
        if (webView == null) return;
        int safeTargetY = Math.max(0, Math.min(targetY, markdownMaxScrollY()));
        // Markdown pages are visual viewport buckets, not continuous TXT anchors.
        // Animated scrolling causes intermediate onScroll updates, which makes the
        // toolbar page counter flicker through transient pages. Snap directly to
        // the target bucket so previous/next/page-jump behaves like a page turn.
        webView.scrollTo(0, safeTargetY);
    }

    int markdownViewportHeightPx() {
        if (webView == null) return 1;
        int h = webView.getHeight() - webView.getPaddingTop() - webView.getPaddingBottom();
        if (h > 0) return h;
        int stable = stableMarkdownViewportHeightPx();
        if (stable > 0) return stable;
        return Math.max(1, h);
    }

    private int stableMarkdownViewportHeightPx() {
        View root = findViewById(R.id.document_root);
        int rootHeight = root != null ? root.getHeight() : 0;
        if (rootHeight <= 0 && webView != null) rootHeight = webView.getRootView() != null ? webView.getRootView().getHeight() : 0;
        if (rootHeight <= 0) return Math.max(1, lastStableMarkdownViewportHeightPx);

        int topChromeHeight = measuredHeightEvenWhenHidden(documentAppBar);
        int bottomChromeHeight = measuredHeightEvenWhenHidden(documentBottomChrome);
        // Markdown visual pages should not change simply because the toolbar row or
        // bottom controls are hidden. Keep the largest expanded chrome heights seen
        // in this session as the stable pagination frame, while the actual WebView
        // can still grow visually when controls are hidden.
        if (documentChromeVisible) {
            if (topChromeHeight > lastExpandedDocumentTopChromeHeightPx) {
                lastExpandedDocumentTopChromeHeightPx = topChromeHeight;
            }
            if (bottomChromeHeight > lastExpandedDocumentBottomChromeHeightPx) {
                lastExpandedDocumentBottomChromeHeightPx = bottomChromeHeight;
            }
        }
        int stableTop = Math.max(topChromeHeight, lastExpandedDocumentTopChromeHeightPx);
        int stableBottom = Math.max(bottomChromeHeight, lastExpandedDocumentBottomChromeHeightPx);
        int chromeHeight = stableTop + stableBottom;
        int candidate = rootHeight - chromeHeight;
        if (candidate <= 0 && webView != null) candidate = webView.getHeight() - webView.getPaddingTop() - webView.getPaddingBottom();
        if (candidate > 0 && (lastStableMarkdownViewportHeightPx <= 0 || documentChromeVisible)) {
            lastStableMarkdownViewportHeightPx = candidate;
        }
        return Math.max(1, lastStableMarkdownViewportHeightPx > 0 ? lastStableMarkdownViewportHeightPx : candidate);
    }

    private int measuredHeightEvenWhenHidden(View view) {
        if (view == null) return 0;
        int h = view.getHeight();
        if (h <= 0) h = view.getMeasuredHeight();
        return Math.max(0, h);
    }

    int markdownContentHeightPx() {
        if (webView == null) return markdownViewportHeightPx();
        float scale = webView.getScale();
        if (scale <= 0f || Float.isNaN(scale) || Float.isInfinite(scale)) scale = 1f;
        int viewport = markdownViewportHeightPx();
        int measured = Math.max(viewport, Math.round(webView.getContentHeight() * scale));
        // Use the current measured rendered height. Holding the largest value seen
        // caused stale/phantom Markdown final pages after layout or toolbar changes.
        // Keep the field only as a fallback for the rare zero-height WebView report.
        if (measured > viewport) {
            lastStableMarkdownContentHeightPx = measured;
            return measured;
        }
        return Math.max(viewport, lastStableMarkdownContentHeightPx > 0 ? lastStableMarkdownContentHeightPx : measured);
    }

    int markdownMaxScrollY() {
        return Math.max(0, markdownContentHeightPx() - markdownViewportHeightPx());
    }

    private int markdownPageTopOverlapPx() {
        int viewport = Math.max(1, markdownViewportHeightPx());
        // Markdown is a flowing WebView, so page boundaries can fall through the
        // middle of a rendered line, list item, or code block.  Use a real page
        // stride overlap large enough to repeat several lines on the next page.
        int preferred = dpToPx(96f);
        int viewportCap = Math.max(dpToPx(48f), viewport / 5);
        return Math.max(0, Math.min(preferred, viewportCap));
    }

    void scheduleMarkdownVisualPageModelUpdate() {
        if (!isMarkdownDocument() || webView == null) return;
        webView.post(() -> updateMarkdownVisualPageModel(false));
        webView.postDelayed(() -> updateMarkdownVisualPageModel(false), 80);
        webView.postDelayed(() -> updateMarkdownVisualPageModel(false), 260);
        webView.postDelayed(() -> updateMarkdownVisualPageModel(true), 620);
    }

    void updateMarkdownVisualPageModel(boolean saveState) {
        if (!isMarkdownDocument() || webView == null) return;
        int viewport = markdownViewportHeightPx();
        int content = markdownContentHeightPx();
        int overlap = markdownPageTopOverlapPx();
        int total = MarkdownVisualPageMath.totalPages(content, viewport, overlap);
        int stableMaxScroll = Math.max(0, content - viewport);
        lastMarkdownMaxScrollYPx = stableMaxScroll;
        lastMarkdownCurrentRawScrollYPx = Math.max(0, webView.getScrollY());
        // Detect the page against the same stable scroll range that
        // targetScrollYForPage uses, so the slider position and page-jump
        // targets round-trip instead of disagreeing near the document end when
        // chrome show/hide changes the live viewport.
        int page = MarkdownVisualPageMath.currentPageIndex(
                webView.getScrollY(), viewport, total, stableMaxScroll, overlap);
        boolean changed = page != markdownVisualCurrentPage || total != markdownVisualTotalPages;
        markdownVisualCurrentPage = page;
        markdownVisualTotalPages = total;
        if (changed || pageStatus != null) updateDocumentPageStatusViews();
        if (saveState) saveReadingState();
    }

    void restoreMarkdownVisualPositionAfterLoadIfNeeded(@NonNull WebView view) {
        if (!isMarkdownDocument()) return;
        if (isDocumentSearchActiveOnCurrentPage()) {
            pendingMarkdownRestoreSourceOffset = -1;
            pendingMarkdownRestorePage = -1;
            pendingMarkdownRestoreScrollY = -1;
            return;
        }
        final int restoreSourceOffset = pendingMarkdownRestoreSourceOffset;
        final int restorePage = pendingMarkdownRestorePage;
        final int restoreY = pendingMarkdownRestoreScrollY;
        pendingMarkdownRestoreSourceOffset = -1;
        pendingMarkdownRestorePage = -1;
        pendingMarkdownRestoreScrollY = -1;
        view.postDelayed(() -> {
            if (activityDestroyed || webView == null || !isMarkdownDocument()) return;
            installMarkdownSourceAnchorScript();
            updateMarkdownVisualPageModel(false);
            if (restoreSourceOffset >= 0) {
                scrollMarkdownToSourceOffset(restoreSourceOffset, restorePage >= 0, restorePage);
            } else if (restorePage >= 0) {
                scrollMarkdownToVisualPage(restorePage, false);
            } else if (restoreY >= 0) {
                webView.scrollTo(0, Math.max(0, Math.min(markdownMaxScrollY(), restoreY)));
                updateMarkdownVisualPageModel(false);
                updateMarkdownSourceAnchorFromWebView();
            } else {
                updateMarkdownVisualPageModel(false);
                updateMarkdownSourceAnchorFromWebView();
            }
        }, 90);
        view.postDelayed(() -> updateMarkdownVisualPageModel(false), 360);
        view.postDelayed(this::updateMarkdownSourceAnchorFromWebView, 420);
    }

    void runDocumentSlideInAnimation() {
        pageDisplay().runSlideInAnimation();
    }

    void addBookmarkForCurrentPage() {
        addBookmarkForCurrentPage(null);
    }

    void addBookmarkForCurrentPage(Runnable afterSave) {
        if (filePath == null || pages.isEmpty()) {
            if (afterSave != null) afterSave.run();
            return;
        }
        if (isMarkdownDocument()) {
            updateMarkdownVisualPageModel(false);
            updateMarkdownSourceAnchorFromWebView(() -> {
                addMarkdownBookmarkFromCachedAnchor();
                if (afterSave != null) afterSave.run();
            });
            return;
        }

        if (isRenderedContentAnchorDocument()) {
            updateDocumentContentAnchorFromWebView(() -> {
                addRenderedDocumentBookmarkFromCurrentAnchor();
                if (afterSave != null) afterSave.run();
            });
            return;
        }
        addRenderedDocumentBookmarkFromCurrentAnchor();
        if (afterSave != null) afterSave.run();
    }


    String buildMarkdownContentAnchorJson(int sourceOffset, int sourceLine, int visualPage, int totalPages) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("kind", "Markdown_SOURCE_ANCHOR_v1");
            obj.put("docType", "Markdown");
            obj.put("sourceOffset", Math.max(0, sourceOffset));
            obj.put("sourceLine", Math.max(1, sourceLine));
            obj.put("visualPage", Math.max(1, visualPage));
            obj.put("visualTotalPages", Math.max(1, totalPages));
            obj.put("textBefore", markdownAnchorTextAround(sourceOffset, true));
            obj.put("textAfter", markdownAnchorTextAround(sourceOffset, false));
            return obj.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String documentBookmarkPageExcerpt(int page, int total) {
        return String.format(Locale.getDefault(), "Page %d / %d", Math.max(1, page), Math.max(1, total));
    }

    void addRenderedDocumentBookmarkFromCurrentAnchor() {
        int bookmarkPosition = currentDisplayDocumentPageIndex();
        int bookmarkPageNumber = bookmarkPosition + 1;
        int total = documentPageCount();
        String anchorJson = isRenderedContentAnchorDocument() ? lastDocumentContentAnchorJson : "";
        String excerpt = documentBookmarkPageExcerpt(bookmarkPageNumber, total);
        for (Bookmark b : bookmarkManager.getBookmarksForFile(filePath)) {
            if (b.getCharPosition() == bookmarkPosition
                    && (!isRenderedContentAnchorDocument() || sameDocumentContentAnchorSpot(b, anchorJson, bookmarkPosition))) {
                b.setLineNumber(bookmarkPageNumber);
                b.setPageNumber(bookmarkPageNumber);
                b.setTotalPages(total);
                b.setExcerpt(excerpt);
                b.setEndPosition(bookmarkPosition);
                if (isRenderedContentAnchorDocument()) {
                    b.setContentAnchorJson(anchorJson);
                    b.setPageLayoutSignature(docType + "_CONTENT_ANCHOR_v1");
                }
                bookmarkManager.updateBookmark(b);
                ShortToast.show(this, getString(R.string.bookmark_updated));
                return;
            }
        }

        Bookmark bookmark = new Bookmark(filePath, fileName, bookmarkPosition, bookmarkPageNumber, excerpt);
        bookmark.setPageNumber(bookmarkPageNumber);
        bookmark.setTotalPages(total);
        bookmark.setEndPosition(bookmarkPosition);
        if (isRenderedContentAnchorDocument()) {
            bookmark.setContentAnchorJson(anchorJson);
            bookmark.setPageLayoutSignature(docType + "_CONTENT_ANCHOR_v1");
        }
        bookmarkManager.addBookmark(bookmark);
        ShortToast.show(this, getString(R.string.bookmark_saved));
    }

    void addMarkdownBookmarkFromCachedAnchor() {
        if (filePath == null || pages.isEmpty() || !isMarkdownDocument()) return;
        updateMarkdownVisualPageModel(false);
        int sourceOffset = clampMarkdownSourceOffset(lastMarkdownSourceOffset);
        int sourceLine = Math.max(1, lastMarkdownSourceLine > 0 ? lastMarkdownSourceLine : markdownSourceLineForOffset(sourceOffset));
        int visualPage = currentDisplayDocumentPageNumber();
        int total = documentPageCount();
        String excerpt = markdownBookmarkExcerpt(sourceOffset, visualPage, total);
        for (Bookmark b : bookmarkManager.getBookmarksForFile(filePath)) {
            if (isMarkdownSourceAnchorSignature(b.getPageLayoutSignature()) && Math.abs(b.getCharPosition() - sourceOffset) <= 2) {
                b.setLineNumber(sourceLine);
                b.setPageNumber(visualPage);
                b.setTotalPages(total);
                b.setExcerpt(excerpt);
                b.setEndPosition(sourceOffset);
                b.setAnchorTextBefore(markdownAnchorTextAround(sourceOffset, true));
                b.setAnchorTextAfter(markdownAnchorTextAround(sourceOffset, false));
                b.setContentAnchorJson(buildMarkdownContentAnchorJson(sourceOffset, sourceLine, visualPage, total));
                b.setPageLayoutSignature("Markdown_SOURCE_ANCHOR_v1");
                bookmarkManager.updateBookmark(b);
                ShortToast.show(this, getString(R.string.bookmark_updated));
                return;
            }
        }
        Bookmark bookmark = new Bookmark(filePath, fileName, sourceOffset, sourceLine, excerpt);
        bookmark.setPageNumber(visualPage);
        bookmark.setTotalPages(total);
        bookmark.setEndPosition(sourceOffset);
        bookmark.setAnchorTextBefore(markdownAnchorTextAround(sourceOffset, true));
        bookmark.setAnchorTextAfter(markdownAnchorTextAround(sourceOffset, false));
        bookmark.setContentAnchorJson(buildMarkdownContentAnchorJson(sourceOffset, sourceLine, visualPage, total));
        bookmark.setPageLayoutSignature("Markdown_SOURCE_ANCHOR_v1");
        bookmarkManager.addBookmark(bookmark);
        ShortToast.show(this, getString(R.string.bookmark_saved));
    }


    boolean sameDocumentContentAnchorPage(@NonNull Bookmark bookmark, int pageIndex) {
        if (bookmark.getContentAnchorJson() == null || bookmark.getContentAnchorJson().trim().isEmpty()) return true;
        try {
            JSONObject obj = new JSONObject(bookmark.getContentAnchorJson());
            return obj.optInt("pageIndex", pageIndex) == pageIndex;
        } catch (Exception ignored) {
            return true;
        }
    }

    boolean sameDocumentContentAnchorSpot(@NonNull Bookmark bookmark, String newAnchorJson, int pageIndex) {
        if (newAnchorJson == null || newAnchorJson.trim().isEmpty()) {
            return sameDocumentContentAnchorPage(bookmark, pageIndex);
        }
        String oldAnchorJson = bookmark.getContentAnchorJson();
        if (oldAnchorJson == null || oldAnchorJson.trim().isEmpty()) return false;
        try {
            JSONObject oldObj = new JSONObject(oldAnchorJson);
            JSONObject newObj = new JSONObject(newAnchorJson);
            if (oldObj.optInt("pageIndex", pageIndex) != newObj.optInt("pageIndex", pageIndex)) return false;
            int oldBlock = oldObj.optInt("blockIndex", -100000);
            int newBlock = newObj.optInt("blockIndex", 100000);
            if (oldBlock >= 0 && newBlock >= 0 && Math.abs(oldBlock - newBlock) <= 1) return true;
            String oldText = oldObj.optString("text", "").trim();
            String newText = newObj.optString("text", "").trim();
            return oldText.length() >= 16 && oldText.equals(newText);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showBookmarksDialog() {
        new DocumentBookmarkDialogController(this).showBookmarksDialog();
    }

    int documentPageCount() {
        return isMarkdownDocument() ? Math.max(1, markdownVisualTotalPages) : pages.size();
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
        if (isMarkdownDocument()) {
            if (webView != null) {
                updateMarkdownVisualPageModel(false);
                updateMarkdownSourceAnchorFromWebView();
            }
            state.setCharPosition(clampMarkdownSourceOffset(lastMarkdownSourceOffset));
            state.setScrollY(webView != null ? webView.getScrollY() : 0);
            state.setPageNumber(currentDisplayDocumentPageNumber());
            state.setTotalPages(documentPageCount());
            state.setContentAnchorJson(buildMarkdownContentAnchorJson(
                    clampMarkdownSourceOffset(lastMarkdownSourceOffset),
                    Math.max(1, lastMarkdownSourceLine > 0 ? lastMarkdownSourceLine : markdownSourceLineForOffset(lastMarkdownSourceOffset)),
                    currentDisplayDocumentPageNumber(),
                    documentPageCount()));
            state.setEncoding("Markdown_SOURCE_ANCHOR_v1");
        } else {
            if (isRenderedContentAnchorDocument()) updateDocumentContentAnchorFromWebView();
            state.setCharPosition(currentPage);
            state.setScrollY(0);
            state.setPageNumber(currentPage + 1);
            state.setTotalPages(pages.size());
            if (isRenderedContentAnchorDocument() && lastDocumentContentAnchorJson != null && !lastDocumentContentAnchorJson.isEmpty()) {
                state.setContentAnchorJson(lastDocumentContentAnchorJson);
                state.setEncoding(docType + "_CONTENT_ANCHOR_v1");
            } else {
                state.setEncoding(docType + "_PAGE");
            }
        }
        state.setFileLength(fileSizeBytes(filePath));
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
            int spinePageIndex = pages.size();
            boolean centerAsCover = !epubFixedLayoutLike
                    && isEpubCoverLikePage(html, title, path, spinePageIndex);
            pages.add(new Page(title, epubFixedLayoutLike ? html : prepareEpubHtml(html, centerAsCover), path));
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

    void loadMarkdownPage(File file) throws Exception {
        closeResourceZip();
        String markdown = FileUtils.readTextFile(file);
        markdownSourceText = (markdown != null ? markdown : "").replace("\r\n", "\n").replace('\r', '\n');
        lastStableMarkdownViewportHeightPx = 0;
        lastStableMarkdownContentHeightPx = 0;
        lastMarkdownSourceOffset = 0;
        lastMarkdownSourceLine = 1;
        lastMarkdownAnchorText = "";
        String title = file != null ? file.getName() : "Markdown";
        pages.add(new Page(title, MarkdownDocumentRenderer.render(markdownSourceText, title), null));
    }

    void loadWordPages(File file) throws Exception {
        closeResourceZip();
        if (file != null && isLegacyBinaryDoc(file)) {
            loadLegacyDocPages(file);
            return;
        }
        resourceZip = new ZipFile(file);
        wordRelationships.clear();
        loadWordRelationships(resourceZip);
        wordDefaultFontFamily = detectWordDefaultFontFamily(resourceZip);
        wordHasDocumentFont = wordDefaultFontFamily != null && !wordDefaultFontFamily.trim().isEmpty();
        ZipEntry documentXml = resourceZip.getEntry("word/document.xml");
        if (documentXml == null) throw new IOException("Unsupported Word file");

        if (tryLoadWordRenderedPages(file)) {
            return;
        }

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


    private boolean tryLoadWordRenderedPages(File file) {
        try {
            RenderedDocument rendered = DocumentDocxLayoutExtractor.extract(
                    resourceZip, file != null ? file.getName() : fileName, LOCAL_HOST, WORD_PARAGRAPHS_PER_PAGE);
            if (rendered == null || rendered.pages.isEmpty()) return false;
            for (RenderedPage page : rendered.pages) {
                RenderedDocument singlePage = RenderedDocument.builder("docx")
                        .title(rendered.title)
                        .addPage(page)
                        .build();
                String html = FixedHtmlRenderer.render(singlePage);
                pages.add(new Page("Page " + (page.pageIndex + 1), html, null));
            }
            return !pages.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLegacyBinaryDoc(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[8];
            int read = 0;
            while (read < magic.length) {
                int r = in.read(magic, read, magic.length - read);
                if (r < 0) break;
                read += r;
            }
            if (read < magic.length) return false;
            return (magic[0] & 0xFF) == 0xD0 && (magic[1] & 0xFF) == 0xCF
                    && (magic[2] & 0xFF) == 0x11 && (magic[3] & 0xFF) == 0xE0
                    && (magic[4] & 0xFF) == 0xA1 && (magic[5] & 0xFF) == 0xB1
                    && (magic[6] & 0xFF) == 0x1A && (magic[7] & 0xFF) == 0xE1;
        } catch (IOException e) {
            return false;
        }
    }

    private void loadLegacyDocPages(File file) throws Exception {
        RenderedDocument rendered = DocLegacyLayoutExtractor.extract(
                file, file.getName(), WORD_PARAGRAPHS_PER_PAGE);
        if (rendered == null || rendered.pages.isEmpty()) {
            throw new IOException("Unsupported or empty legacy Word (.doc) file");
        }
        for (RenderedPage page : rendered.pages) {
            RenderedDocument singlePage = RenderedDocument.builder("doc")
                    .title(rendered.title)
                    .addPage(page)
                    .build();
            String html = FixedHtmlRenderer.render(singlePage);
            pages.add(new Page("Page " + (page.pageIndex + 1), html, null));
        }
    }



    void loadHwpPages(File file) throws Exception {
        closeResourceZip();
        if (tryLoadHwpRenderedPages(file)) {
            return;
        }
        String text = HwpTextExtractor.read(file);
        List<String> pageBodies = splitPlainTextDocumentIntoPages(text, HWP_PARAGRAPHS_PER_PAGE, HWP_TARGET_CHARS_PER_PAGE);
        for (int i = 0; i < pageBodies.size(); i++) {
            pages.add(new Page("Page " + (i + 1), wrapHwpPage(pageBodies.get(i), i + 1), null));
        }
    }

    private boolean tryLoadHwpRenderedPages(File file) {
        try {
            RenderedDocument rendered = DocumentHwpLayoutExtractor.extract(
                    file, file != null ? file.getName() : fileName, HWP_PARAGRAPHS_PER_PAGE, HWP_TARGET_CHARS_PER_PAGE);
            if (rendered == null || rendered.pages.isEmpty()) return false;
            for (RenderedPage page : rendered.pages) {
                RenderedDocument singlePage = RenderedDocument.builder(rendered.sourceFormat)
                        .title(rendered.title)
                        .addPage(page)
                        .build();
                String html = FixedHtmlRenderer.render(singlePage);
                pages.add(new Page("Page " + (page.pageIndex + 1), html, null));
            }
            return !pages.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private List<String> splitPlainTextDocumentIntoPages(String text, int paragraphLimit, int targetChars) {
        ArrayList<String> result = new ArrayList<>();
        String normalized = (text != null ? text : "").replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = normalized.split("\n{2,}");
        StringBuilder current = new StringBuilder();
        int paragraphCount = 0;
        for (String paragraph : paragraphs) {
            String p = paragraph != null ? paragraph.trim() : "";
            if (p.isEmpty()) continue;
            if (current.length() > 0
                    && (paragraphCount >= paragraphLimit || current.length() + p.length() > targetChars)) {
                result.add(current.toString());
                current.setLength(0);
                paragraphCount = 0;
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(p);
            paragraphCount++;
        }
        if (current.length() > 0 || result.isEmpty()) result.add(current.toString());
        return result;
    }


    int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // Serves only readwide.local resources, and only entries that exist INSIDE the
    // current document archive resourceZip or the user-selected font -- never an
    // arbitrary filesystem path. getEntry and getInputStream read from the in-memory
    // zip, so a crafted ../ path in document HTML cannot escape to the filesystem;
    // together with disabled file access this leaves no local-file read surface.
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

    boolean handleEpubInternalNavigation(Uri uri) {
        if (uri == null || !"EPUB".equals(docType) || pages == null || pages.isEmpty()) return false;
        if (!LOCAL_HOST.equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getPath();
        if (path == null || !path.startsWith(EPUB_PREFIX)) return false;

        String zipPath = path.substring(EPUB_PREFIX.length());
        try {
            zipPath = URLDecoder.decode(zipPath, "UTF-8");
        } catch (Exception ignored) {}
        zipPath = normalizeZipPath(zipPath);
        int hash = zipPath.indexOf('#');
        if (hash >= 0) zipPath = zipPath.substring(0, hash);
        if (zipPath.isEmpty()) return false;

        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (page == null || page.sourcePath == null) continue;
            if (normalizeZipPath(page.sourcePath).equals(zipPath)) {
                if (i != currentPage) {
                    showPage(i, Integer.compare(i, currentPage));
                }
                return true;
            }
        }
        return false;
    }

    String prepareFixedLayoutEpubHtml(String html) {
        if (html == null) html = "";
        int[] viewport = extractFixedLayoutViewportSize(html);
        if (viewport[0] <= 0 || viewport[1] <= 0) {
            viewport = extractSvgViewBoxSize(html);
        }
        String css;
        if (viewport[0] > 0 && viewport[1] > 0) {
            css = "<style id=\"textview-fixed-layout-center\">"
                    + "html{margin:0 !important;padding:0 !important;width:100vw !important;min-height:100vh !important;"
                    + "background:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                    + "body{margin:0 !important;padding:0 !important;width:" + viewport[0] + "px !important;"
                    + "height:auto !important;min-width:" + viewport[0] + "px !important;"
                    + "min-height:" + viewport[1] + "px !important;position:absolute !important;left:0;top:0;"
                    + "transform-origin:0 0 !important;background:transparent !important;overflow:visible !important;}"
                    + "body>div:only-child,body>svg:only-child{width:100% !important;height:100% !important;}"
                    + "body img,body svg{max-width:100% !important;max-height:100% !important;}"
                    + "</style>"
                    + "<script id=\"textview-fixed-layout-fit\">"
                    + "(function(){var W=" + viewport[0] + ",H=" + viewport[1] + ";"
                    + "function fit(){try{var vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||W);"
                    + "var vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||H);"
                    + "var naturalH=Math.max(H,document.body.scrollHeight||0);"
                    + "var landscape=W>H*1.2;"
                    + "var safe=landscape?Math.min(36,Math.max(18,Math.round(vw*0.025))):0;"
                    + "var fitW=Math.max(1,vw-(safe*2));"
                    + "var s=Math.min(fitW/W,vh/H);if(!isFinite(s)||s<=0)s=1;"
                    + "var scaledH=naturalH*s;"
                    + "var l=Math.max(safe,(vw-W*s)/2),t=Math.max(0,(vh-Math.min(H*s,scaledH))/2);"
                    + "document.documentElement.style.width=vw+'px';document.documentElement.style.height=Math.max(vh,scaledH+t)+'px';"
                    + "document.body.style.width=W+'px';document.body.style.minHeight=naturalH+'px';"
                    + "document.body.style.left=l+'px';document.body.style.top=t+'px';"
                    + "document.body.style.transform='scale('+s+')';}catch(e){}}"
                    + "window.addEventListener('resize',fit);window.addEventListener('orientationchange',function(){setTimeout(fit,60);});"
                    + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',fit);else fit();"
                    + "setTimeout(fit,0);setTimeout(fit,180);})();"
                    + "</script>";
        } else {
            css = "<style id=\"textview-fixed-layout-center\">"
                    + "html{margin:0 !important;padding:0 !important;width:100vw !important;height:100vh !important;"
                    + "display:flex !important;align-items:center !important;justify-content:center !important;"
                    + "background:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                    + "body{margin:0 !important;padding:0 !important;flex:0 0 auto;box-sizing:border-box !important;}"
                    + "body>img:only-child,body>svg:only-child{display:block;margin:0 auto;}"
                    + "</style>";
        }
        return injectIntoHtmlHead(replaceFixedLayoutViewportMeta(html), css);
    }

    private String replaceFixedLayoutViewportMeta(String html) {
        if (html == null) return "";
        String replacement = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\"/>";
        String next = html.replaceFirst("(?is)<meta[^>]+name\\s*=\\s*['\\\"]viewport['\\\"][^>]*>", replacement);
        if (!next.equals(html)) return next;
        return html.replaceFirst("(?i)<head[^>]*>", "$0" + replacement);
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

    private int[] extractSvgViewBoxSize(String html) {
        int[] result = new int[]{0, 0};
        if (html == null) return result;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?is)<svg[^>]+viewBox\\s*=\\s*['\\\"]\\s*[-0-9.]+\\s+[-0-9.]+\\s+([0-9.]+)\\s+([0-9.]+)")
                    .matcher(html);
            if (!m.find()) return result;
            result[0] = Math.round(Float.parseFloat(m.group(1)));
            result[1] = Math.round(Float.parseFloat(m.group(2)));
            if (result[0] < 100 || result[1] < 100) {
                result[0] = 0;
                result[1] = 0;
            }
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
        return prepareEpubHtml(html, false);
    }

    private String prepareEpubHtml(String html, boolean centerAsCover) {
        if (html == null) html = "";
        if (epubFixedLayoutLike) {
            // Preserve the fixed-layout page geometry, but center the fixed page in
            // the available WebView area instead of leaving it pinned to the top.
            return prepareFixedLayoutEpubHtml(html);
        }
        if (centerAsCover) {
            html = addClassToHtmlBody(html, "textview-reader-epub-cover-page");
        }
        String css = "<style>" +
                "html,body{margin:0;padding:0;background:#121212;color:#e8eaed;}" +
                "body{line-height:1.55;padding:22px;box-sizing:border-box;}" +
                "body.textview-reader-epub-cover-page{min-height:100vh !important;display:flex !important;" +
                "flex-direction:column !important;align-items:center !important;justify-content:center !important;}" +
                "body.textview-reader-epub-cover-page>*{max-width:100% !important;}" +
                "img,svg,video{max-width:100%;height:auto;}" +
                "a{color:#8ab4f8;}" +
                "pre{white-space:pre-wrap;}" +
                "</style>";
        String viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">";
        if (html.toLowerCase(Locale.ROOT).contains("<head")) {
            return html.replaceFirst("(?i)<head[^>]*>", "$0" + viewport + css);
        }
        return "<!doctype html><html><head>" + viewport + css + "</head><body"
                + (centerAsCover ? " class=\"textview-reader-epub-cover-page\"" : "")
                + ">" + html + "</body></html>";
    }

    private boolean isEpubCoverLikePage(String html, String title, String path, int spinePageIndex) {
        // Only the first spine item is auto-centered. Later image-heavy EPUB
        // pages must keep the ordinary reflow layout so illustrations and text do
        // not jump to the middle of the viewport.
        if (spinePageIndex != 0 || html == null) return false;

        String lowerPath = path != null ? path.toLowerCase(Locale.ROOT) : "";
        String lowerTitle = title != null ? title.toLowerCase(Locale.ROOT) : "";
        boolean nameLooksLikeCover = lowerPath.contains("cover")
                || lowerPath.contains("title")
                || lowerTitle.contains("cover")
                || lowerTitle.contains("title");

        int imageLikeCount = countHtmlTag(html, "img") + countHtmlTag(html, "svg");
        int paragraphCount = countHtmlTag(html, "p");
        int headingCount = countHtmlTag(html, "h1")
                + countHtmlTag(html, "h2")
                + countHtmlTag(html, "h3");
        int bodyTextLength = strippedHtmlTextLength(html);

        if (nameLooksLikeCover && paragraphCount <= 3 && bodyTextLength <= 1400) return true;
        if (imageLikeCount > 0 && paragraphCount <= 1 && bodyTextLength <= 1200) return true;
        return headingCount > 0 && paragraphCount <= 3 && bodyTextLength <= 900;
    }

    private int countHtmlTag(String html, String tag) {
        if (html == null || tag == null || tag.isEmpty()) return 0;
        String lower = html.toLowerCase(Locale.ROOT);
        String needle = "<" + tag.toLowerCase(Locale.ROOT);
        int count = 0;
        int from = 0;
        while ((from = lower.indexOf(needle, from)) >= 0) {
            int next = from + needle.length();
            if (next >= lower.length()) {
                count++;
            } else {
                char c = lower.charAt(next);
                if (Character.isWhitespace(c) || c == '>' || c == '/') count++;
            }
            from = Math.max(next, from + 1);
        }
        return count;
    }

    private int strippedHtmlTextLength(String html) {
        if (html == null || html.isEmpty()) return 0;
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length();
    }

    private String addClassToHtmlBody(String html, String className) {
        if (html == null || html.isEmpty() || className == null || className.isEmpty()) return html != null ? html : "";
        java.util.regex.Matcher body = java.util.regex.Pattern.compile("(?i)<body([^>]*)>").matcher(html);
        if (!body.find()) return html;
        String attrs = body.group(1) != null ? body.group(1) : "";
        String replacementAttrs;
        java.util.regex.Matcher klass = java.util.regex.Pattern
                .compile("(?i)(\\sclass\\s*=\\s*)(['\"])(.*?)\\2")
                .matcher(attrs);
        if (klass.find()) {
            String existing = klass.group(3) != null ? klass.group(3) : "";
            if (existing.contains(className)) return html;
            String newClassValue = existing.trim().isEmpty() ? className : existing + " " + className;
            replacementAttrs = klass.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                    klass.group(1) + klass.group(2) + newClassValue + klass.group(2)));
        } else {
            replacementAttrs = attrs + " class=\"" + className + "\"";
        }
        return html.substring(0, body.start()) + "<body" + replacementAttrs + ">" + html.substring(body.end());
    }



    private String wrapHwpPage(String text, int pageNumber) {
        return "<!doctype html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=yes\">" +
                "<style>" +
                "html,body{margin:0;padding:0;background:#202124;overflow-x:hidden;-webkit-text-size-adjust:100%;max-width:100%;}" +
                "body{font-family:Arial,Helvetica,sans-serif;}" +
                "*{box-sizing:border-box;max-width:100%;}" +
                ".page{background:#fff;color:#111;margin:0 auto;padding:18px 16px;width:100%;max-width:none;" +
                "min-height:100vh;box-shadow:none;overflow-x:hidden;box-sizing:border-box;}" +
                ".pageNo{color:#777;text-align:right;font-size:12px;margin-bottom:14px;}" +
                "p{margin:0 0 10px 0;line-height:1.45;font-size:16px;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;}" +
                "</style></head><body><div class=\"page\"><div class=\"pageNo\">Page " + pageNumber +
                "</div>" + plainTextToParagraphHtml(text) + "</div></body></html>";
    }

    private String plainTextToParagraphHtml(String text) {
        String normalized = (text != null ? text : "").replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = normalized.split("\n{2,}");
        StringBuilder out = new StringBuilder();
        for (String paragraph : paragraphs) {
            String p = paragraph != null ? paragraph.trim() : "";
            if (p.isEmpty()) continue;
            out.append("<p>").append(escapeHtml(p)).append("</p>");
        }
        if (out.length() == 0) out.append("<p></p>");
        return out.toString();
    }

    private String escapeHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(ch); break;
            }
        }
        return out.toString();
    }


    private String wrapWordPage(String body, int pageNumber) {
        return "<!doctype html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=yes\">" +
                "<style>" +
                "html,body{margin:0;padding:0;background:#202124;overflow-x:hidden;-webkit-text-size-adjust:100%;max-width:100%;}" +
                "body{font-family:Arial,Helvetica,sans-serif;}" +
                "*{box-sizing:border-box;max-width:100%;}" +
                ".page{background:#fff;color:#111;margin:0 auto;padding:18px 16px;width:100%;max-width:none;" +
                "min-height:100vh;box-shadow:none;overflow-x:hidden;box-sizing:border-box;}" +
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
        // Bounded read for resources served to the WebView (interceptLocalResource):
        // a crafted document could otherwise declare a huge image/font/entry and OOM
        // the process here. Oversized entries fail to serve instead.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int n;
        long total = 0L;
        while ((n = is.read(buffer)) != -1) {
            total += n;
            if (total > MAX_DOCUMENT_RESOURCE_BYTES) {
                throw new IOException("Document resource exceeds size limit");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
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
