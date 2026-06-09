package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

public class Rar4EncryptedPayloadRewriterTest {
    private static final int RAR4_FILE_PASSWORD = 0x0004;
    private static final int RAR4_FILE_SALT = 0x0400;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void buildDecryptedCopy_storedPayloadClearsEncryptionFlagsAndTrimsPadding() throws Exception {
        byte[] payload = "encrypted stored rewrite payload".getBytes(StandardCharsets.UTF_8);
        File archive = buildRar4EncryptedStoredArchive("private/page001.txt", payload, "pw".toCharArray());

        File rewritten = Rar4EncryptedPayloadRewriter.buildDecryptedCopy(archive, "pw".toCharArray(), null);
        try {
            byte[] bytes = Files.readAllBytes(rewritten.toPath());
            int offset = 7;
            offset += uint16(bytes, offset + 5); // MAIN header.
            assertEquals(0x74, bytes[offset + 2] & 0xff);
            int flags = uint16(bytes, offset + 3);
            int headerSize = uint16(bytes, offset + 5);
            int packedSize = (int) uint32(bytes, offset + 7);
            assertFalse((flags & RAR4_FILE_PASSWORD) != 0);
            assertFalse((flags & RAR4_FILE_SALT) != 0);
            assertEquals(payload.length, packedSize);
            assertArrayEquals(payload, Arrays.copyOfRange(bytes, offset + headerSize, offset + headerSize + packedSize));
        } finally {
            rewritten.delete();
        }
    }

    private File buildRar4EncryptedStoredArchive(String entryName, byte[] payload, char[] password) throws Exception {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[] {0x37, (byte) 0x94, 0x75, (byte) 0xb0, 0x6e, 0x30, 0x39, 0x55};
        byte[] padded = Arrays.copyOf(payload, ((payload.length + 15) / 16) * 16);
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(parameters.key, "AES"),
                new IvParameterSpec(parameters.iv));
        byte[] encrypted = cipher.doFinal(padded);

        File archive = tempFolder.newFile("sample-rar4-encrypted-rewrite.rar");
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));
            byte[] body = bytes(
                    uint32(encrypted.length),
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
            out.write(rar4Header(0x74, 0x8000 | RAR4_FILE_PASSWORD | RAR4_FILE_SALT, body));
            out.write(encrypted);
            out.write(rar4Header(0x7b, 0, new byte[0]));
        }
        return archive;
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) throws Exception {
        return bytes(
                uint16(0),
                new byte[] {(byte) type},
                uint16(flags),
                uint16(7 + body.length),
                body);
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
