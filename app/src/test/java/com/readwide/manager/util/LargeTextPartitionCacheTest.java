package com.readwide.manager.util;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import org.junit.Test;
import static org.junit.Assert.*;

public class LargeTextPartitionCacheTest {
    @Test public void lateResultCannotContaminateReplacementDocumentOrClearItsPendingRead() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        long oldGeneration = cache.generation();
        assertNotNull(cache.acquirePrefetch(1, 100, false, 1));
        cache.clear();
        assertNotNull(cache.acquirePrefetch(1, 100, false, 1));
        assertFalse(cache.cacheIfCurrent(oldGeneration, partition("old", false)));
        assertNull(cache.getNormalByStartLine(1, 100));
        assertNull(cache.acquirePrefetch(1, 100, false, 1));
        assertTrue(cache.cacheIfCurrent(cache.generation(), partition("new", false)));
        assertEquals("new", cache.getNormalByStartLine(1, 100).content);
    }

    @Test public void modeResetAlsoRejectsLateLookbehindPartition() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        long oldGeneration = cache.generation();
        cache.clear();
        assertFalse(cache.isCurrent(oldGeneration));
        assertFalse(cache.cacheIfCurrent(oldGeneration, partition("old", true)));
        assertNull(cache.getManualHandoffByStartLine(1, 100));
        assertTrue(cache.cacheIfCurrent(cache.generation(), partition("new", true)));
        assertEquals("new", cache.getManualHandoffByStartLine(1, 100).content);
        assertNull(cache.getNormalByStartLine(1, 100));
    }

    @Test public void newSwitchReplacesQueuedOwnerAndLateCleanupCannotEraseReplacement() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextPartitionCache.PrefetchTicket old = cache.acquirePrefetch(1, 100, false, 1);
        LargeTextPartitionCache.PrefetchTicket replacement = cache.acquirePrefetch(1, 100, false, 2);
        assertNotNull(replacement);
        assertNotSame(old, replacement);
        assertFalse(cache.ownsPrefetch(old));
        assertTrue(cache.ownsPrefetch(replacement));
        cache.releasePrefetch(old);
        assertFalse(cache.completePrefetch(old, partition("stale", false)));
        assertNull(cache.acquirePrefetch(1, 100, false, 2));
        assertTrue(cache.completePrefetch(replacement, partition("current", false)));
        assertEquals("current", cache.getNormalByStartLine(1, 100).content);
    }

    @Test public void duplicateWindowDeduplicatesButManualHandoffHasSeparateOwnership() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextPartitionCache.PrefetchTicket normal = cache.acquirePrefetch(15, 100, false, 3);
        assertNotNull(normal);
        assertNull(cache.acquirePrefetch(99, 100, false, 3));
        LargeTextPartitionCache.PrefetchTicket manual = cache.acquirePrefetch(99, 100, true, 3);
        assertNotNull(manual);
        assertTrue(cache.completePrefetch(normal, partition("normal", false)));
        assertTrue(cache.ownsPrefetch(manual));
        assertTrue(cache.completePrefetch(manual, partition("manual", true)));
        assertEquals("normal", cache.getNormalByStartLine(1, 100).content);
        assertEquals("manual", cache.getManualHandoffByStartLine(1, 100).content);
    }

    @Test public void cacheResetRejectsTicketEvenWhenSwitchNumberIsReused() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextPartitionCache.PrefetchTicket old = cache.acquirePrefetch(1, 100, false, 7);
        cache.clear();
        LargeTextPartitionCache.PrefetchTicket current = cache.acquirePrefetch(1, 100, false, 7);
        cache.releasePrefetch(old);
        assertFalse(cache.completePrefetch(old, partition("old", false)));
        assertTrue(cache.ownsPrefetch(current));
        assertTrue(cache.completePrefetch(current, partition("new", false)));
    }

    @Test public void foregroundWriteRetiresBackgroundResultWithoutOverwritingVisibleContent() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextPartitionCache.PrefetchTicket ticket = cache.acquirePrefetch(1, 100, false, 1);
        cache.cache(partition("foreground", false));
        assertFalse(cache.ownsPrefetch(ticket));
        assertFalse(cache.completePrefetch(ticket, partition("background", false)));
        cache.releasePrefetch(ticket);
        assertEquals("foreground", cache.getNormalByStartLine(1, 100).content);
    }

    @Test public void rejectedOrFailedWorkCanReleaseExactlyItsOwnReservation() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextPartitionCache.PrefetchTicket failed = cache.acquirePrefetch(1, 100, false, 1);
        cache.releasePrefetch(failed); // executor rejection, failed post or read failure
        LargeTextPartitionCache.PrefetchTicket retry = cache.acquirePrefetch(1, 100, false, 1);
        assertNotNull(retry);
        cache.releasePrefetch(failed);
        cache.releasePrefetch(null);
        assertTrue(cache.ownsPrefetch(retry));
    }

    @Test public void mismatchedResultCannotFillAnotherWindowOrHandoffMode() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextPartitionCache.PrefetchTicket ticket = cache.acquirePrefetch(1, 100, false, 1);
        assertFalse(cache.completePrefetch(ticket, partition("wrong mode", true)));
        assertFalse(cache.completePrefetch(ticket, numberedPartition(1)));
        assertNull(cache.getNormalByStartLine(1, 100));
        assertTrue(cache.ownsPrefetch(ticket));
        assertTrue(cache.completePrefetch(ticket, partition("correct", false)));
    }

    @Test public void characterHitPromotesOwnerSoItSurvivesNextEviction() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        LargeTextLinePartitionResult first = numberedPartition(0);
        cache.cache(first);
        for (int i = 1; i < 7; i++) cache.cache(numberedPartition(i));
        assertSame(first, cache.getNormalOwnerForChar(5));
        cache.cache(numberedPartition(7));
        assertSame(first, cache.getNormalByStartLine(1, 100));
        assertNull(cache.getNormalByStartLine(101, 100));
    }

    @Test public void characterMissDoesNotPromoteUnrelatedPartitions() {
        LargeTextPartitionCache cache = new LargeTextPartitionCache();
        for (int i = 0; i < 7; i++) cache.cache(numberedPartition(i));
        assertNull(cache.getNormalOwnerForChar(950));
        cache.cache(numberedPartition(7));
        assertNull(cache.getNormalByStartLine(1, 100));
        assertNotNull(cache.getNormalByStartLine(101, 100));
    }

    private static LargeTextLinePartitionResult numberedPartition(int number) {
        String text = new String(new char[100]).replace('\0', 'x');
        int start = 1 + number * 100;
        return new LargeTextLinePartitionResult(text, 100, start, start + 99, 1000,
                number * 100, 0, 100, start, false, 1000);
    }

    private static LargeTextLinePartitionResult partition(String text, boolean lookbehind) {
        return new LargeTextLinePartitionResult(text, 1, 1, 1, 1, 0,
                0, text.length(), 1, lookbehind, text.length());
    }
}
