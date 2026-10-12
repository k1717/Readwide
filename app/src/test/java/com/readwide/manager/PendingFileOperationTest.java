package com.readwide.manager;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Exercises queued-input ownership without constructing an Activity. */
public class PendingFileOperationTest {
    @Test public void discardedClipboardTaskReleasesItsClaimWithoutRemovingRetryItem() {
        com.readwide.manager.util.FileClipboardController clipboard = new com.readwide.manager.util.FileClipboardController();
        java.io.File source = new java.io.File("unused") {
            @Override public boolean exists() { return true; }
            @Override public boolean isFile() { return true; }
        };
        clipboard.start(source, true);
        com.readwide.manager.util.FileClipboardController.PendingItem item = clipboard.getActiveItem();
        java.util.List<com.readwide.manager.util.FileClipboardController.PendingItem> claimed = java.util.Collections.singletonList(item);
        clipboard.setItemsInProgress(claimed, true);
        MainActivity.PendingFileOperation task = new MainActivity.PendingFileOperation(
                () -> fail("discarded paste must not start"), () -> clipboard.setItemsInProgress(claimed, false));
        task.discard(); task.run();
        assertFalse(clipboard.isInProgress());
        assertTrue(clipboard.isPending(item));
    }

    @Test public void discardBeforeStartClearsPasswordAndPreventsExecution() {
        char[] password = {'a', 'b'}; int[] calls = {0, 0};
        MainActivity.PendingFileOperation task = new MainActivity.PendingFileOperation(
                () -> calls[0]++, () -> { calls[1]++; PasswordChars.clear(password); });
        task.discard(); task.discard(); task.run();
        assertArrayEquals(new char[2], password); assertArrayEquals(new int[]{0, 1}, calls);
    }

    @Test public void startedWorkerExclusivelyOwnsItsInputCleanup() {
        int[] calls = {0, 0};
        MainActivity.PendingFileOperation task = new MainActivity.PendingFileOperation(
                () -> calls[0]++, () -> calls[1]++);
        task.run(); task.run(); task.discard();
        assertArrayEquals(new int[]{1, 0}, calls);
    }

    @Test public void workerFailureStaysPrimaryAndCannotTriggerDiscardCleanup() {
        AssertionError failure = new AssertionError("worker"); int[] cleanups = {0};
        MainActivity.PendingFileOperation task = new MainActivity.PendingFileOperation(
                () -> { throw failure; }, () -> cleanups[0]++);
        assertSame(failure, assertThrows(AssertionError.class, task::run));
        task.discard(); task.run(); assertEquals(0, cleanups[0]);
    }

    @Test public void failedDiscardStillPreventsLaterExecutionOrDoubleCleanup() {
        IllegalStateException failure = new IllegalStateException("cleanup"); int[] runs = {0};
        MainActivity.PendingFileOperation task = new MainActivity.PendingFileOperation(
                () -> runs[0]++, () -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, task::discard));
        task.discard(); task.run(); assertEquals(0, runs[0]);
    }

    @Test public void concurrentRunAndDiscardClaimExactlyOneOwner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger(), discards = new AtomicInteger();
        MainActivity.PendingFileOperation task = new MainActivity.PendingFileOperation(
                runs::incrementAndGet, discards::incrementAndGet);
        Thread run = new Thread(() -> { await(start); task.run(); });
        Thread discard = new Thread(() -> { await(start); task.discard(); });
        run.start(); discard.start(); start.countDown();
        try {
            run.join(2000); discard.join(2000);
            assertFalse(run.isAlive()); assertFalse(discard.isAlive());
            assertEquals(1, runs.get() + discards.get());
            task.run(); task.discard(); assertEquals(1, runs.get() + discards.get());
        } finally { run.interrupt(); discard.interrupt(); run.join(2000); discard.join(2000); }
    }

    @Test public void shutdownReturnsNeverStartedTaskForPasswordCleanup() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch active = new CountDownLatch(1), release = new CountDownLatch(1);
        char[] password = {'s', 'e', 'c', 'r', 'e', 't'};
        MainActivity.PendingFileOperation task = new MainActivity.PendingFileOperation(
                () -> fail("queued work must not start"), () -> PasswordChars.clear(password));
        try {
            executor.execute(() -> { active.countDown(); await(release); });
            assertTrue(active.await(2, TimeUnit.SECONDS)); executor.execute(task);
            List<Runnable> abandoned = executor.shutdownNow();
            assertEquals(1, abandoned.size()); assertSame(task, abandoned.get(0));
            ((MainActivity.PendingFileOperation) abandoned.get(0)).discard();
            assertArrayEquals(new char[6], password);
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
