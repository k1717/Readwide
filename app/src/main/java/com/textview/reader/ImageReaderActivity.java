package com.textview.reader;

import android.app.Dialog;
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

import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.image.ImageDecodeHelper;
import com.textview.reader.image.ImageInfo;
import com.textview.reader.image.ImageInfoReader;
import com.textview.reader.image.LoadedImage;
import com.textview.reader.util.ImageSequenceNavigationMath;
import com.textview.reader.util.ImageSequenceState;
import com.textview.reader.util.FileSortUtils;
import com.textview.reader.util.FileClipboardController;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;
import com.textview.reader.util.ReaderKeyMap;
import com.textview.reader.view.ZoomImageView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final Object archiveExtractLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> imagePaths = new ArrayList<>();
    private final ArrayList<String> sourceDisplayNames = new ArrayList<>();
    private final ArrayList<String> sourceEntryPaths = new ArrayList<>();

    private PrefsManager prefs;
    private ImageDialogStyleController dialogStyle;
    private ZoomImageView imageView;
    private Toolbar toolbar;
    private ImageReaderSliderController sliderController;
    private TextView statusText;
    private String filePath;
    private String fileUri;
    private String sourceArchivePath;
    private String sequenceHandoffToken;
    private int currentIndex = 0;
    private boolean allowFileOps;
    private boolean destroyed;
    private boolean chromeVisible = true;
    private int systemTopInset;
    private int systemBottomInset;
    private Bitmap currentBitmap;
    private Drawable currentDrawable;
    private int imageLoadGeneration;
    private boolean currentImageDetailLoaded = true;
    private int detailRequestGeneration = -1;

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
        loadImageAsync();
        loadDeferredImageSequenceAsync();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ImageSequenceHandoffStore.discard(sequenceHandoffToken);
        sequenceHandoffToken = null;
        executor.shutdownNow();
        sequenceExecutor.shutdownNow();
        detailExecutor.shutdownNow();
        if (currentDrawable instanceof AnimatedImageDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ((AnimatedImageDrawable) currentDrawable).stop();
        }
        if (imageView != null) imageView.setImageDrawable(null);
        currentDrawable = null;
        persistArchiveImageProgress();
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
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
        if (token == null || token.trim().isEmpty()) return;
        sequenceExecutor.execute(() -> {
            ImageSequenceHandoffStore.Sequence sequence = ImageSequenceHandoffStore.consume(token);
            mainHandler.post(() -> {
                if (token.equals(sequenceHandoffToken)) sequenceHandoffToken = null;
                applyDeferredImageSequence(sequence);
            });
        });
    }

    private void applyDeferredImageSequence(@Nullable ImageSequenceHandoffStore.Sequence sequence) {
        if (destroyed || sequence == null || sequence.paths == null || sequence.paths.isEmpty()) return;
        String currentPath = filePath != null ? new File(filePath).getAbsolutePath() : null;
        if (currentPath == null || currentPath.trim().isEmpty()) return;

        int found = sequence.paths.indexOf(currentPath);
        if (found < 0) return;

        imagePaths.clear();
        imagePaths.addAll(sequence.paths);
        sourceDisplayNames.clear();
        sourceDisplayNames.addAll(sequence.displayNames);
        sourceEntryPaths.clear();
        sourceEntryPaths.addAll(sequence.entryPaths);
        ImageSequenceState.normalizeMetadataLists(imagePaths, sourceDisplayNames, sourceEntryPaths);
        currentIndex = ImageSequenceNavigationMath.clampIndex(found, imagePaths.size());
        filePath = imagePaths.get(currentIndex);
        updateToolbarTitle();
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
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setCallbacks(new ZoomImageView.Callbacks() {
            @Override public void onSingleTap() { toggleChrome(); }
            @Override public void onSwipeLeft() { showAdjacentImage(1); }
            @Override public void onSwipeRight() { showAdjacentImage(-1); }
            @Override public void onZoomRequested() { requestDetailImageForCurrent(); }
        });
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(Color.argb(230, 0, 0, 0));
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
                dpToPx(56),
                Gravity.TOP);
        root.addView(toolbar, toolbarLp);
        tintToolbarMenuIcon(toolbar);

        sliderController = new ImageReaderSliderController(this, this::showImageAtIndex);
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
            systemTopInset = Math.max(0, bars.top);
            systemBottomInset = Math.max(0, bars.bottom);
            toolbar.setPadding(toolbar.getPaddingLeft(), bars.top,
                    toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            ViewGroup.LayoutParams raw = toolbar.getLayoutParams();
            if (raw != null) {
                int targetHeight = dpToPx(56) + bars.top;
                if (raw.height != targetHeight) {
                    raw.height = targetHeight;
                    toolbar.setLayoutParams(raw);
                }
            }
            if (sliderController != null) sliderController.applyBottomInset(bars.bottom);
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

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        if (toolbar != null) toolbar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        if (sliderController != null) sliderController.update(currentIndex, imagePaths.size(), chromeVisible);
    }

    private void updateImageViewportBounds() {
        if (imageView == null) return;
        int top = dpToPx(56) + systemTopInset;
        int sliderItemCountForBounds = shouldReserveImageSliderSpace()
                ? Math.max(2, imagePaths.size())
                : imagePaths.size();
        int bottom = sliderController != null
                ? sliderController.reservedViewportBottomInset(sliderItemCountForBounds)
                : systemBottomInset;
        imageView.setPadding(0, 0, 0, 0);
        ViewGroup.LayoutParams raw = imageView.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (lp.topMargin != top || lp.bottomMargin != bottom) {
                lp.topMargin = top;
                lp.bottomMargin = bottom;
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
        currentIndex = next;
        filePath = imagePaths.get(currentIndex);
        fileUri = null;
        persistArchiveImageProgress();
        updateToolbarTitle();
        loadImageAsync();
    }

    private void loadImageAsync() {
        final int generation = ++imageLoadGeneration;
        detailRequestGeneration = -1;
        currentImageDetailLoaded = false;
        final int index = currentIndex;
        final String path = filePath;
        final String uri = fileUri;
        final String displayName = getDisplayName();
        setLoading(true, null);
        updateToolbarTitle();
        executor.execute(() -> {
            LoadedImage loaded = null;
            try {
                if (ensureArchiveImageExtracted(index, path)) {
                    loaded = ImageDecodeHelper.decodePreview(this, path, uri, displayName);
                }
            } catch (Exception ignored) {
                loaded = null;
            }
            LoadedImage result = loaded;
            mainHandler.post(() -> {
                if (destroyed || generation != imageLoadGeneration) {
                    recycleLoadedImage(result);
                    return;
                }
                if (result != null && (result.bitmap != null || result.drawable != null)) {
                    applyLoadedImage(result, false);
                    currentImageDetailLoaded = result.originalQuality;
                    persistArchiveImageProgress();
                    prefetchAdjacentArchiveImages(index);
                    setLoading(false, null);
                } else {
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
        detailExecutor.execute(() -> {
            LoadedImage loaded = null;
            try {
                if (ensureArchiveImageExtracted(index, path)) {
                    loaded = ImageDecodeHelper.decodeDetail(this, path, uri, displayName);
                }
            } catch (Exception ignored) {
                loaded = null;
            }
            LoadedImage result = loaded;
            mainHandler.post(() -> {
                if (destroyed || generation != imageLoadGeneration) {
                    recycleLoadedImage(result);
                    return;
                }
                if (result != null && (result.bitmap != null || result.drawable != null)) {
                    applyLoadedImage(result, true);
                    currentImageDetailLoaded = result.originalQuality;
                }
            });
        });
    }

    private void applyLoadedImage(@NonNull LoadedImage result, boolean preserveViewport) {
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
        if (oldBitmap != null && oldBitmap != currentBitmap && !oldBitmap.isRecycled()) oldBitmap.recycle();
    }

    private void recycleLoadedImage(@Nullable LoadedImage result) {
        if (result != null && result.bitmap != null && !result.bitmap.isRecycled()) result.bitmap.recycle();
    }


    private boolean ensureArchiveImageExtracted(int index, @Nullable String expectedPath) {
        if (expectedPath == null || expectedPath.trim().isEmpty()) return fileUri != null && fileUri.trim().length() > 0;
        File outFile = new File(expectedPath);
        if (outFile.exists() && outFile.isFile() && outFile.length() > 0L) return true;
        if (sourceArchivePath == null || sourceArchivePath.trim().isEmpty()) return false;
        if (index < 0 || index >= sourceEntryPaths.size()) return false;
        String entryPath = ImageSequenceState.entryPathAt(sourceEntryPaths, index);
        if (entryPath == null || entryPath.trim().isEmpty()) return false;
        File archive = new File(sourceArchivePath);
        if (!archive.exists() || !archive.isFile()) return false;
        synchronized (archiveExtractLock) {
            if (outFile.exists() && outFile.isFile() && outFile.length() > 0L) return true;
            return ArchiveSupport.extractSingleEntryDetailed(archive, entryPath, outFile, null).success
                    && outFile.exists()
                    && outFile.isFile()
                    && outFile.length() > 0L;
        }
    }

    private void prefetchAdjacentArchiveImages(int centerIndex) {
        if (sourceArchivePath == null || sourceArchivePath.trim().isEmpty()) return;
        if (sourceEntryPaths.isEmpty() || imagePaths.size() <= 1) return;
        final int total = imagePaths.size();
        final int first = ImageSequenceNavigationMath.nextIndex(centerIndex, 1, total);
        final int second = ImageSequenceNavigationMath.nextIndex(centerIndex, -1, total);
        sequenceExecutor.execute(() -> {
            ensureArchiveImageExtracted(first, first >= 0 && first < imagePaths.size() ? imagePaths.get(first) : null);
            if (second != first) {
                ensureArchiveImageExtracted(second, second >= 0 && second < imagePaths.size() ? imagePaths.get(second) : null);
            }
        });
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
            if (file.renameTo(newFile)) {
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
