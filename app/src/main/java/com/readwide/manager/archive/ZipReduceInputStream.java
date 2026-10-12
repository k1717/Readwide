package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** First-party streaming ZIP Reduce (methods 2-5), from PKWARE APPNOTE section 5.2. */
final class ZipReduceInputStream extends InputStream {
    private final InputStream source;
    private final int factor;
    private final long size;
    private final byte[][] followers = new byte[256][];
    private final byte[] history = new byte[4096];
    private final byte[] one = new byte[1];
    private long produced;
    private int bits, bitCount, last, run, distance;
    private boolean initialized, ended, closed, sourceClosed;
    private Throwable failure;

    ZipReduceInputStream(InputStream source, int method, long size) throws IOException {
        if (method < 2 || method > 5 || size < 0) throw new IOException("Invalid ZIP Reduce parameters");
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.factor = method - 1;
        this.size = size;
    }

    @Override public int read() throws IOException {
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (buffer == null) throw new NullPointerException("buffer");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("ZIP Reduce stream closed");
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (length == 0) return 0;
        try {
            checkpoint();
            if (!initialized) {
                for (int i = 255; i >= 0; i--) {
                    checkpoint();
                    int count = takeBits(6);
                    if (count > 32) throw new IOException("Invalid ZIP Reduce follower count");
                    byte[] set = new byte[count];
                    for (int j = 0; j < count; j++) set[j] = (byte) takeBits(8);
                    followers[i] = set;
                }
                initialized = true;
            }
            if (produced == size) {
                if (!ended) {
                    // Only the unused bits of the final byte may remain. Reading EOF
                    // also allows the enclosing encrypted stream to finish authentication.
                    if (run != 0 || source.read() != -1) throw new IOException("Trailing ZIP Reduce data");
                    ended = true;
                }
                return -1;
            }
            int count = (int) Math.min(length, size - produced);
            for (int i = 0; i < count; i++) {
                if ((i & 4095) == 0) checkpoint();
                int value;
                if (run == 0) {
                    value = symbol();
                    if (value == 144) {
                        int control = symbol();
                        if (control != 0) {
                            int mask = (1 << (8 - factor)) - 1;
                            run = control & mask;
                            if (run == mask) run += symbol();
                            distance = ((control >>> (8 - factor)) << 8) + symbol() + 1;
                            run += 3;
                            if (run > size - produced) throw new IOException("ZIP Reduce match exceeds declared size");
                        }
                    }
                } else value = 0;
                if (run > 0) {
                    // APPNOTE defines bytes before the beginning of output as zero.
                    value = produced < distance ? 0 : history[(int) (produced - distance) & 4095] & 255;
                    run--;
                }
                buffer[offset + i] = history[(int) produced & 4095] = (byte) value;
                produced++;
            }
            return count;
        } catch (IOException | RuntimeException | Error e) { failure = e; throw e; }
    }

    private int symbol() throws IOException {
        byte[] set = followers[last];
        int value;
        if (set.length == 0 || takeBits(1) != 0) value = takeBits(8);
        else {
            // PKZIP stores a one-bit index even for a single follower.
            int width = Math.max(1, 32 - Integer.numberOfLeadingZeros(set.length - 1));
            int index = takeBits(width);
            if (index >= set.length) throw new IOException("Invalid ZIP Reduce follower index");
            value = set[index] & 255;
        }
        last = value;
        return value;
    }

    private int takeBits(int count) throws IOException {
        while (bitCount < count) {
            int value = source.read();
            if (value < 0) throw new IOException("Truncated ZIP Reduce stream");
            if (value > 255) throw new IOException("Invalid ZIP Reduce source byte");
            bits |= value << bitCount;
            bitCount += 8;
        }
        int value = bits & ((1 << count) - 1);
        bits >>>= count;
        bitCount -= count;
        return value;
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP Reduce decoding cancelled");
    }

    @Override public void close() throws IOException {
        closed = true;
        if (!sourceClosed) { source.close(); sourceClosed = true; }
    }
}
