package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
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
import java.util.zip.CRC32;

/**
 * End-to-end verification of the first-party RAR5 (v5.0) compressed
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
    public void decoderRejectsNonV50AlgorithmVersions() {
        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        try {
            decoder.decodeEntry(new byte[8], 4, 0x0e80L | 1L); // algo version 1 (RAR7-era)
            fail("Non-5.0 algorithm versions must be rejected");
        } catch (Rar5CompressedDecoder.Rar5DataException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("version"));
        }
    }

    // ---- external fixtures (assume-gated; the rar-test-files build dir) ----

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
