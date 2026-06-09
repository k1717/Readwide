package com.textview.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Validation and destination policy for archive extraction queues.
 */
final class MainArchiveExtractionPlanner {
    private MainArchiveExtractionPlanner() {
    }

    @NonNull
    static ArrayList<File> collectReadyArchives(@NonNull List<File> archives) {
        ArrayList<File> ready = new ArrayList<>();
        for (File archive : archives) {
            if (archive != null
                    && ArchiveSupport.isSupportedArchive(archive)
                    && archive.exists()
                    && archive.isFile()
                    && archive.canRead()) {
                ready.add(archive);
            }
        }
        return ready;
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
