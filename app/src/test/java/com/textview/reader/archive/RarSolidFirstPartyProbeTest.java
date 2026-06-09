package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class RarSolidFirstPartyProbeTest {
    @Test
    public void selectSmallestClassicLzSolidCandidateIgnoresNonSolid() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("normal.txt", writeArchive("normal.payload", packed), packed, 1, crc("A"), false));
        entries.add(entry("solid.txt", writeArchive("solid.payload", packed), packed, 1, crc("A"), true));

        RarSolidFirstPartyProbe.Candidate candidate = RarSolidFirstPartyProbe.selectSmallestClassicLzSolidCandidate(entries);

        assertTrue(candidate != null);
        assertEquals("solid.txt", candidate.target.path);
        assertEquals(1, candidate.primerEntryCount);
    }

    @Test
    public void probeSyntheticSolidEntryRunsFirstPartySequencingWithoutEnablingLiveSupport() throws Exception {
        byte[] primerPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'D', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("primer.txt", writeArchive("primer.payload", primerPacked), primerPacked, 4, crc("ABCD"), true));
        entries.add(entry("target.txt", writeArchive("target.payload", secondPacked), secondPacked, 3, crc("BCD"), true));
        File work = tempDir("probe");

        RarSolidFirstPartyProbe.Result result = RarSolidFirstPartyProbe.probe(entries, null, work, false);

        assertEquals(RarSolidFirstPartyProbe.FirstPartyStatus.SUCCESS, result.firstPartyStatus);
        assertEquals(RarSolidFirstPartyProbe.ReferenceStatus.NOT_REQUESTED, result.referenceStatus);
        assertEquals(RarSolidFirstPartyProbe.ComparisonStatus.NOT_COMPARED, result.comparisonStatus);
        assertEquals(RarSolidProbeFailure.Cause.NONE, result.failure.cause);
        assertEquals(3L, result.firstPartySize);
        assertEquals(crc("BCD"), result.firstPartyCrc);
        assertTrue(result.toMarkdown().contains("libarchive remains the primary backend"));
    }


    @Test
    public void probeUsesLeadingNonSolidEntryAsSolidDictionaryPrimer() throws Exception {
        byte[] primerPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'D', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] targetPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("first.txt", writeArchive("solid-run-first.payload", primerPacked), primerPacked, 4, crc("ABCD"), false));
        entries.add(entry("solid-target.txt", writeArchive("solid-run-target.payload", targetPacked), targetPacked, 3, crc("BCD"), true));

        RarSolidFirstPartyProbe.Result result = RarSolidFirstPartyProbe.probe(entries, null, tempDir("leading-primer"), false);

        assertEquals(RarSolidFirstPartyProbe.FirstPartyStatus.SUCCESS, result.firstPartyStatus);
        assertEquals(1, result.candidate.primerEntryCount);
        assertEquals(crc("BCD"), result.firstPartyCrc);
    }

    @Test
    public void noCandidateIsStableDiagnostic() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("normal.txt", writeArchive("plain.payload", packed), packed, 1, crc("A"), false));

        RarSolidFirstPartyProbe.Result result = RarSolidFirstPartyProbe.probe(entries, null, tempDir("none"), false);

        assertEquals(RarSolidFirstPartyProbe.FirstPartyStatus.NO_CANDIDATE, result.firstPartyStatus);
        assertEquals(RarSolidFirstPartyProbe.ComparisonStatus.NOT_COMPARED, result.comparisonStatus);
        assertEquals(RarSolidProbeFailure.Cause.NO_CANDIDATE, result.failure.cause);
    }

    @Test
    public void storedRunStartIsNotTreatedAsClassicLzPrimer() throws Exception {
        byte[] storedBytes = "STORE".getBytes(StandardCharsets.UTF_8);
        byte[] targetPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(storedEntry("stored.txt", writeArchive("stored.payload", storedBytes), storedBytes.length, crc("STORE"), false));
        entries.add(entry("solid-target.txt", writeArchive("target-after-stored.payload", targetPacked), targetPacked, 3, crc("ORE"), true));

        RarSolidFirstPartyProbe.Result result = RarSolidFirstPartyProbe.probe(entries, null, tempDir("stored-run-start"), false);

        assertEquals(RarSolidFirstPartyProbe.FirstPartyStatus.NO_CANDIDATE, result.firstPartyStatus);
        assertEquals(RarSolidProbeFailure.Cause.NO_CANDIDATE, result.failure.cause);
    }



    private File tempDir(String prefix) throws Exception {
        File file = File.createTempFile(prefix, ".tmp");
        if (!file.delete() || !file.mkdirs()) throw new IllegalStateException("Could not create temp dir");
        file.deleteOnExit();
        return file;
    }

    private RarArchiveReader.RarEntry entry(String path, File archive, byte[] packed, long unpackedSize, long crc, boolean solid) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                path,
                false,
                unpackedSize,
                packed.length,
                0,
                4,
                0x33,
                solid,
                false,
                false,
                null,
                crc,
                0);
        entry.sourceArchive = archive;
        return entry;
    }

    private RarArchiveReader.RarEntry storedEntry(String path, File archive, long unpackedSize, long crc, boolean solid) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                path,
                false,
                unpackedSize,
                unpackedSize,
                0,
                4,
                0x30,
                solid,
                false,
                false,
                null,
                crc,
                0);
        entry.sourceArchive = archive;
        return entry;
    }


    private File writeArchive(String name, byte[] packed) throws Exception {
        File archive = new File(tempDir("payloads"), name);
        Files.write(archive.toPath(), packed);
        return archive;
    }

    private static long crc(String text) {
        CRC32 crc = new CRC32();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return crc.getValue() & 0xffffffffL;
    }

    private static Block block(int[] mainSymbols, String actionBits) {
        return new Block(mainSymbols, new int[0], actionBits);
    }

    private static Block blockWithDistance(int[] mainSymbols, int[] distanceSymbols, String actionBits) {
        return new Block(mainSymbols, distanceSymbols, actionBits);
    }

    private static byte[] syntheticPayload(Block... blocks) {
        BitWriter bits = new BitWriter();
        for (Block block : blocks) {
            writeTable(bits, block.mainSymbols, block.distanceSymbols);
            bits.writeBitString(block.actionBits);
        }
        return bits.toByteArray();
    }

    private static void writeTable(BitWriter bits, int[] mainSymbols, int[] distanceSymbols) {
        bits.writeBits(0, 2);
        for (int i = 0; i < Rar3HuffmanTables.BC; i++) {
            bits.writeBits(i == 0 || i == 1 || i == 2 || i == 18 ? 2 : 0, 4);
        }
        int[] lengths = new int[Rar3HuffmanTables.TABLE_SIZE];
        for (int symbol : mainSymbols) lengths[symbol] = 2;
        for (int symbol : distanceSymbols) lengths[Rar3HuffmanTables.NC + symbol] = 1;
        writeMainTableLengths(bits, lengths);
    }

    private static void writeMainTableLengths(BitWriter bits, int[] lengths) {
        for (int i = 0; i < lengths.length;) {
            if (lengths[i] == 0) {
                int count = 0;
                while (i + count < lengths.length && lengths[i + count] == 0 && count < 10) count++;
                if (count >= 3) {
                    bits.writeBitString("11");
                    bits.writeBits(count - 3, 3);
                    i += count;
                } else {
                    bits.writeBitString("00");
                    i++;
                }
            } else if (lengths[i] == 1) {
                bits.writeBitString("01");
                i++;
            } else if (lengths[i] == 2) {
                bits.writeBitString("10");
                i++;
            } else {
                throw new IllegalArgumentException("Test table writer only supports lengths 0, 1, and 2");
            }
        }
    }

    private static final class Block {
        final int[] mainSymbols;
        final int[] distanceSymbols;
        final String actionBits;

        Block(int[] mainSymbols, int[] distanceSymbols, String actionBits) {
            this.mainSymbols = mainSymbols;
            this.distanceSymbols = distanceSymbols;
            this.actionBits = actionBits;
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int bitsInCurrent;

        void writeBitString(String bits) {
            for (int i = 0; i < bits.length(); i++) {
                char c = bits.charAt(i);
                if (c != '0' && c != '1') continue;
                writeBits(c == '1' ? 1 : 0, 1);
            }
        }

        void writeBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                bitsInCurrent++;
                if (bitsInCurrent == 8) {
                    out.write(current);
                    current = 0;
                    bitsInCurrent = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bitsInCurrent > 0) {
                out.write(current << (8 - bitsInCurrent));
                current = 0;
                bitsInCurrent = 0;
            }
            return out.toByteArray();
        }
    }
}
