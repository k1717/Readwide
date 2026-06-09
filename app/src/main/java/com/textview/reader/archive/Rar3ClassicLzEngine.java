package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * First-party RAR3/RAR4 classic-LZ engine.
 *
 * <p>This is an independent Java implementation of the covered RAR3/RAR4 classic-LZ behavior,
 * written against public format behavior notes and validated with local fixtures. It handles
 * byte-aligned block tables, canonical Huffman main/distance/low-distance/repeat tables,
 * old-distance and short-distance caches, multi-block continuation, and the six standard VM
 * filters (E8/E8E9/Itanium/Delta/RGB/Audio). PPMd blocks and non-standard VM programs raise
 * {@link RarArchiveReader.UnsupportedRarFeatureException} so callers fall back to libarchive.</p>
 *
 * <p>Output and dictionary state are driven through {@link RarLzWindow}, so solid dictionary
 * carryover is handled by the shared-window infrastructure rather than this engine.</p>
 */
final class Rar3ClassicLzEngine {
    // RAR3 alphabet sizes.
    private static final int NC30 = 299;
    private static final int DC30 = 60;
    private static final int LDC30 = 17;
    private static final int RC30 = 28;
    private static final int BC30 = 20;
    private static final int HUFF_TABLE_SIZE30 = NC30 + DC30 + RC30 + LDC30; // 404
    private static final int LOW_DIST_REP_COUNT = 16;

    private static final int[] LDECODE = {0,1,2,3,4,5,6,7,8,10,12,14,16,20,24,28,32,40,48,56,64,80,96,112,128,160,192,224};
    private static final int[] LBITS   = {0,0,0,0,0,0,0,0,1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4,  4,  5,  5,  5,  5};
    private static final int[] DBIT_LENGTH_COUNTS = {4,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,14,0,12};
    private static final int[] SDDECODE = {0,4,8,16,32,64,128,192};
    private static final int[] SDBITS   = {2,2,3, 4, 5, 6,  6,  6};

    private static final int[] DDECODE = new int[DC30];
    private static final int[] DBITS = new int[DC30];
    static {
        int dist = 0, bitLength = 0, slot = 0;
        for (int i = 0; i < DBIT_LENGTH_COUNTS.length; i++, bitLength++) {
            for (int j = 0; j < DBIT_LENGTH_COUNTS[i]; j++, slot++, dist += (1 << bitLength)) {
                DDECODE[slot] = dist;
                DBITS[slot] = bitLength;
            }
        }
    }

    private final RarBitInput in;
    private final RarLzWindow window;
    private final long limit;

    private RarCanonicalHuffman ldTable, ddTable, lddTable, rdTable;
    private final int[] oldDist = new int[4];
    private int lastLength;
    private int prevLowDist;
    private int lowDistRepCount;
    private final int[] unpOldTable = new int[HUFF_TABLE_SIZE30];

    // Pending VM filters, applied after decoding the full output region.
    private final List<Rar3VmFilter.PendingFilter> filters = new ArrayList<>();

    private Rar3ClassicLzEngine(@NonNull RarBitInput in, @NonNull RarLzWindow window, long limit,
                                @Nullable int[] seedOldTable) {
        this.in = in;
        this.window = window;
        this.limit = limit;
        if (seedOldTable != null && seedOldTable.length >= HUFF_TABLE_SIZE30) {
            System.arraycopy(seedOldTable, 0, unpOldTable, 0, HUFF_TABLE_SIZE30);
        }
    }

    /**
     * Decodes a single entry's classic-LZ payload into {@code window}. Returns the engine so the
     * caller can apply VM filters / persist table state. {@code seedOldTable} carries the previous
     * entry's table lengths for solid keep-old-table continuity (may be null).
     */
    static Rar3ClassicLzEngine decode(@NonNull RarBitInput in,
                                      @NonNull RarLzWindow window,
                                      long unpackedLimit,
                                      @Nullable int[] seedOldTable) throws IOException {
        Rar3ClassicLzEngine engine = new Rar3ClassicLzEngine(in, window, unpackedLimit, seedOldTable);
        engine.run();
        return engine;
    }

    int[] tableState() { return unpOldTable; }

    private void run() throws IOException {
        if (!readTables()) throw new IOException("RAR3 initial table read failed");
        while (window.written() < limit) {
            int number = ldTable.decode(in);
            if (number < 256) {
                window.writeLiteral(number);
                continue;
            }
            if (number >= 271) {
                int lenIdx = number - 271;
                int length = LDECODE[lenIdx] + 3;
                int bits = LBITS[lenIdx];
                if (bits > 0) length += in.readBits(bits);

                int distNumber = ddTable.decode(in);
                int distance = DDECODE[distNumber] + 1;
                int dbits = DBITS[distNumber];
                if (dbits > 0) {
                    if (distNumber > 9) {
                        if (dbits > 4) {
                            distance += (in.readBits(dbits - 4) << 4);
                        }
                        if (lowDistRepCount > 0) {
                            lowDistRepCount--;
                            distance += prevLowDist;
                        } else {
                            int lowDist = lddTable.decode(in);
                            if (lowDist == 16) {
                                lowDistRepCount = LOW_DIST_REP_COUNT - 1;
                                distance += prevLowDist;
                            } else {
                                distance += lowDist;
                                prevLowDist = lowDist;
                            }
                        }
                    } else {
                        distance += in.readBits(dbits);
                    }
                }
                if (distance >= 0x2000) {
                    length++;
                    if (distance >= 0x40000) length++;
                }
                insertOldDist(distance);
                lastLength = length;
                window.copyMatch(distance, length);
                continue;
            }
            if (number == 256) {
                if (!readEndOfBlock()) break;
                continue;
            }
            if (number == 257) {
                readVMCode();
                continue;
            }
            if (number == 258) {
                if (lastLength != 0) window.copyMatch(oldDist[0], lastLength);
                continue;
            }
            if (number < 263) {
                int distNum = number - 259;
                int distance = oldDist[distNum];
                for (int i = distNum; i > 0; i--) oldDist[i] = oldDist[i - 1];
                oldDist[0] = distance;
                int lengthNumber = rdTable.decode(in);
                int length = LDECODE[lengthNumber] + 2;
                int bits = LBITS[lengthNumber];
                if (bits > 0) length += in.readBits(bits);
                lastLength = length;
                window.copyMatch(distance, length);
                continue;
            }
            int sdIdx = number - 263;
            int distance = SDDECODE[sdIdx] + 1;
            int bits = SDBITS[sdIdx];
            if (bits > 0) distance += in.readBits(bits);
            insertOldDist(distance);
            lastLength = 2;
            window.copyMatch(distance, 2);
        }
    }

    boolean hasFilters() { return !filters.isEmpty(); }

    List<Rar3VmFilter.PendingFilter> filters() { return filters; }

    private void insertOldDist(int distance) {
        oldDist[3] = oldDist[2];
        oldDist[2] = oldDist[1];
        oldDist[1] = oldDist[0];
        oldDist[0] = distance;
    }

    private boolean readTables() throws IOException {
        in.alignToByte();
        int bitField = in.peekBits(16);
        if ((bitField & 0x8000) != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd-compressed entries are not handled by the first-party classic-LZ engine");
        }
        prevLowDist = 0;
        lowDistRepCount = 0;
        boolean keepOldTable = (bitField & 0x4000) != 0;
        if (!keepOldTable) Arrays.fill(unpOldTable, 0);
        in.skipBits(2);

        int[] bitLength = new int[BC30];
        for (int i = 0; i < BC30; i++) {
            int length = in.readBits(4);
            if (length == 15) {
                int zeroCount = in.readBits(4);
                if (zeroCount == 0) {
                    bitLength[i] = 15;
                } else {
                    zeroCount += 2;
                    while (zeroCount-- > 0 && i < BC30) bitLength[i++] = 0;
                    i--;
                }
            } else {
                bitLength[i] = length;
            }
        }
        RarCanonicalHuffman bdTable = RarCanonicalHuffman.fromCodeLengths(bitLength);

        int[] table = new int[HUFF_TABLE_SIZE30];
        for (int i = 0; i < HUFF_TABLE_SIZE30; ) {
            int number = bdTable.decode(in);
            if (number < 16) {
                table[i] = (number + unpOldTable[i]) & 0xf;
                i++;
            } else if (number < 18) {
                int n = (number == 16) ? in.readBits(3) + 3 : in.readBits(7) + 11;
                if (i == 0) throw new IOException("RAR3 repeat code at first position");
                while (n-- > 0 && i < HUFF_TABLE_SIZE30) { table[i] = table[i - 1]; i++; }
            } else {
                int n = (number == 18) ? in.readBits(3) + 3 : in.readBits(7) + 11;
                while (n-- > 0 && i < HUFF_TABLE_SIZE30) table[i++] = 0;
            }
        }

        ldTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, 0, NC30));
        ddTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, NC30, NC30 + DC30));
        lddTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, NC30 + DC30, NC30 + DC30 + LDC30));
        rdTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, NC30 + DC30 + LDC30, HUFF_TABLE_SIZE30));
        System.arraycopy(table, 0, unpOldTable, 0, HUFF_TABLE_SIZE30);
        return true;
    }

    private boolean readEndOfBlock() throws IOException {
        int b16 = in.peekBits(16);
        boolean newTable;
        boolean newFile = false;
        if ((b16 & 0x8000) != 0) {
            newTable = true;
            in.skipBits(1);
        } else {
            newFile = true;
            newTable = (b16 & 0x4000) != 0;
            in.skipBits(2);
        }
        if (newFile) return false;
        if (newTable) return readTables();
        return true;
    }

    // --- VM filter code reading ---
    private void readVMCode() throws IOException {
        int firstByte = in.readBits(8);
        int length = (firstByte & 7) + 1;
        if (length == 7) {
            length = in.readBits(8) + 7;
        } else if (length == 8) {
            length = in.readBits(16);
        }
        if (length == 0) throw new IOException("RAR3 empty VM code");
        byte[] code = new byte[length];
        for (int i = 0; i < length; i++) code[i] = (byte) in.readBits(8);
        addVMCode(firstByte, code);
    }

    private static final class VmCodeReader {
        private final byte[] data;
        private int bitPos;
        VmCodeReader(byte[] data) { this.data = data; }
        int fgetbits() {
            int addr = bitPos >> 3;
            int bit = bitPos & 7;
            int bf = ((addr < data.length ? (data[addr] & 0xff) : 0) << 16)
                    | ((addr + 1 < data.length ? (data[addr + 1] & 0xff) : 0) << 8)
                    | (addr + 2 < data.length ? (data[addr + 2] & 0xff) : 0);
            bf >>>= (8 - bit);
            return bf & 0xffff;
        }
        void addbits(int n) { bitPos += n; }
        long readData() {
            int d0 = fgetbits();
            switch (d0 & 0xc000) {
                case 0:
                    addbits(6);
                    return (d0 >> 10) & 0xf;
                case 0x4000:
                    if ((d0 & 0x3c00) == 0) {
                        long d = 0xffffff00L | ((d0 >> 2) & 0xff);
                        addbits(14);
                        return d & 0xffffffffL;
                    } else {
                        long d = (d0 >> 6) & 0xff;
                        addbits(10);
                        return d;
                    }
                case 0x8000: {
                    addbits(2);
                    long d = fgetbits();
                    addbits(16);
                    return d;
                }
                default: {
                    addbits(2);
                    long d = ((long) fgetbits()) << 16;
                    addbits(16);
                    d |= fgetbits();
                    addbits(16);
                    return d & 0xffffffffL;
                }
            }
        }
    }

    private void addVMCode(int firstByte, byte[] code) throws IOException {
        VmCodeReader vci = new VmCodeReader(code);
        if ((firstByte & 0x80) != 0) {
            long filtPos = vci.readData();
            if (filtPos == 0) filters.clear();
        }
        Rar3VmFilter.PendingFilter pf = new Rar3VmFilter.PendingFilter();
        long blockStart = vci.readData();
        if ((firstByte & 0x40) != 0) blockStart += 258;
        pf.blockStartAbs = window.written() + blockStart;
        if ((firstByte & 0x20) != 0) pf.blockLength = (int) vci.readData();
        pf.initR[4] = pf.blockLength;
        if ((firstByte & 0x10) != 0) {
            int initMask = vci.fgetbits() >> 9;
            vci.addbits(7);
            for (int i = 0; i < 7; i++) {
                if ((initMask & (1 << i)) != 0) pf.initR[i] = (int) vci.readData();
            }
        }
        long vmCodeSize = vci.readData();
        if (vmCodeSize >= 0x10000 || vmCodeSize == 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3 VM program too large or empty");
        }
        byte[] inner = new byte[(int) vmCodeSize];
        for (int i = 0; i < vmCodeSize; i++) {
            inner[i] = (byte) (vci.fgetbits() >> 8);
            vci.addbits(8);
        }
        Rar3VmFilter.StandardFilter type = Rar3VmFilter.identify(inner);
        if (type == Rar3VmFilter.StandardFilter.NONE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3 uses a non-standard VM filter program");
        }
        pf.type = type;
        filters.add(pf);
    }
}
