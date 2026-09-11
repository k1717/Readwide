package com.readwide.manager.archive;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;

/** Delays only queued standard-filter regions; never stores a whole decoded entry. */
final class Rar3PpmdFilterOutput extends OutputStream {
    // VM address-space bound, not an archive/file-size limit.
    static final int MAX_FILTER_BYTES = 0x40000;
    private static final int MAX_PENDING_FILTERS = 1024;
    private final OutputStream destination;
    private final long expectedSize;
    private final Rar3VmFilter.ProgramState programs;
    private final int maximumFilterBytes;
    private final ArrayDeque<Rar3VmFilter.PendingFilter> pending = new ArrayDeque<>();
    private long written;
    private byte[] block;
    private int buffered;
    private boolean finished;
    private IOException failure;

    interface Symbols { int read() throws IOException; }

    Rar3PpmdFilterOutput(OutputStream destination, long expectedSize,
                        Rar3VmFilter.ProgramState programs) {
        this(destination, expectedSize, programs, MAX_FILTER_BYTES);
    }

    Rar3PpmdFilterOutput(OutputStream destination, long expectedSize,
                        Rar3VmFilter.ProgramState programs, int maximumFilterBytes) {
        if (destination == null || programs == null || expectedSize < 0
                || maximumFilterBytes < 1 || maximumFilterBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid PPMd filter output");
        }
        this.destination = destination;
        this.expectedSize = expectedSize;
        this.programs = programs;
        this.maximumFilterBytes = maximumFilterBytes;
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
        } catch (IOException error) { fail(error); throw error; }
    }

    /** Shared by raw LZ VM records and symbol-coded PPMd VM records. */
    void acceptParsed(Rar3VmFilter.ProgramState.Parsed parsed) throws IOException {
        ensureOpen();
        if (parsed.resetPendingFilters && !pending.isEmpty()) {
            IOException error = unsupported("RAR3 VM reset with pending filters is not supported");
            fail(error);
            throw error;
        }
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
            if (filter == null || filter.type == null || filter.type == Rar3VmFilter.StandardFilter.NONE
                    || filter.blockLength <= 0 || filter.blockLength > maximumFilterBytes
                    || filter.blockStartAbs < written || filter.blockStartAbs > expectedSize
                    || filter.blockLength > expectedSize - filter.blockStartAbs) {
                throw unsupported("Invalid or cross-entry RAR3 PPMd VM range");
            }
            Rar3VmFilter.PendingFilter previous = pending.peekLast();
            if (previous != null) {
                boolean sameBlock = previous.blockStartAbs == filter.blockStartAbs
                        && previous.blockLength == filter.blockLength;
                if (!sameBlock && filter.blockStartAbs < previous.blockStartAbs + previous.blockLength) {
                    throw unsupported("Overlapping or out-of-order RAR3 PPMd VM ranges");
                }
            }
            if (pending.size() >= MAX_PENDING_FILTERS) {
                throw unsupported("RAR3 PPMd pending VM filter memory limit");
            }
            // Own the descriptor; later parser/caller changes must not alter queued work.
            Rar3VmFilter.PendingFilter copy = new Rar3VmFilter.PendingFilter();
            copy.type = filter.type;
            copy.blockStartAbs = filter.blockStartAbs;
            copy.fileOffset = filter.fileOffset;
            copy.blockLength = filter.blockLength;
            System.arraycopy(filter.initR, 0, copy.initR, 0, copy.initR.length);
            pending.addLast(copy);
        } catch (IOException error) { fail(error); throw error; }
    }

    @Override public void write(int value) throws IOException {
        ensureOpen();
        try {
            if (written == expectedSize) throw new IOException("RAR3 PPMd output exceeds entry size");
            Rar3VmFilter.PendingFilter first = pending.peekFirst();
            if (first == null || written < first.blockStartAbs) {
                destination.write(value);
                written++;
                return;
            }
            if (block == null) block = new byte[first.blockLength];
            block[buffered++] = (byte) value;
            written++;
            if (buffered == first.blockLength) emitFilteredBlock();
        } catch (IOException error) { fail(error); throw error; }
    }

    private void emitFilteredBlock() throws IOException {
        Rar3VmFilter.PendingFilter first = pending.peekFirst();
        long start = first.blockStartAbs;
        int length = first.blockLength;
        byte[] result = block;
        while ((first = pending.peekFirst()) != null
                && first.blockStartAbs == start && first.blockLength == length) {
            int[] registers = first.initR.clone();
            registers[4] = length;
            result = Rar3VmFilter.apply(first.type, result, length, registers, first.fileOffset);
            if (result.length != length) throw unsupported("RAR3 PPMd length-changing VM filter");
            pending.removeFirst();
        }
        destination.write(result, 0, length);
        block = null;
        buffered = 0;
    }

    void finish() throws IOException {
        ensureOpen();
        try {
            if (written != expectedSize || !pending.isEmpty() || buffered != 0) {
                throw new IOException("Incomplete RAR3 PPMd filtered output");
            }
            destination.flush();
            finished = true;
        } catch (IOException error) { fail(error); throw error; }
    }

    // Caller owns the target: close is an abort, never a drain or implicit commit.
    @Override public void close() {
        pending.clear();
        block = null;
        finished = true;
    }

    private void ensureOpen() throws IOException {
        if (failure != null) throw failure;
        if (finished) throw new IOException("RAR3 PPMd filter output is closed");
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
    }

    private void fail(IOException error) {
        failure = error;
        pending.clear();
        block = null;
    }

    private static IOException unsupported(String message) {
        return new RarArchiveReader.UnsupportedRarFeatureException(message);
    }
}
