package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
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
import static org.junit.Assert.*;

/** Public forward routing over synthetic encrypted and plain LZ archives. */
public class RarForwardPasswordRoutingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "test".toCharArray();
    private static final byte[] SALT = {1,2,3,4,5,6,7,8};

    @Test public void missingPasswordSurvivesIndependentProbe() throws Exception { missing(null, false); }
    @Test public void emptyPasswordSurvivesIndependentProbe() throws Exception { missing(new char[0], false); }
    @Test public void missingPasswordSurvivesSolidProbe() throws Exception { missing(null, true); }
    @Test public void emptyPasswordSurvivesSolidProbe() throws Exception { missing(new char[0], true); }
    @Test public void correctPasswordReadsSolidSuccessor() throws Exception { positive(true, false); }
    @Test public void correctPasswordDrainsSolidPrimer() throws Exception { positive(true, true); }
    @Test public void plainSolidStillReads() throws Exception { positive(false, false); }
    @Test public void plainSolidStillDrains() throws Exception { positive(false, true); }

    @Test public void interruptedOpenRemainsCancellation() throws Exception {
        File archive = archive(true, true), spool = temp.newFolder();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ArchiveSupport.openForwardReader(archive, null, spool));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(new String[0], spool.list());
    }

    private void missing(char[] password, boolean solid) throws Exception {
        File archive = archive(true, solid), spool = temp.newFolder();
        byte[] original = Files.readAllBytes(archive.toPath());
        assertThrows(ArchiveSupport.PasswordRequiredException.class,
                () -> ArchiveSupport.openForwardReader(archive, password, spool));
        assertArrayEquals(original, Files.readAllBytes(archive.toPath()));
        assertArrayEquals(new String[0], spool.list());
    }

    private void positive(boolean encrypted, boolean skip) throws Exception {
        File archive = archive(encrypted, true), spool = temp.newFolder();
        byte[] original = Files.readAllBytes(archive.toPath());
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(
                archive, encrypted ? PASSWORD : null, spool)) {
            assertNotNull(reader);
            assertEquals("primer.bin", reader.nextEntry().path);
            if (skip) assertTrue(reader.drainCurrentEntry(2));
            else assertArrayEquals(new byte[]{'A','B'}, read(reader));
            assertEquals("target.bin", reader.nextEntry().path);
            assertArrayEquals(new byte[]{'B'}, read(reader));
            assertNull(reader.nextEntry());
        }
        assertArrayEquals(original, Files.readAllBytes(archive.toPath()));
        assertArrayEquals(new String[0], spool.list());
    }

    private static byte[] read(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1]; int count;
        while ((count = reader.read(buffer)) >= 0) { assertTrue(count > 0); out.write(buffer, 0, count); }
        return out.toByteArray();
    }

    private File archive(boolean encrypted, boolean solid) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x52,0x61,0x72,0x21,0x1a,0x07,0});
        header(out, 0x73, solid ? 8 : 0, new byte[6]);
        member(out, "primer.bin", Rar3FirstPartyArchiveExtractorTest.independentLzFixture(),
                new byte[]{'A','B'}, encrypted, false);
        if (solid) member(out, "target.bin", new byte[]{0x60,0}, new byte[]{'B'}, encrypted, true);
        header(out, 0x7b, 0, new byte[0]);
        File archive = temp.newFile("sample.rar"); Files.write(archive.toPath(), out.toByteArray());
        return archive;
    }

    private static void member(ByteArrayOutputStream out, String name, byte[] packed, byte[] raw,
            boolean encrypted, boolean solid) throws Exception {
        if (encrypted) {
            Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(PASSWORD, SALT);
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parameters.key, "AES"), new IvParameterSpec(parameters.iv));
                packed = cipher.doFinal(Arrays.copyOf(packed, (packed.length + 15) / 16 * 16));
            } finally { Arrays.fill(parameters.key, (byte) 0); Arrays.fill(parameters.iv, (byte) 0); }
        }
        byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        little(body, packed.length, 4); little(body, raw.length, 4); body.write(3);
        little(body, crc(raw), 4); little(body, 0, 4); body.write(29); body.write(0x33);
        little(body, encoded.length, 2); little(body, 0, 4); body.write(encoded);
        if (encrypted) body.write(SALT);
        header(out, 0x74, 0x8000 | (encrypted ? 0x404 : 0) | (solid ? 0x10 : 0), body.toByteArray());
        out.write(packed);
    }

    private static void header(ByteArrayOutputStream out, int type, int flags, byte[] body) throws IOException {
        ByteArrayOutputStream tail = new ByteArrayOutputStream(); tail.write(type);
        little(tail, flags, 2); little(tail, body.length + 7, 2); tail.write(body);
        little(out, crc(tail.toByteArray()) & 0xffff, 2); out.write(tail.toByteArray());
    }
    private static void little(ByteArrayOutputStream out, long value, int count) {
        for (int i = 0; i < count; i++) out.write((int) (value >>> (i * 8)) & 255);
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
