package com.readwide.manager.archive;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Bounded MSB-first reservoir shared by bit reads and an aligned byte view. */
final class RarBitInput {
    private final byte[] data;
    private final InputStream stream;
    private final long totalBits;
    private long bytesFetched;
    private long consumedBits;
    private long reservoir;
    private int bufferedBits;
    private IOException failure;

    RarBitInput(byte[] data) {
        this.data = data != null ? data : new byte[0];
        stream = null;
        totalBits = this.data.length * 8L;
    }

    /** Caller bounds/owns the input; this reader never closes it or allocates the payload. */
    RarBitInput(InputStream stream, long packedBytes) {
        if (stream == null || packedBytes < 0 || packedBytes > Long.MAX_VALUE / 8) {
            throw new IllegalArgumentException("Invalid RAR packed bit range");
        }
        this.stream = stream;
        data = null;
        totalBits = packedBytes * 8L;
    }

    int readBit() throws IOException {
        return readBits(1);
    }

    int readBits(int count) throws IOException {
        int value = peekBits(count);
        consume(count);
        return value;
    }

    int peekBits(int count) throws IOException {
        if (count < 0 || count > 24) throw new IllegalArgumentException("Invalid RAR bit count");
        ensureAvailable(count);
        fill(count);
        return count == 0 ? 0 : (int) ((reservoir >>> (bufferedBits - count)) & ((1L << count) - 1));
    }

    void skipBits(int count) throws IOException {
        if (count < 0) throw new IllegalArgumentException("Invalid RAR bit count");
        ensureAvailable(count);
        while (count > 0) {
            int chunk = Math.min(24, count);
            fill(chunk);
            consume(chunk);
            count -= chunk;
        }
    }

    void alignToByte() throws IOException {
        int padding = (int) ((8 - (consumedBits & 7)) & 7);
        skipBits(padding);
    }

    long remainingBits() { return totalBits - consumedBits; }

    long bitsRead() { return consumedBits; }

    /**
     * Byte reads use the same reservoir, including bytes fetched by an earlier peek.
     * Call alignToByte explicitly before switching modes. The view does not own input.
     */
    InputStream alignedBytes() throws IOException {
        ensureAligned();
        return new InputStream() {
            @Override public int read() throws IOException {
                ensureAligned();
                ensureAvailable(0);
                return remainingBits() == 0 ? -1 : readBits(8);
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (bytes == null) throw new NullPointerException("bytes");
                if ((offset | length) < 0 || length > bytes.length - offset) {
                    throw new IndexOutOfBoundsException();
                }
                ensureAligned();
                ensureAvailable(0);
                if (length == 0) return 0;
                if (remainingBits() == 0) return -1;
                int count = (int) Math.min((long) length, remainingBits() / 8);
                for (int i = 0; i < count; i++) bytes[offset + i] = (byte) readBits(8);
                return count;
            }
        };
    }

    private void ensureAligned() throws IOException {
        if ((consumedBits & 7) != 0) throw new IOException("RAR byte view requires byte alignment");
    }

    private void ensureAvailable(int count) throws IOException {
        if (failure != null) throw failure;
        if (Thread.currentThread().isInterrupted()) {
            failure = new IOException("RAR bit reading cancelled");
            throw failure;
        }
        if (remainingBits() < count) throw new EOFException("RAR bit stream ended unexpectedly");
    }

    private void fill(int count) throws IOException {
        try {
            while (bufferedBits < count) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("RAR bit reading cancelled");
                int value = data != null ? data[(int) bytesFetched] & 255 : stream.read();
                if (value < 0) throw new EOFException("Truncated RAR packed bit stream");
                bytesFetched++;
                reservoir = (reservoir << 8) | value;
                bufferedBits += 8;
            }
        } catch (IOException error) {
            failure = error;
            throw error;
        }
    }

    private void consume(int count) {
        bufferedBits -= count;
        consumedBits += count;
        reservoir &= (1L << bufferedBits) - 1;
    }
}
