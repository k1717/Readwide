package com.readwide.manager.archive;

/**
 * Original Java implementation of the PPMd variant H statistical model
 * (Dmitry Shkarin's public-domain PPMd var.H, as standardised in Igor
 * Pavlov's public-domain Ppmd7 description) combined with the carryless
 * range decoder used by RAR3/RAR4 PPMd blocks.
 *
 * <p>Decode-only. This class never encodes, never encrypts, and contains
 * no code copied from UnRAR or libarchive. Reference implementations were
 * consulted strictly for behavioural comparison (per-symbol trace diffing
 * against a locally built harness); all constants below originate from the
 * public-domain PPMd var.H / Ppmd7 algorithm description.</p>
 *
 * <p>The model state lives in a single flat byte heap, mirroring the
 * canonical suballocator layout so that behaviour (including rescale and
 * restart-on-allocation-failure paths) is bit-exact with the reference
 * algorithm. All "references" are int offsets into the heap; offset 0 is
 * the null reference (the heap origin is never used for live data because
 * the text area starts at {@code alignOffset >= 1}).</p>
 */
final class RarPpmdVarHDecoder {

    // ---- algorithm constants (public-domain PPMd var.H / Ppmd7) ----
    private static final int UNIT_SIZE = 12;
    private static final int MAX_FREQ = 124;
    private static final int NUM_INDEXES = 38;
    private static final int INT_BITS = 7;
    private static final int PERIOD_BITS = 7;
    private static final int BIN_SCALE = 1 << (INT_BITS + PERIOD_BITS); // 16384
    private static final int MAX_ORDER_LIMIT = 64;
    private static final int K_TOP = 1 << 24;
    private static final int RAR_BOTTOM = 0x8000;

    private static final int[] INIT_BIN_ESC = {
            0x3CDD, 0x1F3F, 0x59BF, 0x48F3, 0x64A1, 0x5ABC, 0x6632, 0x6051
    };
    private static final int[] EXP_ESCAPE = {
            25, 14, 9, 7, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 2
    };

    /** Thrown when the compressed stream is inconsistent with the model. */
    static final class PpmdDataException extends RuntimeException {
        PpmdDataException(String message) {
            super(message);
        }
    }

    // ---- derived lookup tables (built once per instance) ----
    private final int[] units2Indx = new int[128];
    private final int[] indx2Units = new int[NUM_INDEXES];
    private final int[] ns2BsIndx = new int[256];
    private final int[] ns2Indx = new int[256];
    private final int[] hb2Flag = new int[256];

    // ---- suballocator / heap ----
    private byte[] heap;
    private int size;
    private int alignOffset;
    private final int[] freeList = new int[NUM_INDEXES];
    private int glueCount;
    private int textRef;
    private int unitsStartRef;
    private int loUnitRef;
    private int hiUnitRef;

    // ---- model state ----
    private int maxOrder;
    private int minContext; // context ref
    private int maxContext; // context ref
    private int foundState; // state ref
    private int orderFall;
    private int initEsc;
    private int prevSuccess;
    private int runLength;
    private int initRL;
    private int hiBitsFlag;
    private int numMasked;
    private final int[][] binSumm = new int[128][64];
    private final int[] seeSumm = new int[25 * 16];
    private final int[] seeShift = new int[25 * 16];
    private final int[] seeCount = new int[25 * 16];
    private static final int SEE_DUMMY = 25 * 16; // sentinel index for the dummy SEE entry
    private final byte[] charMask = new byte[256];
    private final int[] psBuf = new int[256];

    // ---- range decoder (RAR carryless variant) ----
    private int rdLow;
    private int rdCode;
    private int rdRange;
    private byte[] input;
    private int inputPos;
    private int inputLimit;
    private int eofPad;
    private static final int MAX_EOF_PAD = 64;

    // ---- optional per-symbol trace (verification only) ----
    interface TraceSink {
        void onSymbol(long index, int preMc, int preNs, int preSf, int preOf, int prePs, int preRl,
                      int preLow, int preCode, int preRange, int sym,
                      int postMc, int postNs, int postSf, int postOf,
                      int postText, int postLoUnit, int postHiUnit);
    }

    private TraceSink trace;
    private long symbolIndex;

    RarPpmdVarHDecoder() {
        buildTables();
    }

    void setTraceSink(TraceSink sink) {
        this.trace = sink;
    }

    private void buildTables() {
        int k = 0;
        for (int i = 0; i < NUM_INDEXES; i++) {
            int step = (i >= 12) ? 4 : (i >> 2) + 1;
            do {
                units2Indx[k++] = i;
            } while (--step != 0);
            indx2Units[i] = k;
        }
        ns2BsIndx[0] = 0;
        ns2BsIndx[1] = 2;
        for (int i = 2; i < 11; i++) ns2BsIndx[i] = 4;
        for (int i = 11; i < 256; i++) ns2BsIndx[i] = 6;
        for (int i = 0; i < 3; i++) ns2Indx[i] = i;
        int m = 3;
        int cnt = 1;
        for (int i = 3; i < 256; i++) {
            ns2Indx[i] = m;
            if (--cnt == 0) {
                cnt = (++m) - 2;
            }
        }
        for (int i = 0; i < 0x40; i++) hb2Flag[i] = 0;
        for (int i = 0x40; i < 256; i++) hb2Flag[i] = 8;
    }

    // ---- heap accessors (little-endian, matching the canonical layout) ----
    private int u8(int ref) {
        return heap[ref] & 0xFF;
    }

    private void setU8(int ref, int v) {
        heap[ref] = (byte) v;
    }

    private int u16(int ref) {
        return (heap[ref] & 0xFF) | ((heap[ref + 1] & 0xFF) << 8);
    }

    private void setU16(int ref, int v) {
        heap[ref] = (byte) v;
        heap[ref + 1] = (byte) (v >>> 8);
    }

    private int u32(int ref) {
        return (heap[ref] & 0xFF)
                | ((heap[ref + 1] & 0xFF) << 8)
                | ((heap[ref + 2] & 0xFF) << 16)
                | ((heap[ref + 3] & 0xFF) << 24);
    }

    private void setU32(int ref, int v) {
        heap[ref] = (byte) v;
        heap[ref + 1] = (byte) (v >>> 8);
        heap[ref + 2] = (byte) (v >>> 16);
        heap[ref + 3] = (byte) (v >>> 24);
    }

    // Context layout: NumStats u16 @0, SummFreq u16 @2, Stats u32 @4, Suffix u32 @8
    private int ctxNumStats(int c) {
        return u16(c);
    }

    private void setCtxNumStats(int c, int v) {
        setU16(c, v);
    }

    private int ctxSummFreq(int c) {
        return u16(c + 2);
    }

    private void setCtxSummFreq(int c, int v) {
        setU16(c + 2, v);
    }

    private int ctxStats(int c) {
        return u32(c + 4);
    }

    private void setCtxStats(int c, int v) {
        setU32(c + 4, v);
    }

    private int ctxSuffix(int c) {
        return u32(c + 8);
    }

    private void setCtxSuffix(int c, int v) {
        setU32(c + 8, v);
    }

    /** The embedded single state of a binary context overlays bytes 2..7. */
    private int oneState(int c) {
        return c + 2;
    }

    // State layout: Symbol u8 @0, Freq u8 @1, SuccessorLow u16 @2, SuccessorHigh u16 @4
    private int stSymbol(int s) {
        return u8(s);
    }

    private void setStSymbol(int s, int v) {
        setU8(s, v);
    }

    private int stFreq(int s) {
        return u8(s + 1);
    }

    private void setStFreq(int s, int v) {
        setU8(s + 1, v);
    }

    private int stSuccessor(int s) {
        return u16(s + 2) | (u16(s + 4) << 16);
    }

    private void setStSuccessor(int s, int v) {
        setU16(s + 2, v & 0xFFFF);
        setU16(s + 4, v >>> 16);
    }

    private void copyState(int dst, int src) {
        System.arraycopy(heap, src, heap, dst, 6);
    }

    private void swapStates(int a, int b) {
        for (int i = 0; i < 6; i++) {
            byte t = heap[a + i];
            heap[a + i] = heap[b + i];
            heap[b + i] = t;
        }
    }

    private void copyUnits(int dstRef, int srcRef, int nu) {
        System.arraycopy(heap, srcRef, heap, dstRef, nu * UNIT_SIZE);
    }

    // ---- suballocator ----
    private int u2b(int nu) {
        return nu * UNIT_SIZE;
    }

    private int u2i(int nu) {
        return units2Indx[nu - 1];
    }

    private int i2u(int indx) {
        return indx2Units[indx];
    }

    private void insertNode(int nodeRef, int indx) {
        setU32(nodeRef, freeList[indx]);
        freeList[indx] = nodeRef;
    }

    private int removeNode(int indx) {
        int nodeRef = freeList[indx];
        freeList[indx] = u32(nodeRef);
        return nodeRef;
    }

    private void splitBlock(int ptrRef, int oldIndx, int newIndx) {
        int nu = i2u(oldIndx) - i2u(newIndx);
        int ref = ptrRef + u2b(i2u(newIndx));
        int i = u2i(nu);
        if (i2u(i) != nu) {
            int kUnits = i2u(--i);
            insertNode(ref + u2b(kUnits), nu - kUnits - 1);
        }
        insertNode(ref, i);
    }

    // Free node layout during glue: Stamp u16 @0, NU u16 @2, Next u32 @4, Prev u32 @8
    private void glueFreeBlocks() {
        int head = alignOffset + size;
        int n = head;
        glueCount = 255;

        // Build a doubly linked list of all free blocks.
        for (int i = 0; i < NUM_INDEXES; i++) {
            int nu = i2u(i);
            int next = freeList[i];
            freeList[i] = 0;
            while (next != 0) {
                int node = next;
                setU32(node + 4, n);          // node.Next = n
                setU32(n + 8, node);          // (old n).Prev = node
                n = node;
                next = u32(node);             // free-list chain stored at offset 0
                setU16(node, 0);              // Stamp = 0
                setU16(node + 2, nu);         // NU
            }
        }
        setU16(head, 1);                      // head.Stamp = 1
        setU32(head + 4, n);                  // head.Next = n
        setU32(n + 8, head);                  // n.Prev = head
        if (loUnitRef != hiUnitRef) {
            setU16(loUnitRef, 1);             // sentinel stamp at LoUnit
        }

        // Merge adjacent free blocks.
        while (n != head) {
            int node = n;
            int nu = u16(node + 2);
            for (; ; ) {
                int node2 = node + nu * UNIT_SIZE;
                int nu2 = nu + u16(node2 + 2);
                if (u16(node2) != 0 || nu2 >= 0x10000) {
                    break;
                }
                nu = nu2;
                int prev2 = u32(node2 + 8);
                int next2 = u32(node2 + 4);
                setU32(prev2 + 4, next2);
                setU32(next2 + 8, prev2);
                setU16(node + 2, nu);
            }
            n = u32(node + 4);
        }

        // Re-fill the free lists.
        for (n = u32(head + 4); n != head; ) {
            int node = n;
            int next = u32(node + 4);
            int nu = u16(node + 2);
            while (nu > 128) {
                insertNode(node, NUM_INDEXES - 1);
                nu -= 128;
                node += 128 * UNIT_SIZE;
            }
            int i = u2i(nu);
            if (i2u(i) != nu) {
                int kUnits = i2u(--i);
                insertNode(node + kUnits * UNIT_SIZE, nu - kUnits - 1);
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
        do {
            if (++i == NUM_INDEXES) {
                int numBytes = u2b(i2u(indx));
                glueCount--;
                if (unitsStartRef - textRef > numBytes) {
                    unitsStartRef -= numBytes;
                    return unitsStartRef;
                }
                return 0;
            }
        } while (freeList[i] == 0);
        int retVal = removeNode(i);
        splitBlock(retVal, i, indx);
        return retVal;
    }

    private int allocUnits(int indx) {
        if (freeList[indx] != 0) {
            return removeNode(indx);
        }
        int numBytes = u2b(i2u(indx));
        if (numBytes <= hiUnitRef - loUnitRef) {
            int retVal = loUnitRef;
            loUnitRef += numBytes;
            return retVal;
        }
        return allocUnitsRare(indx);
    }

    private int shrinkUnits(int oldRef, int oldNU, int newNU) {
        int i0 = u2i(oldNU);
        int i1 = u2i(newNU);
        if (i0 == i1) {
            return oldRef;
        }
        if (freeList[i1] != 0) {
            int ref = removeNode(i1);
            copyUnits(ref, oldRef, newNU);
            insertNode(oldRef, i0);
            return ref;
        }
        splitBlock(oldRef, i0, i1);
        return oldRef;
    }

    // ---- model lifetime ----

    /** Allocates (or reuses) the model heap of the requested byte size. */
    boolean alloc(int requestedSize) {
        if (requestedSize < UNIT_SIZE) {
            return false;
        }
        if (heap == null || size != requestedSize) {
            alignOffset = 4 - (requestedSize & 3);
            long total = (long) alignOffset + requestedSize + UNIT_SIZE;
            if (total > Integer.MAX_VALUE - 8) {
                return false;
            }
            heap = new byte[(int) total];
            size = requestedSize;
        }
        return true;
    }

    boolean isAllocated() {
        return heap != null;
    }

    /** Initialises the model for a fresh stream with the given max order. */
    void init(int order) {
        if (order < 2) {
            order = 2;
        }
        if (order > MAX_ORDER_LIMIT) {
            order = MAX_ORDER_LIMIT;
        }
        this.maxOrder = order;
        restartModel();
    }

    private void restartModel() {
        java.util.Arrays.fill(freeList, 0);
        textRef = alignOffset;
        hiUnitRef = textRef + size;
        loUnitRef = unitsStartRef = hiUnitRef - (size / 8 / UNIT_SIZE) * 7 * UNIT_SIZE;
        glueCount = 0;

        orderFall = maxOrder;
        initRL = -(Math.min(maxOrder, 12)) - 1;
        runLength = initRL;
        prevSuccess = 0;
        initEsc = 0;
        hiBitsFlag = 0;

        hiUnitRef -= UNIT_SIZE;
        minContext = maxContext = hiUnitRef;
        setCtxSuffix(minContext, 0);
        setCtxNumStats(minContext, 256);
        setCtxSummFreq(minContext, 256 + 1);
        foundState = loUnitRef;
        loUnitRef += u2b(256 / 2);
        setCtxStats(minContext, foundState);
        for (int i = 0; i < 256; i++) {
            int s = foundState + i * 6;
            setStSymbol(s, i);
            setStFreq(s, 1);
            setStSuccessor(s, 0);
        }

        for (int i = 0; i < 128; i++) {
            for (int k = 0; k < 8; k++) {
                int val = (BIN_SCALE - INIT_BIN_ESC[k] / (i + 2)) & 0xFFFF;
                for (int mcol = 0; mcol < 64; mcol += 8) {
                    binSumm[i][k + mcol] = val;
                }
            }
        }
        for (int i = 0; i < 25; i++) {
            for (int k = 0; k < 16; k++) {
                int idx = i * 16 + k;
                seeShift[idx] = PERIOD_BITS - 4;
                seeSumm[idx] = ((5 * i + 10) << seeShift[idx]) & 0xFFFF;
                seeCount[idx] = 4;
            }
        }
    }

    // ---- range decoder ----

    /** Attaches the compressed payload and primes the range decoder. */
    void rangeInit(byte[] payload, int offset, int length) {
        this.input = payload;
        this.inputPos = offset;
        this.inputLimit = offset + length;
        this.eofPad = 0;
        rdLow = 0;
        rdRange = 0xFFFFFFFF;
        rdCode = 0;
        for (int i = 0; i < 4; i++) {
            rdCode = (rdCode << 8) | nextByte();
        }
    }

    private int nextByte() {
        if (inputPos < inputLimit) {
            return input[inputPos++] & 0xFF;
        }
        if (++eofPad > MAX_EOF_PAD) {
            throw new PpmdDataException("PPMd range decoder ran past the end of the compressed payload");
        }
        return 0;
    }

    int consumedInputBytes() {
        return inputPos;
    }

    private int rdGetThreshold(int total) {
        rdRange = Integer.divideUnsigned(rdRange, total);
        return Integer.divideUnsigned(rdCode - rdLow, rdRange);
    }

    private void rdNormalize() {
        for (; ; ) {
            if (Integer.compareUnsigned(rdLow ^ (rdLow + rdRange), K_TOP) >= 0) {
                if (Integer.compareUnsigned(rdRange, RAR_BOTTOM) >= 0) {
                    break;
                }
                rdRange = (-rdLow) & (RAR_BOTTOM - 1);
            }
            rdCode = (rdCode << 8) | nextByte();
            rdRange <<= 8;
            rdLow <<= 8;
        }
    }

    private void rdDecode(int start, int size) {
        rdLow += start * rdRange;
        rdRange *= size;
        rdNormalize();
    }

    private int rdDecodeBit(int size0) {
        int value = rdGetThreshold(BIN_SCALE);
        if (Integer.compareUnsigned(value, size0) < 0) {
            rdDecode(0, size0);
            return 0;
        }
        rdDecode(size0, BIN_SCALE - size0);
        return 1;
    }

    // ---- SEE helpers ----
    private int makeEscFreqSeeIndex; // set by makeEscFreq
    private int makeEscFreqValue;    // set by makeEscFreq

    private void makeEscFreq(int masked) {
        int mcNs = ctxNumStats(minContext);
        int nonMasked = mcNs - masked;
        if (mcNs != 256) {
            int suffixNs = ctxNumStats(ctxSuffix(minContext));
            int col = (Integer.compareUnsigned(nonMasked, suffixNs - mcNs) < 0 ? 1 : 0)
                    + 2 * ((ctxSummFreq(minContext) < 11 * mcNs) ? 1 : 0)
                    + 4 * ((masked > nonMasked) ? 1 : 0)
                    + hiBitsFlag;
            int idx = ns2Indx[nonMasked - 1] * 16 + col;
            int r = seeSumm[idx] >>> seeShift[idx];
            seeSumm[idx] = (seeSumm[idx] - r) & 0xFFFF;
            makeEscFreqSeeIndex = idx;
            makeEscFreqValue = r + ((r == 0) ? 1 : 0);
        } else {
            makeEscFreqSeeIndex = SEE_DUMMY;
            makeEscFreqValue = 1;
        }
    }

    private void seeUpdate(int idx) {
        if (idx == SEE_DUMMY) {
            return;
        }
        if (seeShift[idx] < PERIOD_BITS && --seeCount[idx] == 0) {
            seeSumm[idx] = (seeSumm[idx] << 1) & 0xFFFF;
            seeCount[idx] = 3 << seeShift[idx];
            seeShift[idx]++;
        }
    }

    private void seeAddSumm(int idx, int v) {
        if (idx == SEE_DUMMY) {
            return;
        }
        seeSumm[idx] = (seeSumm[idx] + v) & 0xFFFF;
    }

    // ---- model update machinery ----

    private int createSuccessors(boolean skip) {
        int c = minContext;
        int upBranch = stSuccessor(foundState);
        int numPs = 0;
        int fSymbol = stSymbol(foundState);

        if (!skip) {
            psBuf[numPs++] = foundState;
        }

        while (ctxSuffix(c) != 0) {
            c = ctxSuffix(c);
            int s;
            if (ctxNumStats(c) != 1) {
                s = ctxStats(c);
                while (stSymbol(s) != fSymbol) {
                    s += 6;
                }
            } else {
                s = oneState(c);
            }
            int successor = stSuccessor(s);
            if (successor != upBranch) {
                c = successor;
                if (numPs == 0) {
                    return c;
                }
                break;
            }
            psBuf[numPs++] = s;
        }

        int upSymbol = u8(upBranch);
        int upSuccessor = upBranch + 1;
        int upFreq;
        if (ctxNumStats(c) == 1) {
            upFreq = stFreq(oneState(c));
        } else {
            int s = ctxStats(c);
            while (stSymbol(s) != upSymbol) {
                s += 6;
            }
            int cf = stFreq(s) - 1;
            int s0 = ctxSummFreq(c) - ctxNumStats(c) - cf;
            upFreq = 1 + ((2 * cf <= s0) ? ((5 * cf > s0) ? 1 : 0)
                    : ((2 * cf + 3 * s0 - 1) / (2 * s0)));
        }

        while (numPs != 0) {
            int c1;
            if (hiUnitRef != loUnitRef) {
                hiUnitRef -= UNIT_SIZE;
                c1 = hiUnitRef;
            } else if (freeList[0] != 0) {
                c1 = removeNode(0);
            } else {
                c1 = allocUnitsRare(0);
                if (c1 == 0) {
                    return 0;
                }
            }
            setCtxNumStats(c1, 1);
            int one = oneState(c1);
            setStSymbol(one, upSymbol);
            setStFreq(one, upFreq);
            setStSuccessor(one, upSuccessor);
            setCtxSuffix(c1, c);
            setStSuccessor(psBuf[--numPs], c1);
            c = c1;
        }
        return c;
    }

    private void updateModel() {
        int fSuccessor = stSuccessor(foundState);
        int fSymbol = stSymbol(foundState);
        int fFreq = stFreq(foundState);

        if (fFreq < MAX_FREQ / 4 && ctxSuffix(minContext) != 0) {
            int c = ctxSuffix(minContext);
            if (ctxNumStats(c) == 1) {
                int s = oneState(c);
                if (stFreq(s) < 32) {
                    setStFreq(s, stFreq(s) + 1);
                }
            } else {
                int s = ctxStats(c);
                if (stSymbol(s) != fSymbol) {
                    do {
                        s += 6;
                    } while (stSymbol(s) != fSymbol);
                    if (stFreq(s) >= stFreq(s - 6)) {
                        swapStates(s, s - 6);
                        s -= 6;
                    }
                }
                if (stFreq(s) < MAX_FREQ - 9) {
                    setStFreq(s, stFreq(s) + 2);
                    setCtxSummFreq(c, ctxSummFreq(c) + 2);
                }
            }
        }

        if (orderFall == 0) {
            int created = createSuccessors(true);
            if (created == 0) {
                restartModel();
                return;
            }
            minContext = maxContext = created;
            setStSuccessor(foundState, created);
            return;
        }

        setU8(textRef, fSymbol);
        textRef++;
        int successor = textRef;
        if (textRef >= unitsStartRef) {
            restartModel();
            return;
        }

        if (fSuccessor != 0) {
            if (Integer.compareUnsigned(fSuccessor, successor) <= 0) {
                int cs = createSuccessors(false);
                if (cs == 0) {
                    restartModel();
                    return;
                }
                fSuccessor = cs;
            }
            if (--orderFall == 0) {
                successor = fSuccessor;
                if (maxContext != minContext) {
                    textRef--;
                }
            }
        } else {
            setStSuccessor(foundState, successor);
            fSuccessor = minContext;
        }

        int ns = ctxNumStats(minContext);
        int s0 = ctxSummFreq(minContext) - ns - (fFreq - 1);

        for (int c = maxContext; c != minContext; c = ctxSuffix(c)) {
            int ns1 = ctxNumStats(c);
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
                        int oldPtr = ctxStats(c);
                        copyUnits(ptr, oldPtr, oldNU);
                        insertNode(oldPtr, i);
                        setCtxStats(c, ptr);
                    }
                }
                int bump = ((2 * ns1 < ns) ? 1 : 0)
                        + 2 * ((((4 * ns1 <= ns) ? 1 : 0) & ((ctxSummFreq(c) <= 8 * ns1) ? 1 : 0)));
                setCtxSummFreq(c, (ctxSummFreq(c) + bump) & 0xFFFF);
            } else {
                int s = allocUnits(0);
                if (s == 0) {
                    restartModel();
                    return;
                }
                copyState(s, oneState(c));
                setCtxStats(c, s);
                int freq = stFreq(s);
                if (freq < MAX_FREQ / 4 - 1) {
                    freq <<= 1;
                } else {
                    freq = MAX_FREQ - 4;
                }
                setStFreq(s, freq);
                setCtxSummFreq(c, (freq + initEsc + ((ns > 3) ? 1 : 0)) & 0xFFFF);
            }

            long cf = 2L * fFreq * (ctxSummFreq(c) + 6);
            long sf = (long) s0 + ctxSummFreq(c);
            int newFreq;
            if (cf < 6 * sf) {
                newFreq = 1 + ((cf > sf) ? 1 : 0) + ((cf >= 4 * sf) ? 1 : 0);
                setCtxSummFreq(c, (ctxSummFreq(c) + 3) & 0xFFFF);
            } else {
                newFreq = 4 + ((cf >= 9 * sf) ? 1 : 0) + ((cf >= 12 * sf) ? 1 : 0)
                        + ((cf >= 15 * sf) ? 1 : 0);
                setCtxSummFreq(c, (ctxSummFreq(c) + newFreq) & 0xFFFF);
            }

            int sNew = ctxStats(c) + ns1 * 6;
            setStSuccessor(sNew, successor);
            setStSymbol(sNew, fSymbol);
            setStFreq(sNew, newFreq);
            setCtxNumStats(c, ns1 + 1);
        }
        maxContext = minContext = fSuccessor;
    }

    private void rescale() {
        int stats = ctxStats(minContext);
        int s = foundState;

        // Move the found state to the front.
        byte[] tmp = new byte[6];
        System.arraycopy(heap, s, tmp, 0, 6);
        for (; s != stats; s -= 6) {
            copyState(s, s - 6);
        }
        System.arraycopy(tmp, 0, heap, stats, 6);
        s = stats;

        int escFreq = ctxSummFreq(minContext) - stFreq(s);
        setStFreq(s, stFreq(s) + 4);
        int adder = (orderFall != 0) ? 1 : 0;
        setStFreq(s, (stFreq(s) + adder) >> 1);
        int sumFreq = stFreq(s);

        int i = ctxNumStats(minContext) - 1;
        do {
            s += 6;
            escFreq -= stFreq(s);
            setStFreq(s, (stFreq(s) + adder) >> 1);
            sumFreq += stFreq(s);
            if (stFreq(s) > stFreq(s - 6)) {
                int s1 = s;
                System.arraycopy(heap, s1, tmp, 0, 6);
                int tmpFreq = tmp[1] & 0xFF;
                do {
                    copyState(s1, s1 - 6);
                    s1 -= 6;
                } while (s1 != stats && tmpFreq > stFreq(s1 - 6));
                System.arraycopy(tmp, 0, heap, s1, 6);
            }
        } while (--i != 0);

        if (stFreq(s) == 0) {
            int numStats = ctxNumStats(minContext);
            i = 0;
            do {
                i++;
                s -= 6;
            } while (stFreq(s) == 0);
            escFreq += i;
            setCtxNumStats(minContext, numStats - i);
            if (ctxNumStats(minContext) == 1) {
                System.arraycopy(heap, stats, tmp, 0, 6);
                int tmpFreq = tmp[1] & 0xFF;
                do {
                    tmpFreq -= tmpFreq >> 1;
                    escFreq >>= 1;
                } while (escFreq > 1);
                tmp[1] = (byte) tmpFreq;
                insertNode(stats, u2i((numStats + 1) >> 1));
                foundState = oneState(minContext);
                System.arraycopy(tmp, 0, heap, foundState, 6);
                return;
            }
            int n0 = (numStats + 1) >> 1;
            int n1 = (ctxNumStats(minContext) + 1) >> 1;
            if (n0 != n1) {
                setCtxStats(minContext, shrinkUnits(stats, n0, n1));
            }
        }
        setCtxSummFreq(minContext, (sumFreq + escFreq - (escFreq >> 1)) & 0xFFFF);
        foundState = ctxStats(minContext);
    }

    private void nextContext() {
        int c = stSuccessor(foundState);
        if (orderFall == 0 && Integer.compareUnsigned(c, textRef) > 0) {
            minContext = maxContext = c;
        } else {
            updateModel();
        }
    }

    private void update1() {
        int s = foundState;
        setStFreq(s, stFreq(s) + 4);
        setCtxSummFreq(minContext, ctxSummFreq(minContext) + 4);
        if (stFreq(s) > stFreq(s - 6)) {
            swapStates(s, s - 6);
            s -= 6;
            foundState = s;
            if (stFreq(s) > MAX_FREQ) {
                rescale();
            }
        }
        nextContext();
    }

    private void update1_0() {
        prevSuccess = (2 * stFreq(foundState) > ctxSummFreq(minContext)) ? 1 : 0;
        runLength += prevSuccess;
        setCtxSummFreq(minContext, ctxSummFreq(minContext) + 4);
        setStFreq(foundState, stFreq(foundState) + 4);
        if (stFreq(foundState) > MAX_FREQ) {
            rescale();
        }
        nextContext();
    }

    private void updateBin() {
        int f = stFreq(foundState);
        if (f < 128) {
            setStFreq(foundState, f + 1);
        }
        prevSuccess = 1;
        runLength++;
        nextContext();
    }

    private void update2() {
        setCtxSummFreq(minContext, ctxSummFreq(minContext) + 4);
        setStFreq(foundState, stFreq(foundState) + 4);
        if (stFreq(foundState) > MAX_FREQ) {
            rescale();
        }
        runLength = initRL;
        updateModel();
    }

    // ---- symbol decode ----

    /**
     * Decodes one PPMd symbol from the attached range-coded stream.
     *
     * @return the symbol 0..255, or -1 (no suffix / model exhausted),
     *         or -2 (range threshold inconsistent with the model)
     */
    int decodeSymbol() {
        int preMc = minContext;
        int preNs = ctxNumStats(minContext);
        int preSf = ctxSummFreq(minContext);
        int preOf = orderFall;
        int prePs = prevSuccess;
        int preRl = runLength;
        int preLow = rdLow;
        int preCode = rdCode;
        int preRange = rdRange;

        int sym = decodeSymbolInner();

        if (trace != null) {
            trace.onSymbol(symbolIndex, preMc, preNs, preSf, preOf, prePs, preRl,
                    preLow, preCode, preRange, sym,
                    minContext, ctxNumStats(minContext), ctxSummFreq(minContext),
                    orderFall, textRef, loUnitRef, hiUnitRef);
        }
        symbolIndex++;
        return sym;
    }

    private int decodeSymbolInner() {
        if (ctxNumStats(minContext) != 1) {
            int s = ctxStats(minContext);
            int count = rdGetThreshold(ctxSummFreq(minContext));
            int hiCnt = stFreq(s);
            if (Integer.compareUnsigned(count, hiCnt) < 0) {
                rdDecode(0, stFreq(s));
                foundState = s;
                int symbol = stSymbol(s);
                update1_0();
                return symbol;
            }
            prevSuccess = 0;
            int i = ctxNumStats(minContext) - 1;
            do {
                s += 6;
                hiCnt += stFreq(s);
                if (Integer.compareUnsigned(hiCnt, count) > 0) {
                    rdDecode(hiCnt - stFreq(s), stFreq(s));
                    foundState = s;
                    int symbol = stSymbol(s);
                    update1();
                    return symbol;
                }
            } while (--i != 0);
            if (Integer.compareUnsigned(count, ctxSummFreq(minContext)) >= 0) {
                return -2;
            }
            hiBitsFlag = hb2Flag[stSymbol(foundState)];
            rdDecode(hiCnt, ctxSummFreq(minContext) - hiCnt);
            java.util.Arrays.fill(charMask, (byte) 0xFF);
            charMask[stSymbol(s)] = 0;
            i = ctxNumStats(minContext) - 1;
            do {
                s -= 6;
                charMask[stSymbol(s)] = 0;
            } while (--i != 0);
        } else {
            int one = oneState(minContext);
            int row = stFreq(one) - 1;
            int col = prevSuccess
                    + ns2BsIndx[ctxNumStats(ctxSuffix(minContext)) - 1]
                    + (hiBitsFlag = hb2Flag[stSymbol(foundState)])
                    + 2 * hb2Flag[stSymbol(one)]
                    + ((runLength >> 26) & 0x20);
            int prob = binSumm[row][col];
            if (rdDecodeBit(prob) == 0) {
                binSumm[row][col] = (prob + (1 << INT_BITS) - getMean(prob)) & 0xFFFF;
                foundState = one;
                int symbol = stSymbol(one);
                updateBin();
                return symbol;
            }
            prob = (prob - getMean(prob)) & 0xFFFF;
            binSumm[row][col] = prob;
            initEsc = EXP_ESCAPE[prob >>> 10];
            java.util.Arrays.fill(charMask, (byte) 0xFF);
            charMask[stSymbol(one)] = 0;
            prevSuccess = 0;
        }

        for (; ; ) {
            int masked = ctxNumStats(minContext);
            do {
                orderFall++;
                if (ctxSuffix(minContext) == 0) {
                    return -1;
                }
                minContext = ctxSuffix(minContext);
            } while (ctxNumStats(minContext) == masked);

            int hiCnt = 0;
            int s = ctxStats(minContext);
            int i = 0;
            int num = ctxNumStats(minContext) - masked;
            do {
                int k = charMask[stSymbol(s)]; // 0 (masked) or -1 (available)
                hiCnt += stFreq(s) & k;
                psBuf[i] = s;
                s += 6;
                i -= k;
            } while (i != num);

            makeEscFreq(masked);
            int seeIdx = makeEscFreqSeeIndex;
            int freqSum = makeEscFreqValue + hiCnt;
            int count = rdGetThreshold(freqSum);

            if (Integer.compareUnsigned(count, hiCnt) < 0) {
                int pi = 0;
                int acc = 0;
                for (; ; ) {
                    acc += stFreq(psBuf[pi]);
                    if (Integer.compareUnsigned(acc, count) > 0) {
                        break;
                    }
                    pi++;
                }
                int sFound = psBuf[pi];
                rdDecode(acc - stFreq(sFound), stFreq(sFound));
                seeUpdate(seeIdx);
                foundState = sFound;
                int symbol = stSymbol(sFound);
                update2();
                return symbol;
            }
            if (Integer.compareUnsigned(count, freqSum) >= 0) {
                return -2;
            }
            rdDecode(hiCnt, freqSum - hiCnt);
            seeAddSumm(seeIdx, freqSum);
            do {
                charMask[stSymbol(psBuf[--i])] = 0;
            } while (i != 0);
        }
    }

    private static int getMean(int summ) {
        return (summ + (1 << (PERIOD_BITS - 2))) >>> PERIOD_BITS;
    }
}
