package com.readwide.manager.util;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class LatestValueDispatcherTest {
    @Test public void burstCreatesOnePendingDeliveryOfTheLatestValue() {
        Queue queue = new Queue();
        List<Integer> shown = new ArrayList<>();
        LatestValueDispatcher<Integer> dispatcher = new LatestValueDispatcher<>(queue, shown::add);
        for (int i = 0; i < 10_000; i++) dispatcher.offer(i);
        assertEquals(1, queue.tasks.size());
        queue.runNext();
        assertEquals(Arrays.asList(9999), shown);
        assertTrue(queue.tasks.isEmpty());
    }

    @Test public void updatesDuringDeliveryScheduleOnlyOneFollowup() {
        Queue queue = new Queue();
        List<Integer> shown = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<LatestValueDispatcher<Integer>> ref = new java.util.concurrent.atomic.AtomicReference<>();
        LatestValueDispatcher<Integer> dispatcher = new LatestValueDispatcher<>(queue, value -> {
            shown.add(value);
            if (value == 1) { ref.get().offer(2); ref.get().offer(3); }
        });
        ref.set(dispatcher);
        dispatcher.offer(1);
        queue.runNext();
        assertEquals(1, queue.tasks.size());
        queue.runNext();
        assertEquals(Arrays.asList(1, 3), shown);
    }

    @Test public void closeRemovesPendingWorkAndIgnoresFutureOffers() {
        Queue queue = new Queue();
        List<Integer> shown = new ArrayList<>();
        LatestValueDispatcher<Integer> dispatcher = new LatestValueDispatcher<>(queue, shown::add);
        dispatcher.offer(1);
        Runnable stale = queue.tasks.peek();
        dispatcher.close();
        dispatcher.offer(2);
        stale.run();
        assertTrue(queue.tasks.isEmpty());
        assertTrue(shown.isEmpty());
    }

    @Test public void rejectedPostCanRetryWithANewerValue() {
        Queue queue = new Queue();
        queue.accept = false;
        List<Integer> shown = new ArrayList<>();
        LatestValueDispatcher<Integer> dispatcher = new LatestValueDispatcher<>(queue, shown::add);
        dispatcher.offer(1);
        queue.accept = true;
        dispatcher.offer(2);
        queue.runNext();
        assertEquals(Arrays.asList(2), shown);
    }

    @Test public void closeInsidePostingRemovesTheLatePostedTask() {
        Queue queue = new Queue();
        List<Integer> shown = new ArrayList<>();
        LatestValueDispatcher<Integer> dispatcher = new LatestValueDispatcher<>(queue, shown::add);
        queue.beforePost = dispatcher::close;
        dispatcher.offer(1);
        assertTrue(queue.tasks.isEmpty());
        assertTrue(shown.isEmpty());
    }

    @Test public void closingDuringDeliveryDropsFollowup() {
        Queue queue = new Queue();
        java.util.concurrent.atomic.AtomicReference<LatestValueDispatcher<Integer>> ref = new java.util.concurrent.atomic.AtomicReference<>();
        LatestValueDispatcher<Integer> dispatcher = new LatestValueDispatcher<>(queue, value -> {
            ref.get().offer(2); ref.get().close();
        });
        ref.set(dispatcher);
        dispatcher.offer(1);
        queue.runNext();
        assertTrue(queue.tasks.isEmpty());
    }

    @Test public void obsoleteDeliveryCannotClearNewerPendingWork() {
        Queue queue = new Queue();
        List<Integer> shown = new ArrayList<>();
        LatestValueDispatcher<Integer> dispatcher = new LatestValueDispatcher<>(queue, shown::add);
        dispatcher.offer(1);
        Runnable old = queue.tasks.peek();
        queue.runNext();
        dispatcher.offer(2);
        old.run();
        assertEquals(1, queue.tasks.size());
        queue.runNext();
        assertEquals(Arrays.asList(1, 2), shown);
    }

    private static final class Queue implements LatestValueDispatcher.Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean accept = true;
        Runnable beforePost;
        @Override public boolean post(Runnable task) {
            if (beforePost != null) beforePost.run();
            if (accept) tasks.add(task);
            return accept;
        }
        @Override public void remove(Runnable task) { tasks.remove(task); }
        void runNext() { tasks.remove().run(); }
    }
}
