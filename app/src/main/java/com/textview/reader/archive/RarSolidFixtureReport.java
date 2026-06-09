package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Diagnostic report for the compressed-solid RAR boundary. */
final class RarSolidFixtureReport {
    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final int MAX_CANDIDATES = 512;
    private static final long MAX_LIBARCHIVE_PROBE_BYTES = 64L * 1024L * 1024L;

    enum MetadataStatus {
        READABLE,
        CHAIN_INVALID,
        PARSE_FAILED,
        NOT_RAR
    }

    enum SolidKind {
        NONE,
        RAR3_OR_4_STORED_SOLID,
        RAR3_OR_4_COMPRESSED_SOLID,
        RAR5_SOLID,
        MIXED_SOLID
    }

    enum FirstPartyBoundary {
        NOT_SOLID,
        STORED_ONLY,
        RAR3_OR_4_COMPRESSED_SOLID_GAP,
        RAR5_SOLID_GAP,
        MIXED_SOLID_GAP,
        UNKNOWN
    }

    enum LibarchiveProbe {
        NOT_REQUESTED,
        UNAVAILABLE,
        NOT_SOLID,
        SKIPPED_TOO_LARGE,
        SUCCESS,
        FAILED
    }

    static final class Row {
        final String selectedPath;
        final String firstVolumePath;
        final MetadataStatus metadataStatus;
        final SolidKind solidKind;
        final FirstPartyBoundary firstPartyBoundary;
        final RarBackendRouteSummary backendRoutes;
        final LibarchiveProbe libarchiveProbe;
        final RarVolumeNameResolver.Style volumeStyle;
        final int volumeCount;
        final int rarVersion;
        final int entryCount;
        final int solidEntryCount;
        final int compressedSolidEntryCount;
        final String firstSolidEntry;
        final long firstSolidUnpackedSize;
        final String detail;

        private Row(@NonNull String selectedPath,
                    @NonNull String firstVolumePath,
                    @NonNull MetadataStatus metadataStatus,
                    @NonNull SolidKind solidKind,
                    @NonNull FirstPartyBoundary firstPartyBoundary,
                    @NonNull RarBackendRouteSummary backendRoutes,
                    @NonNull LibarchiveProbe libarchiveProbe,
                    @NonNull RarVolumeNameResolver.Style volumeStyle,
                    int volumeCount,
                    int rarVersion,
                    int entryCount,
                    int solidEntryCount,
                    int compressedSolidEntryCount,
                    @NonNull String firstSolidEntry,
                    long firstSolidUnpackedSize,
                    @NonNull String detail) {
            this.selectedPath = selectedPath;
            this.firstVolumePath = firstVolumePath;
            this.metadataStatus = metadataStatus;
            this.solidKind = solidKind;
            this.firstPartyBoundary = firstPartyBoundary;
            this.backendRoutes = backendRoutes;
            this.libarchiveProbe = libarchiveProbe;
            this.volumeStyle = volumeStyle;
            this.volumeCount = volumeCount;
            this.rarVersion = rarVersion;
            this.entryCount = entryCount;
            this.solidEntryCount = solidEntryCount;
            this.compressedSolidEntryCount = compressedSolidEntryCount;
            this.firstSolidEntry = firstSolidEntry;
            this.firstSolidUnpackedSize = firstSolidUnpackedSize;
            this.detail = detail;
        }
    }

    private final List<Row> rows;

    private RarSolidFixtureReport(@NonNull List<Row> rows) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @NonNull
    static RarSolidFixtureReport generate(@NonNull File root, @Nullable char[] password) {
        return generate(root, password, DEFAULT_MAX_DEPTH, null);
    }

    @NonNull
    static RarSolidFixtureReport generate(@NonNull File root,
                                          @Nullable char[] password,
                                          int maxDepth,
                                          @Nullable File libarchiveProbeDir) {
        List<File> candidates = root.isDirectory()
                ? scanCandidates(root, Math.max(0, maxDepth))
                : Collections.singletonList(root);
        return generateFromCandidates(candidates, password, libarchiveProbeDir);
    }

    @NonNull
    static RarSolidFixtureReport generateFromCandidates(@NonNull List<File> candidates,
                                                        @Nullable char[] password,
                                                        @Nullable File libarchiveProbeDir) {
        Map<String, File> canonicalFirstVolumes = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile() || !looksLikeRarCandidate(candidate)) continue;
            RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(candidate);
            String key = safeCanonicalPath(resolution.firstVolume());
            if (canonicalFirstVolumes.containsKey(key)) continue;
            canonicalFirstVolumes.put(key, candidate);
            rows.add(analyzeCandidate(candidate, resolution, password, libarchiveProbeDir));
            if (rows.size() >= MAX_CANDIDATES) break;
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return a.firstVolumePath.compareToIgnoreCase(b.firstVolumePath);
            }
        });
        return new RarSolidFixtureReport(rows);
    }

    @NonNull
    static RarSolidFixtureReport fromEntriesForTest(@NonNull String name,
                                                    int volumeCount,
                                                    @NonNull List<RarArchiveReader.RarEntry> entries,
                                                    @NonNull LibarchiveProbe probe,
                                                    @NonNull String detail) {
        List<Row> rows = new ArrayList<>(1);
        rows.add(rowFromEntries(name,
                name,
                MetadataStatus.READABLE,
                RarVolumeNameResolver.Style.SINGLE,
                volumeCount,
                entries,
                probe,
                detail));
        return new RarSolidFixtureReport(rows);
    }

    @NonNull
    List<Row> rows() {
        return rows;
    }

    int solidArchiveCount() {
        int count = 0;
        for (Row row : rows) if (row.solidEntryCount > 0) count++;
        return count;
    }

    int compressedSolidArchiveCount() {
        int count = 0;
        for (Row row : rows) if (row.compressedSolidEntryCount > 0) count++;
        return count;
    }

    int firstPartyGapCount() {
        int count = 0;
        for (Row row : rows) {
            if (row.firstPartyBoundary == FirstPartyBoundary.RAR3_OR_4_COMPRESSED_SOLID_GAP
                    || row.firstPartyBoundary == FirstPartyBoundary.RAR5_SOLID_GAP
                    || row.firstPartyBoundary == FirstPartyBoundary.MIXED_SOLID_GAP) {
                count++;
            }
        }
        return count;
    }

    int libarchiveSuccessCount() {
        int count = 0;
        for (Row row : rows) if (row.libarchiveProbe == LibarchiveProbe.SUCCESS) count++;
        return count;
    }

    int libarchiveFailureCount() {
        int count = 0;
        for (Row row : rows) if (row.libarchiveProbe == LibarchiveProbe.FAILED) count++;
        return count;
    }

    @NonNull
    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAR solid fixture boundary report\n\n");
        sb.append("This report is diagnostic only. Normal compressed and solid RAR extraction remains libarchive-first; ")
                .append("first-party solid rows document gaps before any live solid decoder is enabled.\n\n");
        sb.append("- total candidates: ").append(rows.size()).append('\n');
        sb.append("- solid archives: ").append(solidArchiveCount()).append('\n');
        sb.append("- compressed solid archives: ").append(compressedSolidArchiveCount()).append('\n');
        sb.append("- first-party solid gaps: ").append(firstPartyGapCount()).append('\n');
        sb.append("- libarchive probe successes: ").append(libarchiveSuccessCount()).append('\n');
        sb.append("- libarchive probe failures: ").append(libarchiveFailureCount()).append('\n');
        sb.append("- backend route counts: ").append(backendRouteCountsLabel()).append("\n\n");
        sb.append("| Selected | First volume | Metadata | Solid kind | First-party boundary | Backend route | Libarchive probe | Volumes | RAR | Entries | Solid entries | Compressed solid | First solid entry | Detail |\n");
        sb.append("|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|---|---|\n");
        for (Row row : rows) {
            sb.append("| ").append(escape(row.selectedPath))
                    .append(" | ").append(escape(row.firstVolumePath))
                    .append(" | ").append(row.metadataStatus)
                    .append(" | ").append(row.solidKind)
                    .append(" | ").append(row.firstPartyBoundary)
                    .append(" | ").append(escape(row.backendRoutes.routesLabel()))
                    .append(" | ").append(row.libarchiveProbe)
                    .append(" | ").append(row.volumeCount)
                    .append(" | ").append(row.rarVersion)
                    .append(" | ").append(row.entryCount)
                    .append(" | ").append(row.solidEntryCount)
                    .append(" | ").append(row.compressedSolidEntryCount)
                    .append(" | ").append(escape(row.firstSolidEntry))
                    .append(" | ").append(escape(row.detail))
                    .append(" |\n");
        }
        return sb.toString();
    }

    @NonNull
    private String backendRouteCountsLabel() {
        List<RarBackendRouteSummary> summaries = new ArrayList<>();
        for (Row row : rows) summaries.add(row.backendRoutes);
        return RarBackendRouteSummary.routeCountsLabel(summaries);
    }

    @NonNull
    private static Row analyzeCandidate(@NonNull File selected,
                                        @NonNull RarVolumeChainResolution resolution,
                                        @Nullable char[] password,
                                        @Nullable File libarchiveProbeDir) {
        String selectedPath = displayPath(selected);
        String firstPath = displayPath(resolution.firstVolume());
        int volumeCount = resolution.volumes().size();
        try {
            resolution.requireReadableChain();
        } catch (IOException e) {
            return errorRow(selectedPath,
                    firstPath,
                    MetadataStatus.CHAIN_INVALID,
                    resolution.style(),
                    volumeCount,
                    message(e));
        }

        int version;
        try {
            version = RarArchiveLocator.detectRarVersion(resolution.firstVolume());
        } catch (IOException e) {
            return errorRow(selectedPath,
                    firstPath,
                    MetadataStatus.PARSE_FAILED,
                    resolution.style(),
                    volumeCount,
                    "RAR signature detection failed: " + message(e));
        }
        if (version < 0) {
            return errorRow(selectedPath,
                    firstPath,
                    MetadataStatus.NOT_RAR,
                    resolution.style(),
                    volumeCount,
                    "no RAR signature found in first volume");
        }

        try {
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntriesForSplitStoredDiagnostics(
                    resolution.firstVolume(), password);
            Row base = rowFromEntries(selectedPath,
                    firstPath,
                    MetadataStatus.READABLE,
                    resolution.style(),
                    volumeCount,
                    entries,
                    LibarchiveProbe.NOT_REQUESTED,
                    "metadata readable");
            if (libarchiveProbeDir == null) return base;
            return withLibarchiveProbe(base, resolution.firstVolume(), entries, password, libarchiveProbeDir);
        } catch (IOException e) {
            return errorRow(selectedPath,
                    firstPath,
                    MetadataStatus.PARSE_FAILED,
                    resolution.style(),
                    volumeCount,
                    message(e));
        }
    }

    @NonNull
    private static Row withLibarchiveProbe(@NonNull Row base,
                                           @NonNull File archive,
                                           @NonNull List<RarArchiveReader.RarEntry> entries,
                                           @Nullable char[] password,
                                           @NonNull File probeDir) {
        RarArchiveReader.RarEntry entry = firstSolidFileEntry(entries);
        if (entry == null) {
            return copyWithProbe(base, LibarchiveProbe.NOT_SOLID, base.detail + "; no solid entry to probe");
        }
        if (!RarLibarchiveFallback.isAvailable()) {
            return copyWithProbe(base,
                    LibarchiveProbe.UNAVAILABLE,
                    base.detail + "; libarchive unavailable: " + LibarchiveNativeBridge.backendStatus());
        }
        if (entry.unpackedSize > MAX_LIBARCHIVE_PROBE_BYTES) {
            return copyWithProbe(base,
                    LibarchiveProbe.SKIPPED_TOO_LARGE,
                    base.detail + "; skipped libarchive probe because first solid entry is "
                            + entry.unpackedSize + " bytes");
        }
        if (!probeDir.exists() && !probeDir.mkdirs()) {
            return copyWithProbe(base,
                    LibarchiveProbe.FAILED,
                    base.detail + "; cannot create libarchive probe directory");
        }
        File out = new File(probeDir, safeProbeName(archive.getName(), entry.path));
        if (out.exists() && !out.delete()) {
            return copyWithProbe(base,
                    LibarchiveProbe.FAILED,
                    base.detail + "; cannot reset libarchive probe output");
        }
        try {
            boolean ok = RarLibarchiveFallback.extractSingleEntry(archive, entry.path, out, password);
            if (ok && out.isFile()) {
                long size = out.length();
                if (!out.delete()) out.deleteOnExit();
                return copyWithProbe(base,
                        LibarchiveProbe.SUCCESS,
                        base.detail + "; libarchive extracted first solid entry (" + size + " bytes)");
            }
            if (out.exists() && !out.delete()) out.deleteOnExit();
            return copyWithProbe(base,
                    LibarchiveProbe.FAILED,
                    base.detail + "; libarchive returned false for first solid entry");
        } catch (IOException | SecurityException e) {
            if (out.exists() && !out.delete()) out.deleteOnExit();
            return copyWithProbe(base,
                    LibarchiveProbe.FAILED,
                    base.detail + "; libarchive failed: " + message(e));
        }
    }

    @NonNull
    private static Row copyWithProbe(@NonNull Row row,
                                     @NonNull LibarchiveProbe probe,
                                     @NonNull String detail) {
        return new Row(row.selectedPath,
                row.firstVolumePath,
                row.metadataStatus,
                row.solidKind,
                row.firstPartyBoundary,
                row.backendRoutes,
                probe,
                row.volumeStyle,
                row.volumeCount,
                row.rarVersion,
                row.entryCount,
                row.solidEntryCount,
                row.compressedSolidEntryCount,
                row.firstSolidEntry,
                row.firstSolidUnpackedSize,
                detail);
    }

    @NonNull
    private static Row rowFromEntries(@NonNull String selectedPath,
                                      @NonNull String firstPath,
                                      @NonNull MetadataStatus metadataStatus,
                                      @NonNull RarVolumeNameResolver.Style style,
                                      int volumeCount,
                                      @NonNull List<RarArchiveReader.RarEntry> entries,
                                      @NonNull LibarchiveProbe probe,
                                      @NonNull String detail) {
        int version = -1;
        int solidCount = 0;
        int compressedSolidCount = 0;
        boolean hasRar34Solid = false;
        boolean hasRar34CompressedSolid = false;
        boolean hasRar5Solid = false;
        RarArchiveReader.RarEntry firstSolid = null;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null) continue;
            if (version < 0) version = entry.rarVersion;
            if (entry.directory || !entry.solid) continue;
            solidCount++;
            if (firstSolid == null) firstSolid = entry;
            boolean stored = RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
            if (!stored) compressedSolidCount++;
            if (entry.rarVersion >= 5) {
                hasRar5Solid = true;
            } else if (stored) {
                hasRar34Solid = true;
            } else {
                hasRar34Solid = true;
                hasRar34CompressedSolid = true;
            }
        }
        SolidKind kind = solidKind(hasRar34Solid, hasRar34CompressedSolid, hasRar5Solid, solidCount);
        FirstPartyBoundary boundary = firstPartyBoundary(kind);
        RarBackendRouteSummary backendRoutes = RarBackendRouteSummary.fromEntries(entries);
        String firstPathDisplay = firstSolid == null ? "-" : firstSolid.path;
        long firstSize = firstSolid == null ? -1L : firstSolid.unpackedSize;
        return new Row(selectedPath,
                firstPath,
                metadataStatus,
                kind,
                boundary,
                backendRoutes,
                probe,
                style,
                volumeCount,
                version,
                entries.size(),
                solidCount,
                compressedSolidCount,
                firstPathDisplay,
                firstSize,
                detailForBoundary(detail, boundary, firstSize));
    }

    @NonNull
    private static SolidKind solidKind(boolean hasRar34Solid,
                                       boolean hasRar34CompressedSolid,
                                       boolean hasRar5Solid,
                                       int solidCount) {
        if (solidCount == 0) return SolidKind.NONE;
        if (hasRar5Solid && hasRar34Solid) return SolidKind.MIXED_SOLID;
        if (hasRar5Solid) return SolidKind.RAR5_SOLID;
        if (hasRar34CompressedSolid) return SolidKind.RAR3_OR_4_COMPRESSED_SOLID;
        return SolidKind.RAR3_OR_4_STORED_SOLID;
    }

    @NonNull
    private static FirstPartyBoundary firstPartyBoundary(@NonNull SolidKind kind) {
        switch (kind) {
            case NONE:
                return FirstPartyBoundary.NOT_SOLID;
            case RAR3_OR_4_STORED_SOLID:
                return FirstPartyBoundary.STORED_ONLY;
            case RAR3_OR_4_COMPRESSED_SOLID:
                return FirstPartyBoundary.RAR3_OR_4_COMPRESSED_SOLID_GAP;
            case RAR5_SOLID:
                return FirstPartyBoundary.RAR5_SOLID_GAP;
            case MIXED_SOLID:
                return FirstPartyBoundary.MIXED_SOLID_GAP;
            default:
                return FirstPartyBoundary.UNKNOWN;
        }
    }

    @NonNull
    private static String detailForBoundary(@NonNull String detail,
                                            @NonNull FirstPartyBoundary boundary,
                                            long firstSolidSize) {
        String suffix;
        switch (boundary) {
            case NOT_SOLID:
                suffix = "no solid entries";
                break;
            case STORED_ONLY:
                suffix = "stored solid does not require compressed dictionary continuation";
                break;
            case RAR3_OR_4_COMPRESSED_SOLID_GAP:
                suffix = "first-party RAR3/RAR4 compressed-solid real fixture support is not enabled";
                break;
            case RAR5_SOLID_GAP:
                suffix = "RAR5 solid remains libarchive-owned in the FOSS build";
                break;
            case MIXED_SOLID_GAP:
                suffix = "mixed solid families are unsupported in first-party path";
                break;
            default:
                suffix = "unknown solid boundary";
                break;
        }
        if (firstSolidSize >= 0) suffix += "; first solid unpacked bytes=" + firstSolidSize;
        return detail + "; " + suffix;
    }

    @Nullable
    private static RarArchiveReader.RarEntry firstSolidFileEntry(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null && !entry.directory && entry.solid && !entry.splitBefore) return entry;
        }
        return null;
    }

    @NonNull
    private static Row errorRow(@NonNull String selectedPath,
                                @NonNull String firstPath,
                                @NonNull MetadataStatus status,
                                @NonNull RarVolumeNameResolver.Style style,
                                int volumeCount,
                                @NonNull String detail) {
        return new Row(selectedPath,
                firstPath,
                status,
                SolidKind.NONE,
                FirstPartyBoundary.UNKNOWN,
                RarBackendRouteSummary.empty(),
                LibarchiveProbe.NOT_REQUESTED,
                style,
                volumeCount,
                -1,
                0,
                0,
                0,
                "-",
                -1L,
                detail);
    }

    @NonNull
    private static List<File> scanCandidates(@NonNull File root, int maxDepth) {
        List<File> out = new ArrayList<>();
        ArrayDeque<FileDepth> queue = new ArrayDeque<>();
        queue.add(new FileDepth(root, 0));
        while (!queue.isEmpty() && out.size() < MAX_CANDIDATES) {
            FileDepth current = queue.removeFirst();
            File[] children = current.file.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child == null) continue;
                if (child.isDirectory()) {
                    if (current.depth < maxDepth) queue.addLast(new FileDepth(child, current.depth + 1));
                } else if (looksLikeRarCandidate(child)) {
                    out.add(child);
                    if (out.size() >= MAX_CANDIDATES) break;
                }
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            }
        });
        return out;
    }

    private static boolean looksLikeRarCandidate(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".rar")
                || name.endsWith(".cbr")
                || name.endsWith(".exe")
                || name.matches(".*\\.r\\d{2,3}");
    }

    @NonNull
    private static String safeProbeName(@NonNull String archiveName, @NonNull String entryPath) {
        return (archiveName + "_" + entryPath).replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    @NonNull
    private static String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    @NonNull
    private static String displayPath(@NonNull File file) {
        return file.getPath().replace('\\', '/');
    }

    @NonNull
    private static String escape(@Nullable String value) {
        if (value == null || value.length() == 0) return "-";
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    @NonNull
    private static String message(@NonNull Throwable error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }

    private static final class FileDepth {
        final File file;
        final int depth;

        FileDepth(@NonNull File file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }
}
