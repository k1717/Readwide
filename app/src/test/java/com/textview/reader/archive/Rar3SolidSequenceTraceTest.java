package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class Rar3SolidSequenceTraceTest {
    @Test
    public void captureRecordsPrimerAndTargetDictionaryPositions() throws Exception {
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
        RarArchiveReader.RarEntry primer = entry("primer.txt", writeArchive("trace-primer.payload", primerPacked), primerPacked, 4, crc("ABCD"), false);
        RarArchiveReader.RarEntry target = entry("target.txt", writeArchive("trace-target.payload", targetPacked), targetPacked, 3, crc("BCD"), true);
        entries.add(primer);
        entries.add(target);

        Rar3SolidSequenceTrace trace = Rar3SolidSequenceTrace.capture(entries, target, tempDir("trace-ok"), null);

        assertTrue(trace.completedTarget);
        assertEquals(2, trace.entries().size());
        assertEquals(Rar3SolidSequenceTrace.EntryStatus.SUCCESS, trace.entries().get(0).status);
        assertEquals(Rar3SolidSequenceTrace.EntryStatus.SUCCESS, trace.entries().get(1).status);
        assertFalse(trace.entries().get(0).initializedBefore);
        assertEquals(4, trace.entries().get(0).writePositionAfter);
        assertTrue(trace.entries().get(1).initializedBefore);
        assertEquals(4, trace.entries().get(1).writePositionBefore);
        assertEquals(7, trace.entries().get(1).writePositionAfter);
        assertEquals(Rar3SolidCarryoverCheck.Status.OK, trace.entries().get(0).carryover.status);
        assertEquals(Rar3SolidCarryoverCheck.Status.OK, trace.entries().get(1).carryover.status);
        assertTrue(trace.compactSummary().contains("completedTarget=true"));
        assertTrue(trace.compactSummary().contains("carryover=OK"));
    }

    @Test
    public void captureKeepsCrcMismatchAsTraceStatus() throws Exception {
        byte[] primerPacked = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] targetPacked = syntheticPayload(block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("primer.txt", writeArchive("trace-primer-crc.payload", primerPacked), primerPacked, 1, crc("A"), false));
        RarArchiveReader.RarEntry target = entry("target.txt", writeArchive("trace-target-crc.payload", targetPacked), targetPacked, 1, crc("C"), true);
        entries.add(target);

        Rar3SolidSequenceTrace trace = Rar3SolidSequenceTrace.capture(entries, target, tempDir("trace-crc"), null);

        assertFalse(trace.completedTarget);
        assertEquals(2, trace.entries().size());
        assertEquals(Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH, trace.entries().get(1).status);
        assertEquals(crc("B"), trace.entries().get(1).actualCrc);
        assertEquals(Rar3SolidCarryoverCheck.Status.CRC_MISMATCH_WITH_VALID_CARRYOVER, trace.entries().get(1).carryover.status);
        assertTrue(trace.toMarkdown().contains("CRC_MISMATCH"));
        assertTrue(trace.toMarkdown().contains("CRC_MISMATCH_WITH_VALID_CARRYOVER"));
    }

    @Test
    public void probeDetailIncludesTraceAfterFirstPartyFailure() throws Exception {
        byte[] primerPacked = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] targetPacked = new byte[] {0x01};
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("primer.txt", writeArchive("probe-trace-primer.payload", primerPacked), primerPacked, 1, crc("A"), false));
        entries.add(entry("target.txt", writeArchive("probe-trace-target.payload", targetPacked), targetPacked, 1, crc("B"), true));

        RarSolidFirstPartyProbe.Result result = RarSolidFirstPartyProbe.probe(entries, null, tempDir("probe-trace"), false);

        assertTrue(result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.GAP
                || result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.FAILED);
        assertTrue(result.detail.contains("solidTrace entries="));
        assertTrue(result.detail.contains("last=target.txt"));
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

    private File writeArchive(String name, byte[] packed) throws Exception {
        File archive = new File(tempDir("payloads"), name);
        Files.write(archive.toPath(), packed);
        return archive;
    }

    private static long crc(String text) {
        CRC32 crc = new CRC32();
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
