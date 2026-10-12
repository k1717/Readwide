package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class FileOperationProgressTest {
    @Test public void completionReleasesPausedWaiter() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.setPaused(true);
        AtomicBoolean result = new AtomicBoolean();
        Thread waiter = new Thread(() -> result.set(progress.checkpoint()));
        waiter.start();
        try {
            awaitPaused(waiter);
            progress.markComplete();
            waiter.join(2000);
            assertFalse(waiter.isAlive());
            assertTrue(result.get());
            assertTrue(progress.isComplete());
            assertFalse(progress.isPaused());
        } finally { stop(waiter); }
    }

    @Test public void interruptionOfOneWaiterReleasesAllWaiters() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.setPaused(true);
        AtomicBoolean firstResult = new AtomicBoolean(true), secondResult = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread first = new Thread(() -> {
            firstResult.set(progress.checkpoint());
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        Thread second = new Thread(() -> secondResult.set(progress.checkpoint()));
        first.start(); second.start();
        try {
            awaitPaused(first); awaitPaused(second);
            first.interrupt();
            first.join(2000); second.join(2000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive());
            assertFalse(firstResult.get()); assertFalse(secondResult.get());
            assertTrue(interrupted.get()); assertTrue(progress.isCancelled());
        } finally { stop(first); stop(second); }
    }

    @Test public void completedProgressCannotBePausedUntilExplicitlyRestarted() {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.markComplete(); progress.setPaused(true);
        assertFalse(progress.isPaused());
        progress.setTotalBytes(20); progress.setPaused(true);
        assertTrue(progress.isPaused());
        progress.cancel(); progress.setTotalBytes(30); progress.setPaused(true);
        assertFalse(progress.isPaused()); assertTrue(progress.isComplete());
        assertFalse(progress.checkpoint());
    }

    @Test public void cancellationReleasesPausedWaiterWithoutReportingSuccess() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.setPaused(true);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> result.set(progress.checkpoint()));
        waiter.start();
        try {
            awaitPaused(waiter); progress.cancel(); waiter.join(2000);
            assertFalse(waiter.isAlive()); assertFalse(result.get());
        } finally { stop(waiter); }
    }

    @Test public void byteAccountingStillSaturatesAndResetsForNextEntry() {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.addDoneBytes(Long.MAX_VALUE); progress.addDoneBytes(1);
        assertEquals(Long.MAX_VALUE, progress.snapshot().doneBytes);
        progress.setTotalBytes(20); progress.addDoneBytes(30);
        assertEquals(20, progress.snapshot().doneBytes); assertEquals(100, progress.snapshot().percent());
        progress.markComplete(); progress.setTotalBytes(10);
        assertEquals(0, progress.snapshot().doneBytes); assertFalse(progress.isComplete());
    }

    private static void awaitPaused(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (thread.getState() != Thread.State.WAITING && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.WAITING, thread.getState());
    }

    private static void stop(Thread thread) throws InterruptedException {
        thread.interrupt(); thread.join(2000);
    }
}
