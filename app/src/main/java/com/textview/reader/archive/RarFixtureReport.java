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

/** Builds a conservative diagnostic report for RAR fixture directories. */
final class RarFixtureReport {
    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final int MAX_CANDIDATES = 512;

    enum Status {
        READABLE,
        CHAIN_INVALID,
        PARSE_FAILED,
        NOT_RAR
    }

    static final class Row {
        final String selectedPath;
        final String firstVolumePath;
        final Status status;
        final RarVolumeNameResolver.Style volumeStyle;
        final int volumeCount;
        final int rarVersion;
        final int entryCount;
        final int splitSupported;
        final int splitUnsupported;
        final int splitInvalid;
        final int splitIgnored;
        final RarBackendRouteSummary backendRoutes;
        final RarNonSolidCompatibilityReport nonSolidCompatibility;
        final RarPpmdFixtureReport ppmdBlocks;
        final String detail;

        private Row(@NonNull String selectedPath,
                    @NonNull String firstVolumePath,
                    @NonNull Status status,
                    @NonNull RarVolumeNameResolver.Style volumeStyle,
                    int volumeCount,
                    int rarVersion,
                    int entryCount,
                    int splitSupported,
                    int splitUnsupported,
                    int splitInvalid,
                    int splitIgnored,
                    @NonNull RarBackendRouteSummary backendRoutes,
                    @NonNull RarNonSolidCompatibilityReport nonSolidCompatibility,
                    @NonNull RarPpmdFixtureReport ppmdBlocks,
                    @NonNull String detail) {
            this.selectedPath = selectedPath;
            this.firstVolumePath = firstVolumePath;
            this.status = status;
            this.volumeStyle = volumeStyle;
            this.volumeCount = volumeCount;
            this.rarVersion = rarVersion;
            this.entryCount = entryCount;
            this.splitSupported = splitSupported;
            this.splitUnsupported = splitUnsupported;
            this.splitInvalid = splitInvalid;
            this.splitIgnored = splitIgnored;
            this.backendRoutes = backendRoutes;
            this.nonSolidCompatibility = nonSolidCompatibility;
            this.ppmdBlocks = ppmdBlocks;
            this.detail = detail;
        }
    }

    private final List<Row> rows;

    private RarFixtureReport(@NonNull List<Row> rows) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @NonNull
    static RarFixtureReport generate(@NonNull File root, @Nullable char[] password) {
        return generate(root, password, DEFAULT_MAX_DEPTH);
    }

    @NonNull
    static RarFixtureReport generate(@NonNull File root,
                                     @Nullable char[] password,
                                     int maxDepth) {
        List<File> candidates = root.isDirectory()
                ? scanCandidates(root, Math.max(0, maxDepth))
                : Collections.singletonList(root);
        return generateFromCandidates(candidates, password);
    }

    @NonNull
    static RarFixtureReport generateFromCandidates(@NonNull List<File> candidates,
                                                   @Nullable char[] password) {
        Map<String, File> canonicalFirstVolumes = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile() || !looksLikeRarCandidate(candidate)) continue;
            RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(candidate);
            String key = safeCanonicalPath(resolution.firstVolume());
            if (canonicalFirstVolumes.containsKey(key)) continue;
            canonicalFirstVolumes.put(key, candidate);
            rows.add(analyzeCandidate(candidate, resolution, password));
            if (rows.size() >= MAX_CANDIDATES) break;
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return a.firstVolumePath.compareToIgnoreCase(b.firstVolumePath);
            }
        });
        return new RarFixtureReport(rows);
    }

    @NonNull
    List<Row> rows() {
        return rows;
    }

    int readableCount() {
        int count = 0;
        for (Row row : rows) if (row.status == Status.READABLE) count++;
        return count;
    }

    int chainInvalidCount() {
        int count = 0;
        for (Row row : rows) if (row.status == Status.CHAIN_INVALID) count++;
        return count;
    }

    int parseFailedCount() {
        int count = 0;
        for (Row row : rows) if (row.status == Status.PARSE_FAILED) count++;
        return count;
    }

    int supportedStoredSplitCount() {
        int count = 0;
        for (Row row : rows) count += row.splitSupported;
        return count;
    }

    int firstPartyRoutedEntryCount() {
        int count = 0;
        for (Row row : rows) count += row.backendRoutes.firstPartyAllowedCount;
        return count;
    }

    int libarchiveOwnedEntryCount() {
        int count = 0;
        for (Row row : rows) count += row.backendRoutes.libarchiveOwnedCount;
        return count;
    }

    int cleanUnsupportedEntryCount() {
        int count = 0;
        for (Row row : rows) count += row.backendRoutes.cleanUnsupportedCount;
        return count;
    }

    @NonNull
    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAR fixture support boundary report\n\n");
        sb.append("This report is diagnostic only. Normal compressed RAR extraction remains libarchive-first; ")
                .append("first-party rows describe limited verified routes, while unsupported rows remain clean diagnostics.\n\n");
        sb.append("- total candidates: ").append(rows.size()).append('\n');
        sb.append("- readable metadata: ").append(readableCount()).append('\n');
        sb.append("- invalid chains: ").append(chainInvalidCount()).append('\n');
        sb.append("- parse failures: ").append(parseFailedCount()).append('\n');
        sb.append("- first-party stored split candidates: ").append(supportedStoredSplitCount()).append('\n');
        sb.append("- first-party routed entries: ").append(firstPartyRoutedEntryCount()).append('\n');
        sb.append("- libarchive-owned entries: ").append(libarchiveOwnedEntryCount()).append('\n');
        sb.append("- clean unsupported entries: ").append(cleanUnsupportedEntryCount()).append('\n');
        sb.append("- backend route counts: ").append(backendRouteCountsLabel()).append('\n');
        sb.append("- non-solid compatibility counts: ").append(nonSolidCompatibilityCountsLabel()).append('\n');
        sb.append("- PPMd block probe counts: ").append(ppmdBlockCountsLabel()).append("\n\n");
        sb.append("| Selected | First volume | Status | Style | Volumes | RAR | Entries | Backend route | Route counts | Non-solid compatibility | PPMd block probe | Stored split | Unsupported split | Invalid split | Ignored | Detail |\n");
        sb.append("|---|---|---:|---|---:|---:|---:|---|---|---|---|---:|---:|---:|---:|---|\n");
        for (Row row : rows) {
            sb.append("| ").append(escape(row.selectedPath))
                    .append(" | ").append(escape(row.firstVolumePath))
                    .append(" | ").append(row.status)
                    .append(" | ").append(row.volumeStyle)
                    .append(" | ").append(row.volumeCount)
                    .append(" | ").append(row.rarVersion)
                    .append(" | ").append(row.entryCount)
                    .append(" | ").append(escape(row.backendRoutes.routesLabel()))
                    .append(" | ").append(escape(row.backendRoutes.countsLabel()))
                    .append(" | ").append(escape(row.nonSolidCompatibility.oneLineSummary()))
                    .append(" | ").append(escape(row.ppmdBlocks.oneLineSummary()))
                    .append(" | ").append(row.splitSupported)
                    .append(" | ").append(row.splitUnsupported)
                    .append(" | ").append(row.splitInvalid)
                    .append(" | ").append(row.splitIgnored)
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
    private String nonSolidCompatibilityCountsLabel() {
        int nonSolid = 0;
        int firstParty = 0;
        int libarchive = 0;
        int limitedFallback = 0;
        int gaps = 0;
        int excludedSolid = 0;
        int rar5Stored = 0;
        int rar5Compressed = 0;
        for (Row row : rows) {
            nonSolid += row.nonSolidCompatibility.nonSolidPayloadCount();
            firstParty += row.nonSolidCompatibility.firstPartyDirectCount();
            libarchive += row.nonSolidCompatibility.libarchiveDependentCount();
            limitedFallback += row.nonSolidCompatibility.limitedFallbackCount();
            gaps += row.nonSolidCompatibility.firstPartyGapCount();
            excludedSolid += row.nonSolidCompatibility.excludedSolidCount();
            rar5Stored += row.nonSolidCompatibility.rar5StoredFirstPartyCount();
            rar5Compressed += row.nonSolidCompatibility.rar5CompressedLibarchiveCount();
        }
        return "nonSolid=" + nonSolid
                + ", firstParty=" + firstParty
                + ", libarchiveDependent=" + libarchive
                + ", limitedFallback=" + limitedFallback
                + ", firstPartyGap=" + gaps
                + ", excludedSolid=" + excludedSolid
                + ", rar5StoredFirstParty=" + rar5Stored
                + ", rar5CompressedLibarchive=" + rar5Compressed;
    }

    @NonNull
    private String ppmdBlockCountsLabel() {
        List<RarPpmdFixtureReport> summaries = new ArrayList<>();
        for (Row row : rows) summaries.add(row.ppmdBlocks);
        return RarPpmdFixtureReport.countsLabel(summaries);
    }

    @NonNull
    private static RarNonSolidCompatibilityReport emptyCompatibility() {
        return RarNonSolidCompatibilityReport.fromEntries(Collections.<RarArchiveReader.RarEntry>emptyList());
    }

    @NonNull
    private static RarPpmdFixtureReport emptyPpmdBlocks() {
        return RarPpmdFixtureReport.empty();
    }

    @NonNull
    private static Row analyzeCandidate(@NonNull File selected,
                                        @NonNull RarVolumeChainResolution resolution,
                                        @Nullable char[] password) {
        String selectedPath = displayPath(selected);
        String firstPath = displayPath(resolution.firstVolume());
        int volumeCount = resolution.volumes().size();
        try {
            resolution.requireReadableChain();
        } catch (IOException e) {
            return new Row(selectedPath,
                    firstPath,
                    Status.CHAIN_INVALID,
                    resolution.style(),
                    volumeCount,
                    -1,
                    0,
                    0,
                    0,
                    1,
                    0,
                    RarBackendRouteSummary.empty(),
                    emptyCompatibility(),
                    emptyPpmdBlocks(),
                    message(e));
        }

        int version;
        try {
            version = RarArchiveLocator.detectRarVersion(resolution.firstVolume());
        } catch (IOException e) {
            return new Row(selectedPath,
                    firstPath,
                    Status.PARSE_FAILED,
                    resolution.style(),
                    volumeCount,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    RarBackendRouteSummary.empty(),
                    emptyCompatibility(),
                    emptyPpmdBlocks(),
                    "RAR signature detection failed: " + message(e));
        }
        if (version < 0) {
            return new Row(selectedPath,
                    firstPath,
                    Status.NOT_RAR,
                    resolution.style(),
                    volumeCount,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    RarBackendRouteSummary.empty(),
                    emptyCompatibility(),
                    emptyPpmdBlocks(),
                    "no RAR signature found in first volume");
        }

        try {
            List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntriesForSplitStoredDiagnostics(
                    resolution.firstVolume(), password);
            RarSplitStoredMatrixReport splitReport = RarSplitStoredMatrixReport.analyzeIncludingNonSplit(entries);
            return new Row(selectedPath,
                    firstPath,
                    Status.READABLE,
                    resolution.style(),
                    volumeCount,
                    version,
                    entries.size(),
                    splitReport.supportedCount(),
                    splitReport.unsupportedCount(),
                    splitReport.invalidCount(),
                    splitReport.ignoredCount(),
                    RarBackendRouteSummary.fromEntries(entries),
                    RarNonSolidCompatibilityReport.fromEntries(entries),
                    RarPpmdFixtureReport.fromEntries(entries),
                    summarizeSplitReport(splitReport));
        } catch (IOException e) {
            return new Row(selectedPath,
                    firstPath,
                    Status.PARSE_FAILED,
                    resolution.style(),
                    volumeCount,
                    version,
                    0,
                    0,
                    0,
                    0,
                    0,
                    RarBackendRouteSummary.empty(),
                    emptyCompatibility(),
                    emptyPpmdBlocks(),
                    message(e));
        }
    }

    @NonNull
    private static String summarizeSplitReport(@NonNull RarSplitStoredMatrixReport report) {
        if (report.rows().isEmpty()) return "no entries";
        if (report.supportedCount() > 0) {
            return "first-party stored split candidate(s) present; extraction still requires CRC verification";
        }
        if (report.unsupportedCount() > 0) return "split payload present but outside stored split support boundary";
        if (report.invalidCount() > 0) return "split metadata is invalid or incomplete";
        return "no split payload candidates";
    }

    @NonNull
    private static List<File> scanCandidates(@NonNull File root, int maxDepth) {
        List<File> result = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, 0));
        while (!queue.isEmpty() && result.size() < MAX_CANDIDATES) {
            Node node = queue.removeFirst();
            File[] children = node.file.listFiles();
            if (children == null) continue;
            List<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, children);
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File child : sorted) {
                if (child.isDirectory()) {
                    if (node.depth < maxDepth && !isGeneratedOrBuildDir(child)) {
                        queue.addLast(new Node(child, node.depth + 1));
                    }
                } else if (child.isFile() && looksLikeRarCandidate(child)) {
                    result.add(child);
                    if (result.size() >= MAX_CANDIDATES) break;
                }
            }
        }
        return result;
    }

    private static boolean looksLikeRarCandidate(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".rar") || name.endsWith(".cbr")) return true;
        if (name.matches(".*\\.r\\d{2,3}$")) return true;
        return name.endsWith(".exe");
    }

    private static boolean isGeneratedOrBuildDir(@NonNull File file) {
        String name = file.getName();
        String path = file.getPath().replace(File.separatorChar, '/');
        return ".git".equals(name)
                || ".gradle".equals(name)
                || path.endsWith("/app/build");
    }

    @NonNull
    private static String displayPath(@NonNull File file) {
        return file.getPath().replace(File.separatorChar, '/');
    }

    @NonNull
    private static String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    @NonNull
    private static String message(@NonNull Exception e) {
        String message = e.getMessage();
        return message == null || message.length() == 0 ? e.getClass().getSimpleName() : message;
    }

    @NonNull
    private static String escape(@Nullable String text) {
        if (text == null) return "";
        return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private static final class Node {
        final File file;
        final int depth;

        Node(@NonNull File file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }
}
