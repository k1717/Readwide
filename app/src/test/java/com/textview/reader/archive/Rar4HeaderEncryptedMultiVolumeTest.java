package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

public class Rar4HeaderEncryptedMultiVolumeTest {
    private static final int RAR4_MAIN_PASSWORD = 0x0080;
    private static final int RAR4_LONG_BLOCK = 0x8000;
    private static final int RAR4_FILE_SPLIT_BEFORE = 0x0001;
    private static final int RAR4_FILE_SPLIT_AFTER = 0x0002;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void extractSingleEntry_rar4HeaderEncryptedSplitStored_decryptsAllVolumes() throws Exception {
        char[] password = "chain-pass".toCharArray();
        byte[] payload = ("header encrypted multi-volume stored payload - "
                + "split across two old-style RAR volumes")
                .getBytes(StandardCharsets.UTF_8);
        File archive = buildSplitChain("multi", "docs/private.txt", payload, password);
        File out = tempFolder.newFile("out.txt");

        assertTrue(RarArchiveReader.extractSingleEntry(archive, "docs/private.txt", out, password));

        assertArrayEquals(payload, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void extractArchive_rar4HeaderEncryptedSplitStored_decryptsAllVolumes() throws Exception {
        char[] password = "chain-pass".toCharArray();
        byte[] payload = "archive-wide hp split stored payload".getBytes(StandardCharsets.UTF_8);
        File archive = buildSplitChain("book", "docs/private.txt", payload, password);
        File target = tempFolder.newFolder("target");

        assertTrue(RarArchiveReader.extractArchiveIntoDirectory(archive, target, password, null, null));

        assertArrayEquals(payload, Files.readAllBytes(new File(target, "docs/private.txt").toPath()));
    }

    @Test
    public void extractSingleEntry_rar4HeaderEncryptedSplitStored_wrongPasswordRejected() throws Exception {
        File archive = buildSplitChain(
                "wrong-pass",
                "docs/private.txt",
                "payload".getBytes(StandardCharsets.UTF_8),
                "correct".toCharArray());

        try {
            RarArchiveReader.extractSingleEntry(
                    archive,
                    "docs/private.txt",
                    tempFolder.newFile("wrong.txt"),
                    "wrong".toCharArray());
            fail("wrong password accepted");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
            // Expected.
        }
    }

    private File buildSplitChain(String prefix,
                                 String entryName,
                                 byte[] payload,
                                 char[] password) throws Exception {
        int cut = Math.max(1, payload.length / 2);
        byte[] firstPart = Arrays.copyOfRange(payload, 0, cut);
        byte[] secondPart = Arrays.copyOfRange(payload, cut, payload.length);
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        long crc = crc32(payload);
        File first = new File(tempFolder.getRoot(), prefix + ".rar");
        File second = new File(tempFolder.getRoot(), prefix + ".r00");
        writeVolume(first, password, salt((byte) 0x11), name, firstPart, payload.length, crc, RAR4_FILE_SPLIT_AFTER);
        writeVolume(second, password, salt((byte) 0x22), name, secondPart, payload.length, crc, RAR4_FILE_SPLIT_BEFORE);
        return first;
    }

    private static void writeVolume(File file,
                                    char[] password,
                                    byte[] salt,
                                    byte[] name,
                                    byte[] data,
                                    int unpackedSize,
                                    long crc,
                                    int splitFlags) throws Exception {
        byte[] fileBody = bytes(
                uint32(data.length),
                uint32(unpackedSize),
                new byte[] {1},
                uint32(crc),
                uint32(0),
                new byte[] {29},
                new byte[] {0x30},
                uint16(name.length),
                uint32(0),
                name);
        byte[] encryptedFileHeader = encryptHeader(
                password,
                salt,
                rar4Header(0x74, RAR4_LONG_BLOCK | splitFlags, fileBody));
        byte[] encryptedEndHeader = encryptHeader(password, salt, rar4Header(0x7b, 0, new byte[0]));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, RAR4_MAIN_PASSWORD, new byte[] {0, 0, 0, 0, 0, 0}));
            out.write(salt);
            out.write(encryptedFileHeader);
            out.write(data);
            out.write(encryptedEndHeader);
        }
    }

    private static byte[] encryptHeader(char[] password, byte[] salt, byte[] header) throws Exception {
        return encrypt(password, salt, Arrays.copyOf(header, padded(header.length)));
    }

    private static byte[] encrypt(char[] password, byte[] salt, byte[] data) throws Exception {
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(parameters.key, "AES"),
                new IvParameterSpec(parameters.iv));
        return cipher.doFinal(data);
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) throws Exception {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        tail.write(type);
        tail.write(uint16(flags));
        tail.write(uint16(7 + body.length));
        tail.write(body);
        byte[] tailBytes = tail.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(tailBytes);
        return bytes(uint16((int) crc.getValue() & 0xffff), tailBytes);
    }

    private static byte[] salt(byte tag) {
        return new byte[] {tag, (byte) 0x94, 0x75, (byte) 0xb0, 0x6e, 0x30, 0x39, 0x55};
    }

    private static int padded(int value) {
        return ((value + 15) / 16) * 16;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static byte[] uint16(int value) {
        return new byte[] {(byte) value, (byte) (value >>> 8)};
    }

    private static byte[] uint32(long value) {
        return new byte[] {
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    private static byte[] bytes(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }
}
