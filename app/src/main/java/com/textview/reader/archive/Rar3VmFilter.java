package com.textview.reader.archive;

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * RAR3 VM filter support, limited to the six standard filters emitted by common RAR3 encoders
 * (E8, E8E9, Itanium, Delta, RGB, Audio). This is an independent Java implementation based on
 * publicly documented filter behavior and local fixture validation. The general RAR VM bytecode
 * interpreter is not implemented; a non-standard filter program causes an explicit failure.
 */
final class Rar3VmFilter {
    enum StandardFilter { NONE, E8, E8E9, ITANIUM, DELTA, RGB, AUDIO }

    // Standard filter fingerprints: {innerCodeLength, CRC32}.
    private static final long[][] STD = {
            {53, 0xad576887L},   // E8
            {57, 0x3cd7e57eL},   // E8E9
            {120, 0x3769893fL},  // ITANIUM
            {29, 0x0e06077dL},   // DELTA
            {149, 0x1c2c5dc8L},  // RGB
            {216, 0xbc85e701L},  // AUDIO
    };
    private static final StandardFilter[] STD_TYPE = {
            StandardFilter.E8, StandardFilter.E8E9, StandardFilter.ITANIUM,
            StandardFilter.DELTA, StandardFilter.RGB, StandardFilter.AUDIO
    };

    /** A pending filter to apply to a region of the output, in decode order. */
    static final class PendingFilter {
        StandardFilter type;
        long blockStartAbs; // absolute output position where the filtered block begins
        int blockLength;
        final int[] initR = new int[7];
    }

    private Rar3VmFilter() {}

    /** Identifies a standard filter from the inner VM code, or NONE. */
    static StandardFilter identify(byte[] innerCode) {
        if (innerCode.length == 0) return StandardFilter.NONE;
        // Standard-filter checksum guard: Code[0] must equal XOR of the rest.
        int xor = 0;
        for (int i = 1; i < innerCode.length; i++) xor ^= (innerCode[i] & 0xff);
        if ((xor & 0xff) != (innerCode[0] & 0xff)) return StandardFilter.NONE;

        CRC32 crc = new CRC32();
        crc.update(innerCode);
        long codeCrc = crc.getValue();
        for (int i = 0; i < STD.length; i++) {
            if (STD[i][0] == innerCode.length && STD[i][1] == codeCrc) {
                return STD_TYPE[i];
            }
        }
        return StandardFilter.NONE;
    }

    /**
     * Applies a standard filter in place to {@code data[0..length)}.
     * R registers: R[0],R[1] are init params; R[4]=blockLength; R[6]=fileOffset.
     * Returns the filtered bytes, possibly reusing the same array region.
     */
    static byte[] apply(StandardFilter type, byte[] data, int length, int[] r, long fileOffset) throws IOException {
        switch (type) {
            case E8:
            case E8E9:
                applyE8(type, data, length, fileOffset);
                return java.util.Arrays.copyOf(data, length);
            case ITANIUM:
                applyItanium(data, length, fileOffset);
                return java.util.Arrays.copyOf(data, length);
            case DELTA:
                return applyDelta(data, length, r[0]);
            case RGB:
                return applyRgb(data, length, r[0], r[1]);
            case AUDIO:
                return applyAudio(data, length, r[0]);
            default:
                throw new IOException("Unsupported RAR3 VM filter");
        }
    }

    private static long rawGet4(byte[] d, int p) {
        return (d[p] & 0xffL) | ((d[p + 1] & 0xffL) << 8) | ((d[p + 2] & 0xffL) << 16) | ((d[p + 3] & 0xffL) << 24);
    }

    private static void rawPut4(long v, byte[] d, int p) {
        d[p] = (byte) v;
        d[p + 1] = (byte) (v >>> 8);
        d[p + 2] = (byte) (v >>> 16);
        d[p + 3] = (byte) (v >>> 24);
    }

    private static void applyE8(StandardFilter type, byte[] data, int dataSize, long fileOffset) {
        if (dataSize < 4) return;
        final long fileSize = 0x1000000L;
        int cmpByte2 = (type == StandardFilter.E8E9) ? 0xe9 : 0xe8;
        int curPos = 0;
        int p = 0;
        while (curPos < dataSize - 4) {
            int curByte = data[p++] & 0xff;
            curPos++;
            if (curByte == 0xe8 || curByte == cmpByte2) {
                long offset = curPos + fileOffset;
                long addr = rawGet4(data, p);
                if ((addr & 0x80000000L) != 0) {            // addr < 0
                    if (((addr + offset) & 0x80000000L) == 0) {  // addr+offset >= 0
                        rawPut4((addr + fileSize) & 0xffffffffL, data, p);
                    }
                } else {
                    if (((addr - fileSize) & 0x80000000L) != 0) { // addr < fileSize
                        rawPut4((addr - offset) & 0xffffffffL, data, p);
                    }
                }
                p += 4;
                curPos += 4;
            }
        }
    }

    private static int itGetBits(byte[] data, int bitPos, int bitCount) {
        int inAddr = bitPos / 8;
        int inBit = bitPos & 7;
        long bitField = (data[inAddr] & 0xffL)
                | ((data[inAddr + 1] & 0xffL) << 8)
                | ((data[inAddr + 2] & 0xffL) << 16)
                | ((data[inAddr + 3] & 0xffL) << 24);
        bitField >>>= inBit;
        return (int) (bitField & (0xffffffffL >>> (32 - bitCount)));
    }

    private static void itSetBits(byte[] data, int bitField, int bitPos, int bitCount) {
        int inAddr = bitPos / 8;
        int inBit = bitPos & 7;
        long andMask = 0xffffffffL >>> (32 - bitCount);
        andMask = ~(andMask << inBit) & 0xffffffffL;
        long bf = ((long) bitField << inBit) & 0xffffffffL;
        for (int i = 0; i < 4; i++) {
            data[inAddr + i] = (byte) ((data[inAddr + i] & (andMask >>> (i * 8))) | (bf >>> (i * 8)));
        }
    }

    private static void applyItanium(byte[] data, int dataSize, long fileOffset) {
        if (dataSize < 21) return;
        int curPos = 0;
        long fo = fileOffset >>> 4;
        int base = 0;
        final int[] masks = {4, 4, 6, 6, 0, 0, 7, 7, 4, 4, 0, 0, 4, 4, 0, 0};
        while (curPos < dataSize - 21) {
            int b = (data[base] & 0x1f) - 0x10;
            if (b >= 0) {
                int cmdMask = masks[b];
                if (cmdMask != 0) {
                    for (int i = 0; i <= 2; i++) {
                        if ((cmdMask & (1 << i)) != 0) {
                            int startPos = i * 41 + 5;
                            int opType = itGetBits(data, base * 8 + startPos + 37, 4);
                            if (opType == 5) {
                                int offset = itGetBits(data, base * 8 + startPos + 13, 20);
                                itSetBits(data, (int) ((offset - fo) & 0xfffff), base * 8 + startPos + 13, 20);
                            }
                        }
                    }
                }
            }
            base += 16;
            curPos += 16;
            fo++;
        }
    }

    // Delta/RGB/Audio write into a second half and return that region.
    private static byte[] applyDelta(byte[] data, int dataSize, int channels) throws IOException {
        if (channels <= 0) throw new IOException("RAR3 delta filter: invalid channels");
        byte[] out = new byte[dataSize];
        int srcPos = 0;
        for (int curChannel = 0; curChannel < channels; curChannel++) {
            int prevByte = 0;
            for (int destPos = curChannel; destPos < dataSize; destPos += channels) {
                prevByte = (prevByte - (data[srcPos++] & 0xff)) & 0xff;
                out[destPos] = (byte) prevByte;
            }
        }
        return out;
    }

    private static byte[] applyRgb(byte[] data, int dataSize, int r0, int posR) throws IOException {
        int width = r0 - 3;
        if (dataSize < 3 || width <= 0 || width > dataSize || posR > 2) {
            throw new IOException("RAR3 RGB filter: invalid params");
        }
        byte[] dest = new byte[dataSize];
        final int channels = 3;
        int src = 0;
        for (int curChannel = 0; curChannel < channels; curChannel++) {
            int prevByte = 0;
            for (int i = curChannel; i < dataSize; i += channels) {
                int predicted;
                if (i >= width + 3) {
                    int upperByte = dest[i - width] & 0xff;
                    int upperLeftByte = dest[i - width - 3] & 0xff;
                    predicted = prevByte + upperByte - upperLeftByte;
                    int pa = Math.abs(predicted - prevByte);
                    int pb = Math.abs(predicted - upperByte);
                    int pc = Math.abs(predicted - upperLeftByte);
                    if (pa <= pb && pa <= pc) predicted = prevByte;
                    else if (pb <= pc) predicted = upperByte;
                    else predicted = upperLeftByte;
                } else {
                    predicted = prevByte;
                }
                prevByte = (predicted - (data[src++] & 0xff)) & 0xff;
                dest[i] = (byte) prevByte;
            }
        }
        for (int i = posR, border = dataSize - 2; i < border; i += 3) {
            int g = dest[i + 1] & 0xff;
            dest[i] = (byte) ((dest[i] & 0xff) + g);
            dest[i + 2] = (byte) ((dest[i + 2] & 0xff) + g);
        }
        return dest;
    }

    private static byte[] applyAudio(byte[] data, int dataSize, int channels) throws IOException {
        if (channels <= 0) throw new IOException("RAR3 audio filter: invalid channels");
        byte[] dest = new byte[dataSize];
        int src = 0;
        for (int curChannel = 0; curChannel < channels; curChannel++) {
            int prevByte = 0, prevDelta = 0;
            int[] dif = new int[7];
            int d1 = 0, d2 = 0, d3;
            int k1 = 0, k2 = 0, k3 = 0;
            for (int i = curChannel, byteCount = 0; i < dataSize; i += channels, byteCount++) {
                d3 = d2;
                d2 = prevDelta - d1;
                d1 = prevDelta;

                int predicted = 8 * prevByte + k1 * d1 + k2 * d2 + k3 * d3;
                predicted = (predicted >> 3) & 0xff;

                int curByte = data[src++] & 0xff;
                predicted = (predicted - curByte) & 0xff;
                dest[i] = (byte) predicted;

                prevDelta = (byte) (predicted - prevByte); // signed char
                prevByte = predicted;

                int d = (byte) curByte;  // signed char
                d = d << 3;

                dif[0] += Math.abs(d);
                dif[1] += Math.abs(d - d1);
                dif[2] += Math.abs(d + d1);
                dif[3] += Math.abs(d - d2);
                dif[4] += Math.abs(d + d2);
                dif[5] += Math.abs(d - d3);
                dif[6] += Math.abs(d + d3);

                if ((byteCount & 0x1f) == 0) {
                    int minDif = dif[0], numMinDif = 0;
                    dif[0] = 0;
                    for (int j = 1; j < dif.length; j++) {
                        if (dif[j] < minDif) { minDif = dif[j]; numMinDif = j; }
                        dif[j] = 0;
                    }
                    switch (numMinDif) {
                        case 1: if (k1 >= -16) k1--; break;
                        case 2: if (k1 < 16) k1++; break;
                        case 3: if (k2 >= -16) k2--; break;
                        case 4: if (k2 < 16) k2++; break;
                        case 5: if (k3 >= -16) k3--; break;
                        case 6: if (k3 < 16) k3++; break;
                    }
                }
            }
        }
        return dest;
    }
}
