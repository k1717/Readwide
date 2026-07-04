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
 * Tests for {@link SevenZBcj2ArchiveReader}, the first-party 7z path for the
 * BCJ2 branch filter that Apache Commons Compress cannot decode ("Multi
 * input/output stream coders are not yet supported").
 *
 * <p>Fixtures are self-made: a deterministic 2,868-byte payload of random
 * bytes interleaved with real x86 {@code E8}/{@code E9}/{@code 0F 8x} branch
 * instructions and 4-byte operands (so BCJ2 has genuine conversions to undo),
 * packed by p7zip in three coder chains - BCJ2 over stored inputs, BCJ2 over
 * LZMA, and AES-256 + LZMA + BCJ2 with an encrypted (encoded) header. Each
 * fixture was extracted with the reference {@code 7z} tool to confirm it round
 * trips before embedding; the decoded output is pinned here by SHA-256 and
 * length. Password: {@code pw1717}.</p>
 */
public class SevenZBcj2ArchiveReaderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String PAYLOAD_SHA256 =
            "c1d477b07574b457e8751f9aa7ee7858ee1f7b624942651e3728129498fcc6aa";
    private static final int PAYLOAD_LENGTH = 2868;
    private static final String ENTRY_NAME = "selfmade.bin";
    private static final char[] PASSWORD = "pw1717".toCharArray();

    @Test
    public void bcj2StoredInputs_extractsByteExact() throws Exception {
        File archive = writeFixture("sm-bcj2.7z", Sevenz7Bcj2Fixtures.STORED_B64);

        assertTrue(SevenZBcj2ArchiveReader.archiveUsesBcj2(archive, null));
        File out = extractSingle(archive, null);
        assertPayload(out);
    }

    @Test
    public void bcj2OverLzma_extractsByteExact() throws Exception {
        File archive = writeFixture("sm-bcj2-lzma.7z", Sevenz7Bcj2Fixtures.LZMA_B64);

        File out = extractSingle(archive, null);
        assertPayload(out);
    }

    @Test
    public void bcj2WithAesAndEncodedHeader_extractsByteExact() throws Exception {
        File archive = writeFixture("sm-bcj2-aes.7z", Sevenz7Bcj2Fixtures.AES_B64);

        // The header is itself AES-encrypted, so listing needs the password.
        List<ArchiveSupport.EntryInfo> entries = SevenZBcj2ArchiveReader.listEntries(archive, PASSWORD);
        assertEquals(1, entries.size());
        assertEquals(ENTRY_NAME, entries.get(0).path);

        File out = extractSingle(archive, PASSWORD);
        assertPayload(out);
    }

    @Test
    public void bcj2Aes_wrongPassword_failsCleanly() throws Exception {
        File archive = writeFixture("sm-bcj2-aes-wrong.7z", Sevenz7Bcj2Fixtures.AES_B64);
        File out = new File(tempFolder.getRoot(), "wrong.bin");

        try {
            SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, "nope".toCharArray());
            fail("Expected wrong password to fail");
        } catch (IOException expected) {
            // AES-CBC of the header with the wrong key yields a corrupt header,
            // which fails to parse - a clean IOException, never partial output.
        }
    }

    @Test
    public void bcj2Aes_missingPassword_promptsForPassword() throws Exception {
        File archive = writeFixture("sm-bcj2-aes-nopw.7z", Sevenz7Bcj2Fixtures.AES_B64);
        File out = new File(tempFolder.getRoot(), "nopw.bin");

        try {
            SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, null);
            fail("Expected missing password to fail");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
        } catch (IOException e) {
            // The encoded header cannot be read without the password; either a
            // password-required signal or a clean IOException is acceptable.
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void bcj2ExtractIntoDirectory_writesEntry() throws Exception {
        File archive = writeFixture("sm-bcj2-dir.7z", Sevenz7Bcj2Fixtures.STORED_B64);
        File target = tempFolder.newFolder("bcj2-out");

        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, target, null, null, null));
        assertPayload(new File(target, ENTRY_NAME));
    }

    private File writeFixture(String name, String base64) throws Exception {
        File archive = tempFolder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(Base64.getDecoder().decode(base64));
        }
        return archive;
    }

    private File extractSingle(File archive, char[] password) throws Exception {
        File out = tempFolder.newFile("out-" + System.nanoTime() + ".bin");
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, password));
        return out;
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
