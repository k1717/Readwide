package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic compatibility report for RAR cases after excluding compressed-solid payloads.
 *
 * <p>This is not a support claim generator. It describes the current extraction boundary so the
 * app can separate libarchive-primary RAR compatibility from first-party Java gap reducers.</p>
 */
final class RarNonSolidCompatibilityReport {
    enum Compatibility {
        FIRST_PARTY_STORED,
        FIRST_PARTY_STORED_SPLIT,
        LIBARCHIVE_PRIMARY,
        REWRITE_THEN_LIBARCHIVE,
        LIMITED_CLASSIC_LZ_FALLBACK,
        FIRST_PARTY_GAP,
        EXCLUDED_SOLID,
        METADATA_ONLY
    }

    static final class Row {
        final String path;
        final int rarVersion;
        final int method;
        final boolean encrypted;
        final boolean split;
        final boolean solid;
        final RarBackendRoute.Kind route;
        final Compatibility compatibility;
        final String detail;

        private Row(@NonNull RarArchiveReader.RarEntry entry,
                    @NonNull RarBackendRoute.Kind route,
                    @NonNull Compatibility compatibility,
                    @NonNull String detail) {
            this.path = entry.path;
            this.rarVersion = entry.rarVersion;
            this.method = entry.method;
            this.encrypted = entry.encrypted();
            this.split = entry.splitBefore || entry.splitAfter;
            this.solid = entry.solid;
            this.route = route;
            this.compatibility = compatibility;
            this.detail = detail;
        }
    }

    private final List<Row> rows;

    private RarNonSolidCompatibilityReport(@NonNull List<Row> rows) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @NonNull
    static RarNonSolidCompatibilityReport fromEntries(@NonNull List<RarArchiveReader.RarEntry> entries) {
        List<Row> rows = new ArrayList<>();
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null) continue;
            rows.add(classify(entry));
        }
        return new RarNonSolidCompatibilityReport(rows);
    }

    @NonNull
    List<Row> rows() {
        return rows;
    }

    int nonSolidPayloadCount() {
        int count = 0;
        for (Row row : rows) {
            if (row.compatibility != Compatibility.EXCLUDED_SOLID
                    && row.compatibility != Compatibility.METADATA_ONLY) {
                count++;
            }
        }
        return count;
    }

    int excludedSolidCount() {
        return count(Compatibility.EXCLUDED_SOLID);
    }

    int firstPartyDirectCount() {
        return count(Compatibility.FIRST_PARTY_STORED)
                + count(Compatibility.FIRST_PARTY_STORED_SPLIT);
    }

    int libarchiveDependentCount() {
        return count(Compatibility.LIBARCHIVE_PRIMARY)
                + count(Compatibility.REWRITE_THEN_LIBARCHIVE);
    }

    int limitedFallbackCount() {
        return count(Compatibility.LIMITED_CLASSIC_LZ_FALLBACK);
    }

    int firstPartyGapCount() {
        return count(Compatibility.FIRST_PARTY_GAP);
    }

    int rar5StoredFirstPartyCount() {
        int count = 0;
        for (Row row : rows) {
            if (row.rarVersion >= 5
                    && (row.compatibility == Compatibility.FIRST_PARTY_STORED
                    || row.compatibility == Compatibility.FIRST_PARTY_STORED_SPLIT)) {
                count++;
            }
        }
        return count;
    }

    int rar5CompressedLibarchiveCount() {
        int count = 0;
        for (Row row : rows) {
            if (row.rarVersion >= 5 && row.compatibility == Compatibility.LIBARCHIVE_PRIMARY) {
                count++;
            }
        }
        return count;
    }

    int count(@NonNull Compatibility compatibility) {
        int count = 0;
        for (Row row : rows) if (row.compatibility == compatibility) count++;
        return count;
    }

    @NonNull
    String oneLineSummary() {
        return "nonSolid=" + nonSolidPayloadCount()
                + ", firstParty=" + firstPartyDirectCount()
                + ", libarchiveDependent=" + libarchiveDependentCount()
                + ", limitedFallback=" + limitedFallbackCount()
                + ", firstPartyGap=" + firstPartyGapCount()
                + ", excludedSolid=" + excludedSolidCount()
                + ", rar5StoredFirstParty=" + rar5StoredFirstPartyCount()
                + ", rar5CompressedLibarchive=" + rar5CompressedLibarchiveCount();
    }

    @NonNull
    String countsLabel() {
        Map<Compatibility, Integer> counts = new EnumMap<>(Compatibility.class);
        for (Row row : rows) {
            Integer old = counts.get(row.compatibility);
            counts.put(row.compatibility, old == null ? 1 : old + 1);
        }
        if (counts.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (Compatibility compatibility : Compatibility.values()) {
            Integer count = counts.get(compatibility);
            if (count == null || count == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(compatibility.name()).append('=').append(count);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    @NonNull
    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAR non-solid compatibility audit\n\n");
        sb.append("This report excludes compressed-solid payload support from the compatibility view. ")
                .append("RAR5 compressed payloads are treated as libarchive-primary/backend-dependent, not as a first-party Java decoder claim. ")
                .append("RAR5 stored/stored-split remains first-party limited, while RAR5 compressed remains delegated to libarchive.\n\n");
        sb.append("- ").append(oneLineSummary()).append('\n');
        sb.append("- counts: ").append(countsLabel()).append("\n\n");
        sb.append("| Entry | RAR | Method | Flags | Route | Compatibility | Detail |\n");
        sb.append("|---|---:|---:|---|---|---|---|\n");
        for (Row row : rows) {
            sb.append("| ").append(escape(row.path))
                    .append(" | ").append(row.rarVersion)
                    .append(" | ").append(methodLabel(row.method))
                    .append(" | ").append(flagsLabel(row))
                    .append(" | ").append(row.route.name())
                    .append(" | ").append(row.compatibility.name())
                    .append(" | ").append(escape(row.detail))
                    .append(" |\n");
        }
        return sb.toString();
    }

    @NonNull
    private static Row classify(@NonNull RarArchiveReader.RarEntry entry) {
        if (entry.directory) {
            RarBackendDecision decision = RarBackendRouter.decideEntry(entry);
            return new Row(entry, decision.route, Compatibility.METADATA_ONLY,
                    "directory metadata only");
        }
        RarBackendDecision decision = RarBackendRouter.decideEntry(entry);
        if (entry.splitBefore) {
            return new Row(entry, decision.route, Compatibility.METADATA_ONLY,
                    "split continuation is accounted for through the first split part");
        }
        if (entry.solid && !isStored(entry)) {
            return new Row(entry, decision.route, Compatibility.EXCLUDED_SOLID,
                    "compressed-solid payload intentionally excluded from this audit");
        }
        if (entry.rarVersion >= 5) {
            if (entry.method == 0) {
                return new Row(entry, decision.route,
                        entry.splitBefore || entry.splitAfter
                                ? Compatibility.FIRST_PARTY_STORED_SPLIT
                                : Compatibility.FIRST_PARTY_STORED,
                        entry.encrypted()
                                ? "RAR5 stored AES/KDF path is first-party limited and password-dependent"
                                : "RAR5 stored method-0 path is first-party limited");
            }
            return new Row(entry, decision.route, Compatibility.LIBARCHIVE_PRIMARY,
                    "RAR5 compressed non-solid payload is libarchive-primary; first-party Java compressed RAR5 remains a gap");
        }
        if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
            return new Row(entry, decision.route,
                    entry.splitBefore || entry.splitAfter
                            ? Compatibility.FIRST_PARTY_STORED_SPLIT
                            : Compatibility.FIRST_PARTY_STORED,
                    entry.encrypted()
                            ? "RAR3/RAR4 stored AES path is first-party limited and password-dependent"
                            : "RAR3/RAR4 stored path is first-party with CRC verification");
        }
        if (RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(entry)
                || RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(entry)
                || RarFeatureClassifier.isRar3Or4EncryptedRewriteCandidate(entry)) {
            return new Row(entry, decision.route, Compatibility.REWRITE_THEN_LIBARCHIVE,
                    "visible-header non-solid compressed RAR3/RAR4 can be rewritten/decrypted into a temporary RAR4 and delegated to libarchive");
        }
        if (Rar3FirstPartyArchiveExtractor.isLimitedNonSolidClassicLzFallbackCandidate(entry)) {
            return new Row(entry, decision.route, Compatibility.LIMITED_CLASSIC_LZ_FALLBACK,
                    "non-solid classic-LZ has libarchive primary plus a narrow CRC-verified first-party fallback");
        }
        if (RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method)) {
            return new Row(entry, decision.route, Compatibility.LIBARCHIVE_PRIMARY,
                    "normal non-solid compressed RAR3/RAR4 is libarchive-primary; first-party coverage depends on the narrower gates");
        }
        return new Row(entry, decision.route, Compatibility.FIRST_PARTY_GAP,
                "payload is outside current first-party non-solid RAR extraction gates");
    }

    private static boolean isStored(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion >= 5
                ? entry.method == 0
                : RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
    }

    @NonNull
    private static String flagsLabel(@NonNull Row row) {
        List<String> flags = new ArrayList<>();
        if (row.encrypted) flags.add("encrypted");
        if (row.split) flags.add("split");
        if (row.solid) flags.add("solid");
        if (flags.isEmpty()) return "plain";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < flags.size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(flags.get(i));
        }
        return sb.toString();
    }

    @NonNull
    private static String methodLabel(int method) {
        return String.format(Locale.ROOT, "0x%02x", method);
    }

    @NonNull
    private static String escape(@NonNull String value) {
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
