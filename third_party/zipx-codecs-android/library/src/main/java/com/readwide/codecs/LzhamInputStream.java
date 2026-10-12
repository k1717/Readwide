/* Readwide adapter. SPDX-License-Identifier: Apache-2.0 */
package com.readwide.codecs;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;

/** Raw LZHAM streaming; the 7z container owns properties, CRC and guarded output.
 * Dictionary memory follows the codec, not a whole-payload allocation or quota.
 */
public final class LzhamInputStream extends InputStream {
    private static final int BUFFER_SIZE = 65536;
    private final InputStream source;
    private final long expectedSize;
    private final byte[] packed = new byte[BUFFER_SIZE], decoded = new byte[BUFFER_SIZE], one = new byte[1];
    private long handle, produced;
    private int packedPosition, packedLimit, decodedPosition, decodedLimit;
    private boolean sourceEnded, ended, closed, busy, sourceClosed;
    private Throwable failure;

    private LzhamInputStream(InputStream source, long expectedSize) {
        this.source = source;
        this.expectedSize = expectedSize;
    }

    /** Takes ownership of input on entry, including initialization failure. */
    public static InputStream open(InputStream source, int dictionaryBits, long expectedSize) throws IOException {
        Objects.requireNonNull(source, "source");
        LzhamInputStream result = null;
        try {
            checkpoint();
            if (dictionaryBits < 15 || dictionaryBits > 29 || expectedSize < 0) {
                throw new IOException("Invalid LZHAM stream parameters");
            }
            ensureLoaded();
            result = new LzhamInputStream(source, expectedSize);
            result.handle = nativeCreate(dictionaryBits);
            if (result.handle == 0) throw new IOException("Cannot initialize LZHAM decoder");
            checkpoint();
            return result;
        } catch (IOException | RuntimeException | Error error) {
            try { if (result == null) source.close(); else result.close(); }
            catch (IOException | RuntimeException | Error cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
            if (error instanceof LinkageError) throw new IOException("LZHAM decoder unavailable", error);
            throw error;
        }
    }

    @Override public synchronized int read() throws IOException {
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override public synchronized int read(byte[] data, int offset, int length) throws IOException {
        Objects.requireNonNull(data);
        if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("LZHAM stream closed");
        if (busy) throw new IOException("Reentrant LZHAM read");
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (length == 0) return 0;
        busy = true;
        try {
            checkpoint();
            while (decodedPosition == decodedLimit && !ended) {
                checkpoint();
                if (packedPosition == packedLimit && !sourceEnded) fill();
                int available = packedLimit - packedPosition;
                int room = (int) Math.min(BUFFER_SIZE, Math.max(1L, expectedSize - produced));
                long result = nativeStep(handle, packed, packedPosition, available, decoded, room);
                checkpoint();
                int consumed = (int) ((result >>> 32) & 0x7fffffffL);
                int count = (int) result;
                boolean finished = result < 0;
                if (consumed > available || count < 0 || count > room) throw new IOException("Invalid LZHAM decoder progress");
                packedPosition += consumed;
                if (count > expectedSize - produced) throw new IOException("LZHAM output exceeds declared size");
                produced += count;
                decodedPosition = 0;
                decodedLimit = count;
                if (finished) {
                    if (produced != expectedSize) throw new IOException("LZHAM output size mismatch");
                    if (packedPosition != packedLimit || (!sourceEnded && fill())) {
                        throw new IOException("Trailing LZHAM packed data");
                    }
                    ended = true;
                    releaseNative();
                } else if (consumed == 0 && count == 0) {
                    if (sourceEnded && available == 0) throw new EOFException("Truncated LZHAM stream");
                    throw new IOException("LZHAM decoder made no progress");
                }
            }
            if (decodedPosition == decodedLimit) return -1;
            int count = Math.min(length, decodedLimit - decodedPosition);
            System.arraycopy(decoded, decodedPosition, data, offset, count);
            decodedPosition += count;
            return count;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            try { releaseNative(); }
            catch (IOException | RuntimeException | Error cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
            throw error;
        } finally { busy = false; }
    }

    private boolean fill() throws IOException {
        checkpoint();
        int count = source.read(packed, 0, packed.length);
        checkpoint();
        if (count == 0 || count < -1 || count > packed.length) throw new IOException("Invalid LZHAM packed read");
        packedPosition = 0;
        packedLimit = Math.max(0, count);
        sourceEnded = count == -1;
        return count > 0;
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("LZHAM extraction cancelled");
    }

    private void releaseNative() throws IOException {
        long owned = handle;
        handle = 0;
        if (owned != 0) nativeFree(owned);
    }

    @Override public synchronized void close() throws IOException {
        if (busy) throw new IOException("Cannot close LZHAM stream from an input callback");
        closed = true;
        busy = true;
        try {
            try { releaseNative(); }
            catch (IOException | RuntimeException | Error error) {
                try { closeSource(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                throw error;
            }
            closeSource();
        } finally { busy = false; }
    }

    private void closeSource() throws IOException {
        // Failed cleanup retains ownership, but never reopens the decoder.
        if (!sourceClosed) { source.close(); sourceClosed = true; }
    }

    private static boolean loaded;
    private static synchronized void ensureLoaded() {
        if (!loaded) { System.loadLibrary("readwide-lzham"); loaded = true; }
    }

    private static native long nativeCreate(int dictionaryBits) throws IOException;
    private static native long nativeStep(long handle, byte[] input, int offset, int count,
            byte[] output, int capacity) throws IOException;
    private static native void nativeFree(long handle) throws IOException;
}
