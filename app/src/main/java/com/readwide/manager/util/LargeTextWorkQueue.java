package com.readwide.manager.util;

import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One TXT worker: foreground first, FIFO within a priority, cooperative prefetch yielding. */
public final class LargeTextWorkQueue extends ThreadPoolExecutor {
    private final AtomicLong order = new AtomicLong();
    private final AtomicInteger foreground = new AtomicInteger();

    public LargeTextWorkQueue() {
        super(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>());
    }

    @Override public void execute(Runnable task) { enqueue(task, false); }

    public void executeForeground(Runnable task) { enqueue(task, true); }

    public boolean hasForegroundWork() { return foreground.get() > 0; }

    private void enqueue(Runnable task, boolean visible) {
        Work work = new Work(task, visible, order.getAndIncrement());
        if (visible) foreground.incrementAndGet();
        try { super.execute(work); }
        catch (RuntimeException | Error failure) { work.release(); throw failure; }
    }

    @Override public List<Runnable> shutdownNow() {
        List<Runnable> abandoned = super.shutdownNow();
        for (Runnable task : abandoned) ((Work) task).release();
        return abandoned;
    }

    private final class Work implements Runnable, Comparable<Work> {
        final Runnable task;
        final boolean visible;
        final long sequence;
        final AtomicBoolean released = new AtomicBoolean();

        Work(Runnable task, boolean visible, long sequence) {
            if (task == null) throw new NullPointerException("task");
            this.task = task;
            this.visible = visible;
            this.sequence = sequence;
        }

        @Override public int compareTo(Work other) {
            int priority = Boolean.compare(other.visible, visible);
            return priority != 0 ? priority : Long.compare(sequence, other.sequence);
        }

        @Override public void run() {
            try { task.run(); } finally { release(); }
        }

        void release() {
            if (visible && released.compareAndSet(false, true)) foreground.decrementAndGet();
        }
    }
}
