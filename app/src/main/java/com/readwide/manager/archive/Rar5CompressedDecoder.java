package com.readwide.manager.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Original Java implementation of the RAR5 container's compression algorithm
 * versions 0 (RAR 5/6) and 1 (RAR 7) decompressor: block-structured bitstream, five canonical Huffman
 * tables, an LZ77 window with a four-entry distance cache, and the DELTA /
 * x86 / ARM post-processing filters.
 *
 * <p>Decode-only. This class never encodes and never encrypts. No source
 * code, tables, or constants were copied from RARLAB UnRAR or libarchive;
 * reference implementations were consulted strictly for behavioural
 * comparison during development. Callers verify supported CRC32/BLAKE2sp,
 * including encrypted HashMAC, before committing output.</p>
 *
 * <p>Solid semantics: the LZ window, the distance cache, the last match
 * length, and the current Huffman tables persist from one solid entry to
 * the next; the per-file write pointer restarts at zero while a solid
 * offset accumulates so window addressing stays continuous. A solid entry
 * arriving before any non-solid predecessor has primed the window is an
 * error, never a guess.</p>
 */
final class Rar5CompressedDecoder implements java.io.Closeable {

    /** Thrown when the compressed stream is truncated or inconsistent. */
    static final class Rar5DataException extends RuntimeException {
        Rar5DataException(String message) {
            super(message);
        }
    }

    // ---- bitstream / table geometry of the RAR5 format ----
    private static final int BIT_LENGTH_TABLE_SIZE = 20;
    private static final int MAIN_TABLE_SIZE = 306;
    private static final int DIST_TABLE_SIZE_V0 = 64;
    private static final int DIST_TABLE_SIZE_V1 = 80;
    private static final int LOW_DIST_TABLE_SIZE = 16;
    private static final int REP_LEN_TABLE_SIZE = 44;
    private static final int FILTER_DELTA = 0;
    private static final int FILTER_E8 = 1;
    private static final int FILTER_E8E9 = 2;
    private static final int FILTER_ARM = 3;
    // Pending descriptor memory, not an entry/archive payload-size ceiling.
    static final int MAX_PENDING_FILTERS = 8192;

    private static final long BASE_WINDOW_SIZE = 0x20000L; // 128 KiB
    private static final long MAX_DECLARED_WINDOW_SIZE = 1L << 40; // RAR7 field limit: 1 TiB
    private static final long RAR7_V0_SOLID_COMPAT = 0x100000L;

    // ---- canonical Huffman decode table ----
    private static final class DecodeTable {
        int size;
        int symbolCount;
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
    private Rar5HistoryStore window;
    private long windowSize;
    private long solidOffset;
    private final long[] distCache = new long[4];
    private int lastLength;
    private boolean primed; // at least one entry fully decoded since last reset
    private int distanceTableSize = DIST_TABLE_SIZE_V0;

    // ---- per-entry state ----
    private byte[] input;       // one compressed block + zero padding
    private InputStream packedInput;
    private long writePtr;      // file-relative produced byte count
    private long expectedOutput; // Exact known size, or signed-position headroom for unknown size.
    private OutputStream output;
    private final byte[] outputChunk = new byte[64 * 1024];
    private int outputCount;
    private byte[] filterInput;
    private int filterCount;
    private int blockStart;     // offset of the current block's data in `input`
    private int blockSize;      // size of the current block's data
    private int blockBitSize;   // valid bits in the final byte of the block
    private int blockBits;      // excludes zero padding used only for lookahead
    private boolean lastBlock;
    private int inAddr;         // bit reader position, relative to blockStart
    private int bitAddr;
    private final java.util.ArrayDeque<FilterInfo> filters = new java.util.ArrayDeque<>();

    private static final class FilterInfo {
        int type;
        long blockStart;
        int blockLength;
        int channels;
    }

    private long lastFilterBlockStart;
    private long lastFilterBlockLength;

    /** Discards all solid state; the next entry must be non-solid. */
    void reset() throws IOException {
        Rar5HistoryStore previous = window;
        windowSize = 0;
        solidOffset = 0;
        java.util.Arrays.fill(distCache, 0);
        lastLength = 0;
        primed = false;
        invalidateTables();
        if (previous != null) previous.close();
        // Keep a failed-cleanup owner reachable so a later close can retry deletion.
        window = null;
    }

    @Override public void close() throws IOException {
        packedInput = null;
        output = null;
        input = null;
        filterInput = null;
        filters.clear();
        java.util.Arrays.fill(outputChunk, (byte) 0);
        reset();
    }

    private void invalidateTables() {
        bitLengthTable.valid = false;
        mainTable.valid = false;
        distTable.valid = false;
        lowDistTable.valid = false;
        repLenTable.valid = false;
    }

    /**
     * Decodes one compressed entry in the RAR5 container. WinRAR 6 continues
     * to write algorithm version 0. WinRAR 7 can write version 1, which uses
     * 80 distance codes and an extended, potentially non-power-of-two
     * dictionary-size field.
     *
     * @param packed          packed payload bytes of the entry
     * @param unpackedSize    expected unpacked size
     * @param compressionInfo raw compression-info field from the file header
     * @return exactly {@code unpackedSize} decoded bytes
     */
    byte[] decodeEntry(byte[] packed, long unpackedSize, long compressionInfo) {
        // Convenience API for small fixtures only. Production extraction streams.
        if (unpackedSize < 0 || unpackedSize > Integer.MAX_VALUE - 8) {
            throw new Rar5DataException("Byte-array result cannot represent this entry size");
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try {
            decodeEntry(new ByteArrayInputStream(packed), unpackedSize, compressionInfo, result);
        } catch (IOException e) {
            throw new Rar5DataException(e.getMessage());
        }
        return result.toByteArray();
    }

    /** Streams one entry; -1 means decode to the explicit last compressed block.
     * No full packed or unpacked payload is retained.
     */
    void decodeEntry(InputStream packed, long unpackedSize, long compressionInfo,
                     OutputStream destination) throws IOException {
        decodeEntry(packed, unpackedSize, compressionInfo, destination, false);
    }

    /** The extractor may identify a zero-packed-byte file in a solid archive from metadata.
     * A non-solid file resets history, including when it is empty. A solid file
     * still requires a primed predecessor; this mode never invents its history.
     * Never infer it from an unexpected EOF in an ordinary compressed entry.
     */
    void decodeEntry(InputStream packed, long unpackedSize, long compressionInfo,
                     OutputStream destination, boolean emptyPackedEntry) throws IOException {
        decodeEntry(packed, unpackedSize, compressionInfo, destination, emptyPackedEntry, 0);
    }

    /** A reset-only storage hint from the complete solid-run metadata, not a match-distance limit. */
    void decodeEntry(InputStream packed, long unpackedSize, long compressionInfo,
                     OutputStream destination, boolean emptyPackedEntry, long plannedHistorySize) throws IOException {
        try {
            decodeEntryInternal(packed, unpackedSize, compressionInfo, destination, emptyPackedEntry,
                    plannedHistorySize);
        } catch (IOException | RuntimeException | Error failure) {
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            packedInput = null;
            output = null;
        }
    }

    private void decodeEntryInternal(InputStream packed, long unpackedSize, long compressionInfo,
                                     OutputStream destination, boolean emptyPackedEntry,
                                     long plannedHistorySize) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("RAR5 entry decoding cancelled");
        }
        int algoVersion = (int) (compressionInfo & 0x3F);
        if (algoVersion != 0 && algoVersion != 1) {
            throw new Rar5DataException(
                    "Unsupported RAR5 compression algorithm version: " + algoVersion);
        }
        boolean solid = (compressionInfo & 0x40L) != 0;
        if (plannedHistorySize < 0 || plannedHistorySize > MAX_DECLARED_WINDOW_SIZE
                || (solid && plannedHistorySize != 0)) {
            throw new Rar5DataException("Invalid RAR solid history reservation");
        }
        if (emptyPackedEntry) {
            if (unpackedSize != 0 || readCheckedPackedByte(packed) != -1) {
                throw new Rar5DataException("RAR5 empty solid entry has inconsistent metadata or payload");
            }
        }
        long declaredWindow = declaredWindowSize(compressionInfo);
        int entryDistanceTableSize = usesExtendedDistanceTable(compressionInfo)
                ? DIST_TABLE_SIZE_V1 : DIST_TABLE_SIZE_V0;
        if (unpackedSize < -1) {
            throw new Rar5DataException(
                    "RAR5 entry unpacked size is outside supported bounds: " + unpackedSize);
        }

        if (solid) {
            if (window == null || !primed) {
                throw new Rar5DataException(
                        "RAR5 solid entry arrived before any predecessor primed the window; "
                                + "solid predecessors must be decoded first");
            }
            if (solidOffset > Long.MAX_VALUE - writePtr
                    || (unpackedSize >= 0 && solidOffset + writePtr > Long.MAX_VALUE - unpackedSize)) {
                throw new Rar5DataException("RAR5 solid position overflow");
            }
            // The header declares a minimum dictionary. Keep a larger allocation;
            // Planned archive reads reserve the run's maximum before decoding.
            // Unplanned growth is still rejected if history has already been lost.
            window.ensureCapacity(declaredWindow);
            windowSize = Math.max(windowSize, declaredWindow);
            solidOffset += writePtr;
        } else {
            // Retain the full declared logical history using bounded RAM and
            // encrypted temporary pages. No allocation proportional to the header.
            reset();
            window = new Rar5HistoryStore(Math.max(declaredWindow, plannedHistorySize));
            windowSize = declaredWindow;
            solidOffset = 0;
            java.util.Arrays.fill(distCache, 0);
            lastLength = 0;
            invalidateTables();
            primed = false;
        }
        distanceTableSize = entryDistanceTableSize;

        this.packedInput = packed;
        this.writePtr = 0;
        // Unknown output is limited only by representable history positions, not
        // an arbitrary payload cap. The same emit/match/filter guards prevent overflow.
        this.expectedOutput = unpackedSize < 0 ? Long.MAX_VALUE - solidOffset : unpackedSize;
        this.output = destination;
        this.outputCount = 0;
        this.filterInput = null;
        this.filterCount = 0;
        this.filters.clear();
        this.lastFilterBlockStart = 0;
        this.lastFilterBlockLength = 0;
        this.blockSize = 0;
        // A metadata-confirmed empty entry has no compressed block at all.
        // Still perform ordinary reset/solid setup, overflow checks and completion.
        this.lastBlock = emptyPackedEntry;

        // A physical EOF is never a successful terminator. Both known and unknown
        // sizes use the same block grammar and must consume the explicit last block.
        while (!lastBlock) {
            openBlock();
            decodeBlockSymbols();
        }
        if (unpackedSize >= 0 && writePtr != unpackedSize) {
            throw new Rar5DataException(
                    "RAR5 stream did not match the declared unpacked size: produced "
                            + writePtr + " of " + unpackedSize);
        }

        if (!filters.isEmpty() || filterInput != null) {
            throw new Rar5DataException("Incomplete RAR5 output filter");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("RAR5 completion cancelled");
        }
        flushOutputChunk();
        checkPackedCancellation();
        primed = true;
    }

    /** Returns the dictionary size declared by a RAR5-container file header. */
    static long declaredWindowSize(long compressionInfo) {
        int algoVersion = (int) (compressionInfo & 0x3F);
        int exponent = (int) ((compressionInfo >> 10) & 0x1F);
        if (algoVersion == 0) {
            if (exponent > 15) {
                throw new Rar5DataException(
                        "RAR5 algorithm version 0 has an invalid dictionary exponent: "
                                + exponent);
            }
            return BASE_WINDOW_SIZE << exponent;
        }
        if (algoVersion != 1) {
            throw new Rar5DataException(
                    "Unsupported RAR5 compression algorithm version: " + algoVersion);
        }
        if (exponent > 23) {
            throw new Rar5DataException(
                    "RAR7 dictionary exponent is outside the format limit: " + exponent);
        }
        long base = BASE_WINDOW_SIZE << exponent;
        int fraction = (int) ((compressionInfo >> 15) & 0x1F);
        long declared = base + (base >>> 5) * fraction;
        if (declared <= 0 || declared > MAX_DECLARED_WINDOW_SIZE) {
            throw new Rar5DataException(
                    "Declared RAR7 dictionary size is outside the format limit: " + declared);
        }
        return declared;
    }

    /** RAR7 version-1 streams have 80 distance codes unless marked as v0 solid data. */
    static boolean usesExtendedDistanceTable(long compressionInfo) {
        return (compressionInfo & 0x3FL) == 1L
                && (compressionInfo & RAR7_V0_SOLID_COMPAT) == 0L;
    }

    // ---- block management ----

    private boolean blockExhausted() {
        return inAddr * 8 + bitAddr >= blockBits;
    }

    /** Only one format-sized compressed block (at most 0xffffff bytes) is buffered. */
    private void openBlock() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("RAR5 block decoding cancelled");
        }
        int flags = readPackedByte();
        int checksum = readPackedByte();
        int byteCount = (flags >> 3) & 7;
        if (byteCount > 2) {
            throw new Rar5DataException(
                    "Unsupported RAR5 block header size: " + byteCount);
        }
        int size = 0;
        for (int i = 0; i <= byteCount; i++) {
            size |= readPackedByte() << (8 * i);
        }
        int calculated = 0x5A ^ flags ^ (size & 0xFF) ^ ((size >> 8) & 0xFF) ^ ((size >> 16) & 0xFF);
        if ((calculated & 0xFF) != checksum) {
            throw new Rar5DataException("RAR5 block header checksum mismatch");
        }
        if (input == null || input.length < size + 8) input = new byte[size + 8];
        int done = 0;
        while (done < size) {
            checkPackedCancellation();
            int requested = size - done;
            int count = packedInput.read(input, done, requested);
            checkPackedCancellation();
            if (count < -1 || count > requested) throw new IOException("Invalid RAR5 packed read count");
            if (count == -1) throw new Rar5DataException("Truncated RAR5 block data");
            if (count == 0) {
                input[done++] = (byte) readPackedByte();
            } else {
                done += count;
            }
        }
        java.util.Arrays.fill(input, size, size + 8, (byte) 0);

        this.blockStart = 0;
        this.blockSize = size;
        this.blockBitSize = 1 + (flags & 7);
        this.blockBits = size == 0 ? 0 : (size - 1) * 8 + blockBitSize;
        this.lastBlock = ((flags >> 6) & 1) != 0;
        this.inAddr = 0;
        this.bitAddr = 0;

        boolean tablePresent = ((flags >> 7) & 1) != 0;
        if (tablePresent) {
            parseTables();
        } else if (size != 0 && (!mainTable.valid || !distTable.valid
                || distTable.size != distanceTableSize)) {
            throw new Rar5DataException(
                    "RAR5 block reuses Huffman tables, but no tables are available");
        }
    }

    private int readPackedByte() throws IOException {
        int value = readCheckedPackedByte(packedInput);
        if (value < 0) throw new Rar5DataException("Truncated RAR5 packed stream");
        return value;
    }

    /** Source contract failures must not be reinterpreted as compressed symbols. */
    private static int readCheckedPackedByte(InputStream source) throws IOException {
        checkPackedCancellation();
        int value = source.read();
        checkPackedCancellation();
        if (value < -1 || value > 255) throw new IOException("Invalid RAR5 packed byte value");
        return value;
    }

    private static void checkPackedCancellation() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("RAR5 packed decoding cancelled");
        }
    }

    // ---- bit reader (positions relative to blockStart) ----

    private int peekBits16() {
        if (blockExhausted()) {
            throw new Rar5DataException("Premature end of RAR5 block data");
        }
        int base = blockStart + inAddr;
        int bits = (input[base] & 0xFF) << 16;
        bits |= (input[base + 1] & 0xFF) << 8;
        bits |= input[base + 2] & 0xFF;
        bits >>= 8 - bitAddr;
        return bits & 0xFFFF;
    }

    private void skipBits(int bits) {
        // Padding makes a short-code peek safe, but it must never complete a
        // truncated Huffman code, table nibble, distance or filter operand.
        if (bits < 0 || bits > blockBits - (inAddr * 8 + bitAddr)) {
            throw new Rar5DataException("RAR5 symbol exceeds declared block bits");
        }
        int next = bitAddr + bits;
        inAddr += next >> 3;
        bitAddr = next & 7;
    }

    private int readBits(int n) {
        int v = peekBits16() >> (16 - n);
        skipBits(n);
        return v;
    }

    /** Reads up to 56 raw bits in stream order without relying on a 32-bit lookahead. */
    private long readRawBitsLong(int count) {
        if (count < 0 || count > 56) {
            throw new Rar5DataException("Invalid RAR5 raw bit count: " + count);
        }
        long value = 0;
        int remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(remaining, 15);
            value = (value << chunk) | readBits(chunk);
            remaining -= chunk;
        }
        return value;
    }

    // ---- canonical Huffman tables ----

    private void createDecodeTable(byte[] bitLength, int off, DecodeTable table, int size) {
        table.valid = false;
        table.symbolCount = 0;
        int[] lengthCount = new int[16];
        java.util.Arrays.fill(table.decodeNum, 0, size, 0);
        table.size = size;
        table.quickBits = size == MAIN_TABLE_SIZE ? 10 : 7;

        for (int i = 0; i < size; i++) {
            int length = bitLength[off + i] & 255;
            if (length > 15) throw new Rar5DataException("Invalid RAR5 Huffman code length");
            lengthCount[length]++;
            if (length != 0) table.symbolCount++;
        }
        lengthCount[0] = 0;
        table.decodePos[0] = 0;
        table.decodeLen[0] = 0;
        int upperLimit = 0;
        for (int i = 1; i < 16; i++) {
            upperLimit += lengthCount[i];
            if (upperLimit > (1 << i)) {
                throw new Rar5DataException("Oversubscribed RAR5 Huffman table");
            }
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
        // Unused distance/repetition alphabets may be empty, but decoding one
        // must not silently manufacture symbol zero from cleared table storage.
        if (!table.valid || table.symbolCount == 0) {
            throw new Rar5DataException("RAR5 Huffman decode from empty or unavailable table");
        }
        int bitField = peekBits16() & 0xFFFE;
        if (bitField >= table.decodeLen[15]) {
            throw new Rar5DataException("Invalid RAR5 Huffman code");
        }
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
        if (pos < 0 || pos >= table.symbolCount) {
            throw new Rar5DataException("Invalid RAR5 Huffman symbol position");
        }
        return table.decodeNum[pos];
    }

    /** Parses the Huffman tables from the start of the current block. */
    private void parseTables() {
        byte[] bitLength = new byte[BIT_LENGTH_TABLE_SIZE];
        for (int w = 0; w < BIT_LENGTH_TABLE_SIZE; ) {
            int value = readBits(4);
            if (value == 15) {
                value = readBits(4);
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
        createDecodeTable(bitLength, 0, bitLengthTable, BIT_LENGTH_TABLE_SIZE);

        int allTablesSize = MAIN_TABLE_SIZE + distanceTableSize
                + LOW_DIST_TABLE_SIZE + REP_LEN_TABLE_SIZE;
        byte[] table = new byte[allTablesSize];
        for (int idx = 0; idx < allTablesSize; ) {
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
                while (n-- > 0 && idx < allTablesSize) {
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
                while (n-- > 0 && idx < allTablesSize) {
                    table[idx++] = 0;
                }
            }
        }

        int off = 0;
        createDecodeTable(table, off, mainTable, MAIN_TABLE_SIZE);
        off += MAIN_TABLE_SIZE;
        createDecodeTable(table, off, distTable, distanceTableSize);
        off += distanceTableSize;
        createDecodeTable(table, off, lowDistTable, LOW_DIST_TABLE_SIZE);
        off += LOW_DIST_TABLE_SIZE;
        createDecodeTable(table, off, repLenTable, REP_LEN_TABLE_SIZE);
    }

    // ---- LZ decode ----

    private void decodeOneSymbol() throws IOException {
        int num = decodeNumber(mainTable);
        if (num < 256) {
            emitByte((byte) num);
        } else if (num >= 262) {
            int len = decodeCodeLength(num - 262);
            int distSlot = decodeNumber(distTable);
            long dist = 1;
            int dBits;
            if (distSlot < 4) {
                dBits = 0;
                dist += distSlot;
            } else {
                dBits = distSlot / 2 - 1;
                dist += (2L | (distSlot & 1)) << dBits;
            }
            if (dBits > 0) {
                if (dBits >= 4) {
                    if (dBits > 4) {
                        dist += readRawBitsLong(dBits - 4) << 4;
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
            long dist = distCacheTouch(num - 258);
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

    private void distCachePush(long value) {
        distCache[3] = distCache[2];
        distCache[2] = distCache[1];
        distCache[1] = distCache[0];
        distCache[0] = value;
    }

    private long distCacheTouch(int idx) {
        long dist = distCache[idx];
        for (int i = idx; i > 0; i--) {
            distCache[i] = distCache[i - 1];
        }
        distCache[0] = dist;
        return dist;
    }

    private void emitByte(byte b) throws IOException {
        if (writePtr >= expectedOutput) {
            throw new Rar5DataException(
                    "RAR5 stream exceeded the output size or numeric position bound");
        }
        window.append(b);
        emitOutputByte(b);
        writePtr++;
    }

    private void copyString(int len, long dist) throws IOException {
        if (dist <= 0 || dist > windowSize) {
            throw new Rar5DataException("RAR5 match distance is outside the window: " + dist);
        }
        long base = solidOffset + writePtr;
        if (len < 0 || len > expectedOutput - writePtr) {
            throw new Rar5DataException("RAR5 match exceeded the output size or numeric position bound");
        }
        FilterInfo pending = filters.peek();
        if (len >= 16 && (pending == null || pending.blockStart >= writePtr + len)) {
            // Share the existing output buffer. History copies are page/ring
            // bounded and overlap-safe; CRC/output-space guards stay downstream.
            int remaining = len;
            while (remaining > 0) {
                int count = Math.min(remaining, outputChunk.length - outputCount);
                window.copyMatch(dist, outputChunk, outputCount, count);
                outputCount += count;
                writePtr += count;
                remaining -= count;
                if (outputCount == outputChunk.length) flushOutputChunk();
            }
            return;
        }
        // Keep the existing byte-exact filter transition path for matches that
        // intersect a filter; the dictionary must retain unfiltered bytes.
        for (int i = 0; i < len; i++) {
            long source = base + i - dist;
            byte b = window.read(source);
            emitByte(b);
        }
    }

    /** Decodes only declared block bits, including filters and no-output repetitions. */
    private void decodeBlockSymbols() throws IOException {
        int symbols = 0;
        while (!blockExhausted()) {
            if ((symbols++ & 1023) == 0 && Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("RAR5 block decoding cancelled");
            }
            decodeOneSymbol();
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

        if (start > expectedOutput - writePtr) {
            throw new Rar5DataException("RAR5 filter starts beyond the entry output");
        }
        long absStart = writePtr + start;
        if (length < 1 || length > 0x400000 || type > FILTER_ARM
                || length > expectedOutput - absStart
                || !isValidFilterRange(absStart, length)) {
            throw new Rar5DataException("Invalid RAR5 filter declaration");
        }
        if (filters.size() >= MAX_PENDING_FILTERS) {
            throw new Rar5DataException("RAR5 pending filter metadata limit");
        }
        FilterInfo filter = new FilterInfo();
        filter.type = type;
        filter.blockStart = absStart;
        filter.blockLength = (int) length;
        if (type == FILTER_DELTA) {
            filter.channels = readBits(5) + 1;
        }
        // Commit only after the entire record (including DELTA channels) is valid.
        filters.add(filter);
        lastFilterBlockStart = filter.blockStart;
        lastFilterBlockLength = filter.blockLength;
    }

    private boolean isValidFilterRange(long absStart, long length) {
        if (lastFilterBlockLength == 0) {
            return true;
        }
        // Same-range records form an ordered chain over one buffered block.
        // Partial overlaps, different chain lengths and already emitted ranges
        // must not become a second consumer of those raw bytes.
        FilterInfo previous = filters.peekLast();
        if (previous != null && absStart == previous.blockStart && length == previous.blockLength) {
            return true;
        }
        return absStart >= lastFilterBlockStart + lastFilterBlockLength;
    }

    /** Filters delay only their own range; the solid dictionary keeps unfiltered bytes. */
    private void emitOutputByte(byte value) throws IOException {
        FilterInfo filter = filters.peek();
        if (filter != null && writePtr >= filter.blockStart) {
            if (filterInput == null) {
                flushOutputChunk();
                filterInput = new byte[filter.blockLength];
                filterCount = 0;
            }
            filterInput[filterCount++] = value;
            if (filterCount == filterInput.length) {
                long start = filter.blockStart;
                int length = filter.blockLength;
                byte[] transformed = filterInput;
                while ((filter = filters.peek()) != null && filter.blockStart == start
                        && filter.blockLength == length) {
                    checkFilterCancellation();
                    transformed = applyFilter(filter, transformed);
                    filters.remove();
                }
                // Publish once, after all transformations, leaving raw LZ history untouched.
                output.write(transformed);
                filterInput = null;
                filterCount = 0;
            }
        } else {
            outputChunk[outputCount++] = value;
            if (outputCount == outputChunk.length) flushOutputChunk();
        }
    }

    private void flushOutputChunk() throws IOException {
        if (outputCount != 0) {
            output.write(outputChunk, 0, outputCount);
            outputCount = 0;
        }
    }

    private byte[] applyFilter(FilterInfo filter, byte[] out) throws IOException {
        long start = filter.blockStart;
        int length = filter.blockLength;
        int s = 0;
        switch (filter.type) {
            case FILTER_DELTA: {
                byte[] filtered = new byte[length];
                int srcPos = 0;
                for (int channel = 0; channel < filter.channels; channel++) {
                    int prev = 0;
                    for (int destPos = channel; destPos < length;
                            destPos += filter.channels) {
                        if ((srcPos & 0x3fff) == 0) checkFilterCancellation();
                        prev = (prev - (out[s + srcPos] & 0xFF)) & 0xFF;
                        filtered[destPos] = (byte) prev;
                        srcPos++;
                    }
                }
                return filtered;
            }
            case FILTER_E8:
            case FILTER_E8E9: {
                boolean extended = filter.type == FILTER_E8E9;
                final long fileSize = 0x1000000L;
                int checkpointAt = 0;
                for (int i = 0; i < length - 4; ) {
                    if (i >= checkpointAt) {
                        checkFilterCancellation();
                        checkpointAt = i + 16384;
                    }
                    int b = out[s + i++] & 0xFF;
                    if (b == 0xE8 || (extended && b == 0xE9)) {
                        long offset = (i + start) % fileSize;
                        long addr = readLe32(out, s + i);
                        if ((addr & 0x80000000L) != 0) {
                            if (((addr + offset) & 0x80000000L) == 0) {
                                writeLe32(out, i, addr + fileSize);
                            }
                        } else {
                            if (((addr - fileSize) & 0x80000000L) != 0) {
                                writeLe32(out, i, addr - offset);
                            }
                        }
                        i += 4;
                    }
                }
                return out;
            }
            case FILTER_ARM: {
                for (int i = 0; i + 3 < length; i += 4) {
                    if ((i & 0x3fff) == 0) checkFilterCancellation();
                    if ((out[s + i + 3] & 0xFF) == 0xEB) {
                        long offset = readLe32(out, s + i) & 0x00FFFFFFL;
                        offset -= (i + start) / 4;
                        offset = (offset & 0x00FFFFFFL) | 0xEB000000L;
                        writeLe32(out, i, offset);
                    }
                }
                return out;
            }
            default:
                throw new Rar5DataException(
                        "Unsupported RAR5 filter type: " + filter.type);
        }
    }

    private static void checkFilterCancellation() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("RAR5 filter decoding cancelled");
        }
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
