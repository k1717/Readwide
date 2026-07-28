package com.readwide.manager;

import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.adapter.SafDocumentAdapter;
import com.readwide.manager.model.SafDocumentEntry;
import com.readwide.manager.util.EdgeToEdgeUtil;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * URI-backed folder browser used when raw java.io.File traversal is unavailable.
 *
 * <p>The selected tree permission is persisted by MainActivity. This activity
 * queries only that tree, supports provider-independent folder navigation, and
 * returns a selected document URI to MainActivity's existing openFileFromUri()
 * pipeline.</p>
 */
public final class SafBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_TREE_URI = "tree_uri";
    private static final String STATE_CURRENT_URI = "current_uri";
    private static final String STATE_CURRENT_NAME = "current_name";
    private static final String STATE_URI_STACK = "uri_stack";
    private static final String STATE_NAME_STACK = "name_stack";
    private static final long FILE_OPEN_DEBOUNCE_MS = 600L;

    private Uri treeUri;
    private Uri currentUri;
    private final ArrayList<String> uriStack = new ArrayList<>();
    private final ArrayList<String> nameStack = new ArrayList<>();
    private final ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService documentOpenExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger queryGeneration = new AtomicInteger();
    private final AtomicInteger documentOpenGeneration = new AtomicInteger();

    private Toolbar toolbar;
    private TextView pathView;
    private TextView emptyView;
    private ProgressBar progressView;
    private SafDocumentAdapter adapter;
    private PrefsManager prefs;
    private String rootName;
    private String currentName;
    private boolean directoryQueryInProgress;
    private boolean openingDocument;
    @Nullable private Future<?> pendingDocumentOpen;
    private long lastFileOpenElapsedRealtime = Long.MIN_VALUE;

    private final ActivityResultLauncher<Uri> changeTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                if (!SafStorageAccess.takePersistableTreeGrant(this, uri)) {
                    ShortToast.show(this, R.string.containing_folder_unavailable);
                    return;
                }
                SafStorageAccess.releasePreviousGrantIfDifferent(this, uri);
                applyNewTree(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saf_browser);
        prefs = PrefsManager.getInstance(this);
        bindViews();

        String rawTree = getIntent() != null ? getIntent().getStringExtra(EXTRA_TREE_URI) : null;
        if (rawTree == null || rawTree.trim().isEmpty()) {
            finishUnavailable();
            return;
        }
        try {
            treeUri = Uri.parse(rawTree);
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            rootName = SafStorageAccess.queryDisplayName(this, rootUri);

            if (savedInstanceState != null) {
                String savedCurrent = savedInstanceState.getString(STATE_CURRENT_URI);
                currentUri = savedCurrent != null ? Uri.parse(savedCurrent) : rootUri;
                currentName = savedInstanceState.getString(STATE_CURRENT_NAME, rootName);
                ArrayList<String> savedUris = savedInstanceState.getStringArrayList(STATE_URI_STACK);
                ArrayList<String> savedNames = savedInstanceState.getStringArrayList(STATE_NAME_STACK);
                if (savedUris != null) uriStack.addAll(savedUris);
                if (savedNames != null) nameStack.addAll(savedNames);
            } else {
                currentUri = rootUri;
                currentName = rootName;
            }
        } catch (Exception ignored) {
            finishUnavailable();
            return;
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                navigateUpOrFinish();
            }
        });
        loadCurrentDirectory();
    }

    private void bindViews() {
        View root = findViewById(R.id.saf_browser_root);
        toolbar = findViewById(R.id.saf_browser_toolbar);
        pathView = findViewById(R.id.saf_browser_path);
        emptyView = findViewById(R.id.saf_browser_empty);
        progressView = findViewById(R.id.saf_browser_progress);
        RecyclerView list = findViewById(R.id.saf_browser_list);
        View content = findViewById(R.id.saf_browser_content);

        int bg = prefs != null ? prefs.getMainBgColor(this) : 0xfffafafa;
        int panel = prefs != null ? prefs.getMainBarColor(this) : 0xff303030;
        int sub = prefs != null ? prefs.getMainSubTextColor(this) : 0xff5f6368;
        root.setBackgroundColor(bg);
        toolbar.setBackgroundColor(panel);
        toolbar.setTitleTextColor(android.graphics.Color.WHITE);
        pathView.setTextColor(sub);
        pathView.setBackgroundColor(bg);
        emptyView.setTextColor(sub);

        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        Drawable nav = toolbar.getNavigationIcon();
        if (nav != null) {
            nav = DrawableCompat.wrap(nav.mutate());
            DrawableCompat.setTint(nav, android.graphics.Color.WHITE);
            toolbar.setNavigationIcon(nav);
        }
        toolbar.setNavigationOnClickListener(v -> navigateUpOrFinish());
        MenuItem changeFolder = toolbar.getMenu().add(R.string.open);
        changeFolder.setIcon(R.drawable.ic_open_file);
        changeFolder.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        Drawable changeIcon = changeFolder.getIcon();
        if (changeIcon != null) {
            changeIcon = DrawableCompat.wrap(changeIcon.mutate());
            DrawableCompat.setTint(changeIcon, android.graphics.Color.WHITE);
            changeFolder.setIcon(changeIcon);
        }
        toolbar.setOnMenuItemClickListener(item -> {
            if (item == changeFolder) {
                changeTreeLauncher.launch(treeUri);
                return true;
            }
            return false;
        });

        adapter = new SafDocumentAdapter(this::onEntryClicked);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        EdgeToEdgeUtil.applyStandardInsets(this, root, toolbar, content);
    }

    private void applyNewTree(@NonNull Uri newTreeUri) {
        try {
            String rootId = DocumentsContract.getTreeDocumentId(newTreeUri);
            Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(newTreeUri, rootId);
            String label = SafStorageAccess.queryDisplayName(this, rootUri);
            treeUri = newTreeUri;
            currentUri = rootUri;
            rootName = label;
            currentName = label;
            uriStack.clear();
            nameStack.clear();
            SafStorageAccess.rememberTree(this, newTreeUri);
            loadCurrentDirectory();
        } catch (Exception ignored) {
            ShortToast.show(this, R.string.containing_folder_unavailable);
        }
    }

    private void onEntryClicked(@NonNull SafDocumentEntry entry) {
        if (entry.isDirectory()) {
            uriStack.add(currentUri.toString());
            nameStack.add(currentName != null ? currentName : rootName);
            currentUri = entry.getUri();
            currentName = entry.getName();
            loadCurrentDirectory();
            return;
        }
        if (!acceptFileOpenNow()) return;
        if (FileUtils.isArchiveFile(entry.getName())) {
            openArchiveDocument(entry);
            return;
        }
        try {
            UriOpenRequest request = UriOpenRequest.create(this, entry.getUri());
            startActivity(request.intent);
        } catch (RuntimeException unavailable) {
            lastFileOpenElapsedRealtime = Long.MIN_VALUE;
            ShortToast.show(this, R.string.containing_folder_unavailable);
        }
    }

    private boolean acceptFileOpenNow() {
        if (openingDocument) return false;
        long now = SystemClock.elapsedRealtime();
        if (lastFileOpenElapsedRealtime != Long.MIN_VALUE
                && now - lastFileOpenElapsedRealtime < FILE_OPEN_DEBOUNCE_MS) {
            return false;
        }
        lastFileOpenElapsedRealtime = now;
        return true;
    }

    private void openArchiveDocument(@NonNull SafDocumentEntry entry) {
        if (openingDocument) return;
        openingDocument = true;
        updateProgressVisibility();
        final int browseGeneration = queryGeneration.get();
        final int openGeneration = documentOpenGeneration.incrementAndGet();
        try {
            pendingDocumentOpen = documentOpenExecutor.submit(() -> {
                File local = null;
                try {
                    local = FileUtils.copyUriToLocal(this, entry.getUri(), entry.getName());
                } catch (Exception ignored) {
                }
                File result = local;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (openGeneration != documentOpenGeneration.get()) return;
                    pendingDocumentOpen = null;
                    openingDocument = false;
                    updateProgressVisibility();
                    if (browseGeneration != queryGeneration.get()) return;
                    if (result == null || !result.exists() || !result.isFile()) {
                        ShortToast.show(this, R.string.archive_open_failed);
                        return;
                    }
                    android.content.Intent intent =
                            new android.content.Intent(this, ArchiveBrowserActivity.class);
                    intent.putExtra(
                            ArchiveBrowserActivity.EXTRA_ARCHIVE_PATH,
                            result.getAbsolutePath());
                    try {
                        startActivity(intent);
                    } catch (RuntimeException unavailable) {
                        ShortToast.show(this, R.string.archive_open_failed);
                    }
                });
            });
        } catch (RuntimeException rejected) {
            pendingDocumentOpen = null;
            openingDocument = false;
            updateProgressVisibility();
            ShortToast.show(this, R.string.archive_open_failed);
        }
    }

    private void navigateUpOrFinish() {
        if (uriStack.isEmpty()) {
            finish();
            return;
        }
        int last = uriStack.size() - 1;
        currentUri = Uri.parse(uriStack.remove(last));
        currentName = !nameStack.isEmpty()
                ? nameStack.remove(nameStack.size() - 1) : rootName;
        loadCurrentDirectory();
    }

    private void loadCurrentDirectory() {
        cancelPendingDocumentOpenForNavigation();
        final int generation = queryGeneration.incrementAndGet();
        directoryQueryInProgress = true;
        updateProgressVisibility();
        emptyView.setVisibility(View.GONE);
        adapter.setEntries(Collections.emptyList());
        updatePath();

        final Uri requested = currentUri;
        try {
            queryExecutor.execute(() -> {
                QueryResult result = queryChildren(requested);
                runOnUiThread(() -> {
                    if (isFinishing()
                            || isDestroyed()
                            || generation != queryGeneration.get()) {
                        return;
                    }
                    directoryQueryInProgress = false;
                    updateProgressVisibility();
                    if (result.error) {
                        emptyView.setText(R.string.containing_folder_unavailable);
                        emptyView.setVisibility(View.VISIBLE);
                        return;
                    }
                    adapter.setEntries(result.entries);
                    emptyView.setText(R.string.no_text_files_in_directory);
                    emptyView.setVisibility(
                            result.entries.isEmpty() ? View.VISIBLE : View.GONE);
                });
            });
        } catch (RuntimeException rejected) {
            directoryQueryInProgress = false;
            updateProgressVisibility();
            emptyView.setText(R.string.containing_folder_unavailable);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    private void updateProgressVisibility() {
        if (progressView == null) return;
        progressView.setVisibility(
                directoryQueryInProgress || openingDocument ? View.VISIBLE : View.GONE);
    }

    private void cancelPendingDocumentOpenForNavigation() {
        documentOpenGeneration.incrementAndGet();
        Future<?> pending = pendingDocumentOpen;
        pendingDocumentOpen = null;
        if (pending != null) pending.cancel(true);
        openingDocument = false;
        lastFileOpenElapsedRealtime = Long.MIN_VALUE;
        updateProgressVisibility();
    }

    @NonNull
    private QueryResult queryChildren(@NonNull Uri directoryUri) {
        ArrayList<SafDocumentEntry> entries = new ArrayList<>();
        boolean showHiddenFiles = prefs != null && prefs.getShowHiddenFiles();
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try {
            String parentId = DocumentsContract.getDocumentId(directoryUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            try (Cursor cursor = getContentResolver().query(
                    childrenUri, projection, null, null, null)) {
                if (cursor == null) return QueryResult.error();
                int idColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_SIZE);
                int modifiedColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (idColumn < 0) return QueryResult.error();
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(idColumn);
                    if (documentId == null || documentId.trim().isEmpty()) continue;
                    String name = nameColumn >= 0 && !cursor.isNull(nameColumn)
                            ? cursor.getString(nameColumn) : null;
                    if (name == null || name.trim().isEmpty()) {
                        name = fallbackName(documentId);
                    }
                    if (!showHiddenFiles && name.startsWith(".")) {
                        continue;
                    }
                    String mime = mimeColumn >= 0 && !cursor.isNull(mimeColumn)
                            ? cursor.getString(mimeColumn) : null;
                    if (mime == null || mime.trim().isEmpty()) {
                        mime = "application/octet-stream";
                    }
                    long size = sizeColumn < 0 || cursor.isNull(sizeColumn)
                            ? 0L : cursor.getLong(sizeColumn);
                    long modified = modifiedColumn < 0 || cursor.isNull(modifiedColumn)
                            ? 0L : cursor.getLong(modifiedColumn);
                    boolean directory =
                            DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    if (!directory && !FileUtils.isVisibleInAllFilesFilter(name)) continue;
                    Uri childUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    entries.add(new SafDocumentEntry(
                            childUri, documentId, name, mime, directory, size, modified));
                }
            }
            sortEntries(entries, prefs != null ? prefs.getSortMode() : PrefsManager.SORT_NAME_ASC);
            return QueryResult.success(entries);
        } catch (Exception ignored) {
            return QueryResult.error();
        }
    }

    private static String fallbackName(@NonNull String documentId) {
        int slash = Math.max(documentId.lastIndexOf('/'), documentId.lastIndexOf('\\'));
        int colon = documentId.lastIndexOf(':');
        int cut = Math.max(slash, colon);
        String name = cut >= 0 && cut < documentId.length() - 1
                ? documentId.substring(cut + 1) : documentId;
        return name.trim().isEmpty() ? "document" : name;
    }

    private static void sortEntries(@NonNull List<SafDocumentEntry> entries, int mode) {
        Comparator<SafDocumentEntry> withinType;
        switch (mode) {
            case PrefsManager.SORT_NAME_DESC:
                withinType = (a, b) -> compareName(b, a);
                break;
            case PrefsManager.SORT_DATE_NEW:
                withinType = (a, b) -> compareLongDesc(
                        a.getLastModified(), b.getLastModified(), a, b);
                break;
            case PrefsManager.SORT_DATE_OLD:
                withinType = (a, b) -> compareLongAsc(
                        a.getLastModified(), b.getLastModified(), a, b);
                break;
            case PrefsManager.SORT_SIZE_LARGE:
                withinType = (a, b) -> compareLongDesc(a.getSize(), b.getSize(), a, b);
                break;
            case PrefsManager.SORT_SIZE_SMALL:
                withinType = (a, b) -> compareLongAsc(a.getSize(), b.getSize(), a, b);
                break;
            case PrefsManager.SORT_TYPE:
                withinType = (a, b) -> {
                    int type = extension(a.getName()).compareTo(extension(b.getName()));
                    return type != 0 ? type : compareName(a, b);
                };
                break;
            case PrefsManager.SORT_NAME_ASC:
            default:
                withinType = SafBrowserActivity::compareName;
                break;
        }
        entries.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return withinType.compare(a, b);
        });
    }

    private static int compareName(SafDocumentEntry a, SafDocumentEntry b) {
        int folded = a.getName().compareToIgnoreCase(b.getName());
        return folded != 0 ? folded : a.getName().compareTo(b.getName());
    }

    private static int compareLongDesc(long a, long b,
                                       SafDocumentEntry entryA, SafDocumentEntry entryB) {
        int value = Long.compare(b, a);
        return value != 0 ? value : compareName(entryA, entryB);
    }

    private static int compareLongAsc(long a, long b,
                                      SafDocumentEntry entryA, SafDocumentEntry entryB) {
        int value = Long.compare(a, b);
        return value != 0 ? value : compareName(entryA, entryB);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private void updatePath() {
        String current = currentName != null ? currentName : rootName;
        toolbar.setTitle(current);
        StringBuilder path = new StringBuilder(rootName != null ? rootName : "");
        for (String name : nameStack) {
            if (name == null || name.isEmpty() || name.equals(rootName)) continue;
            if (path.length() > 0) path.append(" / ");
            path.append(name);
        }
        if (!current.equals(rootName)
                && (nameStack.isEmpty() || !current.equals(nameStack.get(nameStack.size() - 1)))) {
            if (path.length() > 0) path.append(" / ");
            path.append(current);
        }
        pathView.setText(path);
    }

    private void finishUnavailable() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentUri != null) outState.putString(STATE_CURRENT_URI, currentUri.toString());
        if (currentName != null) outState.putString(STATE_CURRENT_NAME, currentName);
        outState.putStringArrayList(STATE_URI_STACK, new ArrayList<>(uriStack));
        outState.putStringArrayList(STATE_NAME_STACK, new ArrayList<>(nameStack));
    }

    @Override
    protected void onDestroy() {
        queryGeneration.incrementAndGet();
        documentOpenGeneration.incrementAndGet();
        Future<?> pending = pendingDocumentOpen;
        pendingDocumentOpen = null;
        if (pending != null) pending.cancel(true);
        queryExecutor.shutdownNow();
        documentOpenExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class QueryResult {
        final List<SafDocumentEntry> entries;
        final boolean error;

        private QueryResult(List<SafDocumentEntry> entries, boolean error) {
            this.entries = entries;
            this.error = error;
        }

        static QueryResult success(List<SafDocumentEntry> entries) {
            return new QueryResult(entries, false);
        }

        static QueryResult error() {
            return new QueryResult(Collections.emptyList(), true);
        }
    }
}
