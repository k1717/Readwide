/* Readwide native-module adapter. SPDX-License-Identifier: LGPL-2.1-or-later */
package com.readwide.codecs;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Sequential LZ5/Lizard frames, including canonical 7-Zip-zstd MT wrappers.
 * Uses bounded Java I/O buffers; native history/block memory follows the frame.
 * No worker thread, complete-file buffer, temporary file or encoder is used.
 */
public final class LzFamilyInputStream extends InputStream {
    private static final int BUFFER_SIZE = 65536;
    private final InputStream source;
    private final boolean lizard;
    private final long expectedSize;
    private final byte[] scratch = new byte[BUFFER_SIZE];
    private final byte[] one = new byte[1];
    // Stable addresses are required by the native frame API across partial reads.
    private final ByteBuffer input = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer output = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private long handle, produced, frameRemaining = -1;
    private boolean sawFrame, sourceEnded, ended, closed, sourceClosed, busy;
    private Throwable failure;

    private LzFamilyInputStream(InputStream source, boolean lizard, long expectedSize) throws IOException {
        if (expectedSize < 0) throw new IOException("Invalid 7z LZ output size");
        this.source = Objects.requireNonNull(source);
        this.lizard = lizard;
        this.expectedSize = expectedSize;
        input.limit(0); output.limit(0);
    }

    /** Owns input on entry, including initialization failure. */
    public static InputStream open(InputStream source, boolean lizard, long expectedSize) throws IOException {
        Objects.requireNonNull(source);
        try {
            checkpoint();
            ZipxNativeCodecs.ensureLoaded();
            return new LzFamilyInputStream(source, lizard, expectedSize);
        } catch (IOException | RuntimeException | Error error) {
            try { source.close(); }
            catch (IOException | RuntimeException | Error cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
            if (error instanceof LinkageError) throw new IOException("Native LZ5/Lizard decoder unavailable", error);
            throw error;
        }
    }

    @Override public synchronized int read() throws IOException {
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override public synchronized int read(byte[] data, int offset, int length) throws IOException {
        Objects.requireNonNull(data);
        if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("LZ frame stream closed");
        if (busy) throw new IOException("Reentrant LZ frame read");
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (length == 0) return 0;
        busy = true;
        try {
            checkpoint();
            while (!output.hasRemaining() && !ended) {
                checkpoint();
                if (handle == 0 && !beginFrame()) {
                    if (!sawFrame || produced != expectedSize) throw new IOException("7z LZ stream size mismatch");
                    ended = true;
                    break;
                }
                int available = input.remaining();
                if (frameRemaining >= 0) available = (int) Math.min(available, frameRemaining);
                int room = (int) Math.min(BUFFER_SIZE, Math.max(1L, expectedSize - produced));
                long result = nativeStep(handle, input, input.position(), available, output, room);
                checkpoint();
                int consumed = (int) ((result >>> 32) & 0x7fffffffL);
                int count = (int) result;
                boolean frameEnded = result < 0;
                if (consumed < 0 || consumed > available || count < 0 || count > room) {
                    throw new IOException("Invalid native LZ decoder progress");
                }
                input.position(input.position() + consumed);
                if (frameRemaining >= 0) frameRemaining -= consumed;
                if (count > expectedSize - produced) throw new IOException("7z LZ output exceeds declared size");
                produced += count;
                output.position(0); output.limit(count);
                if (frameEnded) {
                    releaseNative();
                    if (frameRemaining > 0) throw new IOException("Unused LZ MT frame payload");
                    sawFrame = true;
                } else if (consumed == 0 && count == 0) {
                    if (available != 0) throw new IOException("LZ decoder made no progress");
                    if (frameRemaining == 0 || !fill()) throw new EOFException("Truncated LZ frame");
                }
            }
            if (!output.hasRemaining()) return -1;
            int count = Math.min(length, output.remaining());
            output.get(data, offset, count);
            return count;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            try { releaseNative(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
            throw error;
        } finally { busy = false; }
    }

    private boolean beginFrame() throws IOException {
        // 0x50 remains reserved for the strict canonical MT wrapper below.
        // Other skippable IDs are ordinary metadata, not codec/output bytes.
        while (true) {
            checkpoint();
            if (!prefix(4)) return false;
            int marker = input.getInt(input.position());
            if ((marker & 0xfffffff0) != 0x184d2a50 || marker == 0x184d2a50) break;
            if (!prefix(8)) throw new EOFException("Truncated LZ metadata header");
            long remaining = input.getInt(input.position() + 4) & 0xffffffffL;
            input.position(input.position() + 8);
            while (remaining > 0) {
                checkpoint();
                if (!input.hasRemaining() && !fill()) throw new EOFException("Truncated LZ metadata payload");
                int count = (int) Math.min(remaining, input.remaining());
                input.position(input.position() + count);
                remaining -= count;
            }
        }
        frameRemaining = -1;
        int magic = input.getInt(input.position());
        if (magic == 0x184d2a50) {
            // MT metadata is a four-byte compressed-frame length, not output size.
            if (!prefix(12)) throw new EOFException("Truncated LZ MT header");
            if (input.getInt(input.position() + 4) != 4) throw new IOException("Invalid LZ MT metadata length");
            frameRemaining = input.getInt(input.position() + 8) & 0xffffffffL;
            if (frameRemaining < 7) throw new IOException("Invalid LZ MT packed size");
            input.position(input.position() + 12);
            if (!prefix(4)) throw new EOFException("Missing LZ MT frame");
            magic = input.getInt(input.position());
        }
        if (magic != (lizard ? 0x184d2206 : 0x184d2205)) throw new IOException("Invalid LZ frame magic or trailing data");
        handle = nativeCreate(lizard);
        if (handle == 0) throw new IOException("Cannot allocate LZ decoder context");
        return true;
    }

    /** Header lookahead is used only between native frames, never while srcExpect is live. */
    private boolean prefix(int required) throws IOException {
        while (input.remaining() < required) {
            if (!fill()) {
                if (input.hasRemaining()) throw new EOFException("Truncated LZ frame header");
                return false;
            }
        }
        return true;
    }

    private boolean fill() throws IOException {
        checkpoint();
        if (sourceEnded) return false;
        input.compact();
        int wanted = input.remaining();
        if (wanted == 0) { input.flip(); throw new IOException("LZ input buffer made no progress"); }
        int count = source.read(scratch, 0, wanted);
        checkpoint();
        if (count == -1) { sourceEnded = true; input.flip(); return false; }
        if (count <= 0 || count > wanted) { input.flip(); throw new IOException("Invalid LZ input read"); }
        input.put(scratch, 0, count); input.flip();
        return true;
    }

    private void releaseNative() {
        long owned = handle; handle = 0;
        if (owned != 0) nativeFree(owned);
    }

    @Override public synchronized void close() throws IOException {
        if (busy) throw new IOException("Cannot close LZ frame stream from an input callback");
        closed = true;
        busy = true;
        try {
            try { releaseNative(); }
            catch (RuntimeException | Error error) {
                try { closeSource(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                throw error;
            }
            closeSource();
        } finally { busy = false; }
    }

    private void closeSource() throws IOException {
        if (!sourceClosed) { source.close(); sourceClosed = true; }
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("7z extraction cancelled");
    }

    private static native long nativeCreate(boolean lizard) throws IOException;
    private static native long nativeStep(long handle, ByteBuffer input, int offset, int count,
                                         ByteBuffer output, int capacity) throws IOException;
    private static native void nativeFree(long handle);
}
