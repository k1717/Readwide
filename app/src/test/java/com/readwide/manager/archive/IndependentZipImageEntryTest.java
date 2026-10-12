package com.readwide.manager.archive;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Narrow cache route: independent readers, CRC rollback and conservative eligibility. */
public class IndependentZipImageEntryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Before @After public void clear() { ZipxAesArchiveReader.clearCachedIndexes(); }
    private static final byte[] DATA = data();

    @Test public void storedAndDeflatedReadsDoNotWaitForTheSharedDecoderMonitor() throws Exception {
        for (int method : new int[]{ZipEntry.STORED, ZipEntry.DEFLATED}) {
            File archive = archive(method), warm = temp.newFile();
            assertTrue(ArchiveSupport.tryExtractIndependentZipImageEntry(archive, "1.png", warm).success);
            Field field = ZipxAesArchiveReader.class.getDeclaredField("INDEXES"); field.setAccessible(true);
            Map<?, ?> indexes = (Map<?, ?>) field.get(null);
            Object index;
            synchronized (indexes) { index = indexes.get(archive.getCanonicalPath()); }
            assertNotNull(index);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            File first = temp.newFile(), second = temp.newFile();
            try {
                synchronized (index) {
                    Future<ArchiveSupport.ExtractionResult> one = workers.submit(
                            () -> ArchiveSupport.tryExtractIndependentZipImageEntry(archive, "1.png", first));
                    Future<ArchiveSupport.ExtractionResult> two = workers.submit(
                            () -> ArchiveSupport.tryExtractIndependentZipImageEntry(archive, "2.png", second));
                    assertTrue(one.get(10, TimeUnit.SECONDS).success);
                    assertTrue(two.get(10, TimeUnit.SECONDS).success);
                }
                assertArrayEquals(DATA, Files.readAllBytes(first.toPath()));
                assertArrayEquals(DATA, Files.readAllBytes(second.toPath()));
            } finally {
                workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
    @Test public void crcFailureRestoresDestinationAndNeverReturnsIneligible() throws Exception {
        File archive = archive(ZipEntry.DEFLATED);
        byte[] packed = Files.readAllBytes(archive.toPath());
        for (int i = 0; i + 46 <= packed.length; i++) {
            if (packed[i] == 'P' && packed[i + 1] == 'K' && packed[i + 2] == 1 && packed[i + 3] == 2) {
                packed[i + 16] ^= 1; break;
            }
        }
        Files.write(archive.toPath(), packed);
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        ArchiveSupport.ExtractionResult result = ArchiveSupport.tryExtractIndependentZipImageEntry(archive, "1.png", output);
        assertNotNull(result); assertFalse(result.success);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void sourceCannotBeReplacedByItsOwnEntry() throws Exception {
        File archive = archive(ZipEntry.STORED); byte[] original = Files.readAllBytes(archive.toPath());
        assertFalse(ArchiveSupport.tryExtractIndependentZipImageEntry(archive, "1.png", archive).success);
        assertArrayEquals(original, Files.readAllBytes(archive.toPath()));
    }
    @Test public void unknownEntryAndOtherSuffixDeferWithoutTouchingOutput() throws Exception {
        File archive = archive(ZipEntry.DEFLATED), output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertNull(ArchiveSupport.tryExtractIndependentZipImageEntry(archive, "absent.png", output));
        File other = new File(temp.newFolder(), "archive.rar"); Files.copy(archive.toPath(), other.toPath());
        assertNull(ArchiveSupport.tryExtractIndependentZipImageEntry(other, "1.png", output));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void encryptedAndSplitEntriesDefer() throws Exception {
        ZipxAesArchiveReaderTest fixture = new ZipxAesArchiveReaderTest(); fixture.temp.create();
        try {
            java.lang.reflect.Method make = ZipxAesArchiveReaderTest.class.getDeclaredMethod("buildAesZipx", String.class, int.class);
            make.setAccessible(true);
            File encrypted = (File) make.invoke(fixture, "aes.zip", 12);
            File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
            assertNull(ArchiveSupport.tryExtractIndependentZipImageEntry(encrypted, "folder/page.txt", output));
            java.lang.reflect.Method split = ZipxAesArchiveReaderTest.class.getDeclaredMethod("splitZipx", File.class, int[].class);
            split.setAccessible(true);
            File[] parts = (File[]) split.invoke(fixture, archive(ZipEntry.STORED, 1), new int[]{20});
            File finalVolume = new File(parts[parts.length - 1].getParentFile(), "split.zip");
            Files.move(parts[parts.length - 1].toPath(), finalVolume.toPath());
            assertTrue(ZipxAesArchiveReader.canExtractArchive(finalVolume));
            assertNull(ArchiveSupport.tryExtractIndependentZipImageEntry(finalVolume, "1.png", output));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        } finally { fixture.temp.delete(); }
    }
    private File archive(int method) throws IOException {
        return archive(method, 2);
    }
    private File archive(int method, int count) throws IOException {
        File archive = new File(temp.newFolder(), "images.cbz");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            for (int i = 1; i <= count; i++) {
                String name = i + ".png";
                ZipEntry entry = new ZipEntry(name); entry.setMethod(method);
                if (method == ZipEntry.STORED) {
                    CRC32 crc = new CRC32(); crc.update(DATA);
                    entry.setSize(DATA.length); entry.setCompressedSize(DATA.length); entry.setCrc(crc.getValue());
                }
                output.putNextEntry(entry); output.write(DATA); output.closeEntry();
            }
        }
        return archive;
    }
    private static byte[] data() {
        byte[] raw = new byte[65537]; for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i * 37); return raw;
    }
}
