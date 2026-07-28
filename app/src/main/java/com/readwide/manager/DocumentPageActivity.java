package com.readwide.manager;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.BitmapFactory;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.widget.TextViewCompat;

import com.readwide.manager.adapter.BookmarkFolderAdapter;
import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.model.Theme;
import com.readwide.manager.util.DocumentAnchorMath;
import com.readwide.manager.util.UriPathCodec;
import com.readwide.manager.util.EpubBindingRewriter;
import com.readwide.manager.util.EpubSpreadSlotMath;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.UUID;
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
    /** Match the visible gutter used by the PDF two-page composite. */
    private static final float EPUB_SPREAD_GAP_CSS_PX = 12f;
    // Cap per in-document WebView resource (EPUB/Word image/font/entry) so a crafted
    // oversized entry can't blow up memory while being served. See interceptLocalResource.
    private static final long MAX_DOCUMENT_RESOURCE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_EPUB_MEDIA_OVERLAY_AUDIO_BYTES = 256L * 1024L * 1024L;

    Toolbar toolbar;
    View documentAppBar;
    View documentBottomChrome;
    View documentNavBarSpacer;
    boolean documentChromeVisible = true;
    WebView webView;
    WebView rightWebView;
    LinearLayout documentSpreadContainer;
    View documentFastScrollRail;
    View documentFastScrollThumb;
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
    private int pendingEpubAnchorPage = -1;
    private String pendingEpubAnchorFragment = "";
    private EpubCfi pendingEpubCfi;
    private String localDocumentHost = LOCAL_HOST;
    int primaryDocumentPageLoadGeneration;
    int primaryDocumentPageLoadPage = -1;
    int rightDocumentPageLoadGeneration;
    int rightDocumentPageLoadPage = -1;

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
    DocumentArchiveUtils.EpubPackageResources epubPackageResources =
            new DocumentArchiveUtils.EpubPackageResources();
    final Set<String> epubArchiveEntries = new HashSet<>();
    private final Set<String> epubBindingPayloadPaths = new HashSet<>();
    File localFile;
    String filePath;
    String fileName;
    String docType = "Document";
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
    /** Invalidates asynchronous DOM-anchor callbacks whenever a page is reloaded. */
    int documentAnchorPageGeneration = 0;
    /** Cancels delayed anchor settling after the user starts a new gesture. */
    int documentInteractionGeneration = 0;
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
    /** WebView that supplied the gesture currently being dispatched. */
    private WebView documentGestureSourceView;
    private boolean documentDoubleTapResetSequence = false;
    private boolean documentTapPagingSequence = false;
    /** True after a second pointer joined the current WebView gesture. */
    private boolean documentGestureHadMultiplePointers = false;
    private int armedDocumentEdgeDirection = 0;
    private long armedDocumentEdgeTimeMs = 0L;
    private boolean wordGestureStartedAtLeftEdge = true;
    private boolean wordGestureStartedAtRightEdge = true;
    volatile boolean wordSelectionActive = false;
    volatile boolean activityDestroyed = false;
    private String loadedEpubBoundarySignature = "";
    private final SharedPreferences.OnSharedPreferenceChangeListener epubBoundaryPreferenceListener =
            (sharedPreferences, key) -> {
                if (!PrefsManager.isEpubBoundaryPreferenceKey(key)) return;
                runOnUiThread(() -> {
                    if (!activityDestroyed && "EPUB".equals(docType)) {
                        applyAndReloadEpubBoundaryImmediately();
                    }
                });
            };

    // SAF picker for importing a custom .ttf/.otf font into the document font list.
    // The result Uri is handled by importDocumentFontFromUri.
    private final ActivityResultLauncher<String[]> documentFontImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importDocumentFontFromUri(uri); });
    int loadGeneration = 0;
    File selectedDocumentFontFile = null;
    boolean epubHasDocumentFont = false;
    boolean epubFixedLayoutLike = false;
    /** True only when most EPUB spine items are page-sized image canvases. */
    boolean epubImagePageLike = false;
    /** True when publisher CSS uses horizontal-flowing vertical writing columns. */
    boolean epubVerticalWritingLike = false;
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
    final Runnable documentContentAnchorUpdateRunnable =
            this::updateDocumentContentAnchorFromWebView;
    final Map<String, String> wordRelationships = new LinkedHashMap<>();
    private DocumentPageStartupController startupController;
    private DocumentPageTurnController pageTurnController;
    private DocumentWebViewController documentWebViewController;
    private DocumentPageLoadController documentPageLoadController;
    private DocumentPageDisplayController documentPageDisplayController;
    private ProportionalFastScrollController documentFastScrollController;
    private EpubMediaOverlayController epubMediaOverlayController;

    static class Page {
        final String title;
        final String html;
        final String sourcePath;
        final boolean imageDominantEpubPage;
        final boolean fixedLayoutEpubPage;
        final boolean verticalWritingEpubPage;
        final boolean scriptedEpubPage;
        final String manifestId;
        final String itemRefId;
        final int spineIndex;
        final EpubSmilParser.Timeline mediaOverlayTimeline;

        Page(String title, String html, String sourcePath) {
            this(title, html, sourcePath, false, false, false,
                    false, "", "", -1, null);
        }

        Page(String title, String html, String sourcePath,
             boolean imageDominantEpubPage) {
            this(title, html, sourcePath, imageDominantEpubPage, false, false,
                    false, "", "", -1, null);
        }

        Page(String title, String html, String sourcePath,
             boolean imageDominantEpubPage,
             boolean fixedLayoutEpubPage,
             boolean verticalWritingEpubPage) {
            this(title, html, sourcePath, imageDominantEpubPage,
                    fixedLayoutEpubPage, verticalWritingEpubPage,
                    false, "", "", -1, null);
        }

        Page(String title, String html, String sourcePath,
             boolean imageDominantEpubPage,
             boolean fixedLayoutEpubPage,
             boolean verticalWritingEpubPage,
             boolean scriptedEpubPage,
             String manifestId,
             String itemRefId,
             int spineIndex,
             @Nullable EpubSmilParser.Timeline mediaOverlayTimeline) {
            this.title = title;
            this.html = html;
            this.sourcePath = sourcePath;
            this.imageDominantEpubPage = imageDominantEpubPage;
            this.fixedLayoutEpubPage = fixedLayoutEpubPage;
            this.verticalWritingEpubPage = verticalWritingEpubPage;
            this.scriptedEpubPage = scriptedEpubPage;
            this.manifestId = manifestId != null ? manifestId : "";
            this.itemRefId = itemRefId != null ? itemRefId : "";
            this.spineIndex = spineIndex;
            this.mediaOverlayTimeline = mediaOverlayTimeline;
        }

        boolean hasMediaOverlay() {
            return mediaOverlayTimeline != null && !mediaOverlayTimeline.isEmpty();
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
        prefs.getPrefs().registerOnSharedPreferenceChangeListener(epubBoundaryPreferenceListener);
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
        if (documentFastScrollController != null) {
            documentFastScrollController.resume();
        }
    }

    @Override
    protected void onPause() {
        if (epubMediaOverlayController != null) {
            epubMediaOverlayController.pauseForBackground();
        }
        if (documentFastScrollController != null) {
            documentFastScrollController.suspend();
        }
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
                    ? prefs.getEpubForceReaderThemeColors() : false)
                + "|epubFont=" + (("EPUB".equals(docType) && prefs != null)
                    ? prefs.getEpubFontFamily() : "");
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
                ? prefs.getEpubForceReaderThemeColors() : false)
                + "|epubFont=" + (("EPUB".equals(docType) && prefs != null)
                ? prefs.getEpubFontFamily() : "");
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
        // EPUB keeps Android's bars visible in both overlay states. Keep both
        // bars visually continuous with the page body rather than changing the
        // status-bar color when Readwide's title overlay is toggled.
        boolean epub = "EPUB".equals(docType);
        int statusBg = epub ? bodyBg : (documentChromeVisible ? readerToolbarBg : bodyBg);
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
        View insetRoot = findViewById(R.id.document_root);
        if (insetRoot != null) {
            if ("EPUB".equals(docType)) {
                // EPUB app chrome is only an overlay. Android's status and
                // navigation bars remain visible in both overlay states; the
                // content column owns their stable safe insets.
                androidx.core.view.WindowInsetsControllerCompat barController =
                        androidx.core.view.WindowCompat.getInsetsController(getWindow(), insetRoot);
                if (barController != null) {
                    barController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                }
            } else {
                com.readwide.manager.util.EdgeToEdgeUtil.applyReaderSystemBarVisibility(
                        this,
                        insetRoot,
                        documentChromeVisible,
                        prefs != null && prefs.getShowStatusBar());
            }
        }
        applyDocumentChromeFillColors();
    }

    void applyDocumentChromeFillColors() {
        // With edge-to-edge enforced on Android 15+, the padded AppBar parent is
        // what shows behind a transparent status bar. EPUB therefore gives the
        // parent the body color while its Toolbar child retains the chrome color.
        int topFillerBg = "EPUB".equals(docType) ? readerBg : readerToolbarBg;
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
        int[] epubSystemBarScrims = {
                R.id.document_system_bar_scrim_top,
                R.id.document_system_bar_scrim_bottom,
                R.id.document_system_bar_scrim_start,
                R.id.document_system_bar_scrim_end
        };
        for (int id : epubSystemBarScrims) {
            View scrim = findViewById(id);
            if (scrim != null) scrim.setBackgroundColor(readerBg);
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
        if (appbar != null) {
            appbar.setBackgroundColor("EPUB".equals(docType) ? readerBg : readerToolbarBg);
        }
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
        if (rightWebView != null) rightWebView.setBackgroundColor(readerBg);
        if (documentSpreadContainer != null) documentSpreadContainer.setBackgroundColor(readerBg);
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
        if (prefs != null) {
            prefs.getPrefs().unregisterOnSharedPreferenceChangeListener(epubBoundaryPreferenceListener);
        }
        activityDestroyed = true;
        loadGeneration++;
        if (documentFastScrollController != null) {
            documentFastScrollController.destroy();
            documentFastScrollController = null;
        }
        if (documentTtsController != null) {
            documentTtsController.release();
            documentTtsController = null;
        }
        if (epubMediaOverlayController != null) {
            epubMediaOverlayController.release();
            epubMediaOverlayController = null;
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

    private boolean handleDocumentTapGesture(@NonNull WebView source, @NonNull MotionEvent event) {
        if (documentGestureDetector == null) return false;
        documentGestureSourceView = source;
        boolean handled = documentGestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();

        if (documentDoubleTapResetSequence) {
            // A double tap is handled by this Activity as a zoom reset.  Consume the
            // second tap sequence so Android WebView's own double-tap zoom does not
            // race against the reset and immediately zoom the EPUB back in/out.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                documentDoubleTapResetSequence = false;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                documentGestureSourceView = null;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            documentGestureSourceView = null;
        }
        // Do not consume normal ACTION_DOWN; the WebView still needs the original
        // down event for scrolling, text selection, and edge-swipe tracking.
        return handled && action != MotionEvent.ACTION_DOWN;
    }

    private boolean handleFastDocumentTapPaging(@NonNull MotionEvent e) {
        int eventAction = e.getActionMasked();
        if (eventAction == MotionEvent.ACTION_CANCEL) {
            documentTapPagingSequence = false;
            documentGestureHadMultiplePointers = false;
            return false;
        }
        if (!isPagedWebDocument() || webView == null || prefs == null
                || ("EPUB".equals(docType) && currentEpubPageKeepsOriginalLayout())
                || !prefs.getDocumentTapPagingEnabled(docType)
                || documentPageCount() <= 1 || pageTurnInFlight) {
            documentTapPagingSequence = false;
            return false;
        }

        if (eventAction == MotionEvent.ACTION_DOWN) {
            wordSwipeStartX = e.getX();
            wordSwipeStartY = e.getY();
            documentGestureHadMultiplePointers = false;
            documentTapPagingSequence = getDocumentTapPagingAction(e) != TapZoneMath.ACTION_MENU;
            // Keep the native WebView long-press pipeline intact. A real page tap
            // is cancelled immediately before the page turn, not on DOWN; cancelling
            // here prevented side-zone text selection in Markdown/WebView documents.
            return false;
        }

        if (eventAction == MotionEvent.ACTION_POINTER_DOWN || e.getPointerCount() > 1) {
            documentGestureHadMultiplePointers = true;
            documentTapPagingSequence = false;
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
        boolean stillTap = isShortStationaryDocumentTap(e);
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

    private boolean isShortStationaryDocumentTap(@NonNull MotionEvent event) {
        return TapZoneMath.isShortTapRelease(
                event.getX() - wordSwipeStartX,
                event.getY() - wordSwipeStartY,
                wordSwipeTouchSlop,
                event.getEventTime() - event.getDownTime(),
                ViewConfiguration.getLongPressTimeout(),
                wordSelectionActive,
                wordSwipeTriggered,
                documentGestureHadMultiplePointers);
    }

    private int getDocumentTapPagingAction(@NonNull MotionEvent e) {
        if (!isPagedWebDocument() || webView == null || prefs == null) return TapZoneMath.ACTION_MENU;
        if (!prefs.getDocumentTapPagingEnabled(docType)) return TapZoneMath.ACTION_MENU;
        if (documentPageCount() <= 1 || pageTurnInFlight) return TapZoneMath.ACTION_MENU;
        // The chrome (top app bar / bottom controls) floats over the WebView, so a
        // tap landing on a visible chrome bar must not also page the document
        // underneath it. Skip paging when the tap falls within a shown bar.
        if (tapIntersectsVisibleChrome(e)) return TapZoneMath.ACTION_MENU;
        float x = e.getX();
        float y = e.getY();
        int w = webView.getWidth();
        int h = webView.getHeight();
        // In the landscape two-page spread, taps arrive from either WebView with
        // view-relative coordinates, so per-view zones would put paging zones at
        // the center of the SCREEN (the seam is the left view's trailing zone and
        // the right view's leading zone) - a center tap turned the page instead
        // of toggling the controls. Compute the zones against the whole spread
        // instead: leading zone at the far left page edge, trailing zone at the
        // far right, and the middle - including the seam - toggles the controls.
        if (isLandscapeTwoPageDocumentMode() && documentSpreadContainer != null
                && documentSpreadContainer.getWidth() > 0) {
            int[] loc = new int[2];
            documentSpreadContainer.getLocationOnScreen(loc);
            x = e.getRawX() - loc[0];
            y = e.getRawY() - loc[1];
            w = documentSpreadContainer.getWidth();
            h = documentSpreadContainer.getHeight();
        }
        int action = TapZoneMath.actionForTap(
                x,
                y,
                w,
                h,
                true,
                true,
                prefs.getTapZoneMode(),
                prefs.getTapLeadingZonePercent(),
                prefs.getTapTrailingZonePercent());
        if ("EPUB".equals(docType)
                && prefs.getEpubPageDirection() == PrefsManager.EPUB_PAGE_DIRECTION_RTL) {
            if (action == TapZoneMath.ACTION_PREVIOUS) return TapZoneMath.ACTION_NEXT;
            if (action == TapZoneMath.ACTION_NEXT) return TapZoneMath.ACTION_PREVIOUS;
        }
        return action;
    }

    /** True if the touch point lies inside a currently-visible chrome bar. */
    private boolean tapIntersectsVisibleChrome(@NonNull MotionEvent e) {
        if (!documentChromeVisible) return false;
        // Raw coordinates are already in screen space and remain correct for both
        // the left and right WebViews in a landscape spread. Using the left
        // WebView's location here made right-page taps test the wrong point.
        float screenX = e.getRawX();
        float screenY = e.getRawY();
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
        turnDocumentDisplayPageBy(direction);
    }

    private void toggleDocumentChrome() {
        setDocumentChromeVisible(!documentChromeVisible);
    }

    private int desiredDocumentTopPageStatusVisibility(boolean chromeVisible) {
        // EPUB uses the bottom page counter while controls are visible and no
        // compact counter while they are hidden. Keeping this in normal layout
        // flow as INVISIBLE/VISIBLE permanently reserved 32dp above the WebView.
        // Other document viewers retain their existing collapsed counter.
        if ("EPUB".equals(docType)) return View.GONE;
        return chromeVisible ? View.INVISIBLE : View.VISIBLE;
    }

    void applyDocumentTopPageStatusVisibility() {
        if (topPageStatus == null) return;
        int visibility = desiredDocumentTopPageStatusVisibility(documentChromeVisible);
        if (topPageStatus.getVisibility() != visibility) {
            topPageStatus.setVisibility(visibility);
        }
    }

    private void setDocumentChromeVisible(boolean visible) {
        int desiredTopStatus = desiredDocumentTopPageStatusVisibility(visible);
        if (documentChromeVisible == visible
                && documentAppBar != null
                && documentBottomChrome != null
                && topPageStatus != null
                && ((visible && documentAppBar.getVisibility() == View.VISIBLE
                && documentBottomChrome.getVisibility() == View.VISIBLE
                && topPageStatus.getVisibility() == desiredTopStatus)
                || (!visible && documentAppBar.getVisibility() == View.GONE
                && documentBottomChrome.getVisibility() == View.GONE
                && topPageStatus.getVisibility() == desiredTopStatus))) {
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
            if (pageStatus != null && pageStatus.getVisibility() != View.VISIBLE) {
                pageStatus.setVisibility(View.VISIBLE);
            }
            if (documentBottomChrome != null && documentBottomChrome.getVisibility() != View.VISIBLE) {
                documentBottomChrome.setVisibility(View.VISIBLE);
            }
        } else {
            // Collapsed state hides the toolbar/bottom overlay. Non-EPUB viewers
            // keep their compact page counter; EPUB releases that normal-flow row
            // so the WebView can use the complete system-safe frame.
            if (documentAppBar != null && documentAppBar.getVisibility() != View.GONE) {
                documentAppBar.setVisibility(View.GONE);
            }
            if (documentBottomChrome != null && documentBottomChrome.getVisibility() != View.GONE) {
                documentBottomChrome.setVisibility(View.GONE);
            }
        }
        applyDocumentTopPageStatusVisibility();
        applyDocumentSystemBarColors();
        if (!"EPUB".equals(docType)) {
            // Other document viewers can still change system-bar visibility with
            // their chrome. EPUB keeps a fixed system-safe frame and must not
            // relayout the WebView on a toolbar-only toggle.
            androidx.core.view.ViewCompat.requestApplyInsets(findViewById(R.id.document_root));
            androidx.core.view.ViewCompat.requestApplyInsets(topPageStatus);
        }
    }

    private void destroyDocumentWebView() {
        if (webView != null) {
            try {
                webView.removeCallbacks(checkWordSelectionAfterScrollRunnable);
                webView.removeCallbacks(releasePageTurnRunnable);
                webView.removeCallbacks(documentContentAnchorUpdateRunnable);
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
        // The spread's right WebView has its own lifecycle handling in
        // pause/resume; it must be destroyed here too or it leaks its renderer.
        if (rightWebView != null) {
            try {
                rightWebView.animate().cancel();
                rightWebView.setOnTouchListener(null);
                rightWebView.setOnScrollChangeListener(null);
                rightWebView.setWebViewClient(null);
                rightWebView.stopLoading();
                rightWebView.loadUrl("about:blank");
                rightWebView.clearHistory();
                rightWebView.removeAllViews();
                rightWebView.destroy();
            } catch (Throwable ignored) {
            } finally {
                rightWebView = null;
            }
        }
    }

    void closeResourceZip() {
        if (resourceZip != null) {
            try { resourceZip.close(); } catch (IOException ignored) {}
            resourceZip = null;
        }
        epubPackageResources = new DocumentArchiveUtils.EpubPackageResources();
        epubArchiveEntries.clear();
        epubBindingPayloadPaths.clear();
    }

    boolean epubSourcePathMatches(@Nullable String left, @Nullable String right) {
        if (left == null || right == null) return false;
        return normalizeZipPath(left).equals(normalizeZipPath(right));
    }

    int findEpubPageBySourcePath(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return -1;
        String wanted = normalizeZipPath(path);
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (page != null && wanted.equals(normalizeZipPath(page.sourcePath))) return i;
        }
        return -1;
    }

    void evaluateEpubJavascript(@NonNull WebView target,
                                int pageIndex,
                                @NonNull String javascript) {
        WebSettings settings = target.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        target.evaluateJavascript(javascript, value ->
                restoreDocumentJavaScriptPolicy(
                        target, pageIndex, restoreJavascriptOff));
    }

    /** Extracts one already-validated local SMIL audio target on the document worker. */
    File extractEpubMediaOverlayAudio(@NonNull String rawPath) throws IOException {
        if (resourceZip == null) throw new IOException("EPUB archive is unavailable");
        String path = normalizeZipPath(UriPathCodec.decodePercentEscapes(rawPath));
        ZipEntry entry = resourceZip.getEntry(path);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("EPUB media-overlay audio is missing");
        }
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_EPUB_MEDIA_OVERLAY_AUDIO_BYTES) {
            throw new IOException("EPUB media-overlay audio exceeds size limit");
        }

        File dir = new File(getCacheDir(), "epub_media_overlay");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create EPUB media cache");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot >= 0 && lower.length() - dot <= 6
                ? lower.substring(dot) : ".bin";
        String publicationKey = filePath != null ? filePath : "epub";
        if (localFile != null) {
            publicationKey += "|" + localFile.length() + "|" + localFile.lastModified();
        }
        String key = Integer.toHexString(publicationKey.hashCode())
                + "_" + Integer.toHexString(path.hashCode());
        File output = new File(dir, key + extension);
        if (output.isFile() && (declaredSize < 0L || output.length() == declaredSize)) {
            return output;
        }
        File temporary = new File(dir, key + ".partial");
        long total = 0L;
        try (InputStream input = resourceZip.getInputStream(entry);
             FileOutputStream out = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_EPUB_MEDIA_OVERLAY_AUDIO_BYTES) {
                    throw new IOException("EPUB media-overlay audio exceeds size limit");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw e;
        }
        if (output.exists() && !output.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Unable to replace EPUB media cache");
        }
        if (!temporary.renameTo(output)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Unable to finalize EPUB media cache");
        }
        return output;
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
        prevButton.setOnClickListener(v -> turnDocumentDisplayPageBy(-1));
        nextButton.setOnClickListener(v -> turnDocumentDisplayPageBy(1));
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
            documentTtsButton.setOnClickListener(v -> {
                if (epubMediaOverlay().hasOverlayForPage(currentPage)) {
                    epubMediaOverlay().toggleCurrentPage();
                } else {
                    showDocumentTtsDialog();
                }
            });
            // Books with publisher narration use a normal click for that audio;
            // a long press always opens Android TTS so the user retains both.
            documentTtsButton.setOnLongClickListener(v -> {
                showDocumentTtsDialog();
                return true;
            });
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
        // Some Android/OEM builds reveal system bars again while the activity
        // handles rotation in place. Reassert the current chrome/status-bar policy
        // before the rotated WebView is laid out, otherwise hidden chrome can gain
        // a live side/bottom navigation inset and narrow the landscape body.
        applyDocumentSystemBarColors();
        if (isMarkdownDocument()) {
            // Orientation changes the metrics the Markdown visual-page model is
            // computed from. Drop the stable caches (the same reset the
            // text-zoom path performs) so the page count, page turns, and the
            // read-aloud start anchor are recomputed for the new orientation
            // instead of reusing the previous orientation's viewport height.
            lastStableMarkdownContentHeightPx = 0;
            lastStableMarkdownViewportHeightPx = 0;
        }
        if (!pages.isEmpty() && webView != null) {
            showPage(currentPage, 0);
        }
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
                // Preview only. Do not mutate currentPage/markdownVisualCurrentPage
                // before the user releases the thumb: doing so makes autosave,
                // TTS, and orientation changes observe a page that has not been
                // rendered yet, and also loses the real navigation direction in
                // onStopTrackingTouch().
                String label = documentPageStatusLabel(safe + 1, total);
                if (pageStatus != null) pageStatus.setText(label);
                if (topPageStatus != null) topPageStatus.setText(label);
                boolean spread = !isMarkdownDocument() && isLandscapeTwoPageDocumentMode();
                if (prevButton != null) {
                    prevButton.setEnabled(com.readwide.manager.util.SpreadMath.canTurn(
                            safe, -1, total, spread));
                }
                if (nextButton != null) {
                    nextButton.setEnabled(com.readwide.manager.util.SpreadMath.canTurn(
                            safe, 1, total, spread));
                }
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
                    if (target != currentPage) {
                        showPage(target, Integer.compare(target, currentPage));
                    } else {
                        // Releasing the thumb on the current page should restore
                        // the live label/buttons without reloading the WebView or
                        // losing an in-page scroll position.
                        updateDocumentPageStatusViews();
                    }
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
                WebView zoomTarget = documentGestureSourceView != null
                        ? documentGestureSourceView : webView;
                if (zoomTarget != null && zoomTarget.canZoomOut()) {
                    // Already zoomed in (pinch or a previous double-tap): reset only
                    // the page that was actually touched.
                    resetDocumentZoom(zoomTarget);
                } else if (zoomTarget != null) {
                    // At fit size: zoom the touched page, not always the left page.
                    zoomTarget.zoomIn();
                    zoomTarget.zoomIn();
                }
                clearDocumentEdgeArm();
                if (zoomTarget != null) {
                    zoomTarget.postDelayed(() -> {
                        if (!activityDestroyed) documentDoubleTapResetSequence = false;
                    }, 360);
                }
                return true;
            }
        });

        if (rightWebView != null) {
            rightWebView.setOnTouchListener((v, event) ->
                    handleDocumentWebViewTouch((WebView) v, event, false));
        }
        webView.setOnTouchListener((v, event) ->
                handleDocumentWebViewTouch((WebView) v, event, true));
    }

    private boolean handleDocumentWebViewTouch(@NonNull WebView source,
                                               @NonNull MotionEvent event,
                                               boolean primaryView) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            documentInteractionGeneration++;
        }
        if (handleFastDocumentTapPaging(event)) {
            resetWordSwipeTracking(source);
            clearDocumentEdgeArm();
            return true;
        }
        if (!documentTapPagingSequence && handleDocumentTapGesture(source, event)) {
            return true;
        }
        if (!isPagedWebDocument() || documentPageCount() <= 1 || pageTurnInFlight) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                wordSwipeStartX = event.getX();
                wordSwipeStartY = event.getY();
                wordSwipeTriggered = false;
                wordSwipeMovedEnoughForParentDisallow = false;
                documentGestureHadMultiplePointers = false;
                markdownNativeLongPressCanceledForGesture = false;
                wordGestureStartedAtLeftEdge = !source.canScrollHorizontally(-1);
                wordGestureStartedAtRightEdge = !source.canScrollHorizontally(1);
                if (webView != null) webView.removeCallbacks(releasePageTurnRunnable);
                if (primaryView) {
                    source.removeCallbacks(checkWordSelectionAfterScrollRunnable);
                }
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                documentGestureHadMultiplePointers = true;
                documentTapPagingSequence = false;
                return false;

            case MotionEvent.ACTION_MOVE:
                if (primaryView && wordSelectionActive) return false;
                float dx = event.getX() - wordSwipeStartX;
                float dy = event.getY() - wordSwipeStartY;
                if (primaryView && isMarkdownDocument()
                        && !markdownNativeLongPressCanceledForGesture
                        && (Math.abs(dx) > markdownSelectionCancelSlopPx
                        || Math.abs(dy) > markdownSelectionCancelSlopPx)) {
                    source.cancelLongPress();
                    markdownNativeLongPressCanceledForGesture = true;
                }
                if (!wordSwipeMovedEnoughForParentDisallow
                        && Math.abs(dx) > wordSwipeTouchSlop
                        && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                    wordSwipeMovedEnoughForParentDisallow = true;
                    if (source.getParent() != null) {
                        source.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (!wordSwipeTriggered && shouldTurnDocumentPageBySwipe(source, event)) {
                    wordSwipeTriggered = true;
                    if (primaryView && isMarkdownDocument()) source.cancelLongPress();
                    turnDocumentPageBySwipe(pageDeltaForHorizontalSwipe(dx));
                    clearDocumentEdgeArm();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
                // A long-press release must remain owned by WebView's text-selection
                // pipeline. Calling performClick() here could activate a link or clear
                // selection after the user lifts their finger.
                if (isShortStationaryDocumentTap(event)) {
                    source.performClick();
                }
                if (!wordSwipeTriggered && shouldTurnDocumentPageBySwipe(source, event)) {
                    wordSwipeTriggered = true;
                    if (primaryView && isMarkdownDocument()) source.cancelLongPress();
                    turnDocumentPageBySwipe(pageDeltaForHorizontalSwipe(
                            event.getX() - wordSwipeStartX));
                    clearDocumentEdgeArm();
                    return true;
                }
                resetWordSwipeTracking(source);
                if (primaryView) source.postDelayed(checkWordSelectionAfterScrollRunnable, 120);
                return false;

            case MotionEvent.ACTION_CANCEL:
                resetWordSwipeTracking(source);
                if (primaryView) source.postDelayed(checkWordSelectionAfterScrollRunnable, 120);
                return false;

            default:
                return false;
        }
    }

    private void resetWordSwipeTracking() {
        resetWordSwipeTracking(webView);
    }

    private void resetWordSwipeTracking(WebView source) {
        wordSwipeTriggered = false;
        wordSwipeMovedEnoughForParentDisallow = false;
        markdownNativeLongPressCanceledForGesture = false;
        documentTapPagingSequence = false;
        documentGestureHadMultiplePointers = false;
        wordGestureStartedAtLeftEdge = true;
        wordGestureStartedAtRightEdge = true;
        if (source != null && source.getParent() != null) {
            source.getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    private boolean isPagedWebDocument() {
        return "Word".equals(docType) || "HWP".equals(docType) || "EPUB".equals(docType) || "Markdown".equals(docType);
    }

    void clearDocumentEdgeArm() {
        armedDocumentEdgeDirection = 0;
        armedDocumentEdgeTimeMs = 0L;
    }

    private boolean shouldTurnDocumentPageBySwipe(@NonNull WebView source, @NonNull MotionEvent event) {
        if (activityDestroyed || source == null || documentPageCount() <= 1 || pageTurnInFlight) return false;

        float dx = event.getX() - wordSwipeStartX;
        float dy = event.getY() - wordSwipeStartY;
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);
        long duration = event.getEventTime() - event.getDownTime();
        // Once the gesture has reached long-press territory, ownership belongs to
        // WebView text selection. Do not reinterpret a selection drag or handle
        // adjustment as a horizontal page swipe, even when selection callbacks are
        // unavailable for this document type.
        if (duration >= ViewConfiguration.getLongPressTimeout()
                || wordSelectionActive
                || documentGestureHadMultiplePointers) {
            return false;
        }

        // Slightly lighter than before so zoomed Word/EPUB pages do not feel
        // like they need multiple hard swipes. The edge rule below still prevents
        // accidental page turns while the WebView can pan horizontally.
        float threshold = Math.max(dpToPx(28), source.getWidth() * 0.06f);
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
        if (!source.canScrollHorizontally(-1) && !source.canScrollHorizontally(1)) {
            return true;
        }

        if (source.canScrollHorizontally(horizontalScrollDirection)) {
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
        turnDocumentDisplayPageBy(direction);
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
     * Threat model: JavaScript is disabled by default. It stays enabled for Word
     * documents whose HTML the app generates, fixed-layout helper pages, OPF
     * `scripted` spine items, and validated binding-rewritten pages; ordinary EPUB
     * pages enable only short Readwide-owned helper calls and restore the page
     * policy afterward. Some enabled EPUB paths may carry untrusted publisher
     * scripts. Even when reachable, this interface has a single method that takes
     * a boolean and only flips an internal "word is
     * selected" flag: no file or content access, no reflection, no navigation, no
     * eval, no data returned. On targetSdk 17 or higher only methods annotated with
     * JavascriptInterface are exposed, so nothing else such as getClass is callable.
     * The WebView also disables file access, content access, and DOM storage, and
     * serves only a per-open synthetic host bound to the current document archive --
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

    private int getEpubLeftPaddingPx() {
        return prefs != null ? prefs.getEpubLeftPaddingPx() : 30;
    }

    private int getEpubRightPaddingPx() {
        return prefs != null ? prefs.getEpubRightPaddingPx() : 30;
    }

    private int getEpubTopPaddingPx() {
        return prefs != null ? prefs.getEpubTopPaddingPx() : 0;
    }

    private int getEpubBottomPaddingPx() {
        return prefs != null ? prefs.getEpubBottomPaddingPx() : 0;
    }

    void refreshEpubSpacingIfNeeded() {
        applyAndReloadEpubBoundaryImmediately();
    }

    private String currentEpubBoundarySignature() {
        if (!epubPageUsesReaderBoundary(currentPage)) {
            return "";
        }
        return clampEpubBoundaryPx(getEpubLeftPaddingPx()) + ":"
                + clampEpubBoundaryPx(getEpubRightPaddingPx()) + ":"
                + clampEpubBoundaryPx(getEpubTopPaddingPx()) + ":"
                + clampEpubBoundaryPx(getEpubBottomPaddingPx()) + ":"
                + getResources().getDisplayMetrics().densityDpi;
    }

    void markCurrentEpubBoundaryRenderLoaded() {
        loadedEpubBoundarySignature = currentEpubBoundarySignature();
    }

    void applyAndReloadEpubBoundaryImmediately() {
        applyEpubBoundaryMarginsIfNeeded();
        if (webView == null || pages.isEmpty()
                || currentPage < 0 || currentPage >= pages.size()
                || !"EPUB".equals(docType)
                || !epubPageUsesReaderBoundary(currentPage)) {
            return;
        }
        String currentSignature = currentEpubBoundarySignature();
        if (currentSignature.equals(loadedEpubBoundarySignature)) return;

        // Reflowable EPUB keeps page JavaScript disabled. DOM style injection is
        // attempted for the fastest visual update, but a same-page HTML reload is
        // the authoritative path: it guarantees the new boundary CSS is parsed
        // even on System WebView versions that reject evaluateJavascript while
        // JavaScript is disabled. Preserve the current within-page position.
        pendingThemeRefreshScrollX = webView.getScrollX();
        pendingThemeRefreshScrollY = webView.getScrollY();
        restoreDocumentScrollAfterThemeRefresh = true;
        showPage(currentPage, 0);
    }

    void installDocumentFastScroll() {
        if (documentFastScrollController != null
                || documentFastScrollRail == null
                || documentFastScrollThumb == null) {
            return;
        }
        documentFastScrollController = new ProportionalFastScrollController(
                documentFastScrollRail,
                documentFastScrollThumb,
                new ProportionalFastScrollController.ScrollSource() {
                    @Override
                    public boolean isEnabled() {
                        return webView != null && (!"EPUB".equals(docType)
                                || epubPageUsesReaderBoundary(currentPage));
                    }

                    @Override
                    public long scrollRange() {
                        return webView == null ? 0L : Math.max(
                                0L, Math.round(webView.getContentHeight() * (double) webView.getScale()));
                    }

                    @Override
                    public long scrollExtent() {
                        return webView == null ? 0L : Math.max(0, webView.getHeight());
                    }

                    @Override
                    public long scrollOffset() {
                        return webView == null ? 0L : Math.max(0, webView.getScrollY());
                    }

                    @Override
                    public void scrollToFraction(float fraction) {
                        if (webView == null) return;
                        long range = Math.max(0L,
                                Math.round(webView.getContentHeight() * (double) webView.getScale()));
                        long maxOffset = Math.max(0L, range - webView.getHeight());
                        long target = Math.round(
                                Math.max(0f, Math.min(1f, fraction)) * (double) maxOffset);
                        webView.scrollTo(webView.getScrollX(),
                                (int) Math.min(Integer.MAX_VALUE, target));
                    }

                    @Override public void onFastScrollStart() {}
                    @Override public void onFastScrollStop() {}
                });
        documentFastScrollController.install();
    }

    void scheduleDocumentFastScrollUpdate() {
        if (documentFastScrollController != null) {
            documentFastScrollController.scheduleMetricsUpdate();
        }
    }

    void notifyDocumentFastScrollActivity() {
        if (documentFastScrollController != null) {
            documentFastScrollController.notifyScrollActivity();
        }
    }

    void beginDocumentFastScrollContentChange() {
        if (documentFastScrollController != null) {
            documentFastScrollController.beginContentChange();
        }
    }

    private int clampEpubBoundaryPx(int px) {
        int clamped = Math.max(0, Math.min(240, px));
        return Math.round(clamped / 5f) * 5;
    }

    private int getEffectiveEpubBottomMarginPx(int requestedBottomBoundaryPx, int bottomToolbarHeightPx) {
        if (!"EPUB".equals(docType) || requestedBottomBoundaryPx <= 0) return 0;
        return Math.max(0, requestedBottomBoundaryPx);
    }

    void applyEpubBoundaryMarginsIfNeeded() {
        if (webView == null) return;

        // Keep the WebViews full-size. EPUB boundaries belong to the HTML body,
        // not the Android View: View margins/padding either moved the side rail
        // inward or clipped publisher layout without reliably reflowing it.
        for (WebView v : new WebView[]{webView, rightWebView}) {
            if (v == null) continue;
            ViewGroup.LayoutParams rawLp = v.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
                if (lp.leftMargin != 0 || lp.rightMargin != 0
                        || lp.topMargin != 0 || lp.bottomMargin != 0) {
                    lp.setMargins(0, 0, 0, 0);
                    v.setLayoutParams(lp);
                }
            }
            if (v.getPaddingLeft() != 0 || v.getPaddingTop() != 0
                    || v.getPaddingRight() != 0 || v.getPaddingBottom() != 0) {
                v.setPadding(0, 0, 0, 0);
            }
            applyEpubBoundaryCssToLoadedWebView(v);
        }
        scheduleDocumentFastScrollUpdate();
    }

    private boolean epubPageUsesReaderBoundary(int pageIndex) {
        if (!"EPUB".equals(docType) || pageIndex < 0 || pageIndex >= pages.size()) {
            return false;
        }
        Page page = pages.get(pageIndex);
        return !page.fixedLayoutEpubPage
                && !page.imageDominantEpubPage
                && !page.verticalWritingEpubPage;
    }

    private String buildEpubBoundaryCss(@Nullable Page page) {
        if (page == null || !"EPUB".equals(docType)
                || page.fixedLayoutEpubPage
                || page.imageDominantEpubPage
                || page.verticalWritingEpubPage) {
            return "";
        }
        int left = clampEpubBoundaryPx(getEpubLeftPaddingPx());
        int right = clampEpubBoundaryPx(getEpubRightPaddingPx());
        int top = clampEpubBoundaryPx(getEpubTopPaddingPx());
        int bottom = getEffectiveEpubBottomMarginPx(
                clampEpubBoundaryPx(getEpubBottomPaddingPx()), 0);
        float devicePixelsPerCssPixel = Math.max(
                0.1f, getResources().getDisplayMetrics().density);
        return "html{padding:0 !important;}"
                + "body{padding-left:" + physicalPxToCssPx(left, devicePixelsPerCssPixel) + " !important;"
                + "padding-right:" + physicalPxToCssPx(right, devicePixelsPerCssPixel) + " !important;"
                + "padding-top:" + physicalPxToCssPx(top, devicePixelsPerCssPixel) + " !important;"
                + "padding-bottom:" + physicalPxToCssPx(bottom, devicePixelsPerCssPixel) + " !important;"
                + "box-sizing:border-box !important;}";
    }

    private String physicalPxToCssPx(int physicalPx, float devicePixelsPerCssPixel) {
        float cssPx = physicalPx / Math.max(0.1f, devicePixelsPerCssPixel);
        return String.format(Locale.US, "%.4fpx", cssPx);
    }

    void applyEpubBoundaryCssToLoadedWebView(@NonNull WebView target) {
        int pageIndex = target == rightWebView
                ? documentRightSpreadPageIndex() : currentPage;
        boolean reflowableEpub = epubPageUsesReaderBoundary(pageIndex);
        int left = reflowableEpub ? clampEpubBoundaryPx(getEpubLeftPaddingPx()) : 0;
        int right = reflowableEpub ? clampEpubBoundaryPx(getEpubRightPaddingPx()) : 0;
        int top = reflowableEpub ? clampEpubBoundaryPx(getEpubTopPaddingPx()) : 0;
        int bottom = reflowableEpub
                ? getEffectiveEpubBottomMarginPx(clampEpubBoundaryPx(getEpubBottomPaddingPx()), 0)
                : 0;
        String fallbackRatio = String.format(
                Locale.US, "%.4f", Math.max(0.1f, getResources().getDisplayMetrics().density));
        target.evaluateJavascript(
                "(function(){try{"
                        + "var id='readwide-epub-boundary';"
                        + "var s=document.getElementById(id);"
                        + "var enabled=" + reflowableEpub + ";"
                        + "var ratio=Number(window.devicePixelRatio);"
                        + "if(!(ratio>0))ratio=" + fallbackRatio + ";"
                        + "var css=enabled?('html{padding:0 !important;}body{padding-left:'"
                        + "+(" + left + "/ratio)+'px !important;padding-right:'"
                        + "+(" + right + "/ratio)+'px !important;padding-top:'"
                        + "+(" + top + "/ratio)+'px !important;padding-bottom:'"
                        + "+(" + bottom + "/ratio)+'px !important;box-sizing:border-box !important;}'):'';"
                        + "if(!css){if(s&&s.parentNode)s.parentNode.removeChild(s);return true;}"
                        + "if(!s){s=document.createElement('style');s.id=id;"
                        + "(document.head||document.documentElement).appendChild(s);}"
                        + "if(s.textContent!==css)s.textContent=css;"
                        + "if(document.documentElement)void(document.documentElement.offsetWidth);"
                        + "return true;}catch(e){return false;}})()",
                value -> {
                    if (!activityDestroyed) {
                        target.postInvalidateOnAnimation();
                        scheduleDocumentFastScrollUpdate();
                    }
                });
    }

    private void resetDocumentZoom(@NonNull WebView target) {
        WebSettings settings = target.getSettings();
        settings.setTextZoom(documentTextZoomPercent());

        // WebView zoomOut() is step-based.  Use a bounded loop instead of relying
        // on an unbounded canZoomOut() loop so double-tap reset cannot overshoot or
        // get stuck on device-specific WebView implementations.
        for (int i = 0; i < 18 && target.canZoomOut(); i++) {
            if (!target.zoomOut()) break;
        }

        stabilizeDocumentAfterZoomReset(target);
        clearDocumentEdgeArm();
    }

    private void stabilizeDocumentAfterZoomReset(@NonNull WebView target) {
        if (!"EPUB".equals(docType)) return;

        Runnable stabilize = () -> {
            if (activityDestroyed || !"EPUB".equals(docType)) return;
            if (currentEpubPageKeepsOriginalLayout()) {
                if (currentEpubPageIsFixedLayout() && target == webView) {
                    applyFixedLayoutFindOffsetCssIfNeeded();
                }
                target.scrollTo(0, 0);
            }
            clearDocumentEdgeArm();
        };

        target.post(stabilize);
        target.postDelayed(stabilize, 90);
        target.postDelayed(stabilize, 240);
    }

    int documentTextZoomPercent() {
        if ("EPUB".equals(docType) && currentEpubPageKeepsOriginalLayout()) return 100;
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
        if ("EPUB".equals(docType) && currentEpubPageKeepsOriginalLayout()) {
            ShortToast.show(this, localizedText("This EPUB keeps its original page layout.", "이 EPUB은 원본 페이지 배치를 유지합니다."));
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

    private boolean currentEpubPageIsFixedLayout() {
        return epubPageIsFixedLayout(currentPage);
    }

    boolean epubPageIsFixedLayout(int pageIndex) {
        return "EPUB".equals(docType)
                && pageIndex >= 0
                && pageIndex < pages.size()
                && pages.get(pageIndex).fixedLayoutEpubPage;
    }

    boolean epubPageKeepsOriginalLayout(int pageIndex) {
        if (!"EPUB".equals(docType) || pageIndex < 0 || pageIndex >= pages.size()) {
            return epubFixedLayoutLike || epubImagePageLike;
        }
        Page page = pages.get(pageIndex);
        return page.fixedLayoutEpubPage || page.imageDominantEpubPage;
    }

    boolean epubPageUsesVerticalWriting(int pageIndex) {
        return "EPUB".equals(docType)
                && pageIndex >= 0
                && pageIndex < pages.size()
                && pages.get(pageIndex).verticalWritingEpubPage;
    }

    private boolean currentEpubPageKeepsOriginalLayout() {
        return epubPageKeepsOriginalLayout(currentPage);
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
        if (epubMediaOverlayController != null) epubMediaOverlayController.stop(false);
        clearPendingEpubAnchor();
        localDocumentHost = "rw-" + UUID.randomUUID().toString().replace("-", "") + ".local";
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

    private DocumentTtsHighlightController documentTtsHighlightController;

    /** Highlights the currently spoken sentence in the WebView. */
    DocumentTtsHighlightController documentTtsHighlight() {
        if (documentTtsHighlightController == null) {
            documentTtsHighlightController = new DocumentTtsHighlightController(this);
        }
        return documentTtsHighlightController;
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
        if (epubMediaOverlayController != null) epubMediaOverlayController.stop(false);
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
        // Remember the exact within-page position so playback resumes there, not
        // at the page's first character. Consumed by the next speakCurrentPage
        // via the text source; showPage clears it when the page really changes so
        // later manual page turns are unaffected.
        pagedTtsResumeAnchorCharPosition = charPosition;
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

    /**
     * One-shot resume anchor for paged documents (EPUB/Word/HWP): the exact
     * saved char position to begin speaking from, so "continue reading aloud"
     * resumes mid-page instead of from the top of the saved page. -1 = none.
     * Unlike the Markdown anchor this is consumed once: {@code showPage} clears
     * it whenever the displayed page actually changes, so ordinary page turns
     * after the resume speak from the page start as before. Read by
     * {@link DocumentTtsTextSource#getCurrentCharPosition} for paged documents.
     */
    int pagedTtsResumeAnchorCharPosition = -1;

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
                return (epubMediaOverlayController != null && epubMediaOverlayController.isActive())
                        || (documentTtsController != null && documentTtsController.isActive());
            }
            @Override public boolean isPaused() {
                if (epubMediaOverlayController != null && epubMediaOverlayController.isActive()) {
                    return epubMediaOverlayController.isPaused();
                }
                return documentTtsController != null && documentTtsController.isPaused();
            }
            @Override public void togglePlayPause() {
                if (epubMediaOverlayController != null && epubMediaOverlayController.isActive()) {
                    epubMediaOverlayController.toggleCurrentPage();
                    ttsUpdateFloatingCard();
                    return;
                }
                if (documentTtsController == null) return;
                if (documentTtsController.isPaused()) documentTtsController.resumePlayback();
                else documentTtsController.pausePlayback();
                ttsUpdateFloatingCard();
            }
            @Override public void stop() {
                if (epubMediaOverlayController != null && epubMediaOverlayController.isActive()) {
                    epubMediaOverlayController.stop(true);
                }
                if (documentTtsController != null) documentTtsController.stop(true);
                ttsUpdateFloatingCard();
            }
        };
    }

    private EpubMediaOverlayController epubMediaOverlay() {
        if (epubMediaOverlayController == null) {
            epubMediaOverlayController = new EpubMediaOverlayController(this);
        }
        return epubMediaOverlayController;
    }

    void onEpubDisplayedPageChanged(int oldPage, int newPage) {
        if (epubMediaOverlayController != null) {
            epubMediaOverlayController.onDisplayedPageChanged(oldPage, newPage);
        }
    }

    void onEpubPrimaryPageLoaded(@Nullable WebView loadedView) {
        if (epubMediaOverlayController != null) {
            epubMediaOverlayController.onPrimaryPageLoaded(loadedView);
        }
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

    String applyReaderThemeCss(String html, @Nullable Page page) {
        boolean epubPage = "EPUB".equals(docType) && page != null;
        boolean fixedEpubPage = epubPage && page.fixedLayoutEpubPage;
        boolean imageEpubPage = epubPage && page.imageDominantEpubPage;
        boolean verticalEpubPage = epubPage && page.verticalWritingEpubPage;
        if (fixedEpubPage) {
            // Fixed-layout EPUB pages already received their page-canvas fit CSS
            // in prepareFixedLayoutEpubHtml(). Do not add reflow CSS here.
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
                ? "img,svg,video,object,embed{max-width:100% !important;max-height:98vh !important;height:auto;object-fit:contain;}"
                + "svg{width:auto;}"
                : "";
        // Injected after the publisher stylesheets so ordinary reflowable EPUBs
        // stay a single centered reading column in landscape. Fixed-layout and
        // image-page EPUBs return above or use their own page-canvas CSS.
        String epubResponsiveWidthCss = epubPage
                && !fixedEpubPage && !imageEpubPage && !verticalEpubPage
                ? "body{width:100% !important;max-width:980px !important;"
                + "margin-left:auto !important;margin-right:auto !important;}"
                + "@media (orientation:landscape){body{max-width:1120px !important;}}"
                : "";
        // A reflowable spine item can live inside an otherwise pre-paginated
        // image book. Publisher CSS such as Haruko's `body{max-height:28em}` is
        // useful for an old fixed canvas but incorrectly leaves the reflowable
        // information page in only part of the WebView. Reset only the ordinary
        // reflow path; fixed/image canvases and vertical-writing columns own
        // their dimensions separately.
        String epubReflowHeightCss = epubPage
                && !fixedEpubPage && !imageEpubPage && !verticalEpubPage
                ? "html{height:auto !important;min-height:100vh !important;max-height:none !important;}"
                + "body{height:auto !important;min-height:100vh !important;max-height:none !important;}"
                : "";
        String epubBoundaryCss = buildEpubBoundaryCss(page);
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
        // EPUB pages may use a publisher background image as actual page content
        // (especially vertical Japanese and image-like books). A `background:`
        // shorthand would reset that resource while applying the reader color.
        String rootThemeCss = epubPage
                ? "html,body{background-color:" + cssColor(readerBg)
                + " !important;color:" + cssColor(readerFg) + " !important;}"
                : "html,body{background:" + cssColor(readerBg)
                + " !important;color:" + cssColor(readerFg) + " !important;}";
        String flowSafetyCss = verticalEpubPage
                ? "html{height:100vh !important;min-height:100vh !important;max-height:none !important;"
                + "max-width:none !important;margin-top:0 !important;margin-bottom:0 !important;"
                + "margin-inline:0 !important;max-inline-size:none !important;overflow-x:auto !important;}"
                + "body{height:100% !important;min-height:100% !important;max-height:none !important;"
                + "max-width:none !important;margin-inline:0 !important;max-inline-size:none !important;"
                + "overflow:visible !important;}"
                : "html,body{max-width:100%;overflow-x:hidden;}"
                + "body,.page,p,div,span,td,th,li{max-width:100%;overflow-wrap:anywhere;word-break:break-word;}";
        String css = "<style id=\"textview-reader-theme\">" +
                rootThemeCss +
                "a{color:" + cssColor(linkColor) + " !important;}" +
                "body:not(.rw-rendered-doc) table,body:not(.rw-rendered-doc) td,body:not(.rw-rendered-doc) th{border-color:" + cssColor(readerLine) + " !important;}" +
                flowSafetyCss +
                "*{box-sizing:border-box;}" +
                "img,svg,video,.word-img,.textbox,table{max-width:100%;}" +
                "pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;}" +
                renderedPaperCss +
                epubImageFitCss +
                epubResponsiveWidthCss +
                epubReflowHeightCss +
                epubBoundaryCss +
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
        return ("EPUB".equals(docType) && !currentEpubPageKeepsOriginalLayout())
                || "Word".equals(docType)
                || "HWP".equals(docType);
    }

    boolean isDocumentContentAnchorSignature(String signature) {
        return signature != null && signature.startsWith(docType + "_CONTENT_ANCHOR_v");
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

    String buildDocumentContentAnchorJson(int pageIndex, @NonNull JSONObject captured) {
        boolean verticalPage = epubPageUsesVerticalWriting(pageIndex);
        String anchorMode = captured.optString("anchorMode", "");
        String writingMode = captured.optString("writingMode", "");
        boolean verticalSentence = DocumentAnchorMath.isPreciseVerticalSentence(
                verticalPage,
                anchorMode,
                writingMode,
                captured.optBoolean("caretMatched", false),
                captured.optString("elementId", ""),
                captured.optString("text", ""));
        boolean verticalPosition = "vertical-position".equals(anchorMode)
                && (verticalPage || writingMode.startsWith("vertical-"));
        if (verticalPosition) {
            return buildVerticalDomPositionAnchorJson(pageIndex, captured);
        }
        if (!verticalSentence) {
            if ("visible-sentence".equals(anchorMode)) {
                // Never throw away a signed vertical-rl DOM position merely
                // because a transient WebView caret result could not be proven.
                // This fallback remains explicitly non-semantic; a later retry
                // can still replace it with a precise sentence anchor.
                if ((verticalPage || writingMode.startsWith("vertical-"))
                        && captured.has("scrollX")) {
                    return buildVerticalDomPositionAnchorJson(pageIndex, captured);
                }
                return "";
            }
            return buildDocumentContentAnchorJson(
                    pageIndex,
                    captured.optInt("blockIndex", 0),
                    captured.optInt("scrollY", webView != null ? webView.getScrollY() : 0),
                    captured.optInt("maxScrollY", 0),
                    captured.optString("text", ""));
        }
        try {
            int scrollX = captured.optInt("scrollX", webView != null ? webView.getScrollX() : 0);
            int maxScrollX = Math.max(0, captured.optInt("maxScrollX", 0));
            int scrollY = Math.max(0,
                    captured.optInt("scrollY", webView != null ? webView.getScrollY() : 0));
            int maxScrollY = Math.max(0, captured.optInt("maxScrollY", 0));
            JSONObject obj = new JSONObject();
            obj.put("kind", docType + "_CONTENT_ANCHOR_v2");
            obj.put("docType", docType);
            obj.put("pageIndex", Math.max(0, pageIndex));
            obj.put("anchorMode", "visible-sentence");
            String capturedWritingMode = captured.optString("writingMode", "");
            obj.put("writingMode", capturedWritingMode.startsWith("vertical-")
                    ? capturedWritingMode : "vertical-rl");
            obj.put("elementId", captured.optString("elementId", ""));
            obj.put("blockIndex", Math.max(0, captured.optInt("blockIndex", 0)));
            obj.put("charOffset", Math.max(0, captured.optInt("charOffset", 0)));
            obj.put("sentenceOffset", Math.max(0, captured.optInt("sentenceOffset", 0)));
            obj.put("caretMatched", true);
            obj.put("focusRatioX", Math.max(0.0d, Math.min(1.0d,
                    captured.optDouble("focusRatioX", 0.5d))));
            obj.put("focusRatioY", Math.max(0.0d, Math.min(1.0d,
                    captured.optDouble("focusRatioY", 0.25d))));
            obj.put("scrollX", scrollX); // vertical-rl may use a signed DOM scroll position
            obj.put("scrollY", scrollY);
            obj.put("viewportBasis", captured.optString("viewportBasis", ""));
            obj.put("viewportWidth", Math.max(0.0d,
                    captured.optDouble("viewportWidth", 0.0d)));
            obj.put("viewportHeight", Math.max(0.0d,
                    captured.optDouble("viewportHeight", 0.0d)));
            obj.put("viewportScale", Math.max(0.0d,
                    captured.optDouble("viewportScale", 1.0d)));
            obj.put("layoutWidth", Math.max(0.0d,
                    captured.optDouble("layoutWidth", 0.0d)));
            obj.put("layoutHeight", Math.max(0.0d,
                    captured.optDouble("layoutHeight", 0.0d)));
            obj.put("anchorTopInset", Math.max(0.0d,
                    captured.optDouble("anchorTopInset", 0.0d)));
            obj.put("anchorBottomInset", Math.max(0.0d,
                    captured.optDouble("anchorBottomInset", 0.0d)));
            // Chromium's CSS scroll coordinates for vertical-rl have changed
            // conventions across WebView releases (0/positive/negative). Keep
            // the native WebView snapshot as an exact same-viewport fallback;
            // the portable sentence id/text remains authoritative when layout
            // dimensions change.
            obj.put("nativeScrollX", captured.optInt("nativeScrollX",
                    webView != null ? webView.getScrollX() : 0));
            obj.put("nativeScrollY", Math.max(0, captured.optInt("nativeScrollY",
                    webView != null ? webView.getScrollY() : 0)));
            obj.put("nativeViewportWidth", Math.max(0,
                    captured.optInt("nativeViewportWidth", webView != null ? webView.getWidth() : 0)));
            obj.put("nativeViewportHeight", Math.max(0,
                    captured.optInt("nativeViewportHeight", webView != null ? webView.getHeight() : 0)));
            obj.put("scrollRatioX", maxScrollX > 0
                    ? Math.max(0.0d, Math.min(1.0d, Math.abs(scrollX) / (double) maxScrollX)) : 0.0d);
            obj.put("scrollRatio", maxScrollY > 0
                    ? Math.max(0.0d, Math.min(1.0d, scrollY / (double) maxScrollY)) : 0.0d);
            obj.put("text", captured.optString("text", ""));
            obj.put("focusText", captured.optString("focusText", ""));
            // This is presentation metadata only. The exact glyph charOffset
            // above remains the restore and duplicate-detection authority.
            obj.put("columnStartText", captured.optString("columnStartText", ""));
            obj.put("textBefore", captured.optString("textBefore", ""));
            obj.put("textAfter", captured.optString("textAfter", ""));
            return obj.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildVerticalDomPositionAnchorJson(int pageIndex,
                                                       @NonNull JSONObject captured) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("kind", docType + "_CONTENT_ANCHOR_v2");
            obj.put("docType", docType);
            obj.put("pageIndex", Math.max(0, pageIndex));
            obj.put("anchorMode", "vertical-position");
            obj.put("writingMode", "vertical-rl");
            obj.put("scrollX", captured.optInt("scrollX", 0));
            obj.put("scrollY", captured.optInt("scrollY", 0));
            int maxScrollX = Math.max(0, captured.optInt("maxScrollX", 0));
            int maxScrollY = Math.max(0, captured.optInt("maxScrollY", 0));
            int scrollX = captured.optInt("scrollX", 0);
            int scrollY = Math.max(0, captured.optInt("scrollY", 0));
            obj.put("maxScrollX", maxScrollX);
            obj.put("maxScrollY", maxScrollY);
            obj.put("scrollRatioX", maxScrollX > 0
                    ? Math.max(0.0d, Math.min(1.0d, Math.abs(scrollX) / (double) maxScrollX))
                    : 0.0d);
            obj.put("scrollRatio", maxScrollY > 0
                    ? Math.max(0.0d, Math.min(1.0d, scrollY / (double) maxScrollY))
                    : 0.0d);
            obj.put("viewportBasis", captured.optString("viewportBasis", ""));
            obj.put("viewportWidth", Math.max(0.0d,
                    captured.optDouble("viewportWidth", 0.0d)));
            obj.put("viewportHeight", Math.max(0.0d,
                    captured.optDouble("viewportHeight", 0.0d)));
            obj.put("viewportScale", Math.max(0.0d,
                    captured.optDouble("viewportScale", 1.0d)));
            obj.put("layoutWidth", Math.max(0.0d,
                    captured.optDouble("layoutWidth", 0.0d)));
            obj.put("layoutHeight", Math.max(0.0d,
                    captured.optDouble("layoutHeight", 0.0d)));
            obj.put("anchorTopInset", Math.max(0.0d,
                    captured.optDouble("anchorTopInset", 0.0d)));
            obj.put("anchorBottomInset", Math.max(0.0d,
                    captured.optDouble("anchorBottomInset", 0.0d)));
            obj.put("nativeScrollX", captured.optInt("nativeScrollX",
                    webView != null ? webView.getScrollX() : 0));
            obj.put("nativeScrollY", Math.max(0, captured.optInt("nativeScrollY",
                    webView != null ? webView.getScrollY() : 0)));
            obj.put("nativeViewportWidth", Math.max(0,
                    captured.optInt("nativeViewportWidth", webView != null ? webView.getWidth() : 0)));
            obj.put("nativeViewportHeight", Math.max(0,
                    captured.optInt("nativeViewportHeight", webView != null ? webView.getHeight() : 0)));
            return obj.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    String documentContentAnchorSignature(String anchorJson) {
        if (anchorJson != null && !anchorJson.trim().isEmpty()) {
            try {
                String kind = new JSONObject(anchorJson).optString("kind", "");
                if (kind.startsWith(docType + "_CONTENT_ANCHOR_v")) return kind;
            } catch (Exception ignored) {}
        }
        return docType + "_CONTENT_ANCHOR_v1";
    }

    void installDocumentContentAnchorScript() {
        installDocumentContentAnchorScript(this::updateDocumentContentAnchorFromWebView);
    }

    private void installDocumentContentAnchorScript(@Nullable Runnable afterInstall) {
        if (!isRenderedContentAnchorDocument() || webView == null) {
            if (afterInstall != null) afterInstall.run();
            return;
        }
        evaluateDocumentAnchorJavascript(
                DocumentContentAnchorJavascript.installScript(),
                value -> {
                    if (afterInstall != null) afterInstall.run();
                });
    }

    void evaluateDocumentAnchorJavascript(String js, android.webkit.ValueCallback<String> callback) {
        if (webView == null || js == null || js.isEmpty()) return;
        final WebView target = webView;
        final int targetPage = currentPage;
        WebSettings settings = webView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        target.evaluateJavascript(js, value -> {
            restoreDocumentJavaScriptPolicy(target, targetPage, restoreJavascriptOff);
            if (callback != null) callback.onReceiveValue(value);
        });
    }

    void updateDocumentContentAnchorFromWebView() {
        updateDocumentContentAnchorFromWebView(null);
    }

    void updateDocumentContentAnchorFromWebView(Runnable afterUpdate) {
        captureDocumentContentAnchorFromWebView(anchorJson -> {
            if (anchorJson != null && !anchorJson.isEmpty()) {
                lastDocumentContentAnchorJson = anchorJson;
            }
            if (afterUpdate != null) afterUpdate.run();
        });
    }

    private interface DocumentContentAnchorCaptureCallback {
        void onCaptured(@Nullable String anchorJson);
    }

    private void captureDocumentContentAnchorFromWebView(
            @NonNull DocumentContentAnchorCaptureCallback callback) {
        captureDocumentContentAnchorFromWebView(false, callback);
    }

    private void captureDocumentContentAnchorFromWebView(
            boolean captureColumnStart,
            @NonNull DocumentContentAnchorCaptureCallback callback) {
        if (!isRenderedContentAnchorDocument() || webView == null) {
            callback.onCaptured(null);
            return;
        }
        final WebView capturedView = webView;
        final int capturedPage = currentPage;
        final int capturedGeneration = documentAnchorPageGeneration;
        final boolean forceVerticalWriting = epubPageUsesVerticalWriting(capturedPage);
        final int capturedNativeScrollX = capturedView.getScrollX();
        final int capturedNativeScrollY = capturedView.getScrollY();
        final int capturedNativeViewportWidth = capturedView.getWidth();
        final int capturedNativeViewportHeight = capturedView.getHeight();
        final int topOcclusion = documentAnchorOverlayOcclusionPx(documentAppBar, true);
        final int bottomOcclusion = documentAnchorOverlayOcclusionPx(documentBottomChrome, false);
        evaluateDocumentAnchorJavascript(
                DocumentContentAnchorJavascript.installAndCaptureExpression(
                        topOcclusion,
                        bottomOcclusion,
                        Math.max(1, capturedView.getHeight()),
                        forceVerticalWriting,
                        captureColumnStart),
                value -> {
                    String anchorJson = null;
                    try {
                        if (!activityDestroyed
                                && webView == capturedView
                                && currentPage == capturedPage
                                && documentAnchorPageGeneration == capturedGeneration
                                && value != null
                                && !value.trim().isEmpty()
                                && !"null".equals(value)) {
                            JSONObject obj = new JSONObject(value);
                            String anchorMode = obj.optString("anchorMode", "");
                            if (!"script-missing".equals(anchorMode)
                                    && !"capture-error".equals(anchorMode)) {
                                obj.put("nativeScrollX", capturedNativeScrollX);
                                obj.put("nativeScrollY", capturedNativeScrollY);
                                obj.put("nativeViewportWidth", capturedNativeViewportWidth);
                                obj.put("nativeViewportHeight", capturedNativeViewportHeight);
                                anchorJson = buildDocumentContentAnchorJson(capturedPage, obj);
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        callback.onCaptured(anchorJson);
                    }
                });
    }

    private int documentAnchorOverlayOcclusionPx(@Nullable View overlay, boolean top) {
        if (webView == null || overlay == null || overlay.getVisibility() != View.VISIBLE
                || webView.getHeight() <= 0 || overlay.getHeight() <= 0) {
            return 0;
        }
        int[] webLocation = new int[2];
        int[] overlayLocation = new int[2];
        webView.getLocationOnScreen(webLocation);
        overlay.getLocationOnScreen(overlayLocation);
        int webTop = webLocation[1];
        int webBottom = webTop + webView.getHeight();
        int overlap = top
                ? overlayLocation[1] + overlay.getHeight() - webTop
                : webBottom - overlayLocation[1];
        return Math.max(0, Math.min(webView.getHeight(), overlap));
    }

    void scheduleDocumentContentAnchorUpdate() {
        if (!isRenderedContentAnchorDocument() || webView == null) return;
        webView.removeCallbacks(documentContentAnchorUpdateRunnable);
        webView.postDelayed(documentContentAnchorUpdateRunnable, 40);
    }

    void restoreDocumentContentAnchorAfterLoadIfNeeded(@NonNull WebView view) {
        if (!isRenderedContentAnchorDocument()) return;
        if (isDocumentSearchActiveOnCurrentPage()) {
            pendingDocumentRestoreAnchorJson = "";
            return;
        }
        final String anchorJson = pendingDocumentRestoreAnchorJson;
        pendingDocumentRestoreAnchorJson = "";
        final WebView expectedView = view;
        final int expectedPage = currentPage;
        final int expectedPageGeneration = documentAnchorPageGeneration;
        final int requestedInteractionGeneration = documentInteractionGeneration;
        view.postDelayed(() -> {
            if (!documentAnchorRestoreContextMatches(
                    expectedView,
                    expectedPage,
                    expectedPageGeneration,
                    requestedInteractionGeneration)) {
                return;
            }
            if (anchorJson != null && !anchorJson.trim().isEmpty()) {
                if (isVerticalPositionDocumentContentAnchor(anchorJson)
                        && !hasVerticalDomPosition(anchorJson)) {
                    restoreDocumentContentAnchorFallback(expectedView, anchorJson);
                    expectedView.postDelayed(() -> {
                        if (documentAnchorRestoreContextMatches(
                                expectedView,
                                expectedPage,
                                expectedPageGeneration,
                                requestedInteractionGeneration)) {
                            restoreDocumentContentAnchorFallback(expectedView, anchorJson);
                            updateDocumentContentAnchorFromWebView();
                        }
                    }, 180L);
                    return;
                }
                String restoreScript = documentContentAnchorRestoreScript(anchorJson, expectedView);
                final boolean verticalSentenceAnchor =
                        isVerticalSentenceDocumentContentAnchor(anchorJson);
                evaluateDocumentAnchorJavascript(
                        restoreScript,
                        value -> {
                            if (!documentAnchorRestoreContextMatches(
                                    expectedView,
                                    expectedPage,
                                    expectedPageGeneration,
                                    requestedInteractionGeneration)) {
                                return;
                            }
                            boolean ok = "true".equals(value);
                            if (!ok) {
                                restoreDocumentContentAnchorFallback(expectedView, anchorJson);
                                expectedView.postDelayed(this::updateDocumentContentAnchorFromWebView, 60);
                                return;
                            }
                            if (verticalSentenceAnchor) {
                                // Embedded Japanese fonts can settle vertical
                                // column geometry after onPageFinished. Reapply
                                // the stable sentence id once, then verify that
                                // the target is actually visible. Finding a DOM
                                // node alone does not prove scrollIntoView moved a
                                // vertical-rl WebView.
                                expectedView.postDelayed(() -> {
                                    if (!documentAnchorRestoreContextMatches(
                                            expectedView,
                                            expectedPage,
                                            expectedPageGeneration,
                                            requestedInteractionGeneration)) {
                                        return;
                                    }
                                    evaluateDocumentAnchorJavascript(
                                            documentContentAnchorRestoreScript(anchorJson, expectedView),
                                            ignored -> {
                                                if (documentAnchorRestoreContextMatches(
                                                        expectedView,
                                                        expectedPage,
                                                        expectedPageGeneration,
                                                        requestedInteractionGeneration)) {
                                                    expectedView.postDelayed(
                                                            () -> verifyRestoredVerticalDocumentAnchor(
                                                                    expectedView,
                                                                    expectedPage,
                                                                    expectedPageGeneration,
                                                                    requestedInteractionGeneration,
                                                                    anchorJson),
                                                            40);
                                                }
                                            });
                                }, 180);
                            } else {
                                expectedView.postDelayed(this::updateDocumentContentAnchorFromWebView, 80);
                            }
                        });
            } else {
                updateDocumentContentAnchorFromWebView();
            }
        }, 90);
    }

    /**
     * Restores the last-resort native-only vertical position when the viewport
     * still matches. Normal sentence anchors must stay in DOM/CSS coordinates;
     * {@link WebView#getScrollX()} is not equivalent to a vertical-rl DOM
     * scroller on every WebView implementation.
     */
    private boolean applyExactVerticalNativeAnchorIfCompatible(@NonNull WebView targetView,
                                                                @NonNull String anchorJson) {
        // A sentence anchor is restored in CSS/DOM coordinates. Applying
        // WebView.getScrollX() afterward can overwrite the correct result,
        // because vertical-rl pages often scroll their DOM element while the
        // native WebView offset remains zero. Native offsets are authoritative
        // only for the explicit last-resort vertical-position payload.
        if (!isVerticalPositionDocumentContentAnchor(anchorJson)) return false;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            if (!obj.has("nativeScrollX") || !obj.has("nativeViewportWidth")
                    || !obj.has("nativeViewportHeight")) {
                return false;
            }
            int savedWidth = Math.max(0, obj.optInt("nativeViewportWidth", 0));
            int savedHeight = Math.max(0, obj.optInt("nativeViewportHeight", 0));
            int currentWidth = Math.max(0, targetView.getWidth());
            int currentHeight = Math.max(0, targetView.getHeight());
            if (savedWidth <= 0 || savedHeight <= 0 || currentWidth <= 0 || currentHeight <= 0) {
                return false;
            }
            int widthTolerance = Math.max(2, Math.round(currentWidth * 0.01f));
            int heightTolerance = Math.max(2, Math.round(currentHeight * 0.01f));
            if (Math.abs(savedWidth - currentWidth) > widthTolerance
                    || Math.abs(savedHeight - currentHeight) > heightTolerance) {
                return false;
            }
            targetView.scrollTo(
                    obj.optInt("nativeScrollX", targetView.getScrollX()),
                    Math.max(0, obj.optInt("nativeScrollY", targetView.getScrollY())));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean documentAnchorRestoreContextMatches(@NonNull WebView expectedView,
                                                        int expectedPage,
                                                        int expectedPageGeneration,
                                                        int expectedInteractionGeneration) {
        return !activityDestroyed
                && webView == expectedView
                && currentPage == expectedPage
                && documentAnchorPageGeneration == expectedPageGeneration
                && documentInteractionGeneration == expectedInteractionGeneration
                && isRenderedContentAnchorDocument();
    }

    private String documentContentAnchorRestoreScript(@NonNull String anchorJson,
                                                      @NonNull WebView targetView) {
        return documentContentAnchorRestoreScript(anchorJson, targetView, false);
    }

    private String documentContentAnchorRestoreScript(@NonNull String anchorJson,
                                                       @NonNull WebView targetView,
                                                       boolean forceSemantic) {
        int topOcclusion = documentAnchorOverlayOcclusionPx(documentAppBar, true);
        int bottomOcclusion = documentAnchorOverlayOcclusionPx(documentBottomChrome, false);
        String escaped = cssQuote(anchorJson);
        return DocumentContentAnchorJavascript.installScript() + ";"
                + DocumentContentAnchorJavascript.viewportInsetAssignment(
                topOcclusion,
                bottomOcclusion,
                Math.max(1, targetView.getHeight()))
                + "window.__rwDocForceVerticalWriting="
                 + epubPageUsesVerticalWriting(currentPage)
                 + ";(function(){try{var a=JSON.parse('" + escaped
                 + "');if(window.__rwDocScrollToAnchor){return !!window.__rwDocScrollToAnchor(a,"
                 + forceSemantic + ");}"
                 + "return false;}catch(e){return false;}})()";
    }

    private void verifyRestoredVerticalDocumentAnchor(@NonNull WebView expectedView,
                                                      int expectedPage,
                                                      int expectedPageGeneration,
                                                      int expectedInteractionGeneration,
                                                      @NonNull String anchorJson) {
        if (!documentAnchorRestoreContextMatches(
                expectedView,
                expectedPage,
                expectedPageGeneration,
                expectedInteractionGeneration)) {
            return;
        }
        String verifyScript = documentContentAnchorRestoreViewportScript(expectedView)
                + "(function(){try{var a=JSON.parse('" + cssQuote(anchorJson)
                + "');return !!(window.__rwDocVerticalAnchorIsVisible"
                + "&&window.__rwDocVerticalAnchorIsVisible(a));}catch(e){return false;}})()";
        evaluateDocumentAnchorJavascript(verifyScript, value -> {
            if (!documentAnchorRestoreContextMatches(
                    expectedView,
                    expectedPage,
                    expectedPageGeneration,
                    expectedInteractionGeneration)) {
                return;
            }
            if (!"true".equals(value)) {
                evaluateDocumentAnchorJavascript(
                        documentContentAnchorRestoreScript(anchorJson, expectedView, true),
                        ignored -> expectedView.postDelayed(
                                () -> verifyForcedSemanticVerticalDocumentAnchor(
                                        expectedView,
                                        expectedPage,
                                        expectedPageGeneration,
                                        expectedInteractionGeneration,
                                        anchorJson),
                                60));
                return;
            }
            expectedView.postDelayed(this::updateDocumentContentAnchorFromWebView, 60);
        });
    }

    private void verifyForcedSemanticVerticalDocumentAnchor(@NonNull WebView expectedView,
                                                             int expectedPage,
                                                             int expectedPageGeneration,
                                                             int expectedInteractionGeneration,
                                                             @NonNull String anchorJson) {
        if (!documentAnchorRestoreContextMatches(
                expectedView,
                expectedPage,
                expectedPageGeneration,
                expectedInteractionGeneration)) {
            return;
        }
        String verifyScript = documentContentAnchorRestoreViewportScript(expectedView)
                + "(function(){try{var a=JSON.parse('" + cssQuote(anchorJson)
                + "');return !!(window.__rwDocVerticalAnchorIsVisible"
                + "&&window.__rwDocVerticalAnchorIsVisible(a));}catch(e){return false;}})()";
        evaluateDocumentAnchorJavascript(verifyScript, value -> {
            if ("true".equals(value)
                    && documentAnchorRestoreContextMatches(
                    expectedView,
                    expectedPage,
                    expectedPageGeneration,
                    expectedInteractionGeneration)) {
                updateDocumentContentAnchorFromWebView();
            }
        });
    }

    private String documentContentAnchorRestoreViewportScript(@NonNull WebView targetView) {
        return DocumentContentAnchorJavascript.installScript() + ";"
                + DocumentContentAnchorJavascript.viewportInsetAssignment(
                documentAnchorOverlayOcclusionPx(documentAppBar, true),
                documentAnchorOverlayOcclusionPx(documentBottomChrome, false),
                Math.max(1, targetView.getHeight()));
    }

    void restoreDocumentContentAnchorFallback(@NonNull WebView targetView, String anchorJson) {
        if (anchorJson == null || anchorJson.trim().isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            if (isVerticalPositionDocumentContentAnchor(anchorJson)
                    && obj.has("nativeScrollX")
                    && applyExactVerticalNativeAnchorIfCompatible(targetView, anchorJson)) {
                return;
            }
            if (isVerticalPositionDocumentContentAnchor(anchorJson)) {
                // Raw native and CSS positions are meaningful only in the
                // viewport that produced them. The primary JavaScript restore
                // handles portable DOM ratios; if that failed and native
                // geometry is incompatible, leave the safe page start intact.
                return;
            }
            if (isVerticalSentenceDocumentContentAnchor(anchorJson)
                    && obj.has("scrollX")) {
                int cssScrollX = obj.optInt("scrollX", 0);
                int cssScrollY = Math.max(0, obj.optInt("scrollY", 0));
                // Anchor coordinates come from window.scrollX/Y and therefore
                // use CSS pixels. WebView.scrollTo() expects Android physical
                // pixels, so applying the raw values there repeats the same
                // density bug as the old occlusion calculation.
                evaluateDocumentAnchorJavascript(
                        "(function(){window.scrollTo(" + cssScrollX + ","
                                + cssScrollY + ");return true;})()",
                        null);
                return;
            }
            int scrollY = obj.optInt("scrollY", -1);
            if (scrollY >= 0) {
                evaluateDocumentAnchorJavascript(
                        "(function(){window.scrollTo(0," + Math.max(0, scrollY)
                                + ");return true;})()",
                        null);
                return;
            }
            double ratio = obj.optDouble("scrollRatio", -1.0d);
            if (ratio >= 0.0d) {
                double clampedRatio = Math.max(0.0d, Math.min(1.0d, ratio));
                        evaluateDocumentAnchorJavascript(
                        "(function(){var m=Math.max(0,Math.max(document.documentElement.scrollHeight,"
                                + "document.body?document.body.scrollHeight:0)-"
                                + "(window.visualViewport&&window.visualViewport.height>0"
                                + "?window.visualViewport.height:window.innerHeight));"
                                + "window.scrollTo(0,Math.round(m*" + clampedRatio
                                + "));return true;})()",
                        null);
            }
        } catch (Exception ignored) {}
    }

    boolean isVerticalSentenceDocumentContentAnchor(String anchorJson) {
        if (anchorJson == null || anchorJson.trim().isEmpty()) return false;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            return DocumentAnchorMath.isStoredVerticalSentence(
                    false,
                    obj.optString("anchorMode", ""),
                    obj.optString("writingMode", ""),
                    obj.optString("elementId", ""),
                    obj.optString("text", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPreciseVerticalSentenceDocumentContentAnchor(String anchorJson) {
        if (anchorJson == null || anchorJson.trim().isEmpty()) return false;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            return DocumentAnchorMath.isPreciseVerticalSentence(
                    false,
                    obj.optString("anchorMode", ""),
                    obj.optString("writingMode", ""),
                    obj.optBoolean("caretMatched", false),
                    obj.optString("elementId", ""),
                    obj.optString("text", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean isVerticalPositionDocumentContentAnchor(String anchorJson) {
        if (anchorJson == null || anchorJson.trim().isEmpty()) return false;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            return "vertical-position".equals(obj.optString("anchorMode", ""))
                    && obj.optString("writingMode", "").startsWith("vertical-")
                    && obj.has("nativeScrollX")
                    && obj.optInt("nativeViewportWidth", 0) > 0
                    && obj.optInt("nativeViewportHeight", 0) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasVerticalDomPosition(String anchorJson) {
        if (anchorJson == null || anchorJson.trim().isEmpty()) return false;
        try {
            JSONObject obj = new JSONObject(anchorJson);
            return "vertical-position".equals(obj.optString("anchorMode", ""))
                    && obj.has("scrollX")
                    && obj.optInt("viewportWidth", 0) > 0
                    && obj.optInt("viewportHeight", 0) > 0;
        } catch (Exception ignored) {
            return false;
        }
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
                        + "window.__rwMdTextAtTop=function(){"
                        + "try{var ok=function(c){return c&&c.startContainer&&c.startContainer.nodeType===3&&(c.startContainer.nodeValue||'').trim();};"
                        + "var r=null,ys=[8,24,48,80,120],hy=-1;"
                        + "for(var i=0;i<ys.length&&!r;i++){var c=document.caretRangeFromPoint(Math.floor(window.innerWidth/2),ys[i]);"
                        + "if(ok(c)){r=c;hy=ys[i];}}"
                        + "if(!r)return '';"
                        // The center-x hit lands mid-line; sweep x left-to-right at
                        // the same y and take the LEFTMOST text hit = line start.
                        + "var xs=[6,14,28,56,Math.floor(window.innerWidth/4)];"
                        + "for(var j=0;j<xs.length;j++){var c2=document.caretRangeFromPoint(xs[j],hy);"
                        + "if(ok(c2)){r=c2;break;}}"
                        + "var out='',w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
                        + "w.currentNode=r.startContainer;var cur=r.startContainer,off=r.startOffset;"
                        + "while(cur&&out.length<200){out+=(cur.nodeValue||'').substring(off);off=0;cur=w.nextNode();}"
                        + "return out.replace(/\\s+/g,' ').trim().substring(0,160);}catch(e){return '';}};"
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
                "(function(){try{var a=window.__rwMdAnchorAtTop?window.__rwMdAnchorAtTop():{offset:0,line:1,text:''};"
                        + "a.vtext=window.__rwMdTextAtTop?window.__rwMdTextAtTop():'';return a;}catch(e){return {offset:0,line:1,text:'',vtext:''};}})()",
                value -> {
                    try {
                        if (value != null && !value.trim().isEmpty() && !"null".equals(value)) {
                            JSONObject obj = new JSONObject(value);
                            lastMarkdownSourceOffset = clampMarkdownSourceOffset(obj.optInt("offset", 0));
                            lastMarkdownSourceLine = Math.max(1, obj.optInt("line", markdownSourceLineForOffset(lastMarkdownSourceOffset)));
                            // Prefer the character-precise viewport-top text
                            // (caret-based); fall back to the block's text.
                            String vtext = obj.optString("vtext", "");
                            lastMarkdownAnchorText = !vtext.isEmpty() ? vtext : obj.optString("text", "");
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

    boolean isLandscapeTwoPageDocumentMode() {
        return "EPUB".equals(docType)
                && epubImagePageLike
                && pages.size() > 1
                && getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    int documentRightSpreadPageIndex() {
        return com.readwide.manager.util.SpreadMath.rightIndex(
                currentPage, pages.size(), isLandscapeTwoPageDocumentMode());
    }

    boolean hasVisibleDocumentRightSpreadPage() {
        return documentRightSpreadPageIndex() >= 0;
    }

    int clampDocumentPageIndex(int page) {
        return com.readwide.manager.util.SpreadMath.clampIndex(page, pages.size());
    }

    void turnDocumentDisplayPageBy(int direction) {
        if (direction == 0 || documentPageCount() <= 1) return;
        if (isMarkdownDocument()) {
            pageMarkdownBy(direction);
            return;
        }
        int target = com.readwide.manager.util.SpreadMath.turnTarget(
                currentPage, direction, pages.size(), isLandscapeTwoPageDocumentMode());
        if (target != currentPage) {
            showPage(target, Integer.signum(direction));
        }
    }

    int currentDisplayDocumentPageIndex() {
        return isMarkdownDocument() ? markdownVisualCurrentPage : currentPage;
    }

    int currentDisplayDocumentPageNumber() {
        return currentDisplayDocumentPageIndex() + 1;
    }

    String documentPageStatusLabel(int page, int total) {
        int startIndex = com.readwide.manager.util.SpreadMath.clampIndex(page - 1, total);
        boolean spread = !isMarkdownDocument() && isLandscapeTwoPageDocumentMode();
        int endIndex = com.readwide.manager.util.SpreadMath.visibleEndIndex(
                startIndex, total, spread);
        if (endIndex > startIndex) {
            return String.format(Locale.getDefault(), "%d-%d / %d",
                    startIndex + 1, endIndex + 1, total);
        }
        return String.format(Locale.getDefault(), "%d / %d", startIndex + 1, total);
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
        int currentIndex = Math.max(0, Math.min(total - 1, currentDisplayDocumentPageIndex()));
        boolean spread = !isMarkdownDocument() && isLandscapeTwoPageDocumentMode();
        if (prevButton != null) {
            prevButton.setEnabled(com.readwide.manager.util.SpreadMath.canTurn(
                    currentIndex, -1, total, spread));
        }
        if (nextButton != null) {
            nextButton.setEnabled(com.readwide.manager.util.SpreadMath.canTurn(
                    currentIndex, 1, total, spread));
        }
    }

    /** Toolbar read-aloud button visibility; see the integration controller. */
    void updateDocumentTtsButtonVisibility() {
        documentTtsIntegration().updateButtonVisibility();
    }

    String documentBaseUrlForPage(@NonNull Page p) {
        String baseUrl = "https://" + localDocumentHost + "/";
        if ("EPUB".equals(docType) && p.sourcePath != null) {
            String parent = parentPath(p.sourcePath);
            baseUrl = "https://" + localDocumentHost + EPUB_PREFIX + parent;
            if (!baseUrl.endsWith("/")) baseUrl += "/";
        }
        return baseUrl;
    }

    String localDocumentHost() {
        return localDocumentHost;
    }

    String documentBaseUrlForPageLoad(@NonNull Page page,
                                      @NonNull WebView target,
                                      int pageIndex) {
        int generation;
        if (target == rightWebView) {
            generation = ++rightDocumentPageLoadGeneration;
            rightDocumentPageLoadPage = pageIndex;
        } else {
            generation = ++primaryDocumentPageLoadGeneration;
            primaryDocumentPageLoadPage = pageIndex;
        }
        return documentBaseUrlForPage(page) + "?rw_load=" + generation;
    }

    void invalidateDocumentPageLoad(@Nullable WebView target) {
        if (target == rightWebView) {
            rightDocumentPageLoadGeneration++;
            rightDocumentPageLoadPage = -1;
        } else {
            primaryDocumentPageLoadGeneration++;
            primaryDocumentPageLoadPage = -1;
        }
    }

    boolean isCurrentDocumentPageLoad(@NonNull WebView target,
                                      @Nullable String loadedUrl) {
        if (loadedUrl == null || loadedUrl.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(loadedUrl);
            String value = uri.getQueryParameter("rw_load");
            if (value == null || value.isEmpty()) return false;
            int generation = Integer.parseInt(value);
            if (target == rightWebView) {
                return generation == rightDocumentPageLoadGeneration
                        && rightDocumentPageLoadPage == documentRightSpreadPageIndex();
            }
            return generation == primaryDocumentPageLoadGeneration
                    && primaryDocumentPageLoadPage == currentPage;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * JavaScript stays disabled for ordinary EPUB pages. It is enabled only for
     * Word's renderer helpers, fixed-layout EPUB geometry helpers, or a spine
     * item that the OPF explicitly marks as scripted (including a page whose
     * custom object binding was replaced by a local sandboxed handler).
     */
    boolean documentPageRequiresJavaScript(int pageIndex) {
        if ("Word".equals(docType)) return true;
        if (!"EPUB".equals(docType)
                || pageIndex < 0 || pageIndex >= pages.size()) {
            return false;
        }
        Page page = pages.get(pageIndex);
        return page != null && (page.fixedLayoutEpubPage || page.scriptedEpubPage);
    }

    int documentPageIndexForWebView(@Nullable WebView target) {
        return target == rightWebView ? documentRightSpreadPageIndex() : currentPage;
    }

    void restoreDocumentJavaScriptPolicy(@Nullable WebView target,
                                         int pageIndex,
                                         boolean wasTemporarilyEnabled) {
        if (!wasTemporarilyEnabled || target == null || activityDestroyed) return;
        if (documentPageIndexForWebView(target) != pageIndex) return;
        target.getSettings().setJavaScriptEnabled(documentPageRequiresJavaScript(pageIndex));
    }

    String documentHtmlForDisplay(@NonNull Page p, int pageIndex) {
        String htmlForDisplay = p.html;
        if ("EPUB".equals(docType) && p.fixedLayoutEpubPage) {
            htmlForDisplay = prepareFixedLayoutEpubHtml(
                    htmlForDisplay,
                    p.imageDominantEpubPage,
                    epubPhysicalSpreadSlot(pageIndex, p));
        }
        if ("EPUB".equals(docType) && p.scriptedEpubPage) {
            htmlForDisplay = injectIntoHtmlHeadStart(
                    htmlForDisplay,
                    "<script>" + epubReadingSystemJavascript() + "</script>");
        }
        htmlForDisplay = applyDocumentSearchMarkupForDisplay(htmlForDisplay, pageIndex);
        return applyReaderThemeCss(htmlForDisplay, p);
    }

    private String epubReadingSystemJavascript() {
        return "(function(){try{if(!navigator.epubReadingSystem){"
                + "Object.defineProperty(navigator,'epubReadingSystem',{value:{"
                + "name:'Readwide',version:'1.0.16',layoutStyle:'paginated',"
                + "hasFeature:function(f){return ['dom-manipulation','layout-changes',"
                + "'mouse-events','spine-scripting','touch-events'].indexOf(String(f))>=0;}"
                + "},configurable:false});}}catch(e){}})();";
    }

    private String injectIntoHtmlHeadStart(String html, String injection) {
        if (html == null) html = "";
        if (injection == null || injection.isEmpty()) return html;
        java.util.regex.Matcher head = java.util.regex.Pattern
                .compile("(?i)<head[^>]*>").matcher(html);
        if (head.find()) {
            return html.substring(0, head.end()) + injection + html.substring(head.end());
        }
        java.util.regex.Matcher root = java.util.regex.Pattern
                .compile("(?i)<(?:html|body)[^>]*>").matcher(html);
        if (root.find()) {
            return html.substring(0, root.start()) + "<head>" + injection + "</head>"
                    + html.substring(root.start());
        }
        return "<head>" + injection + "</head>" + html;
    }

    private int epubPhysicalSpreadSlot(int pageIndex, @NonNull Page page) {
        if (!page.imageDominantEpubPage) return EpubSpreadSlotMath.CENTER;
        int secondary = documentRightSpreadPageIndex();
        // Edge-align only a genuine image/image spread. If a mostly-image EPUB
        // ends with an About or other text page, pulling just the image page to
        // the seam creates an unbalanced mixed spread. Keep both pages centered
        // in that case; a lone odd final page is centered for the same reason.
        if (secondary < 0
                || currentPage < 0 || currentPage >= pages.size()
                || secondary >= pages.size()
                || !pages.get(currentPage).imageDominantEpubPage
                || !pages.get(secondary).imageDominantEpubPage) {
            return EpubSpreadSlotMath.CENTER;
        }
        boolean rtl = prefs != null
                && prefs.getEpubPageDirection() == PrefsManager.EPUB_PAGE_DIRECTION_RTL;
        return EpubSpreadSlotMath.physicalSlot(
                pageIndex, currentPage, secondary, rtl);
    }

    void updateDocumentSpreadVisibility() {
        if (rightWebView == null) return;
        boolean showRight = hasVisibleDocumentRightSpreadPage();
        rightWebView.setVisibility(showRight ? View.VISIBLE : View.GONE);
        rightWebView.setBackgroundColor(readerBg);
        if (webView != null) webView.setBackgroundColor(readerBg);
        if (documentSpreadContainer != null) {
            documentSpreadContainer.setBackgroundColor(readerBg);
            boolean rtlSpread = showRight && prefs != null
                    && prefs.getEpubPageDirection() == PrefsManager.EPUB_PAGE_DIRECTION_RTL;
            documentSpreadContainer.setLayoutDirection(rtlSpread
                    ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        }
    }

    /** True while the right spread view holds real page content. */
    private boolean rightWebViewHasContent;

    void loadDocumentRightSpreadPageIfNeeded() {
        if (rightWebView == null) return;
        int right = documentRightSpreadPageIndex();
        if (right < 0) {
            // No right page (portrait, non-EPUB, or the last odd page). Blank the
            // view only if it actually holds content: in portrait this runs on
            // every page turn, and an unconditional loadUrl would ping the
            // renderer each time for nothing.
            if (rightWebViewHasContent) {
                rightWebViewHasContent = false;
                invalidateDocumentPageLoad(rightWebView);
                rightWebView.loadUrl("about:blank");
            }
            return;
        }
        rightWebViewHasContent = true;
        Page p = pages.get(right);
        rightWebView.loadDataWithBaseURL(
                documentBaseUrlForPageLoad(p, rightWebView, right),
                documentHtmlForDisplay(p, right),
                "text/html",
                "UTF-8",
                null);
    }

    void showPage(int page, int direction) {
        if (pendingEpubAnchorPage >= 0 && page != pendingEpubAnchorPage) {
            clearPendingEpubAnchor();
        }
        // Invalidate the one-shot resume anchor once we move to a page other than
        // the one it points into. The resume path sets the anchor and then calls
        // showPage(anchorPage), which keeps it (same page); any later turn to a
        // different page clears it so subsequent pages speak from their start.
        if (pagedTtsResumeAnchorCharPosition >= 0 && documentTtsTextSource != null) {
            int anchorPage = documentTtsTextSource.pageIndexForChar(
                    pagedTtsResumeAnchorCharPosition);
            if (page != anchorPage) {
                pagedTtsResumeAnchorCharPosition = -1;
            }
        }
        updateDocumentTtsButtonVisibility();
        pageDisplay().showPage(page, direction);
    }

    void snapDocumentWebViewToPageTopIfNeeded(@NonNull WebView view) {
        if (!snapDocumentPageTopAfterLoad || isMarkdownDocument()) return;
        if (pendingEpubAnchorPage == currentPage
                && (pendingEpubCfi != null || !pendingEpubAnchorFragment.isEmpty())) {
            // A cross-page EPUB anchor owns the final position. In particular,
            // do not let vertical-rl's delayed logical-start alignment pull a
            // successfully resolved CFI back to the chapter start.
            snapDocumentPageTopAfterLoad = false;
            return;
        }
        if (isRenderedContentAnchorDocument()
                && pendingDocumentRestoreAnchorJson != null
                && !pendingDocumentRestoreAnchorJson.trim().isEmpty()) {
            // The anchor restore owns the final position. Scheduling the normal
            // vertical-rl logical-start alignment here would run again after the
            // first restore and visibly pull the bookmark back to page start.
            snapDocumentPageTopAfterLoad = false;
            return;
        }
        if (isDocumentSearchActiveOnCurrentPage()) {
            snapDocumentPageTopAfterLoad = false;
            return;
        }
        snapDocumentPageTopAfterLoad = false;
        final int expectedPage = currentPage;
        final boolean verticalEpubPage = "EPUB".equals(docType)
                && expectedPage >= 0
                && expectedPage < pages.size()
                && pages.get(expectedPage).verticalWritingEpubPage;
        view.post(() -> {
            if (activityDestroyed || webView == null || webView != view
                    || currentPage != expectedPage) return;
            if (verticalEpubPage) {
                alignVerticalEpubPageToLogicalStart(view, expectedPage);
                // WebView can finish the main document before an embedded font
                // or late stylesheet completes its final vertical-column
                // geometry. Recheck once while the page-turn lock is still in
                // force; a later page turn invalidates this by page number.
                view.postDelayed(
                        () -> alignVerticalEpubPageToLogicalStart(view, expectedPage),
                        140L);
            } else {
                webView.scrollTo(0, 0);
            }
            updateDocumentPageStatusViews(false);
            if (isRenderedContentAnchorDocument()) {
                webView.postDelayed(this::updateDocumentContentAnchorFromWebView, 80);
            }
        });
    }

    /**
     * CSS vertical-rl advances along a logical horizontal block axis. Native
     * WebView.scrollTo(0, 0) uses physical coordinates and can leave the first
     * column well inside the viewport. Ask the DOM to align its first real body
     * child to logical block-start, then restore Y so this operation cannot eat
     * publisher top padding or Android's outer safe frame.
     */
    private void alignVerticalEpubPageToLogicalStart(@NonNull WebView target,
                                                      int expectedPage) {
        if (activityDestroyed || webView != target || currentPage != expectedPage
                || expectedPage < 0 || expectedPage >= pages.size()
                || !pages.get(expectedPage).verticalWritingEpubPage) {
            return;
        }
        WebSettings settings = target.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        String js = "(function(){try{"
                + "var y=window.scrollY||0;"
                + "var b=document.body;var first=b&&b.firstElementChild;"
                + "if(first){try{first.scrollIntoView({block:'start',inline:'nearest',behavior:'auto'});}"
                + "catch(e){first.scrollIntoView(true);}"
                + "var x=window.scrollX||0;window.scrollTo(x,y);return true;}"
                + "window.scrollTo(0,y);return false;"
                + "}catch(e){return false;}})()";
        target.evaluateJavascript(js, value -> {
            restoreDocumentJavaScriptPolicy(target, expectedPage, restoreJavascriptOff);
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
            final int requestedPage = currentPage;
            final int requestedGeneration = documentAnchorPageGeneration;
            captureFreshRenderedDocumentBookmark(
                    requestedPage,
                    requestedGeneration,
                    epubPageUsesVerticalWriting(requestedPage),
                    1,
                    afterSave);
            return;
        }
        addRenderedDocumentBookmarkFromCurrentAnchor();
        if (afterSave != null) afterSave.run();
    }

    private void captureFreshRenderedDocumentBookmark(int requestedPage,
                                                       int requestedGeneration,
                                                       boolean requireVerticalSentence,
                                                       int retriesRemaining,
                                                       @Nullable Runnable afterSave) {
        captureFreshRenderedDocumentBookmark(
                requestedPage,
                requestedGeneration,
                requireVerticalSentence,
                retriesRemaining,
                null,
                afterSave);
    }

    private void captureFreshRenderedDocumentBookmark(int requestedPage,
                                                       int requestedGeneration,
                                                       boolean requireVerticalSentence,
                                                       int retriesRemaining,
                                                       @Nullable String retainedPositionAnchor,
                                                       @Nullable Runnable afterSave) {
        if (activityDestroyed
                || webView == null
                || currentPage != requestedPage
                || documentAnchorPageGeneration != requestedGeneration) {
            if (afterSave != null) afterSave.run();
            return;
        }

        // A bookmark button press must never reuse a scroll callback from an
        // earlier point in the same spine item. In vertical EPUBs the integer
        // Bookmark.charPosition remains the spine page index; only this fresh
        // v2 sentence anchor can identify the visible column and sentence.
        webView.removeCallbacks(documentContentAnchorUpdateRunnable);
        lastDocumentContentAnchorJson = "";
        captureDocumentContentAnchorFromWebView(true, anchorJson -> {
                if (activityDestroyed
                        || currentPage != requestedPage
                        || documentAnchorPageGeneration != requestedGeneration) {
                    if (afterSave != null) afterSave.run();
                    return;
                }

                boolean validVerticalSentence =
                        isPreciseVerticalSentenceDocumentContentAnchor(anchorJson);
                String bestPositionAnchor = isVerticalPositionDocumentContentAnchor(anchorJson)
                        ? anchorJson : retainedPositionAnchor;
                if (anchorJson != null && !anchorJson.isEmpty()
                        && (!requireVerticalSentence || validVerticalSentence)) {
                    lastDocumentContentAnchorJson = anchorJson;
                    addRenderedDocumentBookmarkFromCurrentAnchor();
                    if (afterSave != null) afterSave.run();
                    return;
                }

                if (requireVerticalSentence && retriesRemaining > 0 && webView != null) {
                    webView.postDelayed(
                            () -> captureFreshRenderedDocumentBookmark(
                                    requestedPage,
                                     requestedGeneration,
                                     true,
                                     retriesRemaining - 1,
                                     bestPositionAnchor,
                                     afterSave),
                            120L);
                    return;
                }

                if (requireVerticalSentence
                        && isVerticalPositionDocumentContentAnchor(bestPositionAnchor)) {
                    // A precise glyph could not be proven, but the DOM scroll
                    // coordinates still preserve the current vertical page.
                    // Keep this explicitly classified as a position fallback so
                    // it can never masquerade as a sentence anchor.
                    lastDocumentContentAnchorJson = bestPositionAnchor;
                    addRenderedDocumentBookmarkFromCurrentAnchor();
                    if (afterSave != null) afterSave.run();
                    return;
                }

                if (!requireVerticalSentence) {
                    // Horizontal rendered documents can still retain their page
                    // location when a transient WebView capture is unavailable.
                    addRenderedDocumentBookmarkFromCurrentAnchor();
                    if (afterSave != null) afterSave.run();
                    return;
                }

                // Do not pretend WebView.getScrollX/Y() is an EPUB position.
                // Some vertical-rl WebViews keep those values at zero while a
                // DOM scroller moves horizontally, which produced multiple
                // bookmarks that all reopened at the same page-start location.
                // A genuine sentence anchor or signed DOM-position fallback is
                // required; otherwise report the capture failure honestly.
                ShortToast.show(this, getString(R.string.file_operation_failed));
                if (afterSave != null) afterSave.run();
        });
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

    private String documentBookmarkExcerpt(String anchorJson, int page, int total) {
        if (isVerticalSentenceDocumentContentAnchor(anchorJson)) {
            try {
                JSONObject obj = new JSONObject(anchorJson);
                String preview = DocumentAnchorMath.bookmarkPreview(
                        obj.optString("columnStartText", ""),
                        obj.optString("focusText", ""),
                        obj.optString("text", ""),
                        obj.optInt("sentenceOffset", 0),
                        96);
                if (!preview.isEmpty()) return preview;
            } catch (Exception ignored) {}
        }
        return documentBookmarkPageExcerpt(page, total);
    }

    void addRenderedDocumentBookmarkFromCurrentAnchor() {
        int bookmarkPosition = currentDisplayDocumentPageIndex();
        int bookmarkPageNumber = bookmarkPosition + 1;
        int total = documentPageCount();
        String anchorJson = isRenderedContentAnchorDocument() ? lastDocumentContentAnchorJson : "";
        String excerpt = documentBookmarkExcerpt(anchorJson, bookmarkPageNumber, total);
        String anchorSignature = documentContentAnchorSignature(anchorJson);
        Bookmark legacyPageOnlyCandidate = null;
        boolean newVerticalSentenceAnchor =
                isPreciseVerticalSentenceDocumentContentAnchor(anchorJson);
        for (Bookmark b : bookmarkManager.getBookmarksForFile(filePath)) {
            if (b.getCharPosition() != bookmarkPosition) continue;
            if (!isRenderedContentAnchorDocument()
                    || sameDocumentContentAnchorSpot(b, anchorJson, bookmarkPosition)) {
                updateRenderedDocumentBookmark(
                        b, bookmarkPageNumber, total, bookmarkPosition,
                        excerpt, anchorJson, anchorSignature);
                return;
            }
            if (newVerticalSentenceAnchor
                    && legacyPageOnlyCandidate == null
                    && isLegacyPageOnlyRenderedDocumentBookmark(b)) {
                // Upgrade the old Page N / Position N row in place. Leaving it
                // beside the new v2 sentence bookmark makes a successful fix
                // look broken and keeps navigation to the obsolete page start.
                legacyPageOnlyCandidate = b;
            }
        }
        if (legacyPageOnlyCandidate != null) {
            updateRenderedDocumentBookmark(
                    legacyPageOnlyCandidate,
                    bookmarkPageNumber,
                    total,
                    bookmarkPosition,
                    excerpt,
                    anchorJson,
                    anchorSignature);
            return;
        }

        Bookmark bookmark = new Bookmark(filePath, fileName, bookmarkPosition, bookmarkPageNumber, excerpt);
        bookmark.setPageNumber(bookmarkPageNumber);
        bookmark.setTotalPages(total);
        bookmark.setEndPosition(bookmarkPosition);
        if (isRenderedContentAnchorDocument()) {
            bookmark.setContentAnchorJson(anchorJson);
            bookmark.setPageLayoutSignature(anchorSignature);
        }
        bookmarkManager.addBookmark(bookmark);
        ShortToast.show(this, getString(R.string.bookmark_saved));
    }

    private boolean isLegacyPageOnlyRenderedDocumentBookmark(@NonNull Bookmark bookmark) {
        String storedAnchor = bookmark.getContentAnchorJson();
        if (storedAnchor == null || storedAnchor.trim().isEmpty()) return true;
        try {
            JSONObject obj = new JSONObject(storedAnchor);
            return DocumentAnchorMath.isUpgradeableLegacyVerticalAnchor(
                    obj.optString("kind", ""), obj.optString("anchorMode", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateRenderedDocumentBookmark(@NonNull Bookmark bookmark,
                                                int pageNumber,
                                                int totalPages,
                                                int bookmarkPosition,
                                                @NonNull String excerpt,
                                                @NonNull String anchorJson,
                                                @NonNull String anchorSignature) {
        bookmark.setLineNumber(pageNumber);
        bookmark.setPageNumber(pageNumber);
        bookmark.setTotalPages(totalPages);
        bookmark.setExcerpt(excerpt);
        bookmark.setEndPosition(bookmarkPosition);
        if (isRenderedContentAnchorDocument()) {
            bookmark.setContentAnchorJson(anchorJson);
            bookmark.setPageLayoutSignature(anchorSignature);
        }
        bookmarkManager.updateBookmark(bookmark);
        ShortToast.show(this, getString(R.string.bookmark_updated));
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
            boolean oldVertical = "visible-sentence".equals(oldObj.optString("anchorMode", ""));
            boolean newVertical = "visible-sentence".equals(newObj.optString("anchorMode", ""));
            boolean oldNativeVertical = "vertical-position".equals(
                    oldObj.optString("anchorMode", ""));
            boolean newNativeVertical = "vertical-position".equals(
                    newObj.optString("anchorMode", ""));
            if (oldNativeVertical || newNativeVertical) {
                if (!(oldNativeVertical && newNativeVertical)) return false;
                return DocumentAnchorMath.isSameVerticalPositionSpot(
                        oldObj.has("scrollX"),
                        oldObj.optInt("scrollX", 0),
                        oldObj.optInt("scrollY", 0),
                        newObj.has("scrollX"),
                        newObj.optInt("scrollX", 0),
                        newObj.optInt("scrollY", 0));
            }
            if (oldVertical || newVertical) {
                if (!(oldVertical && newVertical)) return false;
                String oldId = oldObj.optString("elementId", "").trim();
                String newId = newObj.optString("elementId", "").trim();
                int oldBlock = oldObj.optInt("blockIndex", -1);
                int newBlock = newObj.optInt("blockIndex", -2);
                int oldOffset = oldObj.optInt("charOffset", -100000);
                int newOffset = newObj.optInt("charOffset", 100000);
                return DocumentAnchorMath.isSameVerticalSentenceSpot(
                        oldId, newId, oldBlock, newBlock, oldOffset, newOffset);
            }
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
        saveReadingStateWithFreshAnchor(1);
    }

    private void saveReadingStateWithFreshAnchor(int retriesRemaining) {
        if (filePath == null || prefs == null || !prefs.getAutoSavePosition()) return;
        if (isRenderedContentAnchorDocument() && webView != null && !activityDestroyed) {
            // DOM capture is asynchronous. Persist only from its callback so a
            // horizontal move through vertical-rl columns cannot save the prior
            // visible sentence. onDestroy falls through to the last cached
            // capture because its WebView may no longer execute JavaScript.
            final int requestedPage = currentPage;
            final int requestedGeneration = documentAnchorPageGeneration;
            captureDocumentContentAnchorFromWebView(anchorJson -> {
                if (activityDestroyed) {
                    // The WebView may no longer execute JavaScript during
                    // teardown, so retain the last successfully captured state.
                    saveReadingStateFromCachedAnchor();
                } else if (currentPage == requestedPage
                        && documentAnchorPageGeneration == requestedGeneration) {
                    // A failed fresh capture must not persist an earlier glyph
                    // from another horizontal column on the same spine page.
                    lastDocumentContentAnchorJson = anchorJson != null ? anchorJson : "";
                    saveReadingStateFromCachedAnchor();
                } else if (retriesRemaining > 0) {
                    saveReadingStateWithFreshAnchor(retriesRemaining - 1);
                } else {
                    saveReadingStateFromCachedAnchor();
                }
            });
            return;
        }
        saveReadingStateFromCachedAnchor();
    }

    private void saveReadingStateFromCachedAnchor() {
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
            state.setCharPosition(currentPage);
            state.setScrollY(0);
            state.setPageNumber(currentPage + 1);
            state.setTotalPages(pages.size());
            if (isRenderedContentAnchorDocument() && lastDocumentContentAnchorJson != null && !lastDocumentContentAnchorJson.isEmpty()) {
                state.setContentAnchorJson(lastDocumentContentAnchorJson);
                state.setEncoding(documentContentAnchorSignature(lastDocumentContentAnchorJson));
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
        epubPackageResources = DocumentArchiveUtils.findEpubPackageResources(resourceZip);
        epubArchiveEntries.clear();
        epubArchiveEntries.addAll(epubArchiveEntryPaths());
        epubFixedLayoutLike = detectEpubFixedLayoutLike(resourceZip);
        epubHasDocumentFont = detectEpubDeclaredFont(resourceZip);
        epubVerticalWritingLike = DocumentArchiveUtils.detectEpubVerticalWritingLike(resourceZip);
        List<DocumentArchiveUtils.EpubSpineItem> spine =
                DocumentArchiveUtils.findEpubSpineItems(resourceZip);
        if (spine.isEmpty()) {
            spine = new ArrayList<>();
            for (String path : findEpubHtmlEntries(resourceZip)) {
                spine.add(new DocumentArchiveUtils.EpubSpineItem(
                        path, mimeForPath(path), "", ""));
            }
        }

        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> rawHtmlPages = new ArrayList<>();
        ArrayList<Boolean> fixedLayoutPages = new ArrayList<>();
        ArrayList<DocumentArchiveUtils.EpubSpineItem> loadedItems = new ArrayList<>();
        ArrayList<EpubSmilParser.Timeline> mediaOverlayTimelines = new ArrayList<>();
        ArrayList<Boolean> scriptedPages = new ArrayList<>();
        Map<String, String> bindingHandlers = epubBindingHandlerPaths();
        Set<String> archiveEntries = epubArchiveEntries;
        for (DocumentArchiveUtils.EpubSpineItem item : spine) {
            if (item == null) continue;
            String path = item.path;
            ZipEntry entry = resourceZip.getEntry(path);
            if (entry == null || entry.isDirectory()) continue;
            boolean pageFixedLayout = item.isImage()
                    || item.isFixedLayoutOverride()
                    || (epubFixedLayoutLike && !item.isReflowableOverride());
            String html;
            if (item.isImage()) {
                int[] dimensions = readEpubImageDimensions(entry, item.mediaType);
                html = DocumentArchiveUtils.buildDirectImageSpineHtml(
                        path, dimensions[0], dimensions[1]);
            } else {
                html = readZipEntryString(resourceZip, entry);
                html = EpubBindingRewriter.normalizeXhtmlCdataForHtmlParser(html);
            }
            EpubBindingRewriter.RewriteResult bindingRewrite =
                    EpubBindingRewriter.rewriteBoundObjects(
                            html,
                            path,
                            bindingHandlers,
                            "https://" + localDocumentHost,
                            archiveEntries);
            if (bindingRewrite.requiresJavaScript() && !item.isScripted()) {
                // A binding iframe requires WebView JavaScript, but that must
                // not activate publisher scripts/event handlers in a parent
                // spine item which the OPF did not declare as scripted.
                html = EpubBindingRewriter.sanitizeNonScriptedParent(html);
                bindingRewrite = EpubBindingRewriter.rewriteBoundObjects(
                        html,
                        path,
                        bindingHandlers,
                        "https://" + localDocumentHost,
                        archiveEntries);
            }
            html = bindingRewrite.html;
            epubBindingPayloadPaths.addAll(bindingRewrite.payloadPaths);
            String title = titleFromHtml(html);
            if (title.isEmpty()) title = fileNameFromPath(path);
            paths.add(path);
            titles.add(title);
            rawHtmlPages.add(html);
            fixedLayoutPages.add(pageFixedLayout);
            loadedItems.add(item);
            scriptedPages.add(item.isScripted() || bindingRewrite.requiresJavaScript());
            EpubSmilParser.Timeline timeline = null;
            if (item.hasMediaOverlay()) {
                try {
                    EpubSmilParser.Timeline parsed = EpubSmilParser.parse(
                            resourceZip, item.mediaOverlayPath);
                    if (parsed != null && !parsed.isEmpty()) timeline = parsed;
                } catch (IOException ignored) {
                    // A broken optional overlay must not make the EPUB unreadable.
                }
            }
            mediaOverlayTimelines.add(timeline);
        }

        epubImagePageLike = DocumentArchiveUtils.detectEpubImagePageLike(
                rawHtmlPages, epubFixedLayoutLike);
        for (int i = 0; i < rawHtmlPages.size(); i++) {
            String html = rawHtmlPages.get(i);
            String title = titles.get(i);
            String path = paths.get(i);
            boolean pageFixedLayout = fixedLayoutPages.get(i);
            DocumentArchiveUtils.EpubSpineItem item = loadedItems.get(i);
            boolean imageDominantPage = EpubImagePageClassifier.isImageDominantPage(
                    html, pageFixedLayout);
            boolean centerAsCover = !pageFixedLayout && !epubImagePageLike
                    && !epubVerticalWritingLike
                    && isEpubCoverLikePage(html, title, path, i);
            String prepared;
            if (pageFixedLayout) {
                prepared = html;
            } else if (epubImagePageLike && imageDominantPage) {
                prepared = prepareImagePageEpubHtml(html);
            } else {
                prepared = prepareEpubHtml(html, centerAsCover);
            }
            pages.add(new Page(
                    title,
                    prepared,
                    path,
                    imageDominantPage,
                    pageFixedLayout,
                    epubVerticalWritingLike && !imageDominantPage,
                    scriptedPages.get(i),
                    item.manifestId,
                    item.itemRefId,
                    item.spineIndex,
                    mediaOverlayTimelines.get(i)));
        }
    }

    private Map<String, String> epubBindingHandlerPaths() {
        Map<String, String> handlers = new LinkedHashMap<>();
        if (epubPackageResources == null) return handlers;
        for (Map.Entry<String, DocumentArchiveUtils.EpubBinding> entry
                : epubPackageResources.bindingsByMediaType.entrySet()) {
            DocumentArchiveUtils.EpubBinding binding = entry.getValue();
            if (binding != null && !binding.handlerPath.isEmpty()) {
                handlers.put(entry.getKey(), binding.handlerPath);
            }
        }
        return handlers;
    }

    private Set<String> epubArchiveEntryPaths() {
        if (!epubArchiveEntries.isEmpty()) return epubArchiveEntries;
        Set<String> result = new HashSet<>();
        if (resourceZip == null) return result;
        Enumeration<? extends ZipEntry> entries = resourceZip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry != null && !entry.isDirectory()) {
                result.add(normalizeZipPath(entry.getName()));
            }
        }
        return result;
    }

    private int[] readEpubImageDimensions(@NonNull ZipEntry entry,
                                           @Nullable String mediaType) {
        if (resourceZip == null) return new int[]{0, 0};
        String mime = mediaType != null ? mediaType.toLowerCase(Locale.ROOT) : "";
        try {
            if (mime.contains("svg") || entry.getName().toLowerCase(Locale.ROOT).endsWith(".svg")) {
                String svg = readZipEntryString(resourceZip, entry);
                return extractSvgViewBoxSize(svg);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream input = resourceZip.getInputStream(entry)) {
                BitmapFactory.decodeStream(input, null, options);
            }
            return new int[]{Math.max(0, options.outWidth), Math.max(0, options.outHeight)};
        } catch (Throwable ignored) {
            return new int[]{0, 0};
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
                    resourceZip, file != null ? file.getName() : fileName,
                    localDocumentHost, WORD_PARAGRAPHS_PER_PAGE);
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

    // Serves only this publication's per-open synthetic host, and only entries
    // that exist INSIDE the
    // current document archive resourceZip or the user-selected font -- never an
    // arbitrary filesystem path. getEntry and getInputStream read from the in-memory
    // zip, so a crafted ../ path in document HTML cannot escape to the filesystem;
    // together with disabled file access this leaves no local-file read surface.
    WebResourceResponse interceptLocalResource(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("data".equalsIgnoreCase(scheme) || "blob".equalsIgnoreCase(scheme)
                || "about".equalsIgnoreCase(scheme)) {
            return null;
        }
        if (!"https".equalsIgnoreCase(scheme)
                || !localDocumentHost.equalsIgnoreCase(uri.getHost())) {
            return blockedDocumentResource(403, "Forbidden");
        }
        String path = uri.getPath();
        if (path == null) return blockedDocumentResource(404, "Not Found");

        if (path.startsWith(FONT_PREFIX)) {
            WebResourceResponse font = interceptSelectedDocumentFont();
            return font != null ? font : blockedDocumentResource(404, "Not Found");
        }

        if (resourceZip == null) return blockedDocumentResource(404, "Not Found");

        String zipPath;
        if (path.startsWith(EPUB_PREFIX)) {
            zipPath = path.substring(EPUB_PREFIX.length());
        } else if (path.startsWith(WORD_PREFIX)) {
            zipPath = path.substring(1);
        } else {
            return blockedDocumentResource(404, "Not Found");
        }

        zipPath = UriPathCodec.decodePercentEscapes(zipPath);
        if (hasArchiveParentTraversal(zipPath)) {
            return blockedDocumentResource(403, "Forbidden");
        }
        zipPath = normalizeZipPath(zipPath);

        ZipEntry entry = resourceZip.getEntry(zipPath);
        if (entry == null || entry.isDirectory()) {
            return blockedDocumentResource(404, "Not Found");
        }
        try {
            byte[] data;
            try (InputStream is = resourceZip.getInputStream(entry)) {
                data = readAllBytes(is);
            }
            boolean epubResource = path.startsWith(EPUB_PREFIX);
            String mime = epubResource && epubPackageResources != null
                    ? epubPackageResources.mediaTypeForPath(zipPath)
                    : mimeForPath(zipPath);
            String encoding = null;
            if ("text/css".equals(mime)) {
                String css = DocumentTextDecoder.decode(data);
                data = EpubCssCompatibility.addWebViewAliases(css)
                        .getBytes(StandardCharsets.UTF_8);
                encoding = "UTF-8";
            } else if (epubResource && isEpubBindingHandlerPath(zipPath)
                    && ("application/xhtml+xml".equals(mime)
                    || "text/html".equals(mime))) {
                String handlerHtml = DocumentTextDecoder.decode(data);
                data = injectIntoHtmlHeadStart(
                                handlerHtml,
                                "<script>" + epubReadingSystemJavascript() + "</script>")
                        .getBytes(StandardCharsets.UTF_8);
                encoding = "UTF-8";
            } else if (epubResource
                    && epubPackageResources != null
                    && epubPackageResources.hasBindings()
                    && epubBindingPayloadPaths.contains(zipPath)) {
                String xml = DocumentTextDecoder.decode(data);
                data = EpubBindingRewriter.rewriteXmlResourceUris(
                                xml,
                                zipPath,
                                "https://" + localDocumentHost,
                                epubArchiveEntryPaths())
                        .getBytes(StandardCharsets.UTF_8);
                // Binding handlers consume these through XMLHttpRequest.responseXML;
                // the foreign object media type itself is not necessarily +xml.
                mime = "application/xml";
                encoding = "UTF-8";
            } else if (mime.startsWith("text/")
                    || "image/svg+xml".equals(mime)
                    || "application/xhtml+xml".equals(mime)
                    || "application/javascript".equals(mime)
                    || "application/json".equals(mime)
                    || "application/xml".equals(mime)
                    || mime.endsWith("+xml")) {
                encoding = "UTF-8";
            }
            if (epubResource) {
                Map<String, String> headers = new LinkedHashMap<>();
                // Binding handlers run in an opaque sandbox. CORS is therefore
                // needed for their explicitly local XHR payloads; no external
                // host or filesystem path is exposed by this interceptor.
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Cache-Control", "no-store");
                return new WebResourceResponse(
                        mime, encoding, 200, "OK", headers,
                        new ByteArrayInputStream(data));
            }
            return new WebResourceResponse(mime, encoding, new ByteArrayInputStream(data));
        } catch (IOException e) {
            return blockedDocumentResource(404, "Not Found");
        }
    }

    private WebResourceResponse blockedDocumentResource(int status, @NonNull String reason) {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                status,
                reason,
                java.util.Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(new byte[0]));
    }

    private boolean hasArchiveParentTraversal(@Nullable String path) {
        if (path == null) return true;
        for (String segment : path.replace('\\', '/').split("/")) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }

    private boolean isEpubBindingHandlerPath(@Nullable String path) {
        if (path == null || epubPackageResources == null) return false;
        String wanted = normalizeZipPath(path);
        for (DocumentArchiveUtils.EpubBinding binding
                : epubPackageResources.bindingsByMediaType.values()) {
            if (binding != null
                    && wanted.equals(normalizeZipPath(binding.handlerPath))) {
                return true;
            }
        }
        return false;
    }

    boolean handleEpubInternalNavigation(@NonNull WebView sourceView, Uri uri) {
        if (uri == null || !"EPUB".equals(docType) || pages == null || pages.isEmpty()) return false;
        if ("about".equalsIgnoreCase(uri.getScheme())
                && "blank".equalsIgnoreCase(uri.getSchemeSpecificPart())) {
            return false;
        }
        // Scripted EPUB support remains archive-local. A publisher script or
        // link cannot navigate this reader WebView to a remote/network origin.
        if (!localDocumentHost.equalsIgnoreCase(uri.getHost())) return true;
        String path = uri.getPath();
        if (path == null || !path.startsWith(EPUB_PREFIX)) return true;

        String zipPath = path.substring(EPUB_PREFIX.length());
        zipPath = UriPathCodec.decodePercentEscapes(zipPath);
        zipPath = normalizeZipPath(zipPath);
        String fragment = uri.getFragment();
        if (fragment != null && fragment.trim().startsWith("epubcfi(")) {
            int sourcePageIndex = sourceView == rightWebView
                    ? documentRightSpreadPageIndex() : currentPage;
            String sourceParent = "";
            if (sourcePageIndex >= 0 && sourcePageIndex < pages.size()) {
                Page sourcePage = pages.get(sourcePageIndex);
                sourceParent = sourcePage == null || sourcePage.sourcePath == null
                        ? "" : normalizeZipPath(parentPath(sourcePage.sourcePath));
            }
            String packagePath = epubPackageResources != null
                    ? normalizeZipPath(epubPackageResources.packagePath) : "";
            // A CFI's package component addresses the selected OPF. Permit a
            // literal fragment-only link resolved against the current chapter's
            // base directory, or an explicit link to that selected OPF; reject
            // arbitrary other archive paths rather than applying the CFI to the
            // wrong package/spine.
            if (!zipPath.equals(sourceParent) && !zipPath.equals(packagePath)) return true;
            return handleEpubCfiNavigation(sourceView, fragment);
        }

        // loadDataWithBaseURL uses the chapter's parent directory as its base.
        // Therefore a literal href="#note" reaches shouldOverrideUrlLoading as
        // that directory plus the fragment, not as the chapter file path.
        int sourcePageIndex = sourceView == rightWebView
                ? documentRightSpreadPageIndex() : currentPage;
        if (uri.getFragment() != null
                && sourcePageIndex >= 0
                && sourcePageIndex < pages.size()) {
            Page sourcePage = pages.get(sourcePageIndex);
            String sourceParent = sourcePage == null || sourcePage.sourcePath == null
                    ? "" : normalizeZipPath(parentPath(sourcePage.sourcePath));
            if (zipPath.equals(sourceParent)) {
                scrollEpubAnchor(sourceView, uri.getFragment());
                return true;
            }
        }
        if (zipPath.isEmpty()) return true;

        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (page == null || page.sourcePath == null) continue;
            if (normalizeZipPath(page.sourcePath).equals(zipPath)) {
                if (i == currentPage
                        || (i == documentRightSpreadPageIndex() && sourceView == rightWebView)) {
                    // Keep the themed loadData document in place and scroll the
                    // actual source pane. Letting WebView navigate to the raw ZIP
                    // entry would discard injected theme/search markup.
                    scrollEpubAnchor(sourceView, uri.getFragment());
                    return true;
                }
                pendingEpubAnchorPage = i;
                pendingEpubAnchorFragment = uri.getFragment() == null
                        ? "" : uri.getFragment();
                showPage(i, Integer.compare(i, currentPage));
                return true;
            }
        }
        // Do not let auxiliary or handler XHTML replace the primary reader
        // document. Recognized spine navigation has already returned above.
        return true;
    }

    void applyPendingEpubAnchorAfterPageLoad(@NonNull WebView loadedView) {
        if ("EPUB".equals(docType)
                && pendingEpubAnchorPage == currentPage
                && pendingEpubCfi != null) {
            EpubCfi cfi = pendingEpubCfi;
            clearPendingEpubAnchor();
            scrollEpubCfi(loadedView, currentPage, cfi);
            return;
        }
        if (!"EPUB".equals(docType)
                || pendingEpubAnchorPage != currentPage
                || pendingEpubAnchorFragment.isEmpty()) {
            if (pendingEpubAnchorPage == currentPage) clearPendingEpubAnchor();
            return;
        }
        String fragment = pendingEpubAnchorFragment;
        clearPendingEpubAnchor();
        scrollEpubAnchor(loadedView, fragment);
    }

    private void scrollEpubAnchor(@NonNull WebView targetView, @Nullable String fragment) {
        if (fragment == null || fragment.isEmpty()) return;
        String quoted = JSONObject.quote(fragment);
        evaluateEpubJavascript(
                targetView,
                documentPageIndexForWebView(targetView),
                "(function(){var k=" + quoted
                        + ";var e=document.getElementById(k);"
                        + "if(!e){var n=document.getElementsByName(k);if(n.length)e=n[0];}"
                        + "if(e){e.scrollIntoView(true);return true;}return false;})()");
    }

    private boolean handleEpubCfiNavigation(@NonNull WebView sourceView,
                                            @NonNull String rawCfi) {
        EpubCfi cfi = EpubCfi.parse(rawCfi);
        if (cfi == null) return true; // scoped unsupported forms stay inside the book
        int targetPage = findEpubPageForCfi(cfi);
        if (targetPage < 0) return true;

        if (targetPage == currentPage) {
            scrollEpubCfi(webView != null ? webView : sourceView, targetPage, cfi);
            return true;
        }
        int rightPage = documentRightSpreadPageIndex();
        if (targetPage == rightPage && rightWebView != null) {
            scrollEpubCfi(rightWebView, targetPage, cfi);
            return true;
        }
        pendingEpubAnchorPage = targetPage;
        pendingEpubAnchorFragment = "";
        pendingEpubCfi = cfi;
        showPage(targetPage, Integer.compare(targetPage, currentPage));
        return true;
    }

    private int findEpubPageForCfi(@NonNull EpubCfi cfi) {
        String assertedItemRef = cfi.itemRefIdAssertion();
        if (!assertedItemRef.isEmpty()) {
            for (int i = 0; i < pages.size(); i++) {
                Page page = pages.get(i);
                if (page != null && assertedItemRef.equals(page.itemRefId)) return i;
            }
        }
        int spineIndex = cfi.spineItemIndex();
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (page != null && page.spineIndex == spineIndex) return i;
        }
        return -1;
    }

    private void scrollEpubCfi(@NonNull WebView targetView,
                               int targetPage,
                               @NonNull EpubCfi cfi) {
        WebSettings settings = targetView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        targetView.evaluateJavascript(
                EpubCfiJavascript.installAndScrollExpression(cfi),
                value -> restoreDocumentJavaScriptPolicy(
                        targetView, targetPage, restoreJavascriptOff));
    }

    private void clearPendingEpubAnchor() {
        pendingEpubAnchorPage = -1;
        pendingEpubAnchorFragment = "";
        pendingEpubCfi = null;
    }

    String prepareFixedLayoutEpubHtml(String html) {
        return prepareFixedLayoutEpubHtml(
                html,
                EpubImagePageClassifier.isImageDominantPage(html, true),
                EpubSpreadSlotMath.CENTER);
    }

    private String prepareFixedLayoutEpubHtml(String html,
                                               boolean imageDominantPage,
                                               int physicalSpreadSlot) {
        if (html == null) html = "";
        int spreadSlot = imageDominantPage ? physicalSpreadSlot : EpubSpreadSlotMath.CENTER;
        float halfGap = EPUB_SPREAD_GAP_CSS_PX / 2f;
        int[] viewport = extractFixedLayoutViewportSize(html);
        if (viewport[0] <= 0 || viewport[1] <= 0) {
            viewport = extractSvgViewBoxSize(html);
        }
        String css;
        if (viewport[0] > 0 && viewport[1] > 0) {
            String bodySizeAndOverflow = imageDominantPage
                    ? "height:" + viewport[1] + "px !important;"
                    + "min-height:" + viewport[1] + "px !important;"
                    + "overflow:hidden !important;"
                    : "height:auto !important;min-height:" + viewport[1]
                    + "px !important;overflow:visible !important;";
            String mediaCanvasCss = imageDominantPage
                    ? "body>:only-child:not(img):not(svg):not(canvas):not(video):not(object):not(embed){"
                    + "width:100% !important;height:100% !important;max-width:100% !important;max-height:100% !important;}"
                    + "body img,body svg,body canvas,body video,body object,body embed{"
                    + "max-width:100% !important;object-fit:contain !important;}"
                    : "body>div:only-child,body>svg:only-child{width:100% !important;height:100% !important;}"
                    + "body img,body svg{max-width:100% !important;max-height:100% !important;}";
            css = "<style id=\"textview-fixed-layout-center\">"
                    + "html{margin:0 !important;padding:0 !important;width:100vw !important;min-height:100vh !important;"
                    + "background-color:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                    + "body{margin:0 !important;padding:0 !important;width:" + viewport[0] + "px !important;"
                    + "min-width:" + viewport[0] + "px !important;" + bodySizeAndOverflow
                    + "position:absolute !important;left:0;top:0;"
                    + "transform-origin:0 0 !important;background-color:transparent !important;}"
                    + mediaCanvasCss
                    + "</style>"
                    + "<script id=\"textview-fixed-layout-fit\">"
                    + "(function(){var W=" + viewport[0] + ",H=" + viewport[1]
                    + ",IMAGE_PAGE=" + (imageDominantPage ? "true" : "false")
                    + ",SLOT=" + spreadSlot + ",HALF_GAP="
                    + String.format(Locale.US, "%.2f", halfGap) + ";"
                    + "function fit(){try{var vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||W);"
                    + "var vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||H);"
                    + "if(IMAGE_PAGE){document.body.style.transform='none';document.body.style.left='0px';"
                    + "document.body.style.top='0px';document.body.style.setProperty('height',H+'px','important');"
                    + "document.body.style.setProperty('min-height',H+'px','important');}"
                    + "function mediaH(){var max=H,b=document.body.getBoundingClientRect();"
                    + "var nodes=document.querySelectorAll('img,svg,canvas,video,object,embed');"
                    + "for(var i=0;i<nodes.length;i++){var c=getComputedStyle(nodes[i]);"
                    + "if(c.display==='none'||c.visibility==='hidden'||parseFloat(c.opacity||'1')===0)continue;"
                    + "var r=nodes[i].getBoundingClientRect();"
                    + "if(r.width>0&&r.height>0)max=Math.max(max,r.bottom-b.top);}"
                    + "return Math.max(H,max);}"
                    + "var naturalH=IMAGE_PAGE?mediaH():Math.max(H,document.body.scrollHeight||0);"
                    + "var landscape=W>H*1.2,inSpread=IMAGE_PAGE&&SLOT!==0;"
                    + "var safe=inSpread?0:(landscape?Math.min(36,Math.max(18,Math.round(vw*0.025))):0);"
                    + "var fitW=Math.max(1,vw-(inSpread?HALF_GAP:(safe*2)));"
                    + "var fitH=IMAGE_PAGE?naturalH:H;"
                    + "var s=Math.min(fitW/W,vh/fitH);if(!isFinite(s)||s<=0)s=1;"
                    + "var scaledH=naturalH*s;"
                    + "var slack=Math.max(0,vw-W*s);"
                    + "var l=SLOT<0?Math.max(0,slack-HALF_GAP):(SLOT>0?Math.min(slack,HALF_GAP):Math.max(safe,slack/2));"
                    + "var t=Math.max(0,(vh-Math.min(fitH*s,scaledH))/2);"
                    + "document.documentElement.style.width=vw+'px';"
                    + "document.documentElement.style.height=(IMAGE_PAGE?vh:Math.max(vh,scaledH+t))+'px';"
                    + "document.body.style.width=W+'px';"
                    + "if(IMAGE_PAGE){document.body.style.setProperty('height',naturalH+'px','important');"
                    + "document.body.style.setProperty('min-height',naturalH+'px','important');"
                    + "document.body.style.setProperty('overflow','hidden','important');}"
                    + "else{document.body.style.minHeight=naturalH+'px';}"
                    + "document.body.style.left=l+'px';document.body.style.top=t+'px';"
                    + "document.body.style.transform='scale('+s+')';}catch(e){}}"
                    + "function arm(){var media=document.querySelectorAll('img,video,object,embed');"
                    + "for(var i=0;i<media.length;i++){if(media[i].getAttribute('data-rw-fixed-fit')==='1')continue;"
                    + "media[i].setAttribute('data-rw-fixed-fit','1');media[i].addEventListener('load',fit);}}"
                    + "window.addEventListener('resize',fit);window.addEventListener('orientationchange',function(){setTimeout(fit,60);});"
                    + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){arm();fit();});"
                    + "else{arm();fit();}"
                    + "setTimeout(fit,0);setTimeout(fit,180);})();"
                    + "</script>";
        } else if (imageDominantPage) {
            // A few pre-paginated/image EPUBs omit both a viewport meta and an
            // SVG viewBox. Give only those near-image-only pages a definite
            // viewport canvas. The root remains scrollable for WebView zoom/pan,
            // while publisher wrapper heights cannot create a multi-screen blank
            // tail. Mixed/text pages continue through the natural-scroll branch.
            String spreadJustify = spreadSlot == EpubSpreadSlotMath.PHYSICAL_LEFT
                    ? "flex-end" : spreadSlot == EpubSpreadSlotMath.PHYSICAL_RIGHT
                    ? "flex-start" : "center";
            String spreadPadding = spreadSlot == EpubSpreadSlotMath.PHYSICAL_LEFT
                    ? "padding-right:" + halfGap + "px !important;"
                    : spreadSlot == EpubSpreadSlotMath.PHYSICAL_RIGHT
                    ? "padding-left:" + halfGap + "px !important;" : "";
            String spreadBackgroundPosition = spreadSlot == EpubSpreadSlotMath.PHYSICAL_LEFT
                    ? "right center" : spreadSlot == EpubSpreadSlotMath.PHYSICAL_RIGHT
                    ? "left center" : "center";
            css = "<style id=\"textview-fixed-layout-center\">"
                    + "html{margin:0 !important;padding:0 !important;width:100vw !important;height:100vh !important;"
                    + "background-color:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                    + "body{margin:0 !important;padding:0 !important;" + spreadPadding
                    + "width:100vw !important;height:100vh !important;"
                    + "min-height:0 !important;overflow:hidden !important;display:flex !important;"
                    + "align-items:center !important;justify-content:" + spreadJustify
                    + " !important;box-sizing:border-box !important;"
                    + "background-size:contain !important;background-position:"
                    + spreadBackgroundPosition + " !important;"
                    + "background-origin:content-box !important;background-clip:content-box !important;"
                    + "background-repeat:no-repeat !important;}"
                    + "body>:only-child:not(img):not(svg):not(canvas):not(video):not(object):not(embed){width:100% !important;"
                    + "height:100% !important;max-width:100% !important;max-height:100% !important;"
                    + "overflow:hidden !important;display:flex !important;align-items:center !important;"
                    + "justify-content:" + spreadJustify + " !important;background-size:contain !important;"
                    + "background-position:" + spreadBackgroundPosition + " !important;"
                    + "background-origin:content-box !important;background-clip:content-box !important;"
                    + "background-repeat:no-repeat !important;}"
                    + "body img,body svg,body canvas,body video,body object,body embed{display:block !important;"
                    + "margin:0 !important;"
                    + "max-width:100% !important;max-height:100% !important;width:auto !important;height:auto !important;"
                    + "object-fit:contain !important;}"
                    + "</style>";
        } else {
            css = "<style id=\"textview-fixed-layout-center\">"
                    + "html{margin:0 !important;padding:0 !important;width:100vw !important;height:100vh !important;"
                    + "display:flex !important;align-items:center !important;justify-content:center !important;"
                    + "background-color:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                    + "body{margin:0 !important;padding:0 !important;flex:0 0 auto;box-sizing:border-box !important;}"
                    + "body>img:only-child,body>svg:only-child{display:block;margin:0 auto;}"
                    + "</style>";
        }
        return injectIntoHtmlHead(replaceFixedLayoutViewportMeta(html), css);
    }

    private String replaceFixedLayoutViewportMeta(String html) {
        if (html == null) return "";
        String replacement = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\"/>";
        String next = EpubViewportParser.replaceViewportMeta(html, replacement);
        if (!next.equals(html)) return next;
        String withHead = html.replaceFirst("(?i)<head[^>]*>", "$0" + replacement);
        if (!withHead.equals(html)) return withHead;
        return injectIntoHtmlHead(html, replacement);
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
        if (!currentEpubPageIsFixedLayout() || webView == null) return;
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
        String fixedBodyOverflow = page.imageDominantEpubPage ? "hidden" : "visible";
        String css = "html{margin:0 !important;padding:0 !important;width:auto !important;"
                + "min-width:" + minWidth + "px !important;min-height:" + minHeight + "px !important;"
                + "background-color:" + cssColor(readerBg) + " !important;overflow:auto !important;}"
                + "body{width:" + viewport[0] + "px !important;min-width:" + viewport[0] + "px !important;"
                + "height:" + viewport[1] + "px !important;min-height:" + viewport[1] + "px !important;"
                + "margin:" + topMargin + "px " + leftRight + "px " + bottomMargin + "px " + leftRight + "px !important;"
                + "padding:0 !important;box-sizing:border-box !important;position:relative !important;"
                + "overflow:" + fixedBodyOverflow + " !important;background-color:transparent !important;}";
        String js = "(function(){var css='" + cssQuote(css) + "';"
                + "var s=document.getElementById('textview-fixed-layout-find-offset');"
                + "if(!s){s=document.createElement('style');s.id='textview-fixed-layout-find-offset';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent=css;})();";
        evaluateFixedLayoutCssJavascript(js);
    }

    private void evaluateFixedLayoutCssJavascript(String js) {
        if (webView == null || js == null || js.isEmpty()) return;
        final WebView target = webView;
        final int targetPage = currentPage;
        WebSettings settings = target.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        target.evaluateJavascript(js, value ->
                restoreDocumentJavaScriptPolicy(target, targetPage, restoreJavascriptOff));
    }

    private int[] extractFixedLayoutViewportSize(String html) {
        EpubViewportParser.Dimensions dimensions = EpubViewportParser.parse(html);
        return new int[]{dimensions.width, dimensions.height};
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
        if (centerAsCover) {
            html = addClassToHtmlBody(html, "textview-reader-epub-cover-page");
        }
        String pageBodyLayoutCss = epubVerticalWritingLike
                ? "body{line-height:1.55;box-sizing:border-box;"
                + "min-height:100%;margin:0;background:#121212;color:#e8eaed;}"
                : "body{line-height:1.55;padding:22px;box-sizing:border-box;"
                + "width:100%;max-width:980px;margin:0 auto;background:#121212;color:#e8eaed;}"
                + "@media (orientation:landscape){body{max-width:1120px;}}";
        String css = "<style>" +
                "html{margin:0;padding:0;background:#121212;color:#e8eaed;"
                + (epubVerticalWritingLike ? "overflow-x:auto;" : "") + "}" +
                pageBodyLayoutCss +
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

    private String prepareImagePageEpubHtml(String html) {
        if (html == null) html = "";
        String viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">";
        String css = "<style>"
                + "html,body{margin:0 !important;padding:0 !important;width:100% !important;"
                + "height:100% !important;min-height:100vh !important;overflow:hidden !important;"
                + "background-color:#121212 !important;}"
                + "body{display:flex !important;align-items:center !important;justify-content:center !important;"
                + "background-size:contain !important;background-position:center !important;"
                + "background-repeat:no-repeat !important;}"
                + "body>*{max-width:100% !important;max-height:100% !important;}"
                + "img,svg,video,canvas,object,embed{display:block !important;max-width:100% !important;"
                + "max-height:100vh !important;width:auto !important;height:auto !important;"
                + "object-fit:contain !important;margin:auto !important;}"
                + "</style>";
        return injectIntoHtmlHead(html, viewport + css);
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
        return DocumentWordUtils.renderParagraph(p, wordRelationships, localDocumentHost);
    }

    private String renderWordTable(Node table) {
        return DocumentWordUtils.renderTable(table, wordRelationships, localDocumentHost);
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
