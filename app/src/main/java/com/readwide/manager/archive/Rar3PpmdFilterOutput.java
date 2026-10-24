package com.readwide.manager.archive;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import com.readwide.manager.util.FileOperationProgress;

/** Delays only queued filter regions; never stores a whole decoded entry. */
final class Rar3PpmdFilterOutput extends OutputStream {
    // VM address-space bound, not an archive/file-size limit.
    static final int MAX_FILTER_BYTES = 0x40000;
    private final OutputStream destination;
    private final long expectedSize;
    private final Rar3VmFilter.ProgramState programs;
    private final int maximumFilterBytes;
    private final DeferredFilters deferred;
    private final boolean allowPartialCarry;
    private final FileOperationProgress progress;
    private final ArrayDeque<Rar3VmFilter.PendingFilter> pending;
    private final Rar3VmFilter.ProgramRetention retainedPrograms;
    private long written; // Raw dictionary bytes consumed in this entry.
    private long published; // Final filtered file bytes, independent of raw position.
    private boolean lengthChanged;
    // End of the last queued raw-input region, not an intermediate VM result.
    private long tailSourceEnd;
    private byte[] block;
    private int buffered;
    private boolean finished;
    private boolean closed;
    private Throwable failure;

    interface Symbols { int read() throws IOException; }

    /** A bounded raw prefix can survive only after the preceding file's output is complete. */
    static final class DeferredFilters {
        private final ArrayDeque<Rar3VmFilter.PendingFilter> pending = new ArrayDeque<>();
        private final Rar3VmFilter.ProgramRetention retained = new Rar3VmFilter.ProgramRetention();
        private long tailSourceEnd;
        private byte[] block;
        private int buffered;
        private boolean inUse;

        /** Whole-run completion must not silently drop a scheduled future transformation. */
        void requireEmpty() throws IOException {
            if (inUse || !pending.isEmpty() || block != null || buffered != 0) {
                throw new IOException("RAR3 solid run ended with an unfinished VM filter");
            }
        }

        void reset() {
            pending.clear();
            retained.clear();
            tailSourceEnd = 0;
            block = null;
            buffered = 0;
        }
    }

    Rar3PpmdFilterOutput(OutputStream destination, long expectedSize,
                        Rar3VmFilter.ProgramState programs) {
        this(destination, expectedSize, programs, MAX_FILTER_BYTES);
    }

    Rar3PpmdFilterOutput(OutputStream destination, long expectedSize,
                        Rar3VmFilter.ProgramState programs, int maximumFilterBytes) {
        this(destination, expectedSize, programs, maximumFilterBytes, null);
    }

    Rar3PpmdFilterOutput(OutputStream destination, long expectedSize,
                        Rar3VmFilter.ProgramState programs, int maximumFilterBytes,
                        DeferredFilters deferred) {
        this(destination, expectedSize, programs, maximumFilterBytes, deferred, false);
    }

    Rar3PpmdFilterOutput(OutputStream destination, long expectedSize,
                        Rar3VmFilter.ProgramState programs, int maximumFilterBytes,
                        DeferredFilters deferred, boolean allowPartialCarry) {
        this(destination, expectedSize, programs, maximumFilterBytes, deferred, allowPartialCarry, null);
    }

    Rar3PpmdFilterOutput(OutputStream destination, long expectedSize,
                        Rar3VmFilter.ProgramState programs, int maximumFilterBytes,
                        DeferredFilters deferred, boolean allowPartialCarry, FileOperationProgress progress) {
        if (destination == null || programs == null || expectedSize < 0
                || maximumFilterBytes < 1 || maximumFilterBytes > 4 * 1024 * 1024
                || (allowPartialCarry && deferred == null)) {
            throw new IllegalArgumentException("Invalid PPMd filter output");
        }
        this.destination = destination;
        this.expectedSize = expectedSize;
        this.programs = programs;
        this.maximumFilterBytes = maximumFilterBytes;
        this.deferred = deferred;
        this.allowPartialCarry = allowPartialCarry;
        this.progress = progress;
        if (deferred == null) {
            pending = new ArrayDeque<>();
            retainedPrograms = new Rar3VmFilter.ProgramRetention();
        } else {
            if (deferred.inUse) throw new IllegalStateException("RAR3 filter state is already in use");
            deferred.inUse = true;
            pending = deferred.pending;
            retainedPrograms = deferred.retained;
            tailSourceEnd = deferred.tailSourceEnd;
            block = deferred.block;
            buffered = deferred.buffered;
            deferred.block = null;
            deferred.buffered = 0;
            // The prefix is already in solid dictionary history. Do not count or
            // decode it again; final output now differs from this entry's raw input.
            lengthChanged = buffered != 0;
        }
    }

    /** All bytes, including lengths, are PPMd symbols, not raw packed bytes. */
    void readRecord(Symbols symbols) throws IOException {
        ensureOpen();
        try {
            int flags = symbol(symbols);
            int length = (flags & 7) + 1;
            if (length == 7) length = symbol(symbols) + 7;
            else if (length == 8) length = (symbol(symbols) << 8) | symbol(symbols);
            if (length == 0) throw new IOException("Empty RAR3 PPMd VM record");
            byte[] code = new byte[length];
            for (int i = 0; i < length; i++) {
                if ((i & 1023) == 0) ensureOpen();
                code[i] = (byte) symbol(symbols);
            }
            Rar3VmFilter.ProgramState.Parsed parsed = programs.parse(
                    flags, code, written, maximumFilterBytes);
            acceptParsed(parsed);
        } catch (IOException | RuntimeException | Error error) { fail(error); throw error; }
    }

    /** Shared by raw LZ VM records and symbol-coded PPMd VM records. */
    void acceptParsed(Rar3VmFilter.ProgramState.Parsed parsed) throws IOException {
        ensureOpen();
        // A slot reset affects future records, not already queued transformations.
        // Descriptors own registers/globals and retain their original program object.
        queue(parsed.filter);
    }

    private static int symbol(Symbols symbols) throws IOException {
        int value = symbols.read();
        if (value < 0 || value > 255) throw new IOException("Truncated RAR3 PPMd VM record");
        return value;
    }

    void queue(Rar3VmFilter.PendingFilter filter) throws IOException {
        ensureOpen();
        try {
            if (filter == null || filter.type == null
                    || (filter.type == Rar3VmFilter.StandardFilter.NONE && filter.customProgram == null)
                    || filter.blockLength < 0 || filter.blockLength > maximumFilterBytes
                    || filter.blockStartAbs < written) {
                throw unsupported("Invalid or late RAR3 PPMd VM range");
            }
            Rar3VmFilter.PendingFilter previous = pending.peekLast();
            boolean chained = previous != null && previous.blockStartAbs == filter.blockStartAbs;
            // A zero-byte intermediate result consumes no new dictionary bytes.
            // Keep the first raw region nonempty; emitFilteredBlock also checks
            // that every successor's length matches the actual preceding result.
            if (filter.blockLength == 0 && !chained) {
                throw unsupported("RAR3 zero-length VM input requires a preceding filter");
            }
            if (!chained && filter.blockStartAbs > Long.MAX_VALUE - filter.blockLength) {
                throw unsupported("RAR3 PPMd raw filter range overflow");
            }
            // Only the plain gap is known to preserve length. A pending VM block
            // can shrink/expand, so final file size cannot bound its raw extent.
            if (deferred == null && previous == null
                    && filter.blockStartAbs - written > expectedSize - published) {
                throw unsupported("Invalid or cross-entry RAR3 PPMd VM range");
            }
            if (!chained && previous != null && filter.blockStartAbs < tailSourceEnd) {
                throw unsupported("Overlapping or out-of-order RAR3 PPMd VM ranges");
            }
            if (pending.size() >= Rar3VmFilter.ProgramRetention.MAX_PENDING_FILTERS) {
                throw unsupported("RAR3 PPMd pending VM filter memory limit");
            }
            // Own the descriptor; later parser/caller changes must not alter queued work.
            Rar3VmFilter.PendingFilter copy = new Rar3VmFilter.PendingFilter();
            copy.type = filter.type;
            copy.customProgram = filter.customProgram; // Program globals intentionally persist across executions.
            copy.globalData = filter.globalData.clone();
            copy.blockStartAbs = filter.blockStartAbs;
            copy.fileOffset = filter.fileOffset;
            copy.blockLength = filter.blockLength;
            System.arraycopy(filter.initR, 0, copy.initR, 0, copy.initR.length);
            retainedPrograms.retain(copy.customProgram);
            pending.addLast(copy);
            if (!chained) tailSourceEnd = copy.blockStartAbs + copy.blockLength;
        } catch (IOException | RuntimeException | Error error) { fail(error); throw error; }
    }

    @Override public void write(int value) throws IOException {
        ensureOpen();
        try {
            if (written == Long.MAX_VALUE || rawBytesUntilBoundary() == 0) {
                throw new IOException("RAR3 PPMd output exceeds entry size");
            }
            Rar3VmFilter.PendingFilter first = pending.peekFirst();
            if (first == null || written < first.blockStartAbs) {
                destination.write(value);
                written++;
                published++;
                return;
            }
            if (block == null) block = new byte[first.blockLength];
            block[buffered++] = (byte) value;
            written++;
            if (buffered == first.blockLength) emitFilteredBlock();
        } catch (IOException | RuntimeException | Error error) { fail(error); throw error; }
    }

    /** Bulk writes stop at filter boundaries; unfiltered spans need no per-byte dispatch. */
    @Override public void write(byte[] data, int offset, int length) throws IOException {
        if (data == null) throw new NullPointerException("data");
        if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
        ensureOpen();
        try {
            if (length > Long.MAX_VALUE - written
                    || (pending.isEmpty() && length > expectedSize - published)) {
                throw new IOException("RAR3 PPMd output exceeds entry size");
            }
            while (length > 0) {
                ensureOpen();
                Rar3VmFilter.PendingFilter first = pending.peekFirst();
                int count = Math.min(length, 64 * 1024); // Bound time between cancellation checks.
                count = (int) Math.min(count, rawBytesUntilBoundary());
                if (count == 0) throw new IOException("RAR3 PPMd output exceeds entry size");
                if (first == null || written < first.blockStartAbs) {
                    if (first != null) count = (int) Math.min(count, first.blockStartAbs - written);
                    destination.write(data, offset, count);
                    written += count;
                    published += count;
                } else {
                    if (block == null) block = new byte[first.blockLength];
                    count = Math.min(count, first.blockLength - buffered);
                    System.arraycopy(data, offset, block, buffered, count);
                    buffered += count;
                    written += count;
                    if (buffered == first.blockLength) emitFilteredBlock();
                }
                offset += count;
                length -= count;
            }
        } catch (IOException | RuntimeException | Error error) { fail(error); throw error; }
    }

    private void emitFilteredBlock() throws IOException {
        Rar3VmFilter.PendingFilter first = pending.peekFirst();
        long start = first.blockStartAbs;
        int length = first.blockLength;
        byte[] result = block;
        while ((first = pending.peekFirst()) != null
                && first.blockStartAbs == start) {
            // Chained descriptors name the preceding result, not new raw bytes.
            if (first.blockLength != result.length) {
                throw unsupported("RAR3 VM chain input length mismatch");
            }
            // queue() already owns a private register snapshot. Standard filters
            // only read it; custom execution copies it into its own VM state.
            int[] registers = first.initR;
            // Custom bytecode can intentionally override R4 in its record. The
            // actual input length still initializes fixed global block metadata.
            if (first.customProgram == null) registers[4] = result.length;
            // VM offsets and branch filters refer to final file position, not
            // the raw dictionary offset after earlier size-changing regions.
            first.fileOffset = published;
            result = Rar3VmFilter.apply(first, result, result.length, registers, progress);
            pending.removeFirst();
            retainedPrograms.release(first.customProgram);
        }
        if (result.length > expectedSize - published) {
            throw unsupported("RAR3 VM chain exceeds final entry size");
        }
        ArchiveCancellation.checkpoint(progress);
        destination.write(result, 0, result.length);
        published += result.length;
        lengthChanged |= result.length != length;
        block = null;
        buffered = 0;
    }

    /** Decoder writes may cross raw/filter boundaries, but never guess their output size. */
    long rawBytesUntilBoundary() throws IOException {
        ensureOpen();
        Rar3VmFilter.PendingFilter first = pending.peekFirst();
        if (first == null) return expectedSize - published;
        if (written < first.blockStartAbs) return Math.min(expectedSize - published, first.blockStartAbs - written);
        return first.blockLength - buffered;
    }

    boolean needsInput() { return published < expectedSize || !pending.isEmpty(); }
    boolean hasPendingFilters() { return !pending.isEmpty(); }
    boolean changedLength() { return lengthChanged; }
    long rawBytes() { return written; }
    long outputBytes() { return published; }

    /** Never defer output owed to this file; a prefix may belong wholly to the next one. */
    boolean canFinishEntry() {
        Rar3VmFilter.PendingFilter first = pending.peekFirst();
        if (published != expectedSize) return false;
        if (buffered == 0) {
            return first == null || (deferred != null && first.blockStartAbs >= written);
        }
        return allowPartialCarry && first != null && block != null
                && buffered < first.blockLength && block.length == first.blockLength
                && first.blockStartAbs == written - buffered;
    }

    void finish() throws IOException {
        finish(false);
    }

    void finish(boolean explicitFileBoundary) throws IOException {
        ensureOpen();
        try {
            if (!canFinishEntry() || (!pending.isEmpty() && !explicitFileBoundary)) {
                throw new IOException("Incomplete RAR3 PPMd filtered output");
            }
            destination.flush();
            ensureOpen();
            if (deferred != null) {
                // Offsets track raw history, not final bytes after length-changing VM work.
                // Keep original program objects even if their parser slots were reset.
                for (Rar3VmFilter.PendingFilter filter : pending) filter.blockStartAbs -= written;
                deferred.tailSourceEnd = pending.isEmpty() ? 0 : tailSourceEnd - written;
                // A negative rebased start names the prefix consumed by earlier
                // entries, not permission to accept new late filter records.
                deferred.block = block;
                deferred.buffered = buffered;
            }
            finished = true;
        } catch (IOException | RuntimeException | Error error) { fail(error); throw error; }
    }

    // Caller owns the target: close is an abort, never a drain or implicit commit.
    @Override public void close() {
        if (closed) return;
        closed = true;
        // A successfully finished solid entry handed its descriptors to the next entry.
        if (!finished || failure != null || deferred == null) {
            pending.clear();
            retainedPrograms.clear();
            if (deferred != null) deferred.reset();
        }
        if (deferred != null) deferred.inUse = false;
        block = null;
        finished = true;
    }

    private void ensureOpen() throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (finished) throw new IOException("RAR3 PPMd filter output is closed");
        if (Thread.currentThread().isInterrupted()) {
            IOException error = new IOException("RAR extraction cancelled");
            fail(error);
            throw error;
        }
    }

    private void fail(Throwable error) {
        failure = error;
        pending.clear();
        retainedPrograms.clear();
        if (deferred != null) deferred.reset();
        block = null;
    }

    private static IOException unsupported(String message) {
        return new RarArchiveReader.UnsupportedRarFeatureException(message);
    }
}
