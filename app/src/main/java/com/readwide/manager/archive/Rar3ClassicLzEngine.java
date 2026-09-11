package com.readwide.manager.archive;

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
 * filters (E8/E8E9/Itanium/Delta/RGB/Audio). The streaming mixed entry point also dispatches
 * PPMd tables; legacy classic-only entry points reject them. Custom VM and unsupported
 * filter scheduling still fail explicitly. Native libarchive remains primary.</p>
 *
 * <p>Output and dictionary state are driven through {@link RarLzWindow}, so solid dictionary
 * carryover is handled by the shared-window infrastructure. The owning solid context also
 * supplies {@link Rar3UnpackState} for saved match distances and lengths.</p>
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
    @NonNull private final Rar3VmFilter.ProgramState vmProgramState;

    private RarCanonicalHuffman ldTable, ddTable, lddTable, rdTable;
    @NonNull private final Rar3UnpackState matchState;
    private int tableReads;
    private boolean fileEndSeen;
    private boolean reuseTablesForNextEntry;
    private int endCode;
    private int endCodeLength;
    interface PpmdSource {
        void readTable(java.io.InputStream input) throws IOException;
        int symbol() throws IOException;
        int escape();
    }
    private PpmdSource mixedPpmd;
    private Rar3PpmdFilterOutput streamingFilters;
    private boolean ppmdMode;
    private final int[] unpOldTable = new int[HUFF_TABLE_SIZE30];

    // Pending VM filters, applied after decoding the full output region.
    private final List<Rar3VmFilter.PendingFilter> filters = new ArrayList<>();

    private Rar3ClassicLzEngine(@NonNull RarBitInput in, @NonNull RarLzWindow window, long limit,
                                @Nullable int[] seedOldTable,
                                @NonNull Rar3VmFilter.ProgramState vmProgramState) {
        this(in, window, limit, seedOldTable, vmProgramState, new Rar3UnpackState());
    }

    private Rar3ClassicLzEngine(@NonNull RarBitInput in, @NonNull RarLzWindow window, long limit,
                                @Nullable int[] seedOldTable,
                                @NonNull Rar3VmFilter.ProgramState vmProgramState,
                                @NonNull Rar3UnpackState matchState) {
        this.in = in;
        this.window = window;
        this.limit = limit;
        this.vmProgramState = vmProgramState;
        this.matchState = matchState;
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
        return decode(in, window, unpackedLimit, seedOldTable, new Rar3VmFilter.ProgramState());
    }

    static Rar3ClassicLzEngine decode(@NonNull RarBitInput in,
                                      @NonNull RarLzWindow window,
                                      long unpackedLimit,
                                      @Nullable int[] seedOldTable,
                                      @NonNull Rar3VmFilter.ProgramState vmProgramState)
            throws IOException {
        return decode(in, window, unpackedLimit, seedOldTable, vmProgramState, new Rar3UnpackState());
    }

    /** The caller owns the solid match history and must abandon the sequence on decode failure. */
    static Rar3ClassicLzEngine decode(@NonNull RarBitInput in,
                                      @NonNull RarLzWindow window,
                                      long unpackedLimit,
                                      @Nullable int[] seedOldTable,
                                      @NonNull Rar3VmFilter.ProgramState vmProgramState,
                                      @NonNull Rar3UnpackState matchState) throws IOException {
        Rar3ClassicLzEngine engine = new Rar3ClassicLzEngine(
                in, window, unpackedLimit, seedOldTable, vmProgramState, matchState);
        engine.run();
        return engine;
    }

    /** Conservative solid handoff: reuse only after a fully decoded file-end marker. */
    static Rar3ClassicLzEngine decodeSolid(@NonNull RarBitInput in,
                                          @NonNull RarLzWindow window, long unpackedLimit,
                                          @NonNull int[] seedOldTable,
                                          @NonNull Rar3VmFilter.ProgramState vmProgramState,
                                          @NonNull Rar3UnpackState matchState,
                                          boolean reuseTables) throws IOException {
        Rar3ClassicLzEngine engine = new Rar3ClassicLzEngine(
                in, window, unpackedLimit, seedOldTable, vmProgramState, matchState);
        if (reuseTables) engine.buildTables(engine.unpOldTable);
        engine.run();
        engine.captureFileBoundary();
        return engine;
    }

    boolean reuseTablesForNextEntry() { return reuseTablesForNextEntry; }
    boolean fileEndSeen() { return fileEndSeen; }

    /** Production streaming path: one reservoir, raw dictionary and VM queue for both modes. */
    static Rar3ClassicLzEngine decodeMixed(RarBitInput input, RarLzWindow window, long limit,
            int[] oldTable, Rar3VmFilter.ProgramState programs, Rar3UnpackState matches,
            PpmdSource ppmd, Rar3PpmdFilterOutput filters, boolean reuseTables,
            boolean captureBoundary) throws IOException {
        Rar3ClassicLzEngine engine = new Rar3ClassicLzEngine(input, window, limit, oldTable, programs, matches);
        engine.mixedPpmd = ppmd;
        engine.streamingFilters = filters;
        // Preserve older LZ output before a later PPMd transition can reference it.
        // Growth is driven by actual output, not the file's declared decoded size.
        window.retainUpTo(32 * 1024 * 1024);
        if (reuseTables) engine.buildTables(engine.unpOldTable);
        engine.run();
        if (engine.ppmdMode) engine.finishPpmdEntry();
        else if (captureBoundary) engine.captureFileBoundary();
        return engine;
    }

    int[] tableState() { return unpOldTable; }

    private void run() throws IOException {
        if (ldTable == null && !readTables()) throw new IOException("RAR3 initial table read failed");
        while (window.written() < limit) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
            if (ppmdMode) {
                if (!readPpmdSymbol(false)) break;
                continue;
            }
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
                        if (matchState.lowDistanceRepeatCount() > 0) {
                            matchState.consumeRepeatedLowDistance();
                            distance += matchState.previousLowDistance();
                        } else {
                            int lowDist = lddTable.decode(in);
                            if (lowDist == 16) {
                                matchState.startLowDistanceRepeat(LOW_DIST_REP_COUNT - 1);
                                distance += matchState.previousLowDistance();
                            } else {
                                distance += lowDist;
                                matchState.rememberLowDistance(lowDist);
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
                matchState.rememberNewDistanceMatch(distance, length);
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
                if (matchState.lastLength() != 0) {
                    window.copyMatch(matchState.oldDistance(0), matchState.lastLength());
                }
                continue;
            }
            if (number < 263) {
                int distNum = number - 259;
                int distance = matchState.oldDistance(distNum);
                int lengthNumber = rdTable.decode(in);
                int length = LDECODE[lengthNumber] + 2;
                int bits = LBITS[lengthNumber];
                if (bits > 0) length += in.readBits(bits);
                matchState.rememberOldDistanceMatch(distNum, length);
                window.copyMatch(distance, length);
                continue;
            }
            int sdIdx = number - 263;
            int distance = SDDECODE[sdIdx] + 1;
            int bits = SDBITS[sdIdx];
            if (bits > 0) distance += in.readBits(bits);
            matchState.rememberNewDistanceMatch(distance, 2);
            window.copyMatch(distance, 2);
        }
    }


    /** Number of Huffman table (re)reads, i.e. decoded block count for diagnostics. */
    int tableReads() {
        return tableReads;
    }
    boolean hasFilters() { return !filters.isEmpty(); }

    List<Rar3VmFilter.PendingFilter> filters() { return filters; }

    private boolean readTables() throws IOException {
        tableReads++;
        in.alignToByte();
        if (in.peekBits(1) != 0) {
            if (mixedPpmd != null) {
                mixedPpmd.readTable(in.alignedBytes());
                ppmdMode = true;
                return true;
            }
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd-compressed entries are not handled by the first-party classic-LZ engine");
        }
        ppmdMode = false;
        in.skipBits(1);
        matchState.resetLowDistanceForTable();
        boolean keepOldTable = in.readBit() != 0;
        if (!keepOldTable) Arrays.fill(unpOldTable, 0);

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

        buildTables(table);
        System.arraycopy(table, 0, unpOldTable, 0, HUFF_TABLE_SIZE30);
        return true;
    }

    private void buildTables(int[] table) throws IOException {
        ldTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, 0, NC30));
        ddTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, NC30, NC30 + DC30));
        lddTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, NC30 + DC30, NC30 + DC30 + LDC30));
        rdTable = RarCanonicalHuffman.fromCodeLengths(Arrays.copyOfRange(table, NC30 + DC30 + LDC30, HUFF_TABLE_SIZE30));
        endCodeLength = table[256];
        endCode = 0;
        if (endCodeLength != 0) {
            int[] counts = new int[16];
            for (int symbol = 0; symbol < NC30; symbol++) {
                if (table[symbol] != 0) counts[table[symbol]]++;
            }
            for (int length = 1; length <= endCodeLength; length++) {
                endCode = (endCode + counts[length - 1]) << 1;
            }
            for (int symbol = 0; symbol < 256; symbol++) {
                if (table[symbol] == endCodeLength) endCode++;
            }
        }
    }

    private boolean readEndOfBlock() throws IOException {
        // A table transition uses one bit; a file boundary uses two, not a 16-bit peek.
        if (in.readBit() != 0) return readTables();
        reuseTablesForNextEntry = in.readBit() == 0;
        fileEndSeen = true;
        return false;
    }

    private void captureFileBoundary() throws IOException {
        if (fileEndSeen || window.written() != limit || endCodeLength == 0) return;
        // Legacy size-limited fixtures need not contain an EOF marker. Do not infer a
        // continuation from padding, a partial marker, another table or match overshoot.
        // No extra output/filter/table is executed beyond the declared output boundary.
        try {
            int markerBits = endCodeLength + 2;
            int marker = in.peekBits(markerBits);
            if ((marker >>> 2) != endCode || (marker & 2) != 0) return;
            in.skipBits(markerBits);
            reuseTablesForNextEntry = (marker & 1) == 0;
            fileEndSeen = true;
        } catch (java.io.EOFException missingMarker) {
            reuseTablesForNextEntry = false;
        }
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

    private void addVMCode(int firstByte, byte[] code) throws IOException {
        Rar3VmFilter.ProgramState.Parsed parsed = vmProgramState.parse(
                firstByte, code, window.written(), window.size());
        if (streamingFilters != null) {
            streamingFilters.acceptParsed(parsed);
            return;
        }
        if (parsed.resetPendingFilters) filters.clear();
        filters.add(parsed.filter);
    }

    /** Returns false only for a file marker; escape 0 can switch back to LZ. */
    private boolean readPpmdSymbol(boolean atLimit) throws IOException {
        int symbol = mixedPpmd.symbol();
        if (symbol != mixedPpmd.escape()) {
            if (atLimit) throw new IOException("RAR3 PPMd data follows declared size");
            window.writeLiteral(symbol);
            return true;
        }
        int code = mixedPpmd.symbol();
        if (code == 0) return readTables();
        if (code == 2) {
            fileEndSeen = true;
            reuseTablesForNextEntry = false;
            return false;
        }
        if (atLimit) throw new IOException("RAR3 PPMd missing file marker");
        if (code == 3) { streamingFilters.readRecord(mixedPpmd::symbol); return true; }
        if (code == 4 || code == 5) {
            int distance = 1;
            if (code == 4) {
                distance = 0;
                for (int i = 0; i < 3; i++) distance = (distance << 8) | mixedPpmd.symbol();
                distance += 2;
            }
            int length = mixedPpmd.symbol() + (code == 4 ? 32 : 4);
            if (length > limit - window.written() || distance > window.retained()) {
                throw new IOException("RAR3 PPMd match exceeds entry or available history");
            }
            // PPMd match commands must not alter classic-LZ repeated-distance caches.
            window.copyMatch(distance, length);
        } else window.writeLiteral(mixedPpmd.escape());
        return true;
    }

    private void finishPpmdEntry() throws IOException {
        while (!fileEndSeen && ppmdMode) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
            readPpmdSymbol(true);
        }
        // A final PPMd table transition may select LZ; only its explicit EOF is accepted.
        if (!fileEndSeen) {
            captureFileBoundary();
            if (!fileEndSeen) throw new IOException("RAR3 mixed entry lacks file boundary");
        }
    }
}
