package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Real-fixture smoke matrix for non-RAR archive families.
 *
 * <p>This is intentionally diagnostic infrastructure, not a compatibility claim:
 * it records what the current build can list and extract from a local fixture
 * directory so README/support-boundary text can stay grounded in actual samples.</p>
 */
public final class ArchiveFixtureMatrixReport {
    public enum Status {
        OK,
        NOT_SUPPORTED,
        LIST_FAILED,
        NO_FILE_ENTRIES,
        EXTRACT_OK,
        PASSWORD_REQUIRED,
        BAD_PASSWORD,
        UNSUPPORTED_FEATURE,
        CORRUPT_ARCHIVE,
        FAILED
    }

    public static final class Row {
        @NonNull public final String fileName;
        @Nullable public final ArchiveSupport.Type type;
        @NonNull public final Status listStatus;
        public final int listedEntries;
        @Nullable public final String firstFilePath;
        @NonNull public final Status extractStatus;
        @Nullable public final String detail;

        Row(@NonNull String fileName,
            @Nullable ArchiveSupport.Type type,
            @NonNull Status listStatus,
            int listedEntries,
            @Nullable String firstFilePath,
            @NonNull Status extractStatus,
            @Nullable String detail) {
            this.fileName = fileName;
            this.type = type;
            this.listStatus = listStatus;
            this.listedEntries = listedEntries;
            this.firstFilePath = firstFilePath;
            this.extractStatus = extractStatus;
            this.detail = detail;
        }
    }

    @NonNull private final List<Row> rows;

    private ArchiveFixtureMatrixReport(@NonNull List<Row> rows) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @NonNull
    public List<Row> rows() {
        return rows;
    }

    @NonNull
    public static ArchiveFixtureMatrixReport generate(@NonNull File fixtureRoot,
                                                      @Nullable char[] password,
                                                      @Nullable File probeDir) {
        List<File> archives = collectSupportedCandidates(fixtureRoot);
        List<Row> rows = new ArrayList<>();
        if (probeDir != null && !probeDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            probeDir.mkdirs();
        }
        for (File archive : archives) rows.add(probeArchive(archive, password, probeDir));
        return new ArchiveFixtureMatrixReport(rows);
    }

    @NonNull
    private static Row probeArchive(@NonNull File archive,
                                    @Nullable char[] password,
                                    @Nullable File probeDir) {
        ArchiveSupport.Type type = ArchiveSupport.getSupportedArchiveType(archive);
        if (type == null) {
            return new Row(archive.getName(), null, Status.NOT_SUPPORTED, 0, null,
                    Status.NOT_SUPPORTED, null);
        }
        if (type == ArchiveSupport.Type.RAR) {
            return new Row(archive.getName(), type, Status.NOT_SUPPORTED, 0, null,
                    Status.NOT_SUPPORTED, "Use RarFixtureMatrixReport for RAR-specific boundary probes.");
        }

        List<ArchiveSupport.EntryInfo> entries;
        try {
            entries = ArchiveSupport.listEntries(archive, password);
        } catch (ArchiveSupport.PasswordRequiredException e) {
            return new Row(archive.getName(), type, Status.PASSWORD_REQUIRED, 0, null,
                    Status.PASSWORD_REQUIRED, e.getMessage());
        } catch (Exception e) {
            return new Row(archive.getName(), type, Status.LIST_FAILED, 0, null,
                    Status.FAILED, e.getMessage());
        }

        ArchiveSupport.EntryInfo firstFile = firstFile(entries);
        if (firstFile == null) {
            return new Row(archive.getName(), type, Status.NO_FILE_ENTRIES, entries.size(), null,
                    Status.NO_FILE_ENTRIES, null);
        }

        File out = outputFileForProbe(archive, firstFile, probeDir);
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(
                archive, firstFile.path, out, password);
        Status status = result.success ? Status.EXTRACT_OK : fromFailure(result.failure);
        if (!result.success) {
            try { out.delete(); } catch (SecurityException ignored) {}
        }
        return new Row(archive.getName(), type, Status.OK, entries.size(), firstFile.path,
                status, result.detail);
    }

    @Nullable
    private static ArchiveSupport.EntryInfo firstFile(@Nullable List<ArchiveSupport.EntryInfo> entries) {
        if (entries == null) return null;
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry != null && !entry.directory) return entry;
        }
        return null;
    }

    @NonNull
    private static File outputFileForProbe(@NonNull File archive,
                                           @NonNull ArchiveSupport.EntryInfo entry,
                                           @Nullable File probeDir) {
        File root = probeDir != null ? probeDir : new File(archive.getParentFile(), ".readwide_archive_probe");
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        String safe = (archive.getName() + "__" + entry.path).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 120) safe = safe.substring(0, 120);
        return new File(root, safe + ".out");
    }

    @NonNull
    private static Status fromFailure(@NonNull ArchiveSupport.ExtractionFailure failure) {
        switch (failure) {
            case PASSWORD_REQUIRED: return Status.PASSWORD_REQUIRED;
            case BAD_PASSWORD: return Status.BAD_PASSWORD;
            case UNSUPPORTED_FEATURE: return Status.UNSUPPORTED_FEATURE;
            case CORRUPT_ARCHIVE: return Status.CORRUPT_ARCHIVE;
            case NONE: return Status.EXTRACT_OK;
            case FAILED:
            default: return Status.FAILED;
        }
    }

    @NonNull
    private static List<File> collectSupportedCandidates(@NonNull File root) {
        List<File> out = new ArrayList<>();
        collectRecursive(root, out);
        Collections.sort(out, (a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));
        return out;
    }

    private static void collectRecursive(@Nullable File file, @NonNull List<File> out) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            if (ArchiveSupport.isSupportedArchiveFileName(file.getName())) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectRecursive(child, out);
    }

    @NonNull
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Archive fixture matrix\\n\\n");
        sb.append("This diagnostic report records list/extract behavior for non-RAR archive fixtures. ")
                .append("RAR uses `RarFixtureMatrixReport` because its support boundary is more specialized.\\n\\n");
        sb.append("| File | Type | List | Entries | First file | Extract | Detail |\\n");
        sb.append("| --- | --- | --- | ---: | --- | --- | --- |\\n");
        for (Row row : rows) {
            sb.append("| ").append(escape(row.fileName))
                    .append(" | ").append(row.type == null ? "-" : row.type.name())
                    .append(" | ").append(row.listStatus.name())
                    .append(" | ").append(row.listedEntries)
                    .append(" | ").append(escape(row.firstFilePath == null ? "-" : row.firstFilePath))
                    .append(" | ").append(row.extractStatus.name())
                    .append(" | ").append(escape(row.detail == null ? "" : row.detail))
                    .append(" |\\n");
        }
        return sb.toString();
    }

    @NonNull
    private static String escape(@NonNull String value) {
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
