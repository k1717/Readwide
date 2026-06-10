package com.textview.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;

import java.io.File;
import java.util.Set;

/**
 * Shared cache/extraction gate for archive-backed image sequences.
 *
 * Non-password archives may trust an existing preview file because the archive
 * content is not gated by user input. Password-protected archives must prove
 * that the current password can still extract the requested entry before a
 * sensitive cached plaintext image is reused.
 */
final class ArchiveImageEntryCache {
    private ArchiveImageEntryCache() {}

    @NonNull
    static ArchiveSupport.ExtractionResult ensureReady(@NonNull File archiveFile,
                                                       @NonNull String entryPath,
                                                       @NonNull File outFile,
                                                       @Nullable char[] password,
                                                       boolean sensitiveCache,
                                                       @Nullable Set<String> verifiedSensitivePaths) {
        if (!sensitiveCache && isUsableFile(outFile)) {
            return ArchiveSupport.ExtractionResult.success();
        }

        String cacheKey = outFile.getAbsolutePath();
        if (sensitiveCache
                && verifiedSensitivePaths != null
                && verifiedSensitivePaths.contains(cacheKey)
                && isUsableFile(outFile)) {
            return ArchiveSupport.ExtractionResult.success();
        }

        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archiveFile,
                entryPath,
                outFile,
                password);
        if (result.success && sensitiveCache && verifiedSensitivePaths != null && isUsableFile(outFile)) {
            verifiedSensitivePaths.add(cacheKey);
        }
        return result;
    }

    static boolean isUsableFile(@Nullable File file) {
        return file != null && file.exists() && file.isFile() && file.length() > 0L;
    }
}
