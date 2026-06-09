package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Cooperative progress/cancel/pause token for long file operations.
 *
 * The token is UI-free. File-system code reports byte progress and checks the
 * token between chunks; Activity controllers decide how to present it.
 */
public final class FileOperationProgress {
    public interface Listener {
        void onProgress(@NonNull Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String title;
        public final String detail;
        public final String folder;
        public final long doneBytes;
        public final long totalBytes;
        public final boolean paused;
        public final boolean cancelled;
        public final boolean indeterminate;
        public final boolean complete;
        public final int itemIndex;
        public final int itemTotal;
        public final int folderIndex;
        public final int folderTotal;

        private Snapshot(@NonNull String title,
                         @NonNull String detail,
                         @NonNull String folder,
                         long doneBytes,
                         long totalBytes,
                         boolean paused,
                         boolean cancelled,
                         boolean indeterminate,
                         boolean complete,
                         int itemIndex,
                         int itemTotal,
                         int folderIndex,
                         int folderTotal) {
            this.title = title;
            this.detail = detail;
            this.folder = folder;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
            this.paused = paused;
            this.cancelled = cancelled;
            this.indeterminate = indeterminate;
            this.complete = complete;
            this.itemIndex = itemIndex;
            this.itemTotal = itemTotal;
            this.folderIndex = folderIndex;
            this.folderTotal = folderTotal;
        }

        public int percent() {
            if (totalBytes <= 0L) return 0;
            double pct = ((double) doneBytes * 100.0d) / (double) totalBytes;
            if (pct < 0.0d) return 0;
            if (pct > 100.0d) return 100;
            return (int) pct;
        }
    }

    private final Object lock = new Object();
    private Listener listener;
    private String title;
    private String detail = "";
    private String folder = "";
    private long doneBytes = 0L;
    private long totalBytes = -1L;
    private boolean paused = false;
    private boolean cancelled = false;
    private boolean indeterminate = true;
    private boolean complete = false;
    private int itemIndex = 0;
    private int itemTotal = 0;
    private int folderIndex = 0;
    private int folderTotal = 0;

    public FileOperationProgress(@NonNull String title, @Nullable Listener listener) {
        this.title = title;
        this.listener = listener;
        notifyListener();
    }

    public void setListener(@Nullable Listener listener) {
        synchronized (lock) {
            this.listener = listener;
        }
        notifyListener();
    }

    public void setTotalBytes(long totalBytes) {
        synchronized (lock) {
            this.totalBytes = Math.max(0L, totalBytes);
            this.doneBytes = 0L;
            this.indeterminate = this.totalBytes <= 0L;
            if (!cancelled) complete = false;
        }
        notifyListener();
    }

    public void setDetail(@Nullable String detail) {
        synchronized (lock) {
            this.detail = detail == null ? "" : detail;
        }
        notifyListener();
    }

    public void setFolder(@Nullable String folder) {
        synchronized (lock) {
            this.folder = folder == null ? "" : folder;
        }
        notifyListener();
    }

    public void setItemProgress(int index, int total) {
        synchronized (lock) {
            if (total <= 0) {
                itemIndex = 0;
                itemTotal = 0;
            } else {
                itemTotal = total;
                itemIndex = Math.max(1, Math.min(index, total));
            }
        }
        notifyListener();
    }

    public void clearItemProgress() {
        synchronized (lock) {
            itemIndex = 0;
            itemTotal = 0;
        }
        notifyListener();
    }

    public void setFolderProgress(int index, int total) {
        synchronized (lock) {
            if (total <= 0) {
                folderIndex = 0;
                folderTotal = 0;
            } else {
                folderTotal = total;
                folderIndex = Math.max(1, Math.min(index, total));
            }
        }
        notifyListener();
    }

    public void clearFolderProgress() {
        synchronized (lock) {
            folderIndex = 0;
            folderTotal = 0;
        }
        notifyListener();
    }

    public void addDoneBytes(long bytes) {
        if (bytes <= 0L) return;
        synchronized (lock) {
            doneBytes += bytes;
            if (totalBytes > 0L && doneBytes > totalBytes) doneBytes = totalBytes;
        }
        notifyListener();
    }

    public void markComplete() {
        synchronized (lock) {
            if (totalBytes > 0L) doneBytes = totalBytes;
            paused = false;
            complete = true;
        }
        notifyListener();
    }

    public boolean checkpoint() {
        synchronized (lock) {
            while (paused && !cancelled) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    break;
                }
            }
            return !cancelled;
        }
    }

    public void setPaused(boolean paused) {
        synchronized (lock) {
            if (cancelled) return;
            this.paused = paused;
            if (!paused) lock.notifyAll();
        }
        notifyListener();
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            paused = false;
            complete = true;
            lock.notifyAll();
        }
        notifyListener();
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    public boolean isComplete() {
        synchronized (lock) {
            return complete;
        }
    }

    @NonNull
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(title, detail, folder, doneBytes, totalBytes, paused, cancelled, indeterminate, complete, itemIndex, itemTotal, folderIndex, folderTotal);
        }
    }

    private void notifyListener() {
        Listener current;
        synchronized (lock) {
            current = listener;
        }
        if (current != null) current.onProgress(snapshot());
    }
}
