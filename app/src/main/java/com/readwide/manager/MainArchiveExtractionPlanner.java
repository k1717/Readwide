package com.readwide.manager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validation and destination policy for archive extraction queues.
 */
final class MainArchiveExtractionPlanner {
    private MainArchiveExtractionPlanner() {
    }

    @NonNull
    static ArrayList<File> collectReadyArchives(@NonNull List<File> archives) {
        Map<String, File> readyByExtractionRoot = new LinkedHashMap<>();
        for (File archive : archives) {
            if (archive == null
                    || !archive.exists()
                    || !archive.isFile()
                    || !archive.canRead()
                    || !ArchiveSupport.isSupportedArchive(archive)) {
                continue;
            }

            File extractionRoot = ArchiveSupport.normalizeExtractionQueueArchive(archive);
            if (extractionRoot == null
                    || !extractionRoot.exists()
                    || !extractionRoot.isFile()
                    || !extractionRoot.canRead()
                    || !ArchiveSupport.isSupportedArchive(extractionRoot)) {
                extractionRoot = archive;
            }

            String key = canonicalQueueKey(extractionRoot);
            if (!readyByExtractionRoot.containsKey(key)) {
                readyByExtractionRoot.put(key, extractionRoot);
            }
        }
        return new ArrayList<>(readyByExtractionRoot.values());
    }

    @NonNull
    private static String canonicalQueueKey(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    @NonNull
    static String archiveOutputBaseName(@NonNull File archive, @NonNull String fallbackName) {
        return ArchiveSupport.getArchiveOutputBaseName(archive, fallbackName);
    }

    @Nullable
    static File numberedDirectoryDestination(@NonNull File parentDir,
                                             @NonNull String baseName,
                                             @NonNull String fallbackName) {
        String cleanBase = baseName.trim();
        if (cleanBase.length() == 0) cleanBase = fallbackName;
        for (int i = 1; i < 10000; i++) {
            File candidate = new File(parentDir, cleanBase + " (" + i + ")");
            if (!candidate.exists()) return candidate;
        }
        return null;
    }

    static boolean hasPasswordProtectedArchive(@NonNull List<File> archives) {
        for (File archive : archives) {
            if (archive == null
                    || !archive.exists()
                    || !archive.canRead()
                    || !ArchiveSupport.isSupportedArchive(archive)) {
                continue;
            }
            if (ArchiveSupport.requiresPasswordForExtraction(archive)) return true;
        }
        return false;
    }
}
