package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Public API checks; run only when libarchive is present (real or a host JNI API substitute). */
public final class SevenZNativePresentForwardTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private File spoolRoot;
    private String previousTemporary;
    @Before public void setup() throws Exception {
        Assume.assumeTrue("requires native-present routing", LibarchiveNativeBridge.isAvailable());
        previousTemporary = System.getProperty("java.io.tmpdir");
        spoolRoot = temporary.newFolder("owned-spools");
        System.setProperty("java.io.tmpdir", spoolRoot.getAbsolutePath());
    }
    @After public void restore() {
        if (spoolRoot != null) {
            if (previousTemporary == null) System.clearProperty("java.io.tmpdir");
            else System.setProperty("java.io.tmpdir", previousTemporary);
        }
    }
    @Test public void swap2UsesPublicForwardRoute() throws Exception { single(2, false, false); }
    @Test public void swap4UsesPublicForwardRoute() throws Exception { single(4, false, false); }
    @Test public void swapOnlyInEncodedHeaderUsesPublicForwardRoute() throws Exception { single(4, true, true); }
    @Test public void plainCopyKeepsWorking() throws Exception {
        File archive = new File(temporary.newFolder(), "copy.7z");
        byte[] raw = Round4Fixtures.data(103);
        try (org.apache.commons.compress.archivers.sevenz.SevenZOutputFile output =
                new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(archive)) {
            output.setContentCompression(org.apache.commons.compress.archivers.sevenz.SevenZMethod.COPY);
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry =
                    new org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry();
            entry.setName("data.bin"); entry.setSize(raw.length);
            output.putArchiveEntry(entry); output.write(raw); output.closeArchiveEntry();
        }
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            assertEquals("data.bin", reader.nextEntry().path);
            assertArrayEquals(raw, bytes(reader));
            assertNull(reader.nextEntry());
        }
        assertEmptySpools();
    }
    private void single(int width, boolean encoded, boolean headerOnly) throws Exception {
        byte[] raw = Round4Fixtures.data(1003);
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), raw, width,
                false, encoded, false, false, headerOnly);
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            assertNotNull(reader);
            assertEquals("data.bin", reader.nextEntry().path);
            assertArrayEquals(raw, bytes(reader));
            assertNull(reader.nextEntry());
            assertEmptySpools(); // EOF also retires the owned temporary directory.
        }
        assertEmptySpools();
    }
    @Test public void solidSecondEntryAfterUnlimitedDrain() throws Exception {
        byte[] raw = Round4Fixtures.data(20006);
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), raw, 4, false, false, false, true);
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            assertEquals("1.bin", reader.nextEntry().path);
            assertTrue(reader.drainCurrentEntry(-1));
            assertEquals("2.bin", reader.nextEntry().path);
            assertArrayEquals(Arrays.copyOfRange(raw, raw.length / 2, raw.length), bytes(reader));
        }
        assertEmptySpools();
    }
    @Test public void splitSwapFromLaterVolumeCrossesHeaderAndData() throws Exception {
        byte[] raw = Round4Fixtures.data(1003);
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), raw, 2, false, true, false, false);
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(split(archive), null)) {
            assertEquals("data.bin", reader.nextEntry().path);
            assertArrayEquals(raw, bytes(reader));
        }
        assertEmptySpools();
    }
    @Test public void splitLz4WithEncodedHeaderUsesPublicForwardRoute() throws Exception {
        SevenZLz4ArchiveTest fixture = new SevenZLz4ArchiveTest();
        fixture.temp.create();
        try {
            Method factory = SevenZLz4ArchiveTest.class.getDeclaredMethod("archive", boolean.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
            factory.setAccessible(true);
            File archive = (File) factory.invoke(fixture, false, true, true, true, false, false);
            byte[] expected = new byte[4096];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) ((i + 4096) % 47);
            try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(split(archive), null)) {
                assertEquals("1.bin", reader.nextEntry().path);
                assertTrue(reader.drainCurrentEntry(4096));
                assertEquals("2.bin", reader.nextEntry().path);
                assertArrayEquals(expected, bytes(reader));
            }
        } finally { fixture.temp.delete(); }
        assertEmptySpools();
    }
    @Test public void closeBeforeReadingRemovesOwnedDirectory() throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), new byte[4], 4, false, false, false, false);
        ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null);
        assertEquals("data.bin", reader.nextEntry().path);
        reader.close(); reader.close();
        assertEmptySpools();
    }
    @Test public void folderCrcFailureRemovesOwnedSpool() throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), new byte[4], 4, false, false, true, false);
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            reader.nextEntry();
            assertThrows(IOException.class, () -> reader.read(new byte[4]));
            assertEmptySpools();
        }
    }
    @Test public void drainLimitFailureRemovesOwnedDirectory() throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), new byte[4], 4, false, false, false, false);
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            reader.nextEntry();
            assertThrows(IOException.class, () -> reader.drainCurrentEntry(3));
            assertEmptySpools();
        }
    }
    @Test public void interruptionDuringReadRetiresSpoolAndPreservesFlag() throws Exception {
        File archive = Round4Fixtures.sevenZ(temporary.newFolder(), new byte[4], 4, false, false, false, false);
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            reader.nextEntry();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, () -> reader.read(new byte[4]));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertEmptySpools();
        }
    }
    private File split(File archive) throws IOException {
        byte[] all = Files.readAllBytes(archive.toPath());
        File dir = temporary.newFolder();
        int[] cuts = {0, 3, 31, 40, all.length - 7, all.length};
        File last = null;
        for (int i = 0; i + 1 < cuts.length; i++) last = Round4Fixtures.write(dir,
                String.format(Locale.ROOT, "book.7z.%03d", i + 1), Arrays.copyOfRange(all, cuts[i], cuts[i + 1]));
        return last;
    }
    private static byte[] bytes(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[37];
        for (int count; (count = reader.read(buffer)) != -1;) output.write(buffer, 0, count);
        return output.toByteArray();
    }
    private void assertEmptySpools() { assertArrayEquals(new String[0], spoolRoot.list()); }
}
