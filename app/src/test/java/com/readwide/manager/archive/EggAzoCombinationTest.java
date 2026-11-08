package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic containers, not evidence that ALZip produces these combinations. */
public class EggAzoCombinationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "azo-combination".toCharArray();
    private static final int[] CIPHERS = {0, 1, 2, 5, 6};
    private static final int END = 0x08e28222;
    private static final byte[] SENTINEL = {42, 17};

    @Test public void compressedBlocksCrossCipherVolumeAndFileBoundaries() throws Exception {
        for (int cipher : CIPHERS) for (boolean solid : new boolean[]{false, true}) {
            Fixture fixture = fixture(cipher, solid);
            check(write(fixture, -1)[0], solid);
            File[] split = write(fixture, fixture.secondPayload + 19);
            check(split[0], solid);
            check(split[1], solid);
        }
    }

    @Test public void splitInsideEncryptionFieldAndAzoFrameKeepsState() throws Exception {
        for (int cipher : CIPHERS) {
            Fixture fixture = fixture(cipher, true);
            for (int cut : new int[]{fixture.encryption + 8, fixture.firstPayload + 1,
                    fixture.firstPayload + 15, fixture.secondPayload + 18}) {
                check(write(fixture, cut)[1], true);
            }
        }
    }

    @Test public void rejectedPasswordPreservesExistingDestination() throws Exception {
        for (int cipher : CIPHERS) for (boolean solid : new boolean[]{false, true}) {
            Fixture fixture = fixture(cipher, solid);
            File archive = write(fixture, fixture.secondPayload + 18)[1];
            File output = sentinel();
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                    archive, solid ? "prefix.bin" : "payload.bin", output, "wrong".toCharArray());
            assertFalse(result.success);
            assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, result.failure);
            assertArrayEquals(SENTINEL, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void prefixInclusiveZipCryptoHeaderWorksAcrossSolidSplit() throws Exception {
        Fixture fixture = fixture(0, true);
        fixture.bytes[fixture.encryption + 5] = 24;
        check(write(fixture, -1)[0], true);
        for (int cut : new int[]{fixture.encryption + 8, fixture.firstPayload + 15}) {
            check(write(fixture, cut)[1], true);
        }
    }

    @Test public void laterAuthenticatedCiphertextIsCheckedBeforeEarlySolidOutput() throws Exception {
        for (int cipher : new int[]{1, 2, 5, 6}) {
            Fixture fixture = fixture(cipher, true);
            fixture.bytes[fixture.secondPayload + 14] ^= 64;
            File archive = write(fixture, fixture.secondPayload + 18)[0];
            File output = sentinel();
            IOException failure = assertThrows(IOException.class, () ->
                    EggArchiveReader.extractSingleEntry(archive, "prefix.bin", output, PASSWORD));
            assertTrue(failure.getMessage(), failure.getMessage().contains("authentication"));
            assertArrayEquals(SENTINEL, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void validCiphertextWithBadDecodedCrcCannotReplaceDestination() throws Exception {
        for (int cipher : CIPHERS) {
            Fixture fixture = fixture(cipher, true);
            fixture.bytes[fixture.firstPayload - 8] ^= 1; // First block CRC, outside ciphertext.
            File archive = write(fixture, fixture.secondPayload + 18)[1];
            File output = sentinel();
            assertThrows(IOException.class, () ->
                    EggArchiveReader.extractSingleEntry(archive, "prefix.bin", output, PASSWORD));
            assertArrayEquals(SENTINEL, Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void earlyForwardCloseRemovesVerifiedAndAuthenticatedSpools() throws Exception {
        for (int cipher : CIPHERS) {
            Fixture fixture = fixture(cipher, true);
            File archive = write(fixture, fixture.secondPayload + 18)[1];
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, PASSWORD, spool)) {
                assertNotNull(reader);
                assertEquals("prefix.bin", reader.nextEntry().path);
                assertEquals(1, reader.read(new byte[1]));
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void cancellationBeforeDecryptDoesNotReplaceDestination() throws Exception {
        for (int cipher : CIPHERS) {
            Fixture fixture = fixture(cipher, true);
            File archive = write(fixture, -1)[0];
            File output = sentinel();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, () ->
                        EggArchiveReader.extractSingleEntry(archive, "prefix.bin", output, PASSWORD));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertArrayEquals(SENTINEL, Files.readAllBytes(output.toPath()));
        }
    }

    private void check(File archive, boolean solid) throws Exception {
        String[] names = solid ? new String[]{"prefix.bin", "payload.bin"} : new String[]{"payload.bin"};
        int[] sizes = solid ? new int[]{73, 123} : new int[]{196};
        assertEquals(names.length, ArchiveSupport.listEntries(archive, PASSWORD).size());
        File bulk = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, bulk, PASSWORD));
        for (int i = names.length - 1; i >= 0; i--) {
            byte[] expected = repeated(sizes[i]);
            assertArrayEquals(expected, Files.readAllBytes(new File(bulk, names[i]).toPath()));
            File selected = temp.newFile();
            assertTrue(ArchiveSupport.extractSingleEntry(archive, names[i], selected, PASSWORD));
            assertArrayEquals(expected, Files.readAllBytes(selected.toPath()));
        }
        assertEquals(names.length, bulk.list().length);
        if (solid) {
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, PASSWORD, spool)) {
                assertNotNull(reader);
                assertEquals(names[0], reader.nextEntry().path);
                assertTrue(reader.drainCurrentEntry(sizes[0]));
                assertEquals(names[1], reader.nextEntry().path);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[7];
                for (int count; (count = reader.read(buffer)) != -1;) bytes.write(buffer, 0, count);
                assertArrayEquals(repeated(sizes[1]), bytes.toByteArray());
                assertNull(reader.nextEntry());
            }
            assertEquals(0, spool.list().length);
        }
    }

    private File sentinel() throws IOException {
        File output = temp.newFile();
        Files.write(output.toPath(), SENTINEL);
        return output;
    }

    private static final class Fixture {
        byte[] bytes;
        int encryption, firstPayload, secondPayload;
        boolean solid;
    }

    private static Fixture fixture(int method, boolean solid) throws Exception {
        byte[][] packed = {frame(127), frame(63)};
        byte[] joined = new byte[packed[0].length + packed[1].length];
        System.arraycopy(packed[0], 0, joined, 0, packed[0].length);
        System.arraycopy(packed[1], 0, joined, packed[0].length, packed[1].length);
        byte[][] encrypted = encrypt(method, joined);
        Fixture fixture = new Fixture();
        fixture.solid = solid;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(prefix(0x1111, 0, 0, solid, false));
        if (solid) fileHeader(out, 0, 73, "prefix.bin");
        le(out, 0x0a8590e3, 4); le(out, solid ? 1 : 0, 4); le(out, solid ? 123 : 196, 8);
        extra(out, 0x0a8591ac, "payload.bin".getBytes(StandardCharsets.UTF_8), false);
        fixture.encryption = out.size();
        // Producer fields count the seven-byte prefix; legacy fields count payload only.
        extra(out, 0x08d1470f, encrypted[0], method != 0);
        le(out, END, 4);
        int offset = 0;
        for (int i = 0; i < packed.length; i++) {
            le(out, 0x02b50c13, 4); out.write(3); out.write(0);
            le(out, i == 0 ? 130 : 66, 4); le(out, packed[i].length, 4);
            le(out, crc(repeated(i == 0 ? 130 : 66)), 4); le(out, END, 4);
            if (i == 0) fixture.firstPayload = out.size(); else fixture.secondPayload = out.size();
            out.write(encrypted[1], offset, packed[i].length);
            offset += packed[i].length;
        }
        le(out, END, 4);
        fixture.bytes = out.toByteArray();
        return fixture;
    }

    private File[] write(Fixture fixture, int cut) throws IOException {
        File dir = temp.newFolder();
        if (cut < 0) {
            File archive = new File(dir, "fixture.egg");
            Files.write(archive.toPath(), fixture.bytes);
            return new File[]{archive};
        }
        int oldPrefix = fixture.solid ? 25 : 18;
        assertTrue(cut > oldPrefix && cut < fixture.bytes.length);
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        first.write(prefix(0x1111, 0, 0x2222, fixture.solid, true));
        first.write(fixture.bytes, oldPrefix, cut - oldPrefix);
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        second.write(prefix(0x2222, 0x1111, 0, fixture.solid, true));
        second.write(fixture.bytes, cut, fixture.bytes.length - cut);
        File[] volumes = {new File(dir, "fixture.vol1.egg"), new File(dir, "fixture.vol2.egg")};
        Files.write(volumes[0].toPath(), first.toByteArray());
        Files.write(volumes[1].toPath(), second.toByteArray());
        return volumes;
    }

    private static byte[] prefix(int id, int prev, int next, boolean solid, boolean split) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le(out, 0x41474745, 4); le(out, 0x100, 2); le(out, id, 4); le(out, 0, 4);
        if (solid) extra(out, 0x24e5a060, new byte[0], false);
        if (split) {
            ByteArrayOutputStream links = new ByteArrayOutputStream();
            le(links, prev, 4); le(links, next, 4);
            extra(out, 0x24f5a262, links.toByteArray(), false);
        }
        le(out, END, 4);
        return out.toByteArray();
    }

    private static void fileHeader(ByteArrayOutputStream out, int id, int size, String name) throws IOException {
        le(out, 0x0a8590e3, 4); le(out, id, 4); le(out, size, 8);
        extra(out, 0x0a8591ac, name.getBytes(StandardCharsets.UTF_8), false);
        le(out, END, 4);
    }

    private static void extra(ByteArrayOutputStream out, int magic, byte[] payload, boolean inclusive) throws IOException {
        le(out, magic, 4); out.write(0); le(out, payload.length + (inclusive ? 7 : 0), 2); out.write(payload);
    }

    private static byte[] frame(int index) throws IOException {
        // Uniform initial AZO probabilities: literal A, match/history/long, seven-bit slot.
        int bits = (((('A' << 3) | 6) << 7) | index) << 14;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte('1'); out.writeByte(0);
        out.writeInt(index + 3); out.writeInt(16); out.writeInt((index + 3) ^ 16);
        out.writeInt(bits); out.write(new byte[12]); out.write(new byte[12]);
        return bytes.toByteArray();
    }

    private static byte[][] encrypt(int method, byte[] plain) throws Exception {
        ByteArrayOutputStream field = new ByteArrayOutputStream();
        field.write(method);
        if (method == 0) {
            byte[] verifier = new byte[12];
            verifier[11] = (byte) (crc(repeated(196)) >>> 24);
            ZipWriter writer = new ZipWriter();
            field.write(writer.encrypt(verifier));
            le(field, crc(repeated(196)), 4);
            return new byte[][]{field.toByteArray(), writer.encrypt(plain)};
        }
        int keyLength = method == 1 || method == 5 ? 16 : 32;
        byte[] salt = new byte[keyLength / 2];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) (i + 1);
        PBEKeySpec spec = new PBEKeySpec(PASSWORD, salt, 1000, (2 * keyLength + 2) * 8);
        byte[] derived;
        try { derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        byte[] key = Arrays.copyOf(derived, keyLength);
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        EggLeaCipher lea = method >= 5 ? new EggLeaCipher(key) : null;
        byte[] counter = new byte[16], encrypted = plain.clone(), stream = new byte[16];
        if (lea == null) counter[0] = 1;
        for (int offset = 0; offset < encrypted.length; offset += 16) {
            if (lea == null) stream = aes.doFinal(counter); else lea.encryptBlock(counter, stream);
            for (int j = 0; j < 16 && offset + j < encrypted.length; j++) encrypted[offset + j] ^= stream[j];
            if (lea == null) { for (int j = 0; j < 16 && ++counter[j] == 0; j++) { } }
            else { for (int j = 15; j >= 0 && ++counter[j] == 0; j--) { } }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(derived, keyLength, 2 * keyLength), "HmacSHA1"));
        field.write(salt); field.write(derived, 2 * keyLength, 2);
        field.write(Arrays.copyOf(mac.doFinal(encrypted), 10));
        Arrays.fill(derived, (byte) 0); Arrays.fill(key, (byte) 0);
        return new byte[][]{field.toByteArray(), encrypted};
    }

    private static final class ZipWriter {
        int a = 0x12345678, b = 0x23456789, c = 0x34567890;
        ZipWriter() { for (char ch : PASSWORD) update(ch); }
        private void update(int plain) {
            a = step(a, plain); b = (b + (a & 255)) * 134775813 + 1; c = step(c, b >>> 24);
        }
        private static int step(int crc, int value) {
            crc ^= value & 255;
            for (int i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xedb88320);
            return crc;
        }
        byte[] encrypt(byte[] plain) {
            byte[] result = new byte[plain.length];
            for (int i = 0; i < plain.length; i++) {
                int k = (c & 65535) | 2;
                result[i] = (byte) (plain[i] ^ ((k * (k ^ 1)) >>> 8));
                update(plain[i]);
            }
            return result;
        }
    }

    private static byte[] repeated(int size) { byte[] bytes = new byte[size]; Arrays.fill(bytes, (byte) 'A'); return bytes; }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
    private static void le(ByteArrayOutputStream out, long value, int count) { for (int i = 0; i < count; i++) out.write((int) (value >>> (8 * i)) & 255); }
}
