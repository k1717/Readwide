package com.readwide.manager.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
        long fileOffset; // output position within the current unpacked file
        int blockLength;
        final int[] initR = new int[7];
    }

    /** Persistent program slots used by consecutive VM records (and solid entries). */
    static final class ProgramState {
        private static final int MAX_VM_CODE_SIZE = 0x10000;
        private static final int MAX_USER_GLOBAL_SIZE = 0x1fc0;
        private static final int PROGRAM_SYSTEM_GLOBAL_ADDRESS = 0x3c000;

        private final List<Program> programs = new ArrayList<>();
        private int lastProgramNumber;

        static final class Parsed {
            final PendingFilter filter;
            final boolean resetPendingFilters;

            Parsed(PendingFilter filter, boolean resetPendingFilters) {
                this.filter = filter;
                this.resetPendingFilters = resetPendingFilters;
            }
        }

        private static final class Program {
            final StandardFilter type;
            int oldBlockLength;
            int usageCount;

            Program(StandardFilter type) {
                this.type = type;
            }
        }

        Parsed parse(int flags, byte[] code, long currentOutput, int dictionarySize)
                throws IOException {
            VmCodeReader reader = new VmCodeReader(code);
            boolean resetPending = false;
            int number;
            if ((flags & 0x80) != 0) {
                long encoded = reader.readData();
                if (encoded == 0) {
                    programs.clear();
                    resetPending = true;
                    number = 0;
                } else {
                    encoded--;
                    if (encoded > Integer.MAX_VALUE) throw invalid("program number");
                    number = (int) encoded;
                }
                if (number > programs.size()) throw invalid("program number");
                lastProgramNumber = number;
            } else {
                number = lastProgramNumber;
                if (number > programs.size()) throw invalid("remembered program number");
            }

            Program program = number < programs.size() ? programs.get(number) : null;
            if (program != null && program.usageCount < Integer.MAX_VALUE) program.usageCount++;

            long relativeStart = reader.readData();
            if ((flags & 0x40) != 0) relativeStart += 258L;
            if (relativeStart < 0 || currentOutput > Long.MAX_VALUE - relativeStart) {
                throw invalid("block start");
            }
            long blockStart = currentOutput + relativeStart;

            long encodedLength = (flags & 0x20) != 0
                    ? reader.readData() : program == null ? 0L : program.oldBlockLength;
            if (encodedLength <= 0 || encodedLength > dictionarySize || encodedLength > Integer.MAX_VALUE) {
                throw invalid("block length");
            }
            int blockLength = (int) encodedLength;

            PendingFilter pending = new PendingFilter();
            pending.blockStartAbs = blockStart;
            pending.fileOffset = blockStart;
            pending.blockLength = blockLength;
            pending.initR[3] = PROGRAM_SYSTEM_GLOBAL_ADDRESS;
            pending.initR[4] = blockLength;
            pending.initR[5] = program == null ? 0 : program.usageCount;

            if ((flags & 0x10) != 0) {
                int initMask = reader.readBits(7);
                for (int i = 0; i < 7; i++) {
                    if ((initMask & (1 << i)) != 0) pending.initR[i] = (int) reader.readData();
                }
            }

            if (program == null) {
                long encodedCodeSize = reader.readData();
                if (encodedCodeSize <= 0 || encodedCodeSize > MAX_VM_CODE_SIZE) {
                    throw invalid("program size");
                }
                byte[] inner = new byte[(int) encodedCodeSize];
                for (int i = 0; i < inner.length; i++) inner[i] = (byte) reader.readBits(8);
                StandardFilter type = identify(inner);
                if (type == StandardFilter.NONE) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "RAR3 uses a non-standard VM filter program");
                }
                program = new Program(type);
                programs.add(program);
            }
            program.oldBlockLength = blockLength;
            pending.type = program.type;

            if ((flags & 0x08) != 0) {
                long globalLength = reader.readData();
                if (globalLength > MAX_USER_GLOBAL_SIZE) throw invalid("global data length");
                for (long i = 0; i < globalLength; i++) reader.readBits(8);
                // Standard filters do not consume user global data. Reading and validating it
                // still preserves the VM-record grammar and rejects truncated records.
            }
            reader.requireNotPastEnd();
            return new Parsed(pending, resetPending);
        }

        void reset() {
            programs.clear();
            lastProgramNumber = 0;
        }

        int programCount() {
            return programs.size();
        }

        private static IOException invalid(String field) {
            return new IOException("Invalid RAR3 VM filter " + field);
        }
    }

    /** MSB-first reader for the compact integer grammar used inside RAR VM records. */
    private static final class VmCodeReader {
        private final byte[] data;
        private int bitPosition;

        VmCodeReader(byte[] data) {
            this.data = data;
        }

        private int peek16() {
            int address = bitPosition >>> 3;
            int bit = bitPosition & 7;
            int field = ((address < data.length ? data[address] & 0xff : 0) << 16)
                    | ((address + 1 < data.length ? data[address + 1] & 0xff : 0) << 8)
                    | (address + 2 < data.length ? data[address + 2] & 0xff : 0);
            return (field >>> (8 - bit)) & 0xffff;
        }

        int readBits(int count) throws IOException {
            if (count < 0 || count > 16 || bitPosition > data.length * 8 - count) {
                throw ProgramState.invalid("record bit range");
            }
            int value = peek16() >>> (16 - count);
            bitPosition += count;
            return value;
        }

        long readData() throws IOException {
            int prefix = peek16();
            switch (prefix & 0xc000) {
                case 0:
                    return readBits(6) & 0xfL;
                case 0x4000:
                    if ((prefix & 0x3c00) == 0) {
                        return (0xffffff00L | (readBits(14) & 0xffL)) & 0xffffffffL;
                    }
                    return readBits(10) & 0xffL;
                case 0x8000:
                    readBits(2);
                    return readBits(16) & 0xffffL;
                default:
                    readBits(2);
                    return ((long) readBits(16) << 16) | (readBits(16) & 0xffffL);
            }
        }

        void requireNotPastEnd() throws IOException {
            if (bitPosition > data.length * 8) throw ProgramState.invalid("record length");
        }
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
