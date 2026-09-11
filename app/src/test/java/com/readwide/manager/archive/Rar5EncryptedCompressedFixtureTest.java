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

    @Test public void encryptedCompressedSplitChecksPackedHashBeforePublishing() throws Exception {
        File archive = writeFixture();
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive,PASSWORD).get(0);
        byte[] all = Files.readAllBytes(archive.toPath());
        byte[] packed = java.util.Arrays.copyOfRange(all,(int)original.dataOffset,
                (int)(original.dataOffset+original.packedSize));
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(PASSWORD,
                original.encryption.kdfCount,original.encryption.salt);
        for (boolean corrupt : new boolean[]{false,true}) {
            List<RarArchiveReader.RarEntry> chain = new java.util.ArrayList<>();
            for (int i=0;i<2;i++) {
                byte[] bytes = java.util.Arrays.copyOfRange(packed,i == 0 ? 0 : 17,
                        i == 0 ? 17 : packed.length);
                long crc = original.dataCrc;
                byte[] hash = original.blake2sp;
                if (i == 0) {
                    java.util.zip.CRC32 check = new java.util.zip.CRC32(); check.update(bytes);
                    crc = check.getValue();
                    RarBlake2sp blake = new RarBlake2sp(); blake.update(bytes,0,bytes.length);
                    hash = blake.digest();
                    if (!RarStoredPayloadIO.hasPlaintextCrc(original)) {
                        crc = Rar5Crypto.tweakCrc32(crc,secrets);
                        hash = Rar5Crypto.tweakBlake2sp(hash,secrets);
                    }
                    if (corrupt) hash[0] ^= 1; // Valid intermediate CRC and final decoded CRC.
                }
                File volume = tempFolder.newFile(); Files.write(volume.toPath(),bytes);
                RarArchiveReader.RarEntry part = new RarArchiveReader.RarEntry(original.path,false,
                        original.unpackedSize,bytes.length,0,5,original.method,original.solid,
                        i == 1,i == 0,original.encryption,crc,0,original.rar5CompressionInfo,hash);
                part.sourceArchive = volume; chain.add(part);
            }
            File out = tempFolder.newFile();
            try {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(chain.get(0),chain,out,PASSWORD,null));
                if (corrupt) org.junit.Assert.fail("Corrupt intermediate hash must fail");
                assertArrayEquals(expected(STORY_LINE,20),Files.readAllBytes(out.toPath()));
            } catch (java.io.IOException expected) {
                if (!corrupt) throw expected;
                assertTrue(expected.getMessage().contains("packed-volume"));
                org.junit.Assert.assertFalse(out.exists());
            }
        }
    }

    @Test public void compressedHashMacChecksBlakeEvenWhenCrcMatches() throws Exception {
        File archive = writeFixture();
        RarArchiveReader.RarEntry original = RarArchiveReader.readEntries(archive,PASSWORD).get(0);
        byte[] plain = expected(STORY_LINE,20);
        RarBlake2sp blake = new RarBlake2sp(); blake.update(plain,0,plain.length);
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(PASSWORD,
                original.encryption.kdfCount,original.encryption.salt);
        byte[] digest = blake.digest();
        if (!RarStoredPayloadIO.hasPlaintextCrc(original)) digest = Rar5Crypto.tweakBlake2sp(digest,secrets);
        for (boolean corrupt : new boolean[]{false,true}) {
            byte[] stored = digest.clone(); if (corrupt) stored[0] ^= 1;
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(original.path,false,
                    original.unpackedSize,original.packedSize,original.dataOffset,5,original.method,
                    original.solid,false,false,original.encryption,original.dataCrc,original.timeMillis,
                    original.rar5CompressionInfo,stored);
            entry.sourceArchive = archive;
            File out = tempFolder.newFile();
            try {
                assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entry,
                        java.util.Collections.singletonList(entry),out,PASSWORD,null));
                if (corrupt) org.junit.Assert.fail("Valid CRC must not mask a bad BLAKE2sp digest");
                assertArrayEquals(plain,Files.readAllBytes(out.toPath()));
            } catch (java.io.IOException expected) {
                if (!corrupt) throw expected;
                org.junit.Assert.assertFalse(out.exists());
            }
        }
    }

    @Test
    public void forwardEncryptedReaderVerifiesEachEntryAndOwnsPasswordCopy() throws Exception {
        File archive = writeFixture();
        File spool = tempFolder.newFolder("forward");
        char[] password = PASSWORD.clone();
        try (ArchiveSupport.ForwardArchiveReader reader =
                     ArchiveSupport.openForwardReader(archive, password, spool)) {
            java.util.Arrays.fill(password, '\0');
            ArchiveSupport.ForwardEntry entry;
            int files = 0;
            while ((entry = reader.nextEntry()) != null) {
                if (entry.directory) continue;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[113];
                int count;
                while ((count = reader.read(buffer)) != -1) out.write(buffer, 0, count);
                assertArrayEquals(entry.path.endsWith("story.txt")
                        ? expected(STORY_LINE, 20) : expected(NOTES_LINE, 15), out.toByteArray());
                files++;
            }
            assertEquals(2, files);
        }
        assertEquals(0, spool.list().length);
    }

    @Test
    public void forwardWrongPasswordLeavesNoSpoolAndCannotBeReused() throws Exception {
        File spool = tempFolder.newFolder("forward-wrong");
        try (ArchiveSupport.ForwardArchiveReader reader =
                     Rar5CompressedArchiveExtractor.openForwardReader(writeFixture(), "wrong".toCharArray(), spool, false)) {
            reader.nextEntry();
            try { reader.read(new byte[1]); org.junit.Assert.fail("Expected password failure"); }
            catch (java.io.IOException expected) { }
            assertEquals(0, spool.list().length);
            try { reader.nextEntry(); org.junit.Assert.fail("Failed reader must not resume"); }
            catch (java.io.IOException expected) { }
        }
    }

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

    @Test
    public void realWinrarChecksumsMatchHashMacTransform() throws Exception {
        List<RarArchiveReader.RarEntry> entries =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(writeFixture(), PASSWORD);
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.directory) continue;
            byte[] plain = entry.path.endsWith("story.txt") ? expected(STORY_LINE, 20) : expected(NOTES_LINE, 15);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(plain);
            assertTrue("Fixture must exercise HashMAC", (entry.encryption.flags & 2) != 0);
            Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(PASSWORD,
                    entry.encryption.kdfCount, entry.encryption.salt);
            assertEquals(entry.dataCrc, Rar5Crypto.tweakCrc32(crc.getValue(), secrets));
        }
    }

    @Test
    public void encryptedCompressedCrcWorksWithoutPasswordCheckAndRejectsTampering() throws Exception {
        RarArchiveReader.RarEntry original =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(writeFixture(), PASSWORD).get(0);
        RarArchiveReader.EncryptionInfo enc = original.encryption;
        RarArchiveReader.EncryptionInfo withoutCheck = new RarArchiveReader.EncryptionInfo(
                enc.version, enc.flags & ~1L, enc.kdfCount, enc.salt, enc.iv, new byte[0]);
        RarArchiveReader.RarEntry entry = copyEntry(original, withoutCheck, original.dataCrc,
                original.sourceArchive, original.dataOffset, original.packedSize, false, false);
        File out = new File(tempFolder.getRoot(), "without-check.txt");
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(entry,
                java.util.Collections.singletonList(entry), out, PASSWORD, null));
        assertArrayEquals(expected(STORY_LINE, 20), Files.readAllBytes(out.toPath()));

        RarArchiveReader.RarEntry bad = copyEntry(original, enc, original.dataCrc ^ 1,
                original.sourceArchive, original.dataOffset, original.packedSize, false, false);
        File rejected = new File(tempFolder.getRoot(), "bad-crc.txt");
        try {
            Rar5CompressedArchiveExtractor.tryExtractEntry(bad,
                    java.util.Collections.singletonList(bad), rejected, PASSWORD, null);
            org.junit.Assert.fail("A valid password must not bypass damaged content CRC");
        } catch (java.io.IOException expected) { org.junit.Assert.assertFalse(rejected.exists()); }
    }

    @Test
    public void encryptedCompressedSplitChecksFinalHashMacCrc() throws Exception {
        RarArchiveReader.RarEntry original =
                RarArchiveReader.readEntriesForSplitStoredDiagnostics(writeFixture(), PASSWORD).get(0);
        byte[] archive = Files.readAllBytes(original.sourceArchive.toPath());
        byte[] packed = java.util.Arrays.copyOfRange(archive, (int) original.dataOffset,
                (int) (original.dataOffset + original.packedSize));
        File firstFile = tempFolder.newFile("hashmac.part1.rar");
        File lastFile = tempFolder.newFile("hashmac.part2.rar");
        Files.write(firstFile.toPath(), java.util.Arrays.copyOfRange(packed, 0, 17));
        Files.write(lastFile.toPath(), java.util.Arrays.copyOfRange(packed, 17, packed.length));
        RarArchiveReader.RarEntry first = copyEntry(original, original.encryption, 123,
                firstFile, 0, 17, false, true);
        RarArchiveReader.RarEntry last = copyEntry(original, original.encryption, original.dataCrc,
                lastFile, 0, packed.length - 17, true, false);
        File out = new File(tempFolder.getRoot(), "split.txt");
        assertTrue(Rar5CompressedArchiveExtractor.tryExtractEntry(first,
                java.util.Arrays.asList(first, last), out, PASSWORD, null));
        assertArrayEquals(expected(STORY_LINE, 20), Files.readAllBytes(out.toPath()));
        RarArchiveReader.RarEntry badLast = copyEntry(last, last.encryption, last.dataCrc ^ 1,
                lastFile, 0, last.packedSize, true, false);
        File rejected = new File(tempFolder.getRoot(), "split-bad.txt");
        try {
            Rar5CompressedArchiveExtractor.tryExtractEntry(first,
                    java.util.Arrays.asList(first, badLast), rejected, PASSWORD, null);
            org.junit.Assert.fail("Final split checksum must be verified");
        } catch (java.io.IOException expected) { org.junit.Assert.assertFalse(rejected.exists()); }
    }

    private static RarArchiveReader.RarEntry copyEntry(RarArchiveReader.RarEntry source,
            RarArchiveReader.EncryptionInfo encryption, long crc, File volume,
            long offset, long packedSize, boolean before, boolean after) {
        RarArchiveReader.RarEntry copy = new RarArchiveReader.RarEntry(source.path, false,
                source.unpackedSize, packedSize, offset, 5, source.method, source.solid,
                before, after, encryption, crc, source.timeMillis, source.rar5CompressionInfo);
        copy.sourceArchive = volume;
        return copy;
    }
}
