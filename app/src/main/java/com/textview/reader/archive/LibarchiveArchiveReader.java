package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Generic libarchive reader for the bundled native archive backend.
 *
 * <p>This class is deliberately conservative: it does not replace archive creation, and Java
 * readers remain primary where they are known to be faster or have better app-specific coverage.
 * Current routing keeps dedicated Java readers as the ZIP/TAR/7z primary paths, uses
 * libarchive as the RAR primary path through {@link RarLibarchiveFallback}, and keeps
 * this generic reader as a compatibility fallback when a dedicated backend rejects a file.</p>
 */
final class LibarchiveArchiveReader {
    private LibarchiveArchiveReader() {}

    static boolean isAvailable() {
        return LibarchiveNativeBridge.isAvailable();
    }

    @NonNull
    static String backendStatus() {
        return LibarchiveNativeBridge.backendStatus();
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archive,
                                                      @Nullable char[] password) throws IOException {
        ensureAvailable();
        String listing = LibarchiveNativeBridge.listEntries(archive.getAbsolutePath(), password);
        return RarLibarchiveFallback.parseListingRows(listing);
    }

    static boolean requiresPasswordForExtraction(@NonNull File archive) {
        if (!isAvailable()) return false;
        try {
            return LibarchiveNativeBridge.requiresPassword(archive.getAbsolutePath());
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress,
                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        ensureAvailable();
        if (progress != null && !progress.checkpoint()) throw new IOException("Archive extraction cancelled");
        return LibarchiveNativeBridge.extractArchive(
                archive.getAbsolutePath(),
                targetDir.getAbsolutePath(),
                password,
                progress,
                entryProgress);
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password,
                                      @Nullable FileOperationProgress progress) throws IOException {
        ensureAvailable();
        if (progress != null && !progress.checkpoint()) throw new IOException("Archive extraction cancelled");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory");
        }
        return LibarchiveNativeBridge.extractEntry(
                archive.getAbsolutePath(),
                entryPath,
                outFile.getAbsolutePath(),
                password,
                progress);
    }

    private static void ensureAvailable() throws IOException {
        if (!isAvailable()) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "libarchive backend unavailable: " + LibarchiveNativeBridge.backendStatus());
        }
    }
}
