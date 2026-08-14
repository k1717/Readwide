package com.readwide.manager.archive;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
        if (previous != null) return new Scope(previous, null, false);
        ArchiveExtractionByteBudget budget = new ArchiveExtractionByteBudget(limitBytes);
        ACTIVE.set(budget);
        return new Scope(budget, null, true);
    }

    static OutputStream openOutputStream(File file, long standaloneLimitBytes) throws IOException {
        String path = file.getCanonicalPath();
        FileOutputStream raw = new FileOutputStream(file, false);
        ArchiveExtractionByteBudget budget = ACTIVE.get();
        if (budget == null) budget = new ArchiveExtractionByteBudget(standaloneLimitBytes);
        budget.beginFile(path);
        return new BudgetedOutputStream(raw, budget, path);
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
        long fileBytes = bytesByPath.containsKey(path) ? bytesByPath.get(path) : 0L;
        bytesByPath.put(path, fileBytes + count);
        totalBytes += count;
    }

    private synchronized void rollback(String path, int count) {
        if (count <= 0) return;
        long fileBytes = bytesByPath.containsKey(path) ? bytesByPath.get(path) : 0L;
        long rolledBack = Math.min(fileBytes, (long) count);
        bytesByPath.put(path, fileBytes - rolledBack);
        totalBytes = Math.max(0L, totalBytes - rolledBack);
    }

    static final class Scope implements AutoCloseable {
        private final ArchiveExtractionByteBudget budget;
        private final ArchiveExtractionByteBudget previous;
        private final boolean installed;
        private boolean closed;

        private Scope(ArchiveExtractionByteBudget budget,
                      ArchiveExtractionByteBudget previous,
                      boolean installed) {
            this.budget = budget;
            this.previous = previous;
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
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    private static final class BudgetedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ArchiveExtractionByteBudget budget;
        private final String path;

        private BudgetedOutputStream(OutputStream delegate,
                                     ArchiveExtractionByteBudget budget,
                                     String path) {
            this.delegate = delegate;
            this.budget = budget;
            this.path = path;
        }

        @Override
        public void write(int value) throws IOException {
            budget.reserve(path, 1);
            try {
                delegate.write(value);
            } catch (IOException | RuntimeException e) {
                budget.rollback(path, 1);
                throw e;
            }
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return;
            budget.reserve(path, length);
            try {
                delegate.write(data, offset, length);
            } catch (IOException | RuntimeException e) {
                budget.rollback(path, length);
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
