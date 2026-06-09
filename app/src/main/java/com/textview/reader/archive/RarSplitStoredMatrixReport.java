package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small diagnostic report for the first-party stored split RAR path.
 *
 * <p>The report is intentionally limited to stored split candidates. It does not route
 * compressed split archives away from libarchive, and it does not make compressed split or
 * solid split first-party support claims. Its job is to make the existing first-party stored
 * split boundary testable and documentable before any compressed-solid work starts.</p>
 */
final class RarSplitStoredMatrixReport {
    enum Status {
        SUPPORTED,
        UNSUPPORTED,
        INVALID,
        IGNORED
    }

    enum Category {
        PLAIN_STORED_SPLIT,
        RAR4_AES_STORED_SPLIT,
        RAR5_AES_STORED_SPLIT,
        COMPRESSED_SPLIT,
        SOLID_SPLIT,
        MIXED_ENCRYPTION,
        ENCRYPTION_PARAMETERS_CHANGED,
        INCOMPLETE_CHAIN,
        INVALID_SPLIT_FLAGS,
        MISSING_SOURCE_VOLUME,
        DANGLING_CONTINUATION,
        NON_SPLIT_ENTRY,
        OTHER
    }

    static final class Row {
        final String path;
        final Status status;
        final Category category;
        final int partCount;
        final int rarVersion;
        final int method;
        final boolean encrypted;
        final boolean solid;
        final long packedBytes;
        final long unpackedBytes;
        final String detail;

        private Row(@NonNull String path,
                    @NonNull Status status,
                    @NonNull Category category,
                    int partCount,
                    int rarVersion,
                    int method,
                    boolean encrypted,
                    boolean solid,
                    long packedBytes,
                    long unpackedBytes,
                    @NonNull String detail) {
            this.path = path;
            this.status = status;
            this.category = category;
            this.partCount = partCount;
            this.rarVersion = rarVersion;
            this.method = method;
            this.encrypted = encrypted;
            this.solid = solid;
            this.packedBytes = packedBytes;
            this.unpackedBytes = unpackedBytes;
            this.detail = detail;
        }
    }

    private final List<Row> rows;

    private RarSplitStoredMatrixReport(@NonNull List<Row> rows) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @NonNull
    static RarSplitStoredMatrixReport analyzeArchive(@NonNull File archive,
                                                     @Nullable char[] password) throws IOException {
        return analyze(RarArchiveReader.readEntriesForSplitStoredDiagnostics(archive, password));
    }

    @NonNull
    static RarSplitStoredMatrixReport analyze(@NonNull List<RarArchiveReader.RarEntry> entries) {
        List<Row> rows = new ArrayList<>();
        Set<RarArchiveReader.RarEntry> consumed = Collections.newSetFromMap(
                new IdentityHashMap<RarArchiveReader.RarEntry, Boolean>());

        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null) continue;
            if (consumed.contains(entry)) continue;
            if (!entry.splitBefore && entry.splitAfter) {
                rows.add(analyzeFirstPart(entry, entries, consumed));
            } else if (entry.splitBefore && !entry.splitAfter) {
                rows.add(danglingContinuation(entry));
                consumed.add(entry);
            }
        }
        return new RarSplitStoredMatrixReport(rows);
    }

    @NonNull
    static RarSplitStoredMatrixReport analyzeIncludingNonSplit(@NonNull List<RarArchiveReader.RarEntry> entries) {
        RarSplitStoredMatrixReport splitOnly = analyze(entries);
        List<Row> rows = new ArrayList<>(splitOnly.rows);
        Set<RarArchiveReader.RarEntry> splitEntries = Collections.newSetFromMap(
                new IdentityHashMap<RarArchiveReader.RarEntry, Boolean>());
        for (Row row : rows) {
            for (RarArchiveReader.RarEntry entry : entries) {
                if (entry != null && entry.path.equals(row.path) && (entry.splitBefore || entry.splitAfter)) {
                    splitEntries.add(entry);
                }
            }
        }
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || splitEntries.contains(entry) || entry.splitBefore || entry.splitAfter) continue;
            rows.add(nonSplit(entry));
        }
        return new RarSplitStoredMatrixReport(rows);
    }

    @NonNull
    List<Row> rows() {
        return rows;
    }

    int supportedCount() {
        int count = 0;
        for (Row row : rows) if (row.status == Status.SUPPORTED) count++;
        return count;
    }

    int unsupportedCount() {
        int count = 0;
        for (Row row : rows) if (row.status == Status.UNSUPPORTED) count++;
        return count;
    }

    int invalidCount() {
        int count = 0;
        for (Row row : rows) if (row.status == Status.INVALID) count++;
        return count;
    }

    int ignoredCount() {
        int count = 0;
        for (Row row : rows) if (row.status == Status.IGNORED) count++;
        return count;
    }

    @NonNull
    String toMarkdownTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Path | Status | Category | Parts | RAR | Method | Packed | Detail |\n");
        sb.append("|---|---:|---|---:|---:|---:|---:|---|\n");
        for (Row row : rows) {
            sb.append("| ").append(escape(row.path))
                    .append(" | ").append(row.status)
                    .append(" | ").append(row.category)
                    .append(" | ").append(row.partCount)
                    .append(" | ").append(row.rarVersion)
                    .append(" | ").append(formatMethod(row.method))
                    .append(" | ").append(row.packedBytes)
                    .append(" | ").append(escape(row.detail))
                    .append(" |\n");
        }
        return sb.toString();
    }

    @NonNull
    private static Row analyzeFirstPart(@NonNull RarArchiveReader.RarEntry first,
                                        @NonNull List<RarArchiveReader.RarEntry> entries,
                                        @NonNull Set<RarArchiveReader.RarEntry> consumed) {
        try {
            RarSplitStoredPlan plan = RarSplitStoredPlan.fromFirstEntry(first, entries);
            for (RarArchiveReader.RarEntry part : plan.chain()) consumed.add(part);
            return fromPlan(first.path, plan);
        } catch (IOException e) {
            List<RarArchiveReader.RarEntry> partialChain = partialChain(first, entries);
            for (RarArchiveReader.RarEntry part : partialChain) consumed.add(part);
            return failure(first, partialChain, e);
        }
    }

    @NonNull
    private static Row fromPlan(@NonNull String path, @NonNull RarSplitStoredPlan plan) throws IOException {
        Category category;
        switch (plan.kind()) {
            case PLAIN_STORED:
                category = Category.PLAIN_STORED_SPLIT;
                break;
            case RAR4_AES_STORED:
                category = Category.RAR4_AES_STORED_SPLIT;
                break;
            case RAR5_AES_STORED:
                category = Category.RAR5_AES_STORED_SPLIT;
                break;
            default:
                category = Category.OTHER;
                break;
        }
        RarArchiveReader.RarEntry first = plan.chain().get(0);
        return new Row(
                path,
                Status.SUPPORTED,
                category,
                plan.chain().size(),
                first.rarVersion,
                first.method,
                plan.encrypted(),
                first.solid,
                plan.totalPackedSize(),
                plan.unpackedSize(),
                "first-party stored split plan is valid; extraction still verifies CRC");
    }

    @NonNull
    private static Row failure(@NonNull RarArchiveReader.RarEntry first,
                               @NonNull List<RarArchiveReader.RarEntry> chain,
                               @NonNull IOException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        Category category = classifyFailure(message, first);
        Status status = category == Category.INCOMPLETE_CHAIN
                || category == Category.INVALID_SPLIT_FLAGS
                || category == Category.MISSING_SOURCE_VOLUME
                ? Status.INVALID
                : Status.UNSUPPORTED;
        return new Row(
                first.path,
                status,
                category,
                Math.max(1, chain.size()),
                first.rarVersion,
                first.method,
                first.encrypted(),
                first.solid,
                sumPacked(chain),
                first.unpackedSize,
                message);
    }

    @NonNull
    private static Row danglingContinuation(@NonNull RarArchiveReader.RarEntry entry) {
        return new Row(
                entry.path,
                Status.INVALID,
                Category.DANGLING_CONTINUATION,
                1,
                entry.rarVersion,
                entry.method,
                entry.encrypted(),
                entry.solid,
                Math.max(0L, entry.packedSize),
                entry.unpackedSize,
                "split continuation appeared without a matching first part in this entry list");
    }

    @NonNull
    private static Row nonSplit(@NonNull RarArchiveReader.RarEntry entry) {
        return new Row(
                entry.path,
                Status.IGNORED,
                Category.NON_SPLIT_ENTRY,
                1,
                entry.rarVersion,
                entry.method,
                entry.encrypted(),
                entry.solid,
                Math.max(0L, entry.packedSize),
                entry.unpackedSize,
                "not a split payload candidate");
    }

    @NonNull
    private static Category classifyFailure(@NonNull String message,
                                            @NonNull RarArchiveReader.RarEntry first) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("compressed split")) return Category.COMPRESSED_SPLIT;
        if (lower.contains("solid rar split") || first.solid) return Category.SOLID_SPLIT;
        if (lower.contains("mixed encrypted")) return Category.MIXED_ENCRYPTION;
        if (lower.contains("parameters changed")) return Category.ENCRYPTION_PARAMETERS_CHANGED;
        if (lower.contains("missing rar split continuation") || lower.contains("incomplete rar split")) {
            return Category.INCOMPLETE_CHAIN;
        }
        if (lower.contains("invalid") && lower.contains("split")) return Category.INVALID_SPLIT_FLAGS;
        if (lower.contains("source volume")) return Category.MISSING_SOURCE_VOLUME;
        if (lower.contains("encrypted split")) return Category.ENCRYPTION_PARAMETERS_CHANGED;
        return Category.OTHER;
    }

    @NonNull
    private static List<RarArchiveReader.RarEntry> partialChain(@NonNull RarArchiveReader.RarEntry first,
                                                                @NonNull List<RarArchiveReader.RarEntry> entries) {
        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        chain.add(first);
        RarArchiveReader.RarEntry current = first;
        while (current.splitAfter) {
            RarArchiveReader.RarEntry next = null;
            int start = Math.max(0, entries.indexOf(current) + 1);
            for (int i = start; i < entries.size(); i++) {
                RarArchiveReader.RarEntry candidate = entries.get(i);
                if (candidate == null || candidate.directory) continue;
                if (candidate.splitBefore && candidate.path.equals(first.path)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null || chain.contains(next)) break;
            chain.add(next);
            current = next;
        }
        return chain;
    }

    private static long sumPacked(@NonNull List<RarArchiveReader.RarEntry> chain) {
        long total = 0L;
        for (RarArchiveReader.RarEntry entry : chain) {
            if (entry == null || entry.packedSize < 0L) continue;
            if (Long.MAX_VALUE - total < entry.packedSize) return Long.MAX_VALUE;
            total += entry.packedSize;
        }
        return total;
    }

    @NonNull
    private static String escape(@Nullable String text) {
        if (text == null) return "";
        return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    @NonNull
    private static String formatMethod(int method) {
        return "0x" + Integer.toHexString(method);
    }
}
