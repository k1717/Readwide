package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Rar4HeaderSaltFramingTest {
    private static final byte[] SIGNATURE = {0x52, 0x61, 0x72, 0x21, 0x1a, 7, 0};
    private static final char[] PASSWORD = "header-salt-fixture".toCharArray();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void differentFileAndEndSaltsPreservePayloadsAndFlags() throws Exception {
        assertSaltSequence(new byte[][]{salt(1), salt(2), salt(3)});
    }

    @Test public void repeatedSaltAndReturnToEarlierSaltRemainReadable() throws Exception {
        assertSaltSequence(new byte[][]{salt(1), salt(1), salt(1)});
        assertSaltSequence(new byte[][]{salt(1), salt(2), salt(1)});
    }

    @Test public void splitSolidMetadataSurvivesHeaderRewrite() throws Exception {
        int flags = 0x8013;
        byte[] payload = {4, 5, 6};
        byte[] fileHeader = fileHeader("part.bin", flags, payload.length, 12, 0x12345678L, new byte[0]);
        byte[] end = header(0x7b, 1, new byte[0]);
        File source = source(join(main(0x189), encryptedHeader(salt(1), fileHeader), payload,
                encryptedHeader(salt(2), end)));
        File output = rewrite(source, PASSWORD);
        assertArrayEquals(join(main(0x109), fileHeader, payload, end), Files.readAllBytes(output.toPath()));
        List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntries(output, null);
        assertEquals(1, entries.size());
        RarArchiveReader.RarEntry entry = entries.get(0);
        assertTrue(entry.solid);
        assertTrue(entry.splitBefore);
        assertTrue(entry.splitAfter);
        assertEquals(0x12345678L, entry.dataCrc);
        assertEquals(3, entry.packedSize);
        assertEquals(12, entry.unpackedSize);
    }

    @Test public void firstHeaderRequiresCompleteSaltAndCipherBlock() throws Exception {
        for (int length : new int[]{0, 1, 7, 8, 15, 23}) {
            reject(join(main(0x80), new byte[length]), PASSWORD);
        }
    }

    @Test public void truncatedNextSaltOrHeaderRemovesAlreadyWrittenPrefix() throws Exception {
        byte[] payload = {42};
        byte[] first = encryptedHeader(salt(1), fileHeader("a", 0x8000, 1, 1, 0, new byte[0]));
        for (int length : new int[]{1, 7, 8, 23}) {
            reject(join(main(0x80), first, payload, new byte[length]), PASSWORD);
        }
    }

    @Test public void missingEndSaltIsNotAcceptedAsLegacyLayout() throws Exception {
        byte[] payload = {42};
        byte[] first = encryptedHeader(salt(1), fileHeader("a", 0x8000, 1, 1, 0, new byte[0]));
        byte[] end = encryptedHeader(salt(1), header(0x7b, 0, new byte[0]));
        reject(join(main(0x80), first, payload, Arrays.copyOfRange(end, 8, end.length)), PASSWORD);
    }

    @Test public void incorrectPasswordAndCorruptLaterHeaderRetainPasswordClassification() throws Exception {
        byte[] payload = {42};
        byte[] first = encryptedHeader(salt(1), fileHeader("a", 0x8000, 1, 1, 0, new byte[0]));
        byte[] end = header(0x7b, 0, new byte[0]);
        reject(join(main(0x80), first, payload, encryptedHeader(salt(2), end)),
                "incorrect".toCharArray());
        end[0] ^= 1;
        reject(join(main(0x80), first, payload, encryptedHeader(salt(2), end)), PASSWORD);
    }

    @Test public void completeHeaderWithoutEndMarkerKeepsExistingRecoveryBehavior() throws Exception {
        byte[] payload = {42};
        byte[] file = fileHeader("a", 0x8000, 1, 1, 0, new byte[0]);
        File source = source(join(main(0x80), encryptedHeader(salt(1), file), payload));
        assertArrayEquals(join(main(0), file, payload), Files.readAllBytes(rewrite(source, PASSWORD).toPath()));
    }

    private void assertSaltSequence(byte[][] salts) throws Exception {
        byte[] firstPayload = new byte[16];
        Arrays.fill(firstPayload, (byte) 0x62);
        byte[] secondPayload = {7, 8, 9};
        byte[] first = fileHeader("first.bin", 0x8404, firstPayload.length, 3, 0x12345678L, salt(9));
        byte[] second = fileHeader("second.bin", 0x8010, secondPayload.length, 4, 0x23456789L, new byte[0]);
        byte[] end = header(0x7b, 0, new byte[0]);
        File source = source(join(main(0x88), encryptedHeader(salts[0], first), firstPayload,
                encryptedHeader(salts[1], second), secondPayload, encryptedHeader(salts[2], end)));
        assertArrayEquals(join(main(8), first, firstPayload, second, secondPayload, end),
                Files.readAllBytes(rewrite(source, PASSWORD).toPath()));
    }

    private void reject(byte[] archive, char[] password) throws Exception {
        File source = source(archive);
        File output = new File(temporary.newFolder(), "rejected.rar");
        assertThrows(ArchiveSupport.PasswordRequiredException.class,
                () -> Rar4HeaderEncryptedArchiveRewriter.writeDecryptedHeaderCopy(source, output, password, null));
        assertFalse(output.exists());
        assertTrue(source.isFile());
    }

    private File source(byte[] bytes) throws IOException {
        File source = temporary.newFile();
        Files.write(source.toPath(), bytes);
        return source;
    }

    private File rewrite(File source, char[] password) throws IOException {
        File output = temporary.newFile();
        Rar4HeaderEncryptedArchiveRewriter.writeDecryptedHeaderCopy(source, output, password, null);
        return output;
    }

    private static byte[] main(int flags) throws IOException {
        return join(SIGNATURE, header(0x73, flags, new byte[6]));
    }

    private static byte[] fileHeader(String name, int flags, int packed, int unpacked,
            long crc, byte[] payloadSalt) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        little(body, packed, 4);
        little(body, unpacked, 4);
        body.write(2);
        little(body, crc, 4);
        little(body, 0, 4);
        body.write(29);
        body.write(0x33);
        little(body, nameBytes.length, 2);
        little(body, 0, 4);
        body.write(nameBytes);
        body.write(payloadSalt);
        return header(0x74, flags, body.toByteArray());
    }

    private static byte[] header(int type, int flags, byte[] body) throws IOException {
        ByteArrayOutputStream checked = new ByteArrayOutputStream();
        checked.write(type);
        little(checked, flags, 2);
        little(checked, 7 + body.length, 2);
        checked.write(body);
        CRC32 crc = new CRC32();
        crc.update(checked.toByteArray());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        little(result, crc.getValue(), 2);
        result.write(checked.toByteArray());
        return result.toByteArray();
    }

    private static byte[] encryptedHeader(byte[] salt, byte[] header) throws Exception {
        Rar3Crypto.Parameters keys = Rar3Crypto.deriveParameters(PASSWORD, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.key, "AES"), new IvParameterSpec(keys.iv));
            return join(salt, cipher.doFinal(Arrays.copyOf(header, (header.length + 15) & ~15)));
        } finally {
            Arrays.fill(keys.key, (byte) 0);
            Arrays.fill(keys.iv, (byte) 0);
        }
    }

    private static byte[] salt(int value) {
        return new byte[]{(byte) value, 2, 3, 4, 5, 6, 7, 8};
    }

    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }

    private static void little(ByteArrayOutputStream out, long value, int width) {
        for (int i = 0; i < width; i++) out.write((int) (value >>> (8 * i)) & 255);
    }
}
