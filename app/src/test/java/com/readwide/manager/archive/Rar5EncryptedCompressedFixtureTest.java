package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * End-to-end regression for AES-256 encrypted, compressed RAR5 extraction.
 *
 * <p>The bundled libarchive backend cannot decrypt RAR5 (initially verified with
 * libarchive 3.7.2 against real WinRAR archives: it reports "Encryption is not
 * supported"), so the only route for a
 * password-protected compressed RAR5 entry is the first-party path -
 * {@link Rar5CompressedArchiveExtractor} AES-decrypts each entry with
 * {@link Rar5Crypto} and then decodes it with {@link Rar5CompressedDecoder}.
 * Plain JVM unit tests have no libarchive backend, so
 * {@link RarArchiveReader#extractArchiveIntoDirectory} exercises exactly that
 * first-party route here.
 *
 * <p>The fixture is a genuine WinRAR 7.00 archive
 * ({@code rar a -ma5 -m5 -pReadwide2026}) containing two compressed text
 * entries (method 5, not stored). It was cross-checked with {@code unrar t}
 * ("All OK") and, at the crypto+decode level, byte-for-byte against a Python
 * AES-CBC mirror feeding {@code Rar5CompressedDecoder} (both CRCs matched the
 * originals) before being embedded. This locks in that the encrypted +
 * compressed RAR5 combination extracts correctly and does not regress into a
 * silent wrong-output or an unsupported error.
 */
public class Rar5EncryptedCompressedFixtureTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /** Real WinRAR 7.00 -ma5 -m5 -pReadwide2026 archive: fx/story.txt + fx/notes.txt. */
    private static final String RAR5_AES_COMPRESSED_BASE64 =
            "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAACN8TllsCAzzgAAS4DaSDAnyZmPuABQEMZngvc3RvcnkudHh0MAEAAw/Zp1S8GQ17hvp1mwHZIKxMGT68732GajXSzzCxpD4tXHcpcCMcUxwRLA1LOQoDE5wLR2rHFQsLlNOs42kUsmUnU3xKyqtruqwBXzHpCO7x1kwsXCIikvsMece6bXTmkyeUJMxTjv8XRxPjYZSucwjMTj5r5RULwZXjG1tKI6krCSIx320SFtlcatQw4CcHVK3iZyeVh1jjkFDfQVsCAzzQAASTB6SDAkJ/VmmABQEMZngvbm90ZXMudHh0MAEAAw/Zp1S8GQ17hvp1mwHZIKxMb02TKL/JdvYlGI3CGqeXhHcpcCMcUxwRLA1LOQoDE5wLR2oOHQwLo+peYNMfixRkku87idfkaWJv76CEk2pSUcljVmEKwQ1wKHwljRaEXajGzzEJuzNXcrLEFt+FjRPaYk4VspZulM4WYFAdNXpVAXiBJSDNEVAdd1ZRAwUEAA==";

    private static final char[] PASSWORD = "Readwide2026".toCharArray();

    private static final String STORY_LINE =
            "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.\n";
    private static final String NOTES_LINE =
            "Readwide RAR5 AES compressed decode regression fixture line.\n";

    private static byte[] expected(String line, int repeats) {
        StringBuilder sb = new StringBuilder(line.length() * repeats);
        for (int i = 0; i < repeats; i++) sb.append(line);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private File writeFixture() throws Exception {
        File archive = tempFolder.newFile("rar5aes.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(java.util.Base64.getDecoder().decode(RAR5_AES_COMPRESSED_BASE64));
        }
        return archive;
    }

    @Test
    public void listsEncryptedCompressedRar5Entries() throws Exception {
        File archive = writeFixture();

        List<ArchiveSupport.EntryInfo> entries = RarArchiveReader.listEntries(archive, PASSWORD);

        boolean sawStory = false;
        boolean sawNotes = false;
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry.path.endsWith("story.txt")) sawStory = true;
            if (entry.path.endsWith("notes.txt")) sawNotes = true;
        }
        assertTrue("story.txt not listed: " + entries, sawStory);
        assertTrue("notes.txt not listed: " + entries, sawNotes);
    }

    @Test
    public void extractsEncryptedCompressedRar5ArchiveByteForByte() throws Exception {
        File archive = writeFixture();
        File target = tempFolder.newFolder("out");

        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, target, PASSWORD, null));

        File story = new File(target, "fx/story.txt");
        File notes = new File(target, "fx/notes.txt");
        assertTrue("story.txt missing at " + story, story.isFile());
        assertTrue("notes.txt missing at " + notes, notes.isFile());
        assertArrayEquals(expected(STORY_LINE, 20), Files.readAllBytes(story.toPath()));
        assertArrayEquals(expected(NOTES_LINE, 15), Files.readAllBytes(notes.toPath()));
    }

    @Test
    public void extractsSingleEncryptedCompressedRar5Entry() throws Exception {
        File archive = writeFixture();
        File out = tempFolder.newFile("story-only.txt");

        assertTrue(RarArchiveReader.extractSingleEntry(archive, "fx/story.txt", out, PASSWORD));

        assertArrayEquals(expected(STORY_LINE, 20), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void reportsPasswordRequiredWhenNoPasswordGiven() throws Exception {
        File archive = writeFixture();
        File target = tempFolder.newFolder("nopass");
        try {
            RarArchiveReader.extractArchiveIntoDirectory(archive, target, null, null);
            // Some routes surface the missing password only on read; either a
            // PasswordRequiredException or a plain failure is acceptable, but a
            // silent success writing garbage is not.
            File story = new File(target, "fx/story.txt");
            if (story.isFile()) {
                byte[] data = Files.readAllBytes(story.toPath());
                assertEquals("encrypted content extracted without a password", 0, data.length);
            }
        } catch (ArchiveSupport.PasswordRequiredException expected) {
            // Correct: encryption detected, password demanded.
        } catch (java.io.IOException alsoAcceptable) {
            // Also fine: a clean failure rather than partial/garbage output.
        }
    }
}
