package com.readwide.manager.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class LargeTextCursorSessionTest {
    private static BufferedReader tracked(AtomicInteger closes, CountDownLatch closed) {
        return new BufferedReader(new StringReader("text")) {
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                super.close();
                closed.countDown();
            }
        };
    }

    @Test public void retirementReturnsDuringActiveReadAndCannotReopen() throws Exception {
        LargeTextPartitionReader.ForwardCursorSession session = new LargeTextPartitionReader.ForwardCursorSession();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        try {
            Future<?> read = workers.submit(() -> session.read(cursor -> {
                cursor.reader = tracked(closes, closed);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("read release timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                return null;
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            workers.submit(session::closeAsync).get(2, TimeUnit.SECONDS);
            assertEquals(0, closes.get()); // do not close a reader still in use
            release.countDown();
            read.get(2, TimeUnit.SECONDS);
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            session.closeAsync();
            assertEquals(1, closes.get());
            assertThrows(InterruptedIOException.class, () -> session.read(cursor -> {
                fail("retired session must not execute another read"); return null;
            }));
        } finally { release.countDown(); session.closeAsync(); workers.shutdownNow(); }
    }

    @Test public void idleCursorIsReusedThenClosedWithoutActivityExecutor() throws Exception {
        LargeTextPartitionReader.ForwardCursorSession session = new LargeTextPartitionReader.ForwardCursorSession();
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        try {
            LargeTextPartitionReader.ForwardCursor first = session.read(cursor -> {
                cursor.reader = tracked(closes, closed); return cursor;
            });
            assertSame(first, session.read(cursor -> cursor));
            session.closeAsync();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
        } finally { session.closeAsync(); }
    }

    @Test public void preInterruptedReadDoesNotRunOrConsumeInterruption() throws Exception {
        LargeTextPartitionReader.ForwardCursorSession session = new LargeTextPartitionReader.ForwardCursorSession();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> session.read(cursor -> {
                fail("cancelled read must not run"); return null;
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); session.closeAsync(); }
    }
}
