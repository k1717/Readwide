package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the public selected-entry dispatcher using complete synthetic RAR containers. */
public class Rar3SelectedSolidFallbackTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final char[] PASSWORD = "bridge".toCharArray();
    private static final byte[] SALT = {1,2,3,4,5,6,7,8};
    private static final byte[] ORIGINAL = {42,43};

    @Test public void selectedAesSolidUsesLogicalFallback() throws Exception { success(true, false, false); }
    @Test public void selectedSplitSolidUsesLogicalFallback() throws Exception { success(false, true, false); }
    @Test public void selectedAesSplitSolidUsesLogicalFallback() throws Exception { success(true, true, false); }
    @Test public void storedMemberDoesNotResetSelectedAesSolidHistory() throws Exception { success(true, false, true); }

    @Test public void badTargetCrcPreservesExistingDestination() throws Exception {
        File archive = fixture(true, false, false, false, true);
        File output = original();
        ArchiveSupport.ExtractionResult result = extract(archive, output, PASSWORD);
        assertFalse(result.success);
        assertTrue(result.detail, result.detail != null && result.detail.contains("CRC"));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void badPrimerCrcPreservesExistingDestination() throws Exception {
        File archive = fixture(true, false, false, true, false);
        File output = original();
        ArchiveSupport.ExtractionResult result = extract(archive, output, PASSWORD);
        assertFalse(result.success);
        assertTrue(result.detail, result.detail != null && result.detail.contains("CRC"));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void badTargetCrcLeavesNoNewPartialFile() throws Exception {
        File archive = fixture(true, false, false, false, true);
        File output = new File(temporary.newFolder(), "new.bin");
        ArchiveSupport.ExtractionResult result = extract(archive, output, PASSWORD);
        assertFalse(result.success);
        assertTrue(result.detail, result.detail != null && result.detail.contains("CRC"));
        assertFalse(output.exists());
    }

    @Test public void missingPasswordPreservesDestination() throws Exception {
        File archive = fixture(true, false, false, false, false);
        File output = original();
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, extract(archive, output, null).failure);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void wrongPasswordDoesNotCommitOutput() throws Exception {
        File archive = fixture(true, false, false, false, false);
        File output = original();
        assertFalse(extract(archive, output, "wrong".toCharArray()).success);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void missingTargetVolumeDoesNotCommitOutput() throws Exception {
        File archive = fixture(false, true, false, false, false);
        assertTrue(new File(archive.getParentFile(), "book.part2.rar").delete());
        File output = original();
        assertFalse(extract(archive, output, null).success);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    @Test public void interruptedPublicRequestPreservesFlagAndDestination() throws Exception {
        File archive = fixture(true, false, false, false, false);
        File output = original();
        Thread.currentThread().interrupt();
        try {
            assertFalse(extract(archive, output, PASSWORD).success);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(ORIGINAL, Files.readAllBytes(output.toPath()));
    }

    private void success(boolean encrypted, boolean split, boolean stored) throws Exception {
        File archive = fixture(encrypted, split, stored, false, false);
        byte[] source = Files.readAllBytes(archive.toPath());
        File output = original();
        ArchiveSupport.ExtractionResult result = extract(archive, output, encrypted ? PASSWORD : null);
        assertTrue(result.failure + ": " + result.detail, result.success);
        assertArrayEquals(new byte[]{'B'}, Files.readAllBytes(output.toPath()));
        assertArrayEquals(source, Files.readAllBytes(archive.toPath()));
        assertArrayEquals(new String[]{output.getName()}, output.getParentFile().list());
    }

    static ArchiveSupport.ExtractionResult extract(File archive, File output, char[] password) {
        return ArchiveSupport.extractSingleEntryDetailed(archive, "target.bin", output, password);
    }

    File original() throws Exception {
        File output = new File(temporary.newFolder(), "selected.bin");
        Files.write(output.toPath(), ORIGINAL);
        return output;
    }

    File fixture(boolean encrypted, boolean split, boolean stored, boolean badPrimer, boolean badTarget) throws Exception {
        File directory = temporary.newFolder();
        File first = new File(directory, split ? "book.part1.rar" : "book.rar");
        ByteArrayOutputStream one = volume(split);
        byte[] primer = payload(Rar3FirstPartyArchiveExtractorTest.independentLzFixture(), encrypted);
        append(one, "primer.bin", primer, 2, crc(new byte[]{'A','B'}) ^ (badPrimer ? 1 : 0),
                0x33, encrypted, false, false, false);
        if (stored) append(one, "stored.bin", new byte[]{42}, 1, crc(new byte[]{42}), 0x30, false, false, false, false);
        byte[] target = payload(new byte[]{0x60,0}, encrypted);
        long checksum = crc(new byte[]{'B'}) ^ (badTarget ? 1 : 0);
        if (split) {
            int cut = Math.min(7, target.length - 1);
            byte[] head = Arrays.copyOf(target, cut);
            append(one, "target.bin", head, 1, crc(head), 0x33, encrypted, true, false, true);
            one.write(header(0x7b, 1, new byte[0]));
            ByteArrayOutputStream two = volume(true);
            append(two, "target.bin", Arrays.copyOfRange(target, cut, target.length), 1,
                    checksum, 0x33, encrypted, true, true, false);
            two.write(header(0x7b, 0, new byte[0]));
            Files.write(new File(directory, "book.part2.rar").toPath(), two.toByteArray());
        } else {
            append(one, "target.bin", target, 1, checksum, 0x33, encrypted, true, false, false);
            one.write(header(0x7b, 0, new byte[0]));
        }
        Files.write(first.toPath(), one.toByteArray());
        return first;
    }

    private static ByteArrayOutputStream volume(boolean split) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x52,0x61,0x72,0x21,0x1a,7,0});
        out.write(header(0x73, split ? 1 : 0, new byte[6]));
        return out;
    }

    private static void append(ByteArrayOutputStream out, String name, byte[] packed, long size,
            long checksum, int method, boolean encrypted, boolean solid, boolean before, boolean after) throws Exception {
        byte[] rawName = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        le(body, packed.length, 4); le(body, size, 4); body.write(2); le(body, checksum, 4);
        le(body, 0, 4); body.write(29); body.write(method); le(body, rawName.length, 2);
        le(body, 0x20, 4); body.write(rawName); if (encrypted) body.write(SALT);
        int flags = 0x8000 | (encrypted ? 0x404 : 0) | (solid ? 0x10 : 0)
                | (before ? 1 : 0) | (after ? 2 : 0);
        out.write(header(0x74, flags, body.toByteArray())); out.write(packed);
    }

    private static byte[] header(int type, int flags, byte[] body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type); le(out, flags, 2); le(out, body.length + 7, 2); out.write(body);
        byte[] checked = out.toByteArray(); out.reset(); le(out, crc(checked) & 0xffff, 2); out.write(checked);
        return out.toByteArray();
    }
    private static void le(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int)(value >>> (i * 8)) & 255);
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
    private static byte[] payload(byte[] plain, boolean encrypted) throws Exception {
        if (!encrypted) return plain;
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(PASSWORD, SALT);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parameters.key, "AES"), new IvParameterSpec(parameters.iv));
        return cipher.doFinal(Arrays.copyOf(plain, (plain.length + 15) / 16 * 16));
    }
}
