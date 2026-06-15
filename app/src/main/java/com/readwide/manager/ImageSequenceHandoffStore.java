package com.readwide.manager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ImageSequenceHandoffStore {
    interface Provider {
        @Nullable Sequence build();
    }

    interface ClearableProvider extends Provider {
        void clear();
    }

    static final class Sequence {
        final ArrayList<String> paths;
        final ArrayList<String> displayNames;
        final ArrayList<String> entryPaths;
        @Nullable final char[] archivePassword;

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                 @Nullable ArrayList<String> entryPaths) {
            this(paths, displayNames, entryPaths, null);
        }

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                 @Nullable ArrayList<String> entryPaths,
                 @Nullable char[] archivePassword) {
            this.paths = paths;
            this.displayNames = displayNames != null ? displayNames : new ArrayList<>();
            this.entryPaths = entryPaths != null ? entryPaths : new ArrayList<>();
            this.archivePassword = PasswordChars.cloneOf(archivePassword);
        }

        void clearSensitiveData() {
            PasswordChars.clear(archivePassword);
        }
    }

    private static final long PROVIDER_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_PROVIDER_COUNT = 16;

    private static final class ProviderRecord {
        @NonNull final Provider provider;
        final long createdAtMs;

        ProviderRecord(@NonNull Provider provider, long createdAtMs) {
            this.provider = provider;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final ConcurrentHashMap<String, ProviderRecord> PROVIDERS = new ConcurrentHashMap<>();

    private ImageSequenceHandoffStore() {}

    @NonNull
    static String put(@NonNull Provider provider) {
        pruneExpiredAndOverflow();
        String token = UUID.randomUUID().toString();
        PROVIDERS.put(token, new ProviderRecord(provider, System.currentTimeMillis()));
        pruneExpiredAndOverflow();
        return token;
    }

    @Nullable
    static Sequence consume(@Nullable String token) {
        if (token == null || token.trim().isEmpty()) return null;
        pruneExpiredAndOverflow();
        ProviderRecord record = PROVIDERS.remove(token);
        if (record == null) return null;
        try {
            return record.provider.build();
        } catch (Exception ignored) {
            return null;
        } finally {
            clearProvider(record.provider);
        }
    }

    static void discard(@Nullable String token) {
        if (token == null || token.trim().isEmpty()) return;
        ProviderRecord record = PROVIDERS.remove(token);
        if (record != null) clearProvider(record.provider);
    }

    private static void pruneExpiredAndOverflow() {
        long now = System.currentTimeMillis();
        for (String token : new ArrayList<>(PROVIDERS.keySet())) {
            ProviderRecord record = PROVIDERS.get(token);
            if (record != null && now - record.createdAtMs > PROVIDER_TTL_MS) {
                ProviderRecord removed = PROVIDERS.remove(token);
                if (removed != null) clearProvider(removed.provider);
            }
        }
        while (PROVIDERS.size() > MAX_PROVIDER_COUNT) {
            String oldestToken = null;
            long oldest = Long.MAX_VALUE;
            for (String token : PROVIDERS.keySet()) {
                ProviderRecord record = PROVIDERS.get(token);
                if (record != null && record.createdAtMs < oldest) {
                    oldest = record.createdAtMs;
                    oldestToken = token;
                }
            }
            if (oldestToken == null) break;
            ProviderRecord removed = PROVIDERS.remove(oldestToken);
            if (removed != null) clearProvider(removed.provider);
        }
    }

    private static void clearProvider(@NonNull Provider provider) {
        if (provider instanceof ClearableProvider) {
            try { ((ClearableProvider) provider).clear(); } catch (Exception ignored) {}
        }
    }

}
