package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * Tests for the first-party 7z PPMd path: {@link SevenZPpmd7Decoder} (a Java
 * port of the public-domain Ppmd7 reference; see the decoder javadoc and
 * {@code THIRD_PARTY_NOTICES.md}) wired through
 * {@link SevenZBcj2ArchiveReader}. Commons Compress has no PPMd coder and the
 * bundled libarchive cannot decrypt 7z, so the AES fixture here exercises a
 * combination that previously had no supported path at all.
 *
 * <p>Fixtures are self-made: a deterministic 31,600-byte payload of 400
 * numbered pangram lines, packed by p7zip with
 * {@code -m0=PPMd:mem=64k:o=6} - plain with an uncompressed header, and
 * AES-256 with an encrypted header ({@code -mhe=on}, password
 * {@code pw1717}). Both round trip through the reference {@code 7z} tool.
 * Beyond these, the decoder itself was validated during development against
 * real 7z streams across orders 2-32, memory sizes 64 KiB-1 MiB (forcing
 * model restarts and free-block gluing), and text/random/zero-run payloads.</p>
 */
public class SevenZPpmdArchiveTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String PAYLOAD_SHA256 =
            "d8cb9b211ea4130bdace77b73a1cd345ee0fc3062356599c07534746851b1d73";
    private static final int PAYLOAD_LENGTH = 31600;
    private static final String ENTRY_NAME = "ppmd-sm.txt";
    private static final char[] PASSWORD = "pw1717".toCharArray();

    @Test
    public void ppmdPlain_extractsByteExact() throws Exception {
        File archive = writeFixture("ppmd-sm-plain.7z", Sevenz7PpmdFixtures.PLAIN_B64);

        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        assertFalse(SevenZBcj2ArchiveReader.archiveUsesBcj2(archive, null));

        File target = tempFolder.newFolder("ppmd-plain-out");
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null));
        assertPayload(new File(target, ENTRY_NAME));
    }

    @Test
    public void ppmdWithAesAndEncodedHeader_extractsByteExact() throws Exception {
        File archive = writeFixture("ppmd-sm-aes.7z", Sevenz7PpmdFixtures.AES_B64);

        List<ArchiveSupport.EntryInfo> entries = SevenZBcj2ArchiveReader.listEntries(archive, PASSWORD);
        assertEquals(1, entries.size());
        assertEquals(ENTRY_NAME, entries.get(0).path);

        File out = tempFolder.newFile("ppmd-aes-out.txt");
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, PASSWORD));
        assertPayload(out);
    }

    @Test
    public void ppmdAes_wrongPassword_failsCleanly() throws Exception {
        File archive = writeFixture("ppmd-sm-aes-wrong.7z", Sevenz7PpmdFixtures.AES_B64);
        File out = new File(tempFolder.getRoot(), "wrong.txt");

        try {
            SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, "nope".toCharArray());
            fail("Expected wrong password to fail");
        } catch (IOException expected) {
            // Decrypting the encoded header with the wrong key yields garbage,
            // which fails to parse - a clean IOException, never partial output.
        }
    }

    @Test
    public void ppmdDecoder_rejectsBadProperties() throws Exception {
        try {
            SevenZPpmd7Decoder.decode(new byte[16], new byte[] {1, 0, 0, 4, 0}, 16);
            fail("Expected order 1 to be rejected");
        } catch (IOException expected) {
        }
        try {
            SevenZPpmd7Decoder.decode(new byte[16], new byte[] {6, 0, 0, 0, 0}, 16);
            fail("Expected tiny memory size to be rejected");
        } catch (IOException expected) {
        }
    }

    private File writeFixture(String name, String base64) throws Exception {
        File archive = tempFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(Base64.getDecoder().decode(base64));
        }
        return archive;
    }

    private void assertPayload(File file) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        assertEquals(PAYLOAD_LENGTH, data.length);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
        assertEquals(PAYLOAD_SHA256, sb.toString());
    }
}
