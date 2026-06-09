package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class Rar3FirstPartyArchiveExtractorTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void tryExtractArchive_decodesSyntheticNonSolidAndSolidSequence() throws Exception {
        byte[] firstPacked = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] solidPrimerPacked = syntheticPayload(
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'D', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] solidSecondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("plain.txt", writeArchive("plain.payload", firstPacked), firstPacked, 1, crc("A"), false));
        entries.add(entry("solid-primer.txt", writeArchive("solid-primer.payload", solidPrimerPacked), solidPrimerPacked, 3, crc("BCD"), true));
        entries.add(entry("solid-copy.txt", writeArchive("solid-copy.payload", solidSecondPacked), solidSecondPacked, 3, crc("BCD"), true));
        File target = tempFolder.newFolder("out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchive(entries, target, null, null, null));

        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "plain.txt").toPath()));
        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "solid-primer.txt").toPath()));
        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "solid-copy.txt").toPath()));
    }

    @Test
    public void tryExtractSingleEntry_primesPreviousSolidEntries() throws Exception {
        byte[] primerPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        RarArchiveReader.RarEntry primer = entry("primer.txt", writeArchive("primer.payload", primerPacked), primerPacked, 3, crc("ABC"), true);
        RarArchiveReader.RarEntry second = entry("second.txt", writeArchive("second.payload", secondPacked), secondPacked, 3, crc("ABC"), true);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(primer);
        entries.add(second);
        File out = tempFolder.newFile("single-solid.txt");
        assertTrue(out.delete());

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntry(second, entries, out, null));

        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }


    @Test
    public void tryExtractSingleEntry_primesSolidTargetFromLeadingNonSolidEntry() throws Exception {
        byte[] primerPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'D', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] targetPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        RarArchiveReader.RarEntry primer = entry("first-in-solid-run.txt", writeArchive("first-in-solid-run.payload", primerPacked), primerPacked, 4, crc("ABCD"), false);
        RarArchiveReader.RarEntry target = entry("solid-target.txt", writeArchive("solid-target.payload", targetPacked), targetPacked, 3, crc("BCD"), true);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(primer);
        entries.add(target);
        File out = tempFolder.newFile("solid-target.txt");
        assertTrue(out.delete());

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntry(target, entries, out, null));

        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void tryExtractArchive_keepsLeadingNonSolidDictionaryForFollowingSolidEntry() throws Exception {
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
        entries.add(entry("first.txt", writeArchive("archive-first.payload", primerPacked), primerPacked, 4, crc("ABCD"), false));
        entries.add(entry("second.txt", writeArchive("archive-second.payload", targetPacked), targetPacked, 3, crc("BCD"), true));
        File targetDir = tempFolder.newFolder("archive-out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchive(entries, targetDir, null, null, null));

        assertArrayEquals("ABCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "first.txt").toPath()));
        assertArrayEquals("BCD".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "second.txt").toPath()));
    }

    @Test
    public void tryExtractArchive_bootstrapsFirstEntryMarkedSolidFromEmptyDictionary() throws Exception {
        byte[] firstPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("first-solid.txt", writeArchive("first-solid.payload", firstPacked), firstPacked, 3, crc("ABC"), true));
        entries.add(entry("second-solid.txt", writeArchive("second-solid.payload", secondPacked), secondPacked, 3, crc("ABC"), true));
        File targetDir = tempFolder.newFolder("solid-bootstrap-out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchive(entries, targetDir, null, null, null));

        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "first-solid.txt").toPath()));
        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(targetDir, "second-solid.txt").toPath()));
    }

    @Test
    public void limitedFallbackExtractsOnlyNonSolidClassicLz() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry entry = entry("limited.txt", writeArchive("limited.payload", packed), packed, 1, crc("A"), false);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry);
        File target = tempFolder.newFolder("limited-out");

        assertTrue(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(target, "limited.txt").toPath()));
    }

    @Test
    public void limitedFallbackRejectsPpmdBeforeClassicLzEngine() throws Exception {
        byte[] ppmdPacked = new byte[] {(byte) 0x80, 0x00, 0x00, 0x00};
        RarArchiveReader.RarEntry ppmd = entry("ppmd.txt", writeArchive("limited-ppmd.payload", ppmdPacked), ppmdPacked, 1, crc("A"), false);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(ppmd);
        File target = tempFolder.newFolder("limited-ppmd-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));
        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(ppmd, entries, tempFolder.newFile("ppmd-out.txt"), null));
    }

    @Test
    public void limitedFallbackDoesNotEnableSolidClassicLz() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry entry = entry("solid.txt", writeArchive("limited-solid.payload", packed), packed, 1, crc("A"), true);
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry);
        File target = tempFolder.newFolder("limited-solid-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));
    }

    @Test
    public void tryExtractSingleEntry_rejectsEncryptedCompressedCandidate() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry encrypted = new RarArchiveReader.RarEntry(
                "encrypted.txt",
                false,
                1,
                packed.length,
                0,
                4,
                0x33,
                false,
                false,
                false,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8}),
                crc("A"),
                0);
        encrypted.sourceArchive = writeArchive("encrypted.payload", packed);
        File out = tempFolder.newFile("encrypted-out.txt");
        assertTrue(out.delete());

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractSingleEntry(encrypted, java.util.Collections.singletonList(encrypted), out, null));
        assertFalse(out.exists());
    }


    @Test
    public void limitedFallbackRejectsMixedEncryptedStoredBeforeWriting() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("limited.txt", writeArchive("mixed-limited.payload", packed), packed, 1, crc("A"), false));
        entries.add(entryWithOptions(
                "encrypted-stored.txt",
                writeArchive("mixed-encrypted-stored.payload", "B".getBytes(StandardCharsets.UTF_8)),
                "B".getBytes(StandardCharsets.UTF_8),
                1,
                crc("B"),
                4,
                0x30,
                false,
                false,
                false,
                RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8})));
        File target = tempFolder.newFolder("mixed-encrypted-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertFalse(new File(target, "limited.txt").exists());
        assertFalse(new File(target, "encrypted-stored.txt").exists());
    }

    @Test
    public void limitedFallbackRejectsMixedSplitStoredBeforeWriting() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("limited.txt", writeArchive("mixed-split-limited.payload", packed), packed, 1, crc("A"), false));
        entries.add(entryWithOptions(
                "split-stored.txt",
                writeArchive("mixed-split-stored.payload", "B".getBytes(StandardCharsets.UTF_8)),
                "B".getBytes(StandardCharsets.UTF_8),
                1,
                crc("B"),
                4,
                0x30,
                false,
                false,
                true,
                null));
        File target = tempFolder.newFolder("mixed-split-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertFalse(new File(target, "limited.txt").exists());
        assertFalse(new File(target, "split-stored.txt").exists());
    }

    @Test
    public void limitedFallbackRejectsMixedSolidClassicLzBeforeWriting() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        entries.add(entry("limited.txt", writeArchive("mixed-solid-limited.payload", packed), packed, 1, crc("A"), false));
        entries.add(entry("solid.txt", writeArchive("mixed-solid.payload", packed), packed, 1, crc("A"), true));
        File target = tempFolder.newFolder("mixed-solid-out");

        assertFalse(Rar3FirstPartyArchiveExtractor.tryExtractArchiveLimitedFallback(entries, target, null, null, null));

        assertFalse(new File(target, "limited.txt").exists());
        assertFalse(new File(target, "solid.txt").exists());
    }

    @Test
    public void limitedFallbackDeletesOutputOnCrcMismatch() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        RarArchiveReader.RarEntry entry = entry(
                "bad-crc.txt",
                writeArchive("bad-crc.payload", packed),
                packed,
                1,
                crc("not A"),
                false);
        File out = tempFolder.newFile("bad-crc.txt");
        assertTrue(out.delete());

        try {
            Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entry, java.util.Collections.singletonList(entry), out, null);
            assertFalse("CRC mismatch should fail before commit", true);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            // expected
        }

        assertFalse(out.exists());
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


    private RarArchiveReader.RarEntry entryWithOptions(String path,
                                                       File archive,
                                                       byte[] packed,
                                                       long unpackedSize,
                                                       long crc,
                                                       int rarVersion,
                                                       int method,
                                                       boolean solid,
                                                       boolean splitBefore,
                                                       boolean splitAfter,
                                                       RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                path,
                false,
                unpackedSize,
                packed.length,
                0,
                rarVersion,
                method,
                solid,
                splitBefore,
                splitAfter,
                encryption,
                crc,
                0);
        entry.sourceArchive = archive;
        return entry;
    }

    private File writeArchive(String name, byte[] packed) throws Exception {
        File archive = tempFolder.newFile(name);
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
