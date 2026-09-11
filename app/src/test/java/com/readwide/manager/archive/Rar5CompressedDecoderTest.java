package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.CRC32;

/**
 * End-to-end verification of the first-party RAR5-container compressed
 * decoder. The embedded fixtures originate from the CC0-dedicated
 * "RAR Test Files" collection (see docs/ASSET_PROVENANCE.md) and cover a
 * compressed entry, a stored+compressed mix, and a solid continuation.
 */
public class Rar5CompressedDecoderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** testfile.rar5.cbr (410 bytes): jpg compressed (method 5) + png stored. */
    private static final String CBR_HEX = ""
            + "526172211a0701003392b5e50a01050600050101808000f41d9aa6220202d60106dc01b68302d00e503a6cb170da8005"
            + "010c7465737466696c652e6a7067c54cd326644532f65035d9d3c67673922101107c2504110685202d4b6b11a106b684"
            + "106896be0ed4dcf85a082f80212031e805b5a9b1b380a3b254f8c6f49a66e6e6ef93fe686e1986061fff5fde2e794fe0"
            + "0b760b900208000c4d43f50b409233b4f54bec9913f9aa422845955d7195965d97195d86d81b5a334db85298a213af3a"
            + "57a6bca7dc0c2a088b8af9854e0146757f956616994465049ff884e1e51e7501d87e8249f0590a913fc76fb53bc589f6"
            + "3b57267adae5c32471c9e766fd3092c72c18eae8a59a1c9a87f419544eddebe633de8fcce250dc1c220202d70006d700"
            + "b68302d00e503a62acb0af8000010c7465737466696c652e706e6789504e470d0a1a0a0000000d494844520000000200"
            + "00000208030000004568fd1600000006504c5445000000ffffffa5d99fdd0000000c494441547801636060044200000c"
            + "000339e077030000000049454e44ae4260821d77565103050400";

    /** testfile.rar5.solid.cbr (407 bytes): jpg compressed + png compressed solid. */
    private static final String SOLID_CBR_HEX = ""
            + "526172211a07010020b6fa110a0105060405010180800046c44bd1220202ec0106dc01b68302d00e503a6cb170da801d"
            + "010c7465737466696c652e6a7067c477e9276544432f57044ac99996de5e66497731181041e1282288231cc8b8890c52"
            + "6a62260a20e8742082a4d8ba9e02ea41d4bb1e06820bc01090191fe066d753b1d4960a4aabd538c5ddd7f17eaf57aab9"
            + "357c515545515e3c7df6bfdc5d77978013ab5e5ae0d34003e272fd83481ba0ce31edffce19cfece633a678e840b3cf22"
            + "0a5021fa2f8cb0aa1a3492952442a71534c453a57e00f9c280f456d42a79063b1770a7217252233a24c5a4258e3c7138"
            + "c37cbf512328680655ba9e7dff8efcdbdba8ffbe71b8b33aaf6d5db972efd76f8f3da367fa6cee65f1d5e1b5bdd23be0"
            + "703ae4736ba9517daef8d31cd7d7220202be0006d700b68302d00e503a62acb0afc01d010c7465737466696c652e706e"
            + "6745243be89ac69c28aa825a865d48f618a98c76c9f26ffee4140d36b5990a2b5afb2ff5fc14b04b1e8d9e56b7679dca"
            + "c5f41bcdc1c8cb80964b11f7569f981d77565103050400";

    /** testfile.rar5.solid.rar (97 bytes): single compressed txt entry. */
    private static final String SOLID_RAR_HEX = ""
            + "526172211a07010020b6fa110a010506040501018080001b084cbc2202029b00068c00b68302d00e503afe8fc16e801d"
            + "010c7465737466696c652e747874c6841830022fb32fd85305562ac15ce39390bfdfe35bc983f357901d775651030504"
            + "00";

    private static final long TXT_CRC = 0x6ec18ffeL;
    private static final long PNG_CRC = 0xafb0ac62L;
    private static final long JPG_CRC = 0xda70b16cL;

    // ---- embedded fixtures (always-on) ----

    @Test
    public void splitCompressedFileUsesTheFinalUnpackedCrc() throws Exception {
        File archive = writeFixture("split-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original, false);
        File out = new File(temp.getRoot(), "split-decoded.txt");
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(
                split.get(0), split, out, null, null));
        assertExtractedFile(out, 12, TXT_CRC);
    }

    @Test
    public void splitCompressedFileRejectsBadFinalCrcBeforeCommitting() throws Exception {
        File archive = writeFixture("split-bad-source.rar", SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original, true);
        File out = new File(temp.getRoot(), "bad-final.txt");
        try {
            Rar5CompressedArchiveExtractor.tryExtractEntry(split.get(0), split, out, null, null);
            throw new AssertionError("Bad final CRC must fail");
        } catch (IOException expected) {
            assertTrue("No output may be committed", !out.exists());
        }
    }

    @Test
    public void splitPredecessorPrimesSolidTargetExactlyOnce() throws Exception {
        File archive = writeFixture("split-solid-source.rar", SOLID_CBR_HEX);
        java.util.List<RarArchiveReader.RarEntry> originals =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, null);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(originals.get(0), false);
        java.util.List<RarArchiveReader.RarEntry> all = new java.util.ArrayList<>(split);
        all.add(originals.get(1));
        java.lang.reflect.Method plan = Rar5CompressedArchiveExtractor.class.getDeclaredMethod(
                "buildSolidChain", RarArchiveReader.RarEntry.class, java.util.List.class);
        plan.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<RarArchiveReader.RarEntry> chain =
                (java.util.List<RarArchiveReader.RarEntry>) plan.invoke(null, originals.get(1), all);
        assertEquals(java.util.Arrays.asList(split.get(0), originals.get(1)), chain);
        File out = new File(temp.getRoot(), "split-solid.png");
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(
                originals.get(1), all, out, null, null));
        assertExtractedFile(out, 87, PNG_CRC);
    }

    @Test public void splitCompressedRejectsBadPackedCrcEvenWhenDecodedFileCrcIsValid() throws Exception {
        File archive = writeFixture("bad-packed-crc.rar",SOLID_RAR_HEX);
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive,null).get(0);
        java.util.List<RarArchiveReader.RarEntry> split = splitForRegression(original,false);
        RarArchiveReader.RarEntry first = split.get(0);
        RarArchiveReader.RarEntry bad = new RarArchiveReader.RarEntry(first.path,false,
                first.unpackedSize,first.packedSize,first.dataOffset,5,first.method,first.solid,
                false,true,null,first.dataCrc^1,first.timeMillis,first.rar5CompressionInfo);
        bad.sourceArchive = first.sourceArchive;
        split.set(0,bad);
        File out = new File(temp.getRoot(),"bad-packed-output.txt");
        try { Rar5CompressedArchiveExtractor.tryExtractEntry(bad,split,out,null,null);
            throw new AssertionError("Intermediate packed CRC must be checked");
        } catch (IOException expected) { assertTrue(!out.exists()); }
    }

    private java.util.List<RarArchiveReader.RarEntry> splitForRegression(
            RarArchiveReader.RarEntry original, boolean badFinalCrc) throws Exception {
        byte[] packed = new byte[(int) original.packedSize];
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(original.sourceArchive, "r")) {
            input.seek(original.dataOffset);
            input.readFully(packed);
        }
        int cut = packed.length / 2;
        byte[][] segments = {java.util.Arrays.copyOfRange(packed, 0, cut),
                java.util.Arrays.copyOfRange(packed, cut, packed.length)};
        java.util.List<RarArchiveReader.RarEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            File volume = temp.newFile("payload-" + i + ".bin");
            Files.write(volume.toPath(), segments[i]);
            CRC32 packedCrc = new CRC32();
            packedCrc.update(segments[i]);
            long crc = i == 0 ? packedCrc.getValue()
                    : (badFinalCrc ? original.dataCrc ^ 1L : original.dataCrc);
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                    original.path, false, original.unpackedSize, segments[i].length, 0,
                    5, original.method, original.solid, i != 0, i == 0, null,
                    crc, original.timeMillis, original.rar5CompressionInfo);
            entry.sourceArchive = volume;
            entries.add(entry);
        }
        return entries;
    }

    @Test
    public void compressedTxtEntryDecodesWithCrcMatch() throws Exception {
        File archive = writeFixture("solid.rar", SOLID_RAR_HEX);
        File outDir = temp.newFolder("txt");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.txt"), 12, TXT_CRC);
    }

    @Test
    public void mixedStoredAndCompressedCbrExtractsBothImages() throws Exception {
        File archive = writeFixture("plain.cbr", CBR_HEX);
        File outDir = temp.newFolder("cbr");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.jpg"), 220, JPG_CRC);
        assertExtractedFile(new File(outDir, "testfile.png"), 87, PNG_CRC);
    }

    @Test
    public void solidCbrExtractsBothImagesWithWindowCarryover() throws Exception {
        File archive = writeFixture("solid.cbr", SOLID_CBR_HEX);
        File outDir = temp.newFolder("solidcbr");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.jpg"), 220, JPG_CRC);
        assertExtractedFile(new File(outDir, "testfile.png"), 87, PNG_CRC);
    }

    @Test
    public void forwardReaderCarriesSolidHistoryAndCleansSpools() throws Exception {
        File archive = writeFixture("forward-solid.cbr", SOLID_CBR_HEX);
        File spool = temp.newFolder("forward-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            assertEquals("testfile.jpg", reader.nextEntry().path);
            assertForwardPayload(reader, 220, JPG_CRC);
            assertEquals(1, spool.list().length);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertEquals(0, spool.list().length);
            assertForwardPayload(reader, 87, PNG_CRC);
        }
        assertEquals(0, spool.list().length);
    }

    @Test
    public void forwardReaderDrainsSolidPrimerWithoutSpoolingIt() throws Exception {
        File archive = writeFixture("forward-skip.cbr", SOLID_CBR_HEX);
        File spool = temp.newFolder("skip-spool");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(archive, null, spool, false)) {
            reader.nextEntry();
            assertTrue(reader.drainCurrentEntry(Long.MAX_VALUE));
            assertEquals(0, spool.list().length);
            assertEquals("testfile.png", reader.nextEntry().path);
            assertForwardPayload(reader, 87, PNG_CRC);
        }
        assertEquals(0, spool.list().length);
    }

    private static void assertForwardPayload(ArchiveSupport.ForwardArchiveReader reader,
            int length, long expectedCrc) throws Exception {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buffer = new byte[37];
        int count, total = 0;
        while ((count = reader.read(buffer)) != -1) { crc.update(buffer, 0, count); total += count; }
        assertEquals(length, total);
        assertEquals(expectedCrc, crc.getValue());
    }

    @Test
    public void singleEntryExtractionOfSolidMemberPrimesPredecessors() throws Exception {
        File archive = writeFixture("solid2.cbr", SOLID_CBR_HEX);
        File out = new File(temp.newFolder("single"), "testfile.png");
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "testfile.png", out, null));
        assertExtractedFile(out, 87, PNG_CRC);
    }

    @Test
    public void singleEntryExtractionOfNonSolidCompressedMemberWorks() throws Exception {
        File archive = writeFixture("solid3.cbr", SOLID_CBR_HEX);
        File out = new File(temp.newFolder("single2"), "testfile.jpg");
        assertTrue(RarArchiveReader.extractSingleEntry(archive, "testfile.jpg", out, null));
        assertExtractedFile(out, 220, JPG_CRC);
    }

    @Test
    public void solidEntryWithoutPrimerIsRejectedNotFaked() throws Exception {
        byte[] cbr = hexToBytes(SOLID_CBR_HEX);
        // The solid png entry payload starts at 0x151 and is 62 bytes;
        // compression info 0x0ec0 declares solid + 1 MiB dictionary.
        byte[] packed = new byte[62];
        System.arraycopy(cbr, 0x151, packed, 0, 62);
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        try {
            decoder.decodeEntry(packed, 87, 0x0ec0L);
            fail("Solid entry without a primed window must not decode");
        } catch (Rar5CompressedDecoder.Rar5DataException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("solid"));
        }
    }

    @Test
    public void corruptedCompressedPayloadFailsInsteadOfReportingSuccess() throws Exception {
        byte[] cbr = hexToBytes(SOLID_CBR_HEX);
        byte[] packed = new byte[236];
        System.arraycopy(cbr, 0x3e, packed, 0, 236);
        packed[40] ^= 0x5A; // corrupt mid-stream
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        boolean failed;
        try {
            byte[] data = decoder.decodeEntry(packed, 220, 0x0e80L);
            CRC32 crc = new CRC32();
            crc.update(data);
            failed = crc.getValue() != JPG_CRC;
        } catch (Rar5CompressedDecoder.Rar5DataException expected) {
            failed = true;
        }
        assertTrue("Corrupted payload must fail decode or CRC, never report a clean result", failed);
    }

    @Test
    public void rar7V0SolidCompatibilityMarkerDecodesExistingV0Stream() {
        byte[] cbr = hexToBytes(CBR_HEX);
        byte[] packed = new byte[236];
        System.arraycopy(cbr, 0x3e, packed, 0, packed.length);

        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        byte[] data = decoder.decodeEntry(
                packed, 220, 0x0e80L | 1L | 0x100000L);
        CRC32 crc = new CRC32();
        crc.update(data);
        assertEquals(JPG_CRC, crc.getValue());
    }

    @Test
    public void rar7VersionOneParsesExtendedDictionaryAndDistanceGeometry() {
        long fortyEightMiB = 1L
                | (8L << 10)   // 128 KiB * 2^8 = 32 MiB
                | (16L << 15); // plus 16/32 = 16 MiB
        assertEquals(48L * 1024 * 1024,
                Rar5CompressedDecoder.declaredWindowSize(fortyEightMiB));
        assertTrue(Rar5CompressedDecoder.usesExtendedDistanceTable(fortyEightMiB));
        assertTrue(Rar5CompressedArchiveExtractor.isSupportedCompressionInfo(fortyEightMiB));

        long oneTiB = 1L | (23L << 10);
        assertEquals(1L << 40, Rar5CompressedDecoder.declaredWindowSize(oneTiB));

        long v0SolidCompatibility = fortyEightMiB | 0x100000L;
        assertFalse(Rar5CompressedDecoder.usesExtendedDistanceTable(v0SolidCompatibility));
        assertTrue(Rar5CompressedArchiveExtractor.isSupportedCompressionInfo(
                v0SolidCompatibility));
    }

    @Test
    public void rar7ExtendedDistanceStreamUsesBoundedWindowAndZeroFillsVoid() {
        long oneTiBMethodOne = 1L | 0x80L | (23L << 10);
        byte[] decoded = new Rar5CompressedDecoder().decodeEntry(
                buildRar7ExtendedDistancePayload(), 6, oneTiBMethodOne);
        assertArrayEquals(new byte[] {'A', 0, 0, 0, 0, 0}, decoded);
    }

    @Test
    public void decoderStillRejectsUnknownPostRar7AlgorithmVersions() {
        assertFalse(Rar5CompressedArchiveExtractor.isSupportedCompressionInfo(2L));
        try {
            Rar5CompressedDecoder.declaredWindowSize(2L);
            fail("Unknown algorithm versions must be rejected");
        } catch (Rar5CompressedDecoder.Rar5DataException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("version"));
        }
    }

    // ---- external fixtures (assume-gated; the rar-test-files build dir) ----

    @Test
    public void extractionEligibilityDoesNotImposeFormerPackedOrUnpackedCaps() throws Exception {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "large.bin", false, (long) Integer.MAX_VALUE + 1, 65L * 1024 * 1024,
                0, 5, 1, false, false, false, null, -1, 0, 0x80L);
        entry.sourceArchive = new File(temp.getRoot(), "large.rar");
        java.lang.reflect.Method eligible = Rar5CompressedArchiveExtractor.class.getDeclaredMethod(
                "isEligibleCompressed", RarArchiveReader.RarEntry.class);
        eligible.setAccessible(true);
        assertEquals(Boolean.TRUE, eligible.invoke(null, entry));
    }

    @Test
    public void streamingEntryAcceptsLongSizeAndPropagatesOutputFailure() throws Exception {
        IOException stop = new IOException("deliberate output failure");
        try {
            new Rar5CompressedDecoder().decodeEntry(
                    new java.io.ByteArrayInputStream(buildRepeatedLiteralBlock(false)),
                    (long) Integer.MAX_VALUE + 123L, 0x80L, new java.io.OutputStream() {
                        @Override public void write(int value) throws IOException { throw stop; }
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                            assertEquals(64 * 1024, length);
                            throw stop;
                        }
                    });
            fail("Output failure must propagate");
        } catch (IOException expected) {
            org.junit.Assert.assertSame(stop, expected);
        }
    }

    @Test
    public void streamingFiltersAtZeroAndAdjacentRangesAreAppliedBeforeOutput() throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) data.write(i == 0 || i == 9 ? 1 : 0);
        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // main A
        writeZeroLengths(bits, 190);
        bits.write(0, 1); // main filter (256)
        writeZeroLengths(bits, 49);
        bits.write(0, 1); // distance 0
        writeZeroLengths(bits, 123); // remaining distance/low-distance/repetition tables
        for (int start : new int[] {0, 4}) {
            bits.write(1, 1); // filter
            bits.write(0, 2); bits.write(start, 8);
            bits.write(0, 2); bits.write(4, 8);
            bits.write(0, 3); // delta
            bits.write(0, 5); // one channel
        }
        bits.write(0, 8); // eight A literals
        byte[] tail = bits.toByteArray();
        data.write(tail);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        new Rar5CompressedDecoder().decodeEntry(new java.io.ByteArrayInputStream(
                frameBlock(data.toByteArray(), bits.bitCount(), true)), 8, 0x80L, decoded);
        assertArrayEquals(new byte[] {(byte) 191, 126, 61, (byte) 252,
                (byte) 191, 126, 61, (byte) 252}, decoded.toByteArray());
    }

    @Test
    public void streamingCrossesBlockBoundariesAndCarriesSolidHistory() throws Exception {
        byte[] first = buildRepeatedLiteralBlock(false);
        byte[] last = buildRepeatedLiteralBlock(true);
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        decoder.decodeEntry(new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(first), new java.io.ByteArrayInputStream(last)),
                2L * (1 + 32 * 4097), 0x80L, result);
        assertEquals(2 * (1 + 32 * 4097), result.size());
        for (byte value : result.toByteArray()) assertEquals('A', value);
        result.reset();
        decoder.decodeEntry(new java.io.ByteArrayInputStream(last), 1 + 32 * 4097,
                0xC0L, result);
        assertEquals(1 + 32 * 4097, result.size());
    }

    @Test
    public void largeStreamingOutputCrossesFormer256MiBLimitWhenEnabled() throws Exception {
        assumeTrue("Enable explicitly for a large streaming regression",
                Boolean.getBoolean("readwide.largeArchiveTests"));
        final int blocks = 2100;
        byte[] ordinary = buildRepeatedLiteralBlock(false);
        byte[] last = buildRepeatedLiteralBlock(true);
        java.io.InputStream generated = new java.io.InputStream() {
            int block;
            int offset;
            @Override public int read() {
                if (block == blocks) return -1;
                byte[] current = block == blocks - 1 ? last : ordinary;
                int value = current[offset++] & 0xff;
                if (offset == current.length) { offset = 0; block++; }
                return value;
            }
        };
        final long[] count = {0};
        long expected = blocks * (1L + 32 * 4097);
        assertTrue(expected > 256L * 1024 * 1024);
        new Rar5CompressedDecoder().decodeEntry(generated, expected, 0x80L,
                new java.io.OutputStream() {
                    @Override public void write(int value) { assertEquals('A', value); count[0]++; }
                    @Override public void write(byte[] bytes, int offset, int length) {
                        for (int i = offset; i < offset + length; i++) assertEquals('A', bytes[i]);
                        count[0] += length;
                    }
                });
        assertEquals(expected, count[0]);
    }

    @Test
    public void rar5SolidMatchBeyond64MiBUsesSpilledHistoryWhenEnabled() throws Exception {
        assertSolidLongHistory(0);
    }

    @Test
    public void rar7SolidMatchBeyond64MiBUsesSpilledHistoryWhenEnabled() throws Exception {
        assertSolidLongHistory(1);
    }

    private void assertSolidLongHistory(int algorithm) throws Exception {
        assumeTrue("Enable explicitly for a >64 MiB history regression",
                Boolean.getBoolean("readwide.largeArchiveTests"));
        byte[] ordinary = buildRepeatedLiteralBlock(false, algorithm == 1);
        byte[] last = buildRepeatedLiteralBlock(true, algorithm == 1);
        final int blocks = 512;
        long primedBytes = blocks * (1L + 32 * 4097);
        assertTrue(primedBytes > 64L * 1024 * 1024 + 1);
        java.io.InputStream input = new java.io.InputStream() {
            int block, offset;
            @Override public int read() {
                if (block == blocks) return -1;
                byte[] bytes = block == blocks - 1 ? last : ordinary;
                int value = bytes[offset++] & 255;
                if (offset == bytes.length) { offset = 0; block++; }
                return value;
            }
        };
        final long[] count = {0};
        java.io.OutputStream checked = new java.io.OutputStream() {
            @Override public void write(int value) { assertEquals('A', value); count[0]++; }
            @Override public void write(byte[] bytes, int offset, int length) {
                for (int i = offset; i < offset + length; i++) assertEquals('A', bytes[i]);
                count[0] += length;
            }
        };
        long info = (10L << 10) | 0x80L | algorithm; // 128 MiB logical dictionary.
        try (Rar5CompressedDecoder decoder = new Rar5CompressedDecoder()) {
            decoder.decodeEntry(input, primedBytes, info, checked);
            decoder.decodeEntry(new java.io.ByteArrayInputStream(buildLongHistoryMatchBlock(algorithm)),
                    6, info | 0x40L, checked);
        }
        assertEquals(primedBytes + 6, count[0]);
    }

    private static byte[] buildLongHistoryMatchBlock(int algorithm) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) data.write(i == 0 || i == 9 ? 1 : 0);
        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // literal A
        writeZeroLengths(bits, 196);
        bits.write(0, 1); // match symbol 262
        writeZeroLengths(bits, 95);
        bits.write(0, 1); // distance slot 52 => (2 << 25) + 1
        writeZeroLengths(bits, algorithm == 1 ? 27 : 11);
        bits.write(0, 1); // low distance 0
        writeZeroLengths(bits, 59);
        bits.write(0, 1); // literal A
        bits.write(1, 1); // match base length 2 + 3 distance increments
        bits.write(0, 1); // distance slot 52
        bits.write(0, 21);
        bits.write(0, 1); // low distance 0
        data.write(bits.toByteArray());
        byte[] block = data.toByteArray();
        int valid = bits.bitCount() % 8;
        int flags = 0xC0 | ((valid == 0 ? 8 : valid) - 1);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(flags);
        packed.write((0x5A ^ flags ^ block.length) & 255);
        packed.write(block.length);
        packed.write(block);
        return packed.toByteArray();
    }

    private static byte[] buildRepeatedLiteralBlock(boolean last) throws IOException {
        return buildRepeatedLiteralBlock(last, false);
    }

    private static byte[] buildRepeatedLiteralBlock(boolean last, boolean extended) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) data.write(i == 0 || i == 9 ? 1 : 0);
        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // main literal A, length 1
        writeZeroLengths(bits, 239);
        bits.write(0, 1); // main match 305, length 1
        bits.write(0, 1); // distance 0, length 1
        writeZeroLengths(bits, extended ? 139 : 123);
        bits.write(0, 1); // initial A
        for (int i = 0; i < 32; i++) {
            bits.write(1, 1); // length code 43
            bits.write(511, 9); // match length 4097
            bits.write(0, 1); // distance 1
        }
        data.write(bits.toByteArray());
        return frameBlock(data.toByteArray(), bits.bitCount(), last);
    }

    private static byte[] frameBlock(byte[] block, int tailBitCount, boolean last) throws IOException {
        int finalBits = tailBitCount & 7;
        if (finalBits == 0) finalBits = 8;
        int flags = 0x80 | (last ? 0x40 : 0) | finalBits - 1;
        if (block.length > 255) throw new AssertionError("Fixture requires larger block framing");
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(flags);
        packed.write((0x5A ^ flags ^ block.length) & 0xff);
        packed.write(block.length);
        packed.write(block);
        return packed.toByteArray();
    }

    @Test
    public void externalRar5FixturesAllExtractWhenProvided() throws Exception {
        File build = externalFixtureBuildDir();
        String[][] cases = {
                {"testfile.rar5.rar", "testfile.txt"},
                {"testfile.rar5.solid.rar", "testfile.txt"},
                {"testfile.rar5.cbr", "testfile.jpg"},
                {"testfile.rar5.solid.cbr", "testfile.png"},
                {"testfile.rar5.locked.cbr", "testfile.jpg"},
                {"testfile.rar5.rr.cbr", "testfile.png"},
        };
        for (String[] c : cases) {
            File archive = new File(build, c[0]);
            assumeTrue("Missing fixture: " + archive, archive.isFile());
            File outDir = temp.newFolder("ext_" + c[0].replace('.', '_'));
            assertTrue("extract failed: " + c[0],
                    RarArchiveReader.extractArchiveIntoDirectory(archive, outDir, null));
            assertTrue("missing " + c[1] + " from " + c[0],
                    new File(outDir, c[1]).isFile());
        }
    }

    // ---- helpers ----

    private void assertExtractedFile(File file, int expectedSize, long expectedCrc) throws IOException {
        assertTrue("missing output: " + file, file.isFile());
        byte[] data = Files.readAllBytes(file.toPath());
        assertEquals(expectedSize, data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        assertEquals(expectedCrc, crc.getValue());
    }

    private File writeFixture(String name, String hex) throws IOException {
        File fixture = temp.newFile(name);
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            out.write(hexToBytes(hex));
        }
        return fixture;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * Builds one version-1 block from the public RAR5-container grammar:
     * literal 'A', then a long-distance match using extended distance slot 64
     * (base length 2 plus the three standard distance-dependent increments).
     * The declared 1 TiB dictionary is never allocated; the match points into
     * the initial zero-filled portion of the logical window.
     */
    private static byte[] buildRar7ExtendedDistancePayload() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        // Bit-length alphabet: symbols 1 and 19 have one-bit codes (0 and 1).
        for (int i = 0; i < 10; i++) {
            data.write(i == 0 || i == 9 ? 0x01 : 0x00);
        }

        BitWriter bits = new BitWriter();
        writeZeroLengths(bits, 65);
        bits.write(0, 1); // main literal 65 ('A') length 1
        writeZeroLengths(bits, 196);
        bits.write(0, 1); // main match symbol 262 length 1
        writeZeroLengths(bits, 107);
        bits.write(0, 1); // extended distance slot 64 length 1
        writeZeroLengths(bits, 15);
        bits.write(0, 1); // low-distance symbol 0 length 1
        writeZeroLengths(bits, 59);

        bits.write(0, 1);  // literal 'A'
        bits.write(1, 1);  // match symbol 262 => length 2
        bits.write(0, 1);  // distance slot 64
        bits.write(0, 27); // high bits of the 33-bit distance
        bits.write(0, 1);  // low-distance symbol 0
        byte[] tail = bits.toByteArray();
        data.write(tail, 0, tail.length);

        byte[] block = data.toByteArray();
        int finalBits = bits.bitCount() & 7;
        if (finalBits == 0) finalBits = 8;
        int flags = 0xC0 | (finalBits - 1); // last block + tables present
        int checksum = 0x5A ^ flags ^ block.length;
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(flags);
        packed.write(checksum & 0xFF);
        packed.write(block.length);
        packed.write(block, 0, block.length);
        return packed.toByteArray();
    }

    private static void writeZeroLengths(BitWriter bits, int count) {
        while (count > 0) {
            int chunk = Math.min(count, 138);
            if (count > chunk && count - chunk < 11) chunk = count - 11;
            if (chunk < 11) {
                throw new AssertionError("test table zero run is too short: " + chunk);
            }
            bits.write(1, 1); // bit-length symbol 19
            bits.write(chunk - 11, 7);
            count -= chunk;
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;
        private int count;

        void write(long value, int width) {
            for (int i = width - 1; i >= 0; i--) {
                current = (current << 1) | (int) ((value >>> i) & 1L);
                used++;
                count++;
                if (used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
        }

        int bitCount() {
            return count;
        }

        byte[] toByteArray() {
            if (used > 0) {
                out.write(current << (8 - used));
                current = 0;
                used = 0;
            }
            return out.toByteArray();
        }
    }

    private static File externalFixtureBuildDir() {
        String path = System.getProperty("textview.rarFixtureRoot");
        if (path == null || path.trim().length() == 0) {
            path = System.getenv("TEXTVIEW_RAR_FIXTURE_ROOT");
        }
        assumeTrue("RAR fixture root not provided", path != null && path.trim().length() > 0);
        File build = new File(path, "rar-test-files-master/build");
        if (!build.isDirectory()) {
            build = new File(path); // allow pointing directly at the build dir
        }
        assumeTrue("RAR fixture build dir missing: " + build.getAbsolutePath(), build.isDirectory());
        return build;
    }
}
