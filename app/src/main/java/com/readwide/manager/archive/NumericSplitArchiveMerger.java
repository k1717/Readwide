package com.readwide.manager.archive;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/** Bounded-handle, cancellable spooling for numeric splits that require a real file. */
final class NumericSplitArchiveMerger {
    private NumericSplitArchiveMerger() {}

    interface SpaceBudget { long availableBytes(File directory); }

    static File merge(List<File> parts) throws IOException {
        return merge(parts, null, ArchiveSupport::runtimeExtractionBudgetBytes);
    }

    /** Package-visible seam for deterministic low-space/race regression tests. */
    static File merge(List<File> parts, File temporaryDirectory, SpaceBudget budget) throws IOException {
        checkpoint();
        if (parts == null || parts.isEmpty()) throw new IOException("No numeric split volumes");
        // Snapshot the whole set before the first output write, not each part just before copying it.
        File[] files = parts.toArray(new File[0]);
        long[] lengths = new long[files.length], modified = new long[files.length];
        long expected = 0;
        for (int i = 0; i < files.length; i++) {
            checkpoint();
            File part = files[i];
            if (part == null || !part.isFile() || !part.canRead()) throw new IOException("Split volume unavailable");
            lengths[i] = part.length();
            modified[i] = part.lastModified();
            if (lengths[i] < 0 || expected > Long.MAX_VALUE - lengths[i]) throw new IOException("Split archive too large");
            expected += lengths[i];
        }
        File temp = File.createTempFile("textview_split_archive_", ".tmp", temporaryDirectory);
        try {
            File parent = temp.getAbsoluteFile().getParentFile();
            if (budget.availableBytes(parent) < expected) throw new IOException("Not enough free space to merge split archive");
            // The one-open-volume channel preserves the round-two descriptor bound.
            try (SplitSeekableByteChannel input = new SplitSeekableByteChannel(java.util.Arrays.asList(files));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
                if (input.size() != expected) throw new IOException("Split volume set changed before merge");
                byte[] bytes = new byte[64 * 1024];
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                long copied = 0, sinceSpaceCheck = 0;
                while (copied < expected) {
                    checkpoint();
                    // Recheck remaining spool demand at most every MiB; free space may change.
                    if (sinceSpaceCheck >= 1024 * 1024) {
                        output.flush();
                        if (budget.availableBytes(parent) < expected - copied) {
                            throw new IOException("Not enough free space to merge split archive");
                        }
                        sinceSpaceCheck = 0;
                    }
                    buffer.clear();
                    int count = input.read(buffer);
                    if (count <= 0 || count > expected - copied) throw new IOException("Split archive changed during merge");
                    output.write(bytes, 0, count);
                    copied += count;
                    sinceSpaceCheck += count;
                }
                output.flush();
            }
            checkpoint();
            // Earlier parts can change while a later one is being copied. Recheck ALL stamps.
            for (int i = 0; i < files.length; i++) {
                checkpoint();
                if (!files[i].isFile() || !files[i].canRead() || files[i].length() != lengths[i]
                        || files[i].lastModified() != modified[i]) {
                    throw new IOException("Split volume changed during merge: " + files[i].getName());
                }
            }
            if (temp.length() != expected) throw new IOException("Incomplete split archive merge");
            return temp;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (temp.exists() && !temp.delete()) {
                    failure.addSuppressed(new IOException("Cannot remove failed split-archive spool: " + temp));
                }
            } catch (SecurityException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Split archive merge cancelled");
    }
}
