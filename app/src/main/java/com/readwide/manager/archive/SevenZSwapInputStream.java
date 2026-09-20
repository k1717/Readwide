package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;

/** Streaming 7z Swap2/Swap4: reverse complete words; leave the final partial word unchanged. */
final class SevenZSwapInputStream extends InputStream {
    private final InputStream source;
    private final int width;
    private final byte[] buffer = new byte[64 * 1024];
    private final byte[] single = new byte[1];
    private int position, limit;
    private boolean eof, closed;
    private IOException failure;

    SevenZSwapInputStream(InputStream source, int width) {
        if (width != 2 && width != 4) throw new IllegalArgumentException("Swap word width must be 2 or 4");
        this.source = Objects.requireNonNull(source, "source");
        this.width = width;
    }
    @Override public int read() throws IOException {
        return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
    }
    @Override public int read(byte[] output, int offset, int length) throws IOException {
        Objects.requireNonNull(output, "output");
        if ((offset | length) < 0 || length > output.length - offset) throw new IndexOutOfBoundsException();
        checkOpen();
        if (length == 0) return 0;
        if (position == limit && !fill()) return -1;
        int count = Math.min(length, limit - position);
        System.arraycopy(buffer, position, output, offset, count);
        position += count;
        return count;
    }
    private boolean fill() throws IOException {
        if (eof) return false;
        try {
            int count = checkedRead(0, buffer.length);
            if (count == -1) { eof = true; return false; }
            // Preserve word boundaries even when the wrapped stream returns short reads.
            while (count % width != 0) {
                int n = checkedRead(count, width - count % width);
                if (n == -1) { eof = true; break; }
                count += n;
            }
            int whole = count - count % width;
            for (int start = 0; start < whole; start += width) {
                for (int left = start, right = start + width - 1; left < right; left++, right--) {
                    byte value = buffer[left]; buffer[left] = buffer[right]; buffer[right] = value;
                }
            }
            position = 0;
            limit = count;
            return true;
        } catch (IOException error) {
            failure = error;
            throw error;
        }
    }
    private int checkedRead(int offset, int length) throws IOException {
        checkOpen();
        int count = source.read(buffer, offset, length);
        if (count == 0 || count < -1 || count > length) throw new IOException("Invalid or zero-progress 7z Swap read");
        return count;
    }
    private void checkOpen() throws IOException {
        if (closed) throw new IOException("7z Swap stream is closed");
        if (failure != null) throw failure;
        if (Thread.currentThread().isInterrupted()) {
            failure = new InterruptedIOException("7z Swap decoding cancelled");
            throw failure;
        }
    }
    @Override public int available() throws IOException { checkOpen(); return limit - position; }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        java.util.Arrays.fill(buffer, (byte) 0);
        source.close();
    }
}
