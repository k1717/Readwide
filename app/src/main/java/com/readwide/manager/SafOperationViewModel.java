package com.readwide.manager;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.readwide.manager.model.SafDocumentEntry;
import com.readwide.manager.util.TaskSubmission;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Owns provider mutations through configuration changes without retaining a screen. */
public final class SafOperationViewModel extends AndroidViewModel {
    final MutableLiveData<Boolean> running = new MutableLiveData<>(false);
    final MutableLiveData<Integer> outcome = new MutableLiveData<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Future<?> pending;
    private volatile boolean cleared;
    private volatile boolean cancelled;
    private volatile Thread activeWorker;
    private Uri browserTree;
    private long clipboardGeneration;
    Uri clipboardTree;
    Uri clipboardParent;
    SafDocumentEntry clipboardEntry;
    boolean clipboardMove;

    public SafOperationViewModel(@NonNull Application application) { super(application); }

    boolean isRunning() { return Boolean.TRUE.equals(running.getValue()); }

    void setBrowserTree(Uri tree) {
        if (cleared || java.util.Objects.equals(browserTree, tree)) return;
        Uri previous = browserTree;
        SafStorageAccess.pinTree(tree);
        browserTree = tree;
        if (previous != null) SafStorageAccess.unpinTree(getApplication(), previous);
    }

    void select(Uri tree, Uri parent, SafDocumentEntry entry, boolean move) {
        clearClipboard();
        clipboardTree = tree;
        clipboardParent = parent;
        clipboardEntry = entry;
        clipboardMove = move;
        SafStorageAccess.pinTree(tree);
    }

    void clearClipboard() {
        clipboardGeneration++;
        Uri previous = clipboardTree;
        clipboardTree = null;
        clipboardParent = null;
        clipboardEntry = null;
        if (previous != null) SafStorageAccess.unpinTree(getApplication(), previous);
    }

    void start(Callable<Integer> task, boolean consumesClipboard, Uri... operationTrees) {
        if (cleared || isRunning()) return;
        outcome.setValue(null);
        cancelled = false;
        final long selectedClipboardGeneration = clipboardGeneration;
        final android.content.Context context = getApplication();
        for (Uri tree : operationTrees) SafStorageAccess.pinTree(tree);
        running.setValue(true);
        try {
            pending = TaskSubmission.submit(worker, () -> {
                int message;
                activeWorker = Thread.currentThread();
                try {
                    if (cancelled) throw new java.io.InterruptedIOException();
                    message = task.call();
                }
                catch (Exception | OutOfMemoryError | StackOverflowError failure) {
                    message = cancelled ? R.string.file_operation_cancelled : R.string.file_operation_failed;
                }
                finally {
                    activeWorker = null;
                    for (Uri tree : operationTrees) SafStorageAccess.unpinTree(context, tree);
                }
                final int result = cancellationMessage(message, cancelled);
                main.post(() -> {
                    if (cleared) return;
                    pending = null;
                    if (consumesClipboard && selectedClipboardGeneration == clipboardGeneration
                            && consumesClipboardResult(result)) clearClipboard();
                    running.setValue(false);
                    outcome.setValue(result);
                });
            });
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError rejected) {
            for (Uri tree : operationTrees) SafStorageAccess.unpinTree(context, tree);
            running.setValue(false);
            outcome.setValue(R.string.file_operation_failed);
        }
    }

    static int cancellationMessage(int message, boolean cancelled) {
        if (cancelled && (message == R.string.file_operation_failed || message == R.string.delete_failed)) {
            return R.string.file_operation_cancelled;
        }
        return message;
    }

    static boolean consumesClipboardResult(int message) {
        // Partial or uncertain output needs a fresh selection before another paste.
        return message == R.string.saf_operation_done
                || message == R.string.saf_source_remaining
                || message == R.string.saf_cleanup_required;
    }

    void cancel() {
        cancelled = true;
        Thread thread = activeWorker;
        if (thread != null) thread.interrupt();
    }

    @Override protected void onCleared() {
        cleared = true;
        clearClipboard();
        Uri previous = browserTree;
        browserTree = null;
        if (previous != null) SafStorageAccess.unpinTree(getApplication(), previous);
        cancel();
        worker.shutdown();
    }
}
