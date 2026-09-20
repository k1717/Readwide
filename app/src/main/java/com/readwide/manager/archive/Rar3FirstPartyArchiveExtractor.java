package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Scoped first-party RAR3/RAR4 extraction and shared execution of plain RAR4 decode plans.
 *
 * <p>libarchive remains the primary backend for normal compressed RAR. This class is used only
 * after the primary backend and the rewrite helpers cannot handle an archive. Keeping sequencing
 * here prevents {@link RarArchiveReader} from gaining another large compressed/solid branch table.</p>
 */
final class Rar3FirstPartyArchiveExtractor {
    private Rar3FirstPartyArchiveExtractor() {}

    /**
     * Native remains primary. The extended plain, single-volume fallback requires known CRCs,
     * a non-solid primer and explicit file boundaries for checked runs. Isolated classic-LZ
     * files retain size/CRC termination. Stored files cannot prime a following solid entry.
     */
    static boolean tryExtractArchiveLimitedFallback(@NonNull List<RarArchiveReader.RarEntry> entries,
                                                    @NonNull File targetDir,
                                                    @Nullable char[] password,
                                                    @Nullable FileOperationProgress progress,
                                                    @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (password != null && password.length > 0) return false;
        Rar3DecodePlan plan = Rar3DecodePlan.forArchive(entries);
        if (plan == null) return false;
        if (progress != null) progress.setTotalBytes(plan.totalUnpackedBytes);
        PlannedDecoder decoder = new PlannedDecoder(plan);
        for (int i = 0; i < plan.size(); i++) {
            RarArchiveReader.RarEntry entry = plan.step(i).entry;
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
            if (out == null) throw new IOException("Invalid RAR output path");
            if (entry.directory) {
                if (entryProgress != null) entryProgress.onDirectory(entry.path);
            } else {
                if (entryProgress != null) entryProgress.onFile(entry.path);
                else if (progress != null) progress.setDetail(entry.path);
            }
            decoder.extractNext(out, progress);
        }
        return true;
    }

    static boolean tryExtractSingleEntryLimitedFallback(@NonNull RarArchiveReader.RarEntry target,
                                                       @NonNull List<RarArchiveReader.RarEntry> entries,
                                                       @NonNull File outFile,
                                                       @Nullable FileOperationProgress progress) throws IOException {
        Rar3DecodePlan plan = Rar3DecodePlan.forTarget(entries, target);
        if (plan == null) return false;
        PlannedDecoder decoder = new PlannedDecoder(plan);
        for (int i = 0; i < plan.size(); i++) {
            decoder.extractNext(i == plan.size() - 1 ? outFile : null, progress);
        }
        return true;
    }

    /** Shared ordered executor for bulk, target-only and forward-reading plans. */
    static final class PlannedDecoder {
        private final Rar3DecodePlan plan;
        private Rar3SolidState state;
        private int position;
        private boolean retired;
        PlannedDecoder(Rar3DecodePlan plan) { this.plan = plan; }

        void extractNext(File out, FileOperationProgress progress) throws IOException {
            if (retired || position >= plan.size()) throw new IOException("RAR decode plan is exhausted or retired");
            try {
                if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
                    throw new IOException("RAR extraction cancelled");
                }
                Rar3DecodePlan.Step step = plan.step(position);
                RarArchiveReader.RarEntry entry = step.entry;
                Rar3UnpackContext context;
                switch (step.action) {
                    case DIRECTORY:
                        if (out != null && !out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create RAR output directory");
                        position++;
                        return;
                    case STORED:
                        state = null;
                        if (out == null) throw new IOException("Stored RAR verification requires a destination");
                        RarArchiveReader.extractStoredEntry(entry, out, null, plan.entries(), progress);
                        position++;
                        return;
                    case INDEPENDENT_LZ:
                        state = null;
                        context = Rar3UnpackContext.forEntry(entry.sourceArchive, entry.dataOffset,
                                entry.packedSize, entry.unpackedSize, entry.method, false, false, false, false, entry.dataCrc);
                        break;
                    case START_CHECKED:
                        state = new Rar3SolidState();
                        context = solidSequenceContext(entry, state);
                        context.requireFileBoundary();
                        break;
                    case CONTINUE_CHECKED:
                        if (state == null) throw new IOException("Missing checked RAR solid primer");
                        context = solidSequenceContext(entry, state);
                        context.requireFileBoundary();
                        break;
                    default:
                        throw new IOException("Unknown RAR plan action");
                }
                if (out == null) Rar3Unpacker.unpackToDiscard(context, progress);
                else {
                    try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
                        Rar3Unpacker.unpack(context, out, progress);
                        guard.commit();
                    }
                }
                position++;
            } catch (IOException | RuntimeException | Error failure) {
                retire();
                throw failure;
            }
        }
        void retire() { retired = true; state = null; }
    }

    /** Legacy diagnostic/test harness; production fallback uses the planned entry points above. */
    static boolean tryExtractArchive(@NonNull List<RarArchiveReader.RarEntry> entries,
                                     @NonNull File targetDir,
                                     @Nullable char[] password,
                                     @Nullable FileOperationProgress progress,
                                     @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (!hasCandidate(entries)) return false;
        if (password != null && password.length > 0 && hasEncryptedCompressed(entries)) return false;
        if (progress != null) progress.setTotalBytes(sumCandidateUnpackedBytes(entries));

        Rar3SolidState solidState = null;
        boolean sawEntry = false;
        boolean sawNonDirectoryDataBeforeCurrent = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.splitBefore) continue;
            if (progress != null && !progress.checkpoint()) return false;
            if (entryProgress != null) {
                if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                else entryProgress.onFile(entry.path);
            } else if (progress != null) {
                progress.setDetail(entry.path);
            }

            File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
            if (out == null) return false;
            sawEntry = true;
            if (entry.directory || entry.path.endsWith("/")) {
                if (!out.isDirectory() && !out.mkdirs()) {
                    throw new IOException("Cannot create RAR output directory");
                }
                continue;
            }

            if (isFirstPartyCompressedCandidate(entry)) {
                if (entry.solid) {
                    if (solidState == null || !solidState.initialized()) {
                        if (sawNonDirectoryDataBeforeCurrent) {
                            throw new RarArchiveReader.UnsupportedRarFeatureException(
                                    "RAR3/RAR4 solid compressed entry has no validated first-party classic-LZ primer");
                        }
                        solidState = new Rar3SolidState();
                        solidState.reset();
                    }
                    extractCompressedSolidSequence(entry, out, solidState, progress);
                } else {
                    solidState = new Rar3SolidState();
                    solidState.reset();
                    extractCompressedSolidSequence(entry, out, solidState, progress);
                }
                sawNonDirectoryDataBeforeCurrent = true;
                continue;
            }

            if (RarFeatureClassifier.isUnsupportedRar3Or4Payload(entry)) {
                throw RarFeatureClassifier.firstPartyRar3Or4Gap(entry, null);
            }
            if (!entry.solid && entry.method != 0) solidState = null;
            RarArchiveReader.extractStoredEntry(entry, out, password, entries, progress);
            sawNonDirectoryDataBeforeCurrent = true;
        }
        return sawEntry;
    }

    /** Legacy diagnostic probe; not the application's limited-fallback admission gate. */
    static boolean tryExtractSingleEntry(@NonNull RarArchiveReader.RarEntry target,
                                         @NonNull List<RarArchiveReader.RarEntry> entries,
                                         @NonNull File outFile,
                                         @Nullable FileOperationProgress progress) throws IOException {
        if (!isFirstPartyCompressedCandidate(target)) return false;
        if (!target.solid) {
            extractCompressedNonSolid(target, outFile, progress);
            return true;
        }

        Rar3SolidSequencePlan plan = Rar3SolidSequencePlan.forTarget(entries, target);
        if (plan == null) return false;
        Rar3SolidState solidState = new Rar3SolidState();
        for (RarArchiveReader.RarEntry entry : plan.sequenceEntries()) {
            if (!entry.solid) solidState.reset();
            if (entry == target) {
                extractCompressedSolidSequence(entry, outFile, solidState, progress);
                return true;
            }
            primeCompressedSolidSequence(entry, solidState, progress);
        }
        return false;
    }

    static boolean isFirstPartyCompressedCandidate(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion < 5
                && !entry.directory
                && !entry.encrypted()
                && !entry.splitBefore
                && !entry.splitAfter
                && RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method);
    }

    static boolean isLimitedNonSolidClassicLzFallbackCandidate(@NonNull RarArchiveReader.RarEntry entry) {
        if (!Rar3DecodePlan.isCompressedCandidate(entry) || entry.solid) return false;
        // Preserve the existing size-terminated non-solid route. The extended route
        // below accepts PPMd starts/solid sequences only with explicit file boundaries.
        Rar3PpmdBlockProbe.Result probe = Rar3PpmdBlockProbe.probe(entry);
        return probe.isClassicLz();
    }

    static boolean isArchiveLimitedFallbackAllowed(@NonNull List<RarArchiveReader.RarEntry> entries) throws IOException {
        Rar3DecodePlan plan = Rar3DecodePlan.forArchive(entries);
        return plan != null && plan.independentLzOnly;
    }

    private static boolean hasCandidate(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null && isFirstPartyCompressedCandidate(entry)) return true;
        }
        return false;
    }

    private static boolean hasEncryptedCompressed(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null
                    && entry.rarVersion < 5
                    && !entry.directory
                    && entry.encrypted()
                    && RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method)) {
                return true;
            }
        }
        return false;
    }

    private static long sumCandidateUnpackedBytes(@NonNull List<RarArchiveReader.RarEntry> entries) {
        long total = 0L;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) continue;
            if (entry.unpackedSize < 0L) return -1L;
            if (Long.MAX_VALUE - total < entry.unpackedSize) return Long.MAX_VALUE;
            total += entry.unpackedSize;
        }
        return total;
    }

    private static void extractCompressedNonSolid(@NonNull RarArchiveReader.RarEntry entry,
                                                  @NonNull File outFile,
                                                  @Nullable FileOperationProgress progress) throws IOException {
        if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
        Rar3UnpackContext context = Rar3UnpackContext.forEntry(
                entry.sourceArchive,
                entry.dataOffset,
                entry.packedSize,
                entry.unpackedSize,
                entry.method,
                false,
                false,
                false,
                false,
                entry.dataCrc);
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            Rar3Unpacker.unpack(context, outFile, progress);
            guard.commit();
        }
    }

    private static void extractCompressedSolidSequence(@NonNull RarArchiveReader.RarEntry entry,
                                                       @NonNull File outFile,
                                                       @NonNull Rar3SolidState solidState,
                                                       @Nullable FileOperationProgress progress) throws IOException {
        Rar3UnpackContext context = solidSequenceContext(entry, solidState);
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            Rar3Unpacker.unpack(context, outFile, progress);
            guard.commit();
        }
    }

    private static void primeCompressedSolidSequence(@NonNull RarArchiveReader.RarEntry entry,
                                                     @NonNull Rar3SolidState solidState,
                                                     @Nullable FileOperationProgress progress) throws IOException {
        Rar3UnpackContext context = solidSequenceContext(entry, solidState);
        Rar3Unpacker.unpackSolidPrimerToDiscard(context, progress);
    }

    @NonNull
    private static Rar3UnpackContext solidSequenceContext(@NonNull RarArchiveReader.RarEntry entry,
                                                          @NonNull Rar3SolidState solidState) throws IOException {
        if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
        return Rar3UnpackContext.forSolidSequenceEntry(
                entry.sourceArchive,
                entry.dataOffset,
                entry.packedSize,
                entry.unpackedSize,
                entry.method,
                entry.splitBefore,
                entry.splitAfter,
                false,
                entry.dataCrc,
                solidState);
    }
}
