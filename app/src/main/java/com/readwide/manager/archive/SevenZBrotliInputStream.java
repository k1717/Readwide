package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PushbackInputStream;
import org.brotli.dec.BrotliInputStream;

/** Raw Brotli and Brotli-MT frames; streamed output, with history sized by the decoder. */
final class SevenZBrotliInputStream extends InputStream {
    private final PushbackInputStream source;
    private final InputStream ownedSource;
    private final long expectedSize;
    private final byte[] one = new byte[1];
    private InputStream decoder;
    private Payload payload;
    private long produced, frameProduced, frameLimit;
    private boolean initialized, framed, ended, closed, sourceClosed;
    private boolean reading, closing;
    private Throwable failure;

    SevenZBrotliInputStream(InputStream source, long expectedSize) throws IOException {
        if (expectedSize < 0) throw new IOException("Invalid 7z Brotli output size");
        this.ownedSource = java.util.Objects.requireNonNull(source);
        // Validate physical reads before pushback can combine a bad source count
        // with buffered prefix bytes. Retain faults that Brotli reduces to codes.
        this.source = new PushbackInputStream(new CheckedSource(), 4);
        this.expectedSize = expectedSize;
    }

    @Override public int read() throws IOException {
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        java.util.Objects.requireNonNull(buffer);
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        if (reading || closing) throw new IOException("Reentrant 7z Brotli read");
        if (closed) throw new IOException("7z Brotli stream closed");
        rethrowFailure();
        if (length == 0) return 0;
        reading = true;
        try {
            checkpoint();
            if (!initialized) initialize();
            while (!ended) {
                checkpoint();
                if (decoder == null && !nextFrame()) {
                    if (produced != expectedSize) throw new IOException("7z Brotli output size mismatch");
                    ended = true;
                    break;
                }
                // Use only the decoder's bulk API, including single-byte callers.
                // One extra byte detects overrun without overflowing Long.MAX_VALUE.
                int want = (int) Math.min(length, Math.max(1L, expectedSize - produced));
                int count = decoder.read(buffer, offset, want);
                rethrowFailure();
                checkpoint();
                if (count > 0) {
                    if (count > want || count > expectedSize - produced
                            || count > frameLimit - frameProduced) {
                        throw new IOException("7z Brotli output exceeds declared size");
                    }
                    produced += count;
                    frameProduced += count;
                    return count;
                }
                if (count != -1) throw new IOException("Zero-progress 7z Brotli decoder");
                if (payload.remaining > 0) throw new IOException("Unused Brotli frame payload");
                releaseDecoder(); // Payload.close does not close the next frame's input.
                if (!framed) {
                    if (source.read() != -1) throw new IOException("Trailing Brotli stream data");
                    if (produced != expectedSize) throw new IOException("7z Brotli output size mismatch");
                    ended = true;
                }
            }
            checkpoint();
            return -1;
        } catch (IOException | RuntimeException | Error error) {
            if (failure == null) failure = error;
            try { releaseDecoder(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            rethrowFailure();
            throw error;
        } finally { reading = false; }
    }

    private void initialize() throws IOException {
        byte[] prefix = new byte[4];
        int length = 0, value;
        while (length < prefix.length && (value = source.read()) != -1) prefix[length++] = (byte) value;
        source.unread(prefix, 0, length);
        framed = length == 4 && little32(prefix, 0) == 0x184d2a50L;
        initialized = true;
        if (!framed) {
            frameLimit = expectedSize;
            payload = new Payload(-1);
            startDecoder();
        }
    }

    private boolean nextFrame() throws IOException {
        byte[] header = new byte[16];
        int first = source.read();
        if (first == -1) return false;
        header[0] = (byte) first;
        for (int i = 1; i < header.length; i++) {
            int value = source.read();
            if (value == -1) throw new IOException("Truncated Brotli-MT header");
            header[i] = (byte) value;
        }
        // Published Brotli-MT framing: magic, eight-byte metadata, packed length,
        // BR marker, and an output-size ceiling in 64 KiB units. Never allocate it.
        if (little32(header, 0) != 0x184d2a50L || little32(header, 4) != 8
                || (header[12] & 255) != 0x42 || (header[13] & 255) != 0x52) {
            throw new IOException("Invalid Brotli-MT frame header");
        }
        long packed = little32(header, 8);
        frameLimit = ((header[14] & 255L) | ((header[15] & 255L) << 8)) << 16;
        if (packed == 0) throw new IOException("Empty Brotli-MT packed stream");
        frameProduced = 0;
        payload = new Payload(packed);
        startDecoder();
        return true;
    }

    private void startDecoder() throws IOException {
        BrotliInputStream stream = new BrotliInputStream(payload);
        // Record ownership before configuring so the outer failure path closes it.
        decoder = stream;
        // Also accepts standard streams; the extension must be enabled before reading.
        // No guessed custom dictionary is attached to an archive payload.
        stream.enableLargeWindow();
    }

    private static long little32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("7z Brotli decoding cancelled");
    }

    private void rethrowFailure() throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    /** The outer owner, not this borrowed view or the codec, closes the source. */
    private final class CheckedSource extends InputStream {
        @Override public int read() throws IOException {
            try {
                rethrowFailure();
                checkpoint();
                int value = ownedSource.read();
                checkpoint();
                if (value < -1 || value > 255) throw new IOException("Invalid Brotli source byte");
                return value;
            } catch (IOException | RuntimeException | Error error) { failure = error; throw error; }
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                rethrowFailure();
                checkpoint();
                int count = ownedSource.read(bytes, offset, length);
                checkpoint();
                if (count < -1 || count > length || (count == 0 && length != 0)) {
                    throw new IOException("Invalid Brotli source read");
                }
                return count;
            } catch (IOException | RuntimeException | Error error) { failure = error; throw error; }
        }
    }

    private final class Payload extends InputStream {
        private final byte[] single = new byte[1];
        long remaining; // -1 for the raw, already container-bounded stream.
        Payload(long remaining) { this.remaining = remaining; }
        @Override public int read() throws IOException {
            return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                rethrowFailure();
                checkpoint();
                if (length == 0) return 0;
                if (remaining == 0) return -1;
                int want = remaining < 0 ? length : (int) Math.min(length, remaining);
                int count = source.read(buffer, offset, want);
                if (count == -1) {
                    if (remaining > 0) throw new IOException("Truncated Brotli-MT payload");
                    return -1;
                }
                if (count <= 0 || count > want) throw new IOException("Invalid Brotli source read");
                if (remaining > 0) remaining -= count;
                return count;
            } catch (IOException | RuntimeException | Error error) { failure = error; throw error; }
        }
        @Override public void close() { /* The outer owner closes the physical stream. */ }
    }

    private void releaseDecoder() throws IOException {
        InputStream owned = decoder;
        decoder = null;
        if (owned != null) owned.close();
    }

    @Override public void close() throws IOException {
        if (reading || closing) throw new IOException("Reentrant 7z Brotli close");
        closing = true;
        try { closeOwned(); }
        finally { closing = false; }
    }

    private void closeOwned() throws IOException {
        closed = true;
        Throwable first = null;
        try { releaseDecoder(); }
        catch (IOException | RuntimeException | Error error) { first = error; throw error; }
        finally {
            try {
                // Retain the physical source for retry even if a wrapper's close
                // implementation discards its reference before throwing.
                if (!sourceClosed) { ownedSource.close(); sourceClosed = true; }
            } catch (IOException | RuntimeException | Error cleanup) {
                if (first == null) throw cleanup;
                if (cleanup != first) first.addSuppressed(cleanup);
            }
        }
    }
}
