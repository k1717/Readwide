package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class ArchiveExtractionByteBudgetTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void interruptedWriterDoesNotCommitBytes() throws Exception {
        File file = temp.newFile("interrupted.bin");
        try (OutputStream out = ArchiveExtractionByteBudget.openOutputStream(file, Long.MAX_VALUE)) {
            Thread.currentThread().interrupt();
            try { out.write(1); fail("Interrupted extraction must stop"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("interrupted")); }
            finally { Thread.interrupted(); }
        }
        assertEquals(0L, file.length());
    }

    @Test
    public void storageBudgetCanExceedFormer128GiBCeiling() {
        long free = 512L * 1024 * 1024 * 1024;
        assertEquals(free - 64L * 1024 * 1024,
                ArchiveSupport.runtimeExtractionBudgetForUsableSpace(free));
    }

    @Test
    public void freeSpaceReserveAndUnknownSpaceBehaviorArePreserved() {
        assertEquals(0L, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(64L * 1024 * 1024));
        assertEquals(1L, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(64L * 1024 * 1024 + 1));
        assertEquals(Long.MAX_VALUE, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(0L));
        assertEquals(Long.MAX_VALUE, ArchiveSupport.runtimeExtractionBudgetForUsableSpace(-1L));
    }

    @Test
    public void streamAccountingCrosses128GiBButRejectsLongOverflow() throws Exception {
        long formerLimit = 128L * 1024 * 1024 * 1024;
        assertEquals(formerLimit + 1, ArchiveSupport.checkedAddDecodedStreamBytes(formerLimit, 1));
        try {
            ArchiveSupport.checkedAddDecodedStreamBytes(Long.MAX_VALUE, 1);
            fail("Counter overflow must fail");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("overflow")); }
    }

    @Test
    public void sharedAccountingCanCross128GiBWithoutWritingHugeFiles() throws Exception {
        long spaceBudget = 512L * 1024 * 1024 * 1024;
        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(spaceBudget)) {
            ArchiveExtractionByteBudget budget = scope.budgetForTest();
            java.lang.reflect.Method reserve = ArchiveExtractionByteBudget.class.getDeclaredMethod(
                    "reserve", String.class, int.class);
            reserve.setAccessible(true);
            for (int i = 0; i < 65; i++) reserve.invoke(budget, "virtual-entry", Integer.MAX_VALUE);
            assertEquals(65L * Integer.MAX_VALUE, budget.totalBytesForTest());
            assertTrue(budget.totalBytesForTest() > 128L * 1024 * 1024 * 1024);
        }
    }

    @Test
    public void separateUnknownSizeEntriesShareOneOperationBudget() throws Exception {
        File first = temp.newFile("first.bin");
        File second = temp.newFile("second.bin");
        File overflow = temp.newFile("overflow.bin");

        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(5L)) {
            write(first, new byte[] {1, 2, 3});
            write(second, new byte[] {4, 5});
            assertEquals(5L, scope.budgetForTest().totalBytesForTest());

            try {
                write(overflow, new byte[] {6});
                fail("Expected the operation-wide extraction limit to reject the third file");
            } catch (ArchiveSupport.UnsupportedArchiveFeatureException expected) {
                assertTrue(expected.getMessage().contains("safety limit"));
            }
            assertEquals(0L, overflow.length());
            assertEquals(5L, scope.budgetForTest().totalBytesForTest());
        }
    }

    @Test
    public void fallbackRewriteReplacesEarlierPartialFileAccounting() throws Exception {
        File retried = temp.newFile("retried.bin");
        File finalEntry = temp.newFile("final.bin");

        try (ArchiveExtractionByteBudget.Scope scope = ArchiveExtractionByteBudget.begin(5L)) {
            write(retried, new byte[] {1, 2, 3, 4});
            write(retried, new byte[] {8, 9});
            write(finalEntry, new byte[] {5, 6, 7});

            assertEquals(2L, retried.length());
            assertEquals(3L, finalEntry.length());
            assertEquals(5L, scope.budgetForTest().totalBytesForTest());
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        try (OutputStream out = ArchiveExtractionByteBudget.openOutputStream(file, 5L)) {
            out.write(data);
            out.flush();
        }
    }
}
