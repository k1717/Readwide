package com.readwide.manager.archive;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Synthetic encoded-header routing checks; producer/device execution is separate. */
@RunWith(AndroidJUnit4.class)
public class SevenZEncodedHeaderFolderInstrumentedTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Before public void requireNative() {
        assertTrue(LibarchiveNativeBridge.backendStatus(), LibarchiveNativeBridge.isAvailable());
    }

    @Test public void nativeRejectsZeroAndMultipleHeaderFoldersWithoutReplacingOutput() throws Exception {
        for (byte[] bytes : new byte[][]{wrap(new byte[0], new byte[]{23, 0}), encoded(2)}) {
            File archive = writeArchive(bytes);
            assertThrows(IOException.class, () -> LibarchiveNativeBridge.listEntries(archive.getPath(), null));
            File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{7, 8});
            assertThrows(IOException.class, () -> LibarchiveNativeBridge.extractEntry(
                    archive.getPath(), "data.bin", output.getPath(), null, null));
            assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(output.toPath()));
            assertThrows(IOException.class, () -> {
                LibarchiveNativeBridge.ForwardStream reader = LibarchiveNativeBridge.openForwardStream(
                        new String[]{archive.getPath()}, null);
                try {
                    reader.nextEntry();
                } finally { reader.close(); }
            });
        }
    }

    @Test public void publicRoutesCannotAcceptASecondHeaderFolderThroughAnotherDecoder() throws Exception {
        File archive = writeArchive(encoded(2));
        assertThrows(IOException.class, () -> ArchiveSupport.listEntries(archive, null));
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{7, 8});
        assertFalse(ArchiveSupport.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(output.toPath()));
        File root = temporary.newFolder();
        File keep = new File(root, "keep"); Files.write(keep.toPath(), new byte[]{9});
        assertFalse(ArchiveSupport.extractArchive(archive, root, true, null));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(keep.toPath()));
        assertFalse(new File(root, "data.bin").exists());
        assertThrows(IOException.class, () -> {
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
                assertNotNull(reader); reader.nextEntry();
            }
        });
    }

    @Test public void nativeAndPublicSplitRoutesKeepTheSameHeaderFolderBoundary() throws Exception {
        byte[] complete = encoded(2);
        File folder = temporary.newFolder();
        File first = new File(folder, "header.7z.001"), last = new File(folder, "header.7z.002");
        int split = complete.length - 9;
        Files.write(first.toPath(), Arrays.copyOf(complete, split));
        Files.write(last.toPath(), Arrays.copyOfRange(complete, split, complete.length));
        String[] paths = {first.getPath(), last.getPath()};
        assertThrows(IOException.class, () -> LibarchiveNativeBridge.listEntries(paths, null));
        assertThrows(IOException.class, () -> ArchiveSupport.listEntries(last, null));
        File output = temporary.newFile(); Files.write(output.toPath(), new byte[]{7});
        assertThrows(IOException.class, () -> LibarchiveNativeBridge.extractEntry(
                paths, "data.bin", output.getPath(), null, null));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(output.toPath()));
        assertThrows(IOException.class, () -> {
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(first, null)) {
                assertNotNull(reader); reader.nextEntry();
            }
        });
    }

    @Test public void oneCopyEncodedFolderStillListsAndExtractsThroughNativeAndPublicRoutes() throws Exception {
        File archive = writeArchive(encoded(1));
        assertEquals("data.bin", RarLibarchiveFallback.parseListingRows(
                LibarchiveNativeBridge.listEntries(archive.getPath(), null)).get(0).path);
        assertEquals("data.bin", ArchiveSupport.listEntries(archive, null).get(0).path);
        File nativeOutput = temporary.newFile();
        assertTrue(LibarchiveNativeBridge.extractEntry(
                archive.getPath(), "data.bin", nativeOutput.getPath(), null, null));
        assertEquals(0L, nativeOutput.length());
        File output = temporary.newFile();
        assertTrue(ArchiveSupport.extractSingleEntry(archive, "data.bin", output, null));
        assertEquals(0L, output.length());
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            assertNotNull(reader); assertEquals("data.bin", reader.nextEntry().path);
            assertEquals(-1, reader.read(new byte[8]));
            assertNull(reader.nextEntry());
        }
    }

    private File writeArchive(byte[] bytes) throws IOException {
        File archive = new File(temporary.newFolder(), "header.7z");
        Files.write(archive.toPath(), bytes);
        return archive;
    }

    private static byte[] encoded(int count) throws IOException {
        byte[][] folders = count == 1 ? new byte[][]{emptyFileHeader()}
                : new byte[][]{emptyFileHeader(), new byte[]{42}};
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(23); // EncodedHeader
        descriptor.write(6); descriptor.write(0); descriptor.write(count); // PackInfo, PackPos, count
        descriptor.write(9); // Sizes, all fixtures fit single-byte 7z integers.
        for (byte[] folder : folders) { descriptor.write(folder.length); packed.write(folder); }
        descriptor.write(10); descriptor.write(1); // All packed CRCs defined.
        for (byte[] folder : folders) little(descriptor, crc(folder), 4);
        descriptor.write(0);
        descriptor.write(7); descriptor.write(11); descriptor.write(count); descriptor.write(0);
        for (byte[] ignored : folders) {
            descriptor.write(1); descriptor.write(1); descriptor.write(0); // One Copy coder.
        }
        descriptor.write(12);
        for (byte[] folder : folders) descriptor.write(folder.length);
        descriptor.write(10); descriptor.write(1);
        for (byte[] folder : folders) little(descriptor, crc(folder), 4);
        descriptor.write(0); descriptor.write(0);
        return wrap(packed.toByteArray(), descriptor.toByteArray());
    }

    private static byte[] emptyFileHeader() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(new byte[]{1, 5, 1, 14, 1, (byte) 128, 15, 1, (byte) 128});
        byte[] name = "data.bin\0".getBytes(StandardCharsets.UTF_16LE);
        header.write(17); header.write(name.length + 1); header.write(0); header.write(name);
        header.write(0); header.write(0);
        return header.toByteArray();
    }

    private static byte[] wrap(byte[] packed, byte[] header) throws IOException {
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        little(start, packed.length, 8); little(start, header.length, 8); little(start, crc(header), 4);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c, 0, 4});
        little(output, crc(start.toByteArray()), 4);
        output.write(start.toByteArray()); output.write(packed); output.write(header);
        return output.toByteArray();
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue();
    }

    private static void little(ByteArrayOutputStream output, long value, int width) {
        for (int i = 0; i < width; i++) output.write((int) (value >>> (8 * i)));
    }
}
