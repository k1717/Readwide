package com.textview.reader.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LargeTextPageModelMathTest {
    @Test
    public void displayedTotal_usesOneCanonicalPriorityOrder() {
        assertEquals(7, LargeTextPageModelMath.displayedTotalPages(
                false, false, 0, true, 99, 88, 7));
        assertEquals(12, LargeTextPageModelMath.displayedTotalPages(
                true, true, 12, true, 99, 88, 7));
        assertEquals(99, LargeTextPageModelMath.displayedTotalPages(
                true, false, 0, true, 99, 88, 7));
        assertEquals(88, LargeTextPageModelMath.displayedTotalPages(
                true, false, 0, false, 99, 88, 7));
        assertEquals(7, LargeTextPageModelMath.displayedTotalPages(
                true, false, 0, false, 0, 0, 7));
    }

    @Test
    public void displayedCurrent_usesPendingEndExactThenEstimate() {
        assertEquals(3, LargeTextPageModelMath.displayedCurrentPage(
                false, 3, 10, true, 8, true, true, 9, 20));
        assertEquals(8, LargeTextPageModelMath.displayedCurrentPage(
                true, 3, 10, true, 8, true, true, 9, 20));
        assertEquals(10, LargeTextPageModelMath.displayedCurrentPage(
                true, 3, 10, false, 0, true, true, 9, 20));
        assertEquals(9, LargeTextPageModelMath.displayedCurrentPage(
                true, 3, 10, false, 0, false, true, 9, 20));
        assertEquals(6, LargeTextPageModelMath.displayedCurrentPage(
                true, 3, 10, false, 0, false, false, 0, 3));
    }

    @Test
    public void preserveKnownTarget_keepsCallerProvidedTotalStable() {
        LargeTextPageModelMath.OffsetState state = LargeTextPageModelMath.preserveKnownTarget(
                4, 20, 30, 12, 18, 8);

        assertEquals(30, state.totalPages);
        assertEquals(16, state.basePageOffset);
    }

    @Test
    public void recomputePartitionOffset_usesExactTotalWhenAvailable() {
        LargeTextPageModelMath.OffsetState state = LargeTextPageModelMath.recomputePartitionOffset(
                0, 5, 123, 1000, 100, 501);

        assertEquals(123, state.totalPages);
        assertEquals(62, state.basePageOffset);
    }

    @Test
    public void recomputePartitionOffset_keepsExistingEstimateUntilExactTotalExists() {
        LargeTextPageModelMath.OffsetState state = LargeTextPageModelMath.recomputePartitionOffset(
                80, 5, 0, 1000, 100, 501);

        assertEquals(80, state.totalPages);
        assertEquals(40, state.basePageOffset);
    }
}
