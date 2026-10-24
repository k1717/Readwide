package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

/** Synthetic two-folder LZMA+Swap2 containers, not producer/device interoperability evidence. */
public class SevenZLzmaArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void optionalMarkerWorksThroughSelectedBulkAndForwardPaths() throws Exception {
        for (boolean marker : new boolean[]{false, true}) {
            File archive = fixture(marker, false, false);
            File selected = temp.newFile();
            assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "2.bin", selected, null));
            assertArrayEquals(data(2), Files.readAllBytes(selected.toPath()));
            File bulk = temp.newFolder();
            assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, bulk, null, null, null));
            for (int i = 1; i <= 2; i++) assertArrayEquals(data(i), Files.readAllBytes(new File(bulk, i + ".bin").toPath()));
            File spool = temp.newFolder();
            try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
                assertNotNull(reader);
                byte[] firstDictionary = null;
                for (int i = 1; i <= 2; i++) {
                    assertEquals(i + ".bin", reader.nextEntry().path);
                    assertArrayEquals(data(i), read(reader));
                    SevenZDecoderArrayCache cache = cache(reader);
                    byte[] dictionary = cache.getByteArray(32768, true);
                    if (i == 1) firstDictionary = dictionary;
                    else assertSame(firstDictionary, dictionary);
                    cache.putArray(dictionary);
                }
                assertNull(reader.nextEntry());
                assertNotSame(firstDictionary, cache(reader).getByteArray(32768, false));
            }
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void crcFailureStillRollsBackAndRetiresForwardCache() throws Exception {
        File archive = fixture(true, true, false);
        File selected = temp.newFile();
        Files.write(selected.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "2.bin", selected, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(selected.toPath()));
        File bulk = temp.newFolder();
        File second = new File(bulk, "2.bin");
        Files.write(second.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, bulk, null, null, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(second.toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
            assertNotNull(reader.nextEntry());
            assertArrayEquals(data(1), read(reader));
            SevenZDecoderArrayCache cache = cache(reader);
            byte[] dictionary = cache.getByteArray(32768, false);
            cache.putArray(dictionary);
            assertNotNull(reader.nextEntry());
            assertThrows(IOException.class, () -> read(reader));
            assertThrows(IOException.class, reader::nextEntry);
            cache.putArray(dictionary);
            assertNotSame(dictionary, cache.getByteArray(32768, false));
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void truncatedMarkerWithCorrectPackedCrcDoesNotPublishOutput() throws Exception {
        File archive = fixture(true, false, true);
        File output = temp.newFile();
        Files.write(output.toPath(), new byte[]{7});
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "2.bin", output, null));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(output.toPath()));
    }

    @Test public void splitSourceKeepsOptionalMarkerAndFolderReuse() throws Exception {
        byte[] archive = Files.readAllBytes(fixture(true, false, false).toPath());
        File parts = temp.newFolder();
        File first = new File(parts, "book.7z.001"), second = new File(parts, "book.7z.002");
        Files.write(first.toPath(), Arrays.copyOfRange(archive, 0, archive.length / 2));
        Files.write(second.toPath(), Arrays.copyOfRange(archive, archive.length / 2, archive.length));
        try (ArchiveSupport.ForwardArchiveReader reader = SevenZBcj2ArchiveReader.openSpecialForwardReader(second, null, temp.newFolder())) {
            assertNotNull(reader);
            for (int i = 1; i <= 2; i++) {
                assertEquals(i + ".bin", reader.nextEntry().path);
                assertArrayEquals(data(i), read(reader));
            }
            assertNull(reader.nextEntry());
        }
        assertTrue(first.delete());
        assertTrue(second.delete());
    }

    @Test public void cancellationAtIntermediateSourceCheckRestoresCurrentFolder() throws Exception {
        checkIntermediateSourceCancellation(true);
    }

    @Test public void activeTokenAtIntermediateSourceCheckCommitsBothFolders() throws Exception {
        checkIntermediateSourceCancellation(false);
    }

    private void checkIntermediateSourceCancellation(boolean cancel) throws Exception {
        File archive = fixture(false, false, false), directory = temp.newFolder();
        File first = new File(directory, "1.bin"), second = new File(directory, "2.bin");
        byte[] original = {8, 9, 10}; Files.write(first.toPath(), original);
        com.readwide.manager.util.FileOperationProgress progress =
                new com.readwide.manager.util.FileOperationProgress("7z", null);
        int[] boundaryChecks = {0};
        File observedArchive = new File(archive.getPath()) {
            @Override public boolean canRead() {
                boolean readable = super.canRead();
                // SingleSourceStamp retains this File; physical reads use canonical paths.
                // Only the first folder's completion sees its replacement, without 2.bin.
                if (first.length() == 32768 && !second.exists()) {
                    boundaryChecks[0]++;
                    if (cancel) progress.cancel();
                }
                return readable;
            }
        };
        if (cancel) {
            assertThrows(InterruptedIOException.class, () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                    observedArchive, directory, null, progress, null));
            assertArrayEquals(original, Files.readAllBytes(first.toPath()));
            assertFalse(second.exists());
            assertArrayEquals(new String[]{"1.bin"}, directory.list());
        } else {
            assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(observedArchive, directory, null, progress, null));
            assertArrayEquals(data(1), Files.readAllBytes(first.toPath()));
            assertArrayEquals(data(2), Files.readAllBytes(second.toPath()));
            assertEquals(2, directory.list().length);
        }
        assertEquals(1, boundaryChecks[0]);
        assertEquals(cancel, progress.isCancelled());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static SevenZDecoderArrayCache cache(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        java.lang.reflect.Field field = reader.getClass().getDeclaredField("decoderArrays");
        field.setAccessible(true);
        return (SevenZDecoderArrayCache) field.get(reader);
    }
    private static byte[] read(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[997];
        int n;
        while ((n = reader.read(buffer)) != -1) output.write(buffer, 0, n);
        return output.toByteArray();
    }
    private static byte[] data(int index) {
        byte[] data = new byte[32768];
        new java.util.Random(620 + index).nextBytes(data);
        return data;
    }
    private File fixture(boolean marker, boolean badCrc, boolean truncated) throws Exception {
        byte[][] packed = new byte[2][];
        for (int i = 0; i < 2; i++) {
            byte[] swapped = data(i + 1);
            for (int j = 0; j < swapped.length; j += 2) {
                byte value = swapped[j]; swapped[j] = swapped[j + 1]; swapped[j + 1] = value;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            LZMA2Options options = new LZMA2Options(1);
            options.setDictSize(32768);
            try (LZMAOutputStream encoder = new LZMAOutputStream(bytes, options, marker)) { encoder.write(swapped); }
            packed[i] = bytes.toByteArray();
        }
        if (truncated) packed[1] = Arrays.copyOf(packed[1], packed[1].length - 1);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(new byte[]{1, 4, 6, 0, 2, 9}); // Header, MainStreams, PackInfo, pos, count, sizes.
        for (byte[] part : packed) uint(header, part.length);
        header.write(new byte[]{10, 1});
        for (byte[] part : packed) little(header, crc(part), 4);
        header.write(new byte[]{0, 7, 11, 2, 0}); // End PackInfo, UnpackInfo, two inline folders.
        for (int i = 0; i < 2; i++) {
            header.write(new byte[]{2, 0x23, 3, 1, 1, 5, 0x5d, 0, (byte)0x80, 0, 0,
                    3, 2, 3, 2, 1, 0}); // LZMA -> Swap2; bind input 1 to output 0.
        }
        header.write(12);
        for (int i = 0; i < 4; i++) uint(header, 32768);
        header.write(new byte[]{10, 1});
        little(header, crc(data(1)), 4);
        little(header, crc(data(2)) ^ (badCrc ? 1 : 0), 4);
        header.write(new byte[]{0, 0, 5, 2, 17}); // End Unpack/Streams; FilesInfo, count, Name.
        byte[] names = "1.bin\0".concat("2.bin\0").getBytes(StandardCharsets.UTF_16LE);
        uint(header, names.length + 1);
        header.write(0); header.write(names); header.write(new byte[]{0, 0});
        byte[] next = header.toByteArray();
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        little(start, packed[0].length + packed[1].length, 8);
        little(start, next.length, 8); little(start, crc(next), 4);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(new byte[]{'7', 'z', (byte)0xbc, (byte)0xaf, 0x27, 0x1c, 0, 4});
        little(archive, crc(start.toByteArray()), 4); archive.write(start.toByteArray());
        for (byte[] part : packed) archive.write(part);
        archive.write(next);
        File file = temp.newFile("lzma-" + marker + "-" + badCrc + "-" + truncated + ".7z");
        Files.write(file.toPath(), archive.toByteArray());
        return file;
    }
    private static void uint(ByteArrayOutputStream out, long value) {
        out.write(0xff); little(out, value, 8); // Legal full-width 7z UInt64 representation.
    }
    private static void little(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int)(value >>> (8 * i)) & 255);
    }
    private static long crc(byte[] value) { CRC32 crc = new CRC32(); crc.update(value); return crc.getValue(); }
}
