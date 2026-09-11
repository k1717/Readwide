package com.readwide.manager.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Visible-holder subscriptions. Keys include dataset generation and source/size identity. */
public final class VisibleThumbnailBindings<T> {
    private final Map<T, String> keys = new IdentityHashMap<>();
    private final Map<String, Set<T>> holders = new HashMap<>();

    public synchronized void bind(T holder, String key) {
        if (holder == null || key == null) throw new IllegalArgumentException("Missing thumbnail binding");
        if (key.equals(keys.get(holder))) return;
        unbind(holder);
        keys.put(holder, key);
        holders.computeIfAbsent(key, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(holder);
    }

    public synchronized String unbind(T holder) {
        String key = keys.remove(holder);
        if (key == null) return null;
        Set<T> subscribers = holders.get(key);
        subscribers.remove(holder);
        if (subscribers.isEmpty()) holders.remove(key);
        return key;
    }

    public synchronized boolean hasSubscribers(String key) {
        return holders.containsKey(key);
    }

    public synchronized List<T> snapshot(String key) {
        Set<T> subscribers = holders.get(key);
        return subscribers == null ? Collections.emptyList() : new ArrayList<>(subscribers);
    }

    public synchronized void clear() {
        keys.clear();
        holders.clear();
    }
}
