package com.readwide.manager.util;

import java.util.HashMap;
import java.util.Map;

/** Bounds and deduplicates queued/running ordinary bitmap warm-ups, not foreground loads. */
public final class ImagePrefetchRequests {
    public static final class Ticket {
        private final long key;
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

    public synchronized void release(Ticket ticket) {
        if (ticket != null && pending.get(ticket.key) == ticket) pending.remove(ticket.key);
    }

    /** Teardown only; a late completion cannot remove a newer ticket for the same key. */
    public synchronized void clear() { pending.clear(); }
}
