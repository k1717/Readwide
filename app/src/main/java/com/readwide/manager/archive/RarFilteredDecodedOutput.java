package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;

/**
 * {@link RarDecodedOutput} adapter backed by the future VM delayed-output buffer.
 *
 * <p>This is intentionally not wired into live RAR extraction yet. Normal compressed RAR remains
 * libarchive-primary, and the first-party RAR3/RAR4 VM path still stops at a precise unsupported
 * gap when a real archive requires VM register/state decoding. The adapter only proves that the
 * decoded-output boundary can feed the delayed VM buffer/plan scaffold without
 * changing the existing unfiltered output path.</p>
 */
final class RarFilteredDecodedOutput implements RarDecodedOutput {
    private final RarFilteredOutputBuffer buffer;
    private final RarVmFilterPipeline pipeline;

    RarFilteredDecodedOutput(@NonNull OutputStream out) {
        this(new RarFilteredOutputBuffer(out));
    }

    RarFilteredDecodedOutput(@NonNull OutputStream out, int maxDelayedBytes) {
        this(new RarFilteredOutputBuffer(out, maxDelayedBytes));
    }

    RarFilteredDecodedOutput(@NonNull RarFilteredOutputBuffer buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("Missing RAR filtered decoded output buffer");
        }
        this.buffer = buffer;
        this.pipeline = new RarVmFilterPipeline(buffer);
    }

    @Override
    public void writeDecodedByte(int value) throws IOException {
        buffer.write(value & 0xff);
    }

    @Override
    public void writeDecodedBytes(byte[] data, int offset, int length) throws IOException {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length || offset + length < offset
                || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid RAR filtered decoded output range");
        }
        buffer.write(data, offset, length);
    }

    void queueExecutionPlan(@NonNull RarVmFilterExecutionPlan plan) throws IOException {
        pipeline.queueExecutionPlan(plan);
    }

    void queueStandaloneFilter(@NonNull RarVmFilter filter,
                               int outputLength,
                               long fileOffset) throws IOException {
        pipeline.queueStandaloneFilter(filter, outputLength, fileOffset);
    }

    void flush() throws IOException {
        buffer.flush();
    }

    void finish() throws IOException {
        buffer.finish();
    }

    long writtenOffset() {
        return buffer.writtenOffset();
    }

    long emittedOffset() {
        return buffer.emittedOffset();
    }

    long pendingByteCount() {
        return buffer.pendingByteCount();
    }

    long filteredCrcValue() {
        return buffer.filteredCrcValue();
    }
}
