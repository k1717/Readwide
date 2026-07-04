package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Method coverage for the three 7z compression variants that Commons Compress
 * and the bundled libarchive backend split between them:
 *
 * <ul>
 *   <li><b>Deflate64</b> - decoded by Commons Compress (pure Java), so it works
 *       on the primary path everywhere, including these JVM tests. libarchive
 *       does not read this codec (0x040109), so the dedicated path is the only
 *       one - this test proves it end to end.</li>
 *   <li><b>PPMd</b> and <b>BCJ2</b> - not in the Commons Compress 7z coder
 *       table, and historically device-only via the bundled native
 *       libarchive. Both are now decoded first party
 *       ({@code SevenZBcj2ArchiveReader} with {@code SevenZBcj2Decoder} /
 *       {@code SevenZPpmd7Decoder}), so listing and byte-exact extraction
 *       work in every environment including these JVM tests; the assertions
 *       here prove that end to end against pinned payload hashes. The
 *       libarchive fallback remains behind the first-party path on
 *       devices.</li>
 * </ul>
 *
 * The fixtures are self-made (created with p7zip from first-party content),
 * so no third-party archive is redistributed.
 */
public class SevenZMethodCoverageTest {

    // 7z a -m0=PPMd (two text entries: doc1.txt, doc2.txt)
    private static final String PPMD_7Z_BASE64 =
            "N3q8ryccAARG64vv1AAAAAAAAAAhAAAAAAAAAAmbzmQAcvCX1Nw2inq1JeBI14njou4Ko9X9FQlwygidY24AsoVf2k+eN7vf"
            + "dhrxp3bB/sJ3athqK9UzbAAAAAAAAAAAAAAAAAAA5KX+rFO8c9HN5DgxZ9UfZh3uzyFgXgwAkQOiAAAAAAAAAAAAAACBMweu"
            + "D9QQ7H1AwJDTQ8UqLG6gn3tq7btMyjgpnMOzraODO+quO/Z51JS2K1LGyFHXLfrH9+aEF3DOyepuBcddFVLFb8PhaOT1Ukmz"
            + "pg2xqMJoK1mmLC7QNdKQRB2BtEEjLeckHmEAABcGagEJagAHCwEAASMDAQEFXQAQAAAMgIYKAdmVz08AAA==";

    // 7z a -m0=BCJ2 -m1=LZMA -m2=LZMA -m3=LZMA (one binary entry: prog.bin)
    private static final String BCJ2_7Z_BASE64 =
            "N3q8ryccAAQs8sqVMwAAAAAAAACKAAAAAAAAAMsPdkoAdASCUTjAb3ccMO+OD9APKVQ6bFs0DcyVLKev+MKjAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAABBAYABAkgCQUFAAcLAQAEIwMBAQVdABAAACMDAQEFXQAQAAAjAwEBBV0AEAAAFAMDARsEAQUABAEDAgIG"
            + "AQAMAACMgIyAAAgKASZ48a0AAAUBGQkAAAAAAAAAAAAREwBwAHIAbwBnAC4AYgBpAG4AAAAZABQKAQAYDlFc7wndARUGAQAg"
            + "gKSBAAA=";

    // 7z a -m0=Deflate64 (one text entry: doc1.txt)
    private static final String DEFLATE64_7Z_BASE64 =
            "N3q8ryccAAROOQmvWAAAAAAAAABaAAAAAAAAAP6uid7ly7ENgDAMBMBVfgGGihQjLEFiBQOBiiXoGISZkNiBIVL+9TfLKqk7"
            + "YDZF9Fp9KQIL+5hDxKY+4Lvu9znhUh0hRRQxcXXNCfSZPtNn+kyf6TN9ps/0uT3/AQQGAAEJWAAHCwEAAQMEAQkMiYgACAoB"
            + "vUfMnQAABQEZCwAAAAAAAAAAAAAAERMAZABvAGMAMQAuAHQAeAB0AAAAGQAUCgEAXAhRXO8J3QEVBgEAIICkgQAA";

    private static final String DOC1_SHA256 =
            "46113c197db79f84aec5a40614f692e6ab3ac95de23b2689e7e4e0f557f9d407";
    private static final long DOC1_LENGTH = 2440L;
    private static final String PROG_BIN_SHA256 =
            "314ce04b0e8c098c8335daf29f0042566cc3d2e1bfe031438762bec39d3ad444";
    private static final long PROG_BIN_LENGTH = 3200L;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void deflate64SevenZ_listsAndExtractsOnPrimaryPath() throws Exception {
        File archive = writeFixture("fixture-d64.7z", DEFLATE64_7Z_BASE64);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
        assertEquals(1, entries.size());
        assertEquals("doc1.txt", entries.get(0).path);
        assertEquals(DOC1_LENGTH, entries.get(0).size);

        File outDir = tempFolder.newFolder();
        assertTrue(ArchiveSupport.extractArchive(archive, outDir, true, null));
        File extracted = new File(outDir, "doc1.txt");
        assertTrue(extracted.isFile());
        assertEquals(DOC1_SHA256, sha256Hex(extracted));
    }

    @Test
    public void ppmdSevenZ_listsEntriesEvenWithoutLibarchive() throws Exception {
        File archive = writeFixture("fixture-ppmd.7z", PPMD_7Z_BASE64);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(2, entries.size());
    }

    @Test
    public void ppmdSevenZ_singleEntryExtractsFirstParty() throws Exception {
        assertSingleEntryExtractsFirstParty(
                writeFixture("fixture-ppmd.7z", PPMD_7Z_BASE64), "doc1.txt",
                DOC1_SHA256, DOC1_LENGTH);
    }

    @Test
    public void bcj2SevenZ_listsEntriesEvenWithoutLibarchive() throws Exception {
        File archive = writeFixture("fixture-bcj2.7z", BCJ2_7Z_BASE64);

        List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);

        assertEquals(1, entries.size());
        assertEquals("prog.bin", entries.get(0).path);
    }

    @Test
    public void bcj2SevenZ_singleEntryExtractsFirstParty() throws Exception {
        assertSingleEntryExtractsFirstParty(
                writeFixture("fixture-bcj2.7z", BCJ2_7Z_BASE64), "prog.bin",
                PROG_BIN_SHA256, PROG_BIN_LENGTH);
    }

    /**
     * These fixtures used to require the native libarchive backend, and this
     * helper asserted a clean failure without it. The first-party
     * {@code SevenZBcj2ArchiveReader}/{@code SevenZPpmd7Decoder} path now
     * decodes BCJ2 and PPMd folders in pure Java, so extraction succeeds in
     * every environment - with or without libarchive - and the assertion is
     * byte-exact content instead.
     */
    private void assertSingleEntryExtractsFirstParty(File archive, String entryPath,
                                                     String expectedSha256, long expectedLength) throws Exception {
        File out = new File(tempFolder.newFolder(), "single.out");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractSingleEntryDetailed(archive, entryPath, out, null);
        assertTrue(result.success);
        assertEquals(expectedLength, out.length());
        assertEquals(expectedSha256, sha256Hex(out));
    }

    private File writeFixture(String name, String base64) throws IOException {
        File file = new File(tempFolder.getRoot(), name.toLowerCase(Locale.ROOT));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(Base64.getDecoder().decode(base64));
        }
        return file;
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
