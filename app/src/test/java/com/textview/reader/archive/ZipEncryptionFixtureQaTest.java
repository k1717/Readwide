package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Real-fixture QA for the encrypted ZIP/CBZ password boundaries.
 *
 * <p>Fixtures are generated at test time with Zip4j (a first-party
 * dependency), covering both ZipCrypto and AES-256 encryption, so no binary
 * fixture needs to be embedded. The expectations encode Readwide's design:
 * listing an encrypted ZIP without a password throws
 * {@link ArchiveSupport.PasswordRequiredException} (the UI uses this to
 * raise the password prompt), a wrong password classifies as BAD_PASSWORD
 * with stale output cleaned up, and a correct password extracts byte-exact
 * content.</p>
 */
public class ZipEncryptionFixtureQaTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String PASSWORD = "SECRET";

    private File payload1;
    private File payload2;

    @Test
    public void zipCryptoBoundariesBehaveLikeDesign() throws Exception {
        runMatrix(buildEncryptedZip("zc.zip", EncryptionMethod.ZIP_STANDARD), "zipcrypto");
    }

    @Test
    public void aes256BoundariesBehaveLikeDesign() throws Exception {
        runMatrix(buildEncryptedZip("aes.zip", EncryptionMethod.AES), "aes");
    }

    @Test
    public void aes256CbzRenameBehavesIdentically() throws Exception {
        File zip = buildEncryptedZip("aescbz.zip", EncryptionMethod.AES);
        File cbz = temp.newFile("renamed.cbz");
        Files.copy(zip.toPath(), cbz.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        runMatrix(cbz, "cbz");
    }

    @Test
    public void plainZipDoesNotRequirePassword() throws Exception {
        ensurePayloads();
        File plain = new File(temp.newFolder("pl"), "plain.zip");
        new ZipFile(plain).addFiles(Arrays.asList(payload1, payload2));
        assertFalse(ArchiveSupport.requiresPasswordForExtraction(plain));
        File dest = new File(temp.newFolder("pl2"), "out");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractArchiveDetailed(plain, dest, true, null, null);
        assertTrue(String.valueOf(result.failure), result.success);
    }

    @Test
    public void corruptEncryptedZipWithCorrectPasswordStillFails() throws Exception {
        File zip = buildEncryptedZip("corrupt.zip", EncryptionMethod.AES);
        byte[] data = Files.readAllBytes(zip.toPath());
        data[data.length / 2] ^= 0x5A; // damage payload area
        File corrupt = temp.newFile("damaged.zip");
        Files.write(corrupt.toPath(), data);
        File dest = new File(temp.newFolder("co"), "out");
        ArchiveSupport.ExtractionResult result =
                ArchiveSupport.extractArchiveDetailed(corrupt, dest, true, PASSWORD.toCharArray(), null);
        assertFalse("damaged encrypted ZIP must not extract successfully", result.success);
    }

    // ---- shared matrix ----

    private void runMatrix(File archive, String tag) throws Exception {
        // listing without a password throws by design
        try {
            ArchiveSupport.listEntries(archive, null);
            fail(tag + ": encrypted ZIP listing without a password must throw");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
            // expected
        }
        assertEquals(2, ArchiveSupport.listEntries(archive, PASSWORD.toCharArray()).size());
        assertTrue(tag + ": requiresPasswordForExtraction must be true",
                ArchiveSupport.requiresPasswordForExtraction(archive));

        // no password -> PASSWORD_REQUIRED
        ArchiveSupport.ExtractionResult noPw = ArchiveSupport.extractArchiveDetailed(
                archive, new File(temp.newFolder(tag + "np"), "out"), true, null, null);
        assertFalse(noPw.success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, noPw.failure);

        // wrong password -> BAD_PASSWORD
        ArchiveSupport.ExtractionResult wrong = ArchiveSupport.extractArchiveDetailed(
                archive, new File(temp.newFolder(tag + "wp"), "out"), true, "WRONG".toCharArray(), null);
        assertFalse(wrong.success);
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, wrong.failure);

        // wrong password single-entry -> BAD_PASSWORD and no stale output
        File singleOut = new File(temp.newFolder(tag + "ws"), "zpage1.txt");
        ArchiveSupport.ExtractionResult wrongSingle = ArchiveSupport.extractSingleEntryDetailed(
                archive, "zpage1.txt", singleOut, "WRONG".toCharArray());
        assertFalse(wrongSingle.success);
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, wrongSingle.failure);
        assertFalse(tag + ": failed single-entry extraction must not leave stale output",
                singleOut.exists());

        // correct password -> byte-exact extraction (whole and single-entry)
        File dest = new File(temp.newFolder(tag + "ok"), "out");
        ArchiveSupport.ExtractionResult ok = ArchiveSupport.extractArchiveDetailed(
                archive, dest, true, PASSWORD.toCharArray(), null);
        assertTrue(String.valueOf(ok.failure), ok.success);
        assertSameContent(new File(dest, "zpage1.txt"), payload1);
        assertSameContent(new File(dest, "zpage2.txt"), payload2);

        File singleOk = new File(temp.newFolder(tag + "os"), "zpage2.txt");
        ArchiveSupport.ExtractionResult okSingle = ArchiveSupport.extractSingleEntryDetailed(
                archive, "zpage2.txt", singleOk, PASSWORD.toCharArray());
        assertTrue(String.valueOf(okSingle.failure), okSingle.success);
        assertSameContent(singleOk, payload2);
    }

    // ---- helpers ----

    private void ensurePayloads() throws IOException {
        if (payload1 != null) return;
        payload1 = temp.newFile("zpage1.txt");
        payload2 = temp.newFile("zpage2.txt");
        StringBuilder s1 = new StringBuilder();
        StringBuilder s2 = new StringBuilder();
        for (int i = 0; i < 300; i++) s1.append("readwide zip fixture alpha ").append(i).append('\n');
        for (int i = 0; i < 320; i++) s2.append("readwide zip fixture beta ").append(i).append('\n');
        Files.write(payload1.toPath(), s1.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(payload2.toPath(), s2.toString().getBytes(StandardCharsets.UTF_8));
    }

    private File buildEncryptedZip(String name, EncryptionMethod method) throws IOException {
        ensurePayloads();
        File archive = new File(temp.newFolder("fx_" + name.replace('.', '_')), name);
        ZipParameters params = new ZipParameters();
        params.setEncryptFiles(true);
        params.setEncryptionMethod(method);
        if (method == EncryptionMethod.AES) {
            params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }
        ZipFile zip = new ZipFile(archive, PASSWORD.toCharArray());
        zip.addFiles(Arrays.asList(payload1, payload2), params);
        return archive;
    }

    private static void assertSameContent(File actual, File expected) throws IOException {
        assertTrue("missing output: " + actual, actual.isFile());
        assertEquals("size mismatch for " + actual.getName(), expected.length(), actual.length());
        assertEquals("content mismatch for " + actual.getName(), crc(expected), crc(actual));
    }

    private static long crc(File file) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(Files.readAllBytes(file.toPath()));
        return crc.getValue();
    }
}
