package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.model.LargeTextLinePartitionResult;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small synchronized LRU cache for large TXT runtime partitions.
 *
 * Normal partitions are used for exact tap/page/bookmark targets.  Manual handoff
 * partitions may include lookbehind for scroll smoothness.  Keeping these caches
 * separate protects the invariant that exact jumps resolve only to the canonical
 * body owner while manual scrolling can still use lookbehind/lookahead buffers.
 */
public final class LargeTextPartitionCache {
    private static final int NORMAL_CACHE_SIZE = 7;
    private static final int MANUAL_HANDOFF_CACHE_SIZE = 3;

    private final Object lock = new Object();
    private long generation;

    public long generation() { synchronized (lock) { return generation; } }
    public boolean isCurrent(long expected) { synchronized (lock) { return generation == expected; } }
    private final LinkedHashMap<Integer, LargeTextLinePartitionResult> normalCache =
            new LinkedHashMap<Integer, LargeTextLinePartitionResult>(NORMAL_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, LargeTextLinePartitionResult> eldest) {
                    return size() > NORMAL_CACHE_SIZE;
                }
            };
    private final LinkedHashMap<Integer, LargeTextLinePartitionResult> manualHandoffCache =
            new LinkedHashMap<Integer, LargeTextLinePartitionResult>(MANUAL_HANDOFF_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, LargeTextLinePartitionResult> eldest) {
                    return size() > MANUAL_HANDOFF_CACHE_SIZE;
                }
            };
    /** Identity, rather than only a start line, prevents late cleanup from retiring a replacement. */
    public static final class PrefetchTicket {
        private final long cacheGeneration;
        private final int switchGeneration;
        private final int startLine;
        private final boolean manualHandoff;

        private PrefetchTicket(long cacheGeneration, int switchGeneration,
                               int startLine, boolean manualHandoff) {
            this.cacheGeneration = cacheGeneration;
            this.switchGeneration = switchGeneration;
            this.startLine = startLine;
            this.manualHandoff = manualHandoff;
        }
    }

    private final Map<Integer, PrefetchTicket> pendingNormalPrefetches = new HashMap<>();
    private final Map<Integer, PrefetchTicket> pendingManualHandoffPrefetches = new HashMap<>();

    public void clear() {
        synchronized (lock) {
            generation++;
            normalCache.clear();
            manualHandoffCache.clear();
            pendingNormalPrefetches.clear();
            pendingManualHandoffPrefetches.clear();
        }
    }

    public void cache(@NonNull LargeTextLinePartitionResult partition) {
        synchronized (lock) {
            if (partition.includesLookbehind) {
                manualHandoffCache.put(partition.startLine, partition);
                pendingManualHandoffPrefetches.remove(partition.startLine);
            } else {
                normalCache.put(partition.startLine, partition);
                pendingNormalPrefetches.remove(partition.startLine);
            }
        }
    }

    /** Commit and epoch validation share the same lock as clear(). */
    public boolean cacheIfCurrent(long expected, @NonNull LargeTextLinePartitionResult partition) {
        synchronized (lock) {
            if (generation != expected) return false;
            cache(partition);
            return true;
        }
    }

    @Nullable
    public LargeTextLinePartitionResult getNormalByStartLine(int startLine, int partitionLines) {
        int normalizedStart = LargeTextContinuityMath.partitionStartLineForLine(startLine, partitionLines);
        synchronized (lock) {
            return normalCache.get(normalizedStart);
        }
    }

    @Nullable
    public LargeTextLinePartitionResult getManualHandoffByStartLine(int startLine, int partitionLines) {
        int normalizedStart = LargeTextContinuityMath.partitionStartLineForLine(startLine, partitionLines);
        synchronized (lock) {
            return manualHandoffCache.get(normalizedStart);
        }
    }

    @Nullable
    public LargeTextLinePartitionResult getNormalOwnerForChar(int absoluteCharPosition) {
        int target = Math.max(0, absoluteCharPosition);
        synchronized (lock) {
            Integer owner = null;
            for (Map.Entry<Integer, LargeTextLinePartitionResult> entry : normalCache.entrySet()) {
                if (LargeTextContinuityMath.ownsAbsoluteChar(entry.getValue(), target)) {
                    owner = entry.getKey();
                    break;
                }
            }
            // Promote a hit only after ending iteration: access-order get mutates the map.
            return owner != null ? normalCache.get(owner) : null;
        }
    }

    @Nullable
    public PrefetchTicket acquirePrefetch(int startLine, int partitionLines,
                                         boolean manualHandoff, int switchGeneration) {
        int normalizedStart = LargeTextContinuityMath.partitionStartLineForLine(startLine, partitionLines);
        synchronized (lock) {
            Map<Integer, LargeTextLinePartitionResult> cache = manualHandoff ? manualHandoffCache : normalCache;
            if (cache.containsKey(normalizedStart)) return null;
            Map<Integer, PrefetchTicket> pending = pendingFor(manualHandoff);
            PrefetchTicket previous = pending.get(normalizedStart);
            if (previous != null && previous.switchGeneration == switchGeneration) return null;
            // A new switch can replace a stale reservation even if its worker is still queued.
            PrefetchTicket ticket = new PrefetchTicket(generation, switchGeneration, normalizedStart, manualHandoff);
            pending.put(normalizedStart, ticket);
            return ticket;
        }
    }

    public boolean ownsPrefetch(@Nullable PrefetchTicket ticket) {
        synchronized (lock) {
            return ticket != null && generation == ticket.cacheGeneration
                    && pendingFor(ticket.manualHandoff).get(ticket.startLine) == ticket;
        }
    }

    /** Publish only the matching request's result; foreground cache writes supersede prefetch. */
    public boolean completePrefetch(@NonNull PrefetchTicket ticket,
                                    @NonNull LargeTextLinePartitionResult partition) {
        synchronized (lock) {
            if (!ownsPrefetch(ticket) || partition.startLine != ticket.startLine
                    || partition.includesLookbehind != ticket.manualHandoff) return false;
            cache(partition);
            return true;
        }
    }

    public void releasePrefetch(@Nullable PrefetchTicket ticket) {
        synchronized (lock) {
            if (ownsPrefetch(ticket)) pendingFor(ticket.manualHandoff).remove(ticket.startLine);
        }
    }

    private Map<Integer, PrefetchTicket> pendingFor(boolean manualHandoff) {
        return manualHandoff ? pendingManualHandoffPrefetches : pendingNormalPrefetches;
    }
}
