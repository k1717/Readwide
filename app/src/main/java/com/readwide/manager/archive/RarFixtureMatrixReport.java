package com.readwide.manager.archive;

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

/**
 * Runtime extraction smoke matrix for real RAR fixtures.
 *
 * This is intentionally diagnostic. It does not expand public RAR claims; it lets a
 * developer point the test suite at a real fixture folder and record what current
 * listing, first-file extraction, and image-entry extraction paths actually do.
 */
final class RarFixtureMatrixReport {
    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final int MAX_CANDIDATES = 512;
    private static final long MAX_PROBE_ENTRY_BYTES = 64L * 1024L * 1024L;

    enum ListStatus {
        LISTED,
        PASSWORD_REQUIRED,
        UNSUPPORTED_FEATURE,
        FAILED,
        CHAIN_INVALID
    }

    enum ProbeStatus {
        NOT_REQUESTED,
        NOT_FOUND,
        SKIPPED_LARGE,
        OK,
        PASSWORD_REQUIRED,
        UNSUPPORTED_FEATURE,
        FAILED
    }

    static final class Probe {
        final String entryPath;
        final long entrySize;
        final ProbeStatus status;
        final long outputBytes;
        final String detail;

        Probe(@Nullable String entryPath,
              long entrySize,
              @NonNull ProbeStatus status,
              long outputBytes,
              @Nullable String detail) {
            this.entryPath = entryPath == null ? "" : entryPath;
            this.entrySize = entrySize;
            this.status = status;
            this.outputBytes = outputBytes;
            this.detail = detail == null ? "" : detail;
        }
    }

    static final class Row {
        final String selectedPath;
        final String firstVolumePath;
        final ListStatus listStatus;
        final int entryCount;
        final int imageEntryCount;
        final Probe firstFileProbe;
        final Probe firstImageProbe;
        final String detail;

        Row(@NonNull String selectedPath,
            @NonNull String firstVolumePath,
            @NonNull ListStatus listStatus,
            int entryCount,
            int imageEntryCount,
            @NonNull Probe firstFileProbe,
            @NonNull Probe firstImageProbe,
            @Nullable String detail) {
            this.selectedPath = selectedPath;
            this.firstVolumePath = firstVolumePath;
            this.listStatus = listStatus;
            this.entryCount = entryCount;
            this.imageEntryCount = imageEntryCount;
            this.firstFileProbe = firstFileProbe;
            this.firstImageProbe = firstImageProbe;
            this.detail = detail == null ? "" : detail;
        }
    }

    private final List<Row> rows;

    private RarFixtureMatrixReport(@NonNull List<Row> rows) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @NonNull
    static RarFixtureMatrixReport generate(@NonNull File root,
                                           @Nullable char[] password,
                                           @NonNull File probeOutputDir) {
        return generate(root, password, DEFAULT_MAX_DEPTH, probeOutputDir);
    }

    @NonNull
    static RarFixtureMatrixReport generate(@NonNull File root,
                                           @Nullable char[] password,
                                           int maxDepth,
                                           @NonNull File probeOutputDir) {
        List<File> candidates = root.isDirectory()
                ? scanCandidates(root, Math.max(0, maxDepth))
                : Collections.singletonList(root);
        return generateFromCandidates(candidates, password, probeOutputDir);
    }

    @NonNull
    static RarFixtureMatrixReport generateFromCandidates(@NonNull List<File> candidates,
                                                         @Nullable char[] password,
                                                         @NonNull File probeOutputDir) {
        Map<String, File> canonicalFirstVolumes = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile() || !looksLikeRarCandidate(candidate)) continue;
            RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(candidate);
            String key = safeCanonicalPath(resolution.firstVolume());
            if (canonicalFirstVolumes.containsKey(key)) continue;
            canonicalFirstVolumes.put(key, candidate);
            rows.add(analyzeCandidate(candidate, resolution, password, probeOutputDir));
            if (rows.size() >= MAX_CANDIDATES) break;
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return a.firstVolumePath.compareToIgnoreCase(b.firstVolumePath);
            }
        });
        return new RarFixtureMatrixReport(rows);
    }

    @NonNull
    List<Row> rows() {
        return rows;
    }

    int listedCount() {
        int count = 0;
        for (Row row : rows) if (row.listStatus == ListStatus.LISTED) count++;
        return count;
    }

    int firstFileOkCount() {
        int count = 0;
        for (Row row : rows) if (row.firstFileProbe.status == ProbeStatus.OK) count++;
        return count;
    }

    int firstImageOkCount() {
        int count = 0;
        for (Row row : rows) if (row.firstImageProbe.status == ProbeStatus.OK) count++;
        return count;
    }

    @NonNull
    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAR real-fixture extraction matrix\n\n");
        sb.append("This matrix is a smoke-test record for real RAR fixtures. It separates listability, ")
                .append("first-file extraction, and first-image extraction. It is not a claim of complete RAR support.\n\n");
        sb.append("- total candidates: ").append(rows.size()).append('\n');
        sb.append("- listed: ").append(listedCount()).append('\n');
        sb.append("- first file extracted: ").append(firstFileOkCount()).append('\n');
        sb.append("- first image extracted: ").append(firstImageOkCount()).append('\n');
        sb.append("- libarchive available in this JVM: ").append(RarLibarchiveFallback.isAvailable()).append("\n\n");
        sb.append("| Archive | First volume | List | Entries | Images | First file probe | First image probe | Detail |\n");
        sb.append("|---|---|---:|---:|---:|---|---|---|\n");
        for (Row row : rows) {
            sb.append("| ").append(escape(row.selectedPath))
                    .append(" | ").append(escape(row.firstVolumePath))
                    .append(" | ").append(row.listStatus)
                    .append(" | ").append(row.entryCount)
                    .append(" | ").append(row.imageEntryCount)
                    .append(" | ").append(escape(probeLabel(row.firstFileProbe)))
                    .append(" | ").append(escape(probeLabel(row.firstImageProbe)))
                    .append(" | ").append(escape(row.detail))
                    .append(" |\n");
        }
        return sb.toString();
    }

    @NonNull
    private static Row analyzeCandidate(@NonNull File selected,
                                        @NonNull RarVolumeChainResolution resolution,
                                        @Nullable char[] password,
                                        @NonNull File probeOutputDir) {
        try {
            resolution.requireReadableChain();
        } catch (IOException e) {
            return new Row(displayPath(selected), displayPath(resolution.firstVolume()),
                    ListStatus.CHAIN_INVALID, 0, 0,
                    notRequested(), notRequested(), message(e));
        }

        List<ArchiveSupport.EntryInfo> entries;
        try {
            entries = ArchiveSupport.listEntries(resolution.firstVolume(), password);
        } catch (ArchiveSupport.PasswordRequiredException e) {
            return new Row(displayPath(selected), displayPath(resolution.firstVolume()),
                    ListStatus.PASSWORD_REQUIRED, 0, 0,
                    notRequested(), notRequested(), message(e));
        } catch (ArchiveSupport.UnsupportedArchiveFeatureException e) {
            return new Row(displayPath(selected), displayPath(resolution.firstVolume()),
                    ListStatus.UNSUPPORTED_FEATURE, 0, 0,
                    notRequested(), notRequested(), message(e));
        } catch (IOException | SecurityException e) {
            return new Row(displayPath(selected), displayPath(resolution.firstVolume()),
                    ListStatus.FAILED, 0, 0,
                    notRequested(), notRequested(), message(e));
        }

        ArchiveSupport.EntryInfo firstFile = null;
        ArchiveSupport.EntryInfo firstImage = null;
        int imageCount = 0;
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry == null || entry.directory) continue;
            if (firstFile == null) firstFile = entry;
            if (isImageName(entry.name())) {
                imageCount++;
                if (firstImage == null) firstImage = entry;
            }
        }
        Probe fileProbe = probeEntry(resolution.firstVolume(), firstFile, password, probeOutputDir, "first-file");
        Probe imageProbe = probeEntry(resolution.firstVolume(), firstImage, password, probeOutputDir, "first-image");
        return new Row(displayPath(selected), displayPath(resolution.firstVolume()),
                ListStatus.LISTED, entries.size(), imageCount, fileProbe, imageProbe, "");
    }

    @NonNull
    private static Probe probeEntry(@NonNull File archive,
                                    @Nullable ArchiveSupport.EntryInfo entry,
                                    @Nullable char[] password,
                                    @NonNull File probeOutputDir,
                                    @NonNull String kind) {
        if (entry == null) return new Probe("", 0L, ProbeStatus.NOT_FOUND, 0L, "");
        if (entry.size > MAX_PROBE_ENTRY_BYTES) {
            return new Probe(entry.path, entry.size, ProbeStatus.SKIPPED_LARGE, 0L,
                    "entry larger than probe cap " + MAX_PROBE_ENTRY_BYTES);
        }
        if (!probeOutputDir.exists() && !probeOutputDir.mkdirs()) {
            return new Probe(entry.path, entry.size, ProbeStatus.FAILED, 0L,
                    "could not create probe output dir");
        }
        File out = new File(probeOutputDir, safeName(archive.getName()) + "_" + kind + "_" + safeName(entry.name()));
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive, entry.path, out, password);
        if (result.success && out.exists() && out.isFile() && out.length() > 0L) {
            return new Probe(entry.path, entry.size, ProbeStatus.OK, out.length(), "");
        }
        ProbeStatus status;
        switch (result.failure) {
            case PASSWORD_REQUIRED:
                status = ProbeStatus.PASSWORD_REQUIRED;
                break;
            case UNSUPPORTED_FEATURE:
                status = ProbeStatus.UNSUPPORTED_FEATURE;
                break;
            case FAILED:
            case NONE:
            default:
                status = ProbeStatus.FAILED;
                break;
        }
        return new Probe(entry.path, entry.size, status, out.exists() ? out.length() : 0L, result.detail);
    }

    @NonNull
    private static Probe notRequested() {
        return new Probe("", 0L, ProbeStatus.NOT_REQUESTED, 0L, "");
    }

    @NonNull
    private static String probeLabel(@NonNull Probe probe) {
        StringBuilder sb = new StringBuilder();
        sb.append(probe.status);
        if (probe.entryPath.length() > 0) sb.append(" ").append(probe.entryPath);
        if (probe.outputBytes > 0L) sb.append(" bytes=").append(probe.outputBytes);
        if (probe.detail.length() > 0) sb.append(" (").append(probe.detail).append(")");
        return sb.toString();
    }

    @NonNull
    private static List<File> scanCandidates(@NonNull File root, int maxDepth) {
        List<File> result = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, 0));
        while (!queue.isEmpty() && result.size() < MAX_CANDIDATES) {
            Node node = queue.removeFirst();
            File file = node.file;
            if (file == null || isGeneratedOrBuildDir(file)) continue;
            if (file.isFile()) {
                if (looksLikeRarCandidate(file)) result.add(file);
                continue;
            }
            if (!file.isDirectory() || node.depth >= maxDepth) continue;
            File[] children = file.listFiles();
            if (children == null) continue;
            for (File child : children) queue.addLast(new Node(child, node.depth + 1));
        }
        return result;
    }

    private static boolean looksLikeRarCandidate(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".rar") || name.endsWith(".cbr")) return true;
        if (name.matches(".*\\.r\\d{2,3}$")) return true;
        return name.endsWith(".exe") || name.endsWith(".sfx") || name.endsWith(".bin");
    }

    private static boolean isGeneratedOrBuildDir(@NonNull File file) {
        String name = file.getName();
        String path = file.getPath().replace(File.separatorChar, '/');
        return ".git".equals(name)
                || ".gradle".equals(name)
                || path.endsWith("/app/build");
    }

    private static boolean isImageName(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
                || lower.endsWith(".avif") || lower.endsWith(".heic") || lower.endsWith(".heif");
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
    private static String safeName(@Nullable String text) {
        if (text == null || text.trim().length() == 0) return "entry";
        String safe = text.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
        return safe.length() == 0 ? "entry" : safe;
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
