package com.readwide.manager.util;

import org.junit.Test;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public class MatchOffsetIndexTest {
    @Test public void sparseOffsetsRetainExactRankAndSelection() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(10_000, 100);
        builder.add(0); builder.add(50); builder.add(9000);
        MatchOffsetIndex index = builder.build();
        assertTrue(index.isComplete());
        assertEquals(12L, index.cachedBytes());
        assertEquals(1, index.rank(50));
        assertEquals(2, index.rank(51));
        assertEquals(9000, index.occurrence(3));
        assertEquals(-1, index.occurrence(4));
    }

    @Test public void denseRankAndSelectionAgreeAtEveryWordAndBlockBoundary() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(1500, 200);
        for (int i = 0; i < 1500; i += 2) builder.add(i);
        MatchOffsetIndex index = builder.build();
        assertTrue(index.isComplete()); // more than 200 hits without an overflow scan
        assertEquals(750, index.count());
        assertTrue(index.cachedBytes() <= 800);
        for (int bound = -1; bound <= 1501; bound++) {
            int safe = Math.max(0, Math.min(1500, bound));
            assertEquals((safe + 1) / 2, index.rank(bound));
        }
        for (int ordinal = 1; ordinal <= 750; ordinal++) assertEquals(2 * (ordinal - 1), index.occurrence(ordinal));
    }

    @Test public void bitmapSelectionSkipsEmptyRankBlocksAndFindsLastPartialWord() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(4097, 256);
        for (int i = 0; i < 200; i++) builder.add(i);
        builder.add(4096);
        MatchOffsetIndex index = builder.build();
        assertTrue(index.cachedBytes() < 4L * index.count());
        assertEquals(200, index.rank(4096));
        assertEquals(201, index.rank(4097));
        assertEquals(4096, index.occurrence(201));
        assertEquals(199, index.occurrence(200));
        assertEquals(201, index.rank(Long.MAX_VALUE));
        assertEquals(0, index.rank(Long.MIN_VALUE));
    }

    @Test public void denseAllBitsSetSelectsBit63AndTheNextWord() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(1025, 64);
        for (int i = 0; i < 1025; i++) builder.add(i);
        MatchOffsetIndex index = builder.build();
        assertTrue(index.isComplete());
        assertEquals(63, index.occurrence(64));
        assertEquals(64, index.occurrence(65));
        assertEquals(1024, index.occurrence(1025));
        assertEquals(1024, index.rank(1024));
    }

    @Test public void retainedOffsetPayloadNeverExceedsItsBudget() {
        for (int length : new int[]{0, 1, 64, 65, 511, 512, 513, 10_000}) {
            MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(length, 64);
            for (int i = 0; i < length; i++) builder.add(i);
            MatchOffsetIndex index = builder.build();
            assertEquals(length, index.count());
            assertTrue(index.cachedBytes() <= 256L);
        }
    }

    @Test public void insufficientBitmapBudgetRetainsPrefixWithoutClaimingCompleteness() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(1_000_000, 3);
        builder.add(0); builder.add(11); builder.add(25); builder.add(999999);
        MatchOffsetIndex index = builder.build();
        assertEquals(4, index.count());
        assertFalse(index.isComplete());
        assertEquals(25, index.occurrence(3));
        assertEquals(-1, index.occurrence(4));
        try { index.rank(1000); fail("Partial ranks must not look complete"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void hugeDeclaredTextLengthDoesNotForceAHugeBitmap() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(Integer.MAX_VALUE, 2);
        builder.add(0); builder.add(31); builder.add(Integer.MAX_VALUE - 1);
        MatchOffsetIndex index = builder.build();
        assertFalse(index.isComplete());
        assertEquals(8L, index.cachedBytes());
        assertEquals(3, index.count());
    }

    @Test public void duplicateAndOutOfOrderOffsetsAreRejected() {
        for (int bad : new int[]{-1, 2, 3, 10}) {
            MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(10, 100);
            builder.add(3);
            try { builder.add(bad); fail("Invalid offset accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void completedBuilderCannotMutatePublishedStorage() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(64, 64);
        for (int i = 0; i < 10; i++) builder.add(i);
        MatchOffsetIndex index = builder.build();
        try { builder.add(11); fail("Consumed builder reused"); } catch (IllegalStateException expected) { }
        assertEquals(10, index.count());
    }

    @Test public void interruptedBuildDoesNotPublishItsIndex() {
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(64, 64);
        for (int i = 0; i < 10; i++) builder.add(i);
        Thread.currentThread().interrupt();
        try { builder.build(); fail("Interrupted build published"); }
        catch (CancellationException expected) { }
        finally { Thread.interrupted(); }
    }
}
