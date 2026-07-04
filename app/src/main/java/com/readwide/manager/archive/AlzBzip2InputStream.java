/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * This file is a MODIFIED copy of Apache Commons Compress 1.28.0
 * org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
 * (which is itself based on work by Keiron Liddle, Aftex Software), adapted
 * for the trimmed bzip2 bitstream variant used inside ALZip .alz archives.
 *
 * Modifications (see docs/ALZ_FORMAT_NOTES.md; the bitstream facts follow the
 * zlib-licensed unalz reference decoder by kippler@gmail.com,
 * http://www.kipple.pe.kr, UnAlzBz2decompress.c):
 *  - no "BZh<level>" stream magic and no block-size byte; the block size is
 *    implicitly 900k,
 *  - the 48-bit block magic is replaced by the 32 bits 'D','L','Z',0x01 and
 *    the 48-bit end-of-stream magic by 'D','L','Z',0x02,
 *  - the 32-bit stored block CRC and the 1-bit "randomised" flag are absent
 *    (so the randomised code paths and CRC bookkeeping are removed; the ALZ
 *    container stores a per-entry CRC32 that the caller verifies instead),
 *  - the 32-bit combined CRC after the end-of-stream magic is absent, and
 *  - concatenated-stream support and the commons-compress base classes are
 *    dropped; this is a plain read-only java.io.InputStream.
 */
package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Decodes the modified bzip2 bitstream that ALZip 4.x writes into .alz
 * archives (compression method 1). Standard bzip2 decoders cannot read these
 * payloads: the block framing bytes differ and the per-block CRC and
 * randomisation fields are omitted entirely, shifting every subsequent bit.
 */
final class AlzBzip2InputStream extends InputStream {

    private static final int BASE_BLOCK_SIZE = 100000;
    private static final int BLOCK_SIZE_100K = 9; // fixed 900k in the ALZ variant
    private static final int MAX_ALPHA_SIZE = 258;
    private static final int MAX_CODE_LEN = 23;
    private static final int N_GROUPS = 6;
    private static final int G_SIZE = 50;
    private static final int MAX_SELECTORS = 2 + 900000 / G_SIZE;
    private static final int RUNA = 0;
    private static final int RUNB = 1;

    private static final int EOF = 0;
    private static final int START_BLOCK_STATE = 1;
    private static final int NO_RAND_PART_A_STATE = 5;
    private static final int NO_RAND_PART_B_STATE = 6;
    private static final int NO_RAND_PART_C_STATE = 7;

    private static final class Data {
        final boolean[] inUse = new boolean[256];
        final byte[] seqToUnseq = new byte[256];
        final byte[] selector = new byte[MAX_SELECTORS];
        final byte[] selectorMtf = new byte[MAX_SELECTORS];
        final int[] unzftab = new int[256];
        final int[][] limit = new int[N_GROUPS][MAX_ALPHA_SIZE];
        final int[][] base = new int[N_GROUPS][MAX_ALPHA_SIZE];
        final int[][] perm = new int[N_GROUPS][MAX_ALPHA_SIZE];
        final int[] minLens = new int[N_GROUPS];
        final int[] cftab = new int[257];
        final char[] getAndMoveToFrontDecode_yy = new char[256];
        final char[][] temp_charArray2d = new char[N_GROUPS][MAX_ALPHA_SIZE];
        final byte[] recvDecodingTables_pos = new byte[N_GROUPS];
        int[] tt;
        final byte[] ll8 = new byte[BLOCK_SIZE_100K * BASE_BLOCK_SIZE];

        int[] initTT(final int length) {
            int[] ttShadow = this.tt;
            if (ttShadow == null || ttShadow.length < length) {
                this.tt = ttShadow = new int[length];
            }
            return ttShadow;
        }
    }

    /** Minimal MSB-first bit reader over the underlying stream. */
    private static final class BitReader {
        private final InputStream in;
        private long bitCache;
        private int bitCount;

        BitReader(@NonNull InputStream in) {
            this.in = in;
        }

        /** Reads {@code n} (1..24) bits; throws at end of stream. */
        int readBits(final int n) throws IOException {
            while (bitCount < n) {
                final int b = in.read();
                if (b < 0) throw new IOException("Unexpected end of stream");
                bitCache = bitCache << 8 | b & 0xff;
                bitCount += 8;
            }
            final int result = (int) (bitCache >>> bitCount - n) & (1 << n) - 1;
            bitCount -= n;
            return result;
        }
    }

    private int last;
    private int origPtr;
    private int nInUse;
    private BitReader bin;
    private InputStream source;
    private int currentState = START_BLOCK_STATE;
    private int su_count;
    private int su_ch2;
    private int su_chPrev;
    private int su_i2;
    private int su_j2;
    private int su_tPos;
    private char su_z;
    private Data data;

    AlzBzip2InputStream(@NonNull final InputStream in) throws IOException {
        this.source = in;
        this.bin = new BitReader(in);
        initBlock();
    }

    @Override
    public void close() throws IOException {
        final InputStream inShadow = this.source;
        if (inShadow != null) {
            try {
                inShadow.close();
            } finally {
                this.data = null;
                this.bin = null;
                this.source = null;
            }
        }
    }

    private static boolean bsGetBit(final BitReader bin) throws IOException {
        return bin.readBits(1) != 0;
    }

    private static char bsGetUByte(final BitReader bin) throws IOException {
        return (char) bin.readBits(8);
    }

    private static void checkBounds(final int checkVal, final int limitExclusive, final String name) throws IOException {
        if (checkVal < 0) {
            throw new IOException("Corrupted input, " + name + " value negative");
        }
        if (checkVal >= limitExclusive) {
            throw new IOException("Corrupted input, " + name + " value too big");
        }
    }

    private static void hbCreateDecodeTables(final int[] limit, final int[] base, final int[] perm, final char[] length, final int minLen, final int maxLen,
            final int alphaSize) throws IOException {
        for (int i = minLen, pp = 0; i <= maxLen; i++) {
            for (int j = 0; j < alphaSize; j++) {
                if (length[j] == i) {
                    perm[pp++] = j;
                }
            }
        }

        for (int i = MAX_CODE_LEN; --i > 0;) {
            base[i] = 0;
            limit[i] = 0;
        }

        for (int i = 0; i < alphaSize; i++) {
            final int l = length[i];
            checkBounds(l, MAX_ALPHA_SIZE, "length");
            base[l + 1]++;
        }

        for (int i = 1, b = base[0]; i < MAX_CODE_LEN; i++) {
            b += base[i];
            base[i] = b;
        }

        for (int i = minLen, vec = 0, b = base[i]; i <= maxLen; i++) {
            final int nb = base[i + 1];
            vec += nb - b;
            b = nb;
            limit[i] = vec - 1;
            vec <<= 1;
        }

        for (int i = minLen + 1; i <= maxLen; i++) {
            base[i] = (limit[i - 1] + 1 << 1) - base[i];
        }
    }

    private void createHuffmanDecodingTables(final int alphaSize, final int nGroups) throws IOException {
        final Data dataShadow = this.data;
        final char[][] len = dataShadow.temp_charArray2d;
        final int[] minLens = dataShadow.minLens;
        final int[][] limit = dataShadow.limit;
        final int[][] base = dataShadow.base;
        final int[][] perm = dataShadow.perm;

        for (int t = 0; t < nGroups; t++) {
            int minLen = 32;
            int maxLen = 0;
            final char[] len_t = len[t];
            for (int i = alphaSize; --i >= 0;) {
                final char lent = len_t[i];
                if (lent > maxLen) {
                    maxLen = lent;
                }
                if (lent < minLen) {
                    minLen = lent;
                }
            }
            hbCreateDecodeTables(limit[t], base[t], perm[t], len[t], minLen, maxLen, alphaSize);
            minLens[t] = minLen;
        }
    }

    private void getAndMoveToFrontDecode() throws IOException {
        final BitReader bin = this.bin;
        this.origPtr = bin.readBits(24);
        recvDecodingTables();
        final Data dataShadow = this.data;
        final byte[] ll8 = dataShadow.ll8;
        final int[] unzftab = dataShadow.unzftab;
        final byte[] selector = dataShadow.selector;
        final byte[] seqToUnseq = dataShadow.seqToUnseq;
        final char[] yy = dataShadow.getAndMoveToFrontDecode_yy;
        final int[] minLens = dataShadow.minLens;
        final int[][] limit = dataShadow.limit;
        final int[][] base = dataShadow.base;
        final int[][] perm = dataShadow.perm;
        final int limitLast = BLOCK_SIZE_100K * 100000;

        for (int i = 256; --i >= 0;) {
            yy[i] = (char) i;
            unzftab[i] = 0;
        }

        int groupNo = 0;
        int groupPos = G_SIZE - 1;
        final int eob = this.nInUse + 1;
        int nextSym = getAndMoveToFrontDecode0();
        int lastShadow = -1;
        int zt = selector[groupNo] & 0xff;
        checkBounds(zt, N_GROUPS, "zt");
        int[] base_zt = base[zt];
        int[] limit_zt = limit[zt];
        int[] perm_zt = perm[zt];
        int minLens_zt = minLens[zt];

        while (nextSym != eob) {
            if (nextSym == RUNA || nextSym == RUNB) {
                int s = -1;

                for (int n = 1; true; n <<= 1) {
                    if (nextSym == RUNA) {
                        s += n;
                    } else if (nextSym == RUNB) {
                        s += n << 1;
                    } else {
                        break;
                    }

                    if (groupPos == 0) {
                        groupPos = G_SIZE - 1;
                        checkBounds(++groupNo, MAX_SELECTORS, "groupNo");
                        zt = selector[groupNo] & 0xff;
                        checkBounds(zt, N_GROUPS, "zt");
                        base_zt = base[zt];
                        limit_zt = limit[zt];
                        perm_zt = perm[zt];
                        minLens_zt = minLens[zt];
                    } else {
                        groupPos--;
                    }

                    int zn = minLens_zt;
                    checkBounds(zn, MAX_ALPHA_SIZE, "zn");
                    int zvec = bin.readBits(zn);
                    while (zvec > limit_zt[zn]) {
                        checkBounds(++zn, MAX_ALPHA_SIZE, "zn");
                        zvec = zvec << 1 | bin.readBits(1);
                    }
                    final int tmp = zvec - base_zt[zn];
                    checkBounds(tmp, MAX_ALPHA_SIZE, "zvec");
                    nextSym = perm_zt[tmp];
                }
                checkBounds(s, this.data.ll8.length, "s");

                final int yy0 = yy[0];
                checkBounds(yy0, 256, "yy");
                final byte ch = seqToUnseq[yy0];
                unzftab[ch & 0xff] += s + 1;

                final int from = ++lastShadow;
                lastShadow += s;
                checkBounds(lastShadow, this.data.ll8.length, "lastShadow");
                Arrays.fill(ll8, from, lastShadow + 1, ch);

                if (lastShadow >= limitLast) {
                    throw new IOException("Block overrun while expanding RLE in MTF, " + lastShadow + " exceeds " + limitLast);
                }
            } else {
                if (++lastShadow >= limitLast) {
                    throw new IOException("Block overrun in MTF, " + lastShadow + " exceeds " + limitLast);
                }
                checkBounds(nextSym, 256 + 1, "nextSym");

                final char tmp = yy[nextSym - 1];
                checkBounds(tmp, 256, "yy");
                unzftab[seqToUnseq[tmp] & 0xff]++;
                ll8[lastShadow] = seqToUnseq[tmp];

                if (nextSym <= 16) {
                    for (int j = nextSym - 1; j > 0;) {
                        yy[j] = yy[--j];
                    }
                } else {
                    System.arraycopy(yy, 0, yy, 1, nextSym - 1);
                }

                yy[0] = tmp;

                if (groupPos == 0) {
                    groupPos = G_SIZE - 1;
                    checkBounds(++groupNo, MAX_SELECTORS, "groupNo");
                    zt = selector[groupNo] & 0xff;
                    checkBounds(zt, N_GROUPS, "zt");
                    base_zt = base[zt];
                    limit_zt = limit[zt];
                    perm_zt = perm[zt];
                    minLens_zt = minLens[zt];
                } else {
                    groupPos--;
                }

                int zn = minLens_zt;
                checkBounds(zn, MAX_ALPHA_SIZE, "zn");
                int zvec = bin.readBits(zn);
                while (zvec > limit_zt[zn]) {
                    checkBounds(++zn, MAX_ALPHA_SIZE, "zn");
                    zvec = zvec << 1 | bin.readBits(1);
                }
                final int idx = zvec - base_zt[zn];
                checkBounds(idx, MAX_ALPHA_SIZE, "zvec");
                nextSym = perm_zt[idx];
            }
        }

        this.last = lastShadow;
    }

    private int getAndMoveToFrontDecode0() throws IOException {
        final Data dataShadow = this.data;
        final int zt = dataShadow.selector[0] & 0xff;
        checkBounds(zt, N_GROUPS, "zt");
        final int[] limit_zt = dataShadow.limit[zt];
        int zn = dataShadow.minLens[zt];
        checkBounds(zn, MAX_ALPHA_SIZE, "zn");
        int zvec = bin.readBits(zn);
        while (zvec > limit_zt[zn]) {
            checkBounds(++zn, MAX_ALPHA_SIZE, "zn");
            zvec = zvec << 1 | bin.readBits(1);
        }
        final int tmp = zvec - dataShadow.base[zt][zn];
        checkBounds(tmp, MAX_ALPHA_SIZE, "zvec");

        return dataShadow.perm[zt][tmp];
    }

    /**
     * Reads the 4-byte ALZ block framing: 'D','L','Z' followed by 0x01 for a
     * data block or 0x02 for end of stream (no combined CRC follows).
     */
    private void initBlock() throws IOException {
        final BitReader bin = this.bin;
        final char magic0 = bsGetUByte(bin);
        final char magic1 = bsGetUByte(bin);
        final char magic2 = bsGetUByte(bin);
        final char magic3 = bsGetUByte(bin);

        if (magic0 != 'D' || magic1 != 'L' || magic2 != 'Z') {
            this.currentState = EOF;
            throw new IOException("Bad ALZ bzip2 block header");
        }
        if (magic3 == 0x02) {
            // End of stream. The ALZ variant stores no combined CRC.
            this.currentState = EOF;
            this.data = null;
            return;
        }
        if (magic3 != 0x01) {
            this.currentState = EOF;
            throw new IOException("Bad ALZ bzip2 block header");
        }

        // The ALZ variant carries no stored block CRC and no randomised bit.
        if (this.data == null) {
            this.data = new Data();
        }

        getAndMoveToFrontDecode();
        this.currentState = START_BLOCK_STATE;
    }

    private void makeMaps() {
        final boolean[] inUse = this.data.inUse;
        final byte[] seqToUnseq = this.data.seqToUnseq;

        int nInUseShadow = 0;

        for (int i = 0; i < 256; i++) {
            if (inUse[i]) {
                seqToUnseq[nInUseShadow++] = (byte) i;
            }
        }

        this.nInUse = nInUseShadow;
    }

    @Override
    public int read() throws IOException {
        if (this.bin != null) {
            return read0();
        }
        throw new IOException("Stream closed");
    }

    @Override
    public int read(final byte[] dest, final int offs, final int len) throws IOException {
        if (offs < 0) {
            throw new IndexOutOfBoundsException("offs(" + offs + ") < 0.");
        }
        if (len < 0) {
            throw new IndexOutOfBoundsException("len(" + len + ") < 0.");
        }
        if (offs + len > dest.length) {
            throw new IndexOutOfBoundsException("offs(" + offs + ") + len(" + len + ") > dest.length(" + dest.length + ").");
        }
        if (this.bin == null) {
            throw new IOException("Stream closed");
        }
        if (len == 0) {
            return 0;
        }

        final int hi = offs + len;
        int destOffs = offs;
        int b;
        while (destOffs < hi && (b = read0()) >= 0) {
            dest[destOffs++] = (byte) b;
        }

        return destOffs == offs ? -1 : destOffs - offs;
    }

    private int read0() throws IOException {
        switch (currentState) {
        case EOF:
            return -1;

        case START_BLOCK_STATE:
            return setupBlock();

        case NO_RAND_PART_A_STATE:
            throw new IllegalStateException();

        case NO_RAND_PART_B_STATE:
            return setupNoRandPartB();

        case NO_RAND_PART_C_STATE:
            return setupNoRandPartC();

        default:
            throw new IllegalStateException();
        }
    }

    private void recvDecodingTables() throws IOException {
        final BitReader bin = this.bin;
        final Data dataShadow = this.data;
        final boolean[] inUse = dataShadow.inUse;
        final byte[] pos = dataShadow.recvDecodingTables_pos;
        final byte[] selector = dataShadow.selector;
        final byte[] selectorMtf = dataShadow.selectorMtf;

        int inUse16 = 0;

        /* Receive the mapping table */
        for (int i = 0; i < 16; i++) {
            if (bsGetBit(bin)) {
                inUse16 |= 1 << i;
            }
        }

        Arrays.fill(inUse, false);
        for (int i = 0; i < 16; i++) {
            if ((inUse16 & 1 << i) != 0) {
                final int i16 = i << 4;
                for (int j = 0; j < 16; j++) {
                    if (bsGetBit(bin)) {
                        inUse[i16 + j] = true;
                    }
                }
            }
        }

        makeMaps();
        final int alphaSize = this.nInUse + 2;
        /* Now the selectors */
        final int nGroups = bin.readBits(3);
        final int selectors = bin.readBits(15);
        if (selectors < 0) {
            throw new IOException("Corrupted input, nSelectors value negative");
        }
        checkBounds(alphaSize, MAX_ALPHA_SIZE + 1, "alphaSize");
        checkBounds(nGroups, N_GROUPS + 1, "nGroups");

        // Don't fail on nSelectors overflowing boundaries but discard the values in overflow
        // See https://gnu.wildebeest.org/blog/mjw/2019/08/02/bzip2-and-the-cve-that-wasnt/

        for (int i = 0; i < selectors; i++) {
            int j = 0;
            while (bsGetBit(bin)) {
                j++;
            }
            if (i < MAX_SELECTORS) {
                selectorMtf[i] = (byte) j;
            }
        }
        final int nSelectors = Math.min(selectors, MAX_SELECTORS);

        /* Undo the MTF values for the selectors. */
        for (int v = nGroups; --v >= 0;) {
            pos[v] = (byte) v;
        }

        for (int i = 0; i < nSelectors; i++) {
            int v = selectorMtf[i] & 0xff;
            checkBounds(v, N_GROUPS, "selectorMtf");
            final byte tmp = pos[v];
            while (v > 0) {
                pos[v] = pos[v - 1];
                v--;
            }
            pos[0] = tmp;
            selector[i] = tmp;
        }

        final char[][] len = dataShadow.temp_charArray2d;

        /* Now the coding tables */
        for (int t = 0; t < nGroups; t++) {
            int curr = bin.readBits(5);
            final char[] len_t = len[t];
            for (int i = 0; i < alphaSize; i++) {
                while (bsGetBit(bin)) {
                    curr += bsGetBit(bin) ? -1 : 1;
                }
                len_t[i] = (char) curr;
            }
        }

        // finally create the Huffman tables
        createHuffmanDecodingTables(alphaSize, nGroups);
    }

    private int setupBlock() throws IOException {
        if (currentState == EOF || this.data == null) {
            return -1;
        }

        final int[] cftab = this.data.cftab;
        final int ttLen = this.last + 1;
        final int[] tt = this.data.initTT(ttLen);
        final byte[] ll8 = this.data.ll8;
        cftab[0] = 0;
        System.arraycopy(this.data.unzftab, 0, cftab, 1, 256);

        for (int i = 1, c = cftab[0]; i <= 256; i++) {
            c += cftab[i];
            cftab[i] = c;
        }

        for (int i = 0, lastShadow = this.last; i <= lastShadow; i++) {
            final int tmp = cftab[ll8[i] & 0xff]++;
            checkBounds(tmp, ttLen, "tt index");
            tt[tmp] = i;
        }

        if (this.origPtr < 0 || this.origPtr >= tt.length) {
            throw new IOException("Stream corrupted");
        }

        this.su_tPos = tt[this.origPtr];
        this.su_count = 0;
        this.su_i2 = 0;
        this.su_ch2 = 256; /* not a char and not EOF */

        return setupNoRandPartA();
    }

    private int setupNoRandPartA() throws IOException {
        if (this.su_i2 <= this.last) {
            this.su_chPrev = this.su_ch2;
            final int su_ch2Shadow = this.data.ll8[this.su_tPos] & 0xff;
            this.su_ch2 = su_ch2Shadow;
            checkBounds(this.su_tPos, this.data.tt.length, "su_tPos");
            this.su_tPos = this.data.tt[this.su_tPos];
            this.su_i2++;
            this.currentState = NO_RAND_PART_B_STATE;
            return su_ch2Shadow;
        }
        this.currentState = NO_RAND_PART_A_STATE;
        initBlock();
        return setupBlock();
    }

    private int setupNoRandPartB() throws IOException {
        if (this.su_ch2 != this.su_chPrev) {
            this.su_count = 1;
            return setupNoRandPartA();
        }
        if (++this.su_count >= 4) {
            checkBounds(this.su_tPos, this.data.ll8.length, "su_tPos");
            this.su_z = (char) (this.data.ll8[this.su_tPos] & 0xff);
            this.su_tPos = this.data.tt[this.su_tPos];
            this.su_j2 = 0;
            return setupNoRandPartC();
        }
        return setupNoRandPartA();
    }

    private int setupNoRandPartC() throws IOException {
        if (this.su_j2 < this.su_z) {
            final int su_ch2Shadow = this.su_ch2;
            this.su_j2++;
            this.currentState = NO_RAND_PART_C_STATE;
            return su_ch2Shadow;
        }
        this.su_i2++;
        this.su_count = 0;
        return setupNoRandPartA();
    }
}
