package com.readwide.manager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
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
        @NonNull final Set<String> verifiedSensitivePaths;
        @Nullable final String archivePathSnapshot;
        final long archiveLengthSnapshot;
        final long archiveLastModifiedSnapshot;
        @Nullable private Closeable preparedResource;

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                 @Nullable ArrayList<String> entryPaths) {
            this(paths, displayNames, entryPaths, null);
        }

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                  @Nullable ArrayList<String> entryPaths,
                  @Nullable char[] archivePassword) {
            this(paths, displayNames, entryPaths, archivePassword, null);
        }

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                 @Nullable ArrayList<String> entryPaths,
                 @Nullable char[] archivePassword,
                 @Nullable Closeable preparedResource) {
            this(paths, displayNames, entryPaths, archivePassword, preparedResource, null);
        }

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                 @Nullable ArrayList<String> entryPaths,
                 @Nullable char[] archivePassword,
                 @Nullable Closeable preparedResource,
                 @Nullable Set<String> verifiedSensitivePaths) {
            this(paths, displayNames, entryPaths, archivePassword, preparedResource,
                    verifiedSensitivePaths, null, -1L, -1L);
        }

        Sequence(@NonNull ArrayList<String> paths,
                 @Nullable ArrayList<String> displayNames,
                 @Nullable ArrayList<String> entryPaths,
                 @Nullable char[] archivePassword,
                 @Nullable Closeable preparedResource,
                 @Nullable Set<String> verifiedSensitivePaths,
                 @Nullable String archivePathSnapshot,
                 long archiveLengthSnapshot,
                 long archiveLastModifiedSnapshot) {
            this.paths = paths;
            this.displayNames = displayNames != null ? displayNames : new ArrayList<>();
            this.entryPaths = entryPaths != null ? entryPaths : new ArrayList<>();
            this.archivePassword = PasswordChars.cloneOf(archivePassword);
            this.verifiedSensitivePaths = verifiedSensitivePaths != null
                    ? new HashSet<>(verifiedSensitivePaths) : new HashSet<>();
            this.archivePathSnapshot = archivePathSnapshot;
            this.archiveLengthSnapshot = archiveLengthSnapshot;
            this.archiveLastModifiedSnapshot = archiveLastModifiedSnapshot;
            this.preparedResource = preparedResource;
        }

        /**
         * Legacy/non-archive handoffs have no snapshot and remain compatible.
         * Production archive handoffs must still match the exact source metadata
         * captured before extraction, otherwise their cached paths belong to an
         * archive version that is no longer current.
         */
        boolean matchesSourceArchiveSnapshot(@Nullable String sourceArchivePath) {
            if (archivePathSnapshot == null) return true;
            if (sourceArchivePath == null || sourceArchivePath.trim().isEmpty()
                    || archiveLengthSnapshot < 0L || archiveLastModifiedSnapshot < 0L) {
                return false;
            }
            File source = new File(sourceArchivePath);
            return source.exists() && source.isFile()
                    && SequentialArchiveImageReader.matchesArchiveSnapshot(
                    source,
                    archivePathSnapshot,
                    archiveLengthSnapshot,
                    archiveLastModifiedSnapshot);
        }

        @Nullable
        synchronized Closeable takePreparedResource() {
            Closeable resource = preparedResource;
            preparedResource = null;
            return resource;
        }

        synchronized void clearSensitiveData() {
            PasswordChars.clear(archivePassword);
            verifiedSensitivePaths.clear();
            if (preparedResource != null) {
                try {
                    preparedResource.close();
                } catch (Exception ignored) {
                }
                preparedResource = null;
            }
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
