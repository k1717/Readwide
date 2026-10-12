package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default FOSS-friendly RAR3/RAR4 backend hook backed by libarchive.
 *
 * <p>The app builds the bundled libarchive-android backend from source instead of
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
        ArchiveCancellation.checkpoint(progress);
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
        ArchiveCancellation.checkpoint(progress);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory");
        }
        return LibarchiveNativeBridge.extractEntryWithSequentialPriming(
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
        ArchiveCancellation.checkpoint(null);
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        if (listing == null || listing.isEmpty()) return result;
        boolean escaped = listing.startsWith(LISTING_HEADER);
        int start = escaped ? LISTING_HEADER.length() : 0;
        while (start < listing.length()) {
            ArchiveCancellation.checkpoint(null);
            int end = listing.indexOf('\n', start);
            if (end < 0) end = listing.length();
            String line = listing.substring(start, end);
            start = end == listing.length() ? end : end + 1;
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length != 4 || !("D".equals(parts[0]) || "F".equals(parts[0]))) {
                throw new IOException("Invalid libarchive listing row");
            }
            boolean directory = "D".equals(parts[0]);
            String path = ArchiveSupport.sanitizeEntryPathForList(escaped ? decodeListingPath(parts[1]) : parts[1]);
            if (path == null) continue;
            if (directory && !path.endsWith("/")) path += "/";
            long size = parseLongOrDefault(parts[2], directory ? -1L : 0L);
            long timeMillis = parseLongOrDefault(parts[3], 0L);
            result.add(new ArchiveSupport.EntryInfo(path, directory, size, timeMillis));
        }
        return ArchiveSupport.withFirstFileEntryIdentities(result);
    }

    // Version the Java bridge framing: archive paths may contain tabs/newlines.
    // Legacy unescaped rows remain readable, but all new producer rows are escaped.
    static final String LISTING_HEADER = "READWIDE-LISTING/1\n";

    static String encodeListingPath(@NonNull String path) {
        StringBuilder result = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '\t': result.append("\\t"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                default: result.append(c);
            }
        }
        return result.toString();
    }

    private static String decodeListingPath(@NonNull String encoded) throws IOException {
        StringBuilder result = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != '\\') { result.append(c); continue; }
            if (++i == encoded.length()) throw new IOException("Truncated libarchive listing escape");
            switch (encoded.charAt(i)) {
                case '\\': result.append('\\'); break;
                case 't': result.append('\t'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                default: throw new IOException("Invalid libarchive listing escape");
            }
        }
        return result.toString();
    }

    private static long parseLongOrDefault(@Nullable String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

}
