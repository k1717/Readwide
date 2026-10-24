package com.readwide.manager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Submission fixtures only; no document I/O or real memory exhaustion. */
@RunWith(AndroidJUnit4.class)
public class ReaderContentsSubmissionInstrumentedTest {
    @Test public void rejectionReportsFailureWithoutRunningQueuedEdit() {
        assertRejected(new RejectedExecutionException("stopped"));
    }

    @Test public void workerAllocationFailureCancelsQueuedScan() {
        assertRejected(new OutOfMemoryError("synthetic startup"));
    }

    @Test public void workerStackFailureCancelsQueuedEdit() {
        assertRejected(new StackOverflowError("synthetic startup"));
    }

    @Test public void acceptedEditCanFinishAfterOrderlyShutdown() throws Exception {
        Queue worker = new Queue(null);
        int[] calls = {0};
        Future<?> accepted = ReaderContentsController.submitTask(worker,
                () -> calls[0]++, failure -> fail("unexpected rejection"));
        assertNotNull(accepted);
        worker.shutdown();
        worker.queued.run();
        accepted.get();
        assertEquals(1, calls[0]);
    }

    private static void assertRejected(Throwable error) {
        Queue worker = new Queue(error);
        int[] calls = {0}, failures = {0};
        Future<?> rejected = ReaderContentsController.submitTask(worker, () -> calls[0]++, failure -> {
            assertSame(error, failure);
            failures[0]++;
        });
        assertNull(rejected);
        assertNotNull(worker.queued);
        worker.queued.run(); // An executor may retain a task when starting a thread fails.
        assertEquals(0, calls[0]);
        assertEquals(1, failures[0]);
    }

    private static final class Queue extends AbstractExecutorService {
        final Throwable failure;
        Runnable queued;
        boolean stopped;
        Queue(Throwable failure) { this.failure = failure; }
        @Override public void execute(Runnable task) {
            queued = task;
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
        }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
}
