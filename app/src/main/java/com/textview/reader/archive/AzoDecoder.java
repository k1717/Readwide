package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Java AZO decoder for EGG method-3 payloads.
 *
 * <p>This file is a modified Java port for TextView Reader based on the
 * zlib-licensed kippler/xunazo AZO decoder. The implementation is intentionally
 * package-private and extraction-only; it is not used to create EGG/AZO data.</p>
 *
 * <p>Original xunazo license notice:</p>
 *
 * <pre>
 * Copyright (C) 2018 kippler@gmail.com
 *
 * This software is provided 'as-is', without any express or implied warranty.
 * In no event will the authors be held liable for any damages arising from the
 * use of this software. Permission is granted to anyone to use this software
 * for any purpose, including commercial applications, and to alter it and
 * redistribute it freely, subject to the following restrictions:
 * 1. The origin of this software must not be misrepresented; you must not claim
 *    that you wrote the original software. If you use this software in a
 *    product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 * </pre>
 */
final class AzoDecoder {
    private static final int MAX_OUTPUT_BYTES = 512 * 1024 * 1024;

    private AzoDecoder() {
    }

    @NonNull
    static byte[] decode(@NonNull byte[] data, long expectedSize) throws IOException {
        if (expectedSize < 0 || expectedSize > MAX_OUTPUT_BYTES) {
            throw unsupported("AZO output size is outside decoder limits");
        }
        IOException framedFailure = null;
        if (looksLikeFramedAzo(data)) {
            try {
                byte[] framed = new FramedDecoder(data, (int) expectedSize).decode();
                if (expectedSize <= 0 || framed.length == expectedSize) return framed;
                throw unsupported("AZO framed output size mismatch");
            } catch (IOException e) {
                framedFailure = e;
            }
        }
        try {
            if (expectedSize == 0 && data.length == 0) return new byte[0];
            return decodeRawBlock(data, (int) expectedSize);
        } catch (IOException rawFailure) {
            if (framedFailure != null) throw framedFailure;
            throw rawFailure;
        }
    }

    private static boolean looksLikeFramedAzo(@NonNull byte[] data) {
        return data.length >= 2 && data[0] == '1' && (data[1] == 0 || data[1] == 1);
    }

    @NonNull
    private static byte[] decodeRawBlock(@NonNull byte[] data, int expectedSize) throws IOException {
        if (expectedSize < 0 || expectedSize > MAX_OUTPUT_BYTES) {
            throw unsupported("AZO raw block output size is outside decoder limits");
        }
        if (expectedSize == data.length) return Arrays.copyOf(data, data.length);
        if (expectedSize == 0) {
            if (data.length == 0) return new byte[0];
            throw unsupported("AZO raw block has data for zero-size output");
        }
        RawBlockDecoder decoder = new RawBlockDecoder(data, expectedSize);
        return decoder.decode();
    }

    @NonNull
    private static IOException unsupported(@NonNull String message) {
        return new ArchiveSupport.UnsupportedArchiveFeatureException(message);
    }

    private static final class FramedDecoder {
        private final byte[] input;
        private final ByteArrayOutputStream output;
        private int readPos;
        private boolean x86Filter;
        private boolean endOfBlocks;
        private int crc;

        FramedDecoder(@NonNull byte[] input, int expectedSize) {
            this.input = input;
            this.output = new ByteArrayOutputStream(Math.max(32, expectedSize));
        }

        @NonNull
        byte[] decode() throws IOException {
            if (readByte() != '1') throw unsupported("Unsupported AZO stream version");
            int filter = readByte();
            if (filter != 0 && filter != 1) throw unsupported("Unsupported AZO filter flag");
            x86Filter = filter == 1;
            while (!endOfBlocks) decodeOneBlock();
            return output.toByteArray();
        }

        private void decodeOneBlock() throws IOException {
            int uncomp = readUInt32BE();
            int comp = readUInt32BE();
            int check = readUInt32BE();
            if (uncomp == 0 && comp == 0 && check == 0) {
                endOfBlocks = true;
                return;
            }
            if (((uncomp ^ comp) != check) && check != 0) {
                throw unsupported("AZO block size check mismatch");
            }
            if (uncomp < 0 || comp < 0 || uncomp > MAX_OUTPUT_BYTES || comp > input.length - readPos) {
                throw unsupported("AZO block size is outside decoder limits");
            }
            byte[] plain;
            if (comp == uncomp) {
                plain = Arrays.copyOfRange(input, readPos, readPos + comp);
                readPos += comp;
            } else {
                byte[] compressed = Arrays.copyOfRange(input, readPos, readPos + comp);
                readPos += comp;
                plain = decodeRawBlock(compressed, uncomp);
            }
            if (x86Filter) applyX86Filter(plain);
            crc = crc32(crc, plain);
            output.write(plain, 0, plain.length);
        }

        private int readByte() throws IOException {
            if (readPos >= input.length) throw unsupported("Truncated AZO stream");
            return input[readPos++] & 0xff;
        }

        private int readUInt32BE() throws IOException {
            int b0 = readByte();
            int b1 = readByte();
            int b2 = readByte();
            int b3 = readByte();
            return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        }
    }

    private static int crc32(int previous, @NonNull byte[] data) {
        CRC32 crc = new CRC32();
        // java.util.zip.CRC32 does not expose a public seed setter. The running
        // CRC is informational in xunazo and EGG block CRC is verified by
        // EggArchiveReader after decoding, so the stream CRC value is not used.
        crc.update(data);
        return (int) crc.getValue();
    }

    private static void applyX86Filter(@NonNull byte[] buf) {
        if (buf.length < 5) return;
        for (int i = 0; i < buf.length - 4; i++) {
            int op = buf[i] & 0xff;
            if (op == 0xe8 || op == 0xe9) {
                int high = buf[i + 4] & 0xff;
                if (high == 0 || high == 0xff) {
                    int val = readIntLE(buf, i + 1) - i;
                    if ((val & 0xff000000) != 0xff000000) {
                        val &= 0x00ffffff;
                    }
                    writeIntLE(buf, i + 1, val);
                }
                i += 4;
            }
        }
    }

    private static int readIntLE(@NonNull byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static void writeIntLE(@NonNull byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static final class RawBlockDecoder {
        private static final int HISTORY_LEN_SHORT = 2;
        private static final int HISTORY_LEN_POS = 16;
        private static final int SHORT_HISTORY_BIT_LEN = 1;
        private static final int LONG_HISTORY_BIT_LEN = 4;
        private static final int DIST_INDEX_BIT_LEN = 7;

        private final byte[] input;
        private final byte[] output;
        private final ArithDecoder arith = new ArithDecoder();
        private final LiteralProb literal = new LiteralProb();
        private final LenProb lenProb = new LenProb();
        private final BoolProb probMatch = new BoolProb();
        private final BoolProb probHistory = new BoolProb();
        private final BoolProb probDistShortHistoryBit = new BoolProb();
        private final BoolProb probLenPosShortHistoryBit = new BoolProb();
        private final Prob[] probDistShortHistoryIndex = createProbArray(1 << SHORT_HISTORY_BIT_LEN, Prob.DEFAULT_BITS);
        private final Prob[] probLenPosShortHistoryIndex = createProbArray(1 << SHORT_HISTORY_BIT_LEN, Prob.DEFAULT_BITS);
        private final Prob[] probLenPosLongHistoryIndex = createProbArray(1 << LONG_HISTORY_BIT_LEN, Prob.DEFAULT_BITS);
        private final Prob[] probDistIndex = createProbArray(1 << DIST_INDEX_BIT_LEN, Prob.DEFAULT_BITS);
        private final History historyShortDist = new History(HISTORY_LEN_SHORT);
        private final History historyShortLen = new History(HISTORY_LEN_SHORT);
        private final History historyLen = new History(HISTORY_LEN_POS);
        private final History historyPos = new History(HISTORY_LEN_POS);
        private final AzoTable table = new AzoTable();
        private int writePos;

        RawBlockDecoder(@NonNull byte[] input, int outputSize) {
            this.input = input;
            this.output = new byte[outputSize];
        }

        @NonNull
        byte[] decode() throws IOException {
            arith.init(input);
            literal.init();
            lenProb.init();
            probMatch.init();
            probHistory.init();
            probDistShortHistoryBit.init();
            probLenPosShortHistoryBit.init();
            for (Prob p : probDistShortHistoryIndex) p.init(Prob.DEFAULT_BITS);
            for (Prob p : probLenPosShortHistoryIndex) p.init(Prob.DEFAULT_BITS);
            for (Prob p : probLenPosLongHistoryIndex) p.init(Prob.DEFAULT_BITS);
            for (Prob p : probDistIndex) p.init(Prob.DEFAULT_BITS);
            for (int i = 0; i < HISTORY_LEN_SHORT; i++) {
                historyShortDist.data[i] = i + AzoTable.MIN_DIST;
                historyShortLen.data[i] = i;
            }
            for (int i = 0; i < HISTORY_LEN_POS; i++) {
                historyLen.data[i] = i + AzoTable.MIN_LEN;
                historyPos.data[i] = 0;
            }
            if (output.length == 0) return output;
            decodeLiteral();
            while (writePos < output.length) {
                int bit0 = arith.decodeBoolState(probMatch);
                if (bit0 == 0) {
                    decodeLiteral();
                } else {
                    int bit1 = arith.decodeBoolState(probHistory);
                    if (bit1 == 1) decodeLenPosHistory();
                    else decodeMatch();
                }
            }
            return output;
        }

        private int peek(int distance) throws IOException {
            if (writePos > distance) return output[writePos - distance - 1] & 0xff;
            if (writePos == 0 && distance == 0) return 0;
            throw unsupported("Invalid AZO back-reference");
        }

        private void write(int value) throws IOException {
            if (writePos >= output.length) throw unsupported("AZO decoder wrote beyond output size");
            output[writePos++] = (byte) value;
        }

        private void decodeLiteral() throws IOException {
            int prevByte = peek(0);
            int literalPosState = prevByte >>> LiteralProb.SHIFT;
            Prob[] prob1 = literal.prob1[prevByte];
            Prob[] prob2 = literal.prob2[literalPosState];
            int symbol;
            if (literal.state[prevByte] >= 0) {
                symbol = arith.decodeTree(prob1, LiteralProb.BIT_LEN, LiteralProb.PROB_BITS, LiteralProb.PROB_MOVE_BITS);
                arith.updateProb(prob2, symbol, LiteralProb.BIT_LEN, LiteralProb.PROB_BITS, LiteralProb.PROB_MOVE_BITS);
            } else {
                symbol = arith.decodeTree(prob2, LiteralProb.BIT_LEN, LiteralProb.PROB_BITS, LiteralProb.PROB_MOVE_BITS);
                arith.updateProb(prob1, symbol, LiteralProb.BIT_LEN, LiteralProb.PROB_BITS, LiteralProb.PROB_MOVE_BITS);
            }
            long[] sums = arith.calcProbSum(prob1, prob2, symbol, LiteralProb.BIT_LEN, LiteralProb.PROB_BITS);
            if (sums[0] > sums[1]) literal.state[prevByte]++;
            else if (sums[0] < sums[1]) literal.state[prevByte]--;
            write(symbol);
        }

        private void decodeMatch() throws IOException {
            int dist = decodeDist();
            int len = decodeLen(dist);
            historyLen.addRecent(len);
            historyPos.addRecent(writePos);
            for (int i = 0; i < len; i++) write(peek(dist - 1));
        }

        private int decodeDist() throws IOException {
            int shortHistoryBit = arith.decodeBoolState(probDistShortHistoryBit);
            int dist;
            if (shortHistoryBit != 0) {
                int index = arith.decodeTree(probDistShortHistoryIndex, SHORT_HISTORY_BIT_LEN,
                        Prob.DEFAULT_BITS, Prob.DEFAULT_SHIFT);
                dist = historyShortDist.getDataAndUpdate(index);
            } else {
                int distIndex = arith.decodeTree(probDistIndex, DIST_INDEX_BIT_LEN,
                        Prob.DEFAULT_BITS, Prob.DEFAULT_SHIFT);
                int[] extraBits = new int[1];
                dist = table.distIndex2Dist(distIndex, extraBits);
                if (extraBits[0] != 0) {
                    dist += arith.decodeDirect(extraBits[0]);
                }
                historyShortDist.addRecent(dist);
            }
            return dist;
        }

        private int decodeLen(int dist) throws IOException {
            int distIndex = table.getDistIndex(dist);
            int lenIndex = decodeLenIndex(distIndex);
            int[] extraBits = new int[1];
            int len = table.lenIndex2Len(lenIndex, extraBits);
            if (extraBits[0] != 0) {
                len += arith.decodeDirect(extraBits[0]);
            }
            return len;
        }

        private int decodeLenIndex(int distIndex) throws IOException {
            Prob[] prob1 = lenProb.prob1[distIndex];
            Prob[] prob2 = lenProb.prob2[distIndex >>> LenProb.SHIFT];
            int lenIndex;
            if (lenProb.state[distIndex] >= 0) {
                lenIndex = arith.decodeTree(prob1, LenProb.BIT_LEN, LenProb.PROB_BITS, LenProb.PROB_MOVE_BITS);
                arith.updateProb(prob2, lenIndex, LenProb.BIT_LEN, LenProb.PROB_BITS, LenProb.PROB_MOVE_BITS);
            } else {
                lenIndex = arith.decodeTree(prob2, LenProb.BIT_LEN, LenProb.PROB_BITS, LenProb.PROB_MOVE_BITS);
                arith.updateProb(prob1, lenIndex, LenProb.BIT_LEN, LenProb.PROB_BITS, LenProb.PROB_MOVE_BITS);
            }
            long[] sums = arith.calcProbSum(prob1, prob2, lenIndex, LenProb.BIT_LEN, LenProb.PROB_BITS);
            if (sums[0] > sums[1]) lenProb.state[distIndex]++;
            else if (sums[0] < sums[1]) lenProb.state[distIndex]--;
            return lenIndex;
        }

        private void decodeLenPosHistory() throws IOException {
            int shortHistoryBit = arith.decodeBoolState(probLenPosShortHistoryBit);
            int historyIndex;
            if (shortHistoryBit != 0) {
                int lenShortIndex = arith.decodeTree(probLenPosShortHistoryIndex, SHORT_HISTORY_BIT_LEN,
                        Prob.DEFAULT_BITS, Prob.DEFAULT_SHIFT);
                historyIndex = historyShortLen.getDataAndUpdate(lenShortIndex);
            } else {
                historyIndex = arith.decodeTree(probLenPosLongHistoryIndex, LONG_HISTORY_BIT_LEN,
                        Prob.DEFAULT_BITS, Prob.DEFAULT_SHIFT);
                historyShortLen.addRecent(historyIndex);
            }
            int len = historyLen.getDataAndUpdate(historyIndex);
            int pos = historyPos.getDataAndUpdate(historyIndex);
            int dist = writePos - pos;
            for (int i = 0; i < len; i++) write(peek(dist - 1));
        }
    }

    private static final class Prob {
        static final int DEFAULT_BITS = 10;
        static final int DEFAULT_SHIFT = 4;
        int prob;

        void init(int probBits) {
            prob = 1 << (probBits - 1);
        }
    }

    private static final class BoolProb {
        static final int STATES = 256;
        static final int PROB_BITS = 12;
        static final int PROB_SHIFT_BITS = 6;
        final Prob[] prob = createProbArray(STATES, PROB_BITS);
        int boolState;

        void init() {
            for (Prob p : prob) p.init(PROB_BITS);
            boolState = 0;
        }
    }

    private static final class LiteralProb {
        static final int PREV_STATE = 256;
        static final int BIT_LEN = 8;
        static final int SIZE = 1 << BIT_LEN;
        static final int SHIFT = 5;
        static final int PROB_BITS = 10;
        static final int PROB_MOVE_BITS = 4;
        final Prob[][] prob1 = create2D(PREV_STATE, SIZE, PROB_BITS);
        final Prob[][] prob2 = create2D(PREV_STATE >>> SHIFT, SIZE, PROB_BITS);
        final int[] state = new int[SIZE];

        void init() {
            Arrays.fill(state, 0);
            init2D(prob1, PROB_BITS);
            init2D(prob2, PROB_BITS);
        }
    }

    private static final class LenProb {
        static final int PREV_STATE = 128;
        static final int BIT_LEN = 7;
        static final int SIZE = 1 << BIT_LEN;
        static final int SHIFT = 4;
        static final int PROB_BITS = 10;
        static final int PROB_MOVE_BITS = 4;
        final Prob[][] prob1 = create2D(PREV_STATE, SIZE, PROB_BITS);
        final Prob[][] prob2 = create2D(PREV_STATE >>> SHIFT, SIZE, PROB_BITS);
        final int[] state = new int[SIZE];

        void init() {
            Arrays.fill(state, 0);
            init2D(prob1, PROB_BITS);
            init2D(prob2, PROB_BITS);
        }
    }

    @NonNull
    private static Prob[] createProbArray(int size, int probBits) {
        Prob[] result = new Prob[size];
        for (int i = 0; i < size; i++) {
            result[i] = new Prob();
            result[i].init(probBits);
        }
        return result;
    }

    @NonNull
    private static Prob[][] create2D(int rows, int cols, int probBits) {
        Prob[][] result = new Prob[rows][cols];
        for (int r = 0; r < rows; r++) result[r] = createProbArray(cols, probBits);
        return result;
    }

    private static void init2D(@NonNull Prob[][] table, int probBits) {
        for (Prob[] row : table) for (Prob p : row) p.init(probBits);
    }

    private static final class History {
        final int[] data;

        History(int size) {
            data = new int[size];
        }

        void addRecent(int value) {
            for (int i = data.length - 1; i > 0; i--) data[i] = data[i - 1];
            data[0] = value;
        }

        int getDataAndUpdate(int index) throws IOException {
            if (index < 0 || index >= data.length) throw unsupported("Invalid AZO history index");
            int result = data[index];
            for (int i = index; i > 0; i--) data[i] = data[i - 1];
            data[0] = result;
            return result;
        }
    }

    private static final class AzoTable {
        static final int MIN_DIST = 1;
        static final int MIN_LEN = 2;
        static final int MAX_TABLE = 128;
        final int[] lenTable = new int[MAX_TABLE];
        final int[] distTable = new int[MAX_TABLE];
        final int[] lenExtraBits = new int[MAX_TABLE];
        final int[] distExtraBits = new int[MAX_TABLE];

        AzoTable() {
            for (int i = 0; i < 40; i++) {
                lenTable[i] = i + MIN_LEN;
                lenExtraBits[i] = 0;
            }
            for (int i = 40; i < MAX_TABLE; i++) {
                int bit = (i - 40) / 8 + 1;
                lenExtraBits[i] = bit;
                lenTable[i] = lenTable[i - 1] + (1 << lenExtraBits[i - 1]);
            }
            for (int i = 0; i < 20; i++) {
                distTable[i] = i + MIN_DIST;
                distExtraBits[i] = 0;
            }
            for (int i = 20; i < MAX_TABLE; i++) {
                int bit = (i - 20) / 4 + 1;
                distExtraBits[i] = bit;
                distTable[i] = distTable[i - 1] + (1 << distExtraBits[i - 1]);
            }
        }

        int lenIndex2Len(int index, @NonNull int[] extraBits) throws IOException {
            if (index < 0 || index >= MAX_TABLE) throw unsupported("Invalid AZO length index");
            extraBits[0] = lenExtraBits[index];
            return lenTable[index];
        }

        int distIndex2Dist(int index, @NonNull int[] extraBits) throws IOException {
            if (index < 0 || index >= MAX_TABLE) throw unsupported("Invalid AZO distance index");
            extraBits[0] = distExtraBits[index];
            return distTable[index];
        }

        int getDistIndex(int dist) throws IOException {
            if (dist < MIN_DIST) throw unsupported("Invalid AZO distance");
            int distMinusOne = dist - MIN_DIST;
            if (distMinusOne <= 20) return distMinusOne;
            int shift = 0;
            int temp = ((dist - 1 - 16) / 4 + 1) >>> 1;
            while (temp != 0) {
                temp >>>= 1;
                shift++;
            }
            int index = 16 + (shift * 4) + ((dist - 1 - 16) - ((1 << shift) - 1) * 4) / (1 << shift);
            if (index < 0 || index >= MAX_TABLE) throw unsupported("Invalid AZO computed distance index");
            return index;
        }
    }

    private static final class ArithDecoder {
        private static final int STATE_SIZE = 32;
        private static final long MASK = 0xffffffffL;
        private static final long TOP_MASK = 0x80000000L;
        private static final long SECOND_MASK = 0x40000000L;
        private byte[] buffer;
        private int cursor;
        private int currentByte;
        private int remainingBits;
        private boolean readError;
        private long code;
        private long low;
        private long high;

        void init(@NonNull byte[] input) {
            buffer = input;
            cursor = 0;
            currentByte = 0;
            remainingBits = 0;
            readError = false;
            low = 0;
            high = MASK;
            code = 0;
            for (int i = 0; i < STATE_SIZE; i++) {
                code = ((code << 1) | readCodeBit()) & MASK;
            }
        }

        int decodeDirect(int bitsLen) throws IOException {
            if (bitsLen <= 0) return 0;
            long range = (high - low + 1) >>> bitsLen;
            if (range == 0) throw unsupported("Invalid AZO direct range");
            long symbol = (code - low) / range;
            high = low + range * (symbol + 1) - 1;
            low = low + range * symbol;
            update();
            if (readError) throw unsupported("Truncated AZO arithmetic stream");
            if (symbol > Integer.MAX_VALUE) throw unsupported("AZO direct symbol too large");
            return (int) symbol;
        }

        int decodeBoolState(@NonNull BoolProb state) throws IOException {
            int bit = decodeBit(state.prob[state.boolState], BoolProb.PROB_BITS, BoolProb.PROB_SHIFT_BITS);
            state.boolState = ((state.boolState << 1) & (BoolProb.STATES - 1)) + bit;
            return bit;
        }

        int decodeTree(@NonNull Prob[] prob, int bitsLen, int probBits, int probMoveBits) throws IOException {
            int symbol = 1;
            for (int i = 0; i < bitsLen; i++) {
                int bit = decodeBit(prob[symbol], probBits, probMoveBits);
                symbol = (symbol << 1) + bit;
            }
            return symbol - (1 << bitsLen);
        }

        void updateProb(@NonNull Prob[] prob, int decodedSymbol, int bitsLen, int probBits, int probMoveBits) {
            int symbol = 1;
            int msb = 1 << (bitsLen - 1);
            for (int i = 0; i < bitsLen; i++) {
                int bit = (decodedSymbol & msb) != 0 ? 1 : 0;
                decodedSymbol <<= 1;
                Prob p = prob[symbol];
                if (bit == 0) p.prob += ((1 << probBits) - p.prob) / (1 << probMoveBits);
                else p.prob -= p.prob / (1 << probMoveBits);
                symbol = (symbol << 1) + bit;
            }
        }

        @NonNull
        long[] calcProbSum(@NonNull Prob[] prob1, @NonNull Prob[] prob2, int symbolValue,
                           int bitsLen, int probBits) {
            int prev = 1;
            int msb = 1 << (bitsLen - 1);
            int maxProb = 1 << probBits;
            long total1 = 1;
            long total2 = 1;
            for (int i = 0; i < bitsLen; i++) {
                int p1 = prob1[prev].prob;
                int p2 = prob2[prev].prob;
                int bit = (symbolValue & msb) != 0 ? 1 : 0;
                symbolValue <<= 1;
                if (((total1 | total2) & 0xffc00000L) != 0) {
                    total1 >>= probBits;
                    total2 >>= probBits;
                }
                total1 *= bit != 0 ? (maxProb - p1) : p1;
                total2 *= bit != 0 ? (maxProb - p2) : p2;
                prev = (prev << 1) + bit;
            }
            return new long[]{total1, total2};
        }

        private int decodeBit(@NonNull Prob prob, int probBits, int probMoveBits) throws IOException {
            long range = high - low + 1;
            long unit = range >>> probBits;
            if (unit == 0) throw unsupported("Invalid AZO bit range");
            long bound = (code - low) / unit;
            int bit;
            if (bound < prob.prob) {
                high = low + unit * prob.prob - 1;
                bit = 0;
                prob.prob += ((1 << probBits) - prob.prob) / (1 << probMoveBits);
            } else {
                low = low + unit * prob.prob;
                bit = 1;
                prob.prob -= prob.prob / (1 << probMoveBits);
            }
            update();
            if (readError) throw unsupported("Truncated AZO arithmetic stream");
            return bit;
        }

        private void update() {
            while (((low ^ high) & TOP_MASK) == 0) {
                shift();
                low = (low << 1) & MASK;
                high = ((high << 1) & MASK) | 1;
            }
            while ((low & ~high & SECOND_MASK) != 0) {
                underflow();
                low = (low << 1) & (MASK >>> 1);
                high = ((high << 1) & (MASK >>> 1)) | TOP_MASK | 1;
            }
        }

        private void shift() {
            code = ((code << 1) & MASK) | readCodeBit();
        }

        private void underflow() {
            code = (code & TOP_MASK) | ((code << 1) & (MASK >>> 1)) | readCodeBit();
        }

        private int readCodeBit() {
            if (remainingBits == 0) {
                if (cursor < buffer.length) {
                    currentByte = buffer[cursor++] & 0xff;
                    remainingBits = 8;
                } else {
                    if (code == 0) readError = true;
                    return 0;
                }
            }
            remainingBits--;
            return (currentByte >>> remainingBits) & 1;
        }
    }
}
