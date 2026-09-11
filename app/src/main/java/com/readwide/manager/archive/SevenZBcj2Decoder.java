package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * First-party decoder for the 7-Zip BCJ2 branch-conversion filter (coder id
 * {@code 03 03 01 1B}).
 *
 * <p>BCJ2 is a four-input coder that Apache Commons Compress cannot decode
 * ("Multi input/output stream coders are not yet supported"), so 7z archives
 * whose entries use BCJ2 - including AES-encrypted ones, where the AES layer
 * decrypts fine but the BCJ2 join then fails - were previously unsupported.
 * This is a clean-room implementation from the published BCJ2 algorithm
 * (7-Zip {@code Bcj2Dec.c}); no 7-Zip source is copied.</p>
 *
 * <p>The four inputs are: the main byte stream; the "call" stream of 4-byte
 * big-endian absolute targets for converted {@code E8} instructions; the
 * "jump" stream of the same for {@code E9} and two-byte {@code 0F 8x}
 * conditional jumps; and a range-coded control stream of one bit per
 * candidate instruction deciding whether its displacement was converted to an
 * absolute address. On decode, each converted 4-byte absolute target is turned
 * back into the original PC-relative displacement.</p>
 */
final class SevenZBcj2Decoder {
    private static final int TOP_VALUE = 1 << 24;
    private static final int MODEL_TOTAL_BITS = 11;
    private static final int BIT_MODEL_TOTAL = 1 << MODEL_TOTAL_BITS;
    private static final int MOVE_BITS = 5;
    private static final int NUM_PROBS = 2 + 256;

    private SevenZBcj2Decoder() {
    }

    /**
     * Decodes a BCJ2-coded entry from its four input streams into exactly
     * {@code outSize} output bytes.
     *
     * @param main the main byte stream (coder input 0)
     * @param call the call-target stream (coder input 1)
     * @param jump the jump-target stream (coder input 2)
     * @param rc   the range-coded control stream (coder input 3)
     */
    @NonNull
    static byte[] decode(@NonNull byte[] main, @NonNull byte[] call, @NonNull byte[] jump,
                         @NonNull byte[] rc, long outSize) throws IOException {
        if (outSize < 0 || outSize > Integer.MAX_VALUE - 8) {
            throw new IOException("BCJ2 byte-array output size out of range");
        }
        try (InputStream decoded = decodeStream(new ByteArrayInputStream(main),
                new ByteArrayInputStream(call), new ByteArrayInputStream(jump),
                new ByteArrayInputStream(rc), outSize)) {
            byte[] result = new byte[(int) outSize];
            int offset = 0;
            while (offset < result.length) {
                int count = decoded.read(result, offset, result.length - offset);
                if (count < 0) throw new EOFException("Truncated BCJ2 output");
                offset += count;
            }
            return result;
        }
    }

    /** Pull decoder: only probability state and one pending four-byte address are retained. */
    @NonNull
    static InputStream decodeStream(@NonNull InputStream main, @NonNull InputStream call,
                                    @NonNull InputStream jump, @NonNull InputStream rc,
                                    long outSize) throws IOException {
        return new DecoderStream(main, call, jump, rc, outSize);
    }

    private static final class DecoderStream extends InputStream {
        private final InputStream main, call, jump, rc;
        private final long size;
        private final int[] probs = new int[NUM_PROBS];
        private long position;
        private long code;
        private long range = 0xffffffffL;
        private long pendingAddress;
        private int pendingBytes;
        private int previous;
        private boolean initialized;
        private boolean closed;

        DecoderStream(InputStream main, InputStream call, InputStream jump, InputStream rc,
                      long size) throws IOException {
            if (size < 0) throw new IOException("Invalid BCJ2 output size");
            this.main = main; this.call = call; this.jump = jump; this.rc = rc;
            this.size = size;
            java.util.Arrays.fill(probs, BIT_MODEL_TOTAL >> 1);
        }

        @Override public int read() throws IOException {
            if (closed) throw new IOException("BCJ2 stream closed");
            if (!initialized) {
                if (requiredByte(rc, "control") != 0) throw new IOException("Invalid BCJ2 range header");
                for (int i = 0; i < 4; i++) code = ((code << 8) | requiredByte(rc, "control")) & 0xffffffffL;
                initialized = true;
            }
            if (position == size) return -1;
            if (pendingBytes > 0) {
                int value = (int) pendingAddress & 0xff;
                pendingAddress >>>= 8;
                pendingBytes--;
                position++;
                if (pendingBytes == 0) previous = value;
                return value;
            }
            int value = requiredByte(main, "main");
            position++;
            boolean branch = (value & 0xfe) == 0xe8 || (previous == 0x0f && (value & 0xf0) == 0x80);
            if (!branch) { previous = value; return value; }
            int index = value == 0xe8 ? 2 + previous : value == 0xe9 ? 1 : 0;
            int probability = probs[index];
            long bound = (range >>> MODEL_TOTAL_BITS) * probability;
            boolean converted = code >= bound;
            if (!converted) {
                range = bound;
                probs[index] = probability + ((BIT_MODEL_TOTAL - probability) >>> MOVE_BITS);
            } else {
                range -= bound;
                code = (code - bound) & 0xffffffffL;
                probs[index] = probability - (probability >>> MOVE_BITS);
            }
            if (range < TOP_VALUE) {
                range = (range << 8) & 0xffffffffL;
                code = ((code << 8) | requiredByte(rc, "control")) & 0xffffffffL;
            }
            if (!converted) { previous = value; return value; }
            if (size - position < 4) throw new IOException("BCJ2 address extends past output");
            InputStream addresses = value == 0xe8 ? call : jump;
            long target = 0;
            for (int i = 0; i < 4; i++) target = (target << 8) | requiredByte(addresses, "address");
            pendingAddress = (target - ((position + 4) & 0xffffffffL)) & 0xffffffffL;
            pendingBytes = 4;
            return value;
        }

        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
            if (closed) throw new IOException("BCJ2 stream closed");
            if (length == 0) return 0;
            int count = 0;
            while (count < length) {
                int value = read();
                if (value < 0) break;
                data[offset + count++] = (byte) value;
            }
            return count == 0 ? -1 : count;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            for (InputStream stream : new InputStream[] {main, call, jump, rc}) {
                try { stream.close(); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
    }

    private static int requiredByte(InputStream input, String name) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("BCJ2 " + name + " stream exhausted");
        return value;
    }
}
