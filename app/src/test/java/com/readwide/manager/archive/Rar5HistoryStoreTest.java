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

    @Test public void growthRetainsSparseInitialHistoryWithoutDeclaredSizeAllocation() throws Exception {
        try (Rar5HistoryStore history = store(100)) {
            history.append((byte) 19);
            history.ensureCapacity(1L << 40);
            assertEquals(19, history.read(0));
            assertEquals(1, history.residentPagesForTest());
            assertNull(history.spoolForTest());
            byte[] match = new byte[6];
            history.copyMatch(3, match, 0, match.length);
            assertArrayEquals(new byte[]{0, 0, 19, 0, 0, 19}, match);
        }
    }

    @Test public void growthAtExactCapacityKeepsSpilledPagesAndNonAlignedRingSlots() throws Exception {
        int oldCapacity = 3 * PAGE + 17, newCapacity = 5 * PAGE + 31;
        byte[] reference = new byte[newCapacity];
        try (Rar5HistoryStore history = store(oldCapacity)) {
            for (int i = 0; i < oldCapacity; i++) history.append(reference[i] = (byte) (i * 31 + i / 97));
            File spool = history.spoolForTest();
            assertNotNull(spool);
            history.ensureCapacity(newCapacity);
            assertEquals(spool, history.spoolForTest());
            for (int i = 0; i < oldCapacity; i += 8191) assertEquals(reference[i], history.read(i));
            byte[] match = new byte[PAGE + 7];
            history.copyMatch(oldCapacity, match, 0, match.length);
            for (int i = 0; i < match.length; i++) {
                assertEquals(reference[i], match[i]);
                reference[oldCapacity + i] = match[i];
            }
            int produced = oldCapacity + match.length;
            for (int i = produced; i < newCapacity + 97; i++) {
                byte value = (byte) (i * 17);
                history.append(value);
                reference[i % newCapacity] = value;
            }
            for (int i = 97; i < newCapacity + 97; i += 8191) {
                assertEquals(reference[i % newCapacity], history.read(i));
            }
            assertTrue(history.residentPagesForTest() <= 2);
        }
    }

    @Test public void smallerMinimumDoesNotShrinkOrReinterpretWrappedHistory() throws Exception {
        try (Rar5HistoryStore history = store(100)) {
            for (int i = 0; i < 120; i++) history.append((byte) i);
            history.ensureCapacity(3);
            assertEquals(20, history.read(20));
            byte[] match = new byte[7];
            history.copyMatch(100, match, 0, match.length);
            assertArrayEquals(new byte[]{20, 21, 22, 23, 24, 25, 26}, match);
        }
    }

    @Test public void growthAfterOverwriteRejectsAndRetiresSpilledHistory() throws Exception {
        int capacity = 3 * PAGE + 17;
        try (Rar5HistoryStore history = store(capacity)) {
            for (int i = 0; i <= capacity; i++) history.append((byte) i);
            File spool = history.spoolForTest();
            IOException failure = assertThrows(IOException.class, () -> history.ensureCapacity(capacity + 1));
            assertTrue(failure.getMessage().contains("after history wrap"));
            assertFalse(spool.exists());
            assertThrows(IOException.class, () -> history.read(capacity));
            assertThrows(IOException.class, () -> history.ensureCapacity(capacity));
        }
    }

    @Test public void growthDoesNotBypassSpilledPageAuthenticationOrSpaceChecks() throws Exception {
        try (Rar5HistoryStore history = store(4L * PAGE)) {
            for (int i = 0; i < 3 * PAGE; i++) history.append((byte) 7);
            File spool = history.spoolForTest();
            try (RandomAccessFile file = new RandomAccessFile(spool, "rw")) {
                file.seek(20); int value = file.read(); file.seek(20); file.write(value ^ 1);
            }
            history.ensureCapacity(8L * PAGE);
            assertThrows(IOException.class, () -> history.read(0));
            assertFalse(spool.exists());
        }
        try (Rar5HistoryStore history = new Rar5HistoryStore(2L * PAGE, 2, temp.getRoot(), ignored -> 0)) {
            for (int i = 0; i < 2 * PAGE; i++) history.append((byte) 7);
            history.ensureCapacity(4L * PAGE);
            assertThrows(IOException.class, () -> history.append((byte) 8));
            assertFalse(history.spoolForTest().exists());
        }
    }

    @Test public void invalidOrCancelledCapacityChangesCannotResumeHistory() throws Exception {
        for (long invalid : new long[]{0, -1, (1L << 40) + 1}) {
            try (Rar5HistoryStore history = store(100)) {
                assertThrows(IOException.class, () -> history.ensureCapacity(invalid));
                assertThrows(IOException.class, () -> history.append((byte) 1));
            }
        }
        try (Rar5HistoryStore history = store(4L * PAGE)) {
            for (int i = 0; i < 3 * PAGE; i++) history.append((byte) 7);
            File spool = history.spoolForTest();
            Thread.currentThread().interrupt();
            try { assertThrows(IOException.class, () -> history.ensureCapacity(8L * PAGE)); }
            finally { Thread.interrupted(); }
            assertFalse(spool.exists());
            assertThrows(IOException.class, () -> history.ensureCapacity(4L * PAGE));
        }
    }

    @Test public void blockMatchesPreserveOverlapWrapAndSpilledPages() throws Exception {
        int capacity = 3 * PAGE + 17;
        byte[] reference = new byte[capacity];
        byte[] block = new byte[PAGE + 39];
        long produced = 0;
        try (Rar5HistoryStore history = store(capacity)) {
            for (int i = 0; i < capacity + 97; i++) {
                byte value = (byte) (i * 31 + i / 97);
                reference[(int) (produced++ % capacity)] = value;
                history.append(value);
            }
            for (int distance : new int[] {capacity, 1, 2, 37, PAGE + 5, capacity - 1}) {
                int length = PAGE + 13;
                history.copyMatch(distance, block, 11, length);
                for (int i = 0; i < length; i++) {
                    byte expected = reference[(int) ((produced - distance) % capacity)];
                    assertEquals(expected, block[11 + i]);
                    reference[(int) (produced++ % capacity)] = expected;
                }
                assertEquals(block[10 + length], history.read(produced - 1));
            }
            assertTrue(history.residentPagesForTest() <= 2);
        }
    }

    @Test public void blockMatchKeepsInitialZeroHistoryAndCancellation() throws Exception {
        try (Rar5HistoryStore history = store(100)) {
            history.append((byte) 7);
            byte[] block = new byte[27];
            history.copyMatch(3, block, 0, block.length);
            for (int i = 0; i < block.length; i++) assertEquals(i % 3 == 2 ? 7 : 0, block[i]);
            Thread.currentThread().interrupt();
            try { history.copyMatch(1, block, 0, 1); fail("Interrupted match was copied"); }
            catch (IOException expected) { }
            finally { Thread.interrupted(); }
            try { history.read(0); fail("Cancelled history was reused"); }
            catch (IOException expected) { }
        }
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
