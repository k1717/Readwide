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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Rar4SplitPayloadRewriterTest {
    private static final int RAR4_FILE_SPLIT_AFTER = 0x0002;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void buildSingleEntryCopy_compressedSplitClearsSplitFlagAndConcatenatesPayloads() throws Exception {
        byte[] compressedPayload = new byte[191];
        for (int i = 0; i < compressedPayload.length; i++) compressedPayload[i] = (byte) (i * 11 + 5);

        File first = tempFolder.newFile("sample.rar");
        File second = tempFolder.newFile("sample.r00");
        byte[] firstPart = Arrays.copyOfRange(compressedPayload, 0, 73);
        byte[] secondPart = Arrays.copyOfRange(compressedPayload, 73, compressedPayload.length);
        long dataOffset;
        try (FileOutputStream out = new FileOutputStream(first)) {
            out.write(new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[] {0, 0, 0, 0, 0, 0}));
            byte[] body = fileHeader("file.bin", compressedPayload.length, 4096, 0x12345678L, 0x33);
            out.write(rar4Header(0x74, RAR4_FILE_SPLIT_AFTER, body));
            dataOffset = first.length();
            out.write(firstPart);
        }
        try (FileOutputStream out = new FileOutputStream(second)) {
            out.write(secondPart);
        }

        List<RarCryptoStreams.EncryptedSegment> segments = new ArrayList<>();
        segments.add(new RarCryptoStreams.EncryptedSegment(first, dataOffset, firstPart.length));
        segments.add(new RarCryptoStreams.EncryptedSegment(second, 0L, secondPart.length));

        File rewritten = Rar4SplitPayloadRewriter.buildSingleEntryCopy(first, dataOffset, segments, null);
        try {
            byte[] bytes = Files.readAllBytes(rewritten.toPath());
            int offset = 7;
            offset += uint16(bytes, offset + 5); // MAIN header.
            assertEquals(0x74, bytes[offset + 2] & 0xff);
            int flags = uint16(bytes, offset + 3);
            int headerSize = uint16(bytes, offset + 5);
            int packedSize = (int) uint32(bytes, offset + 7);
            assertFalse((flags & RAR4_FILE_SPLIT_AFTER) != 0);
            assertEquals(compressedPayload.length, packedSize);
            assertArrayEquals(compressedPayload,
                    Arrays.copyOfRange(bytes, offset + headerSize, offset + headerSize + packedSize));
        } finally {
            rewritten.delete();
        }
    }

    private static byte[] fileHeader(String entryName,
                                     long packedSize,
                                     long unpackedSize,
                                     long dataCrc,
                                     int method) throws Exception {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        return bytes(
                uint32(packedSize),
                uint32(unpackedSize),
                new byte[] {1},
                uint32(dataCrc),
                uint32(0),
                new byte[] {29},
                new byte[] {(byte) method},
                uint16(rawName.length),
                uint32(0x20),
                rawName);
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) throws Exception {
        return bytes(
                uint16(0),
                new byte[] {(byte) type},
                uint16(flags),
                uint16(7 + body.length),
                body);
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
