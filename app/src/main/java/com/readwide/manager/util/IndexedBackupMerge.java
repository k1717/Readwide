package com.readwide.manager.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Stable, indexed same-ID replacement with optional duplicate-location keys. */
final class IndexedBackupMerge {
    private IndexedBackupMerge() { }

    static <T> List<T> merge(List<T> existing, List<T> incoming, Function<T, String> id,
                              Function<T, List<Object>> locations) {
        List<T> result = new ArrayList<>(existing);
        Map<String, Integer> ids = new HashMap<>();
        Map<Object, Integer> counts = new HashMap<>();
        List<List<Object>> keys = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            T value = result.get(i);
            ids.putIfAbsent(id.apply(value), i); // Preserve the old first-ID policy.
            List<Object> entryKeys = locations == null ? Collections.emptyList() : locations.apply(value);
            keys.add(entryKeys);
            adjust(counts, entryKeys, 1);
        }
        for (T value : incoming) {
            String valueId = id.apply(value);
            List<Object> entryKeys = locations == null ? Collections.emptyList() : locations.apply(value);
            Integer index = ids.get(valueId);
            if (index != null) {
                adjust(counts, keys.get(index), -1);
                result.set(index, value); keys.set(index, entryKeys);
                adjust(counts, entryKeys, 1);
            } else {
                boolean duplicate = false;
                for (Object key : entryKeys) if (counts.containsKey(key)) { duplicate = true; break; }
                if (duplicate) continue;
                ids.put(valueId, result.size());
                result.add(value); keys.add(entryKeys); adjust(counts, entryKeys, 1);
            }
        }
        return result;
    }

    private static void adjust(Map<Object, Integer> counts, List<Object> keys, int delta) {
        for (Object key : keys) {
            int count = counts.getOrDefault(key, 0) + delta;
            if (count == 0) counts.remove(key); else counts.put(key, count);
        }
    }
}
