package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.model.LargeTextLinePartitionResult;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
    private final Set<Integer> pendingNormalPrefetches = new HashSet<>();
    private final Set<Integer> pendingManualHandoffPrefetches = new HashSet<>();

    public void clear() {
        synchronized (lock) {
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
            for (LargeTextLinePartitionResult partition : normalCache.values()) {
                if (LargeTextContinuityMath.ownsAbsoluteChar(partition, target)) {
                    return partition;
                }
            }
            return null;
        }
    }

    public boolean markNormalPrefetchPending(int startLine, int partitionLines) {
        int normalizedStart = LargeTextContinuityMath.partitionStartLineForLine(startLine, partitionLines);
        synchronized (lock) {
            if (normalCache.containsKey(normalizedStart)
                    || pendingNormalPrefetches.contains(normalizedStart)) {
                return false;
            }
            pendingNormalPrefetches.add(normalizedStart);
            return true;
        }
    }

    public boolean markManualHandoffPrefetchPending(int startLine, int partitionLines) {
        int normalizedStart = LargeTextContinuityMath.partitionStartLineForLine(startLine, partitionLines);
        synchronized (lock) {
            if (manualHandoffCache.containsKey(normalizedStart)
                    || pendingManualHandoffPrefetches.contains(normalizedStart)) {
                return false;
            }
            pendingManualHandoffPrefetches.add(normalizedStart);
            return true;
        }
    }

    public void unmarkNormalPrefetchPending(int startLine, int partitionLines) {
        int normalizedStart = LargeTextContinuityMath.partitionStartLineForLine(startLine, partitionLines);
        synchronized (lock) {
            pendingNormalPrefetches.remove(normalizedStart);
        }
    }

    public void unmarkManualHandoffPrefetchPending(int startLine, int partitionLines) {
        int normalizedStart = LargeTextContinuityMath.partitionStartLineForLine(startLine, partitionLines);
        synchronized (lock) {
            pendingManualHandoffPrefetches.remove(normalizedStart);
        }
    }
}
