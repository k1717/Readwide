package com.readwide.manager.util;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TaskSubmissionTest {
    @Test public void acceptedTaskRunsOnce() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        FutureTask<Void> task = TaskSubmission.submit(queue::add, calls::incrementAndGet);
        assertFalse(task.isDone());
        queue.remove().run();
        task.run();
        assertNull(task.get());
        assertEquals(1, calls.get());
    }

    @Test public void retainedRejectedTaskCannotRunAfterRetry() {
        assertRejected(new RejectedExecutionException("rejected"));
        assertRejected(new OutOfMemoryError("worker allocation"));
        assertRejected(new StackOverflowError("worker startup"));
    }

    private static void assertRejected(Throwable failure) {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        try {
            TaskSubmission.submit(task -> {
                queue.add(task);
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw (Error) failure;
            }, () -> calls.addAndGet(100));
            fail("Startup failure must be reported");
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError expected) {
            assertSame(failure, expected);
        }
        TaskSubmission.submit(queue::add, calls::incrementAndGet);
        Runnable rejected = queue.remove();
        assertTrue(((FutureTask<?>) rejected).isCancelled());
        rejected.run();
        queue.remove().run();
        assertEquals(1, calls.get());
    }
}
