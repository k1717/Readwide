package com.readwide.manager.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Queue ownership only; no bitmap decoding or Android executor lifecycle. */
public class ImagePrefetchRequestsTest {
    @Test public void duplicatesAreRejectedBeforeWorkStarts() {
        ImagePrefetchRequests gate = new ImagePrefetchRequests(4);
        ImagePrefetchRequests.Ticket first = gate.acquire(1, 2);
        assertNotNull(first);
        assertNull(gate.acquire(1, 2));
        gate.release(first);
        assertNotNull(gate.acquire(1, 2));
    }

    @Test public void generationAndIndexBothIdentifyWork() {
        ImagePrefetchRequests gate = new ImagePrefetchRequests(8);
        assertNotNull(gate.acquire(1, 2));
        assertNotNull(gate.acquire(2, 2));
        assertNotNull(gate.acquire(1, 3));
        assertNotNull(gate.acquire(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertNotNull(gate.acquire(0, Integer.MAX_VALUE));
    }

    @Test public void capacityIsReleasedOnCompletionOrRejectedSubmission() {
        ImagePrefetchRequests gate = new ImagePrefetchRequests(1);
        ImagePrefetchRequests.Ticket first = gate.acquire(1, 1);
        assertNull(gate.acquire(1, 2));
        gate.release(first); // Same release path when executor submission is rejected.
        assertNotNull(gate.acquire(1, 2));
    }

    @Test public void lateCompletionAfterClearCannotReleaseReplacement() {
        ImagePrefetchRequests gate = new ImagePrefetchRequests(1);
        ImagePrefetchRequests.Ticket old = gate.acquire(1, 1);
        gate.clear();
        ImagePrefetchRequests.Ticket replacement = gate.acquire(1, 1);
        assertNotNull(replacement);
        gate.release(old);
        assertNull(gate.acquire(1, 1));
        gate.release(replacement);
        assertNotNull(gate.acquire(1, 1));
    }

    @Test public void duplicateCompletionCannotReleaseNewOwner() {
        ImagePrefetchRequests gate = new ImagePrefetchRequests(1);
        ImagePrefetchRequests.Ticket old = gate.acquire(1, 1);
        gate.release(old);
        assertNotNull(gate.acquire(1, 1));
        gate.release(old);
        gate.release(null);
        assertNull(gate.acquire(1, 1));
    }

    @Test public void invalidInputsDoNotConsumeCapacity() {
        ImagePrefetchRequests gate = new ImagePrefetchRequests(1);
        assertNull(gate.acquire(1, -1));
        assertNotNull(gate.acquire(1, 0));
        try {
            new ImagePrefetchRequests(0);
            fail("Nonpositive capacity accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void separateQualityGatesDoNotSuppressEachOther() {
        ImagePrefetchRequests neighbor = new ImagePrefetchRequests(1);
        ImagePrefetchRequests companion = new ImagePrefetchRequests(1);
        assertNotNull(neighbor.acquire(1, 2));
        assertNotNull(companion.acquire(1, 2));
    }

    @Test public void concurrentRequestsHaveOneOwner() throws Exception {
        ImagePrefetchRequests gate = new ImagePrefetchRequests(8);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ImagePrefetchRequests.Ticket>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) futures.add(workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return gate.acquire(7, 8);
            }));
            start.countDown();
            int owners = 0;
            for (Future<ImagePrefetchRequests.Ticket> result : futures) {
                if (result.get(5, TimeUnit.SECONDS) != null) owners++;
            }
            assertEquals(1, owners);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }
}
