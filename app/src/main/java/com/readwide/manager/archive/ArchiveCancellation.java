package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Cancellation is terminal, not a reason to retry through a different decoder. */
public final class ArchiveCancellation {
    private ArchiveCancellation() {}

    static void checkpoint(FileOperationProgress progress) throws InterruptedIOException {
        // Let the token observe an interrupt before throwing. UI/batch callers
        // inspect the token after an exception has been converted to a result.
        if ((progress != null && !progress.checkpoint()) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Archive extraction cancelled");
        }
    }

    /** Preserve cancellation when an exception leaves the throwing API as a result. */
    public static boolean restoreIfCancelled(Throwable failure, FileOperationProgress progress) {
        try {
            rethrowIfCancelled(failure, progress);
            return false;
        } catch (InterruptedIOException cancelled) {
            Thread.currentThread().interrupt();
            // checkpoint records cancellation without invoking a failing listener.
            if (progress != null) progress.checkpoint();
            return true;
        }
    }

    public static void rethrowIfCancelled(Throwable failure, FileOperationProgress progress)
            throws InterruptedIOException {
        // JNI and codec adapters wrap callback failures; a cleared thread flag
        // must not turn the original cancellation into permission to retry.
        // try-with-resources can suppress a cancellation raised during cleanup.
        // That is still terminal, even if an earlier codec error is primary.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof InterruptedIOException) throw (InterruptedIOException) current;
            if (current.getCause() != null) pending.addFirst(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) pending.addLast(suppressed);
        }
        if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled())) {
            InterruptedIOException cancelled = new InterruptedIOException("Archive extraction cancelled");
            cancelled.initCause(failure);
            throw cancelled;
        }
    }
}
