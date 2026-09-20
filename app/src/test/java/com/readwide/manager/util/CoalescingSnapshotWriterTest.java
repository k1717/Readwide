package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class CoalescingSnapshotWriterTest {
    @Test public void requestsDeferCaptureEncodingAndIoAndCoalesceToLatest() {
        Fixture f = new Fixture();
        f.request(1, "page 1");
        f.request(2, "page 2");
        f.request(3, "page 3");
        assertEquals(1, f.executor.tasks.size());
        assertEquals(0, f.captures);
        assertEquals(0, f.encodes);
        assertTrue(f.writes.isEmpty());
        f.executor.runNext();
        assertEquals(Arrays.asList("page 3"), f.writes);
        assertEquals(1, f.captures);
        assertEquals(1, f.encodes);
        assertTrue(f.executor.tasks.isEmpty());
    }

    @Test public void newerRequestDuringEncodingSkipsStaleCommit() {
        Fixture f = new Fixture();
        f.encoder = value -> {
            if (value.equals("old")) f.request(2, "new");
            return value;
        };
        f.request(1, "old");
        f.executor.runNext();
        assertEquals(Arrays.asList("new"), f.writes);
        assertTrue(f.executor.tasks.isEmpty());
    }

    @Test public void requestDuringWriteDrainsLatestWithoutAnotherQueuedTask() {
        Fixture f = new Fixture();
        f.sink = value -> {
            f.writes.add(value);
            if (value.equals("old")) f.request(2, "new");
        };
        f.request(1, "old");
        f.executor.runNext();
        assertEquals(Arrays.asList("old", "new"), f.writes);
        assertTrue(f.executor.tasks.isEmpty());
    }

    @Test public void lifecycleCheckpointSupersedesQueuedPageTurn() throws Exception {
        Fixture f = new Fixture();
        f.request(1, "queued page");
        f.writer.writeNow(snapshot(2, "pause anchor"));
        f.executor.runNext();
        assertEquals(Arrays.asList("pause anchor"), f.writes);
        assertEquals(0, f.captures);
    }

    @Test public void synchronousDeleteDuringOldEncodingCannotRestoreDeletedHistory() {
        Fixture f = new Fixture();
        f.encoder = value -> {
            if (value.equals("old history")) {
                f.writer.writeNow(snapshot(2, "empty history"));
            }
            return value;
        };
        f.request(1, "old history");
        f.executor.runNext();
        assertEquals(Arrays.asList("empty history"), f.writes);
        assertTrue(f.failures.isEmpty());
    }

    @Test public void olderDirectCheckpointCannotReplaceNewerOne() throws Exception {
        Fixture f = new Fixture();
        f.writer.writeNow(snapshot(5, "imported history"));
        f.writer.writeNow(snapshot(4, "old history"));
        assertEquals(Arrays.asList("imported history"), f.writes);
    }

    @Test public void unchangedFailedWriteDoesNotSpinAndLaterRequestCanRetry() {
        Fixture f = new Fixture();
        f.sink = value -> { throw new IOException("disk unavailable"); };
        f.request(1, "history");
        f.executor.runNext();
        assertEquals(1, f.failures.size());
        assertEquals(1, f.captures);
        assertTrue(f.executor.tasks.isEmpty());
        f.sink = f.writes::add;
        f.request(1, "history");
        f.executor.runNext();
        assertEquals(Arrays.asList("history"), f.writes);
    }

    @Test public void captureFailureRetiresTokenAndAllowsLaterRetry() {
        Fixture f = new Fixture();
        f.failCapture = true;
        f.request(1, "history");
        f.executor.runNext();
        assertEquals(1, f.failures.size());
        assertTrue(f.executor.tasks.isEmpty());
        f.failCapture = false;
        f.request(2, "new history");
        f.executor.runNext();
        assertEquals(Arrays.asList("new history"), f.writes);
    }

    @Test public void rejectedSubmissionReleasesTokenForRetry() {
        Fixture f = new Fixture();
        f.executor.reject = true;
        f.request(1, "history");
        assertEquals(1, f.failures.size());
        assertTrue(f.executor.tasks.isEmpty());
        f.executor.reject = false;
        f.request(2, "new history");
        f.executor.runNext();
        assertEquals(Arrays.asList("new history"), f.writes);
    }

    @Test public void encodingFailureCanBeRetriedBySynchronousCheckpoint() throws Exception {
        Fixture f = new Fixture();
        f.encoder = value -> { throw new IOException("encoding failed"); };
        f.request(1, "history");
        f.executor.runNext();
        assertEquals(1, f.failures.size());
        assertTrue(f.writes.isEmpty());
        f.encoder = value -> value;
        f.writer.writeNow(snapshot(2, "pause anchor"));
        assertEquals(Arrays.asList("pause anchor"), f.writes);
    }

    @Test public void requestDoesNotWaitForInFlightFileWrite() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch latestWritten = new CountDownLatch(1);
        AtomicReference<CoalescingSnapshotWriter.Snapshot<String>> source =
                new AtomicReference<>(snapshot(1, "old"));
        AtomicReference<Exception> failure = new AtomicReference<>();
        CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                pool, source::get, value -> value, value -> {
                    if (value.equals("old")) {
                        writing.countDown();
                        if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new IOException("write timeout");
                    } else latestWritten.countDown();
                }, failure::set);
        try {
            writer.request(1);
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            source.set(snapshot(2, "new"));
            Future<?> request = pool.submit(() -> writer.request(2));
            // This must finish while the sink still owns its separate I/O lock.
            request.get(2, TimeUnit.SECONDS);
            assertEquals(1L, releaseWrite.getCount());
            releaseWrite.countDown();
            assertTrue(latestWritten.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        } finally {
            releaseWrite.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void unexpectedErrorDoesNotPermanentlyRetainWorkerToken() {
        Fixture f = new Fixture();
        f.encoder = value -> { throw new AssertionError("unexpected encoder failure"); };
        f.request(1, "old");
        try {
            f.executor.runNext();
            fail("Expected encoder error");
        } catch (AssertionError expected) {
            assertEquals("unexpected encoder failure", expected.getMessage());
        }
        f.encoder = value -> value;
        f.request(2, "new");
        f.executor.runNext();
        assertEquals(Arrays.asList("new"), f.writes);
    }

    @Test public void committedRevisionIsNotWrittenTwice() throws Exception {
        Fixture f = new Fixture();
        f.request(1, "history");
        f.executor.runNext();
        f.request(1, "history");
        f.writer.writeNow(snapshot(1, "history"));
        assertTrue(f.executor.tasks.isEmpty());
        assertEquals(Arrays.asList("history"), f.writes);
    }

    private static CoalescingSnapshotWriter.Snapshot<String> snapshot(long revision, String value) {
        return new CoalescingSnapshotWriter.Snapshot<>(revision, value);
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;

        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("rejected");
            tasks.add(task);
        }

        void runNext() { tasks.removeFirst().run(); }
    }

    private static final class Fixture {
        final ManualExecutor executor = new ManualExecutor();
        final List<String> writes = new ArrayList<>();
        final List<Exception> failures = new ArrayList<>();
        CoalescingSnapshotWriter.Encoder<String> encoder = value -> value;
        CoalescingSnapshotWriter.Sink sink = writes::add;
        long revision;
        String value;
        int captures;
        int encodes;
        boolean failCapture;
        final CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                executor, () -> {
                    captures++;
                    if (failCapture) throw new IOException("capture failed");
                    return snapshot(revision, value);
                }, captured -> {
                    encodes++;
                    return encoder.encode(captured);
                }, encoded -> sink.write(encoded), failures::add);

        void request(long nextRevision, String nextValue) {
            revision = nextRevision;
            value = nextValue;
            writer.request(revision);
        }
    }
}
