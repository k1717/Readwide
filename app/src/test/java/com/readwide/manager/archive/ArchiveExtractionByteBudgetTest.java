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

    @Test
    public void configuredExtractionLimitIs128GiB() {
        assertEquals(128L * 1024L * 1024L * 1024L,
                ArchiveSupport.MAX_EXTRACTION_TOTAL_BYTES);
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
