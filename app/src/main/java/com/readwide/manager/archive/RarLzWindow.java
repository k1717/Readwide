package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class RarLzWindow {
    private byte[] window;
    private final RarDecodedOutput out;
    private int position;
    private long written;
    private int retained;
    private int maximumHistoryCapacity;

    RarLzWindow(int size, OutputStream out) {
        this(size, RarOutputStreamDecodedOutput.wrapOrMemory(out));
    }

    RarLzWindow(int size, RarDecodedOutput out) {
        this(new byte[validateWindowSize(size)], 0, out);
    }

    RarLzWindow(byte[] sharedWindow, int initialPosition, OutputStream out) {
        this(sharedWindow, initialPosition, RarOutputStreamDecodedOutput.wrapOrMemory(out));
    }

    RarLzWindow(byte[] sharedWindow, int initialPosition, RarDecodedOutput out) {
        this(sharedWindow, initialPosition, sharedWindow == null ? 0 : sharedWindow.length, out);
    }

    RarLzWindow(byte[] sharedWindow, int initialPosition, int retained, RarDecodedOutput out) {
        if (sharedWindow == null || sharedWindow.length <= 0
                || (sharedWindow.length & (sharedWindow.length - 1)) != 0) {
            throw new IllegalArgumentException("RAR LZ window size must be a power of two");
        }
        this.window = sharedWindow;
        maximumHistoryCapacity = sharedWindow.length;
        if (retained < 0 || retained > sharedWindow.length) throw new IllegalArgumentException("RAR history length");
        this.retained = retained;
        this.position = initialPosition & (sharedWindow.length - 1);
        this.out = out != null ? out : new RarOutputStreamDecodedOutput(new ByteArrayOutputStream());
    }

    private static int validateWindowSize(int size) {
        if (size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("RAR LZ window size must be a power of two");
        }
        return size;
    }

    void writeLiteral(int value) throws IOException {
        if (written == Long.MAX_VALUE) throw new IOException("RAR output counter overflow");
        if (retained == window.length && window.length < maximumHistoryCapacity) {
            ensureHistoryCapacity(Math.min(maximumHistoryCapacity, window.length * 2));
        }
        byte b = (byte) value;
        window[position] = b;
        position = (position + 1) & (window.length - 1);
        out.writeDecodedByte(b & 0xff);
        written++;
        if (retained < window.length) retained++;
    }

    void copyMatch(int distance, int length) throws IOException {
        if (distance <= 0 || distance > window.length) {
            throw new IOException("Invalid RAR LZ distance");
        }
        if (length < 0) throw new IOException("Invalid RAR LZ length");
        if (length > Long.MAX_VALUE - written) throw new IOException("RAR output counter overflow");
        int remaining = length;
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("RAR match copying cancelled");
            if (retained == window.length && window.length < maximumHistoryCapacity) {
                ensureHistoryCapacity(Math.min(maximumHistoryCapacity, window.length * 2));
            }
            int count = Math.min(remaining, Math.min(window.length - position, 64 * 1024));
            if (window.length < maximumHistoryCapacity) count = Math.min(count, window.length - retained);
            int source = (position - distance) & (window.length - 1);
            // Seed at most one period from existing history, including ring wrap.
            // A single overlapping arraycopy would NOT implement LZ repetition.
            int seed = Math.min(distance, count);
            int tail = Math.min(seed, window.length - source);
            System.arraycopy(window, source, window, position, tail);
            System.arraycopy(window, 0, window, position + tail, seed - tail);
            // Doubling the initialized prefix handles distance=1 and every other
            // overlapping match in O(log(count / distance)) array-copy calls.
            for (int filled = seed; filled < count; ) {
                int next = Math.min(filled, count - filled);
                System.arraycopy(window, position, window, position + filled, next);
                filled += next;
            }
            out.writeDecodedBytes(window, position, count);
            position = (position + count) & (window.length - 1);
            written += count;
            retained = Math.min(window.length, retained + count);
            remaining -= count;
        }
    }

    long written() {
        return written;
    }

    int position() {
        return position;
    }

    int size() {
        return window.length;
    }

    int retained() { return retained; }
    byte[] bytes() { return window; }

    void retainUpTo(int size) {
        validateWindowSize(size);
        maximumHistoryCapacity = Math.max(window.length, size);
    }

    /** Preserve chronological history across ring wrap; never allocate from declared file size. */
    void ensureHistoryCapacity(int size) {
        validateWindowSize(size);
        if (size <= window.length) return;
        byte[] grown = new byte[size];
        int first = (position - retained) & (window.length - 1);
        int tail = Math.min(retained, window.length - first);
        System.arraycopy(window, first, grown, 0, tail);
        System.arraycopy(window, 0, grown, tail, retained - tail);
        window = grown;
        position = retained;
    }
}
