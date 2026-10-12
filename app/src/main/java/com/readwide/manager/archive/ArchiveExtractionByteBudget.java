package com.readwide.manager.archive;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Operation-wide accounting for bytes materialized by archive extraction.
 *
 * <p>The active budget follows the synchronous extraction call across dedicated
 * and fallback engines. Reopening the same output path replaces its previous
 * contribution, which keeps a partially written failed backend from being
 * counted twice when a fallback truncates and rewrites that file.</p>
 */
final class ArchiveExtractionByteBudget {
    private static final InheritableThreadLocal<ArchiveExtractionByteBudget> ACTIVE =
            new InheritableThreadLocal<>();

    private final long limitBytes;
    private final Map<String, Long> bytesByPath = new HashMap<>();
    private long totalBytes;

    private ArchiveExtractionByteBudget(long limitBytes) {
        this.limitBytes = Math.max(0L, limitBytes);
    }

    static Scope begin(long limitBytes) {
        ArchiveExtractionByteBudget previous = ACTIVE.get();
        if (previous != null) return new Scope(previous, false);
        ArchiveExtractionByteBudget budget = new ArchiveExtractionByteBudget(limitBytes);
        ACTIVE.set(budget);
        return new Scope(budget, true);
    }

    static OutputStream openOutputStream(File file, long standaloneLimitBytes) throws IOException {
        ArchiveCancellation.checkpoint(null); // Do not truncate an output after cancellation.
        String path = file.getCanonicalPath();
        ArchiveExtractionByteBudget budget = ACTIVE.get();
        if (budget == null) budget = new ArchiveExtractionByteBudget(standaloneLimitBytes);
        FileOutputStream raw = new FileOutputStream(file, false);
        try {
            budget.beginFile(path);
            return new BudgetedOutputStream(raw, budget, path, file.getAbsoluteFile().getParentFile());
        } catch (RuntimeException | Error failure) {
            try { raw.close(); } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    synchronized long totalBytesForTest() {
        return totalBytes;
    }

    private synchronized void beginFile(String path) {
        Long previous = bytesByPath.put(path, 0L);
        if (previous != null) totalBytes = Math.max(0L, totalBytes - previous);
    }

    private synchronized void reserve(String path, int count) throws IOException {
        if (count <= 0) return;
        if (totalBytes > limitBytes - count) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "Archive extraction exceeds the runtime byte or free-space safety limit");
        }
        long fileBytes = bytesByPath.getOrDefault(path, 0L);
        bytesByPath.put(path, fileBytes + count);
        totalBytes += count;
    }

    private synchronized void rollback(String path, int count) {
        if (count <= 0) return;
        long fileBytes = bytesByPath.getOrDefault(path, 0L);
        long rolledBack = Math.min(fileBytes, (long) count);
        bytesByPath.put(path, fileBytes - rolledBack);
        totalBytes = Math.max(0L, totalBytes - rolledBack);
    }

    static final class Scope implements AutoCloseable {
        private final ArchiveExtractionByteBudget budget;
        private final boolean installed;
        private boolean closed;

        private Scope(ArchiveExtractionByteBudget budget, boolean installed) {
            this.budget = budget;
            this.installed = installed;
        }

        ArchiveExtractionByteBudget budgetForTest() {
            return budget;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!installed) return;
            ACTIVE.remove();
        }
    }

    private static final class BudgetedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ArchiveExtractionByteBudget budget;
        private final String path;
        private final File parent;
        private long spaceAllowance;
        private Throwable failure;
        private boolean closed;

        private BudgetedOutputStream(OutputStream delegate,
                                     ArchiveExtractionByteBudget budget,
                                     String path, File parent) {
            this.delegate = delegate;
            this.budget = budget;
            this.path = path;
            this.parent = parent;
        }

        private void checkSpace(int count) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Archive extraction interrupted");
            if (count <= spaceAllowance) { spaceAllowance -= count; return; }
            // A sibling block spool or concurrent extraction can consume space
            // after this output opened. Refresh periodically, not only at open.
            long available = parent == null ? Long.MAX_VALUE : ArchiveSupport.runtimeExtractionBudgetBytes(parent);
            if (count > available) throw new IOException("Not enough free space for archive extraction");
            spaceAllowance = Math.min(1024L * 1024L, available - count);
        }

        private void requireWritable() throws IOException {
            if (closed) throw new IOException("Archive extraction output is closed");
            // A fresh exception avoids self-suppression when a buffered close
            // retries a failed write. The original failure stays available as cause.
            if (failure != null) throw new IOException("Archive extraction output failed", failure);
        }

        private void reserve(int count) throws IOException {
            requireWritable();
            try {
                checkSpace(count);
                budget.reserve(path, count);
            } catch (IOException | RuntimeException | Error error) {
                failure = error;
                throw error;
            }
        }

        private void failWrite(Throwable error, int count) {
            failure = error;
            try { budget.rollback(path, count); }
            catch (RuntimeException | Error cleanup) {
                if (cleanup != error) error.addSuppressed(cleanup);
            }
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            try {
                delegate.write(value);
            } catch (IOException | RuntimeException | Error e) {
                failWrite(e, 1);
                throw e;
            }
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) { requireWritable(); return; }
            reserve(length);
            try {
                delegate.write(data, offset, length);
            } catch (IOException | RuntimeException | Error e) {
                failWrite(e, length);
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            requireWritable();
            try { delegate.flush(); }
            catch (IOException | RuntimeException | Error error) { failure = error; throw error; }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            // Cleanup is allowed after failure/cancellation and may be retried.
            delegate.close();
        }
    }
}
