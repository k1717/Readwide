package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** Shared fault injection for all three verified RAR forward readers. */
final class RarForwardFailureAssertions {
    private RarForwardFailureAssertions() {}

    static void truncatedSpool(ArchiveSupport.ForwardArchiveReader reader, File directory) throws Exception {
        try (ArchiveSupport.ForwardArchiveReader owned = reader) {
            assertNotNull(owned.nextEntry());
            assertEquals(1, owned.read(new byte[1]));
            File spool = directory.listFiles()[0];
            try (RandomAccessFile file = new RandomAccessFile(spool, "rw")) { file.setLength(0); }
            try { owned.read(new byte[1]); fail("Shortened spool must not be accepted as EOF"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("spool")); }
            assertRetired(owned);
        }
        assertEquals(0, directory.list().length);
    }

    static void deletionRetry(ArchiveSupport.ForwardArchiveReader reader, File directory) throws Exception {
        Field field = reader.getClass().getDeclaredField("spool");
        field.setAccessible(true);
        File original = null;
        try {
            reader.nextEntry();
            assertEquals(1, reader.read(new byte[1]));
            original = (File) field.get(reader);
            field.set(reader, new File(original.getAbsolutePath()) {
                @Override public boolean delete() { return false; }
            });
            try { reader.close(); fail("Deletion failure must be reported"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("spool")); }
            assertNotNull(field.get(reader));
            assertTrue(original.exists());
            field.set(reader, original);
            reader.close();
            reader.close();
            assertFalse(original.exists());
            assertRetired(reader);
        } finally {
            if (original != null && original.exists()) field.set(reader, original);
            reader.close();
        }
        assertEquals(0, directory.list().length);
    }

    static void interruptionRetires(ArchiveSupport.ForwardArchiveReader reader, File directory) throws Exception {
        try (ArchiveSupport.ForwardArchiveReader owned = reader) {
            owned.nextEntry();
            assertEquals(1, owned.read(new byte[1]));
            Thread.currentThread().interrupt();
            try {
                try { owned.read(new byte[1]); fail("Expected cancellation"); }
                catch (IOException expected) { }
            } finally { Thread.interrupted(); }
            assertRetired(owned); // Clearing the thread flag must not resurrect the session.
        }
        assertEquals(0, directory.list().length);
    }

    private static void assertRetired(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        try { reader.read(new byte[1]); fail("Retired read"); } catch (IOException expected) { }
        try { reader.nextEntry(); fail("Retired advance"); } catch (IOException expected) { }
        try { reader.drainCurrentEntry(Long.MAX_VALUE); fail("Retired drain"); } catch (IOException expected) { }
    }
}
