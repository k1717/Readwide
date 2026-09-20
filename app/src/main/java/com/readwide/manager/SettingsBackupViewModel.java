package com.readwide.manager;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.readwide.manager.util.BookmarkManager;
import com.readwide.manager.util.FileUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One backup operation at a time, with rotation-safe results and no Activity references. */
public final class SettingsBackupViewModel extends ViewModel {
    enum Phase { IDLE, WORKING, CONFIRM, SUCCESS, FAILURE }
    enum Operation { READ, EXPORT, MERGE, REPLACE }

    static final class State {
        final Phase phase;
        final Operation operation;
        final String text;

        State(Phase phase, Operation operation, String text) {
            this.phase = phase; this.operation = operation; this.text = text;
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "readwide-backup");
        thread.setDaemon(true);
        return thread;
    });
    // Separate Settings instances must not interleave two imports/exports.
    private static final AtomicBoolean BACKUP_RUNNING = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> state = new MutableLiveData<>(idle());
    private final Executor worker;
    private volatile boolean cleared;

    public SettingsBackupViewModel() { this(WORKER); }
    SettingsBackupViewModel(Executor worker) { this.worker = worker; }

    LiveData<State> state() { return state; }

    boolean isWorking() {
        State current = state.getValue();
        return current != null && current.phase == Phase.WORKING;
    }

    void readBackup(Context context, Uri uri) {
        Context app = context.getApplicationContext();
        submit(Operation.READ, () -> new State(Phase.CONFIRM, Operation.READ,
                FileUtils.readTextFromUri(app, uri)));
    }

    void exportBackup(Context context, Uri uri) {
        Context app = context.getApplicationContext();
        submit(Operation.EXPORT, () -> {
            String json = BookmarkManager.getInstance(app).exportAll();
            try (OutputStream out = app.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Could not open the backup destination");
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            // A provider can report failure on close: publish success only afterwards.
            return new State(Phase.SUCCESS, Operation.EXPORT, null);
        });
    }

    void importBackup(Context context, boolean merge) {
        State current = state.getValue();
        if (current == null || current.phase != Phase.CONFIRM) return;
        String json = current.text;
        Context app = context.getApplicationContext();
        Operation operation = merge ? Operation.MERGE : Operation.REPLACE;
        submit(operation, () -> {
            BookmarkManager.getInstance(app).importAll(json, merge);
            return new State(Phase.SUCCESS, operation, null);
        });
    }

    void consumeResult() {
        if (!isWorking()) state.setValue(idle());
    }

    interface Work { State run() throws Exception; }

    void submit(Operation operation, Work work) {
        if (cleared || isWorking()) return;
        if (!BACKUP_RUNNING.compareAndSet(false, true)) {
            state.setValue(new State(Phase.FAILURE, operation, "Another backup operation is still running"));
            return;
        }
        state.setValue(new State(Phase.WORKING, operation, null));
        try {
            worker.execute(() -> {
                State result;
                try {
                    result = work.run();
                } catch (Exception failure) {
                    result = failure(operation, failure);
                } finally {
                    BACKUP_RUNNING.set(false);
                }
                State completed = result;
                main.post(() -> { if (!cleared) state.setValue(completed); });
            });
        } catch (RuntimeException rejected) {
            BACKUP_RUNNING.set(false);
            state.setValue(failure(operation, rejected));
        }
    }

    private static State failure(Operation operation, Exception failure) {
        String message = failure.getMessage();
        return new State(Phase.FAILURE, operation,
                message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message);
    }

    private static State idle() { return new State(Phase.IDLE, null, null); }

    @Override protected void onCleared() {
        cleared = true;
        state.setValue(idle());
        // Finishing Settings must not interrupt an import between file commits.
        // Rotation retains this model; a genuinely closed screen ignores late results.
    }
}
