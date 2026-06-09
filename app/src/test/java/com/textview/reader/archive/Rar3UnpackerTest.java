package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;

public class Rar3UnpackerTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void unpack_writesSyntheticLiteralPayloadAndValidatesCrc() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("literal.rar", packed);
        File out = tempFolder.newFile("literal.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 1, crc("A"));

        Rar3Unpacker.unpack(context, out, null);

        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unpack_readsMultipleSyntheticBlocksUntilUnpackedSize() throws Exception {
        byte[] packed = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("multi.rar", packed);
        File out = tempFolder.newFile("multi.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 2, crc("AB"));

        Rar3Unpacker.unpack(context, out, null);

        assertArrayEquals("AB".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unpack_stopsAtDeclaredUnpackedSizeDuringMatch() throws Exception {
        byte[] packed = syntheticPayload(blockWithDistance(
                new int[] {'A', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {0},
                "00010"));
        File archive = writeArchive("limit.rar", packed);
        File out = tempFolder.newFile("limit.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 3, crc("AAA"));

        Rar3Unpacker.unpack(context, out, null);

        assertArrayEquals("AAA".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unpack_crcMismatchDeletesOutput() throws Exception {
        byte[] packed = syntheticPayload(block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("badcrc.rar", packed);
        File out = tempFolder.newFile("badcrc.bin");
        assertTrue(out.delete());
        Rar3UnpackContext context = contextFor(archive, packed, 1, 0x12345678L);

        try {
            Rar3Unpacker.unpack(context, out, null);
        } catch (Exception expected) {
            assertFalse(out.exists());
            return;
        }
        throw new AssertionError("CRC mismatch should fail before reporting success");
    }



    @Test
    public void unpack_realSample3PpmdFixtureStopsAtPpmdGap() throws Exception {
        assertPpmdGap("sample-3.rar", 95, 1363150, 1376878, 0x715fa904L, "sample3-ppmd-gap.out");
    }

    @Test
    public void unpack_realSample4PpmdFixtureStopsAtPpmdGap() throws Exception {
        assertPpmdGap("sample-4.rar", 84, 480547, 1043365, 0x35705f9dL, "sample4-ppmd-gap.out");
    }

    @Test
    public void unpack_realSample5AppleDoubleDocxFixturePassesCrc() throws Exception {
        decodeRealSample5Entry("sample5-appledouble-docx.out", 79, 403, 585, 0xf1ec33a7L);
    }

    @Test
    public void unpack_realSample5AppleDoubleDocFixturePassesCrc() throws Exception {
        decodeRealSample5Entry("sample5-appledouble-doc.out", 540, 167, 496, 0x03e46ab1L);
    }

    @Test
    public void unpack_realSample5DocxFixturePassesCrcAfterOldDistanceLengthFix() throws Exception {
        decodeRealSample5Entry("sample5-docx.out", 755, 5928, 6615, 0x0dc9e250L);
    }

    @Test
    public void unpack_realSample5DocFixtureStopsAtVmFilterGap() throws Exception {
        File sample = externalFixture("sample-5.rar");
        File out = tempFolder.newFile("sample5-doc-vm-gap.out");
        assertTrue(out.delete());
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                sample,
                6730,
                6033,
                23552,
                0x33,
                false,
                false,
                false,
                false,
                0x4ab9b212L);

        try {
            Rar3Unpacker.unpack(context, out, null);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("VM filters"));
            assertFalse(out.exists());
            return;
        }
        throw new AssertionError("sample-5.doc should remain a precise VM-filter first-party gap");
    }


    @Test
    public void unpack_syntheticSolidEntryCopiesFromPreviousEntryWindow() throws Exception {
        byte[] firstPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        File firstArchive = writeArchive("solid-first.rar", firstPacked);
        File secondArchive = writeArchive("solid-second.rar", secondPacked);
        File firstOut = tempFolder.newFile("solid-first.bin");
        File secondOut = tempFolder.newFile("solid-second.bin");
        assertTrue(firstOut.delete());
        assertTrue(secondOut.delete());
        Rar3SolidState solidState = new Rar3SolidState();

        Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                firstArchive,
                0,
                firstPacked.length,
                3,
                0x33,
                false,
                false,
                false,
                crc("ABC"),
                solidState), firstOut, null);
        Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                secondArchive,
                0,
                secondPacked.length,
                3,
                0x33,
                false,
                false,
                false,
                crc("ABC"),
                solidState), secondOut, null);

        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(firstOut.toPath()));
        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(secondOut.toPath()));
    }

    @Test
    public void unpack_syntheticSolidStateResetDropsPreviousDictionary() throws Exception {
        byte[] firstPacked = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'C', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        byte[] secondPacked = syntheticPayload(blockWithDistance(
                new int[] {'X', Rar3SymbolDecoder.SYMBOL_LONG_MATCH_FIRST},
                new int[] {2},
                "010"));
        File firstArchive = writeArchive("solid-reset-first.rar", firstPacked);
        File secondArchive = writeArchive("solid-reset-second.rar", secondPacked);
        File firstOut = tempFolder.newFile("solid-reset-first.bin");
        File secondOut = tempFolder.newFile("solid-reset-second.bin");
        assertTrue(firstOut.delete());
        assertTrue(secondOut.delete());
        Rar3SolidState solidState = new Rar3SolidState();

        Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                firstArchive, 0, firstPacked.length, 3, 0x33, false, false, false,
                crc("ABC"), solidState), firstOut, null);
        solidState.reset();

        try {
            Rar3Unpacker.unpack(Rar3UnpackContext.forSolidEntry(
                    secondArchive, 0, secondPacked.length, 3, 0x33, false, false, false,
                    crc("ABC"), solidState), secondOut, null);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertFalse(secondOut.exists());
            return;
        }
        throw new AssertionError("Reset solid state must not keep the previous LZ dictionary");
    }

    @Test
    public void unpack_emptyMainTableStillDoesNotClaimDecodeSupport() throws Exception {
        File archive = tempFolder.newFile("payload.rar");
        byte[] packed = minimalRepeatZeroTablesPayload();
        Files.write(archive.toPath(), packed);
        File out = tempFolder.newFile("decoded.bin");
        assertTrue(out.delete());

        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                archive,
                0,
                packed.length,
                16,
                0x33,
                false,
                false,
                false,
                false);

        try {
            Rar3Unpacker.unpack(context, out, null);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertFalse(out.exists());
            return;
        }
        throw new AssertionError("RAR3/RAR4 compressed scaffold must not report success without decode symbols");
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void context_rejectsSplitPayloadsUntilSplitRewriteOrSolidStateDecoderHandlesThem() throws Exception {
        File archive = tempFolder.newFile("split.rar");
        Files.write(archive.toPath(), new byte[] {1, 2, 3, 4});

        Rar3UnpackContext.forEntry(
                archive,
                0,
                4,
                16,
                0x33,
                false,
                false,
                true,
                false);
    }

    @Test
    public void unpackPayloadForTest_reportsBitsAndBlockCount() throws Exception {
        byte[] packed = syntheticPayload(
                block(new int[] {'A', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"),
                block(new int[] {'B', Rar3SymbolDecoder.SYMBOL_END_BLOCK}, "0001"));
        File archive = writeArchive("result.rar", packed);
        Rar3UnpackContext context = contextFor(archive, packed, 2, -1L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Rar3DecodeResult result = Rar3Unpacker.unpackPayloadForTest(context, packed, out);

        assertEquals(2, result.written);
        assertEquals(2, result.blocks);
        assertTrue(result.bitsRead > 0);
        assertArrayEquals("AB".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }



    private void assertPpmdGap(String fixtureName, long offset, long packedSize, long unpackedSize, long expectedCrc, String outName) throws Exception {
        File sample = externalFixture(fixtureName);
        File out = tempFolder.newFile(outName);
        assertTrue(out.delete());
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                sample,
                offset,
                packedSize,
                unpackedSize,
                0x33,
                false,
                false,
                false,
                false,
                expectedCrc);

        try {
            Rar3Unpacker.unpack(context, out, null);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("PPMd"));
            assertTrue(expected.getMessage().contains("first-party"));
            assertFalse(out.exists());
            return;
        }
        throw new AssertionError(fixtureName + " should remain a precise PPMd first-party gap");
    }

    private void decodeRealSample5Entry(String outName, long offset, long packedSize, long unpackedSize, long expectedCrc) throws Exception {
        File sample = externalFixture("sample-5.rar");
        File out = tempFolder.newFile(outName);
        assertTrue(out.delete());
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                sample,
                offset,
                packedSize,
                unpackedSize,
                0x33,
                false,
                false,
                false,
                false,
                expectedCrc);

        Rar3Unpacker.unpack(context, out, null);

        assertEquals(unpackedSize, Files.size(out.toPath()));
    }

    private File writeArchive(String name, byte[] packed) throws Exception {
        File archive = tempFolder.newFile(name);
        Files.write(archive.toPath(), packed);
        return archive;
    }

    private Rar3UnpackContext contextFor(File archive, byte[] packed, long unpackedSize, long expectedCrc) throws Exception {
        return Rar3UnpackContext.forEntry(
                archive,
                0,
                packed.length,
                unpackedSize,
                0x33,
                false,
                false,
                false,
                false,
                expectedCrc);
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
        bits.writeBits(0, 2); // PPM=false, keep-old-table=false.
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
                while (i + count < lengths.length && lengths[i + count] == 0 && count < 138) count++;
                if (count >= 3) {
                    count = Math.min(count, 10);
                    bits.writeBitString("11"); // Bit-length symbol 18: zero run of 3..10.
                    bits.writeBits(count - 3, 3);
                    i += count;
                } else {
                    bits.writeBitString("00"); // Direct length 0.
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

    private static byte[] minimalRepeatZeroTablesPayload() {
        BitWriter bits = new BitWriter();
        bits.writeBits(0, 2);
        for (int i = 0; i < Rar3HuffmanTables.BC; i++) {
            bits.writeBits(i == 18 ? 1 : 0, 4);
        }
        int remaining = Rar3HuffmanTables.TABLE_SIZE;
        while (remaining > 0) {
            int count = Math.min(10, remaining);
            bits.writeBits(0, 1);
            bits.writeBits(count - 3, 3);
            remaining -= count;
        }
        return bits.toByteArray();
    }


    private File externalFixture(String name) {
        String root = System.getProperty("textview.externalArchiveFixtureDir");
        if (root == null || root.trim().length() == 0) {
            root = System.getenv("TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR");
        }
        org.junit.Assume.assumeTrue("External archive fixture dir not provided",
                root != null && root.trim().length() > 0);
        File file = new File(root, name);
        org.junit.Assume.assumeTrue("Missing fixture: " + file.getAbsolutePath(), file.isFile());
        return file;
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
