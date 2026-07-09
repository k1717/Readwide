package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.readwide.manager.image.ImageDecodeHelper;
import com.readwide.manager.image.ImageInfo;
import com.readwide.manager.image.ImageInfoReader;
import com.readwide.manager.image.LoadedImage;
import com.readwide.manager.util.FileSystemOps;
import com.readwide.manager.util.ImageSequenceNavigationMath;
import com.readwide.manager.util.ImageSequenceState;
import com.readwide.manager.util.FileSortUtils;
import com.readwide.manager.util.FileClipboardController;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.ReaderKeyMap;
import com.readwide.manager.view.ZoomImageView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal no-animation image viewer. A normal tap toggles the top information
 * bar; horizontal fling moves to the adjacent image in the same file set.
 */
public class ImageReaderActivity extends AppCompatActivity {
    private static final int MENU_ROTATE = 1001;
    private static final int MENU_MORE = 1002;

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_URI = "file_uri";
    public static final String EXTRA_FILE_PATHS = "file_paths";
    public static final String EXTRA_ALLOW_FILE_OPS = "allow_file_ops";
    public static final String EXTRA_SOURCE_DISPLAY_NAMES = "source_display_names";
    public static final String EXTRA_SOURCE_ENTRY_PATHS = "source_entry_paths";
    public static final String EXTRA_SOURCE_ARCHIVE_PATH = "source_archive_path";
    public static final String EXTRA_SEQUENCE_HANDOFF_TOKEN = "sequence_handoff_token";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService sequenceExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService detailExecutor = Executors.newSingleThreadExecutor();
    // Parallel pool for pre-decoding neighbor pages into the bitmap cache. Kept
    // separate from extraction (sequenceExecutor) and from the on-demand decode
    // (executor) so prefetch decoding runs concurrently instead of queuing behind
    // a slow archive extraction. Sized to the device's CPU budget, capped small.
    private final ExecutorService prefetchDecodeExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
    private final Object archiveExtractLock = new Object();
    private final Object deferredSequenceLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> imagePaths = new ArrayList<>();
    private final ArrayList<String> sourceDisplayNames = new ArrayList<>();
    private final ArrayList<String> sourceEntryPaths = new ArrayList<>();
    private final Set<String> verifiedSensitiveArchiveCachePaths = ConcurrentHashMap.newKeySet();
    private final Object sequentialReaderLock = new Object();
    private SequentialArchiveImageReader sequentialImageReader;
    private boolean sequentialReaderClosed;
    // Raised while an on-demand extraction (the page the user is on, or its detail
    // pass) is using the shared forward reader. Sequential prefetch checks this and
    // yields, so a background read-ahead never delays the page the user asked for.
    private final java.util.concurrent.atomic.AtomicInteger onDemandReaderWaiters =
            new java.util.concurrent.atomic.AtomicInteger();
    // Memoized "is the source a solid/sequential (forward-readable) archive", so
    // page turns do not re-read the archive signature every time.
    private volatile Boolean sourceArchiveForwardReadable;

    private PrefsManager prefs;
    private ImageDialogStyleController dialogStyle;
    private FrameLayout rootView;
    private ZoomImageView imageView;
    private Toolbar toolbar;
    private ImageReaderSliderController sliderController;
    private TextView statusText;
    private String filePath;
    private String fileUri;
    private String sourceArchivePath;
    private String sequenceHandoffToken;
    private char[] sourceArchivePassword;
    private ImageSequenceHandoffStore.Sequence pendingDeferredSequence;
    private int currentIndex = 0;
    private boolean allowFileOps;
    private volatile boolean destroyed;
    private boolean chromeVisible = true;
    private int systemLeftInset;
    private int systemTopInset;
    private int systemRightInset;
    private int systemBottomInset;
    private Bitmap currentBitmap;
    private Drawable currentDrawable;
    private volatile int imageLoadGeneration;
    // Consecutive same-direction page turns (ImagePrefetchMath.updateStreak);
    // sustained motion deepens the file-extraction look-ahead in that direction.
    private int pagingStreak;
    // Bumped on every prefetch plan; a queued plan for an older center exits
    // early instead of walking its offsets after the user has moved on.
    private final java.util.concurrent.atomic.AtomicInteger prefetchPlanGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean currentImageDetailLoaded = true;
    private int detailRequestGeneration = -1;
    private int displayedImageIndex = -1;
    private String displayedImagePath;
    private String displayedImageEntryPath;

    // Decoded-preview bitmap cache keyed by image index. Lets adjacent pages be
    // shown instantly (no re-decode) and keeps prefetched neighbors ready. The
    // cache OWNS these bitmaps: they are recycled only on eviction/clear, never
    // by the normal display swap, so a cached page is always safe to re-show.
    private android.util.LruCache<Integer, Bitmap> decodedBitmapCache;
    /**
     * Indexes whose cached bitmap is already full quality (an original-quality
     * preview, or a completed detail decode), so a cache hit can skip the
     * detail refine pass instead of re-decoding on every revisit. Main-thread
     * only, like every cache mutation; entryRemoved clears stale members.
     */
    private final java.util.HashSet<Integer> fullQualityCachedIndices = new java.util.HashSet<>();
    private final java.util.Set<Integer> bitmapPrefetchInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        prefs = PrefsManager.getInstance(this);
        if (prefs != null) {
            prefs.applyLanguage(prefs.getLanguageMode());
            prefs.applyDarkMode(prefs.getDarkMode());
        }
        super.onCreate(savedInstanceState);
        dialogStyle = new ImageDialogStyleController(this);
        overridePendingTransition(R.anim.image_viewer_enter, R.anim.image_viewer_hold);
        ViewerRegistry.activate(this);

        // Budget the decoded-bitmap cache to a fraction of app heap. Sized by
        // bitmap byte count; evicted bitmaps are recycled unless still on screen.
        // A larger budget keeps the ±3 prefetch window resident on big comics;
        // the heap-fraction basis keeps it proportional on low-RAM devices.
        int cacheBudgetBytes = (int) Math.max(
                8L * 1024L * 1024L,
                Math.min(128L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 5L));
        decodedBitmapCache = new android.util.LruCache<Integer, Bitmap>(cacheBudgetBytes) {
            @Override protected int sizeOf(Integer key, Bitmap value) {
                return value == null || value.isRecycled() ? 0 : value.getByteCount();
            }
            @Override protected void entryRemoved(boolean evicted, Integer key,
                                                  Bitmap oldValue, Bitmap newValue) {
                // The stored bitmap for this index changed or left the cache;
                // its quality record is stale either way. Put sites re-add it
                // right after a replacing put when the new bitmap qualifies.
                fullQualityCachedIndices.remove(key);
                // Never recycle the bitmap currently shown on screen; the display
                // swap path owns that one until it is replaced.
                if (oldValue != null && oldValue != newValue
                        && oldValue != currentBitmap && !oldValue.isRecycled()) {
                    oldValue.recycle();
                }
            }
        };

        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        fileUri = getIntent().getStringExtra(EXTRA_FILE_URI);
        sourceArchivePath = getIntent().getStringExtra(EXTRA_SOURCE_ARCHIVE_PATH);
        sequenceHandoffToken = getIntent().getStringExtra(EXTRA_SEQUENCE_HANDOFF_TOKEN);
        allowFileOps = getIntent().getBooleanExtra(EXTRA_ALLOW_FILE_OPS,
                filePath != null && filePath.trim().length() > 0);

        if ((filePath == null || filePath.trim().isEmpty())
                && (fileUri == null || fileUri.trim().isEmpty())) {
            finish();
            return;
        }

        initializeImagePathList();
        buildUi();
        if (sequenceHandoffToken != null && sequenceHandoffToken.trim().length() > 0) {
            setLoading(true, null);
            loadDeferredImageSequenceAsync();
        } else {
            loadImageAsync();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Persist the latest reading position now. When returning to the main
        // screen the parent activity's onResume (which reloads the recent list)
        // runs before this activity's onStop/onDestroy, so saving only in
        // onDestroy left the recent screen showing stale progress for image
        // archives. Flush the pending debounced save and write immediately,
        // matching how the PDF/document/text readers persist in onPause.
        mainHandler.removeCallbacks(imageProgressSaveRunnable);
        persistArchiveImageProgress();
        persistImageReadingProgress();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        ImageSequenceHandoffStore.discard(sequenceHandoffToken);
        sequenceHandoffToken = null;
        synchronized (deferredSequenceLock) {
            if (pendingDeferredSequence != null) {
                pendingDeferredSequence.clearSensitiveData();
                pendingDeferredSequence = null;
            }
        }
        synchronized (archiveExtractLock) {
            PasswordChars.clear(sourceArchivePassword);
            sourceArchivePassword = null;
            verifiedSensitiveArchiveCachePaths.clear();
        }
        executor.shutdownNow();
        sequenceExecutor.shutdownNow();
        detailExecutor.shutdownNow();
        prefetchDecodeExecutor.shutdownNow();
        SequentialArchiveImageReader readerToClose;
        synchronized (sequentialReaderLock) {
            sequentialReaderClosed = true;
            readerToClose = sequentialImageReader;
            sequentialImageReader = null;
        }
        if (readerToClose != null) {
            readerToClose.close();
        }
        if (currentDrawable instanceof AnimatedImageDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ((AnimatedImageDrawable) currentDrawable).stop();
        }
        clearCurrentImageSurface(true);
        if (decodedBitmapCache != null) {
            decodedBitmapCache.evictAll(); // recycles all cached bitmaps
            decodedBitmapCache = null;
        }
        fullQualityCachedIndices.clear();
        persistArchiveImageProgress();
        persistImageReadingProgress();
        ViewerRegistry.unregister(this);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.image_viewer_hold, R.anim.image_viewer_exit);
    }

    private void initializeImagePathList() {
        imagePaths.clear();
        sourceDisplayNames.clear();
        sourceEntryPaths.clear();
        ArrayList<String> provided = getIntent().getStringArrayListExtra(EXTRA_FILE_PATHS);
        ArrayList<String> providedNames = getIntent().getStringArrayListExtra(EXTRA_SOURCE_DISPLAY_NAMES);
        ArrayList<String> providedEntryPaths = getIntent().getStringArrayListExtra(EXTRA_SOURCE_ENTRY_PATHS);
        if (provided != null) {
            boolean archiveBackedSequence = sourceArchivePath != null && sourceArchivePath.trim().length() > 0;
            for (int i = 0; i < provided.size(); i++) {
                String path = provided.get(i);
                if (path == null || path.trim().isEmpty()) continue;
                File f = new File(path);
                String entryPath = providedEntryPaths != null && i < providedEntryPaths.size() ? providedEntryPaths.get(i) : "";
                boolean localImageReady = f.exists() && f.isFile() && FileUtils.isImageFile(f.getName());
                boolean lazyArchiveImage = archiveBackedSequence
                        && entryPath != null
                        && entryPath.trim().length() > 0
                        && FileUtils.isImageFile(f.getName());
                if ((localImageReady || lazyArchiveImage) && !imagePaths.contains(f.getAbsolutePath())) {
                    imagePaths.add(f.getAbsolutePath());
                    sourceDisplayNames.add(providedNames != null && i < providedNames.size() ? providedNames.get(i) : f.getName());
                    sourceEntryPaths.add(entryPath == null ? "" : entryPath);
                }
            }
        }

        if (imagePaths.isEmpty()
                && sequenceHandoffToken == null
                && filePath != null
                && filePath.trim().length() > 0) {
            imagePaths.addAll(scanParentImages(new File(filePath)));
            for (String path : imagePaths) {
                sourceDisplayNames.add(new File(path).getName());
                sourceEntryPaths.add("");
            }
        }

        if (filePath != null && filePath.trim().length() > 0) {
            File current = new File(filePath);
            String absolute = current.getAbsolutePath();
            int found = imagePaths.indexOf(absolute);
            if (found < 0) {
                imagePaths.add(absolute);
                found = imagePaths.size() - 1;
            }
            currentIndex = found;
            filePath = imagePaths.get(currentIndex);
        }
        ImageSequenceState.normalizeMetadataLists(imagePaths, sourceDisplayNames, sourceEntryPaths);
    }

    private void loadDeferredImageSequenceAsync() {
        final String token = sequenceHandoffToken;
        if (token == null || token.trim().isEmpty()) {
            loadImageAsync();
            return;
        }
        sequenceExecutor.execute(() -> {
            ImageSequenceHandoffStore.Sequence sequence = ImageSequenceHandoffStore.consume(token);
            if (sequence == null) {
                mainHandler.post(() -> {
                    if (!destroyed && token.equals(sequenceHandoffToken)) {
                        sequenceHandoffToken = null;
                        loadImageAsync();
                    }
                });
                return;
            }
            synchronized (deferredSequenceLock) {
                if (destroyed) {
                    sequence.clearSensitiveData();
                    return;
                }
                if (pendingDeferredSequence != null) pendingDeferredSequence.clearSensitiveData();
                pendingDeferredSequence = sequence;
            }
            boolean posted = mainHandler.post(() -> {
                ImageSequenceHandoffStore.Sequence pending;
                synchronized (deferredSequenceLock) {
                    pending = pendingDeferredSequence;
                    pendingDeferredSequence = null;
                }
                if (destroyed) {
                    if (pending != null) pending.clearSensitiveData();
                    return;
                }
                if (token.equals(sequenceHandoffToken)) sequenceHandoffToken = null;
                applyDeferredImageSequence(pending);
            });
            if (!posted) {
                synchronized (deferredSequenceLock) {
                    if (pendingDeferredSequence == sequence) pendingDeferredSequence = null;
                }
                sequence.clearSensitiveData();
            }
        });
    }

    private void applyDeferredImageSequence(@Nullable ImageSequenceHandoffStore.Sequence sequence) {
        boolean applied = false;
        try {
            if (destroyed) return;
            if (sequence == null || sequence.paths == null || sequence.paths.isEmpty()) {
                fallbackToSingleImageAfterDeferredSequenceFailure();
                return;
            }
            String currentPath = filePath != null ? new File(filePath).getAbsolutePath() : null;
            if (currentPath == null || currentPath.trim().isEmpty()) {
                fallbackToSingleImageAfterDeferredSequenceFailure();
                return;
            }

            int found = sequence.paths.indexOf(currentPath);
            if (found < 0) {
                fallbackToSingleImageAfterDeferredSequenceFailure();
                return;
            }

            imagePaths.clear();
            imagePaths.addAll(sequence.paths);
            sourceDisplayNames.clear();
            sourceDisplayNames.addAll(sequence.displayNames);
            sourceEntryPaths.clear();
            sourceEntryPaths.addAll(sequence.entryPaths);
            ImageSequenceState.normalizeMetadataLists(imagePaths, sourceDisplayNames, sourceEntryPaths);
            synchronized (archiveExtractLock) {
                PasswordChars.clear(sourceArchivePassword);
                sourceArchivePassword = PasswordChars.cloneOf(sequence.archivePassword);
                verifiedSensitiveArchiveCachePaths.clear();
                seedVerifiedSensitiveArchiveCachePathsLocked();
            }
            currentIndex = ImageSequenceNavigationMath.clampIndex(found, imagePaths.size());
            filePath = imagePaths.get(currentIndex);
            fileUri = null;
            updateToolbarTitle();
            applied = true;
        } finally {
            if (sequence != null) {
                sequence.clearSensitiveData();
            }
        }
        if (applied && !destroyed) {
            loadImageAsync();
        }
    }

    private void fallbackToSingleImageAfterDeferredSequenceFailure() {
        if (destroyed) return;
        sequenceHandoffToken = null;
        imagePaths.clear();
        sourceDisplayNames.clear();
        sourceEntryPaths.clear();
        if (filePath != null && filePath.trim().length() > 0) {
            File current = new File(filePath);
            imagePaths.add(current.getAbsolutePath());
            sourceDisplayNames.add(current.getName());
            sourceEntryPaths.add("");
            currentIndex = 0;
            filePath = current.getAbsolutePath();
        }
        ImageSequenceState.normalizeMetadataLists(imagePaths, sourceDisplayNames, sourceEntryPaths);
        // If the handoff metadata was lost or invalid, the selected archive preview
        // file may still exist on disk. Treat it as a one-off local preview instead
        // of forcing archive-entry extraction with an empty entry-path list.
        if (sourceArchivePath != null && sourceArchivePath.trim().length() > 0) {
            sourceArchivePath = null;
            sourceArchiveForwardReadable = null;
            synchronized (archiveExtractLock) {
                PasswordChars.clear(sourceArchivePassword);
                sourceArchivePassword = null;
                verifiedSensitiveArchiveCachePaths.clear();
            }
        }
        updateToolbarTitle();
        loadImageAsync();
    }

    private void seedVerifiedSensitiveArchiveCachePathsLocked() {
        if (!PasswordChars.hasPassword(sourceArchivePassword)) return;
        if (sourceArchivePath == null || sourceArchivePath.trim().isEmpty()) return;
        for (int i = 0; i < imagePaths.size(); i++) {
            String path = imagePaths.get(i);
            String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, i);
            if (path == null || path.trim().isEmpty()
                    || entryPath == null || entryPath.trim().isEmpty()) {
                continue;
            }
            File cacheFile = new File(path);
            if (ArchiveImageEntryCache.isReadyImageFileForHandoff(entryPath, cacheFile)) {
                verifiedSensitiveArchiveCachePaths.add(cacheFile.getAbsolutePath());
            }
        }
    }

    @NonNull
    private ArrayList<String> scanParentImages(@NonNull File selected) {
        ArrayList<String> paths = new ArrayList<>();
        File parent = selected.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory() || !parent.canRead()) {
            paths.add(selected.getAbsolutePath());
            return paths;
        }
        File[] children = parent.listFiles();
        if (children == null) {
            paths.add(selected.getAbsolutePath());
            return paths;
        }
        List<File> images = new ArrayList<>();
        for (File child : children) {
            if (child != null && child.isFile() && FileUtils.isImageFile(child.getName())) images.add(child);
        }
        int sortMode = prefs != null ? prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
        FileSortUtils.sortMainFiles(this, images, sortMode);
        for (File image : images) paths.add(image.getAbsolutePath());
        if (!paths.contains(selected.getAbsolutePath())) paths.add(selected.getAbsolutePath());
        return paths;
    }

    private void buildUi() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setCallbacks(new ZoomImageView.Callbacks() {
            @Override public void onSingleTap(float normalizedX) { handleImageSingleTap(normalizedX); }
            @Override public boolean shouldHandleTapImmediately(float normalizedX) { return isImageSideTapPageTurn(normalizedX); }
            @Override public void onSwipeLeft() { showAdjacentImage(imageSwipeDelta(true)); }
            @Override public void onSwipeRight() { showAdjacentImage(imageSwipeDelta(false)); }
            @Override public void onZoomRequested() { requestDetailImageForCurrent(); }
        });
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(Color.argb(230, 0, 0, 0));
        // Consume taps on the bar (outside its icons/menu) so they do not fall
        // through to the image view behind it and page the sequence. The image view
        // now extends under this bar (its top strip is padding, not a margin); when
        // the bar is hidden it is GONE and those taps reach the image.
        toolbar.setClickable(true);
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setSubtitleTextColor(Color.rgb(210, 210, 210));
        toolbar.setTitleTextAppearance(this, R.style.TextAppearance_TextView_ImageViewer_Title);
        toolbar.setSubtitleTextAppearance(this, R.style.TextAppearance_TextView_ImageViewer_Subtitle);
        toolbar.setTitle(getDisplayName());
        toolbar.setNavigationContentDescription(R.string.back);
        Drawable nav = ContextCompat.getDrawable(this, R.drawable.ic_bottom_arrow_left);
        if (nav != null) {
            Drawable wrapped = DrawableCompat.wrap(nav.mutate());
            DrawableCompat.setTint(wrapped, Color.WHITE);
            toolbar.setNavigationIcon(wrapped);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        MenuItem rotate = toolbar.getMenu().add(0, MENU_ROTATE, 0, R.string.screen_orientation_rotate);
        rotate.setIcon(R.drawable.ic_screen_rotation);
        rotate.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        MenuItem more = toolbar.getMenu().add(0, MENU_MORE, 1, R.string.image_options);
        more.setIcon(R.drawable.ic_more_vert);
        more.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_ROTATE) {
                toggleScreenOrientation();
                return true;
            }
            if (item.getItemId() == MENU_MORE) {
                showImageOptionsPopup(toolbar);
                return true;
            }
            return false;
        });
        FrameLayout.LayoutParams toolbarLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48),
                Gravity.TOP);
        root.addView(toolbar, toolbarLp);
        tintToolbarMenuIcon(toolbar);

        sliderController = new ImageReaderSliderController(this, this::showImageAtIndex);
        sliderController.setSliderDirection(currentImageSliderDirection());
        root.addView(sliderController.createView(imagePaths.size()), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // Image decoding/loading feedback is intentionally not drawn inside the
        // image viewer. The source screen shows the TXT-style loading window
        // while preparing/opening the image viewer, so the viewer itself stays
        // QuickPic-like: plain image canvas, no internal spinner overlay.

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(15f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16));
        statusText.setVisibility(View.GONE);
        root.addView(statusText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            systemLeftInset = Math.max(0, bars.left);
            systemTopInset = Math.max(0, bars.top);
            systemRightInset = Math.max(0, bars.right);
            systemBottomInset = Math.max(0, bars.bottom);

            // In gesture navigation the navigation inset is normally at the bottom,
            // but in landscape 3-button navigation it can move to the right edge.
            // Keep the image surface and chrome inside the safe system-bar area so
            // the image, slider, and toolbar actions are not hidden behind that bar.
            toolbar.setPadding(systemLeftInset, systemTopInset, systemRightInset, 0);
            ViewGroup.LayoutParams raw = toolbar.getLayoutParams();
            if (raw != null) {
                int targetHeight = dpToPx(48) + systemTopInset;
                if (raw.height != targetHeight) {
                    raw.height = targetHeight;
                    toolbar.setLayoutParams(raw);
                }
            }
            if (sliderController != null) {
                sliderController.applySystemInsets(systemLeftInset, systemRightInset, systemBottomInset);
            }
            updateImageViewportBounds();
            imageView.post(imageView::configureBaseMatrix);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        updateToolbarTitle();
    }

    private void tintToolbarMenuIcon(@NonNull Toolbar toolbar) {
        for (int i = 0; i < toolbar.getMenu().size(); i++) {
            MenuItem item = toolbar.getMenu().getItem(i);
            tintMenuItemIcon(item);
        }
        updateRotationMenuTitle();
    }

    private void tintMenuItemIcon(@Nullable MenuItem item) {
        if (item == null) return;
        Drawable icon = item.getIcon();
        if (icon == null) return;
        Drawable wrapped = DrawableCompat.wrap(icon.mutate());
        DrawableCompat.setTint(wrapped, Color.WHITE);
        item.setIcon(wrapped);
    }

    private void toggleScreenOrientation() {
        boolean switchToLandscape = !isLandscapeNow();
        setRequestedOrientation(switchToLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        updateRotationMenuTitle(switchToLandscape);
        if (imageView != null) imageView.postDelayed(imageView::configureBaseMatrix, 160);
    }

    private boolean isLandscapeNow() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void updateRotationMenuTitle() {
        updateRotationMenuTitle(isLandscapeNow());
    }

    private void updateRotationMenuTitle(boolean currentLandscape) {
        if (toolbar == null) return;
        MenuItem item = toolbar.getMenu().findItem(MENU_ROTATE);
        if (item == null) return;
        item.setTitle(currentLandscape
                ? R.string.screen_orientation_to_portrait
                : R.string.screen_orientation_to_landscape);
        item.setIcon(currentLandscape
                ? R.drawable.ic_screen_rotation
                : R.drawable.ic_screen_portrait);
        tintMenuItemIcon(item);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotationMenuTitle(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
        if (rootView != null) ViewCompat.requestApplyInsets(rootView);
        updateImageViewportBounds();
        if (imageView != null) imageView.post(imageView::configureBaseMatrix);
    }

    // --- Hardware page-turn keys ---

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleImagePageTurnKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Fallback for devices that route hardware keys through onKeyDown() instead
        // of dispatchKeyEvent(). dispatchKeyEvent() normally consumes these first.
        if (handleImagePageTurnKey(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleImagePageTurnKey(@Nullable KeyEvent event) {
        if (event == null || prefs == null || !prefs.getVolumeKeyScroll()) {
            return false;
        }
        int direction = ReaderKeyMap.pageTurnDirectionForKey(event.getKeyCode());
        if (direction == 0) return false;

        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                showAdjacentImage(direction);
            }
            return true;
        }
        return action == KeyEvent.ACTION_UP;
    }

    private void handleImageSingleTap(float normalizedX) {
        if (!isImageTapPagingEnabled() || imagePaths.size() <= 1) {
            toggleChrome();
            return;
        }
        int delta = imageTapPageDelta(normalizedX);
        if (delta == 0) {
            toggleChrome();
        } else {
            showAdjacentImage(delta);
        }
    }

    private boolean isImageSideTapPageTurn(float normalizedX) {
        return isImageTapPagingEnabled()
                && imagePaths.size() > 1
                && imageTapPageDelta(normalizedX) != 0;
    }

    private int imageTapPageDelta(float normalizedX) {
        return ImageSequenceNavigationMath.visualTapZoneDelta(normalizedX, isImageMirrorMode());
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        if (toolbar != null) toolbar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        if (sliderController != null) sliderController.update(currentIndex, imagePaths.size(), chromeVisible);
    }

    private void updateImageViewportBounds() {
        if (imageView == null) return;
        int top = dpToPx(48) + systemTopInset;
        int sliderItemCountForBounds = shouldReserveImageSliderSpace()
                ? Math.max(2, imagePaths.size())
                : imagePaths.size();
        int bottom = sliderController != null
                ? sliderController.reservedViewportBottomInset(sliderItemCountForBounds)
                : systemBottomInset;
        // Reserve the toolbar (top) and slider (bottom) strips as PADDING rather
        // than margins so the view still reaches the top and bottom screen edges
        // and receives taps there. The image content stays inset between the bars
        // exactly as before, but when the chrome is hidden both bars are GONE, so a
        // tap on either strip now reaches the view for page turning instead of
        // landing in dead space. While shown, the toolbar and slider each consume
        // their own touches, so the image underneath is not paged through them.
        imageView.setPadding(0, top, 0, bottom);
        ViewGroup.LayoutParams raw = imageView.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (lp.leftMargin != systemLeftInset
                    || lp.topMargin != 0
                    || lp.rightMargin != systemRightInset
                    || lp.bottomMargin != 0) {
                lp.leftMargin = systemLeftInset;
                lp.topMargin = 0;
                lp.rightMargin = systemRightInset;
                lp.bottomMargin = 0;
                imageView.setLayoutParams(lp);
            }
        }
        imageView.post(imageView::configureBaseMatrix);
    }

    private boolean shouldReserveImageSliderSpace() {
        return imagePaths.size() > 1
                || (sequenceHandoffToken != null && !sequenceHandoffToken.trim().isEmpty());
    }

    private void updateToolbarTitle() {
        if (toolbar == null) return;
        toolbar.setTitle(getDisplayName());
        if (imagePaths.size() > 1) {
            toolbar.setSubtitle(String.format(Locale.getDefault(), "%d / %d", currentIndex + 1, imagePaths.size()));
        } else {
            toolbar.setSubtitle(null);
        }
        updateImageSliderState();
    }

    private void updateImageSliderState() {
        if (sliderController != null) sliderController.update(currentIndex, imagePaths.size(), chromeVisible);
        updateImageViewportBounds();
    }

    private String getDisplayName() {
        if (currentIndex >= 0 && currentIndex < sourceDisplayNames.size()) {
            String sourceName = sourceDisplayNames.get(currentIndex);
            if (sourceName != null && sourceName.trim().length() > 0) return sourceName;
        }
        if (filePath != null && filePath.trim().length() > 0) return new File(filePath).getName();
        if (fileUri != null && fileUri.trim().length() > 0) {
            String name = FileUtils.getFileNameFromUri(this, Uri.parse(fileUri));
            if (name != null && name.trim().length() > 0) return name;
        }
        return getString(R.string.image_viewer_title);
    }

    private int imageSwipeDelta(boolean swipeLeft) {
        int ltrVisualDelta = swipeLeft ? 1 : -1;
        return ImageSequenceNavigationMath.mirroredVisualDelta(ltrVisualDelta, isImageMirrorMode());
    }

    private boolean isImageMirrorMode() {
        return currentImageSliderDirection() == PrefsManager.IMAGE_SLIDER_DIRECTION_RTL;
    }

    private void showAdjacentImage(int direction) {
        if (imagePaths.size() <= 1) return;
        showImageAtIndex(ImageSequenceNavigationMath.nextIndex(currentIndex, direction, imagePaths.size()));
    }

    private void showImageAtIndex(int targetIndex) {
        if (imagePaths.isEmpty()) return;
        int next = ImageSequenceNavigationMath.clampIndex(targetIndex, imagePaths.size());
        if (next == currentIndex) {
            updateImageSliderState();
            return;
        }
        pagingStreak = com.readwide.manager.util.ImagePrefetchMath.updateStreak(pagingStreak, next - currentIndex);
        currentIndex = next;
        filePath = imagePaths.get(currentIndex);
        fileUri = null;
        persistArchiveImageProgress();
        scheduleImageReadingProgressSave();
        updateToolbarTitle();
        // Instant path: if this page's bitmap is already decoded and cached, show
        // it immediately without queuing a decode. This is what makes rapid taps
        // feel responsive instead of waiting on the single-thread decode queue.
        if (showCachedBitmapIfAvailable(currentIndex)) {
            prefetchAdjacentImages(currentIndex);
            return;
        }
        loadImageAsync();
    }

    /** Displays a cached decoded bitmap for the index if present. */
    private boolean showCachedBitmapIfAvailable(int index) {
        if (decodedBitmapCache == null) return false;
        Bitmap cached = decodedBitmapCache.get(index);
        if (cached == null || cached.isRecycled()) return false;
        // Bump the load generation so any in-flight decode for the old page
        // won't overwrite us, and present from cache.
        ++imageLoadGeneration;
        detailRequestGeneration = -1;
        boolean cachedIsFullQuality = fullQualityCachedIndices.contains(index);
        currentImageDetailLoaded = cachedIsFullQuality;
        String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, index);
        LoadedImage cachedLoaded = LoadedImage.forBitmap(
                cached, cachedIsFullQuality, cached.getWidth(), cached.getHeight(), 1);
        applyLoadedImage(cachedLoaded, false, index, filePath, entryPath);
        setLoading(false, null);
        // A cached preview may be lower quality; refine it once - but skip the
        // detail pass entirely when the cached bitmap is known full quality
        // (an original-quality preview or an already-completed detail decode).
        if (!cachedIsFullQuality) {
            requestDetailImageForCurrent();
        }
        return true;
    }

    private void loadImageAsync() {
        final int generation = ++imageLoadGeneration;
        detailRequestGeneration = -1;
        currentImageDetailLoaded = false;
        final int index = currentIndex;
        final String path = filePath;
        final String uri = fileUri;
        final String displayName = getDisplayName();
        final String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, index);
        final Context appContext = getApplicationContext();
        setLoading(true, null);
        updateToolbarTitle();
        executor.execute(() -> {
            if (!isCurrentImageLoadGeneration(generation)) return;
            LoadedImage loaded = null;
            try {
                if (ensureArchiveImageExtracted(index, path)
                        && isCurrentImageLoadGeneration(generation)) {
                    loaded = ImageDecodeHelper.decodePreview(appContext, path, uri, displayName);
                }
            } catch (Exception ignored) {
                loaded = null;
            }
            if (!isCurrentImageLoadGeneration(generation)) {
                recycleLoadedImage(loaded);
                return;
            }
            LoadedImage result = loaded;
            mainHandler.post(() -> {
                if (!isActiveImageRequest(generation, index, path, entryPath)) {
                    recycleLoadedImage(result);
                    return;
                }
                if (result != null && (result.bitmap != null || result.drawable != null)) {
                    // Cache the decoded bitmap (not animated drawables) so this
                    // page can be re-shown instantly later.
                    if (result.bitmap != null && !result.bitmap.isRecycled()
                            && decodedBitmapCache != null) {
                        decodedBitmapCache.put(index, result.bitmap);
                        if (result.originalQuality) fullQualityCachedIndices.add(index);
                    }
                    applyLoadedImage(result, false, index, path, entryPath);
                    currentImageDetailLoaded = result.originalQuality;
                    persistArchiveImageProgress();
                    scheduleImageReadingProgressSave();
                    prefetchAdjacentImages(index);
                    setLoading(false, null);
                } else {
                    discardArchiveImageCacheAfterDecodeFailure(index, path);
                    if (currentBitmap == null && currentDrawable == null) {
                        clearCurrentImageSurface(true);
                    }
                    setLoading(false, getString(R.string.image_open_failed));
                }
            });
        });
    }

    private void requestDetailImageForCurrent() {
        if (currentImageDetailLoaded || destroyed) return;
        final int generation = imageLoadGeneration;
        if (generation <= 0 || detailRequestGeneration == generation) return;
        detailRequestGeneration = generation;
        final int index = currentIndex;
        final String path = filePath;
        final String uri = fileUri;
        final String displayName = getDisplayName();
        final String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, index);
        final Context appContext = getApplicationContext();
        detailExecutor.execute(() -> {
            if (!isCurrentImageLoadGeneration(generation)) return;
            LoadedImage loaded = null;
            try {
                if (ensureArchiveImageExtracted(index, path)
                        && isCurrentImageLoadGeneration(generation)) {
                    loaded = ImageDecodeHelper.decodeDetail(appContext, path, uri, displayName);
                }
            } catch (Exception ignored) {
                loaded = null;
            }
            if (!isCurrentImageLoadGeneration(generation)) {
                recycleLoadedImage(loaded);
                return;
            }
            LoadedImage result = loaded;
            mainHandler.post(() -> {
                if (!isActiveImageRequest(generation, index, path, entryPath)) {
                    recycleLoadedImage(result);
                    return;
                }
                if (result != null && (result.bitmap != null || result.drawable != null)) {
                    applyLoadedImage(result, true, index, path, entryPath);
                    // Once the detail decode succeeds, keep that bitmap/drawable as the
                    // active image even after the user returns to the adaptive-fit view.
                    // Very large sources may still be capped below true original size, but
                    // re-requesting the same detail decode on every later zoom only wastes
                    // CPU and can briefly replace the retained detail bitmap path.
                    currentImageDetailLoaded = true;
                    // Cache the detail bitmap so revisiting this page shows full
                    // quality immediately instead of re-running the detail decode.
                    // Safe to replace the preview entry here: the display already
                    // swapped to the detail bitmap, so the evict hook can recycle
                    // the preview (and it guards currentBitmap regardless).
                    if (result.bitmap != null && !result.bitmap.isRecycled()
                            && decodedBitmapCache != null) {
                        decodedBitmapCache.put(index, result.bitmap);
                        fullQualityCachedIndices.add(index);
                    }
                }
            });
        });
    }

    private boolean isActiveImageRequest(int generation,
                                         int requestIndex,
                                         @Nullable String requestPath,
                                         @Nullable String requestEntryPath) {
        if (destroyed || generation != imageLoadGeneration) return false;
        if (requestIndex != currentIndex) return false;
        if (!TextUtils.equals(requestPath, filePath)) return false;
        return TextUtils.equals(requestEntryPath, ImageSequenceState.entryPathAt(sourceEntryPaths, currentIndex));
    }

    private boolean isCurrentImageLoadGeneration(int generation) {
        return !destroyed && generation == imageLoadGeneration;
    }

    private void applyLoadedImage(@NonNull LoadedImage result,
                                  boolean preserveViewport,
                                  int requestIndex,
                                  @Nullable String requestPath,
                                  @Nullable String requestEntryPath) {
        Bitmap oldBitmap = currentBitmap;
        Drawable oldDrawable = currentDrawable;
        if (oldDrawable instanceof AnimatedImageDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ((AnimatedImageDrawable) oldDrawable).stop();
        }
        if (result.drawable != null) {
            currentDrawable = result.drawable;
            currentBitmap = null;
            imageView.setImageDrawableReady(result.drawable);
        } else {
            currentDrawable = null;
            currentBitmap = result.bitmap;
            imageView.setImageBitmapReady(result.bitmap, preserveViewport);
        }
        displayedImageIndex = requestIndex;
        displayedImagePath = requestPath;
        displayedImageEntryPath = requestEntryPath;
        // Only recycle the previous bitmap if the cache does not own it. Cached
        // bitmaps are recycled exclusively on eviction/clear.
        if (oldBitmap != null && oldBitmap != currentBitmap && !oldBitmap.isRecycled()
                && !isBitmapInCache(oldBitmap)) {
            oldBitmap.recycle();
        }
    }

    private boolean isBitmapInCache(@Nullable Bitmap bitmap) {
        if (bitmap == null || decodedBitmapCache == null) return false;
        java.util.Map<Integer, Bitmap> snapshot = decodedBitmapCache.snapshot();
        return snapshot.containsValue(bitmap);
    }

    private void clearCurrentImageSurface(boolean recycleBitmap) {
        Drawable oldDrawable = currentDrawable;
        Bitmap oldBitmap = currentBitmap;
        if (oldDrawable instanceof AnimatedImageDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ((AnimatedImageDrawable) oldDrawable).stop();
        }
        currentDrawable = null;
        currentBitmap = null;
        displayedImageIndex = -1;
        displayedImagePath = null;
        displayedImageEntryPath = null;
        if (imageView != null) imageView.clearImageReady();
        if (recycleBitmap && oldBitmap != null && !oldBitmap.isRecycled()
                && !isBitmapInCache(oldBitmap)) {
            oldBitmap.recycle();
        }
    }

    private void recycleLoadedImage(@Nullable LoadedImage result) {
        if (result != null && result.bitmap != null && !result.bitmap.isRecycled()) result.bitmap.recycle();
    }


    private boolean ensureArchiveImageExtracted(int index, @Nullable String expectedPath) {
        if (expectedPath == null || expectedPath.trim().isEmpty()) return fileUri != null && fileUri.trim().length() > 0;
        File outFile = new File(expectedPath);
        if (sourceArchivePath == null || sourceArchivePath.trim().isEmpty()) {
            return ArchiveImageEntryCache.isUsableFile(outFile);
        }
        if (index < 0 || index >= sourceEntryPaths.size()) return false;
        String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, index);
        if (entryPath == null || entryPath.trim().isEmpty()) return false;
        File archive = new File(sourceArchivePath);
        if (!archive.exists() || !archive.isFile()) return false;
        char[] passwordSnapshot;
        synchronized (archiveExtractLock) {
            passwordSnapshot = PasswordChars.cloneOf(sourceArchivePassword);
        }
        boolean forwardReadable = isSourceArchiveForwardReadable();
        if (forwardReadable) onDemandReaderWaiters.incrementAndGet();
        try {
            boolean sensitiveCache = PasswordChars.hasPassword(passwordSnapshot);
            SequentialArchiveImageReader reader = ensureSequentialReader(archive);
            return SequentialArchiveImageReader.ensureImageReady(
                    getApplicationContext(),
                    archive,
                    entryPath,
                    outFile,
                    passwordSnapshot,
                    sensitiveCache,
                    verifiedSensitiveArchiveCachePaths,
                    reader).success;
        } finally {
            if (forwardReadable) onDemandReaderWaiters.decrementAndGet();
            PasswordChars.clear(passwordSnapshot);
        }
    }

    /** Memoized: is the source archive a solid/sequential forward-readable type? */
    private boolean isSourceArchiveForwardReadable() {
        Boolean cached = sourceArchiveForwardReadable;
        if (cached != null) return cached;
        boolean result = sourceArchivePath != null && !sourceArchivePath.trim().isEmpty()
                && ArchiveSupport.isForwardImageReadableType(new File(sourceArchivePath));
        sourceArchiveForwardReadable = result;
        return result;
    }

    @Nullable
    private SequentialArchiveImageReader ensureSequentialReader(File archive) {
        synchronized (sequentialReaderLock) {
            if (sequentialReaderClosed) return null;
            if (sequentialImageReader != null) return sequentialImageReader;
            if (!isSourceArchiveForwardReadable()) return null;
            char[] passwordSnapshot;
            synchronized (archiveExtractLock) {
                passwordSnapshot = PasswordChars.cloneOf(sourceArchivePassword);
            }
            try {
                boolean sensitiveCache = PasswordChars.hasPassword(passwordSnapshot);
                sequentialImageReader = SequentialArchiveImageReader.openIfSupported(
                        getApplicationContext(),
                        archive,
                        passwordSnapshot,
                        sensitiveCache,
                        verifiedSensitiveArchiveCachePaths);
            } finally {
                PasswordChars.clear(passwordSnapshot);
            }
            return sequentialImageReader;
        }
    }

    private void discardArchiveImageCacheAfterDecodeFailure(int index, @Nullable String expectedPath) {
        if (sourceArchivePath == null || sourceArchivePath.trim().isEmpty()) return;
        if (expectedPath == null || expectedPath.trim().isEmpty()) return;
        if (index < 0 || index >= sourceEntryPaths.size()) return;
        String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, index);
        if (entryPath == null || entryPath.trim().isEmpty()) return;
        ArchiveImageEntryCache.discardReady(new File(expectedPath));
        verifiedSensitiveArchiveCachePaths.remove(new File(expectedPath).getAbsolutePath());
    }

    private void prefetchAdjacentImages(int centerIndex) {
        if (imagePaths.size() <= 1) return;
        final boolean fromArchive = sourceArchivePath != null
                && !sourceArchivePath.trim().isEmpty()
                && !sourceEntryPaths.isEmpty();
        final ArrayList<String> imagePathsSnapshot = new ArrayList<>(imagePaths);
        final ArrayList<String> entryPathsSnapshot = new ArrayList<>(sourceEntryPaths);
        final int total = imagePathsSnapshot.size();
        // Bitmap warm-up stays at the nearest neighbors (decoded bitmaps cost
        // real memory); file extraction deepens ahead once the paging
        // direction is sustained, so the shared forward stream keeps working
        // ahead of a continuous read instead of idling between page turns.
        final int sustained = com.readwide.manager.util.ImagePrefetchMath
                .sustainedDirection(pagingStreak);
        final int[] offsets = com.readwide.manager.util.ImagePrefetchMath.bitmapOffsets();
        final int[] extractionOffsets = com.readwide.manager.util.ImagePrefetchMath
                .extractionOffsets(sustained);
        final int planGeneration = prefetchPlanGeneration.incrementAndGet();

        if (!fromArchive) {
            // Plain filesystem images already sit on disk at their listed path, so
            // there is no archive entry to extract first: decode each neighbor
            // straight into the cache on the parallel decode pool.
            for (int off : offsets) {
                if (destroyed) break;
                int idx = ImageSequenceNavigationMath.nextIndex(centerIndex, off, total);
                if (idx == centerIndex) continue;
                final int decodeIndex = idx;
                prefetchDecodeExecutor.execute(
                        () -> prefetchDecodeIntoCache(decodeIndex, imagePathsSnapshot, entryPathsSnapshot));
            }
            return;
        }

        // Archive source: each neighbor must be extracted from the archive before
        // it can be decoded. Extraction is sequential (it shares archive state), so
        // run it on the sequence executor and then hand decoding to the parallel
        // pool so it does not block the next extraction.
        final String archivePathSnapshot = sourceArchivePath;
        final boolean forwardSequential = isSourceArchiveForwardReadable();
        final char[] passwordSnapshot;
        synchronized (archiveExtractLock) {
            passwordSnapshot = PasswordChars.cloneOf(sourceArchivePassword);
        }
        sequenceExecutor.execute(() -> {
            try {
                for (int off : extractionOffsets) {
                    if (destroyed) break;
                    // A newer page turn has re-planned the window; this plan's
                    // remaining offsets are centered on a page the user left.
                    if (prefetchPlanGeneration.get() != planGeneration) break;
                    // For a solid/sequential archive the read-ahead and the page the
                    // user asked for share one forward reader; pause read-ahead the
                    // moment an on-demand extraction is waiting so it gets the reader
                    // first. Prefetch resumes after the next page settles.
                    if (forwardSequential && onDemandReaderWaiters.get() > 0) break;
                    int idx = ImageSequenceNavigationMath.nextIndex(centerIndex, off, total);
                    if (idx == centerIndex) continue;
                    prefetchArchiveImageEntry(archivePathSnapshot, entryPathsSnapshot, imagePathsSnapshot, idx, passwordSnapshot, verifiedSensitiveArchiveCachePaths);
                    // Bitmap warm-up only for the nearest neighbors; the deep
                    // ahead run extracts files without holding decoded bitmaps.
                    boolean inDecodeWindow = Math.abs(off) <= 3;
                    if (inDecodeWindow && !destroyed) {
                        final int decodeIndex = idx;
                        prefetchDecodeExecutor.execute(
                                () -> prefetchDecodeIntoCache(decodeIndex, imagePathsSnapshot, entryPathsSnapshot));
                    }
                }
            } finally {
                PasswordChars.clear(passwordSnapshot);
            }
        });
    }

    /**
     * Decodes a neighbor page's preview bitmap into the cache (off the main
     * thread) so a later page turn to it is instant. No-op if already cached or
     * already being decoded.
     */
    private void prefetchDecodeIntoCache(int index,
                                         @NonNull ArrayList<String> imagePathsSnapshot,
                                         @NonNull ArrayList<String> entryPathsSnapshot) {
        if (destroyed || decodedBitmapCache == null) return;
        if (index < 0 || index >= imagePathsSnapshot.size()) return;
        if (decodedBitmapCache.get(index) != null) return;
        Integer key = index;
        if (!bitmapPrefetchInFlight.add(key)) return;
        final String path = imagePathsSnapshot.get(index);
        final String displayName = path != null ? new File(path).getName() : null;
        final Context appContext = getApplicationContext();
        try {
            if (path == null || !new File(path).exists()) return;
            LoadedImage decoded = ImageDecodeHelper.decodePreview(appContext, path, null, displayName);
            if (decoded == null || decoded.bitmap == null || decoded.bitmap.isRecycled()) return;
            final Bitmap bmp = decoded.bitmap;
            final boolean fullQuality = decoded.originalQuality;
            mainHandler.post(() -> {
                if (destroyed || decodedBitmapCache == null || bmp.isRecycled()) {
                    if (!bmp.isRecycled()) bmp.recycle();
                    return;
                }
                if (decodedBitmapCache.get(index) != null) {
                    bmp.recycle(); // someone else cached it meanwhile
                    return;
                }
                decodedBitmapCache.put(index, bmp);
                if (fullQuality) fullQualityCachedIndices.add(index);
            });
        } catch (Exception ignored) {
            // Prefetch is best-effort; a failed neighbor just decodes on demand.
        } finally {
            bitmapPrefetchInFlight.remove(key);
        }
    }

    private void prefetchArchiveImageEntry(@NonNull String archivePath,
                                           @NonNull ArrayList<String> entryPaths,
                                           @NonNull ArrayList<String> imagePaths,
                                           int index,
                                           @Nullable char[] password,
                                           @Nullable Set<String> verifiedSensitivePaths) {
        if (index < 0 || index >= imagePaths.size() || index >= entryPaths.size()) return;
        String expectedPath = imagePaths.get(index);
        String entryPath = ImageSequenceState.entryPathAt(entryPaths, index);
        if (expectedPath == null || expectedPath.trim().isEmpty()
                || entryPath == null || entryPath.trim().isEmpty()) return;
        File archive = new File(archivePath);
        if (!archive.exists() || !archive.isFile()) return;
        File outFile = new File(expectedPath);
        // Cheap cache-hit check first; applies whether or not this is a sequential archive.
        if (ArchiveImageEntryCache.isReadyImageFileForHandoff(entryPath, outFile)) return;
        // Branch through the memoized sequential reader instead of re-detecting the archive type
        // on every prefetch (which, for RAR, would re-read the file signature each call).
        SequentialArchiveImageReader reader = ensureSequentialReader(archive);
        if (reader != null) {
            // Sequential archive: prefetch only through the shared forward reader so a background
            // neighbor fetch never falls back to whole-archive extraction. Pages still behind the
            // read position are skipped here (extractBehindFrontier=false) and extracted on demand
            // instead, so a background fetch never holds the reader lock for a single-entry decode
            // while an on-demand page waits. This keeps the reading frontier, not the whole
            // archive, as the extraction bound.
            reader.ensureExtracted(entryPath, false);
            return;
        }
        ArchiveImageEntryCache.ensureReady(
                archive,
                entryPath,
                outFile,
                password,
                PasswordChars.hasPassword(password),
                verifiedSensitivePaths);
    }

    private void setLoading(boolean loading, @Nullable String message) {
        if (statusText == null) return;
        statusText.setText(message == null ? "" : message);
        statusText.setVisibility(!loading && !TextUtils.isEmpty(message) ? View.VISIBLE : View.GONE);
        if (!loading && !TextUtils.isEmpty(message)) statusText.bringToFront();
    }

    private void persistArchiveImageProgress() {
        if (prefs == null || sourceArchivePath == null || sourceArchivePath.trim().isEmpty()) return;
        String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, currentIndex);
        if (entryPath == null || entryPath.trim().isEmpty()) return;
        prefs.setArchiveLastImageEntryPath(sourceArchivePath, entryPath);
    }

    // Persist a reading-progress state for the image viewer so the recent list
    // shows a percent for image archives (and folder image sequences) the same
    // way it does for PDF/EPUB. Progress is keyed on the archive path when the
    // images come from one (so the archive entry in Recents gets the badge), and
    // on the current image path otherwise.
    // Debounced progress save: rapid page turns coalesce into one disk write,
    // since persistImageReadingProgress -> saveReadingStates serializes the whole
    // reading-state map. The final position is also saved immediately in onDestroy.
    private final Runnable imageProgressSaveRunnable = this::persistImageReadingProgress;

    private void scheduleImageReadingProgressSave() {
        mainHandler.removeCallbacks(imageProgressSaveRunnable);
        mainHandler.postDelayed(imageProgressSaveRunnable, 500L);
    }

    private void persistImageReadingProgress() {
        int total = imagePaths.size();
        if (total < 2) return;
        boolean fromArchive = sourceArchivePath != null && sourceArchivePath.trim().length() > 0;
        String targetPath = fromArchive ? sourceArchivePath : filePath;
        if (targetPath == null || targetPath.trim().isEmpty()) return;
        ReaderState state = new ReaderState(targetPath);
        state.setPageNumber(currentIndex + 1);
        state.setTotalPages(total);
        try {
            BookmarkManager.getInstance(this).saveReadingState(state);
        } catch (Exception ignored) {}
    }

    private boolean canModifyCurrentLocalFile() {
        if (!allowFileOps || filePath == null || filePath.trim().isEmpty()) return false;
        File file = new File(filePath);
        return file.exists() && file.isFile();
    }

    private void showImageOptionsPopup(@NonNull View anchor) {
        final boolean dark = prefs == null || prefs.shouldUseDarkColors(this);
        final int panel = dark ? Color.rgb(28, 28, 28) : Color.WHITE;
        final int fg = dark ? Color.WHITE : Color.rgb(32, 33, 36);
        final int danger = dark ? Color.rgb(255, 170, 170) : Color.rgb(176, 0, 32);
        final boolean ops = canModifyCurrentLocalFile();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dpToPx(4), 0, dpToPx(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panel);
        bg.setCornerRadius(dpToPx(10));
        box.setBackground(bg);

        PopupWindow popup = new PopupWindow(box, dpToPx(210), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(dpToPx(4));
        }

        addPopupRow(box, getString(R.string.file_info), fg, () -> { popup.dismiss(); showImageInfoDialog(); });
        addPopupRow(box, getString(R.string.image_view_options), fg, () -> { popup.dismiss(); showImageViewOptionsDialog(); });
        if (canShareCurrentImage()) {
            addPopupRow(box, getString(R.string.share), fg, () -> {
                popup.dismiss();
                shareCurrentImage();
            });
        }
        if (ops) {
            addPopupRow(box, getString(R.string.rename), fg, () -> {
                popup.dismiss();
                showRenameDialog();
            });
            addPopupRow(box, getString(R.string.cut), fg, () -> {
                popup.dismiss();
                startCutOperation();
            });
            addPopupRow(box, getString(R.string.delete), danger, () -> {
                popup.dismiss();
                showDeleteConfirmDialog();
            });
        }

        int xoff = -(dpToPx(210) - anchor.getWidth());
        popup.showAsDropDown(anchor, xoff, 0, Gravity.NO_GRAVITY);
    }

    private void showImageViewOptionsDialog() {
        Dialog dialog = dialogStyle.makeDialog();
        LinearLayout box = dialogStyle.makeBox();
        box.addView(dialogStyle.makeTitle(getString(R.string.image_view_options)));
        int fg = dialogStyle.textColor();
        int sub = dialogStyle.subTextColor();
        int currentDirection = currentImageSliderDirection();
        TextView ltr = dialogStyle.makeButton(
                optionLabel(R.string.image_slider_direction_ltr,
                        currentDirection == PrefsManager.IMAGE_SLIDER_DIRECTION_LTR),
                currentDirection == PrefsManager.IMAGE_SLIDER_DIRECTION_LTR ? fg : sub);
        TextView rtl = dialogStyle.makeButton(
                optionLabel(R.string.image_slider_direction_rtl,
                        currentDirection == PrefsManager.IMAGE_SLIDER_DIRECTION_RTL),
                currentDirection == PrefsManager.IMAGE_SLIDER_DIRECTION_RTL ? fg : sub);
        boolean tapPagingEnabled = isImageTapPagingEnabled();
        TextView tapPaging = dialogStyle.makeButton(
                getString(tapPagingEnabled
                        ? R.string.image_tap_paging_on
                        : R.string.image_tap_paging_off),
                tapPagingEnabled ? fg : sub);
        TextView cancel = dialogStyle.makeButton(getString(R.string.cancel), sub);
        box.addView(ltr, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        box.addView(rtl, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        box.addView(tapPaging, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        box.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        ltr.setOnClickListener(v -> {
            setImageSliderDirection(PrefsManager.IMAGE_SLIDER_DIRECTION_LTR);
            dialog.dismiss();
        });
        rtl.setOnClickListener(v -> {
            setImageSliderDirection(PrefsManager.IMAGE_SLIDER_DIRECTION_RTL);
            dialog.dismiss();
        });
        tapPaging.setOnClickListener(v -> {
            boolean enabled = !isImageTapPagingEnabled();
            setImageTapPagingEnabled(enabled);
            tapPaging.setText(getString(enabled
                    ? R.string.image_tap_paging_on
                    : R.string.image_tap_paging_off));
            tapPaging.setTextColor(enabled ? fg : sub);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(box);
        dialog.show();
        dialogStyle.setDialogWidth(dialog);
    }

    @NonNull
    private String optionLabel(int textRes, boolean selected) {
        return selected ? "✓ " + getString(textRes) : getString(textRes);
    }

    private int currentImageSliderDirection() {
        return prefs != null ? prefs.getImageSliderDirection() : PrefsManager.IMAGE_SLIDER_DIRECTION_LTR;
    }

    private boolean isImageTapPagingEnabled() {
        return prefs != null && prefs.getImageTapPagingEnabled();
    }

    private void setImageTapPagingEnabled(boolean enabled) {
        if (prefs != null) prefs.setImageTapPagingEnabled(enabled);
    }

    private void setImageSliderDirection(int direction) {
        if (prefs != null) prefs.setImageSliderDirection(direction);
        if (sliderController != null) {
            sliderController.setSliderDirection(currentImageSliderDirection());
            sliderController.update(currentIndex, imagePaths.size(), chromeVisible);
        }
    }

    private void addPopupRow(@NonNull LinearLayout box, @NonNull String label, int color, @NonNull Runnable action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(color);
        row.setTextSize(15f);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setSingleLine(true);
        row.setPadding(dpToPx(16), 0, dpToPx(16), 0);
        row.setOnClickListener(v -> action.run());
        box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)));
    }

    private void showOpsUnavailableToast() {
        ShortToast.show(this, R.string.image_file_ops_unavailable);
    }

    private boolean canShareCurrentImage() {
        if (fileUri != null && fileUri.trim().length() > 0) return true;
        if (filePath == null || filePath.trim().isEmpty()) return false;
        File file = new File(filePath);
        return file.exists() && file.isFile();
    }

    private void shareCurrentImage() {
        try {
            Uri uri = getCurrentShareUri();
            if (uri == null) {
                ShortToast.show(this, R.string.image_share_failed);
                return;
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(getCurrentImageMimeType());
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.share_image)));
        } catch (Exception e) {
            ShortToast.show(this, R.string.image_share_failed);
        }
    }

    @Nullable
    private Uri getCurrentShareUri() {
        if (fileUri != null && fileUri.trim().length() > 0) return Uri.parse(fileUri);
        if (filePath == null || filePath.trim().isEmpty()) return null;
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) return null;
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    @NonNull
    private String getCurrentImageMimeType() {
        return ImageInfoReader.mimeTypeForName(getDisplayName());
    }

    private void showImageInfoDialog() {
        Dialog dialog = dialogStyle.makeDialog();
        LinearLayout box = dialogStyle.makeBox();
        TextView title = dialogStyle.makeTitle(getString(R.string.file_info));
        box.addView(title);

        LinearLayout infoList = new LinearLayout(this);
        infoList.setOrientation(LinearLayout.VERTICAL);
        int fg = dialogStyle.textColor();
        int sub = dialogStyle.subTextColor();
        int panel = dialogStyle.panelColor();
        ImageInfo info = readCurrentImageInfo();

        dialogStyle.addInfoRow(infoList, getString(R.string.file_info_name), info.name, fg, sub, panel);
        dialogStyle.addInfoRow(infoList, getString(R.string.image_info_source), info.source, fg, sub, panel);
        if (!TextUtils.isEmpty(info.pathOrUri)) {
            dialogStyle.addInfoRow(infoList, getString(R.string.file_info_path), info.pathOrUri, fg, sub, panel);
        }
        dialogStyle.addInfoRow(infoList, getString(R.string.file_info_type), nonEmpty(info.type), fg, sub, panel);
        dialogStyle.addInfoRow(infoList, getString(R.string.image_info_mime), nonEmpty(info.mime), fg, sub, panel);
        dialogStyle.addInfoRow(infoList, getString(R.string.image_info_extension), nonEmpty(info.extension), fg, sub, panel);
        dialogStyle.addInfoRow(infoList, getString(R.string.file_info_size), nonEmpty(info.size), fg, sub, panel);
        dialogStyle.addInfoRow(infoList, getString(R.string.file_info_modified), nonEmpty(info.modified), fg, sub, panel);
        if (!TextUtils.isEmpty(info.created)) {
            dialogStyle.addInfoRow(infoList, getString(R.string.image_info_created_downloaded), info.created, fg, sub, panel);
        }
        dialogStyle.addInfoRow(infoList, getString(R.string.image_dimensions), nonEmpty(info.dimensions), fg, sub, panel);
        dialogStyle.addInfoRow(infoList, getString(R.string.image_info_aspect_ratio), nonEmpty(info.aspectRatio), fg, sub, panel);
        dialogStyle.addInfoRow(infoList, getString(R.string.image_info_megapixels), nonEmpty(info.megapixels), fg, sub, panel);
        if (!TextUtils.isEmpty(info.camera)) {
            dialogStyle.addInfoRow(infoList, getString(R.string.image_info_camera), info.camera, fg, sub, panel);
        }
        if (!TextUtils.isEmpty(info.taken)) {
            dialogStyle.addInfoRow(infoList, getString(R.string.image_info_taken), info.taken, fg, sub, panel);
        }
        if (!TextUtils.isEmpty(info.software)) {
            dialogStyle.addInfoRow(infoList, getString(R.string.image_info_software), info.software, fg, sub, panel);
        }
        if (!TextUtils.isEmpty(info.readable)) {
            dialogStyle.addInfoRow(infoList, getString(R.string.file_info_readable), info.readable, fg, sub, panel);
        }
        if (!TextUtils.isEmpty(info.writable)) {
            dialogStyle.addInfoRow(infoList, getString(R.string.file_info_writable), info.writable, fg, sub, panel);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(infoList);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dpToPx(480), getResources().getDisplayMetrics().heightPixels - dpToPx(200))));
        TextView close = dialogStyle.makeButton(getString(R.string.ok), fg);
        box.addView(close, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50)));
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(box);
        dialog.show();
        dialogStyle.setDialogWidth(dialog);
    }

    @NonNull
    private ImageInfo readCurrentImageInfo() {
        return ImageInfoReader.read(
                this,
                filePath,
                fileUri,
                getDisplayName(),
                getImageSourceLabel(),
                getCurrentPathOrUriText(),
                allowFileOps,
                currentBitmap);
    }

    @NonNull
    private String getImageSourceLabel() {
        if (fileUri != null && fileUri.trim().length() > 0) {
            return getString(R.string.image_info_source_content_uri);
        }
        if (allowFileOps) return getString(R.string.image_info_source_local);
        return getString(R.string.image_info_source_preview_cache);
    }

    @Nullable
    private String getCurrentPathOrUriText() {
        if (sourceArchivePath != null && sourceArchivePath.trim().length() > 0
                && currentIndex >= 0 && currentIndex < sourceEntryPaths.size()) {
            String entry = ImageSequenceState.entryPathAt(sourceEntryPaths, currentIndex);
            if (entry != null && entry.trim().length() > 0) return sourceArchivePath + "!" + entry;
        }
        if (filePath != null && filePath.trim().length() > 0) return new File(filePath).getAbsolutePath();
        if (fileUri != null && fileUri.trim().length() > 0) return fileUri;
        return null;
    }

    @NonNull
    private String nonEmpty(@Nullable String value) {
        return TextUtils.isEmpty(value) ? ImageInfoReader.unavailable(this) : value;
    }

    private void showRenameDialog() {
        if (!canModifyCurrentLocalFile()) { showOpsUnavailableToast(); return; }
        File file = new File(filePath);
        Dialog dialog = dialogStyle.makeDialog();
        LinearLayout box = dialogStyle.makeBox();
        box.addView(dialogStyle.makeTitle(getString(R.string.rename)));

        EditText input = new EditText(this);
        input.setText(file.getName());
        input.selectAll();
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(dialogStyle.textColor());
        input.setHintTextColor(dialogStyle.subTextColor());
        input.setTextSize(16f);
        input.setPadding(dpToPx(14), 0, dpToPx(14), 0);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(dialogStyle.panelColor());
        inputBg.setCornerRadius(dpToPx(12));
        input.setBackground(inputBg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52));
        inputLp.setMargins(0, 0, 0, dpToPx(10));
        box.addView(input, inputLp);

        TextView rename = dialogStyle.makeButton(getString(R.string.rename), dialogStyle.textColor());
        TextView cancel = dialogStyle.makeButton(getString(R.string.cancel), dialogStyle.subTextColor());
        box.addView(rename, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        box.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));

        rename.setOnClickListener(v -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) return;
            File parent = file.getParentFile();
            if (parent == null) return;
            File newFile = new File(parent, newName);
            if (FileSystemOps.renameInPlace(file, newName)) {
                filePath = newFile.getAbsolutePath();
                int renamedIndex = ImageSequenceState.applyRename(
                        imagePaths,
                        sourceDisplayNames,
                        file.getAbsolutePath(),
                        filePath,
                        newFile.getName());
                currentIndex = ImageSequenceNavigationMath.clampIndex(
                        renamedIndex >= 0 ? renamedIndex : currentIndex,
                        imagePaths.size());
                updateToolbarTitle();
                ShortToast.show(this, R.string.renamed);
                dialog.dismiss();
            } else {
                ShortToast.show(this, R.string.rename_failed);
            }
        });
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(box);
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        });
        dialog.show();
        dialogStyle.setDialogWidth(dialog);
    }

    private void showDeleteConfirmDialog() {
        if (!canModifyCurrentLocalFile()) { showOpsUnavailableToast(); return; }
        File file = new File(filePath);
        Dialog dialog = dialogStyle.makeDialog();
        LinearLayout box = dialogStyle.makeBox();
        int danger = Color.rgb(255, 125, 125);
        box.addView(dialogStyle.makeTitle(getString(R.string.delete)));
        TextView msg = new TextView(this);
        msg.setText(getString(R.string.delete_file_confirm, file.getName()));
        msg.setTextColor(dialogStyle.textColor());
        msg.setTextSize(16f);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(12));
        box.addView(msg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView delete = dialogStyle.makeButton(getString(R.string.delete), danger);
        TextView cancel = dialogStyle.makeButton(getString(R.string.cancel), dialogStyle.subTextColor());
        box.addView(delete, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        box.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        delete.setOnClickListener(v -> {
            String oldPath = file.getAbsolutePath();
            if (file.delete()) {
                ImageSequenceState.RemoveResult result = ImageSequenceState.removePath(
                        imagePaths,
                        sourceDisplayNames,
                        sourceEntryPaths,
                        oldPath,
                        currentIndex);
                ShortToast.show(this, R.string.deleted);
                dialog.dismiss();
                if (result.empty) {
                    finish();
                } else {
                    currentIndex = result.currentIndex;
                    filePath = result.currentPath;
                    updateToolbarTitle();
                    loadImageAsync();
                }
            } else {
                ShortToast.show(this, R.string.delete_failed);
            }
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(box);
        dialog.show();
        dialogStyle.setDialogWidth(dialog);
    }

    private void startCutOperation() {
        if (!canModifyCurrentLocalFile()) { showOpsUnavailableToast(); return; }
        File file = new File(filePath);
        FileClipboardController.StartResult result = FileClipboardController.getShared().start(file, false);
        if (result == FileClipboardController.StartResult.STARTED) {
            Toast.makeText(this, R.string.file_move_started, Toast.LENGTH_LONG).show();
        } else {
            ShortToast.show(this, R.string.file_move_failed);
        }
    }

    int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

}
