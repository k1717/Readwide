package com.textview.reader.archive;

/**
 * Original Java implementation of the RAR5 (compression algorithm version
 * 5.0) decompressor: block-structured bitstream, five canonical Huffman
 * tables, an LZ77 window with a four-entry distance cache, and the DELTA /
 * x86 / ARM post-processing filters.
 *
 * <p>Decode-only. This class never encodes and never encrypts. No source
 * code, tables, or constants were copied from RARLAB UnRAR or libarchive;
 * reference implementations were consulted strictly for behavioural
 * comparison during development. The decoded output of every entry is
 * CRC-verified by the callers before being reported as extracted.</p>
 *
 * <p>Solid semantics: the LZ window, the distance cache, the last match
 * length, and the current Huffman tables persist from one solid entry to
 * the next; the per-file write pointer restarts at zero while a solid
 * offset accumulates so window addressing stays continuous. A solid entry
 * arriving before any non-solid predecessor has primed the window is an
 * error, never a guess.</p>
 */
final class Rar5CompressedDecoder {

    /** Thrown when the compressed stream is truncated or inconsistent. */
    static final class Rar5DataException extends RuntimeException {
        Rar5DataException(String message) {
            super(message);
        }
    }

    // ---- bitstream / table geometry of the RAR5 format ----
    private static final int BIT_LENGTH_TABLE_SIZE = 20;
    private static final int MAIN_TABLE_SIZE = 306;
    private static final int DIST_TABLE_SIZE = 64;
    private static final int LOW_DIST_TABLE_SIZE = 16;
    private static final int REP_LEN_TABLE_SIZE = 44;
    private static final int ALL_TABLES_SIZE =
            MAIN_TABLE_SIZE + DIST_TABLE_SIZE + LOW_DIST_TABLE_SIZE + REP_LEN_TABLE_SIZE;

    private static final int FILTER_DELTA = 0;
    private static final int FILTER_E8 = 1;
    private static final int FILTER_E8E9 = 2;
    private static final int FILTER_ARM = 3;

    private static final long BASE_WINDOW_SIZE = 0x20000L; // 128 KiB
    private static final long MAX_WINDOW_SIZE = 64L * 1024 * 1024; // format limit for v5.0

    // ---- canonical Huffman decode table ----
    private static final class DecodeTable {
        int size;
        int quickBits;
        final int[] decodeLen = new int[16];
        final int[] decodePos = new int[16];
        final int[] quickLen = new int[1 << 10];
        final int[] quickNum = new int[1 << 10];
        final int[] decodeNum = new int[MAIN_TABLE_SIZE];
        boolean valid;
    }

    private final DecodeTable bitLengthTable = new DecodeTable();
    private final DecodeTable mainTable = new DecodeTable();
    private final DecodeTable distTable = new DecodeTable();
    private final DecodeTable lowDistTable = new DecodeTable();
    private final DecodeTable repLenTable = new DecodeTable();

    // ---- persistent (solid-carrying) state ----
    private byte[] window;
    private long windowSize;
    private long windowMask;
    private long solidOffset;
    private final int[] distCache = new int[4];
    private int lastLength;
    private boolean primed; // at least one entry fully decoded since last reset

    // ---- per-entry state ----
    private byte[] input;       // packed payload + zero padding
    private int payloadLength;  // valid packed bytes (without padding)
    private long writePtr;      // file-relative produced byte count
    private byte[] out;         // produced bytes of the current entry
    private int blockStart;     // offset of the current block's data in `input`
    private int blockSize;      // size of the current block's data
    private int blockBitSize;   // valid bits in the final byte of the block
    private boolean lastBlock;
    private int inAddr;         // bit reader position, relative to blockStart
    private int bitAddr;
    private final java.util.ArrayList<FilterInfo> filters = new java.util.ArrayList<>();

    private static final class FilterInfo {
        int type;
        long blockStart;
        int blockLength;
        int channels;
    }

    private long lastFilterBlockStart;
    private long lastFilterBlockLength;

    /** Discards all solid state; the next entry must be non-solid. */
    void reset() {
        window = null;
        windowSize = 0;
        windowMask = 0;
        solidOffset = 0;
        java.util.Arrays.fill(distCache, 0);
        lastLength = 0;
        primed = false;
        invalidateTables();
    }

    private void invalidateTables() {
        bitLengthTable.valid = false;
        mainTable.valid = false;
        distTable.valid = false;
        lowDistTable.valid = false;
        repLenTable.valid = false;
    }

    /**
     * Decodes one RAR5 v5.0 compressed entry.
     *
     * @param packed          packed payload bytes of the entry
     * @param unpackedSize    expected unpacked size
     * @param compressionInfo raw compression-info field from the file header
     * @return exactly {@code unpackedSize} decoded bytes
     */
    byte[] decodeEntry(byte[] packed, long unpackedSize, long compressionInfo) {
        int algoVersion = (int) (compressionInfo & 0x3F);
        if (algoVersion != 0) {
            throw new Rar5DataException(
                    "Unsupported RAR5 compression algorithm version: " + algoVersion);
        }
        boolean solid = (compressionInfo & 0x40L) != 0;
        long declaredWindow = BASE_WINDOW_SIZE << ((compressionInfo >> 10) & 15);
        if (declaredWindow > MAX_WINDOW_SIZE) {
            throw new Rar5DataException(
                    "Declared RAR5 dictionary size is not supported: " + declaredWindow);
        }
        if (unpackedSize < 0 || unpackedSize > Integer.MAX_VALUE - 8) {
            throw new Rar5DataException(
                    "RAR5 entry unpacked size is outside supported bounds: " + unpackedSize);
        }

        if (solid) {
            if (window == null || !primed) {
                throw new Rar5DataException(
                        "RAR5 solid entry arrived before any predecessor primed the window; "
                                + "solid predecessors must be decoded first");
            }
            if (declaredWindow != windowSize) {
                throw new Rar5DataException(
                        "RAR5 solid entry declares a different window size ("
                                + declaredWindow + " vs " + windowSize + ")");
            }
            solidOffset += writePtr;
        } else {
            window = new byte[(int) declaredWindow];
            windowSize = declaredWindow;
            windowMask = declaredWindow - 1;
            solidOffset = 0;
            java.util.Arrays.fill(distCache, 0);
            lastLength = 0;
            invalidateTables();
            primed = false;
        }

        this.input = new byte[packed.length + 8];
        System.arraycopy(packed, 0, this.input, 0, packed.length);
        this.payloadLength = packed.length;
        this.writePtr = 0;
        this.out = new byte[(int) unpackedSize];
        this.filters.clear();
        this.lastFilterBlockStart = 0;
        this.lastFilterBlockLength = 0;
        this.blockSize = 0;
        this.lastBlock = false;

        int nextBlockOffset = 0;
        boolean blockOpen = false;

        while (writePtr < unpackedSize) {
            if (!blockOpen || blockExhausted()) {
                if (blockOpen && lastBlock) {
                    throw new Rar5DataException(
                            "RAR5 stream ended after " + writePtr + " of " + unpackedSize
                                    + " bytes");
                }
                nextBlockOffset = openBlock(nextBlockOffset);
                blockOpen = true;
                continue;
            }
            decodeOneSymbol();
        }

        // Strict end-of-entry validation: the producing symbol must have been
        // the final symbol of the final block, otherwise the stream is not
        // aligned the way a well-formed archive would be.
        if (writePtr != unpackedSize) {
            throw new Rar5DataException(
                    "RAR5 stream overshot the declared unpacked size: produced "
                            + writePtr + " of " + unpackedSize);
        }
        if (blockOpen && !blockExhausted()) {
            // Allow trailing filter declarations in the final block.
            drainTrailingNonDataSymbols();
        }
        if (!lastBlock) {
            // Tolerate trailing empty/filter-only blocks until the last one.
            while (!lastBlock) {
                nextBlockOffset = openBlock(nextBlockOffset);
                drainTrailingNonDataSymbols();
            }
        }

        applyFilters();
        primed = true;
        return out;
    }

    // ---- block management ----

    private boolean blockExhausted() {
        return inAddr > blockSize - 1
                || (inAddr == blockSize - 1 && bitAddr >= blockBitSize);
    }

    /** Parses a block header at the given payload offset; returns the offset just past the block. */
    private int openBlock(int offset) {
        if (offset + 3 > payloadLength) {
            throw new Rar5DataException("Truncated RAR5 block header");
        }
        int flags = input[offset] & 0xFF;
        int checksum = input[offset + 1] & 0xFF;
        int byteCount = (flags >> 3) & 7;
        if (byteCount > 2) {
            throw new Rar5DataException(
                    "Unsupported RAR5 block header size: " + byteCount);
        }
        int headerLen = 2 + byteCount + 1;
        if (offset + headerLen > payloadLength) {
            throw new Rar5DataException("Truncated RAR5 block header");
        }
        int size = 0;
        for (int i = 0; i <= byteCount; i++) {
            size |= (input[offset + 2 + i] & 0xFF) << (8 * i);
        }
        int calculated = 0x5A ^ flags ^ (size & 0xFF) ^ ((size >> 8) & 0xFF) ^ ((size >> 16) & 0xFF);
        if ((calculated & 0xFF) != checksum) {
            throw new Rar5DataException("RAR5 block header checksum mismatch");
        }
        int dataStart = offset + headerLen;
        if (dataStart + size > payloadLength) {
            throw new Rar5DataException("RAR5 block extends past the packed payload");
        }

        this.blockStart = dataStart;
        this.blockSize = size;
        this.blockBitSize = 1 + (flags & 7);
        this.lastBlock = ((flags >> 6) & 1) != 0;
        this.inAddr = 0;
        this.bitAddr = 0;

        boolean tablePresent = ((flags >> 7) & 1) != 0;
        if (tablePresent) {
            parseTables();
        } else if (!mainTable.valid) {
            throw new Rar5DataException(
                    "RAR5 block reuses Huffman tables, but no tables are available");
        }
        return dataStart + size;
    }

    // ---- bit reader (positions relative to blockStart) ----

    private int peekBits16() {
        if (inAddr >= blockSize) {
            throw new Rar5DataException("Premature end of RAR5 block data");
        }
        int base = blockStart + inAddr;
        int bits = (input[base] & 0xFF) << 16;
        bits |= (input[base + 1] & 0xFF) << 8;
        bits |= input[base + 2] & 0xFF;
        bits >>= 8 - bitAddr;
        return bits & 0xFFFF;
    }

    private long peekBits32() {
        if (inAddr >= blockSize) {
            throw new Rar5DataException("Premature end of RAR5 block data");
        }
        int base = blockStart + inAddr;
        long bits = ((long) (input[base] & 0xFF)) << 24;
        bits |= (input[base + 1] & 0xFF) << 16;
        bits |= (input[base + 2] & 0xFF) << 8;
        bits |= input[base + 3] & 0xFF;
        bits = (bits << bitAddr) & 0xFFFFFFFFL;
        bits |= (input[base + 4] & 0xFF) >> (8 - bitAddr);
        return bits;
    }

    private void skipBits(int bits) {
        int next = bitAddr + bits;
        inAddr += next >> 3;
        bitAddr = next & 7;
    }

    private int readBits(int n) {
        int v = peekBits16() >> (16 - n);
        skipBits(n);
        return v;
    }

    // ---- canonical Huffman tables ----

    private void createDecodeTable(byte[] bitLength, int off, DecodeTable table, int size) {
        int[] lengthCount = new int[16];
        java.util.Arrays.fill(table.decodeNum, 0, size, 0);
        table.size = size;
        table.quickBits = size == MAIN_TABLE_SIZE ? 10 : 7;

        for (int i = 0; i < size; i++) {
            lengthCount[bitLength[off + i] & 15]++;
        }
        lengthCount[0] = 0;
        table.decodePos[0] = 0;
        table.decodeLen[0] = 0;
        int upperLimit = 0;
        for (int i = 1; i < 16; i++) {
            upperLimit += lengthCount[i];
            table.decodeLen[i] = upperLimit << (16 - i);
            table.decodePos[i] = table.decodePos[i - 1] + lengthCount[i - 1];
            upperLimit <<= 1;
        }

        int[] posClone = table.decodePos.clone();
        for (int i = 0; i < size; i++) {
            int len = bitLength[off + i] & 15;
            if (len > 0) {
                table.decodeNum[posClone[len]] = i;
                posClone[len]++;
            }
        }

        int quickDataSize = 1 << table.quickBits;
        int curLen = 1;
        for (int code = 0; code < quickDataSize; code++) {
            int bitField = code << (16 - table.quickBits);
            while (curLen < 16 && bitField >= table.decodeLen[curLen]) {
                curLen++;
            }
            table.quickLen[code] = curLen;
            int dist = bitField - table.decodeLen[curLen - 1];
            dist >>= 16 - curLen;
            int pos = table.decodePos[curLen & 15] + dist;
            if (curLen < 16 && pos < size) {
                table.quickNum[code] = table.decodeNum[pos];
            } else {
                table.quickNum[code] = 0;
            }
        }
        table.valid = true;
    }

    private int decodeNumber(DecodeTable table) {
        int bitField = peekBits16() & 0xFFFE;
        if (bitField < table.decodeLen[table.quickBits]) {
            int code = bitField >> (16 - table.quickBits);
            skipBits(table.quickLen[code]);
            return table.quickNum[code];
        }
        int bits = 15;
        for (int i = table.quickBits + 1; i < 15; i++) {
            if (bitField < table.decodeLen[i]) {
                bits = i;
                break;
            }
        }
        skipBits(bits);
        int dist = bitField - table.decodeLen[bits - 1];
        dist >>= 16 - bits;
        int pos = table.decodePos[bits] + dist;
        if (pos >= table.size) {
            pos = 0;
        }
        return table.decodeNum[pos];
    }

    /** Parses the Huffman tables from the start of the current block. */
    private void parseTables() {
        byte[] bitLength = new byte[BIT_LENGTH_TABLE_SIZE];
        int i = 0;
        int nibbleShift = 4;
        for (int w = 0; w < BIT_LENGTH_TABLE_SIZE; ) {
            if (i >= blockSize) {
                throw new Rar5DataException("Truncated RAR5 Huffman bit-length table");
            }
            int value = (input[blockStart + i] >> nibbleShift) & 0x0F;
            if (nibbleShift == 0) {
                i++;
            }
            nibbleShift ^= 4;
            if (value == 15) {
                if (i >= blockSize) {
                    throw new Rar5DataException("Truncated RAR5 Huffman bit-length table");
                }
                value = (input[blockStart + i] >> nibbleShift) & 0x0F;
                if (nibbleShift == 0) {
                    i++;
                }
                nibbleShift ^= 4;
                if (value == 0) {
                    bitLength[w++] = 15;
                } else {
                    for (int k = 0; k < value + 2 && w < BIT_LENGTH_TABLE_SIZE; k++) {
                        bitLength[w++] = 0;
                    }
                }
            } else {
                bitLength[w++] = (byte) value;
            }
        }
        inAddr = i;
        bitAddr = nibbleShift ^ 4;

        createDecodeTable(bitLength, 0, bitLengthTable, BIT_LENGTH_TABLE_SIZE);

        byte[] table = new byte[ALL_TABLES_SIZE];
        for (int idx = 0; idx < ALL_TABLES_SIZE; ) {
            int num = decodeNumber(bitLengthTable);
            if (num < 16) {
                table[idx++] = (byte) num;
            } else if (num < 18) {
                int n;
                if (num == 16) {
                    n = (peekBits16() >> 13) + 3;
                    skipBits(3);
                } else {
                    n = (peekBits16() >> 9) + 11;
                    skipBits(7);
                }
                if (idx == 0) {
                    throw new Rar5DataException(
                            "RAR5 Huffman table starts with a repeat code");
                }
                while (n-- > 0 && idx < ALL_TABLES_SIZE) {
                    table[idx] = table[idx - 1];
                    idx++;
                }
            } else {
                int n;
                if (num == 18) {
                    n = (peekBits16() >> 13) + 3;
                    skipBits(3);
                } else {
                    n = (peekBits16() >> 9) + 11;
                    skipBits(7);
                }
                while (n-- > 0 && idx < ALL_TABLES_SIZE) {
                    table[idx++] = 0;
                }
            }
        }

        int off = 0;
        createDecodeTable(table, off, mainTable, MAIN_TABLE_SIZE);
        off += MAIN_TABLE_SIZE;
        createDecodeTable(table, off, distTable, DIST_TABLE_SIZE);
        off += DIST_TABLE_SIZE;
        createDecodeTable(table, off, lowDistTable, LOW_DIST_TABLE_SIZE);
        off += LOW_DIST_TABLE_SIZE;
        createDecodeTable(table, off, repLenTable, REP_LEN_TABLE_SIZE);
    }

    // ---- LZ decode ----

    private void decodeOneSymbol() {
        int num = decodeNumber(mainTable);
        if (num < 256) {
            emitByte((byte) num);
        } else if (num >= 262) {
            int len = decodeCodeLength(num - 262);
            int distSlot = decodeNumber(distTable);
            int dist = 1;
            int dBits;
            if (distSlot < 4) {
                dBits = 0;
                dist += distSlot;
            } else {
                dBits = distSlot / 2 - 1;
                dist += (2 | (distSlot & 1)) << dBits;
            }
            if (dBits > 0) {
                if (dBits >= 4) {
                    if (dBits > 4) {
                        long add = peekBits32();
                        skipBits(dBits - 4);
                        add = (add >>> (36 - dBits)) << 4;
                        dist += (int) add;
                    }
                    int lowDist = decodeNumber(lowDistTable);
                    dist += lowDist;
                } else {
                    dist += readBits(dBits);
                }
            }
            if (dist > 0x100) {
                len++;
                if (dist > 0x2000) {
                    len++;
                    if (dist > 0x40000) {
                        len++;
                    }
                }
            }
            distCachePush(dist);
            lastLength = len;
            copyString(len, dist);
        } else if (num == 256) {
            parseFilter();
        } else if (num == 257) {
            if (lastLength != 0) {
                copyString(lastLength, distCache[0]);
            }
        } else {
            // 258..261: distance-cache repetition
            int dist = distCacheTouch(num - 258);
            int lenSlot = decodeNumber(repLenTable);
            int len = decodeCodeLength(lenSlot);
            lastLength = len;
            copyString(len, dist);
        }
    }

    private int decodeCodeLength(int code) {
        int length = 2;
        int lBits;
        if (code < 8) {
            lBits = 0;
            length += code;
        } else {
            lBits = code / 4 - 1;
            length += (4 | (code & 3)) << lBits;
        }
        if (lBits > 0) {
            length += readBits(lBits);
        }
        return length;
    }

    private void distCachePush(int value) {
        distCache[3] = distCache[2];
        distCache[2] = distCache[1];
        distCache[1] = distCache[0];
        distCache[0] = value;
    }

    private int distCacheTouch(int idx) {
        int dist = distCache[idx];
        for (int i = idx; i > 0; i--) {
            distCache[i] = distCache[i - 1];
        }
        distCache[0] = dist;
        return dist;
    }

    private void emitByte(byte b) {
        long writeIdx = (solidOffset + writePtr) & windowMask;
        window[(int) writeIdx] = b;
        if (writePtr >= out.length) {
            throw new Rar5DataException(
                    "RAR5 stream produced more data than the declared unpacked size");
        }
        out[(int) writePtr] = b;
        writePtr++;
    }

    private void copyString(int len, int dist) {
        if (dist <= 0 || (long) dist > windowSize) {
            throw new Rar5DataException("RAR5 match distance is outside the window: " + dist);
        }
        long base = solidOffset + writePtr;
        for (int i = 0; i < len; i++) {
            byte b = window[(int) ((base + i - dist) & windowMask)];
            window[(int) ((base + i) & windowMask)] = b;
            if (writePtr + i >= out.length) {
                throw new Rar5DataException(
                        "RAR5 match overshot the declared unpacked size");
            }
            out[(int) (writePtr + i)] = b;
        }
        writePtr += len;
    }

    /** Consumes remaining non-data symbols (filters / no-op reps) at the end of an entry. */
    private void drainTrailingNonDataSymbols() {
        while (!blockExhausted()) {
            int num = decodeNumber(mainTable);
            if (num == 256) {
                parseFilter();
                continue;
            }
            throw new Rar5DataException(
                    "RAR5 stream desynchronised: data symbol " + num
                            + " after the declared unpacked size was produced");
        }
    }

    // ---- filters ----

    private int parseFilterData() {
        int bytes = readBits(2) + 1;
        int data = 0;
        for (int i = 0; i < bytes; i++) {
            int b = peekBits16() >> 8;
            skipBits(8);
            data += b << (i * 8);
        }
        return data;
    }

    private void parseFilter() {
        long start = parseFilterData() & 0xFFFFFFFFL;
        long length = parseFilterData() & 0xFFFFFFFFL;
        int type = peekBits16() >> 13;
        skipBits(3);

        long absStart = writePtr + start;
        if (length < 4 || length > 0x400000 || type > FILTER_ARM
                || !isValidFilterBlockStart(absStart)) {
            throw new Rar5DataException("Invalid RAR5 filter declaration");
        }
        FilterInfo filter = new FilterInfo();
        filter.type = type;
        filter.blockStart = absStart;
        filter.blockLength = (int) length;
        lastFilterBlockStart = filter.blockStart;
        lastFilterBlockLength = filter.blockLength;
        if (type == FILTER_DELTA) {
            filter.channels = readBits(5) + 1;
        }
        filters.add(filter);
    }

    private boolean isValidFilterBlockStart(long absStart) {
        if (lastFilterBlockStart == 0 || lastFilterBlockLength == 0) {
            return true;
        }
        return absStart >= lastFilterBlockStart + lastFilterBlockLength;
    }

    /**
     * Applies queued filters to the produced output of this entry. Filters
     * transform the output stage only; the LZ window keeps unfiltered data,
     * which matches the format's solid semantics.
     */
    private void applyFilters() {
        for (FilterInfo filter : filters) {
            long start = filter.blockStart;
            int length = filter.blockLength;
            if (start < 0 || start + length > out.length) {
                throw new Rar5DataException(
                        "RAR5 filter range is outside the entry output");
            }
            int s = (int) start;
            byte[] filtered = new byte[length];
            switch (filter.type) {
                case FILTER_DELTA: {
                    int srcPos = 0;
                    for (int channel = 0; channel < filter.channels; channel++) {
                        int prev = 0;
                        for (int destPos = channel; destPos < length;
                                destPos += filter.channels) {
                            prev = (prev - (out[s + srcPos] & 0xFF)) & 0xFF;
                            filtered[destPos] = (byte) prev;
                            srcPos++;
                        }
                    }
                    break;
                }
                case FILTER_E8:
                case FILTER_E8E9: {
                    boolean extended = filter.type == FILTER_E8E9;
                    System.arraycopy(out, s, filtered, 0, length);
                    final long fileSize = 0x1000000L;
                    for (int i = 0; i < length - 4; ) {
                        int b = out[s + i++] & 0xFF;
                        if (b == 0xE8 || (extended && b == 0xE9)) {
                            long offset = (i + start) % fileSize;
                            long addr = readLe32(out, s + i);
                            if ((addr & 0x80000000L) != 0) {
                                if (((addr + offset) & 0x80000000L) == 0) {
                                    writeLe32(filtered, i, addr + fileSize);
                                }
                            } else {
                                if (((addr - fileSize) & 0x80000000L) != 0) {
                                    writeLe32(filtered, i, addr - offset);
                                }
                            }
                            i += 4;
                        }
                    }
                    break;
                }
                case FILTER_ARM: {
                    System.arraycopy(out, s, filtered, 0, length);
                    for (int i = 0; i + 3 < length; i += 4) {
                        if ((out[s + i + 3] & 0xFF) == 0xEB) {
                            long offset = readLe32(out, s + i) & 0x00FFFFFFL;
                            offset -= (i + start) / 4;
                            offset = (offset & 0x00FFFFFFL) | 0xEB000000L;
                            writeLe32(filtered, i, offset);
                        }
                    }
                    break;
                }
                default:
                    throw new Rar5DataException(
                            "Unsupported RAR5 filter type: " + filter.type);
            }
            System.arraycopy(filtered, 0, out, s, length);
        }
        filters.clear();
    }

    private static long readLe32(byte[] buf, int off) {
        return (buf[off] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24);
    }

    private static void writeLe32(byte[] buf, int off, long value) {
        buf[off] = (byte) value;
        buf[off + 1] = (byte) (value >>> 8);
        buf[off + 2] = (byte) (value >>> 16);
        buf[off + 3] = (byte) (value >>> 24);
    }
}
