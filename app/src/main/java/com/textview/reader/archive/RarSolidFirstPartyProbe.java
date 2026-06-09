package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Diagnostic probe for the narrow RAR3/RAR4 compressed-solid first-party path. */
final class RarSolidFirstPartyProbe {
    private static final long MAX_TARGET_UNPACKED_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_PRIMER_UNPACKED_BYTES = 32L * 1024L * 1024L;

    enum FirstPartyStatus {
        NO_CANDIDATE,
        SKIPPED_TOO_LARGE,
        SUCCESS,
        RETURNED_FALSE,
        GAP,
        FAILED
    }

    enum ReferenceStatus {
        NOT_REQUESTED,
        UNAVAILABLE,
        SUCCESS,
        FAILED,
        SKIPPED_TOO_LARGE
    }

    enum ComparisonStatus {
        NOT_COMPARED,
        MATCH,
        MISMATCH,
        REFERENCE_UNAVAILABLE,
        REFERENCE_FAILED,
        FIRST_PARTY_FAILED
    }

    static final class Candidate {
        final RarArchiveReader.RarEntry target;
        final int targetIndex;
        final int runStartIndex;
        final int primerEntryCount;
        final long targetUnpackedBytes;
        final long primerUnpackedBytes;
        final String detail;

        private Candidate(@NonNull RarArchiveReader.RarEntry target,
                          int targetIndex,
                          int runStartIndex,
                          int primerEntryCount,
                          long targetUnpackedBytes,
                          long primerUnpackedBytes,
                          @NonNull String detail) {
            this.target = target;
            this.targetIndex = targetIndex;
            this.runStartIndex = runStartIndex;
            this.primerEntryCount = primerEntryCount;
            this.targetUnpackedBytes = targetUnpackedBytes;
            this.primerUnpackedBytes = primerUnpackedBytes;
            this.detail = detail;
        }
    }

    static final class Result {
        @Nullable final Candidate candidate;
        final FirstPartyStatus firstPartyStatus;
        final ReferenceStatus referenceStatus;
        final ComparisonStatus comparisonStatus;
        final long firstPartySize;
        final long firstPartyCrc;
        final long referenceSize;
        final long referenceCrc;
        final String detail;
        final RarSolidProbeFailure failure;

        private Result(@Nullable Candidate candidate,
                       @NonNull FirstPartyStatus firstPartyStatus,
                       @NonNull ReferenceStatus referenceStatus,
                       @NonNull ComparisonStatus comparisonStatus,
                       long firstPartySize,
                       long firstPartyCrc,
                       long referenceSize,
                       long referenceCrc,
                       @NonNull String detail) {
            this.candidate = candidate;
            this.firstPartyStatus = firstPartyStatus;
            this.referenceStatus = referenceStatus;
            this.comparisonStatus = comparisonStatus;
            this.firstPartySize = firstPartySize;
            this.firstPartyCrc = firstPartyCrc;
            this.referenceSize = referenceSize;
            this.referenceCrc = referenceCrc;
            this.detail = detail;
            this.failure = RarSolidProbeFailure.classify(this);
        }

        @NonNull
        RarBackendRoute.Kind diagnosticRoute() {
            if (failure.cause == RarSolidProbeFailure.Cause.PPMD_MODEL_GAP) {
                return RarBackendRoute.Kind.CLEAN_UNSUPPORTED_PPMD;
            }
            if (failure.cause == RarSolidProbeFailure.Cause.VM_FILTER_GAP
                    || failure.cause == RarSolidProbeFailure.Cause.VM_EXECUTION_STATE_GAP) {
                return RarBackendRoute.Kind.CLEAN_UNSUPPORTED_VM;
            }
            if (candidate != null) return RarBackendRouter.decideEntry(candidate.target).route;
            return RarBackendRoute.Kind.CLEAN_UNSUPPORTED_UNKNOWN;
        }

        @NonNull
        String toMarkdown() {
            String target = candidate == null ? "-" : candidate.target.path;
            String source = candidate == null || candidate.target.sourceArchive == null
                    ? "-"
                    : candidate.target.sourceArchive.getName();
            StringBuilder sb = new StringBuilder();
            sb.append("# RAR compressed-solid first-party probe\n\n");
            sb.append("This is diagnostic only. libarchive remains the primary backend for normal and solid compressed RAR; ")
                    .append("the first-party path is probed only to measure remaining gaps.\n\n");
            sb.append("| Target | Source | First-party | Reference | Compare | Failure cause | Next step | FP size | FP CRC32 | Ref size | Ref CRC32 | Detail |\n");
            sb.append("|---|---|---|---|---|---|---|---:|---:|---:|---:|---|\n");
            sb.append("| ").append(escape(target))
                    .append(" | ").append(escape(source))
                    .append(" | ").append(diagnosticRoute())
                    .append(" | ").append(firstPartyStatus)
                    .append(" | ").append(referenceStatus)
                    .append(" | ").append(comparisonStatus)
                    .append(" | ").append(failure.cause)
                    .append(" | ").append(failure.nextStep)
                    .append(" | ").append(firstPartySize)
                    .append(" | ").append(hex(firstPartyCrc))
                    .append(" | ").append(referenceSize)
                    .append(" | ").append(hex(referenceCrc))
                    .append(" | ").append(escape(detail))
                    .append(" |\n");
            return sb.toString();
        }
    }

    private RarSolidFirstPartyProbe() {}

    @Nullable
    static Candidate selectSmallestClassicLzSolidCandidate(@NonNull List<RarArchiveReader.RarEntry> entries) {
        Candidate best = null;
        int runStart = 0;
        for (int i = 0; i < entries.size(); i++) {
            RarArchiveReader.RarEntry entry = entries.get(i);
            if (entry == null) continue;
            if (!entry.solid) runStart = i;
            if (!isEligibleTarget(entry)) continue;
            Candidate candidate = buildCandidate(entries, runStart, i);
            if (candidate == null) continue;
            if (best == null
                    || candidate.targetUnpackedBytes < best.targetUnpackedBytes
                    || (candidate.targetUnpackedBytes == best.targetUnpackedBytes
                    && candidate.primerUnpackedBytes < best.primerUnpackedBytes)) {
                best = candidate;
            }
        }
        return best;
    }

    @NonNull
    static Result probe(@NonNull List<RarArchiveReader.RarEntry> entries,
                        @Nullable char[] password,
                        @NonNull File workDir,
                        boolean compareWithLibarchive) {
        Candidate candidate = selectSmallestClassicLzSolidCandidate(entries);
        if (candidate == null) {
            return new Result(null,
                    FirstPartyStatus.NO_CANDIDATE,
                    compareWithLibarchive ? ReferenceStatus.NOT_REQUESTED : ReferenceStatus.NOT_REQUESTED,
                    ComparisonStatus.NOT_COMPARED,
                    -1L,
                    -1L,
                    -1L,
                    -1L,
                    "no eligible RAR3/RAR4 classic-LZ solid candidate");
        }
        if (candidate.targetUnpackedBytes > MAX_TARGET_UNPACKED_BYTES
                || candidate.primerUnpackedBytes > MAX_PRIMER_UNPACKED_BYTES) {
            return new Result(candidate,
                    FirstPartyStatus.SKIPPED_TOO_LARGE,
                    compareWithLibarchive ? ReferenceStatus.SKIPPED_TOO_LARGE : ReferenceStatus.NOT_REQUESTED,
                    ComparisonStatus.NOT_COMPARED,
                    -1L,
                    -1L,
                    -1L,
                    -1L,
                    "probe skipped by Android-safe size guard; " + candidate.detail);
        }
        if (!workDir.exists() && !workDir.mkdirs()) {
            return new Result(candidate,
                    FirstPartyStatus.FAILED,
                    compareWithLibarchive ? ReferenceStatus.NOT_REQUESTED : ReferenceStatus.NOT_REQUESTED,
                    ComparisonStatus.FIRST_PARTY_FAILED,
                    -1L,
                    -1L,
                    -1L,
                    -1L,
                    "could not create probe work directory");
        }

        File firstPartyOut = new File(workDir, "rar-solid-first-party.bin");
        File referenceOut = new File(workDir, "rar-solid-reference.bin");
        deleteIfExists(firstPartyOut);
        deleteIfExists(referenceOut);

        FirstPartyStatus firstStatus;
        long firstSize = -1L;
        long firstCrc = -1L;
        StringBuilder detail = new StringBuilder(candidate.detail);
        try {
            boolean ok = Rar3FirstPartyArchiveExtractor.tryExtractSingleEntry(
                    candidate.target,
                    entries,
                    firstPartyOut,
                    null);
            if (ok && firstPartyOut.isFile()) {
                firstStatus = FirstPartyStatus.SUCCESS;
                firstSize = firstPartyOut.length();
                firstCrc = crc32(firstPartyOut);
                detail.append("; first-party probe decoded target");
            } else {
                firstStatus = FirstPartyStatus.RETURNED_FALSE;
                detail.append("; first-party probe returned false");
            }
        } catch (RarArchiveReader.UnsupportedRarFeatureException e) {
            firstStatus = FirstPartyStatus.GAP;
            detail.append("; first-party gap: ").append(message(e));
        } catch (IOException | RuntimeException e) {
            firstStatus = FirstPartyStatus.FAILED;
            detail.append("; first-party failed: ").append(message(e));
        }

        ReferenceStatus referenceStatus = ReferenceStatus.NOT_REQUESTED;
        long refSize = -1L;
        long refCrc = -1L;
        if (compareWithLibarchive) {
            if (!RarLibarchiveFallback.isAvailable()) {
                referenceStatus = ReferenceStatus.UNAVAILABLE;
                detail.append("; libarchive unavailable: ").append(LibarchiveNativeBridge.backendStatus());
            } else if (candidate.target.sourceArchive == null) {
                referenceStatus = ReferenceStatus.FAILED;
                detail.append("; libarchive reference source is missing");
            } else {
                try {
                    boolean ok = RarLibarchiveFallback.extractSingleEntry(
                            candidate.target.sourceArchive,
                            candidate.target.path,
                            referenceOut,
                            password);
                    if (ok && referenceOut.isFile()) {
                        referenceStatus = ReferenceStatus.SUCCESS;
                        refSize = referenceOut.length();
                        refCrc = crc32(referenceOut);
                        detail.append("; libarchive reference extracted target");
                    } else {
                        referenceStatus = ReferenceStatus.FAILED;
                        detail.append("; libarchive reference returned false");
                    }
                } catch (IOException | RuntimeException e) {
                    referenceStatus = ReferenceStatus.FAILED;
                    detail.append("; libarchive reference failed: ").append(message(e));
                }
            }
        }

        if (firstStatus != FirstPartyStatus.SUCCESS) {
            File traceDir = new File(workDir, "solid-sequence-trace");
            Rar3SolidSequenceTrace trace = Rar3SolidSequenceTrace.capture(entries, candidate.target, traceDir, null);
            detail.append("; ").append(trace.compactSummary());
            deleteTree(traceDir);
        }

        ComparisonStatus comparison = comparison(firstStatus, referenceStatus, firstSize, firstCrc, refSize, refCrc);
        deleteIfExists(firstPartyOut);
        deleteIfExists(referenceOut);
        return new Result(candidate,
                firstStatus,
                referenceStatus,
                comparison,
                firstSize,
                firstCrc,
                refSize,
                refCrc,
                detail.toString());
    }

    @Nullable
    private static Candidate buildCandidate(@NonNull List<RarArchiveReader.RarEntry> entries,
                                            int runStart,
                                            int targetIndex) {
        RarArchiveReader.RarEntry target = entries.get(targetIndex);
        Rar3SolidSequencePlan plan = Rar3SolidSequencePlan.forTarget(entries, target);
        if (plan == null) return null;
        return new Candidate(target,
                targetIndex,
                plan.runStartIndex,
                plan.primerEntryCount,
                target.unpackedSize,
                plan.primerUnpackedBytes,
                plan.detail);
    }

    private static boolean isEligibleTarget(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.solid
                && Rar3FirstPartyArchiveExtractor.isFirstPartyCompressedCandidate(entry)
                && entry.sourceArchive != null
                && entry.unpackedSize >= 0L;
    }

    @NonNull
    static List<Result> probeReadableArchives(@NonNull File root,
                                              @Nullable char[] password,
                                              @NonNull File workRoot,
                                              boolean compareWithLibarchive) {
        List<File> candidates = root.isDirectory() ? scanCandidates(root, 4) : Collections.singletonList(root);
        List<Result> results = new ArrayList<>();
        int index = 0;
        for (File candidateFile : candidates) {
            if (candidateFile == null || !candidateFile.isFile()) continue;
            try {
                List<RarArchiveReader.RarEntry> entries = RarArchiveReader.readEntriesForSplitStoredDiagnostics(candidateFile, password);
                Candidate candidate = selectSmallestClassicLzSolidCandidate(entries);
                if (candidate == null) continue;
                File workDir = new File(workRoot, "solid-probe-" + index++);
                results.add(probe(entries, password, workDir, compareWithLibarchive));
            } catch (IOException | RuntimeException ignored) {
                // Boundary report already covers unreadable archives; this probe is only for readable solid candidates.
            }
        }
        return results;
    }

    @NonNull
    static String resultsToMarkdown(@NonNull List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAR compressed-solid first-party probe results\n\n");
        sb.append("Diagnostic only: libarchive remains primary; first-party solid extraction is not enabled from this report.\n\n");
        sb.append(RarSolidProbeSummary.fromResults(results).toMarkdown()).append("\n");
        sb.append("| Target | First-party | Reference | Compare | Failure cause | Next step | FP size | FP CRC32 | Ref size | Ref CRC32 | Detail |\n");
        sb.append("|---|---|---|---|---|---|---:|---:|---:|---:|---|\n");
        for (Result result : results) {
            String target = result.candidate == null ? "-" : result.candidate.target.path;
            sb.append("| ").append(escape(target))
                    .append(" | ").append(result.diagnosticRoute())
                    .append(" | ").append(result.firstPartyStatus)
                    .append(" | ").append(result.referenceStatus)
                    .append(" | ").append(result.comparisonStatus)
                    .append(" | ").append(result.failure.cause)
                    .append(" | ").append(result.failure.nextStep)
                    .append(" | ").append(result.firstPartySize)
                    .append(" | ").append(hex(result.firstPartyCrc))
                    .append(" | ").append(result.referenceSize)
                    .append(" | ").append(hex(result.referenceCrc))
                    .append(" | ").append(escape(result.detail))
                    .append(" |\n");
        }
        return sb.toString();
    }

    @NonNull
    private static List<File> scanCandidates(@NonNull File root, int maxDepth) {
        List<File> result = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, 0));
        while (!queue.isEmpty() && result.size() < 512) {
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
                    if (result.size() >= 512) break;
                }
            }
        }
        return result;
    }

    private static boolean looksLikeRarCandidate(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".rar")
                || name.endsWith(".cbr")
                || name.endsWith(".exe")
                || name.matches(".*\\.r\\d{2,3}$");
    }

    private static boolean isGeneratedOrBuildDir(@NonNull File file) {
        String name = file.getName();
        String path = file.getPath().replace(File.separatorChar, '/');
        return ".git".equals(name)
                || ".gradle".equals(name)
                || path.endsWith("/app/build");
    }

    private static final class Node {
        final File file;
        final int depth;

        Node(@NonNull File file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }

    private static ComparisonStatus comparison(@NonNull FirstPartyStatus firstStatus,
                                               @NonNull ReferenceStatus referenceStatus,
                                               long firstSize,
                                               long firstCrc,
                                               long refSize,
                                               long refCrc) {
        if (firstStatus != FirstPartyStatus.SUCCESS) return ComparisonStatus.FIRST_PARTY_FAILED;
        if (referenceStatus == ReferenceStatus.NOT_REQUESTED) return ComparisonStatus.NOT_COMPARED;
        if (referenceStatus == ReferenceStatus.UNAVAILABLE) return ComparisonStatus.REFERENCE_UNAVAILABLE;
        if (referenceStatus != ReferenceStatus.SUCCESS) return ComparisonStatus.REFERENCE_FAILED;
        return firstSize == refSize && firstCrc == refCrc ? ComparisonStatus.MATCH : ComparisonStatus.MISMATCH;
    }

    private static long crc32(@NonNull File file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) crc.update(buffer, 0, read);
            }
        }
        return crc.getValue() & 0xffffffffL;
    }

    private static void deleteIfExists(@NonNull File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private static void deleteTree(@Nullable File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        deleteIfExists(file);
    }

    @NonNull
    private static String message(@NonNull Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }

    @NonNull
    private static String hex(long crc) {
        return crc < 0L ? "-" : String.format(Locale.US, "0x%08x", crc & 0xffffffffL);
    }

    @NonNull
    private static String escape(@Nullable String text) {
        if (text == null || text.length() == 0) return "-";
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
