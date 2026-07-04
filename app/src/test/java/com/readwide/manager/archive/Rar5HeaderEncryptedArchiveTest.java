package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * Tests for the first-party RAR5 header-encryption ({@code -hp}) path. RAR5
 * {@code -hp} archives previously had no path at all: the bundled libarchive
 * does not decrypt RAR5 headers (it cannot even list such archives), and the
 * first-party reader raised "not supported yet". {@code readRar5Entries} now
 * derives the header key from the archive encryption block via
 * {@link Rar5Crypto} and reads every following header through AES-256-CBC,
 * after which the existing stored-decrypt and compressed decrypt+decode
 * machinery applies unchanged.
 *
 * <p>Fixtures are self-made with the RAR 7.00 CLI from a deterministic
 * 2,680-byte payload (see {@link Rar5HeaderEncryptedFixtures}); the wider
 * matrix (solid, multi-volume, {@code -m5}, Korean entry names, 84 KB mixed
 * payloads) was validated against UNRAR 7.00 output during development.</p>
 */
public class Rar5HeaderEncryptedArchiveTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String PAYLOAD_SHA256 =
            "341a4a5a26b9e2e636aa4dc2645322599aeca379422f461c8a41f67b41160197";
    private static final int PAYLOAD_LENGTH = 2680;
    private static final String ENTRY_NAME = "hp-sm.txt";
    private static final char[] PASSWORD = "speak".toCharArray();

    @Test
    public void headerEncryptedStored_listsAndExtracts() throws Exception {
        File archive = writeFixture("hp-sm-m0.rar", Rar5HeaderEncryptedFixtures.STORED_B64);

        List<ArchiveSupport.EntryInfo> entries = RarArchiveReader.listEntries(archive, PASSWORD);
        assertEquals(1, entries.size());
        assertEquals(ENTRY_NAME, entries.get(0).path);

        File target = tempFolder.newFolder("hp-stored-out");
        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, target, PASSWORD, null, null));
        assertPayload(new File(target, ENTRY_NAME));
    }

    @Test
    public void headerEncryptedCompressed_extractsSingleEntry() throws Exception {
        File archive = writeFixture("hp-sm-m3.rar", Rar5HeaderEncryptedFixtures.COMPRESSED_B64);

        List<ArchiveSupport.EntryInfo> entries = RarArchiveReader.listEntries(archive, PASSWORD);
        assertEquals(1, entries.size());

        File out = tempFolder.newFile("hp-compressed-out.txt");
        assertTrue(RarArchiveReader.extractSingleEntry(archive, ENTRY_NAME, out, PASSWORD));
        assertPayload(out);
    }

    @Test
    public void headerEncrypted_missingPassword_prompts() throws Exception {
        File archive = writeFixture("hp-sm-m0-nopw.rar", Rar5HeaderEncryptedFixtures.STORED_B64);
        try {
            RarArchiveReader.listEntries(archive, null);
            fail("Expected a password prompt");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
        }
    }

    @Test
    public void headerEncrypted_wrongPassword_promptsAgain() throws Exception {
        File archive = writeFixture("hp-sm-m0-wrong.rar", Rar5HeaderEncryptedFixtures.STORED_B64);
        try {
            RarArchiveReader.listEntries(archive, "wrong".toCharArray());
            fail("Expected the check-value mismatch to re-prompt");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
            // The crypt header's password check value catches the wrong
            // password before any header is parsed; no partial output.
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
