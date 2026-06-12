package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;

/**
 * End-to-end verification of the first-party RAR3/RAR4 PPMd solid decoder
 * against the target solid CBR fixture (one PPMd reset entry + one PPMd
 * continuation entry).
 *
 * <p>The 381-byte fixture archive is embedded so these tests always run;
 * the external-fixture variants additionally exercise the same checks
 * against a caller-provided file when configured.</p>
 */
public class Rar3PpmdEngineFixtureProbeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** testfile_rar3_solid.cbr (381 bytes): testfile.png + testfile.jpg, both PPMd, solid. */
    private static final String FIXTURE_CBR_HEX = ""
            + "526172211a07003bd07308000d0000000000000062d47480802c0054000000570000000062acb0af0000212a1d350c00"
            + "200000007465737466696c652e706e67a71888c5fbb542d1f3ded5f7b2c970254f81bf495b52740c9dee9307b04dc502"
            + "422a6eb601460c90a7b9b5a71ceff87387a5fc0269e03c8c0e35402c66502becc86b2a47980e360402c0000000bf8867"
            + "f6a9ffd427a77490802c00b6000000dc000000006cb170da0000212a1d350c00200000007465737466696c652e6a7067"
            + "c715fecff824ae8b900f34afd6ef2cd188338b7cd5790f769ed618231edffc8e4677337ff39dc0e5c952fef4ba715dd3"
            + "696e0995e6215306378140fdbd48d55b0fd8372a4cf27920c17d947a85e90a7c3329f423d733c0b1dbbbf5e25e5717a8"
            + "56cb61233cf05b91d707fef2d00b433a2e66912d5a6292a8118a6ce087f87590b30a8d5ac34804c95d04c1262ff68c90"
            + "a44cb64cb6f9582036e1bd536f918fa6536d2f79685e97a75ed3dfe46d0000bf8867f6a9ffd4c43d7b00400700";

    private static final long PNG_CRC = 0xafb0ac62L;
    private static final long JPG_CRC = 0xda70b16cL;
    private static final int PNG_SIZE = 87;
    private static final int JPG_SIZE = 220;

    // ---- embedded fixture (always-on) ----

    @Test
    public void engineDecodesBothSolidPpmdEntriesWithCrcMatch() throws Exception {
        File fixture = writeEmbeddedFixture();
        List<Rar3PpmdEngineFixtureProbe.Row> rows = Rar3PpmdEngineFixtureProbe.probe(fixture);
        assertBothRowsFullyDecoded(rows);
    }

    @Test
    public void streamDecoderEnforcesEndOfDataMarkerAndSolidContinuation() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        byte[] pngPacked = new byte[0x54];
        System.arraycopy(cbr, 0x40, pngPacked, 0, 0x54);
        byte[] jpgPacked = new byte[0xB6];
        System.arraycopy(cbr, 0xC0, jpgPacked, 0, 0xB6);

        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        Rar3PpmdSolidStreamDecoder.EntryResult png = decoder.decodeEntry(pngPacked, PNG_SIZE);
        assertEquals(PNG_CRC, png.crc32);
        assertEquals(PNG_SIZE, png.data.length);
        assertEquals("png", Rar3PpmdEngineFixtureProbe.magicName(png.data));

        Rar3PpmdSolidStreamDecoder.EntryResult jpg = decoder.decodeEntry(jpgPacked, JPG_SIZE);
        assertEquals(JPG_CRC, jpg.crc32);
        assertEquals(JPG_SIZE, jpg.data.length);
        assertEquals("jpeg", Rar3PpmdEngineFixtureProbe.magicName(jpg.data));
        assertEquals(crc32(jpg.data), jpg.crc32);
    }

    @Test
    public void continuationEntryWithoutPrimerIsRejectedNotFaked() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        byte[] jpgPacked = new byte[0xB6];
        System.arraycopy(cbr, 0xC0, jpgPacked, 0, 0xB6);
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        try {
            decoder.decodeEntry(jpgPacked, JPG_SIZE);
            fail("Continuation entry without a primed model must not decode");
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("continuation"));
        }
    }

    @Test
    public void corruptedPrimerPayloadFailsInsteadOfReportingSuccess() throws Exception {
        byte[] cbr = hexToBytes(FIXTURE_CBR_HEX);
        byte[] pngPacked = new byte[0x54];
        System.arraycopy(cbr, 0x40, pngPacked, 0, 0x54);
        pngPacked[20] ^= 0x5A; // corrupt mid-stream
        Rar3PpmdSolidStreamDecoder decoder = new Rar3PpmdSolidStreamDecoder();
        boolean failed;
        try {
            Rar3PpmdSolidStreamDecoder.EntryResult result = decoder.decodeEntry(pngPacked, PNG_SIZE);
            failed = result.crc32 != PNG_CRC;
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            failed = true;
        }
        assertTrue("Corrupted payload must fail decode or CRC, never report a clean result", failed);
    }

    @Test
    public void wholeArchiveExtractionProducesBothImagesViaProductionPath() throws Exception {
        File fixture = writeEmbeddedFixture();
        File outDir = temp.newFolder("out");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(fixture, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.png"), PNG_SIZE, PNG_CRC);
        assertExtractedFile(new File(outDir, "testfile.jpg"), JPG_SIZE, JPG_CRC);
    }

    @Test
    public void singleEntryExtractionOfContinuationEntryPrimesPredecessors() throws Exception {
        File fixture = writeEmbeddedFixture();
        File out = new File(temp.newFolder("single"), "testfile.jpg");
        assertTrue(RarArchiveReader.extractSingleEntry(fixture, "testfile.jpg", out, null));
        assertExtractedFile(out, JPG_SIZE, JPG_CRC);
    }

    @Test
    public void singleEntryExtractionOfFirstEntryWorks() throws Exception {
        File fixture = writeEmbeddedFixture();
        File out = new File(temp.newFolder("single2"), "testfile.png");
        assertTrue(RarArchiveReader.extractSingleEntry(fixture, "testfile.png", out, null));
        assertExtractedFile(out, PNG_SIZE, PNG_CRC);
    }

    @Test
    public void decodedBytesMatchGroundTruthExactly() throws Exception {
        File fixture = writeEmbeddedFixture();
        List<Rar3PpmdEngineFixtureProbe.Row> rows = Rar3PpmdEngineFixtureProbe.probe(fixture);
        assertEquals(2, rows.size());
        byte[] expectedPngPrefix = hexToBytes("89504e470d0a1a0a0000000d49484452");
        byte[] expectedJpgPrefix = hexToBytes("ffd8ffe000104a464946");
        byte[] pngPrefix = new byte[expectedPngPrefix.length];
        byte[] jpgPrefix = new byte[expectedJpgPrefix.length];
        System.arraycopy(rows.get(0).data, 0, pngPrefix, 0, pngPrefix.length);
        System.arraycopy(rows.get(1).data, 0, jpgPrefix, 0, jpgPrefix.length);
        assertArrayEquals(expectedPngPrefix, pngPrefix);
        assertArrayEquals(expectedJpgPrefix, jpgPrefix);
    }

    // ---- external fixture (assume-gated, same checks against a real file) ----

    @Test
    public void externalFixtureDecodesBothEntriesWhenProvided() throws Exception {
        File fixture = externalFixture();
        List<Rar3PpmdEngineFixtureProbe.Row> rows = Rar3PpmdEngineFixtureProbe.probe(fixture);
        assertBothRowsFullyDecoded(rows);
    }

    @Test
    public void externalFixtureWholeArchiveExtractionWhenProvided() throws Exception {
        File fixture = externalFixture();
        File outDir = temp.newFolder("ext");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(fixture, outDir, null));
        assertExtractedFile(new File(outDir, "testfile.png"), PNG_SIZE, PNG_CRC);
        assertExtractedFile(new File(outDir, "testfile.jpg"), JPG_SIZE, JPG_CRC);
    }

    // ---- helpers ----

    private void assertBothRowsFullyDecoded(List<Rar3PpmdEngineFixtureProbe.Row> rows) {
        assertEquals(2, rows.size());

        Rar3PpmdEngineFixtureProbe.Row png = rows.get(0);
        assertEquals("testfile.png", png.path);
        assertNull(png.failure);
        assertEquals(PNG_SIZE, png.decodedBytes);
        assertEquals(PNG_CRC, png.actualCrc);
        assertTrue(png.crcOk);
        assertTrue(png.magicOk);
        assertEquals("png", png.magicName);

        Rar3PpmdEngineFixtureProbe.Row jpg = rows.get(1);
        assertEquals("testfile.jpg", jpg.path);
        assertNull(jpg.failure);
        assertEquals(JPG_SIZE, jpg.decodedBytes);
        assertEquals(JPG_CRC, jpg.actualCrc);
        assertTrue(jpg.crcOk);
        assertTrue(jpg.magicOk);
        assertEquals("jpeg", jpg.magicName);
    }

    private void assertExtractedFile(File file, int expectedSize, long expectedCrc) throws IOException {
        assertTrue("missing output: " + file, file.isFile());
        byte[] data = Files.readAllBytes(file.toPath());
        assertEquals(expectedSize, data.length);
        assertEquals(expectedCrc, crc32(data));
    }

    private File writeEmbeddedFixture() throws IOException {
        File fixture = temp.newFile("testfile_rar3_solid.cbr");
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            out.write(hexToBytes(FIXTURE_CBR_HEX));
        }
        return fixture;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static File externalFixture() {
        String path = System.getProperty("textview.rar3SolidCbrFixture");
        if (path == null || path.trim().length() == 0) {
            path = System.getenv("TEXTVIEW_RAR3_SOLID_CBR_FIXTURE");
        }
        assumeTrue("Direct RAR3 solid CBR fixture not provided",
                path != null && path.trim().length() > 0);
        File fixture = new File(path);
        assumeTrue("Direct RAR3 solid CBR fixture missing: " + fixture.getAbsolutePath(),
                fixture.isFile());
        return fixture;
    }
}
