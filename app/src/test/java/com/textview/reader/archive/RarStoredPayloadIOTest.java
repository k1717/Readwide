package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class RarStoredPayloadIOTest {
    @Test
    public void copyPlainEntryToFile_copiesBoundedRange() throws Exception {
        File source = File.createTempFile("rar-stored-source", ".bin");
        File out = File.createTempFile("rar-stored-out", ".bin");
        try {
            write(source, new byte[] {9, 1, 2, 3, 4, 8});
            try (RandomAccessFile raf = new RandomAccessFile(source, "r")) {
                raf.seek(1L);
                RarStoredPayloadIO.copyPlainEntryToFile(raf, 4L, out, null);
            }
            assertArrayEquals(new byte[] {1, 2, 3, 4}, java.nio.file.Files.readAllBytes(out.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    @Test
    public void copySegmentsToFile_concatenatesSegments() throws Exception {
        File first = File.createTempFile("rar-segment-a", ".bin");
        File second = File.createTempFile("rar-segment-b", ".bin");
        File out = File.createTempFile("rar-segments-out", ".bin");
        try {
            write(first, new byte[] {0, 1, 2, 3});
            write(second, new byte[] {4, 5, 6, 7, 8});
            List<RarCryptoStreams.EncryptedSegment> segments = new ArrayList<>();
            segments.add(new RarCryptoStreams.EncryptedSegment(first, 1L, 3L));
            segments.add(new RarCryptoStreams.EncryptedSegment(second, 0L, 4L));
            RarStoredPayloadIO.copySegmentsToFile(segments, out, null);
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7}, java.nio.file.Files.readAllBytes(out.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            first.delete();
            //noinspection ResultOfMethodCallIgnored
            second.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    @Test
    public void verifyCrc_removesOutputOnPlainMismatch() throws Exception {
        File out = File.createTempFile("rar-crc-out", ".bin");
        write(out, new byte[] {1, 2, 3});
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                "file.bin",
                false,
                3L,
                3L,
                0L,
                4,
                0,
                false,
                false,
                false,
                null,
                crc(new byte[] {9, 9, 9}),
                0L);
        try {
            RarStoredPayloadIO.verifyCrc(entry, out);
        } catch (IOException expected) {
            assertFalse(out.exists());
            return;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
        throw new AssertionError("CRC mismatch should throw");
    }

    @Test
    public void verifyCrc_acceptsMatchingCrc() throws Exception {
        byte[] data = new byte[] {1, 2, 3, 4};
        File out = File.createTempFile("rar-crc-ok", ".bin");
        try {
            write(out, data);
            RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry(
                    "file.bin",
                    false,
                    data.length,
                    data.length,
                    0L,
                    4,
                    0,
                    false,
                    false,
                    false,
                    null,
                    crc(data),
                    0L);
            RarStoredPayloadIO.verifyCrc(entry, out);
            assertTrue(out.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static long crc(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, data.length);
        return crc32.getValue() & 0xffffffffL;
    }
}
