package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.zip.CRC32;

/**
 * Delayed output buffer for future first-party RAR3/RAR4 VM-filter wiring.
 *
 * <p>The current live RAR extractor still reports VM-filtered streams as an unsupported first-party
 * gap. This buffer is the next safe building block: bytes can be written in normal output order,
 * filter ranges can be queued ahead of the bytes they transform, and flushes will not release a
 * range until the full filter input is available and transformed.</p>
 */
final class RarFilteredOutputBuffer extends OutputStream {
    static final int DEFAULT_MAX_DELAYED_BYTES = 64 * 1024 * 1024;

    private final OutputStream out;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final RarVmFilterQueue acceptedFilters = new RarVmFilterQueue();
    private final Queue<RarVmQueuedFilter> filters = new ArrayDeque<>();
    private final CRC32 filteredCrc = new CRC32();
    private final int maxDelayedBytes;

    private long bufferBaseOffset;
    private long writeOffset;
    private long emittedOffset;
    private boolean finished;

    RarFilteredOutputBuffer(@NonNull OutputStream out) {
        this(out, DEFAULT_MAX_DELAYED_BYTES);
    }

    RarFilteredOutputBuffer(@NonNull OutputStream out, int maxDelayedBytes) {
        if (out == null) {
            throw new IllegalArgumentException("Missing RAR filtered output target");
        }
        if (maxDelayedBytes <= 0) {
            throw new IllegalArgumentException("Invalid RAR filtered output delayed-buffer limit");
        }
        this.out = out;
        this.maxDelayedBytes = maxDelayedBytes;
    }

    void queueFilter(@NonNull RarVmQueuedFilter filter) throws IOException {
        ensureOpen();
        if (filter.length > maxDelayedBytes) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR VM filter range exceeds first-party delayed-output memory limit");
        }
        if (filter.outputOffset < emittedOffset) {
            throw new IOException("RAR VM filter range was already emitted");
        }
        acceptedFilters.add(filter);
        filters.add(filter);
        flushAvailable();
    }

    long writtenOffset() {
        return writeOffset;
    }

    long emittedOffset() {
        return emittedOffset;
    }

    long pendingByteCount() {
        return writeOffset - emittedOffset;
    }

    long filteredCrcValue() {
        return filteredCrc.getValue();
    }

    @Override
    public void write(int value) throws IOException {
        ensureOpen();
        pending.write(value);
        writeOffset++;
        flushAvailable();
        validateDelayedByteLimit();
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        ensureOpen();
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length || offset + length < offset
                || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid RAR filtered output write range");
        }
        if (length == 0) return;
        pending.write(data, offset, length);
        writeOffset += length;
        flushAvailable();
        validateDelayedByteLimit();
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        flushAvailable();
        out.flush();
    }

    void finish() throws IOException {
        ensureOpen();
        while (!filters.isEmpty()) {
            RarVmQueuedFilter filter = filters.peek();
            if (writeOffset < filter.endOffsetExclusive()) {
                throw new IOException("RAR VM filter range is incomplete at end of stream");
            }
            flushAvailable();
        }
        emitBufferedPrefix(pending.size());
        out.flush();
        finished = true;
    }

    @Override
    public void close() throws IOException {
        if (!finished) {
            finish();
        }
        out.close();
    }

    private void flushAvailable() throws IOException {
        while (pending.size() > 0) {
            RarVmQueuedFilter filter = filters.peek();
            if (filter == null) {
                emitBufferedPrefix(pending.size());
                continue;
            }
            if (filter.outputOffset < bufferBaseOffset) {
                throw new IOException("RAR VM filter range was partially emitted");
            }
            if (filter.outputOffset > bufferBaseOffset) {
                long safeBeforeFilter = filter.outputOffset - bufferBaseOffset;
                emitBufferedPrefix(checkedBufferedCount(safeBeforeFilter));
                continue;
            }
            if (writeOffset < filter.endOffsetExclusive()) {
                return;
            }
            applyNextFilterAtBufferStart(filter);
            emitBufferedPrefix(filter.length);
            filters.remove();
        }
    }

    private void applyNextFilterAtBufferStart(@NonNull RarVmQueuedFilter filter)
            throws IOException {
        byte[] data = pending.toByteArray();
        if (data.length < filter.length) {
            throw new IOException("RAR VM filter range is not fully buffered");
        }
        RarStandardFilterApplier.applyInPlace(
                data,
                0,
                filter.length,
                filter.standardFilter,
                filter.fileOffset);
        pending.reset();
        pending.write(data, 0, data.length);
    }

    private void emitBufferedPrefix(int count) throws IOException {
        if (count <= 0) return;
        byte[] data = pending.toByteArray();
        if (count > data.length) {
            throw new IOException("RAR filtered output flush exceeds delayed buffer");
        }
        out.write(data, 0, count);
        filteredCrc.update(data, 0, count);
        emittedOffset += count;
        bufferBaseOffset += count;
        pending.reset();
        if (count < data.length) {
            pending.write(data, count, data.length - count);
        }
    }

    private static int checkedBufferedCount(long count) throws IOException {
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IOException("RAR filtered output buffered range is too large");
        }
        return (int) count;
    }

    private void ensureOpen() throws IOException {
        if (finished) {
            throw new IOException("RAR filtered output buffer is already finished");
        }
    }

    private void validateDelayedByteLimit() throws IOException {
        if (pending.size() > maxDelayedBytes) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR VM delayed output exceeded first-party memory limit");
        }
    }
}
