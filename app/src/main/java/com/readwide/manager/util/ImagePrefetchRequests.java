package com.readwide.manager.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deduplicates bitmap warm-up/foreground work without sharing ownership of bitmap objects. */
public final class ImagePrefetchRequests {
    public static final class Ticket {
        private final long key;
        private boolean started;
        private List<Runnable> completion; // allocate only when a visible request joins
        private Ticket(long key) { this.key = key; }
    }

    private final int capacity;
    private final Map<Long, Ticket> pending = new HashMap<>();

    public ImagePrefetchRequests(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Positive prefetch capacity required");
        this.capacity = capacity;
    }

    public synchronized Ticket acquire(int generation, int index) {
        if (index < 0) return null;
        long key = ((long) generation << 32) | (index & 0xffffffffL);
        if (pending.containsKey(key) || pending.size() >= capacity) return null;
        Ticket ticket = new Ticket(key);
        pending.put(key, ticket);
        return ticket;
    }

    /** A queued warm-up loses ownership if a foreground request takes over first. */
    public synchronized boolean tryStart(Ticket ticket) {
        if (ticket == null || pending.get(ticket.key) != ticket || ticket.started) return false;
        ticket.started = true;
        return true;
    }

    /**
     * Returns ownership, or null after registering a non-blocking completion retry.
     * Queued speculative work is replaced, not waited on. Already-running work
     * publishes to the UI cache before release; only that cache owns the bitmap.
     * Foreground requests are never rejected because the warm-up budget is full.
     */
    public synchronized Ticket acquireForeground(int generation, int index, Runnable onComplete) {
        if (index < 0) throw new IllegalArgumentException("Negative image index");
        long key = ((long) generation << 32) | (index & 0xffffffffL);
        Ticket existing = pending.get(key);
        if (existing != null && existing.started) {
            if (existing.completion == null) existing.completion = new ArrayList<>();
            existing.completion.add(onComplete);
            return null;
        }
        Ticket ticket = new Ticket(key);
        ticket.started = true;
        pending.put(key, ticket);
        return ticket;
    }

    public void release(Ticket ticket) {
        List<Runnable> listeners;
        synchronized (this) {
            if (ticket == null || pending.get(ticket.key) != ticket) return;
            pending.remove(ticket.key);
            listeners = ticket.completion;
            ticket.completion = null;
        }
        notifyCompletion(listeners);
    }

    /** Teardown only; a late completion cannot remove a newer ticket for the same key. */
    public void clear() {
        List<Runnable> listeners = new ArrayList<>();
        synchronized (this) {
            for (Ticket ticket : pending.values()) {
                if (ticket.completion != null) {
                    listeners.addAll(ticket.completion);
                    ticket.completion = null;
                }
            }
            pending.clear();
        }
        notifyCompletion(listeners);
    }

    private static void notifyCompletion(List<Runnable> listeners) {
        if (listeners == null) return;
        for (Runnable listener : listeners) {
            try { listener.run(); }
            catch (RuntimeException ignored) { /* One retired consumer must not strand another. */ }
        }
    }
}
