package com.readwide.manager.archive;

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

    @Test public void bothRewritersPromoteCombinedPackedSizePastUnsigned32Bits() throws Exception {
        byte[] shortHeader = fileHeader("name.bin", 3, 4096, 0x12345678L, 0x33);
        for (boolean encrypted : new boolean[]{false, true}) {
            Object prepared = prepareHeader(encrypted, encrypted ? 0x0406 : 2,
                    encrypted ? bytes(shortHeader, new byte[8]) : shortHeader, 0x1_0000_0010L);
            byte[] header = preparedBytes(prepared);
            assertEquals(0x100, preparedFlags(prepared));
            assertEquals(16, uint32(header, 0));
            assertEquals(1, uint32(header, 25));
            assertEquals(4096, uint32(header, 4));
            assertEquals(0, uint32(header, 29));
            assertArrayEquals(Arrays.copyOfRange(shortHeader, 25, shortHeader.length),
                    Arrays.copyOfRange(header, 33, header.length));
        }
    }

    @Test public void existingLargeHeadersRetainHighUnpackedSizeAndTrailingRecords() throws Exception {
        byte[] header = Rar4SplitPayloadRewriter.addLargeSizeFields(
                fileHeader("name.bin", 3, 4096, 0x12345678L, 0x33));
        header[29] = 2;
        for (boolean encrypted : new boolean[]{false, true}) {
            Object prepared = prepareHeader(encrypted, encrypted ? 0x0506 : 0x102,
                    encrypted ? bytes(header, new byte[8]) : header, 0x3_0000_0020L);
            byte[] rewritten = preparedBytes(prepared);
            assertEquals(32, uint32(rewritten, 0));
            assertEquals(3, uint32(rewritten, 25));
            assertEquals(2, uint32(rewritten, 29));
            assertArrayEquals(Arrays.copyOfRange(header, 33, header.length),
                    Arrays.copyOfRange(rewritten, 33, rewritten.length));
        }
    }

    @Test public void unsigned32BitPackedSizeDoesNotGrowShortHeader() throws Exception {
        byte[] header = fileHeader("name.bin", 3, 4096, 0x12345678L, 0x33);
        Object prepared = prepareHeader(false, 2, header, 0xffff_ffffL);
        assertEquals(0, preparedFlags(prepared));
        assertEquals(header.length, preparedBytes(prepared).length);
        assertEquals(0xffff_ffffL, uint32(preparedBytes(prepared), 0));
    }

    @Test public void largeHeaderInsertionHonorsUnsigned16BitHeaderSize() throws Exception {
        assertEquals(0xffff - 7, Rar4SplitPayloadRewriter.addLargeSizeFields(new byte[0xffff - 15]).length);
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> Rar4SplitPayloadRewriter.addLargeSizeFields(new byte[0xffff - 14]));
    }

    private static Object prepareHeader(boolean encrypted, int flags, byte[] header, long packed) throws Exception {
        Class<?> owner = encrypted ? Rar4EncryptedSplitPayloadRewriter.class : Rar4SplitPayloadRewriter.class;
        java.lang.reflect.Method method = owner.getDeclaredMethod("prepareSplitFileBlock", int.class, byte[].class, long.class);
        method.setAccessible(true);
        return method.invoke(null, flags, header, packed);
    }

    private static byte[] preparedBytes(Object prepared) throws Exception {
        java.lang.reflect.Field field = prepared.getClass().getDeclaredField("headerData");
        field.setAccessible(true);
        return (byte[]) field.get(prepared);
    }

    private static int preparedFlags(Object prepared) throws Exception {
        java.lang.reflect.Field field = prepared.getClass().getDeclaredField("flags");
        field.setAccessible(true);
        return field.getInt(prepared);
    }

    @Test public void plainRewriteChecksIntermediatePackedCrc() throws Exception {
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> checkedCopy(packedCrc() ^ 1, 0, 2, false));
        assertEquals("RAR intermediate packed-volume checksum mismatch", failure.getMessage());
    }

    @Test public void plainRewriteValidatesEmptySegmentPhysicalBounds() throws Exception {
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> checkedCopy(packedCrc(), 99, 0, false));
        org.junit.Assert.assertTrue(failure.getMessage().contains("extends past its volume"));
    }

    @Test public void plainRewriteHonorsInterruptionWithoutProgressCallback() throws Exception {
        try {
            java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> checkedCopy(packedCrc(), 0, 2, true));
            org.junit.Assert.assertTrue(failure.getMessage().contains("cancelled"));
            org.junit.Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void plainRewriteStillConcatenatesValidCheckedSegments() throws Exception {
        File rewritten = checkedCopy(packedCrc(), 0, 2, false);
        try {
            byte[] bytes = Files.readAllBytes(rewritten.toPath());
            int header = 7 + uint16(bytes, 12);
            int payload = header + uint16(bytes, header + 5);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Arrays.copyOfRange(bytes, payload, payload + 5));
        } finally { rewritten.delete(); }
    }

    private File checkedCopy(long checksum, long tailOffset, long tailSize, boolean interrupt) throws Exception {
        File first = tempFolder.newFile();
        File last = tempFolder.newFile();
        long dataOffset;
        try (FileOutputStream out = new FileOutputStream(first)) {
            out.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00});
            out.write(rar4Header(0x73, 0, new byte[6]));
            out.write(rar4Header(0x74, RAR4_FILE_SPLIT_AFTER,
                    fileHeader("file.bin", 3, 16, checksum, 0x33)));
            dataOffset = first.length();
            out.write(new byte[]{1, 2, 3});
        }
        Files.write(last.toPath(), new byte[]{4, 5});
        RarArchiveReader.RarEntry check = new RarArchiveReader.RarEntry("file.bin", false,
                16, 3, dataOffset, 4, 0x33, false, false, true, null, checksum, 0);
        check.sourceArchive = first;
        List<RarCryptoStreams.EncryptedSegment> segments = Arrays.asList(
                new RarCryptoStreams.EncryptedSegment(first, dataOffset, 3, check),
                new RarCryptoStreams.EncryptedSegment(last, tailOffset, tailSize));
        if (interrupt) Thread.currentThread().interrupt();
        return Rar4SplitPayloadRewriter.buildSingleEntryCopy(first, dataOffset, segments, 0x12345678L, null);
    }

    private static long packedCrc() {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(new byte[]{1, 2, 3});
        return crc.getValue();
    }

    @Test public void invalidFinalChecksumIsRejectedBeforeOpeningSource() throws Exception {
        File absent = new File(tempFolder.getRoot(), "absent.rar");
        for (long checksum : new long[]{-1, 0x1_0000_0000L}) {
            java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                    () -> Rar4SplitPayloadRewriter.buildSingleEntryCopy(absent, 0,
                            java.util.Collections.emptyList(), checksum, null));
            assertEquals("Invalid RAR4 final payload checksum", failure.getMessage());
        }
    }

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

        long finalCrc = 0xfedcba98L; // Deliberately differs from the first part's header CRC.
        File rewritten = Rar4SplitPayloadRewriter.buildSingleEntryCopy(first, dataOffset, segments, finalCrc, null);
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
            assertEquals(finalCrc, uint32(bytes, offset + 16));
            java.util.zip.CRC32 headerCrc = new java.util.zip.CRC32();
            headerCrc.update(bytes, offset + 2, headerSize - 2);
            assertEquals(headerCrc.getValue() & 0xffffL, uint16(bytes, offset));
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
