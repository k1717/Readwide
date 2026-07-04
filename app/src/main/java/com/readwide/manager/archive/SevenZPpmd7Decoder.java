package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * Decoder for the 7z PPMd coder (id {@code 03 04 01}): PPMd var.H ("Ppmd7")
 * with the 7z range coder.
 *
 * <p>This is a Java port of the public-domain Ppmd7 reference implementation
 * (Dmitry Shkarin's PPMd var.H, 2001, public domain; Igor Pavlov's Ppmd7
 * codec, public domain). Public-domain code carries no license obligations
 * and is compatible with this project's Apache-2.0 licensing; the provenance
 * is recorded in {@code THIRD_PARTY_NOTICES.md}. No code under the 7-Zip,
 * UnRAR, or libarchive licenses is used.</p>
 *
 * <p>The model lives in one flat byte array mirroring the reference memory
 * layout, so sub-allocator arithmetic - and therefore rescale, restart, and
 * free-block-glue behaviour, which the encoder and decoder must reproduce
 * identically - matches the reference bit for bit. The port was validated
 * byte-exact against real 7z streams across orders 2-32, memory sizes from
 * 64 KiB (forcing model restarts and free-block gluing) to 1 MiB, and text,
 * repetitive, random, and zero-run payloads; see
 * {@code docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md}.</p>
 *
 * <p>Layouts: a CONTEXT is 12 bytes ({@code NumStats u16, SummFreq u16,
 * Stats u32, Suffix u32}); a STATE is 6 bytes ({@code Symbol u8, Freq u8,
 * Successor u32 split into two u16}). A context with {@code NumStats == 1}
 * stores its single state inline in the SummFreq/Stats field (the "one
 * state" union). Refs are byte offsets into the array; a ref below
 * {@code UnitsStart} points into the text area (a pending successor), at or
 * above it to allocated units.</p>
 */
final class SevenZPpmd7Decoder {
    private static final int INT_BITS = 7;
    private static final int PERIOD_BITS = 7;
    private static final int BIN_SCALE = 1 << (INT_BITS + PERIOD_BITS);
    private static final int MAX_FREQ = 124;
    private static final int UNIT_SIZE = 12;
    private static final int N_INDEXES = 38;
    private static final long K_TOP = 1L << 24;

    private static final int MIN_ORDER = 2;
    private static final int MAX_ORDER = 64;
    private static final int MIN_MEM = 1 << 11;
    /** Cap on the model memory declared by the archive, to bound allocations. */
    private static final int MAX_MEM = 1 << 28; // 256 MiB

    private static final int[] K_INIT_BIN_ESC = {0x3CDD, 0x1F3F, 0x59BF, 0x48F3, 0x64A1, 0x5ABC, 0x6632, 0x6051};
    private static final int[] K_EXP_ESCAPE = {25, 14, 9, 7, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 2};

    /** See-context index used as the dummy see (NumStats == 256 case). */
    private static final int DUMMY_SEE = 25 * 16;

    // ---- model fields ----
    private final int maxOrder;
    private final int size;
    private final int alignOffset;
    private final byte[] base;

    private final int[] indx2Units = new int[N_INDEXES];
    private final int[] units2Indx = new int[128];
    private final int[] ns2BSIndx = new int[256];
    private final int[] ns2Indx = new int[256];
    private final int[] hb2Flag = new int[256];

    private final int[] freeList = new int[N_INDEXES];
    private int text;
    private int unitsStart;
    private int loUnit;
    private int hiUnit;
    private int glueCount;

    private int orderFall;
    private int initRL;
    private int runLength;
    private int prevSuccess;
    private int initEsc;
    private int hiBitsFlag;

    private int minContext;
    private int maxContext;
    private int foundState;

    private final int[] seeSumm = new int[25 * 16 + 1];
    private final int[] seeShift = new int[25 * 16 + 1];
    private final int[] seeCount = new int[25 * 16 + 1];
    private final int[] binSumm = new int[128 * 64];

    // ---- range decoder ----
    private final byte[] input;
    private int inPos;
    private long code;
    private long range;

    private SevenZPpmd7Decoder(int order, int memSize, @NonNull byte[] input) throws IOException {
        this.maxOrder = order;
        this.size = memSize;
        this.alignOffset = 4 - (memSize & 3);
        this.base = new byte[alignOffset + memSize + UNIT_SIZE];
        this.input = input;
        constructTables();
        restartModel();
        rangeInit();
    }

    /**
     * Decodes a 7z PPMd stream. {@code properties} is the coder property blob:
     * order (u8) followed by memory size in bytes (u32 LE).
     */
    @NonNull
    static byte[] decode(@NonNull byte[] data, @Nullable byte[] properties, long unpackSize) throws IOException {
        if (properties == null || properties.length < 5) {
            throw new IOException("7z PPMd properties missing");
        }
        int order = properties[0] & 0xff;
        long mem = (properties[1] & 0xffL) | ((properties[2] & 0xffL) << 8)
                | ((properties[3] & 0xffL) << 16) | ((properties[4] & 0xffL) << 24);
        if (order < MIN_ORDER || order > MAX_ORDER) {
            throw new IOException("7z PPMd order out of range: " + order);
        }
        if (mem < MIN_MEM || mem > MAX_MEM) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "7z PPMd memory size unsupported: " + mem);
        }
        if (unpackSize < 0 || unpackSize > Integer.MAX_VALUE - 8) {
            throw new IOException("7z PPMd output size out of range");
        }
        SevenZPpmd7Decoder decoder = new SevenZPpmd7Decoder(order, (int) mem, data);
        byte[] out = new byte[(int) unpackSize];
        for (int i = 0; i < out.length; i++) {
            int sym = decoder.decodeSymbol();
            if (sym < 0) {
                throw new IOException("7z PPMd stream error at byte " + i);
            }
            out[i] = (byte) sym;
        }
        return out;
    }

    // ---- tables ----
    private void constructTables() {
        int k = 0;
        for (int i = 0; i < N_INDEXES; i++) {
            int step = i >= 12 ? 4 : (i >> 2) + 1;
            do {
                units2Indx[k++] = i;
            } while (--step != 0);
            indx2Units[i] = k;
        }
        ns2BSIndx[0] = 0;
        ns2BSIndx[1] = 2;
        for (int i = 2; i < 11; i++) ns2BSIndx[i] = 4;
        for (int i = 11; i < 256; i++) ns2BSIndx[i] = 6;
        for (int i = 0; i < 3; i++) ns2Indx[i] = i;
        int m = 3;
        k = 1;
        for (int i = 3; i < 256; i++) {
            ns2Indx[i] = m;
            if (--k == 0) {
                k = (++m) - 2;
            }
        }
        for (int i = 0; i < 0x40; i++) hb2Flag[i] = 0;
        for (int i = 0x40; i < 0x100; i++) hb2Flag[i] = 8;
    }

    private int u2b(int nu) {
        return nu * UNIT_SIZE;
    }

    private int u2i(int nu) {
        return units2Indx[nu - 1];
    }

    private int i2u(int indx) {
        return indx2Units[indx];
    }

    // ---- flat memory accessors ----
    private int r16(int off) {
        return (base[off] & 0xff) | ((base[off + 1] & 0xff) << 8);
    }

    private void w16(int off, int v) {
        base[off] = (byte) v;
        base[off + 1] = (byte) (v >>> 8);
    }

    private int r32(int off) {
        return (base[off] & 0xff) | ((base[off + 1] & 0xff) << 8)
                | ((base[off + 2] & 0xff) << 16) | ((base[off + 3] & 0xff) << 24);
    }

    private void w32(int off, int v) {
        base[off] = (byte) v;
        base[off + 1] = (byte) (v >>> 8);
        base[off + 2] = (byte) (v >>> 16);
        base[off + 3] = (byte) (v >>> 24);
    }

    // CONTEXT fields
    private int numStats(int c) {
        return r16(c);
    }

    private void setNumStats(int c, int v) {
        w16(c, v);
    }

    private int summFreq(int c) {
        return r16(c + 2);
    }

    private void setSummFreq(int c, int v) {
        w16(c + 2, v);
    }

    private int stats(int c) {
        return r32(c + 4);
    }

    private void setStats(int c, int v) {
        w32(c + 4, v);
    }

    private int suffix(int c) {
        return r32(c + 8);
    }

    private void setSuffix(int c, int v) {
        w32(c + 8, v);
    }

    private int oneState(int c) {
        return c + 2;
    }

    // STATE fields
    private int sym(int s) {
        return base[s] & 0xff;
    }

    private void setSym(int s, int v) {
        base[s] = (byte) v;
    }

    private int freq(int s) {
        return base[s + 1] & 0xff;
    }

    private void setFreq(int s, int v) {
        base[s + 1] = (byte) v;
    }

    private int succ(int s) {
        return r16(s + 2) | (r16(s + 4) << 16);
    }

    private void setSucc(int s, int v) {
        w16(s + 2, v & 0xffff);
        w16(s + 4, (v >>> 16) & 0xffff);
    }

    // ---- restart ----
    private void restartModel() {
        java.util.Arrays.fill(freeList, 0);
        text = alignOffset;
        hiUnit = alignOffset + size;
        int nu = size / 8 / UNIT_SIZE * 7;
        loUnit = unitsStart = hiUnit - nu * UNIT_SIZE;
        glueCount = 0;

        orderFall = maxOrder;
        initRL = -(Math.min(maxOrder, 12)) - 1;
        runLength = initRL;
        prevSuccess = 0;

        hiUnit -= UNIT_SIZE;
        minContext = maxContext = hiUnit;
        setSuffix(minContext, 0);
        setNumStats(minContext, 256);
        setSummFreq(minContext, 256 + 1);
        setStats(minContext, loUnit);
        foundState = loUnit;
        loUnit += u2b(256 / 2);
        for (int i = 0; i < 256; i++) {
            int s = stats(minContext) + i * 6;
            setSym(s, i);
            setFreq(s, 1);
            setSucc(s, 0);
        }

        for (int i = 0; i < 128; i++) {
            for (int k = 0; k < 8; k++) {
                int val = (BIN_SCALE - K_INIT_BIN_ESC[k] / (i + 2)) & 0xffff;
                for (int mIdx = k; mIdx < 64; mIdx += 8) {
                    binSumm[i * 64 + mIdx] = val;
                }
            }
        }
        for (int i = 0; i < 25; i++) {
            for (int k = 0; k < 16; k++) {
                int idx = i * 16 + k;
                seeShift[idx] = PERIOD_BITS - 4;
                seeSumm[idx] = ((5 * i + 10) << seeShift[idx]) & 0xffff;
                seeCount[idx] = 4;
            }
        }
        seeShift[DUMMY_SEE] = PERIOD_BITS;
        seeSumm[DUMMY_SEE] = 0;
        seeCount[DUMMY_SEE] = 64;
    }

    // ---- allocator ----
    private void insertNode(int node, int indx) {
        w32(node, freeList[indx]);
        freeList[indx] = node;
    }

    private int removeNode(int indx) {
        int node = freeList[indx];
        freeList[indx] = r32(node);
        return node;
    }

    private void splitBlock(int pv, int oldIndx, int newIndx) {
        int nu = i2u(oldIndx) - i2u(newIndx);
        int pv2 = pv + u2b(i2u(newIndx));
        int i = u2i(nu);
        if (i2u(i) != nu) {
            int k = i2u(--i);
            insertNode(pv2 + u2b(k), nu - k - 1);
        }
        insertNode(pv2, i);
    }

    private void glueFreeBlocks() {
        // Node layout inside a free block: Stamp u16, NU u16, Next u32, Prev u32.
        int head = alignOffset + size; // sentinel in the guard unit
        glueCount = 255;
        int n = head;
        // Build a circular doubly-linked list of all free blocks.
        for (int i = 0; i < N_INDEXES; i++) {
            int nu = i2u(i);
            int next = freeList[i];
            freeList[i] = 0;
            while (next != 0) {
                int node = next;
                w32(node + 4, n);       // node.Next
                w32(n + 8, node);       // n.Prev
                n = node;
                next = r32(node);       // free-list link, read before Stamp/NU overwrite
                w16(node, 0);           // Stamp
                w16(node + 2, nu);      // NU
            }
        }
        w16(head, 1);                   // head.Stamp
        w32(head + 4, n);               // head.Next
        w32(n + 8, head);               // n.Prev
        if (loUnit != hiUnit) {
            w16(loUnit, 1);             // stopper
        }
        // Glue adjacent blocks.
        n = r32(head + 4);
        while (n != head) {
            int node = n;
            int nu = r16(node + 2);
            while (true) {
                int node2 = node + nu * UNIT_SIZE;
                int nu2 = nu + r16(node2 + 2);
                if (r16(node2) != 0 || nu2 >= 0x10000) {
                    break;
                }
                w32(r32(node2 + 8) + 4, r32(node2 + 4)); // prev.Next = node2.Next
                w32(r32(node2 + 4) + 8, r32(node2 + 8)); // next.Prev = node2.Prev
                nu = nu2;
                w16(node + 2, nu);
            }
            n = r32(node + 4);
        }
        // Refill the free lists.
        n = r32(head + 4);
        while (n != head) {
            int node = n;
            int next = r32(node + 4);
            int nu = r16(node + 2);
            while (nu > 128) {
                insertNode(node, N_INDEXES - 1);
                nu -= 128;
                node += 128 * UNIT_SIZE;
            }
            int i = u2i(nu);
            if (i2u(i) != nu) {
                int k = i2u(--i);
                insertNode(node + k * UNIT_SIZE, nu - k - 1);
            }
            insertNode(node, i);
            n = next;
        }
    }

    private int allocUnitsRare(int indx) {
        if (glueCount == 0) {
            glueFreeBlocks();
            if (freeList[indx] != 0) {
                return removeNode(indx);
            }
        }
        int i = indx;
        while (true) {
            if (++i == N_INDEXES) {
                int numBytes = u2b(i2u(indx));
                glueCount--;
                if (unitsStart - text > numBytes) {
                    unitsStart -= numBytes;
                    return unitsStart;
                }
                return 0;
            }
            if (freeList[i] != 0) {
                break;
            }
        }
        int retVal = removeNode(i);
        splitBlock(retVal, i, indx);
        return retVal;
    }

    private int allocUnits(int indx) {
        if (freeList[indx] != 0) {
            return removeNode(indx);
        }
        int numBytes = u2b(i2u(indx));
        if (numBytes <= hiUnit - loUnit) {
            int retVal = loUnit;
            loUnit += numBytes;
            return retVal;
        }
        return allocUnitsRare(indx);
    }

    private int allocContext() {
        if (hiUnit != loUnit) {
            hiUnit -= UNIT_SIZE;
            return hiUnit;
        }
        if (freeList[0] != 0) {
            return removeNode(0);
        }
        return allocUnitsRare(0);
    }

    private int shrinkUnits(int oldPtr, int oldNU, int newNU) {
        int i0 = u2i(oldNU);
        int i1 = u2i(newNU);
        if (i0 == i1) {
            return oldPtr;
        }
        if (freeList[i1] != 0) {
            int ptr = removeNode(i1);
            System.arraycopy(base, oldPtr, base, ptr, u2b(newNU));
            insertNode(oldPtr, i0);
            return ptr;
        }
        splitBlock(oldPtr, i0, i1);
        return oldPtr;
    }

    private void freeUnits(int ptr, int nu) {
        insertNode(ptr, u2i(nu));
    }

    // ---- model ----
    private void swapStates(int a, int b) {
        for (int o = 0; o < 6; o++) {
            byte t = base[a + o];
            base[a + o] = base[b + o];
            base[b + o] = t;
        }
    }

    private void rescale() {
        int c = minContext;
        int statsPtr = stats(c);
        int s = foundState;
        // Move the found state to the front (shift the others right).
        while (s != statsPtr) {
            swapStates(s, s - 6);
            s -= 6;
        }
        int escFreq = summFreq(c) - freq(statsPtr);
        setFreq(statsPtr, freq(statsPtr) + 4);
        int adder = orderFall != 0 ? 1 : 0;
        setFreq(statsPtr, (freq(statsPtr) + adder) >> 1);
        int sumFreq = freq(statsPtr);

        int n = numStats(c);
        s = statsPtr;
        for (int i = n - 1; i != 0; i--) {
            s += 6;
            escFreq -= freq(s);
            setFreq(s, (freq(s) + adder) >> 1);
            sumFreq += freq(s);
            if (freq(s) > freq(s - 6)) {
                int s1 = s;
                byte[] tmp = new byte[6];
                System.arraycopy(base, s1, tmp, 0, 6);
                int f = tmp[1] & 0xff;
                do {
                    System.arraycopy(base, s1 - 6, base, s1, 6);
                    s1 -= 6;
                } while (s1 != statsPtr && f > freq(s1 - 6));
                System.arraycopy(tmp, 0, base, s1, 6);
            }
        }
        if (freq(s) == 0) {
            int i = 0;
            do {
                i++;
                s -= 6;
            } while (freq(s) == 0);
            escFreq += i;
            int newNum = n - i;
            setNumStats(c, newNum);
            if (newNum == 1) {
                byte[] tmp = new byte[6];
                System.arraycopy(base, statsPtr, tmp, 0, 6);
                int f = tmp[1] & 0xff;
                do {
                    f -= f >> 1;
                    escFreq >>= 1;
                } while (escFreq > 1);
                insertNode(statsPtr, u2i((n + 1) >> 1));
                int one = oneState(c);
                System.arraycopy(tmp, 0, base, one, 6);
                setFreq(one, f);
                foundState = one;
                return;
            }
            int n0 = (n + 1) >> 1;
            int n1 = (newNum + 1) >> 1;
            if (n0 != n1) {
                setStats(c, shrinkUnits(statsPtr, n0, n1));
            }
        }
        setSummFreq(c, sumFreq + escFreq - (escFreq >> 1));
        foundState = stats(c);
    }

    /** Builds the pending contexts for a text successor; 0 on allocation failure. */
    private int createSuccessors(boolean skip) {
        int c = minContext;
        int upBranch = succ(foundState);
        int fSym = sym(foundState);
        int[] ps = new int[MAX_ORDER + 1];
        int numPs = 0;
        if (!skip) {
            ps[numPs++] = foundState;
        }
        while (suffix(c) != 0) {
            c = suffix(c);
            int s;
            if (numStats(c) != 1) {
                s = stats(c);
                while (sym(s) != fSym) {
                    s += 6;
                }
            } else {
                s = oneState(c);
            }
            int successor = succ(s);
            if (successor != upBranch) {
                c = successor;
                if (numPs == 0) {
                    return c;
                }
                break;
            }
            ps[numPs++] = s;
        }

        int upSym = base[upBranch] & 0xff;
        int upSucc = upBranch + 1;
        int upFreq;
        if (numStats(c) != 1) {
            int s = stats(c);
            while (sym(s) != upSym) {
                s += 6;
            }
            int cf = freq(s) - 1;
            int s0 = summFreq(c) - numStats(c) - cf;
            if (2 * cf <= s0) {
                upFreq = 1 + (5 * cf > s0 ? 1 : 0);
            } else {
                upFreq = 1 + (2 * cf + 3 * s0 - 1) / (2 * s0);
            }
        } else {
            upFreq = freq(oneState(c));
        }
        do {
            int c1 = allocContext();
            if (c1 == 0) {
                return 0;
            }
            setNumStats(c1, 1);
            int one = oneState(c1);
            setSym(one, upSym);
            setFreq(one, Math.min(upFreq, MAX_FREQ));
            setSucc(one, upSucc);
            setSuffix(c1, c);
            setSucc(ps[--numPs], c1);
            c = c1;
        } while (numPs != 0);
        return c;
    }

    private void updateModel() {
        int fs = foundState;
        int fSym = sym(fs);
        int fSucc = succ(fs);

        if (freq(fs) < MAX_FREQ / 4 && suffix(minContext) != 0) {
            int c = suffix(minContext);
            if (numStats(c) == 1) {
                int s = oneState(c);
                if (freq(s) < 32) {
                    setFreq(s, freq(s) + 1);
                }
            } else {
                int s = stats(c);
                if (sym(s) != fSym) {
                    do {
                        s += 6;
                    } while (sym(s) != fSym);
                    if (freq(s) >= freq(s - 6)) {
                        swapStates(s, s - 6);
                        s -= 6;
                    }
                }
                if (freq(s) < MAX_FREQ - 9) {
                    setFreq(s, freq(s) + 2);
                    setSummFreq(c, summFreq(c) + 2);
                }
            }
        }

        if (orderFall == 0) {
            int newC = createSuccessors(true);
            if (newC == 0) {
                restartModel();
                return;
            }
            minContext = maxContext = newC;
            setSucc(foundState, newC);
            return;
        }

        base[text] = (byte) fSym;
        text++;
        int successor = text;
        if (text >= unitsStart) {
            restartModel();
            return;
        }

        if (fSucc != 0) {
            if (fSucc <= successor) {
                int cs = createSuccessors(false);
                if (cs == 0) {
                    restartModel();
                    return;
                }
                fSucc = cs;
            }
            if (--orderFall == 0) {
                successor = fSucc;
                if (maxContext != minContext) {
                    text--;
                }
            }
        } else {
            setSucc(foundState, successor);
            fSucc = minContext;
        }

        int ns = numStats(minContext);
        int s0 = summFreq(minContext) - ns - (freq(foundState) - 1);
        for (int c = maxContext; c != minContext; c = suffix(c)) {
            int ns1 = numStats(c);
            if (ns1 != 1) {
                if ((ns1 & 1) == 0) {
                    int oldNU = ns1 >> 1;
                    int i = u2i(oldNU);
                    if (i != u2i(oldNU + 1)) {
                        int ptr = allocUnits(i + 1);
                        if (ptr == 0) {
                            restartModel();
                            return;
                        }
                        int oldPtr = stats(c);
                        System.arraycopy(base, oldPtr, base, ptr, u2b(oldNU));
                        insertNode(oldPtr, i);
                        setStats(c, ptr);
                    }
                }
                int add = (2 * ns1 < ns ? 1 : 0)
                        + 2 * ((4 * ns1 <= ns && summFreq(c) <= 8 * ns1) ? 1 : 0);
                setSummFreq(c, (summFreq(c) + add) & 0xffff);
            } else {
                int ptr = allocUnits(0);
                if (ptr == 0) {
                    restartModel();
                    return;
                }
                int one = oneState(c);
                System.arraycopy(base, one, base, ptr, 6);
                setStats(c, ptr);
                int f = freq(ptr);
                if (f < MAX_FREQ / 4 - 1) {
                    f += f;
                } else {
                    f = MAX_FREQ - 4;
                }
                setFreq(ptr, f);
                setSummFreq(c, (f + initEsc + (ns > 3 ? 1 : 0)) & 0xffff);
            }
            long cf = 2L * freq(foundState) * (summFreq(c) + 6);
            long sf = (long) s0 + summFreq(c);
            int newFreq;
            if (cf < 6 * sf) {
                newFreq = 1 + (cf > sf ? 1 : 0) + (cf >= 4 * sf ? 1 : 0);
                setSummFreq(c, (summFreq(c) + 3) & 0xffff);
            } else {
                newFreq = 4 + (cf >= 9 * sf ? 1 : 0) + (cf >= 12 * sf ? 1 : 0) + (cf >= 15 * sf ? 1 : 0);
                setSummFreq(c, (summFreq(c) + newFreq) & 0xffff);
            }
            int s = stats(c) + ns1 * 6;
            setSucc(s, successor);
            setSym(s, fSym);
            setFreq(s, newFreq);
            setNumStats(c, ns1 + 1);
        }
        maxContext = minContext = fSucc;
    }

    private void nextContext() {
        int c = succ(foundState);
        if (orderFall == 0 && c > text) {
            minContext = maxContext = c;
        } else {
            updateModel();
        }
    }

    private void update1(int s) {
        foundState = s;
        setFreq(s, freq(s) + 4);
        setSummFreq(minContext, summFreq(minContext) + 4);
        if (freq(s) > freq(s - 6)) {
            swapStates(s, s - 6);
            foundState = s - 6;
            if (freq(s - 6) > MAX_FREQ) {
                rescale();
            }
        }
        nextContext();
    }

    private void update1_0(int s) {
        prevSuccess = 2 * freq(s) > summFreq(minContext) ? 1 : 0;
        runLength += prevSuccess;
        setSummFreq(minContext, summFreq(minContext) + 4);
        setFreq(s, freq(s) + 4);
        foundState = s;
        if (freq(s) > MAX_FREQ) {
            rescale();
        }
        nextContext();
    }

    private void updateBin(int s) {
        setFreq(s, freq(s) + (freq(s) < 128 ? 1 : 0));
        prevSuccess = 1;
        runLength++;
        foundState = s;
        nextContext();
    }

    private void update2(int s) {
        foundState = s;
        setFreq(s, freq(s) + 4);
        setSummFreq(minContext, summFreq(minContext) + 4);
        if (freq(s) > MAX_FREQ) {
            rescale();
        }
        runLength = initRL;
        updateModel();
    }

    /** Returns the see index and writes the escape frequency to escFreq[0]. */
    private int makeEscFreq(int numMasked, int[] escFreq) {
        int c = minContext;
        int numStats = numStats(c);
        if (numStats != 256) {
            int nonMasked = numStats - numMasked;
            long suffDiff = (numStats(suffix(c)) - numStats) & 0xFFFFFFFFL;
            int idx = ns2Indx[nonMasked - 1] * 16
                    + (nonMasked < suffDiff ? 1 : 0)
                    + 2 * (summFreq(c) < 11 * numStats ? 1 : 0)
                    + 4 * (numMasked > nonMasked ? 1 : 0)
                    + hiBitsFlag;
            int r = seeSumm[idx] >>> seeShift[idx];
            seeSumm[idx] = (seeSumm[idx] - r) & 0xffff;
            escFreq[0] = r + (r == 0 ? 1 : 0);
            return idx;
        }
        escFreq[0] = 1;
        return DUMMY_SEE;
    }

    private void seeUpdate(int idx) {
        if (seeShift[idx] < PERIOD_BITS && --seeCount[idx] == 0) {
            seeSumm[idx] = (seeSumm[idx] << 1) & 0xffff;
            seeCount[idx] = 3 << seeShift[idx];
            seeShift[idx]++;
        }
    }

    // ---- range decoder (7z Ppmd7z variant) ----
    private int inByte() {
        int b = inPos < input.length ? input[inPos] & 0xff : 0;
        inPos++;
        return b;
    }

    private void rangeInit() throws IOException {
        code = 0;
        range = 0xFFFFFFFFL;
        if (inByte() != 0) {
            throw new IOException("7z PPMd range coder header invalid");
        }
        for (int i = 0; i < 4; i++) {
            code = ((code << 8) | inByte()) & 0xFFFFFFFFL;
        }
    }

    private void rangeNormalize() {
        if (range < K_TOP) {
            code = ((code << 8) | inByte()) & 0xFFFFFFFFL;
            range = (range << 8) & 0xFFFFFFFFL;
            if (range < K_TOP) {
                code = ((code << 8) | inByte()) & 0xFFFFFFFFL;
                range = (range << 8) & 0xFFFFFFFFL;
            }
        }
    }

    private int rangeThreshold(int total) {
        range = range / total;
        return (int) (code / range);
    }

    private void rangeDecode(int start, int size) {
        code = (code - start * range) & 0xFFFFFFFFL;
        range = (range * size) & 0xFFFFFFFFL;
        rangeNormalize();
    }

    private int rangeDecodeBit(int size0) {
        long newBound = (range >>> 14) * size0;
        int bit;
        if (code < newBound) {
            bit = 0;
            range = newBound;
        } else {
            bit = 1;
            code -= newBound;
            range -= newBound;
        }
        rangeNormalize();
        return bit;
    }

    // ---- symbol decode ----
    private int decodeSymbol() {
        byte[] charMask = new byte[256];
        int c = minContext;
        if (numStats(c) != 1) {
            int s = stats(c);
            int count = rangeThreshold(summFreq(c));
            int hiCnt = freq(s);
            if (count < hiCnt) {
                rangeDecode(0, freq(s));
                int symbol = sym(s);
                update1_0(s);
                return symbol;
            }
            prevSuccess = 0;
            int i = numStats(c) - 1;
            do {
                s += 6;
                hiCnt += freq(s);
                if (hiCnt > count) {
                    rangeDecode(hiCnt - freq(s), freq(s));
                    int symbol = sym(s);
                    update1(s);
                    return symbol;
                }
            } while (--i != 0);
            if (count >= summFreq(c)) {
                return -2;
            }
            hiBitsFlag = hb2Flag[sym(foundState)];
            rangeDecode(hiCnt, summFreq(c) - hiCnt);
            for (int m = 0; m < 256; m++) charMask[m] = (byte) 0xff;
            int n = numStats(c);
            int ss = stats(c);
            for (int j = 0; j < n; j++) {
                charMask[sym(ss + j * 6)] = 0;
            }
        } else {
            int s = oneState(c);
            hiBitsFlag = hb2Flag[sym(foundState)];
            int suffNS = numStats(suffix(c));
            int bsIndex = (freq(s) - 1) * 64
                    + prevSuccess
                    + ns2BSIndx[suffNS - 1]
                    + hiBitsFlag
                    + 2 * hb2Flag[sym(s)]
                    + ((runLength >> 26) & 0x20);
            int summ = binSumm[bsIndex];
            int mean = (summ + (1 << (PERIOD_BITS - 2))) >>> PERIOD_BITS;
            if (rangeDecodeBit(summ) == 0) {
                binSumm[bsIndex] = (summ + (1 << INT_BITS) - mean) & 0xffff;
                int symbol = sym(s);
                updateBin(s);
                return symbol;
            }
            binSumm[bsIndex] = (summ - mean) & 0xffff;
            initEsc = K_EXP_ESCAPE[binSumm[bsIndex] >>> 10];
            for (int m = 0; m < 256; m++) charMask[m] = (byte) 0xff;
            charMask[sym(s)] = 0;
            prevSuccess = 0;
        }

        // Masked-symbol loop.
        int[] ps = new int[256];
        int[] escHolder = new int[1];
        while (true) {
            int numMasked = numStats(minContext);
            do {
                orderFall++;
                if (suffix(minContext) == 0) {
                    return -1;
                }
                minContext = suffix(minContext);
            } while (numStats(minContext) == numMasked);
            c = minContext;

            // Collect exactly (NumStats - numMasked) unmasked states in stats
            // order, stopping early - this mirrors the reference loop, which
            // the encoder also runs, so the walked set must match it exactly.
            int hiCnt = 0;
            int s = stats(c);
            int i = 0;
            int num = numStats(c) - numMasked;
            do {
                if (charMask[sym(s)] != 0) {
                    hiCnt += freq(s);
                    ps[i++] = s;
                }
                s += 6;
            } while (i != num);

            int seeIdx = makeEscFreq(numMasked, escHolder);
            int freqSum = escHolder[0] + hiCnt;
            int count = rangeThreshold(freqSum);

            if (count < hiCnt) {
                int acc = 0;
                int idx = 0;
                while (true) {
                    acc += freq(ps[idx]);
                    if (acc > count) {
                        break;
                    }
                    idx++;
                }
                int sel = ps[idx];
                rangeDecode(acc - freq(sel), freq(sel));
                seeUpdate(seeIdx);
                int symbol = sym(sel);
                update2(sel);
                return symbol;
            }
            if (count >= freqSum) {
                return -2;
            }
            rangeDecode(hiCnt, freqSum - hiCnt);
            seeSumm[seeIdx] = (seeSumm[seeIdx] + freqSum) & 0xffff;
            for (int j = 0; j < num; j++) {
                charMask[sym(ps[j])] = 0;
            }
        }
    }
}
