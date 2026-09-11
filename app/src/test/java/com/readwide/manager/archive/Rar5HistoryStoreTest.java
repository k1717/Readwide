package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Random;

import static org.junit.Assert.*;

public class Rar5HistoryStoreTest {
    private static final int PAGE = 64 * 1024;
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Rar5HistoryStore store(long capacity) {
        return new Rar5HistoryStore(capacity, 2, temp.getRoot(), ignored -> Long.MAX_VALUE);
    }

    @Test public void hugeDeclarationDoesNotAllocateOrSpillBeforeHistoryExists() throws Exception {
        try (Rar5HistoryStore history = store(1L << 40)) {
            assertEquals(0, history.residentPagesForTest());
            assertEquals(0, history.read(-123));
            history.append((byte) 31);
            assertEquals(31, history.read(0));
            assertEquals(1, history.residentPagesForTest());
            assertNull(history.spoolForTest());
        }
    }

    @Test public void evictedPagesReloadExactlyAndCloseDeletesSpool() throws Exception {
        File spool;
        try (Rar5HistoryStore history = store(8L * PAGE)) {
            for (int i = 0; i < 6 * PAGE; i++) history.append((byte) (i * 31));
            spool = history.spoolForTest();
            assertNotNull(spool);
            assertTrue(spool.isFile());
            for (int i = 6 * PAGE - 1; i >= 0; i--) assertEquals((byte) (i * 31), history.read(i));
            assertTrue(history.residentPagesForTest() <= 2);
        }
        assertFalse(spool.exists());
    }

    @Test public void nonPowerOfTwoWindowWrapMatchesReferenceHistory() throws Exception {
        int capacity = 3 * PAGE + 17;
        Random random = new Random(19);
        byte[] reference = new byte[capacity];
        try (Rar5HistoryStore history = store(capacity)) {
            for (int i = 0; i < capacity * 4; i++) {
                byte value = (byte) (i * 13 + i / 101);
                reference[i % capacity] = value;
                history.append(value);
                if (i % 16381 == 0) {
                    for (int probe = 0; probe < 20; probe++) {
                        int back = random.nextInt(Math.min(i + 1, capacity));
                        assertEquals(reference[(i - back) % capacity], history.read(i - back));
                    }
                }
            }
        }
    }

    @Test public void overlappingMatchesCanReadNewlyAppendedHistory() throws Exception {
        try (Rar5HistoryStore history = store(4L * PAGE)) {
            history.append((byte) 'A');
            history.append((byte) 'B');
            for (int i = 2; i < 3 * PAGE; i++) history.append(history.read(i - 2));
            for (int i = 0; i < 3 * PAGE; i += 7919) {
                assertEquals((byte) (i % 2 == 0 ? 'A' : 'B'), history.read(i));
            }
        }
    }

    @Test public void corruptionFailsAuthenticationAndRetiresHistory() throws Exception {
        try (Rar5HistoryStore history = store(8L * PAGE)) {
            for (int i = 0; i < 3 * PAGE; i++) history.append((byte) 5);
            File spool = history.spoolForTest();
            try (RandomAccessFile file = new RandomAccessFile(spool, "rw")) {
                file.seek(20);
                int value = file.read();
                file.seek(20);
                file.write(value ^ 1);
            }
            try { history.read(0); fail("Corrupt temporary page was accepted"); }
            catch (IOException expected) { }
            assertFalse(spool.exists());
            try { history.append((byte) 1); fail("Failed history was reused"); }
            catch (IOException expected) { }
        }
    }

    @Test public void recordCannotBeMovedToAnotherPage() throws Exception {
        try (Rar5HistoryStore history = store(8L * PAGE)) {
            for (int i = 0; i < 4 * PAGE; i++) history.append((byte) i);
            File spool = history.spoolForTest();
            byte[] record = new byte[PAGE + 28];
            try (RandomAccessFile file = new RandomAccessFile(spool, "rw")) {
                file.readFully(record);
                file.write(record); // Page zero's valid ciphertext in page one's slot.
            }
            try { history.read(PAGE); fail("Moved temporary page was accepted"); }
            catch (IOException expected) { }
            assertFalse(spool.exists());
        }
    }

    @Test public void lowSpaceDeletesPartialSpoolAndRefusesReuse() throws Exception {
        try (Rar5HistoryStore history = new Rar5HistoryStore(8L * PAGE, 2, temp.getRoot(), ignored -> 0)) {
            for (int i = 0; i < 2 * PAGE; i++) history.append((byte) 2);
            try { history.append((byte) 3); fail("Low-space spill should stop"); }
            catch (IOException expected) { }
            assertNotNull(history.spoolForTest());
            assertFalse(history.spoolForTest().exists());
            try { history.read(0); fail("Failed history was reused"); }
            catch (IOException expected) { }
        }
    }

    @Test public void cancellationRetiresHistoryAndDeletesSpool() throws Exception {
        try (Rar5HistoryStore history = store(8L * PAGE)) {
            for (int i = 0; i < 3 * PAGE; i++) history.append((byte) 3);
            File spool = history.spoolForTest();
            Thread.currentThread().interrupt();
            try { history.append((byte) 4); fail("Interrupted history was written"); }
            catch (IOException expected) { }
            finally { Thread.interrupted(); }
            assertFalse(spool.exists());
        }
    }

    @Test public void referencesOutsideDeclaredHistoryRemainRejected() throws Exception {
        try (Rar5HistoryStore history = store(3)) {
            for (int i = 0; i < 4; i++) history.append((byte) i);
            assertEquals(1, history.read(1));
            try { history.read(0); fail("Expired history was accepted"); }
            catch (IOException expected) { }
        }
        try (Rar5HistoryStore history = store(3)) {
            try { history.read(0); fail("Unproduced history was accepted"); }
            catch (IOException expected) { }
        }
    }

    @Test public void separateSessionsHaveSeparateTemporaryFilesAndKeys() throws Exception {
        try (Rar5HistoryStore first = store(8L * PAGE); Rar5HistoryStore second = store(8L * PAGE)) {
            for (int i = 0; i < 3 * PAGE; i++) { first.append((byte) 7); second.append((byte) 7); }
            assertNotEquals(first.spoolForTest(), second.spoolForTest());
            assertFalse(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(first.spoolForTest().toPath()),
                    java.nio.file.Files.readAllBytes(second.spoolForTest().toPath())));
            assertEquals(7, first.read(0));
            assertEquals(7, second.read(0));
        }
    }
}
