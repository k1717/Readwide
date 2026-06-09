package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Rar4HeaderEncryptedArchiveRewriterTest {
    private static final int RAR4_MAIN_PASSWORD = 0x0080;
    private static final int RAR4_FILE_PASSWORD = 0x0004;
    private static final int RAR4_FILE_SALT = 0x0400;
    private static final int RAR4_LONG_BLOCK = 0x8000;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void buildDecryptedHeaderCopy_revealsHeadersAndKeepsEncryptedPayload() throws Exception {
        byte[] payload = "header encrypted stored payload".getBytes(StandardCharsets.UTF_8);
        char[] password = "hello".toCharArray();
        File archive = buildRar4HeaderEncryptedStoredArchive("private/page001.txt", payload, password);

        File rewritten = Rar4HeaderEncryptedArchiveRewriter.buildDecryptedHeaderCopy(archive, password, null);
        try {
            byte[] bytes = Files.readAllBytes(rewritten.toPath());
            int offset = 7;
            assertEquals(0x73, bytes[offset + 2] & 0xff);
            int mainFlags = uint16(bytes, offset + 3);
            assertFalse((mainFlags & RAR4_MAIN_PASSWORD) != 0);
            offset += uint16(bytes, offset + 5);

            assertEquals(0x74, bytes[offset + 2] & 0xff);
            int fileFlags = uint16(bytes, offset + 3);
            int fileHeaderSize = uint16(bytes, offset + 5);
            int packedSize = (int) uint32(bytes, offset + 7);
            assertTrue((fileFlags & RAR4_FILE_PASSWORD) != 0);
            assertTrue((fileFlags & RAR4_FILE_SALT) != 0);
            assertEquals(aesPaddedSize(payload.length), packedSize);

            byte[] encryptedPayload = Arrays.copyOfRange(bytes, offset + fileHeaderSize, offset + fileHeaderSize + packedSize);
            assertFalse(Arrays.equals(payload, encryptedPayload));
            byte[] decryptedPayload = decryptRar3(password, headerSalt(), encryptedPayload);
            assertArrayEquals(payload, Arrays.copyOf(decryptedPayload, payload.length));

            offset += fileHeaderSize + packedSize;
            assertEquals(0x7b, bytes[offset + 2] & 0xff);
        } finally {
            rewritten.delete();
        }
    }

    @Test(expected = ArchiveSupport.PasswordRequiredException.class)
    public void buildDecryptedHeaderCopy_wrongPasswordRequestsPassword() throws Exception {
        File archive = buildRar4HeaderEncryptedStoredArchive(
                "private/page001.txt",
                "payload".getBytes(StandardCharsets.UTF_8),
                "correct".toCharArray());
        Rar4HeaderEncryptedArchiveRewriter.buildDecryptedHeaderCopy(archive, "wrong".toCharArray(), null);
    }

    private File buildRar4HeaderEncryptedStoredArchive(String entryName, byte[] payload, char[] password) throws Exception {
        byte[] salt = headerSalt();
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedPayload = encryptRar3(password, salt, Arrays.copyOf(payload, aesPaddedSize(payload.length)));
        byte[] fileBody = bytes(
                uint32(encryptedPayload.length),
                uint32(payload.length),
                new byte[] {1},
                uint32(crc32(payload)),
                uint32(0),
                new byte[] {29},
                new byte[] {0x30},
                uint16(rawName.length),
                uint32(0),
                rawName,
                salt);

        byte[] encryptedFileHeader = encryptHeaderBlock(password, salt,
                rar4Header(0x74, RAR4_LONG_BLOCK | RAR4_FILE_PASSWORD | RAR4_FILE_SALT, fileBody));
        byte[] encryptedEndHeader = encryptHeaderBlock(password, salt, rar4Header(0x7b, 0, new byte[0]));

        File archive = tempFolder.newFile("header-encrypted.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, RAR4_MAIN_PASSWORD, new byte[] {0, 0, 0, 0, 0, 0}));
            out.write(salt);
            out.write(encryptedFileHeader);
            out.write(encryptedPayload);
            out.write(encryptedEndHeader);
        }
        return archive;
    }

    private static byte[] encryptHeaderBlock(char[] password, byte[] salt, byte[] header) throws Exception {
        return encryptRar3(password, salt, Arrays.copyOf(header, aesPaddedSize(header.length)));
    }

    private static byte[] encryptRar3(char[] password, byte[] salt, byte[] data) throws Exception {
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(parameters.key, "AES"),
                new IvParameterSpec(parameters.iv));
        return cipher.doFinal(data);
    }

    private static byte[] decryptRar3(char[] password, byte[] salt, byte[] data) throws Exception {
        return Rar3Crypto.decryptAesCbcNoPadding(data, password, salt);
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) throws Exception {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        tail.write(type & 0xff);
        tail.write(uint16(flags));
        tail.write(uint16(7 + body.length));
        tail.write(body);
        CRC32 crc32 = new CRC32();
        byte[] tailBytes = tail.toByteArray();
        crc32.update(tailBytes);
        return bytes(uint16((int) crc32.getValue() & 0xffff), tailBytes);
    }

    private static byte[] headerSalt() {
        return new byte[] {0x37, (byte) 0x94, 0x75, (byte) 0xb0, 0x6e, 0x30, 0x39, 0x55};
    }

    private static int aesPaddedSize(int size) {
        return ((size + 15) / 16) * 16;
    }

    private static long crc32(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }

    private static byte[] uint16(int value) {
        return new byte[] {(byte) (value & 0xff), (byte) ((value >>> 8) & 0xff)};
    }

    private static byte[] uint32(long value) {
        return new byte[] {
                (byte) (value & 0xff),
                (byte) ((value >>> 8) & 0xff),
                (byte) ((value >>> 16) & 0xff),
                (byte) ((value >>> 24) & 0xff)
        };
    }

    private static byte[] bytes(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }

    private static int uint16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long uint32(byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24);
    }
}
