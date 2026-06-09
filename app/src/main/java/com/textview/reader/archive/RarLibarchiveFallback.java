package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Default FOSS-friendly RAR3/RAR4 backend hook backed by libarchive.
 *
 * <p>The normal 2.2.6 APK bundles the Apache-2.0 libarchive-android AAR instead of
 * reintroducing Junrar/UnRAR-licensed Java dependencies. Unsupported RAR variants still
 * fail cleanly, but common compressed RAR3/RAR4 files are attempted by default.</p>
 */
final class RarLibarchiveFallback {
    private RarLibarchiveFallback() {}

    static boolean isAvailable() {
        return LibarchiveNativeBridge.isRarFormatAvailable();
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archiveFile,
                                                      @Nullable char[] password) throws IOException {
        ensureAvailable();
        String listing = LibarchiveNativeBridge.listEntries(archivePaths(archiveFile), password);
        return parseListingRows(listing);
    }

    static boolean requiresPasswordForExtraction(@NonNull File archiveFile) {
        if (!isAvailable()) return false;
        try {
            return LibarchiveNativeBridge.requiresPassword(archivePaths(archiveFile));
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archiveFile,
                                               @NonNull File targetDir,
                                               @Nullable char[] password) throws IOException {
        return extractArchiveIntoDirectory(archiveFile, targetDir, password, null);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archiveFile,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress) throws IOException {
        return extractArchiveIntoDirectory(archiveFile, targetDir, password, progress, null);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archiveFile,
                                               @NonNull File targetDir,
                                               @Nullable char[] password,
                                               @Nullable FileOperationProgress progress,
                                               @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        ensureAvailable();
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        return LibarchiveNativeBridge.extractArchive(
                archivePaths(archiveFile),
                targetDir.getAbsolutePath(),
                password,
                progress,
                entryProgress);
    }

    static boolean extractSingleEntry(@NonNull File archiveFile,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password) throws IOException {
        return extractSingleEntry(archiveFile, entryPath, outFile, password, null);
    }

    static boolean extractSingleEntry(@NonNull File archiveFile,
                                      @NonNull String entryPath,
                                      @NonNull File outFile,
                                      @Nullable char[] password,
                                      @Nullable FileOperationProgress progress) throws IOException {
        ensureAvailable();
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory");
        }
        return LibarchiveNativeBridge.extractEntry(
                archivePaths(archiveFile),
                entryPath,
                outFile.getAbsolutePath(),
                password,
                progress);
    }


    @NonNull
    private static String[] archivePaths(@NonNull File archiveFile) throws IOException {
        List<File> volumes = RarArchiveReader.collectVolumeChainForBackend(archiveFile);
        if (volumes.isEmpty()) volumes = Collections.singletonList(archiveFile);
        String[] paths = new String[volumes.size()];
        for (int i = 0; i < volumes.size(); i++) {
            paths[i] = volumes.get(i).getAbsolutePath();
        }
        return paths;
    }

    private static void ensureAvailable() throws IOException {
        if (!isAvailable()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR compressed fallback requires the bundled libarchive-android backend ("
                            + LibarchiveNativeBridge.backendStatus() + ")");
        }
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> parseListingRows(@Nullable String listing) throws IOException {
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        if (listing == null || listing.isEmpty()) return result;
        String[] lines = listing.split("\\n");
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\t", 4);
            if (parts.length < 4) throw new IOException("Invalid libarchive listing row");
            boolean directory = "D".equalsIgnoreCase(parts[0]);
            String path = sanitizeEntryPath(parts[1]);
            if (path == null) continue;
            if (directory && !path.endsWith("/")) path += "/";
            long size = parseLongOrDefault(parts[2], directory ? -1L : 0L);
            long timeMillis = parseLongOrDefault(parts[3], 0L);
            result.add(new ArchiveSupport.EntryInfo(path, directory, size, timeMillis));
        }
        return withSyntheticDirectories(result);
    }

    @Nullable
    private static String sanitizeEntryPath(@Nullable String input) {
        if (input == null) return null;
        String path = input.replace('\\', '/').trim();
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty() || path.contains("../") || path.equals("..") || path.contains("/../")) return null;
        return path;
    }

    private static long parseLongOrDefault(@Nullable String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @NonNull
    private static List<ArchiveSupport.EntryInfo> withSyntheticDirectories(@NonNull List<ArchiveSupport.EntryInfo> input) {
        Map<String, ArchiveSupport.EntryInfo> out = new LinkedHashMap<>();
        for (ArchiveSupport.EntryInfo entry : input) {
            addParentDirectories(out, entry.path);
            out.put(entry.path.toLowerCase(Locale.ROOT), entry);
        }
        return new ArrayList<>(out.values());
    }

    private static void addParentDirectories(@NonNull Map<String, ArchiveSupport.EntryInfo> out,
                                             @NonNull String path) {
        int slash = path.indexOf('/');
        while (slash >= 0) {
            String dir = path.substring(0, slash + 1);
            String key = dir.toLowerCase(Locale.ROOT);
            if (!out.containsKey(key)) out.put(key, new ArchiveSupport.EntryInfo(dir, true, -1L, 0L));
            slash = path.indexOf('/', slash + 1);
        }
    }
}
