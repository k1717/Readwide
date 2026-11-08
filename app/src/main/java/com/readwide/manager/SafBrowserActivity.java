package com.readwide.manager;

import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
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
import androidx.lifecycle.ViewModelProvider;

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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * URI-backed folder browser used when raw java.io.File traversal is unavailable.
 *
 * <p>MainActivity persists the selected tree permission. Navigation and file
 * actions stay within authorized trees; document opening shares UriOpenRequest
 * routing with the main browser.</p>
 */
public final class SafBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_TREE_URI = "tree_uri";
    private static final String STATE_TREE_URI = "selected_tree_uri";
    private static final String STATE_CURRENT_URI = "current_uri";
    private static final String STATE_CURRENT_NAME = "current_name";
    private static final String STATE_URI_STACK = "uri_stack";
    private static final String STATE_NAME_STACK = "name_stack";
    private static final long FILE_OPEN_DEBOUNCE_MS = 600L;

    private Uri treeUri;
    private Uri currentUri;
    private final ArrayList<String> uriStack = new ArrayList<>();
    private final ArrayList<String> nameStack = new ArrayList<>();
    private final java.util.concurrent.ThreadPoolExecutor queryExecutor = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>());
    private final java.util.concurrent.ThreadPoolExecutor documentOpenExecutor = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>());
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
    private boolean directoryQueryFailed;
    private boolean openingDocument;
    private boolean deletingDocument;
    private boolean refreshOnResume;
    private SafOperationViewModel operations;
    private MenuItem pasteItem;
    private MenuItem clearClipboardItem;
    private MenuItem cancelOperationItem;
    private MenuItem newFolderItem;
    private MenuItem retryDirectoryItem;
    @Nullable private android.app.Dialog deleteDialog;
    @Nullable private Future<?> pendingDirectoryQuery;
    @Nullable private CancellationSignal directoryQueryCancellation;
    @Nullable private Future<?> pendingDocumentOpen;
    private long lastFileOpenElapsedRealtime = Long.MIN_VALUE;

    private final ActivityResultLauncher<Uri> changeTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                applyNewTree(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saf_browser);
        prefs = PrefsManager.getInstance(this);
        operations = new ViewModelProvider(this).get(SafOperationViewModel.class);
        bindViews();

        String rawTree = getIntent() != null ? getIntent().getStringExtra(EXTRA_TREE_URI) : null;
        if (savedInstanceState != null) rawTree = savedInstanceState.getString(STATE_TREE_URI, rawTree);
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
        operations.setBrowserTree(treeUri);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                navigateUpOrFinish();
            }
        });
        operations.running.observe(this, running -> {
            deletingDocument = Boolean.TRUE.equals(running);
            updateProgressVisibility();
            updateOperationMenu();
        });
        operations.outcome.observe(this, message -> {
            if (message == null) return;
            operations.outcome.setValue(null);
            ShortToast.show(this, message);
            if (currentUri != null) loadCurrentDirectory();
        });
        deletingDocument = operations.isRunning();
        updateOperationMenu();
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
        TextView hint = findViewById(R.id.saf_browser_hint);
        hint.setTextColor(sub);

        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous);
        Drawable nav = toolbar.getNavigationIcon();
        if (nav != null) {
            nav = DrawableCompat.wrap(nav.mutate());
            DrawableCompat.setTint(nav, android.graphics.Color.WHITE);
            toolbar.setNavigationIcon(nav);
        }
        toolbar.setNavigationOnClickListener(v -> navigateUpOrFinish());
        MenuItem changeFolder = toolbar.getMenu().add(R.string.saf_choose_folder);
        retryDirectoryItem = toolbar.getMenu().add(R.string.viewer_retry).setVisible(false);
        pasteItem = toolbar.getMenu().add(R.string.saf_paste_here);
        clearClipboardItem = toolbar.getMenu().add(R.string.saf_clear_transfer);
        newFolderItem = toolbar.getMenu().add(R.string.new_folder);
        cancelOperationItem = toolbar.getMenu().add(R.string.saf_cancel_operation);
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
                chooseFolder();
                return true;
            }
            if (item == retryDirectoryItem) { retryCurrentDirectory(); return true; }
            if (item == pasteItem) { confirmPaste(); return true; }
            if (item == clearClipboardItem) { operations.clearClipboard(); updateOperationMenu(); return true; }
            if (item == newFolderItem) { showNamePrompt(null); return true; }
            if (item == cancelOperationItem) { operations.cancel(); return true; }
            return false;
        });

        adapter = new SafDocumentAdapter(new SafDocumentAdapter.Listener() {
            @Override public void onEntryClick(@NonNull SafDocumentEntry entry) {
                onEntryClicked(entry);
            }
            @Override public void onEntryLongClick(@NonNull SafDocumentEntry entry) {
                showEntryActions(entry);
            }
        });
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
            if (!SafStorageAccess.selectTree(this, newTreeUri)) {
                ShortToast.show(this, R.string.containing_folder_unavailable);
                return;
            }
            operations.setBrowserTree(newTreeUri);
            treeUri = newTreeUri;
            currentUri = rootUri;
            rootName = label;
            currentName = label;
            uriStack.clear();
            nameStack.clear();
            loadCurrentDirectory();
        } catch (Exception ignored) {
            ShortToast.show(this, R.string.containing_folder_unavailable);
        }
    }

    private void onEntryClicked(@NonNull SafDocumentEntry entry) {
        if (deletingDocument || directoryQueryInProgress) return;
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
        openDocument(entry);
    }

    private void chooseFolder() {
        if (deletingDocument) return;
        try {
            changeTreeLauncher.launch(treeUri);
        } catch (RuntimeException unavailable) {
            ShortToast.show(this, R.string.containing_folder_unavailable);
        }
    }

    private void confirmDelete(@NonNull SafDocumentEntry entry) {
        if (deletingDocument || openingDocument || directoryQueryInProgress || deleteDialog != null) return;
        if (!SafStorageAccess.hasPersistedWritePermission(this, treeUri)) {
            showConfirmation(getString(R.string.saf_read_only), getString(R.string.saf_read_only_message),
                    getString(R.string.saf_choose_folder), this::chooseFolder);
            return;
        }
        if ((entry.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) {
            ShortToast.show(this, R.string.saf_delete_unsupported);
            return;
        }
        final Uri selectedTree = treeUri;
        final Uri selectedParent = currentUri;
        final int generation = queryGeneration.get();
        String message = getString(R.string.delete_file_confirm, entry.getName()) + "\n\n"
                + getString(R.string.delete_file_confirm_detail);
        if (entry.isDirectory()) {
            message += "\n\n" + getString(R.string.delete_selected_includes_folders_warning, 1);
        }
        showConfirmation(getString(R.string.delete), message, getString(R.string.delete), () -> {
            if (generation != queryGeneration.get() || !selectedTree.equals(treeUri)
                    || !selectedParent.equals(currentUri) || deletingDocument || openingDocument) return;
            deletingDocument = true;
            updateProgressVisibility();
            android.content.Context context = getApplicationContext();
            operations.start(() -> deleteResultMessage(SafDocumentDeletion.delete(context, selectedTree, entry)),
                    false, selectedTree);
        });
    }

    private void updateOperationMenu() {
        if (operations == null || pasteItem == null) return;
        boolean busy = operations.isRunning();
        pasteItem.setVisible(operations.clipboardEntry != null).setEnabled(!busy);
        clearClipboardItem.setVisible(operations.clipboardEntry != null).setEnabled(!busy);
        newFolderItem.setEnabled(!busy);
        cancelOperationItem.setVisible(busy);
    }

    private void showEntryActions(SafDocumentEntry entry) {
        if (deletingDocument || openingDocument || directoryQueryInProgress || deleteDialog != null) return;
        DialogActionScope scope = captureDialogScope();
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(12));
        box.setBackground(rounded(prefs.getMainBgColor(this), 18));
        TextView title = confirmationButton(entry.getName());
        title.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        box.addView(title);
        addAction(box, scope, R.string.copy, () -> selectTransfer(entry, false));
        addAction(box, scope, R.string.move, () -> selectTransfer(entry, true));
        addAction(box, scope, R.string.rename, () -> showNamePrompt(entry));
        addAction(box, scope, R.string.delete, () -> confirmDelete(entry));
        addAction(box, scope, R.string.cancel, () -> { });
        showActionDialog(box, scope);
    }

    private void addAction(android.widget.LinearLayout box, DialogActionScope scope, int label, Runnable action) {
        TextView button = confirmationButton(getString(label));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        box.addView(button, lp);
        button.setOnClickListener(v -> runDialogAction(scope, button, action));
    }

    private void showActionDialog(android.widget.LinearLayout box, DialogActionScope scope) {
        if (isFinishing() || isDestroyed()) return;
        int width = Math.min(dp(380), getResources().getDisplayMetrics().widthPixels - dp(32));
        android.app.Dialog dialog = AdaptiveDialogLayoutHelper.createStableBottomDialog(this, box, 0, false, width);
        if (dialog.getWindow() != null) dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        ownActionDialog(dialog, scope);
        dialog.show();
    }

    DialogActionScope captureDialogScope() {
        return new DialogActionScope(queryGeneration.get(), treeUri, currentUri);
    }

    void ownActionDialog(android.app.Dialog dialog, DialogActionScope scope) {
        dismissActionDialog();
        scope.dialog = dialog;
        deleteDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            scope.consumed = true;
            if (deleteDialog == dialog) deleteDialog = null;
        });
    }

    void runDialogAction(DialogActionScope scope, View button, Runnable action) {
        android.app.Dialog dialog = scope.dialog;
        if (scope.consumed || dialog == null || dialog != deleteDialog || !dialog.isShowing()
                || isFinishing() || isDestroyed() || scope.generation != queryGeneration.get()
                || !java.util.Objects.equals(scope.tree, treeUri)
                || !java.util.Objects.equals(scope.parent, currentUri)) return;
        scope.consumed = true;
        button.setEnabled(false);
        deleteDialog = null;
        dialog.dismiss();
        action.run();
    }

    void dismissActionDialog() {
        android.app.Dialog dialog = deleteDialog;
        deleteDialog = null;
        if (dialog != null) dialog.dismiss();
    }

    static final class DialogActionScope {
        final int generation;
        final Uri tree;
        final Uri parent;
        @Nullable android.app.Dialog dialog;
        boolean consumed;

        DialogActionScope(int generation, Uri tree, Uri parent) {
            this.generation = generation;
            this.tree = tree;
            this.parent = parent;
        }
    }

    private boolean requireWriteAccess() {
        if (SafStorageAccess.hasPersistedWritePermission(this, treeUri)) return true;
        showConfirmation(getString(R.string.saf_read_only), getString(R.string.saf_read_only_message),
                getString(R.string.saf_choose_folder), this::chooseFolder);
        return false;
    }

    private void selectTransfer(SafDocumentEntry entry, boolean move) {
        if (deletingDocument || (move && !requireWriteAccess())) return;
        if (move && (entry.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0
                && (entry.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_MOVE) == 0) {
            ShortToast.show(this, R.string.saf_operation_unsupported);
            return;
        }
        operations.select(treeUri, currentUri, entry, move);
        updateOperationMenu();
        ShortToast.show(this, R.string.saf_transfer_selected);
    }

    private void confirmPaste() {
        if (deletingDocument || operations.clipboardEntry == null || !requireWriteAccess()) return;
        final Uri sourceTree = operations.clipboardTree;
        final Uri sourceParent = operations.clipboardParent;
        final SafDocumentEntry source = operations.clipboardEntry;
        final boolean move = operations.clipboardMove;
        final Uri destinationTree = treeUri;
        final Uri destinationParent = currentUri;
        final int generation = queryGeneration.get();
        showConfirmation(getString(move ? R.string.move : R.string.copy),
                getString(R.string.saf_transfer_confirm, source.getName(), currentName),
                getString(R.string.saf_paste_here), () -> {
                    if (generation != queryGeneration.get() || operations.isRunning()) return;
                    android.content.Context context = getApplicationContext();
                    operations.start(() -> operationMessage(SafDocumentOperations.transfer(context,
                            sourceTree, sourceParent, source, destinationTree, destinationParent, move)),
                            true, sourceTree, destinationTree);
                });
    }

    private void showNamePrompt(@Nullable SafDocumentEntry entry) {
        if (deletingDocument || openingDocument || !requireWriteAccess()) return;
        if (entry != null && (entry.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) == 0) {
            ShortToast.show(this, R.string.saf_operation_unsupported);
            return;
        }
        final Uri selectedTree = treeUri;
        final Uri selectedParent = currentUri;
        final int generation = queryGeneration.get();
        DialogActionScope scope = captureDialogScope();
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(12));
        box.setBackground(rounded(prefs.getMainBgColor(this), 18));
        TextView title = confirmationButton(getString(entry == null ? R.string.new_folder : R.string.rename));
        title.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        box.addView(title);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setTextColor(prefs.getMainTextColor(this));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(rounded(prefs.getMainPanelColor(this), 12));
        if (entry != null) { input.setText(entry.getName()); input.selectAll(); }
        box.addView(input, new android.widget.LinearLayout.LayoutParams(-1, dp(44)));
        addAction(box, scope, entry == null ? R.string.create : R.string.rename, () -> {
            String name = input.getText().toString().trim();
            if (!SafDocumentOperations.validName(name)) { ShortToast.show(this, R.string.saf_invalid_name); return; }
            if (generation != queryGeneration.get() || operations.isRunning()) return;
            android.content.Context context = getApplicationContext();
            operations.start(() -> operationMessage(entry == null
                    ? SafDocumentOperations.createFolder(context, selectedTree, selectedParent, name)
                    : SafDocumentOperations.rename(context, selectedTree, selectedParent, entry, name)), false, selectedTree);
        });
        addAction(box, scope, R.string.cancel, () -> { });
        showActionDialog(box, scope);
    }

    private static int operationMessage(SafDocumentOperations.Result result) {
        switch (result) {
            case DONE: return R.string.saf_operation_done;
            case SOURCE_REMAINS: return R.string.saf_source_remaining;
            case DENIED: return R.string.saf_read_only_message;
            case CHANGED: return R.string.saf_delete_changed;
            case CONFLICT: return R.string.saf_transfer_conflict;
            case CLEANUP_REQUIRED: return R.string.saf_cleanup_required;
            default: return R.string.file_operation_failed;
        }
    }

    private static int deleteResultMessage(SafDocumentDeletion.Result result) {
        switch (result) {
            case DELETED: return R.string.deleted;
            case NEEDS_PERMISSION: return R.string.saf_read_only_message;
            case UNSUPPORTED: return R.string.saf_delete_unsupported;
            case CHANGED: return R.string.saf_delete_changed;
            case UNCONFIRMED: return R.string.saf_delete_unconfirmed;
            default: return R.string.delete_failed;
        }
    }

    private void showConfirmation(String title, String message, String action, Runnable confirmed) {
        if (isFinishing() || isDestroyed()) return;
        DialogActionScope scope = captureDialogScope();
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackground(rounded(prefs.getMainBgColor(this), 18));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(21);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setTextColor(prefs.getMainTextColor(this));
        heading.setGravity(android.view.Gravity.CENTER);
        box.addView(heading);
        TextView detail = new TextView(this);
        detail.setText(message);
        detail.setTextSize(14);
        detail.setTextColor(prefs.getMainSubTextColor(this));
        detail.setPadding(0, dp(12), 0, dp(16));
        box.addView(detail);
        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        box.addView(buttons);
        TextView cancel = confirmationButton(getString(R.string.cancel));
        TextView accept = confirmationButton(action);
        android.widget.LinearLayout.LayoutParams left = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
        left.setMarginEnd(dp(8));
        buttons.addView(cancel, left);
        buttons.addView(accept, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        int width = Math.min(dp(380), getResources().getDisplayMetrics().widthPixels - dp(32));
        android.app.Dialog dialog = AdaptiveDialogLayoutHelper.createStableBottomDialog(
                this, box, 0, false, width);
        if (dialog.getWindow() != null) dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        ownActionDialog(dialog, scope);
        cancel.setOnClickListener(v -> dialog.dismiss());
        accept.setOnClickListener(v -> runDialogAction(scope, accept, confirmed));
        dialog.show();
    }

    private TextView confirmationButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(prefs.getMainTextColor(this));
        button.setGravity(android.view.Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(rounded(prefs.getMainPanelColor(this), 12));
        button.setFocusable(true);
        return button;
    }

    private Drawable rounded(int color, int radius) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        background.setStroke(Math.max(1, dp(1)), prefs.getMainOutlineColor(this));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openDocument(@NonNull SafDocumentEntry entry) {
        openingDocument = true;
        updateProgressVisibility();
        final int browseGeneration = queryGeneration.get();
        final int openGeneration = documentOpenGeneration.incrementAndGet();
        final android.content.Context context = getApplicationContext();
        try {
            pendingDocumentOpen = com.readwide.manager.util.TaskSubmission.submit(documentOpenExecutor, () -> {
                UriOpenRequest request = null;
                try {
                    request = UriOpenRequest.create(context, entry.getUri());
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError unavailable) {
                }
                final UriOpenRequest result = request;
                runOnUiThread(() -> completeDocumentOpen(openGeneration, browseGeneration, result));
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError rejected) {
            completeDocumentOpen(openGeneration, browseGeneration, null);
        }
    }

    void completeDocumentOpen(int openGeneration, int browseGeneration, @Nullable UriOpenRequest request) {
        if (isFinishing() || isDestroyed() || openGeneration != documentOpenGeneration.get()) return;
        pendingDocumentOpen = null;
        openingDocument = false;
        updateProgressVisibility();
        if (browseGeneration != queryGeneration.get()) return;
        if (request == null) {
            lastFileOpenElapsedRealtime = Long.MIN_VALUE;
            ShortToast.show(this, R.string.containing_folder_unavailable);
            return;
        }
        try {
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
        final android.content.Context context = getApplicationContext();
        try {
            pendingDocumentOpen = com.readwide.manager.util.TaskSubmission.submit(documentOpenExecutor, () -> {
                File result = resolveArchiveCopy(() ->
                        FileUtils.copyUriToLocal(context, entry.getUri(), entry.getName()));
                runOnUiThread(() -> completeArchiveOpen(openGeneration, browseGeneration, result));
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError rejected) {
            completeArchiveOpen(openGeneration, browseGeneration, null);
        }
    }

    static File resolveArchiveCopy(java.util.concurrent.Callable<File> copy) {
        try {
            return copy.call();
        } catch (Exception | OutOfMemoryError | StackOverflowError unavailable) {
            return null;
        }
    }

    void completeArchiveOpen(int openGeneration, int browseGeneration, @Nullable File result) {
        if (isFinishing() || isDestroyed() || openGeneration != documentOpenGeneration.get()) return;
        pendingDocumentOpen = null;
        openingDocument = false;
        updateProgressVisibility();
        if (browseGeneration != queryGeneration.get()) return;
        if (result == null || !result.exists() || !result.isFile()) {
            lastFileOpenElapsedRealtime = Long.MIN_VALUE;
            ShortToast.show(this, R.string.archive_open_failed);
            return;
        }
        android.content.Intent intent = new android.content.Intent(this, ArchiveBrowserActivity.class);
        intent.putExtra(ArchiveBrowserActivity.EXTRA_ARCHIVE_PATH, result.getAbsolutePath());
        try {
            startActivity(intent);
        } catch (RuntimeException unavailable) {
            lastFileOpenElapsedRealtime = Long.MIN_VALUE;
            ShortToast.show(this, R.string.archive_open_failed);
        }
    }

    private void navigateUpOrFinish() {
        if (deletingDocument) {
            ShortToast.show(this, R.string.file_deleting);
            return;
        }
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
        dismissActionDialog();
        cancelPendingDocumentOpenForNavigation();
        cancelPendingDirectoryQuery();
        final int generation = queryGeneration.get();
        directoryQueryInProgress = true;
        directoryQueryFailed = false;
        updateProgressVisibility();
        emptyView.setVisibility(View.GONE);
        adapter.setEntries(Collections.emptyList());
        updatePath();

        final Uri requested = currentUri;
        final Uri requestedTree = treeUri;
        final boolean showHidden = prefs != null && prefs.getShowHiddenFiles();
        final int sortMode = prefs != null ? prefs.getSortMode() : PrefsManager.SORT_NAME_ASC;
        try {
            final CancellationSignal cancellation = new CancellationSignal();
            directoryQueryCancellation = cancellation;
            pendingDirectoryQuery = com.readwide.manager.util.TaskSubmission.submit(queryExecutor, () -> {
                Thread.interrupted();
                if (cancellation.isCanceled() || generation != queryGeneration.get()) return;
                QueryResult result = resolveDirectoryQuery(() ->
                        queryChildren(getContentResolver(), requestedTree, requested,
                                showHidden, sortMode, cancellation));
                if (cancellation.isCanceled() || generation != queryGeneration.get()) return;
                runOnUiThread(() -> completeDirectoryQuery(generation, result));
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError rejected) {
            cancelPendingDirectoryQuery();
            showDirectoryResult(QueryResult.error());
        }
    }

    void completeDirectoryQuery(int generation, @NonNull QueryResult result) {
        if (isFinishing() || isDestroyed() || generation != queryGeneration.get()
                || !directoryQueryInProgress) return;
        pendingDirectoryQuery = null;
        directoryQueryCancellation = null;
        directoryQueryInProgress = false;
        showDirectoryResult(result);
    }

    private void showDirectoryResult(@NonNull QueryResult result) {
        directoryQueryFailed = result.error;
        updateProgressVisibility();
        if (result.error) {
            emptyView.setText(R.string.containing_folder_unavailable);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        adapter.setEntries(result.entries);
        emptyView.setText(R.string.no_text_files_in_directory);
        emptyView.setVisibility(result.entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    void retryCurrentDirectory() {
        if (!directoryQueryFailed || directoryQueryInProgress || deletingDocument || openingDocument
                || currentUri == null || isFinishing() || isDestroyed()) return;
        loadCurrentDirectory();
    }

    void cancelPendingDirectoryQuery() {
        queryGeneration.incrementAndGet();
        Future<?> pending = pendingDirectoryQuery;
        CancellationSignal cancellation = directoryQueryCancellation;
        pendingDirectoryQuery = null;
        directoryQueryCancellation = null;
        directoryQueryInProgress = false;
        if (pending != null) pending.cancel(true);
        queryExecutor.getQueue().clear();
        if (cancellation != null) {
            try {
                cancellation.cancel();
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
                android.util.Log.w("ReadwideStorage", "Folder query cancellation failed", failure);
            }
        }
        updateProgressVisibility();
    }

    private void updateProgressVisibility() {
        if (retryDirectoryItem != null) {
            retryDirectoryItem.setVisible(directoryQueryFailed)
                    .setEnabled(!directoryQueryInProgress && !openingDocument && !deletingDocument);
        }
        if (progressView == null) return;
        progressView.setVisibility(
                directoryQueryInProgress || openingDocument || deletingDocument ? View.VISIBLE : View.GONE);
    }

    static QueryResult resolveDirectoryQuery(java.util.concurrent.Callable<QueryResult> query) {
        try {
            QueryResult result = query.call();
            return result != null ? result : QueryResult.error();
        } catch (Exception | OutOfMemoryError | StackOverflowError unavailable) {
            return QueryResult.error();
        }
    }

    private void cancelPendingDocumentOpenForNavigation() {
        documentOpenGeneration.incrementAndGet();
        Future<?> pending = pendingDocumentOpen;
        pendingDocumentOpen = null;
        if (pending != null) pending.cancel(true);
        documentOpenExecutor.purge();
        openingDocument = false;
        lastFileOpenElapsedRealtime = Long.MIN_VALUE;
        updateProgressVisibility();
    }

    @NonNull
    static QueryResult queryChildren(@NonNull android.content.ContentResolver resolver,
                                     @NonNull Uri requestedTree, @NonNull Uri directoryUri,
                                     boolean showHiddenFiles, int sortMode,
                                     @NonNull CancellationSignal cancellation) {
        ArrayList<SafDocumentEntry> entries = new ArrayList<>();
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS
        };
        try {
            cancellation.throwIfCanceled();
            String parentId = DocumentsContract.getDocumentId(directoryUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(requestedTree, parentId);
            try (Cursor cursor = resolver.query(
                    childrenUri, projection, null, null, null, cancellation)) {
                cancellation.throwIfCanceled();
                if (!SafDocumentDeletion.isCompleteResult(cursor)) return QueryResult.error();
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
                int flagsColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
                if (idColumn < 0) return QueryResult.error();
                while (true) {
                    cancellation.throwIfCanceled();
                    if (!cursor.moveToNext()) break;
                    cancellation.throwIfCanceled();
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
                            ? -1L : cursor.getLong(sizeColumn);
                    long modified = modifiedColumn < 0 || cursor.isNull(modifiedColumn)
                            ? 0L : cursor.getLong(modifiedColumn);
                    int flags = flagsColumn < 0 || cursor.isNull(flagsColumn) ? 0 : cursor.getInt(flagsColumn);
                    boolean directory =
                            DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    if (!directory && !FileUtils.isVisibleInAllFilesFilter(name)) continue;
                    Uri childUri =
                            DocumentsContract.buildDocumentUriUsingTree(requestedTree, documentId);
                    entries.add(new SafDocumentEntry(
                            childUri, documentId, name, mime, directory, size, modified, flags));
                }
                if (!SafDocumentDeletion.isCompleteResult(cursor)) return QueryResult.error();
            }
            cancellation.throwIfCanceled();
            sortEntries(entries, sortMode);
            cancellation.throwIfCanceled();
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
        if (treeUri != null) outState.putString(STATE_TREE_URI, treeUri.toString());
        if (currentUri != null) outState.putString(STATE_CURRENT_URI, currentUri.toString());
        if (currentName != null) outState.putString(STATE_CURRENT_NAME, currentName);
        outState.putStringArrayList(STATE_URI_STACK, new ArrayList<>(uriStack));
        outState.putStringArrayList(STATE_NAME_STACK, new ArrayList<>(nameStack));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (refreshOnResume && currentUri != null && !deletingDocument) loadCurrentDirectory();
        refreshOnResume = false;
    }

    @Override
    protected void onPause() {
        // Leaving the browser abandons its pending selection, including a slow
        // provider that completes after Settings, the picker, or Home is shown.
        cancelPendingDocumentOpenForNavigation();
        cancelPendingDirectoryQuery();
        refreshOnResume = true;
        dismissActionDialog();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissActionDialog();
        cancelPendingDirectoryQuery();
        documentOpenGeneration.incrementAndGet();
        Future<?> pending = pendingDocumentOpen;
        pendingDocumentOpen = null;
        if (pending != null) pending.cancel(true);
        queryExecutor.shutdownNow();
        documentOpenExecutor.shutdownNow();
        super.onDestroy();
    }

    static final class QueryResult {
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
