package com.readwide.manager.util;

import java.util.Objects;
import java.util.function.Consumer;

/** Coalesces worker updates into one pending UI delivery; consumers run outside its lock. */
public final class LatestValueDispatcher<T> implements AutoCloseable {
    public interface Scheduler {
        boolean post(Runnable task);
        void remove(Runnable task);
    }

    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Consumer<T> consumer;
    private T latest;
    private Delivery pending;
    private boolean closed;

    public LatestValueDispatcher(Scheduler scheduler, Consumer<T> consumer) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.consumer = Objects.requireNonNull(consumer);
    }

    public void offer(T value) {
        Objects.requireNonNull(value);
        Delivery task = null;
        synchronized (lock) {
            if (closed) return;
            latest = value;
            if (pending == null) { task = new Delivery(); pending = task; }
        }
        if (task != null) post(task);
    }

    private void post(Delivery task) {
        boolean accepted = false;
        try {
            accepted = scheduler.post(task);
        } finally {
            boolean obsolete;
            synchronized (lock) {
                obsolete = closed || pending != task;
                if (!accepted && pending == task) pending = null;
            }
            // Covers closing between reserving a task and actually posting it.
            if (accepted && obsolete) scheduler.remove(task);
        }
    }

    @Override public void close() {
        Delivery task;
        synchronized (lock) {
            closed = true; latest = null; task = pending; pending = null;
        }
        if (task != null) scheduler.remove(task);
    }

    private final class Delivery implements Runnable {
        @Override public void run() {
            T value;
            synchronized (lock) {
                if (closed || pending != this) return;
                value = latest; latest = null;
            }
            try {
                consumer.accept(value);
            } finally {
                Delivery next = null;
                synchronized (lock) {
                    if (pending == this) {
                        pending = null;
                        if (!closed && latest != null) { next = new Delivery(); pending = next; }
                    }
                }
                if (next != null) post(next);
            }
        }
    }
}
