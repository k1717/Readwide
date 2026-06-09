package com.textview.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ImageSequenceHandoffStore {
    interface Provider {
        @Nullable Sequence build();
    }

    static final class Sequence {
        final ArrayList<String> paths;
        final ArrayList<String> displayNames;
        final ArrayList<String> entryPaths;

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                 @Nullable ArrayList<String> entryPaths) {
            this.paths = paths;
            this.displayNames = displayNames != null ? displayNames : new ArrayList<>();
            this.entryPaths = entryPaths != null ? entryPaths : new ArrayList<>();
        }
    }

    private static final ConcurrentHashMap<String, Provider> PROVIDERS = new ConcurrentHashMap<>();

    private ImageSequenceHandoffStore() {}

    @NonNull
    static String put(@NonNull Provider provider) {
        String token = UUID.randomUUID().toString();
        PROVIDERS.put(token, provider);
        return token;
    }

    @Nullable
    static Sequence consume(@Nullable String token) {
        if (token == null || token.trim().isEmpty()) return null;
        Provider provider = PROVIDERS.remove(token);
        if (provider == null) return null;
        try {
            return provider.build();
        } catch (Exception ignored) {
            return null;
        }
    }

    static void discard(@Nullable String token) {
        if (token == null || token.trim().isEmpty()) return;
        PROVIDERS.remove(token);
    }
}
