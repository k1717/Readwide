package com.readwide.manager.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LargeTextWorkQueueTest {
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test public void foregroundRunsBeforeQueuedPrefetchAndEachPriorityIsFifo() throws Exception {
        LargeTextWorkQueue queue = new LargeTextWorkQueue();
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            queue.execute(() -> { running.countDown(); await(release); });
            await(running);
            queue.execute(() -> order.add("background-1"));
            queue.executeForeground(() -> order.add("foreground-1"));
            queue.execute(() -> order.add("background-2"));
            queue.executeForeground(() -> order.add("foreground-2"));
            assertTrue(queue.hasForegroundWork());
            release.countDown();
            queue.shutdown();
            assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("foreground-1", "foreground-2", "background-1", "background-2"), order);
            assertFalse(queue.hasForegroundWork());
        } finally { release.countDown(); queue.shutdownNow(); }
    }

    @Test public void foregroundReservationRemainsUntilItsWorkFinishes() throws Exception {
        LargeTextWorkQueue queue = new LargeTextWorkQueue();
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            queue.executeForeground(() -> { running.countDown(); await(release); });
            await(running);
            assertTrue(queue.hasForegroundWork());
            release.countDown();
            queue.shutdown();
            assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(queue.hasForegroundWork());
        } finally { release.countDown(); queue.shutdownNow(); }
    }

    @Test public void rejectedForegroundDoesNotLeavePrefetchPermanentlyBlocked() {
        LargeTextWorkQueue queue = new LargeTextWorkQueue();
        queue.shutdown();
        assertThrows(RejectedExecutionException.class, () -> queue.executeForeground(() -> {}));
        assertFalse(queue.hasForegroundWork());
        assertThrows(NullPointerException.class, () -> queue.executeForeground(null));
        assertFalse(queue.hasForegroundWork());
    }

    @Test public void shutdownDropsReservationsForNeverStartedForegroundTasks() throws Exception {
        LargeTextWorkQueue queue = new LargeTextWorkQueue();
        CountDownLatch running = new CountDownLatch(1);
        try {
            queue.execute(() -> {
                running.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            await(running);
            queue.executeForeground(() -> fail("Cancelled work must not run"));
            assertTrue(queue.hasForegroundWork());
            assertEquals(1, queue.shutdownNow().size());
            assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(queue.hasForegroundWork());
        } finally { queue.shutdownNow(); }
    }
}
