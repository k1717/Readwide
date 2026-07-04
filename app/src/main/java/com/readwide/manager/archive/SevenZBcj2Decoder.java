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
            throw new IOException("BCJ2 output size out of range");
        }
        byte[] out = new byte[(int) outSize];
        int outPos = 0;
        int mainPos = 0;
        int callPos = 0;
        int jumpPos = 0;

        int[] probs = new int[NUM_PROBS];
        for (int i = 0; i < NUM_PROBS; i++) probs[i] = BIT_MODEL_TOTAL >> 1;

        // Range-decoder init: first byte is ignored, then 4 bytes form code.
        int rcPos = 1;
        long code = 0;
        long range = 0xFFFFFFFFL;
        for (int i = 0; i < 4; i++) {
            code = ((code << 8) | rcByte(rc, rcPos++)) & 0xFFFFFFFFL;
        }

        int prev = 0;
        while (outPos < out.length) {
            int b = main[mainPos++] & 0xff;
            out[outPos++] = (byte) b;

            boolean isBranch = (b & 0xFE) == 0xE8 || (prev == 0x0F && (b & 0xF0) == 0x80);
            if (!isBranch) {
                prev = b;
                continue;
            }

            int probIndex;
            if (b == 0xE8) {
                probIndex = 2 + prev;
            } else if (b == 0xE9) {
                probIndex = 1;
            } else {
                probIndex = 0;
            }

            // Inlined range-decoder bit.
            int v = probs[probIndex];
            long bound = (range >>> MODEL_TOTAL_BITS) * v;
            int bit;
            if ((code & 0xFFFFFFFFL) < bound) {
                range = bound;
                probs[probIndex] = v + ((BIT_MODEL_TOTAL - v) >>> MOVE_BITS);
                bit = 0;
            } else {
                range -= bound;
                code = (code - bound) & 0xFFFFFFFFL;
                probs[probIndex] = v - (v >>> MOVE_BITS);
                bit = 1;
            }
            if ((range & 0xFFFFFFFFL) < TOP_VALUE) {
                range = (range << 8) & 0xFFFFFFFFL;
                code = ((code << 8) | rcByte(rc, rcPos++)) & 0xFFFFFFFFL;
            }

            if (bit == 0) {
                prev = b;
                continue;
            }

            long dest;
            if (b == 0xE8) {
                if (callPos + 4 > call.length) throw new EOFException("BCJ2 call stream exhausted");
                dest = readBigEndian(call, callPos);
                callPos += 4;
            } else {
                if (jumpPos + 4 > jump.length) throw new EOFException("BCJ2 jump stream exhausted");
                dest = readBigEndian(jump, jumpPos);
                jumpPos += 4;
            }
            if (outPos + 4 > out.length) throw new IOException("BCJ2 stream corrupted: address past end");
            long rel = (dest - (outPos + 4)) & 0xFFFFFFFFL;
            out[outPos++] = (byte) (rel & 0xff);
            out[outPos++] = (byte) ((rel >>> 8) & 0xff);
            out[outPos++] = (byte) ((rel >>> 16) & 0xff);
            out[outPos++] = (byte) ((rel >>> 24) & 0xff);
            prev = (int) ((rel >>> 24) & 0xff);
        }
        return out;
    }

    /**
     * Streams-in-memory convenience wrapper: fully reads each input stream and
     * decodes. BCJ2's converted addresses are absolute, so the whole main
     * stream must be available; buffering all four inputs is inherent.
     */
    @NonNull
    static InputStream decodeStream(@NonNull InputStream main, @NonNull InputStream call,
                                    @NonNull InputStream jump, @NonNull InputStream rc,
                                    long outSize) throws IOException {
        return new ByteArrayInputStream(decode(readAll(main), readAll(call), readAll(jump), readAll(rc), outSize));
    }

    private static int rcByte(@NonNull byte[] rc, int pos) {
        return pos < rc.length ? rc[pos] & 0xff : 0;
    }

    private static long readBigEndian(@NonNull byte[] data, int pos) {
        return ((long) (data[pos] & 0xff) << 24)
                | ((data[pos + 1] & 0xff) << 16)
                | ((data[pos + 2] & 0xff) << 8)
                | (data[pos + 3] & 0xff);
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        return bos.toByteArray();
    }
}
