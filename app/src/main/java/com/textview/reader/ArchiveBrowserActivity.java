package com.textview.reader;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.model.ReaderState;
import com.textview.reader.util.BookmarkManager;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArchiveBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_ARCHIVE_PATH = "com.textview.reader.ARCHIVE_PATH";
    public static final String EXTRA_FORCE_FOLDER_PREVIEW = "com.textview.reader.FORCE_ARCHIVE_FOLDER_PREVIEW";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PrefsManager prefs;
    private BookmarkManager bookmarkManager;
    private File archiveFile;
    private final Object passwordHandoffLock = new Object();
    private char[] archivePassword;
    private char[] pendingAcceptedArchivePassword;
    private String currentPrefix = "";
    private List<ArchiveSupport.EntryInfo> allEntries = new ArrayList<>();

    private Toolbar toolbar;
    private TextView pathText;
    private TextView emptyText;
    private RecyclerView recyclerView;
    private EntryAdapter adapter;
    private EditText archiveSearchInput;
    private TextView archiveFilterAll;
    private TextView archiveFilterGeneral;
    private TextView archiveFilterTxt;
    private TextView archiveFilterArchive;
    private TextView archiveFilterPdf;
    private TextView archiveFilterEpub;
    private TextView archiveFilterWord;
    private TextView archiveFilterImage;
    private HorizontalScrollView archiveFilterScroll;
    private ImageView archiveSortButton;
    private int archiveFilterStepPx;
    private int archiveSortMode = PrefsManager.SORT_NAME_ASC;
    private final Deque<ArchiveFolderSortState> archiveFolderSortStack = new ArrayDeque<>();
    private boolean autoOpenedComicArchive = false;
    private boolean forceFolderPreview = false;
    private final Runnable archiveFilterSnapRunnable = this::snapArchiveFilterToSlot;
    private String archiveSearchQuery = "";
    private int activeArchiveFilter = FILTER_ALL;
    private static final int FILTER_ALL = ArchiveEntryListController.FILTER_ALL;
    private static final int FILTER_GENERAL = ArchiveEntryListController.FILTER_GENERAL;
    private static final int FILTER_TXT = ArchiveEntryListController.FILTER_TXT;
    private static final int FILTER_ARCHIVE = ArchiveEntryListController.FILTER_ARCHIVE;
    private static final int FILTER_PDF = ArchiveEntryListController.FILTER_PDF;
    private static final int FILTER_EPUB = ArchiveEntryListController.FILTER_EPUB;
    private static final int FILTER_WORD = ArchiveEntryListController.FILTER_WORD;
    private static final int FILTER_IMAGE = ArchiveEntryListController.FILTER_IMAGE;
    private boolean destroyed = false;
    private Dialog archivePreviewLoadingDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PrefsManager.getInstance(this);
        bookmarkManager = BookmarkManager.getInstance(this);
        archiveSortMode = rootArchiveSortMode();
        forceFolderPreview = getIntent().getBooleanExtra(EXTRA_FORCE_FOLDER_PREVIEW, false);

        String path = getIntent().getStringExtra(EXTRA_ARCHIVE_PATH);
        if (path == null || path.trim().isEmpty()) {
            finish();
            return;
        }
        archiveFile = new File(path);
        if (!archiveFile.exists() || !ArchiveSupport.isSupportedArchive(archiveFile)) {
            ShortToast.show(this, R.string.archive_open_failed);
            finish();
            return;
        }
        saveHostArchiveRecentState();
        executor.execute(() -> ArchivePreviewCache.pruneOtherArchiveCaches(getApplicationContext(), archiveFile));

        buildUi();
        loadArchiveEntries(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        saveHostArchiveRecentState();
    }

    @Override
    protected void onDestroy() {
        hideArchivePreviewLoadingWindow();
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        clearArchivePassword();
        clearPendingAcceptedArchivePassword();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentPrefix != null && currentPrefix.length() > 0) {
            goUpOneArchiveFolder();
            return;
        }
        super.onBackPressed();
    }

    private void setArchivePassword(@Nullable char[] password) {
        clearArchivePassword();
        archivePassword = PasswordChars.cloneOf(password);
        PasswordChars.clear(password);
    }

    @Nullable
    private char[] snapshotArchivePassword() {
        return PasswordChars.cloneOf(archivePassword);
    }

    private void clearArchivePassword() {
        if (archivePassword != null) {
            PasswordChars.clear(archivePassword);
            archivePassword = null;
        }
    }

    private void storePendingAcceptedArchivePassword(@Nullable char[] password) {
        synchronized (passwordHandoffLock) {
            PasswordChars.clear(pendingAcceptedArchivePassword);
            pendingAcceptedArchivePassword = PasswordChars.cloneOf(password);
        }
    }

    @Nullable
    private char[] takePendingAcceptedArchivePassword() {
        synchronized (passwordHandoffLock) {
            char[] password = pendingAcceptedArchivePassword;
            pendingAcceptedArchivePassword = null;
            return password;
        }
    }

    private void clearPendingAcceptedArchivePassword() {
        synchronized (passwordHandoffLock) {
            PasswordChars.clear(pendingAcceptedArchivePassword);
            pendingAcceptedArchivePassword = null;
        }
    }

    private void saveHostArchiveRecentState() {
        if (bookmarkManager == null || archiveFile == null || !archiveFile.exists()) return;
        try {
            ReaderState state = new ReaderState(archiveFile.getAbsolutePath());
            state.setFileLength(archiveFile.length());
            state.setPageNumber(0);
            state.setTotalPages(0);
            bookmarkManager.saveReadingState(state);
        } catch (Exception ignored) {
            // Recent ordering must not block archive preview/opening.
        }
    }

    private void buildUi() {
        boolean dark = prefs == null || prefs.shouldUseDarkColors(this);
        int bg = prefs != null ? prefs.getMainBgColor(this) : (dark ? Color.rgb(33, 33, 33) : Color.WHITE);
        int bar = prefs != null ? prefs.getMainBarColor(this) : (dark ? Color.rgb(24, 35, 52) : Color.rgb(25, 83, 155));
        int fg = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        setContentView(root);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(bg);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(!dark);
        applyArchiveSystemInsets(root);

        toolbar = new Toolbar(this);
        toolbar.setTitle(archiveFile.getName());
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setBackgroundColor(bar);
        toolbar.setNavigationContentDescription(R.string.back);
        android.graphics.drawable.Drawable nav = ContextCompat.getDrawable(this, R.drawable.ic_bottom_arrow_left);
        if (nav != null) {
            android.graphics.drawable.Drawable wrapped = DrawableCompat.wrap(nav.mutate());
            DrawableCompat.setTint(wrapped, Color.WHITE);
            toolbar.setNavigationIcon(wrapped);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(56)));

        pathText = new TextView(this);
        pathText.setTextColor(sub);
        pathText.setTextSize(13f);
        pathText.setSingleLine(true);
        pathText.setEllipsize(TextUtils.TruncateAt.START);
        pathText.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        root.addView(pathText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null);
        recyclerView.setHasFixedSize(true);
        adapter = new EntryAdapter(fg, sub, prefs != null ? prefs.getMainFileLongHoldColor(this) : Color.LTGRAY);
        recyclerView.setAdapter(adapter);
        root.addView(recyclerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        addArchiveBottomInterface(root, bg, fg, sub);

        emptyText = new TextView(this);
        emptyText.setTextColor(sub);
        emptyText.setTextSize(16f);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(54)));
    }

    private void addArchiveBottomInterface(@NonNull LinearLayout root, int bg, int fg, int sub) {
        int panel = prefs != null ? prefs.getMainPanelColor(this) : bg;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(bg);
        bar.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        archiveFilterScroll = scroll;
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));

        archiveFilterAll = addArchiveFilterChip(row, R.string.file_filter_all, FILTER_ALL);
        archiveFilterGeneral = addArchiveFilterChip(row, R.string.file_filter_general, FILTER_GENERAL);
        archiveFilterTxt = addArchiveFilterChip(row, R.string.file_filter_txt, FILTER_TXT);
        archiveFilterArchive = addArchiveFilterChip(row, R.string.file_filter_archive, FILTER_ARCHIVE);
        archiveFilterPdf = addArchiveFilterChip(row, R.string.file_filter_pdf, FILTER_PDF);
        archiveFilterEpub = addArchiveFilterChip(row, R.string.file_filter_epub, FILTER_EPUB);
        archiveFilterWord = addArchiveFilterChip(row, R.string.file_filter_word, FILTER_WORD);
        archiveFilterImage = addArchiveFilterChip(row, R.string.file_filter_img, FILTER_IMAGE);

        bar.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(38)));
        scroll.addOnLayoutChangeListener((v, left, top, right, bottom,
                                           oldLeft, oldTop, oldRight, oldBottom) ->
                applyArchiveFilterSlotWidths(scroll, row));
        scroll.post(() -> applyArchiveFilterSlotWidths(scroll, row));
        scroll.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.removeCallbacks(archiveFilterSnapRunnable);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.postDelayed(archiveFilterSnapRunnable, 110L);
                    break;
                default:
                    break;
            }
            return false;
        });

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0, dpToPx(4), 0, 0);

        archiveSearchInput = new EditText(this);
        archiveSearchInput.setSingleLine(true);
        archiveSearchInput.setMaxLines(1);
        archiveSearchInput.setHint(R.string.file_search_live_hint);
        archiveSearchInput.setTextColor(fg);
        archiveSearchInput.setHintTextColor(sub);
        archiveSearchInput.setTextSize(14f);
        archiveSearchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        archiveSearchInput.setBackgroundColor(panel);
        archiveSearchInput.setPadding(dpToPx(12), 0, dpToPx(12), 0);
        archiveSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                archiveSearchQuery = text == null ? "" : text.toString().trim().toLowerCase(Locale.ROOT);
                renderCurrentFolder();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        searchRow.addView(archiveSearchInput, new LinearLayout.LayoutParams(
                0,
                dpToPx(42),
                1f));

        archiveSortButton = new ImageView(this);
        archiveSortButton.setImageResource(R.drawable.ic_sort);
        archiveSortButton.setContentDescription(getString(R.string.sort_by));
        archiveSortButton.setPadding(dpToPx(11), dpToPx(9), dpToPx(11), dpToPx(9));
        archiveSortButton.setBackground(makeArchiveRowBackground(prefs != null ? prefs.getMainFileLongHoldColor(this) : Color.LTGRAY));
        archiveSortButton.setColorFilter(fg);
        archiveSortButton.setOnClickListener(v -> showArchiveSortDialog());
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(42));
        sortLp.setMargins(dpToPx(8), 0, 0, 0);
        searchRow.addView(archiveSortButton, sortLp);

        bar.addView(searchRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        updateArchiveFilterChips();
    }

    private void applyArchiveSystemInsets(@NonNull View root) {
        final int extraTop = dpToPx(4);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, bars.top + extraTop, 0, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void applyArchiveFilterSlotWidths(@NonNull HorizontalScrollView scroll,
                                              @NonNull LinearLayout row) {
        if (scroll.getWidth() <= 0) return;
        final int visibleSlots = 5;
        final int gap = dpToPx(4);
        final int slot = Math.max(dpToPx(50),
                (scroll.getWidth() - (gap * (visibleSlots - 1))) / visibleSlots);
        archiveFilterStepPx = slot + gap;
        View[] chips = new View[] { archiveFilterAll, archiveFilterGeneral, archiveFilterTxt,
                archiveFilterArchive, archiveFilterPdf, archiveFilterEpub, archiveFilterWord, archiveFilterImage };
        for (int i = 0; i < chips.length; i++) {
            View chip = chips[i];
            if (chip == null) continue;
            ViewGroup.LayoutParams rawLp = chip.getLayoutParams();
            if (rawLp == null) continue;
            rawLp.width = slot;
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) rawLp;
                mlp.setMargins(i == 0 ? 0 : gap, mlp.topMargin, 0, mlp.bottomMargin);
            }
            chip.setLayoutParams(rawLp);
        }
        row.setPadding(0, 0, gap, 0);
        scroll.post(() -> {
            int maxScroll = Math.max(0, row.getWidth() - scroll.getWidth());
            if (scroll.getScrollX() > maxScroll) scroll.scrollTo(maxScroll, 0);
        });
    }

    private void snapArchiveFilterToSlot() {
        HorizontalScrollView scroll = archiveFilterScroll;
        if (scroll == null || scroll.getChildCount() == 0 || scroll.getWidth() <= 0) return;
        int step = Math.max(1, archiveFilterStepPx > 0 ? archiveFilterStepPx : scroll.getWidth() / 5);
        int maxScroll = Math.max(0, scroll.getChildAt(0).getWidth() - scroll.getWidth());
        if (maxScroll <= 1) {
            scroll.smoothScrollTo(0, 0);
            return;
        }
        int current = scroll.getScrollX();
        int target = Math.round(current / (float) step) * step;
        target = Math.max(0, Math.min(target, maxScroll));
        if (Math.abs(target - current) > 1) {
            scroll.smoothScrollTo(target, 0);
        }
    }

    private TextView addArchiveFilterChip(@NonNull LinearLayout row, int labelRes, int filter) {
        TextView chip = new TextView(this);
        chip.setText(labelRes);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextSize(12f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setOnClickListener(v -> {
            activeArchiveFilter = filter;
            updateArchiveFilterChips();
            renderCurrentFolder();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(58), dpToPx(30));
        row.addView(chip, lp);
        return chip;
    }

    private void updateArchiveFilterChips() {
        styleArchiveFilterChip(archiveFilterAll, activeArchiveFilter == FILTER_ALL);
        styleArchiveFilterChip(archiveFilterGeneral, activeArchiveFilter == FILTER_GENERAL);
        styleArchiveFilterChip(archiveFilterTxt, activeArchiveFilter == FILTER_TXT);
        styleArchiveFilterChip(archiveFilterArchive, activeArchiveFilter == FILTER_ARCHIVE);
        styleArchiveFilterChip(archiveFilterPdf, activeArchiveFilter == FILTER_PDF);
        styleArchiveFilterChip(archiveFilterEpub, activeArchiveFilter == FILTER_EPUB);
        styleArchiveFilterChip(archiveFilterWord, activeArchiveFilter == FILTER_WORD);
        styleArchiveFilterChip(archiveFilterImage, activeArchiveFilter == FILTER_IMAGE);
    }

    private void styleArchiveFilterChip(@Nullable TextView chip, boolean selected) {
        if (chip == null) return;
        boolean dark = prefs == null || prefs.shouldUseDarkColors(this);
        int bg = selected
                ? (prefs != null ? prefs.getMainFileLongHoldColor(this) : (dark ? Color.rgb(84, 100, 128) : Color.rgb(214, 230, 255)))
                : (prefs != null ? prefs.getMainFileTypeChipColor(this) : (dark ? Color.rgb(30, 30, 30) : Color.rgb(238, 238, 238)));
        int stroke = selected
                ? (prefs != null ? prefs.getMainControlColor(this) : (dark ? Color.WHITE : Color.rgb(32, 33, 36)))
                : (prefs != null ? prefs.getMainOutlineColor(this) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(190, 190, 190)));
        int fg = selected ? readableTextForBackground(bg)
                : (prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(232, 234, 237) : Color.rgb(32, 33, 36)));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(dpToPx(15));
        shape.setStroke(Math.max(1, dpToPx(1)), stroke);
        chip.setBackground(shape);
        chip.setTextColor(fg);
    }

    private StateListDrawable makeArchiveRowBackground(int pressedColor) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setCornerRadius(dpToPx(12));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    private int readableTextForBackground(int backgroundColor) {
        return UiColorUtils.readableChipTextColorForBackground(backgroundColor);
    }

    @NonNull
    private List<ArchiveSupport.EntryInfo> filterArchiveEntries(@NonNull List<ArchiveSupport.EntryInfo> source) {
        return ArchiveEntryListController.filter(source, archiveSearchQuery, activeArchiveFilter, archiveSortMode);
    }

    private void loadArchiveEntries(@Nullable char[] password) {
        showLoading(true, getString(R.string.loading));
        executor.execute(() -> {
            char[] acceptedPassword = null;
            try {
                acceptedPassword = PasswordChars.cloneOf(password);
                List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archiveFile, password);
                final char[] acceptedPasswordForUi = acceptedPassword;
                acceptedPassword = null;
                storePendingAcceptedArchivePassword(acceptedPasswordForUi);
                boolean posted = mainHandler.post(() -> {
                    char[] passwordForUi = takePendingAcceptedArchivePassword();
                    try {
                        if (destroyed) return;
                        setArchivePassword(passwordForUi);
                        allEntries = entries;
                        currentPrefix = "";
                        archiveFolderSortStack.clear();
                        archiveSortMode = rootArchiveSortMode();
                        renderCurrentFolder();
                        showLoading(false, null);
                        maybeAutoOpenComicArchive();
                    } finally {
                        PasswordChars.clear(passwordForUi);
                    }
                });
                if (!posted) {
                    clearPendingAcceptedArchivePassword();
                }
                PasswordChars.clear(acceptedPasswordForUi);
            } catch (ArchiveSupport.PasswordRequiredException e) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    showLoading(false, null);
                    clearArchivePassword();
                    showPasswordDialog(passwordText -> loadArchiveEntries(passwordText.toCharArray()));
                });
            } catch (ArchiveSupport.UnsupportedArchiveFeatureException e) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    showLoading(false, null);
                    showArchiveSupportBoundaryDialog(
                            ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, e.getMessage()),
                            true);
                });
            } catch (Exception e) {
                final boolean retryPassword = shouldRetryArchivePasswordAfterListFailure(password, e);
                final boolean showPasswordFailureToast = PasswordChars.hasPassword(password);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    showLoading(false, null);
                    if (retryPassword) {
                        clearArchivePassword();
                        showPasswordDialogWithOptionalFailureToast(showPasswordFailureToast,
                                passwordText -> loadArchiveEntries(passwordText.toCharArray()));
                    } else {
                        ShortToast.show(this, R.string.archive_open_failed);
                        finish();
                    }
                });
            } finally {
                PasswordChars.clear(acceptedPassword);
                PasswordChars.clear(password);
            }
        });
    }

    private void renderCurrentFolder() {
        String label = currentPrefix == null || currentPrefix.length() == 0
                ? getString(R.string.archive_root)
                : currentPrefix;
        pathText.setText(label);
        List<ArchiveSupport.EntryInfo> visible = filterArchiveEntries(
                buildDirectChildren(currentPrefix == null ? "" : currentPrefix));
        adapter.setEntries(visible);
        emptyText.setText(R.string.no_archive_entries);
        emptyText.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.scrollToPosition(0);
    }

    @NonNull
    private List<ArchiveSupport.EntryInfo> buildDirectChildren(@NonNull String prefix) {
        return ArchiveEntryListController.directChildren(allEntries, prefix, archiveSortMode);
    }

    private void sortArchiveEntries(@NonNull List<ArchiveSupport.EntryInfo> target, int sortMode) {
        ArchiveEntryListController.sort(target, sortMode);
    }

    private void showArchiveSortDialog() {
        boolean dark = prefs == null || prefs.shouldUseDarkColors(this);
        int bg = prefs != null ? prefs.getMainBgColor(this) : (dark ? Color.rgb(33, 33, 33) : Color.WHITE);
        int panel = prefs != null ? prefs.getMainPanelColor(this) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        int fg = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        int line = prefs != null ? prefs.getMainOutlineColor(this) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpToPx(18), dpToPx(16), dpToPx(18), dpToPx(10));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(dpToPx(18));
        bgShape.setStroke(Math.max(1, dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(this);
        title.setText(R.string.sort_by);
        title.setTextColor(fg);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(10));
        box.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        int[] modes = new int[] {
                PrefsManager.SORT_NAME_ASC,
                PrefsManager.SORT_NAME_DESC,
                PrefsManager.SORT_DATE_NEW,
                PrefsManager.SORT_DATE_OLD,
                PrefsManager.SORT_SIZE_LARGE,
                PrefsManager.SORT_SIZE_SMALL,
                PrefsManager.SORT_TYPE
        };
        int[] labels = new int[] {
                R.string.sort_name_asc,
                R.string.sort_name_desc,
                R.string.sort_date_new,
                R.string.sort_date_old,
                R.string.sort_size_large,
                R.string.sort_size_small,
                R.string.sort_type
        };
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(3000 + modes[i]);
            rb.setText(labels[i]);
            rb.setTextColor(fg);
            rb.setTextSize(15f);
            rb.setGravity(Gravity.CENTER_VERTICAL);
            rb.setPadding(dpToPx(8), 0, dpToPx(8), 0);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                rb.setButtonTintList(ColorStateList.valueOf(fg));
            }
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(panel);
            rowBg.setCornerRadius(dpToPx(12));
            rb.setBackground(rowBg);
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, dpToPx(44));
            lp.setMargins(0, dpToPx(6), 0, 0);
            group.addView(rb, lp);
        }
        group.check(3000 + archiveSortMode);
        box.addView(group, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final Dialog[] ref = new Dialog[1];
        addDialogRow(box, getString(R.string.cancel), sub, panel, () -> {
            if (ref[0] != null) ref[0].dismiss();
        });

        Dialog dialog = new Dialog(this);
        ref[0] = dialog;
        group.setOnCheckedChangeListener((g, checkedId) -> {
            int selected = checkedId - 3000;
            archiveSortMode = selected;
            if (prefs != null && isArchiveRootFolder()) prefs.setArchiveSortMode(selected);
            renderCurrentFolder();
            dialog.dismiss();
        });
        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            int width = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.88f), dpToPx(420));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void openEntry(@NonNull ArchiveSupport.EntryInfo entry) {
        saveHostArchiveRecentState();
        if (entry.directory) {
            enterArchiveFolder(entry.path.endsWith("/") ? entry.path : entry.path + "/");
            return;
        }
        extractEntryAndOpen(entry);
    }

    private void extractEntryAndOpen(@NonNull ArchiveSupport.EntryInfo entry) {
        if (FileUtils.isImageFile(entry.name())) {
            extractImageSiblingEntriesAndOpen(entry);
            return;
        }
        showArchivePreviewLoadingWindow();
        File outFile = buildPreviewOutputFile(entry);
        final File archiveSnapshot = archiveFile;
        final char[] passwordSnapshot = snapshotArchivePassword();
        executor.execute(() -> {
            try {
                ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                        archiveSnapshot,
                        entry.path,
                        outFile,
                        passwordSnapshot);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    hideArchivePreviewLoadingWindow();
                    if (!result.success || !outFile.exists()) {
                        if (shouldAskArchivePassword(result)) {
                            showPasswordDialogAfterFailedResult(result, passwordText -> {
                                setArchivePassword(passwordText.toCharArray());
                                extractEntryAndOpen(entry);
                            });
                        } else {
                            showArchiveEntryExtractionFailure(result);
                        }
                        return;
                    }
                    openExtractedFile(outFile);
                });
            } finally {
                PasswordChars.clear(passwordSnapshot);
            }
        });
    }

    @NonNull
    private File buildPreviewOutputFile(@NonNull ArchiveSupport.EntryInfo entry) {
        boolean sensitive = archivePassword != null && archivePassword.length > 0;
        return ArchiveImageSequenceLoader.outputFileForEntry(this, archiveFile, entry, sensitive);
    }

    private void extractImageSiblingEntriesAndOpen(@NonNull ArchiveSupport.EntryInfo selectedEntry) {
        openArchiveImageSequence(selectedEntry, false);
    }

    private void maybeAutoOpenComicArchive() {
        if (autoOpenedComicArchive || archiveFile == null || !shouldAutoOpenArchiveAsComic()) return;
        if (allEntries == null || allEntries.isEmpty()) return;
        autoOpenedComicArchive = true;
        openArchiveImageSequence(null, true);
    }

    private boolean shouldAutoOpenArchiveAsComic() {
        if (forceFolderPreview) return false;
        if (archiveFile == null) return false;
        if (isComicBookArchiveName(archiveFile.getName())) return true;
        return prefs != null && prefs.shouldOpenGenericArchivesAsComics();
    }

    private boolean isComicBookArchiveName(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cbz") || lower.endsWith(".cbr") || lower.endsWith(".cb7")
                || lower.matches(".*\\.cb7\\.\\d{3}$") || lower.endsWith(".cbt");
    }

    private void openArchiveImageSequence(@Nullable ArchiveSupport.EntryInfo selectedEntry, boolean useSavedPosition) {
        saveHostArchiveRecentState();
        showArchivePreviewLoadingWindow();
        List<ArchiveSupport.EntryInfo> images = collectImageEntriesForSequence(selectedEntry);
        if (images.isEmpty()) {
            hideArchivePreviewLoadingWindow();
            if (!useSavedPosition) ShortToast.show(this, R.string.archive_entry_unsupported);
            return;
        }

        String requestedPath = selectedEntry == null ? null : selectedEntry.path;
        if ((requestedPath == null || requestedPath.length() == 0) && useSavedPosition && prefs != null) {
            String saved = prefs.getArchiveLastImageEntryPath(archiveFile.getAbsolutePath());
            if (saved != null && saved.trim().length() > 0) requestedPath = saved;
        }
        int selectedIndex = 0;
        if (requestedPath != null && requestedPath.length() > 0) {
            for (int i = 0; i < images.size(); i++) {
                if (requestedPath.equals(images.get(i).path)) {
                    selectedIndex = i;
                    break;
                }
            }
        }

        final List<ArchiveSupport.EntryInfo> sequence = images;
        final int targetIndex = selectedIndex;
        final boolean finishAfterOpen = useSavedPosition
                && selectedEntry == null
                && archiveFile != null
                && shouldAutoOpenArchiveAsComic();
        openLazyArchiveImageSequence(selectedEntry, useSavedPosition, sequence, targetIndex, finishAfterOpen);
    }


    private void openLazyArchiveImageSequence(@Nullable ArchiveSupport.EntryInfo selectedEntry,
                                              boolean useSavedPosition,
                                              @NonNull List<ArchiveSupport.EntryInfo> sequence,
                                              int targetIndex,
                                              boolean finishAfterOpen) {
        final char[] passwordForExtraction = snapshotArchivePassword();
        final android.content.Context appContext = getApplicationContext();
        final File archiveSnapshot = archiveFile;
        final ArrayList<ArchiveSupport.EntryInfo> sequenceSnapshot = new ArrayList<>(sequence);
        executor.execute(() -> {
            try {
                ArchiveImageSequenceLoader.Result result = ArchiveImageSequenceLoader.loadLazy(
                        appContext,
                        archiveSnapshot,
                        sequenceSnapshot,
                        targetIndex,
                        passwordForExtraction);
                mainHandler.post(() -> handleArchiveImageSequenceResult(
                        selectedEntry,
                        useSavedPosition,
                        result,
                        finishAfterOpen));
            } finally {
                PasswordChars.clear(passwordForExtraction);
            }
        });
    }

    private void openFullyExtractedArchiveImageSequence(@Nullable ArchiveSupport.EntryInfo selectedEntry,
                                                        boolean useSavedPosition,
                                                        @NonNull List<ArchiveSupport.EntryInfo> sequence,
                                                        int targetIndex,
                                                        boolean finishAfterOpen) {
        final char[] passwordForExtraction = snapshotArchivePassword();
        final android.content.Context appContext = getApplicationContext();
        final File archiveSnapshot = archiveFile;
        final ArrayList<ArchiveSupport.EntryInfo> sequenceSnapshot = new ArrayList<>(sequence);
        executor.execute(() -> {
            try {
                ArchiveImageSequenceLoader.Result result = ArchiveImageSequenceLoader.loadFully(
                        appContext,
                        archiveSnapshot,
                        sequenceSnapshot,
                        targetIndex,
                        passwordForExtraction);
                mainHandler.post(() -> handleArchiveImageSequenceResult(
                        selectedEntry,
                        useSavedPosition,
                        result,
                        finishAfterOpen));
            } finally {
                PasswordChars.clear(passwordForExtraction);
            }
        });
    }

    private void handleArchiveImageSequenceResult(@Nullable ArchiveSupport.EntryInfo selectedEntry,
                                                  boolean useSavedPosition,
                                                  @NonNull ArchiveImageSequenceLoader.Result result,
                                                  boolean finishAfterOpen) {
        if (destroyed) return;
        hideArchivePreviewLoadingWindow();
        if (!result.selectedReady || result.imagePaths.isEmpty()) {
            if (shouldAskArchivePassword(result.extractionResult)) {
                showPasswordDialogAfterFailedResult(result.extractionResult, passwordText -> {
                    setArchivePassword(passwordText.toCharArray());
                    openArchiveImageSequence(selectedEntry, useSavedPosition);
                });
            } else {
                showArchiveEntryExtractionFailure(result.extractionResult);
            }
            return;
        }
        openExtractedImageFiles(
                result.imagePaths,
                result.displayNames,
                result.entryPaths,
                result.selectedIndex,
                finishAfterOpen);
    }

    private void showPasswordDialogAfterFailedResult(@Nullable ArchiveSupport.ExtractionResult result,
                                                     @NonNull PasswordCallback callback) {
        boolean showFailureToast = PasswordChars.hasPassword(archivePassword)
                || (result != null && result.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD);
        clearArchivePassword();
        showPasswordDialogWithOptionalFailureToast(showFailureToast, callback);
    }

    private void showPasswordDialogWithOptionalFailureToast(boolean showFailureToast,
                                                           @NonNull PasswordCallback callback) {
        if (!showFailureToast) {
            showPasswordDialog(callback);
            return;
        }
        ShortToast.show(this, R.string.archive_password_failed);
        mainHandler.postDelayed(() -> {
            if (destroyed) return;
            showPasswordDialog(callback);
        }, ShortToast.DURATION_MS + 120L);
    }

    private boolean shouldAskArchivePassword(@Nullable ArchiveSupport.ExtractionResult result) {
        return archiveFile != null
                && ArchiveSupport.canUsePassword(archiveFile)
                && isArchivePasswordFailure(result);
    }

    private boolean isArchivePasswordFailure(@Nullable ArchiveSupport.ExtractionResult result) {
        if (result == null) return false;
        if (result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED
                || result.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD) {
            return true;
        }
        return result.failure == ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE
                && PasswordChars.hasPassword(archivePassword)
                && detailLooksLikePasswordIntegrityFailure(result.detail);
    }

    private boolean detailLooksLikePasswordIntegrityFailure(@Nullable String detail) {
        if (detail == null) return false;
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("crc")
                || lower.contains("checksum")
                || lower.contains("decryption")
                || lower.contains("authentication")
                || lower.contains("mac check");
    }

    private boolean shouldRetryArchivePasswordAfterListFailure(@Nullable char[] attemptedPassword,
                                                              @NonNull Exception error) {
        return archiveFile != null
                && ArchiveSupport.canUsePassword(archiveFile)
                && PasswordChars.hasPassword(attemptedPassword)
                && looksLikeArchivePasswordFailure(error);
    }

    private boolean looksLikeArchivePasswordFailure(@NonNull Exception error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            String text = (message == null || message.trim().isEmpty())
                    ? cursor.toString()
                    : message;
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("bad password")
                    || lower.contains("wrong password")
                    || lower.contains("invalid password")
                    || lower.contains("incorrect password")
                    || lower.contains("password check failed")
                    || lower.contains("password verification failed")
                    || lower.contains("password verify failed")
                    || lower.contains("wrong passphrase")
                    || lower.contains("decryption failed")
                    || lower.contains("authentication failed")
                    || lower.contains("mac check failed")
                    || lower.contains("crc mismatch")
                    || lower.contains("failed crc verification")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void showArchiveEntryExtractionFailure(@Nullable ArchiveSupport.ExtractionResult result) {
        if (ArchiveFailureMessages.shouldShowSupportBoundaryDialog(result)) {
            showArchiveSupportBoundaryDialog(result, false);
            return;
        }
        ShortToast.show(this, ArchiveFailureMessages.entryFailureMessageRes(archiveFile, result));
    }

    private void showArchiveSupportBoundaryDialog(@Nullable ArchiveSupport.ExtractionResult result,
                                                  boolean finishOnClose) {
        if (destroyed) return;
        String message = ArchiveFailureMessages.supportBoundaryDetail(this, archiveFile, result);
        new AlertDialog.Builder(this)
                .setTitle(R.string.archive_support_boundary_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (finishOnClose) finish();
                })
                .setOnCancelListener(dialog -> {
                    if (finishOnClose) finish();
                })
                .show();
    }

    @NonNull
    private List<ArchiveSupport.EntryInfo> collectImageEntriesForSequence(@Nullable ArchiveSupport.EntryInfo selectedEntry) {
        if (selectedEntry != null && adapter != null) {
            List<ArchiveSupport.EntryInfo> previewOrdered =
                    ArchiveEntryListController.collectPreviewOrderedImageSequence(
                            adapter.snapshotEntries(),
                            selectedEntry);
            if (!previewOrdered.isEmpty()) return previewOrdered;
        }
        return ArchiveEntryListController.collectImageSequence(
                allEntries,
                currentPrefix == null ? "" : currentPrefix,
                selectedEntry,
                archiveSortMode);
    }

    private void openExtractedImageFiles(@NonNull ArrayList<String> imagePaths,
                                         @NonNull ArrayList<String> displayNames,
                                         @NonNull ArrayList<String> entryPaths,
                                         int selectedIndex,
                                         boolean finishAfterOpen) {
        if (imagePaths.isEmpty()) return;
        saveHostArchiveRecentState();
        int safeIndex = Math.max(0, Math.min(selectedIndex, imagePaths.size() - 1));
        final ArrayList<String> sequencePaths = new ArrayList<>(imagePaths);
        final ArrayList<String> sequenceNames = new ArrayList<>(displayNames);
        final ArrayList<String> sequenceEntryPaths = new ArrayList<>(entryPaths);
        final char[] passwordForHandoff = snapshotArchivePassword();
        String token = ImageSequenceHandoffStore.put(new ImageSequenceHandoffStore.ClearableProvider() {
            @Nullable
            @Override
            public ImageSequenceHandoffStore.Sequence build() {
                return new ImageSequenceHandoffStore.Sequence(
                        sequencePaths,
                        sequenceNames,
                        sequenceEntryPaths,
                        passwordForHandoff);
            }

            @Override
            public void clear() {
                PasswordChars.clear(passwordForHandoff);
            }
        });
        Intent intent = new Intent(this, ImageReaderActivity.class);
        intent.putExtra(ImageReaderActivity.EXTRA_FILE_PATH, imagePaths.get(safeIndex));
        intent.putExtra(ImageReaderActivity.EXTRA_SEQUENCE_HANDOFF_TOKEN, token);
        intent.putExtra(ImageReaderActivity.EXTRA_SOURCE_ARCHIVE_PATH, archiveFile.getAbsolutePath());
        intent.putExtra(ImageReaderActivity.EXTRA_ALLOW_FILE_OPS, false);
        try {
            startActivity(intent);
            overridePendingTransition(R.anim.image_viewer_enter, R.anim.image_viewer_hold);
            if (finishAfterOpen) finish();
        } catch (RuntimeException e) {
            ImageSequenceHandoffStore.discard(token);
            ShortToast.show(this, R.string.image_open_failed);
        }
    }

    private void openExtractedFile(@NonNull File file) {
        saveHostArchiveRecentState();
        Intent intent;
        if (ArchiveSupport.isSupportedArchive(file)) {
            intent = new Intent(this, ArchiveBrowserActivity.class);
            intent.putExtra(EXTRA_ARCHIVE_PATH, file.getAbsolutePath());
        } else if (FileUtils.isPdfFile(file.getName())) {
            intent = new Intent(this, PdfReaderActivity.class);
            intent.putExtra(PdfReaderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        } else if (FileUtils.isEpubFile(file.getName()) || FileUtils.isMarkdownFile(file.getName()) || FileUtils.isWordOrHwpFile(file.getName())) {
            intent = new Intent(this, DocumentPageActivity.class);
            intent.putExtra(DocumentPageActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        } else if (FileUtils.isImageFile(file.getName())) {
            intent = new Intent(this, ImageReaderActivity.class);
            intent.putExtra(ImageReaderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
            ArrayList<String> oneImage = new ArrayList<>();
            oneImage.add(file.getAbsolutePath());
            intent.putStringArrayListExtra(ImageReaderActivity.EXTRA_FILE_PATHS, oneImage);
            ArrayList<String> oneName = new ArrayList<>();
            oneName.add(file.getName());
            intent.putStringArrayListExtra(ImageReaderActivity.EXTRA_SOURCE_DISPLAY_NAMES, oneName);
            intent.putExtra(ImageReaderActivity.EXTRA_ALLOW_FILE_OPS, false);
        } else if (FileUtils.isTextFile(file.getName()) || FileUtils.isProbablyPlainTextFile(file)) {
            intent = new Intent(this, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        } else {
            ShortToast.show(this, R.string.archive_entry_unsupported);
            return;
        }
        startActivity(intent);
        if (intent.getComponent() != null
                && ImageReaderActivity.class.getName().equals(intent.getComponent().getClassName())) {
            overridePendingTransition(R.anim.image_viewer_enter, R.anim.image_viewer_hold);
        }
    }

    private void enterArchiveFolder(@NonNull String childPrefix) {
        archiveFolderSortStack.push(new ArchiveFolderSortState(
                currentPrefix == null ? "" : currentPrefix,
                archiveSortMode));
        currentPrefix = childPrefix;
        archiveSortMode = PrefsManager.SORT_NAME_ASC;
        renderCurrentFolder();
    }

    private void goUpOneArchiveFolder() {
        if (!archiveFolderSortStack.isEmpty()) {
            ArchiveFolderSortState previous = archiveFolderSortStack.pop();
            currentPrefix = previous.prefix;
            archiveSortMode = previous.sortMode;
            renderCurrentFolder();
            return;
        }

        String p = currentPrefix == null ? "" : currentPrefix;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int slash = p.lastIndexOf('/');
        currentPrefix = slash >= 0 ? p.substring(0, slash + 1) : "";
        archiveSortMode = currentPrefix.length() == 0
                ? rootArchiveSortMode()
                : PrefsManager.SORT_NAME_ASC;
        renderCurrentFolder();
    }

    private int rootArchiveSortMode() {
        return prefs != null ? prefs.getArchiveSortMode() : PrefsManager.SORT_NAME_ASC;
    }

    private boolean isArchiveRootFolder() {
        return currentPrefix == null || currentPrefix.length() == 0;
    }

    private static final class ArchiveFolderSortState {
        @NonNull final String prefix;
        final int sortMode;

        ArchiveFolderSortState(@NonNull String prefix, int sortMode) {
            this.prefix = prefix;
            this.sortMode = sortMode;
        }
    }

    private interface PasswordCallback {
        void onPassword(@NonNull String passwordText);
    }

    private void showPasswordDialog(@NonNull PasswordCallback callback) {
        boolean dark = prefs == null || prefs.shouldUseDarkColors(this);
        int bg = prefs != null ? prefs.getMainBgColor(this) : (dark ? Color.rgb(33, 33, 33) : Color.WHITE);
        int panel = prefs != null ? prefs.getMainPanelColor(this) : (dark ? Color.rgb(48, 48, 48) : Color.rgb(245, 245, 245));
        int fg = prefs != null ? prefs.getMainTextColor(this) : (dark ? Color.rgb(245, 245, 245) : Color.rgb(32, 33, 36));
        int sub = prefs != null ? prefs.getMainSubTextColor(this) : (dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104));
        int line = prefs != null ? prefs.getMainOutlineColor(this) : (dark ? Color.rgb(92, 92, 92) : Color.rgb(210, 210, 210));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpToPx(18), dpToPx(16), dpToPx(18), dpToPx(10));
        GradientDrawable bgShape = new GradientDrawable();
        bgShape.setColor(bg);
        bgShape.setCornerRadius(dpToPx(18));
        bgShape.setStroke(Math.max(1, dpToPx(1)), line);
        box.setBackground(bgShape);

        TextView title = new TextView(this);
        title.setText(R.string.archive_password_title);
        title.setTextColor(fg);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(10));
        box.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(this);
        msg.setText(R.string.archive_password_message);
        msg.setTextColor(sub);
        msg.setGravity(Gravity.CENTER);
        msg.setTextSize(14f);
        msg.setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(10));
        box.addView(msg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(fg);
        input.setHintTextColor(sub);
        input.setHint(R.string.archive_password_hint);
        input.setSelectAllOnFocus(false);
        box.addView(makePasswordInputRow(input, fg, panel), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52)));

        final Dialog[] ref = new Dialog[1];
        LinearLayout actions = makeDialogActionRow();
        TextView cancel = makeDialogActionButton(getString(R.string.cancel), sub, panel);
        TextView ok = makeDialogActionButton(getString(R.string.ok), fg, panel);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dpToPx(44), 1f));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dpToPx(44), 1f);
        okLp.setMargins(dpToPx(8), 0, 0, 0);
        actions.addView(ok, okLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, dpToPx(12), 0, 0);
        box.addView(actions, actionsLp);

        Dialog dialog = new Dialog(this);
        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        ref[0] = dialog;
        cancel.setOnClickListener(v -> {
            dialog.dismiss();
            if (allEntries.isEmpty()) finish();
        });
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            callback.onPassword(input.getText() == null ? "" : input.getText().toString());
        });
        dialog.setOnShowListener(d -> input.requestFocus());
        dialog.show();
        if (dialog.getWindow() != null) {
            int width = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.88f), dpToPx(420));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private LinearLayout makeDialogActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private TextView makeDialogActionButton(@NonNull String label, int textColor, int panelColor) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setIncludeFontPadding(false);
        button.setPadding(dpToPx(10), 0, dpToPx(10), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panelColor);
        bg.setCornerRadius(dpToPx(12));
        button.setBackground(bg);
        return button;
    }

    private LinearLayout makePasswordInputRow(@NonNull EditText input, int iconColor, int panelColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        ImageButton toggle = new ImageButton(this);
        toggle.setImageResource(R.drawable.ic_visibility);
        toggle.setColorFilter(iconColor);
        toggle.setBackground(makePasswordToggleBackground(panelColor));
        toggle.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        toggle.setContentDescription(getString(R.string.archive_password_show));
        final boolean[] visible = {false};
        toggle.setOnClickListener(v -> {
            visible[0] = !visible[0];
            input.setTransformationMethod(visible[0]
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            toggle.setImageResource(visible[0] ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
            toggle.setColorFilter(iconColor);
            toggle.setContentDescription(getString(visible[0]
                    ? R.string.archive_password_hide
                    : R.string.archive_password_show));
            input.setSelection(input.length());
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
        toggleLp.setMargins(dpToPx(8), 0, 0, 0);
        row.addView(toggle, toggleLp);
        return row;
    }

    private GradientDrawable makePasswordToggleBackground(int panelColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(panelColor);
        bg.setCornerRadius(dpToPx(12));
        return bg;
    }

    private void addDialogRow(@NonNull LinearLayout box, @NonNull String label, int textColor, int panelColor, @NonNull Runnable action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(textColor);
        row.setTextSize(16f);
        row.setGravity(Gravity.CENTER);
        row.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(panelColor);
        rowBg.setCornerRadius(dpToPx(12));
        row.setBackground(rowBg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48));
        lp.setMargins(0, dpToPx(8), 0, 0);
        box.addView(row, lp);
        row.setOnClickListener(v -> action.run());
    }

    private void showArchivePreviewLoadingWindow() {
        hideArchivePreviewLoadingWindow();
        final LoadingWindowTheme.Colors colors = LoadingWindowTheme.main(this, prefs);
        final int fg = colors.fg;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setMinimumWidth(dpToPx(116));
        box.setMinimumHeight(dpToPx(112));
        box.setPadding(dpToPx(20), dpToPx(22), dpToPx(20), dpToPx(20));
        box.setBackground(LoadingWindowTheme.boxDrawable(this, colors));

        ProgressBar spinner = new ProgressBar(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            spinner.setIndeterminateTintList(ColorStateList.valueOf(fg));
        }
        box.addView(spinner, new LinearLayout.LayoutParams(dpToPx(54), dpToPx(54)));

        TextView label = new TextView(this);
        label.setText(R.string.loading);
        label.setTextColor(fg);
        label.setTextSize(15f);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(0, dpToPx(10), 0, 0);
        box.addView(label, labelLp);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCancelable(false);
        LoadingWindowTheme.configureCenteredDialogWindow(dialog);
        archivePreviewLoadingDialog = dialog;
        dialog.show();
        LoadingWindowTheme.configureCenteredDialogWindow(dialog);
    }

    private void hideArchivePreviewLoadingWindow() {
        if (archivePreviewLoadingDialog != null) {
            try {
                if (archivePreviewLoadingDialog.isShowing()) archivePreviewLoadingDialog.dismiss();
            } catch (Exception ignored) {}
            archivePreviewLoadingDialog = null;
        }
    }

    private boolean isArchiveLightColor(int color) {
        return UiColorUtils.isLightColor(color);
    }

    private int blendArchiveColors(int base, int overlay, float amount) {
        return UiColorUtils.blendColors(base, overlay, amount);
    }

    private void showLoading(boolean show, @Nullable String message) {
        if (show) showArchivePreviewLoadingWindow();
        else hideArchivePreviewLoadingWindow();
        if (emptyText == null) return;

        // Keep archive preview/loading feedback in the centered loading window.
        // A separate inline spinner changes the list height and leaves a tiny
        // loading indicator visible beside the normal archive loading UI.
        int visibleCount = adapter != null ? adapter.getItemCount() : 0;
        if (show && message != null && visibleCount == 0) {
            emptyText.setVisibility(View.GONE);
        } else if (show || visibleCount > 0) {
            emptyText.setVisibility(View.GONE);
        }
    }

    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            try {
                return getResources().getDimensionPixelSize(resId);
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private final class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.Holder> {
        private final List<ArchiveSupport.EntryInfo> entries = new ArrayList<>();
        private final int fg;
        private final int sub;
        private final int pressedColor;

        EntryAdapter(int fg, int sub, int pressedColor) {
            this.fg = fg;
            this.sub = sub;
            this.pressedColor = pressedColor;
        }

        void setEntries(@NonNull List<ArchiveSupport.EntryInfo> next) {
            entries.clear();
            entries.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        List<ArchiveSupport.EntryInfo> snapshotEntries() {
            return new ArrayList<>(entries);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
            row.setMinimumHeight(dpToPx(62));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(makeArchiveRowBackground(pressedColor));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(entries.get(position));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            private final ImageView icon;
            private final TextView name;
            private final TextView info;

            Holder(@NonNull View itemView) {
                super(itemView);
                LinearLayout row = (LinearLayout) itemView;
                icon = new ImageView(itemView.getContext());
                row.addView(icon, new LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)));

                LinearLayout texts = new LinearLayout(itemView.getContext());
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dpToPx(14), 0, 0, 0);
                row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                name = new TextView(itemView.getContext());
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                name.setTextColor(fg);
                name.setTextSize(16f);
                texts.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                info = new TextView(itemView.getContext());
                info.setSingleLine(true);
                info.setEllipsize(TextUtils.TruncateAt.END);
                info.setTextColor(sub);
                info.setTextSize(13f);
                texts.addView(info, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                itemView.setOnClickListener(v -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) openEntry(entries.get(pos));
                });
            }

            void bind(@NonNull ArchiveSupport.EntryInfo entry) {
                icon.setImageResource(entry.directory ? R.drawable.ic_folder : R.drawable.ic_text_file);
                icon.setColorFilter(fg);
                name.setText(entry.name());
                info.setText(entry.directory
                        ? getString(R.string.folder)
                        : String.format(Locale.getDefault(), "%s  •  %s",
                        FileUtils.getReadableFileType(entry.name()),
                        entry.size >= 0 ? FileUtils.formatFileSize(entry.size) : "-"));
            }
        }
    }
}
