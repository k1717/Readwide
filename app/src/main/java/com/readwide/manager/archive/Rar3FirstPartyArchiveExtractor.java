package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Archive-wide wrapper for the deliberately narrow first-party RAR3/RAR4 classic-LZ decoder.
 *
 * <p>libarchive remains the primary backend for normal compressed RAR. This class is used only
 * after the primary backend and the rewrite helpers cannot handle an archive. Keeping sequencing
 * here prevents {@link RarArchiveReader} from gaining another large compressed/solid branch table.</p>
 */
final class Rar3FirstPartyArchiveExtractor {
    private Rar3FirstPartyArchiveExtractor() {}

    /**
     * Native remains primary. The extended plain, single-volume fallback requires known CRCs,
     * a non-solid primer and explicit file boundaries. Stored-member solid runs stay excluded.
     */
    static boolean tryExtractArchiveLimitedFallback(@NonNull List<RarArchiveReader.RarEntry> entries,
                                                    @NonNull File targetDir,
                                                    @Nullable char[] password,
                                                    @Nullable FileOperationProgress progress,
                                                    @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (password != null && password.length > 0) return false;
        if (isArchiveLimitedFallbackAllowed(entries)) {
            return tryExtractArchive(entries, targetDir, password, progress, entryProgress);
        }
        if (!isCheckedArchiveAllowed(entries)) return false;
        Rar3SolidState state = new Rar3SolidState();
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory) continue;
            if (!entry.solid) state.reset();
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            if (entryProgress != null) entryProgress.onFile(entry.path);
            File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
            if (out == null) throw new IOException("Invalid RAR output path");
            extractChecked(entry, state, out, progress);
        }
        return true;
    }

    static boolean tryExtractSingleEntryLimitedFallback(@NonNull RarArchiveReader.RarEntry target,
                                                       @NonNull List<RarArchiveReader.RarEntry> entries,
                                                       @NonNull File outFile,
                                                       @Nullable FileOperationProgress progress) throws IOException {
        if (isLimitedNonSolidClassicLzFallbackCandidate(target)) {
            return tryExtractSingleEntry(target, entries, outFile, progress);
        }
        List<RarArchiveReader.RarEntry> sequence = checkedSequence(entries, target);
        if (sequence == null) return false;
        Rar3SolidState state = new Rar3SolidState();
        for (RarArchiveReader.RarEntry entry : sequence) {
            extractChecked(entry, state, entry == target ? outFile : null, progress);
        }
        return true;
    }

    private static boolean isCheckedCandidate(RarArchiveReader.RarEntry entry) {
        return entry != null && entry.rarVersion == 4 && isFirstPartyCompressedCandidate(entry)
                && entry.sourceArchive != null && entry.dataCrc >= 0
                && entry.unpackedSize >= 0 && entry.packedSize > 0;
    }

    private static boolean independentStart(RarArchiveReader.RarEntry entry) {
        if (!isCheckedCandidate(entry) || entry.solid) return false;
        Rar3PpmdBlockProbe.Result probe = Rar3PpmdBlockProbe.probe(entry);
        return probe.isClassicLz() || (probe.isPpmd() && (probe.rawFlags & 0x2000) != 0);
    }

    private static boolean isCheckedArchiveAllowed(List<RarArchiveReader.RarEntry> entries) {
        boolean primed = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory) continue;
            if (!isCheckedCandidate(entry)) return false;
            if (!entry.solid) { if (!independentStart(entry)) return false; primed = true; }
            else if (!primed) return false;
        }
        return primed;
    }

    /** Never infer a missing primer or probe table-less Huffman data as a mode header. */
    private static List<RarArchiveReader.RarEntry> checkedSequence(
            List<RarArchiveReader.RarEntry> entries, RarArchiveReader.RarEntry target) {
        int index = entries.indexOf(target);
        if (index < 0 || !isCheckedCandidate(target)) return null;
        java.util.LinkedList<RarArchiveReader.RarEntry> sequence = new java.util.LinkedList<>();
        for (int i = index; i >= 0; i--) {
            RarArchiveReader.RarEntry entry = entries.get(i);
            if (entry == null || entry.directory) continue;
            if (!isCheckedCandidate(entry)) return null;
            sequence.addFirst(entry);
            if (!entry.solid) return independentStart(entry) ? sequence : null;
        }
        return null;
    }

    private static void extractChecked(RarArchiveReader.RarEntry entry, Rar3SolidState state,
            File out, FileOperationProgress progress) throws IOException {
        Rar3UnpackContext context = solidSequenceContext(entry, state);
        context.requireFileBoundary();
        if (out == null) { Rar3Unpacker.unpackSolidPrimerToDiscard(context, progress); return; }
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
            Rar3Unpacker.unpack(context, out, progress);
            guard.commit();
        }
    }

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
                if (!out.exists() && !out.mkdirs()) return false;
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
        if (!isCheckedCandidate(entry) || entry.solid) return false;
        // Preserve the existing size-terminated non-solid route. The extended route
        // below accepts PPMd starts/solid sequences only with explicit file boundaries.
        Rar3PpmdBlockProbe.Result probe = Rar3PpmdBlockProbe.probe(entry);
        return probe.isClassicLz();
    }

    private static boolean hasLimitedNonSolidClassicLzFallbackCandidate(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null && isLimitedNonSolidClassicLzFallbackCandidate(entry)) return true;
        }
        return false;
    }

    static boolean isArchiveLimitedFallbackAllowed(@NonNull List<RarArchiveReader.RarEntry> entries) {
        boolean sawLimitedCompressedCandidate = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory) continue;
            if (isLimitedNonSolidClassicLzFallbackCandidate(entry)) {
                sawLimitedCompressedCandidate = true;
                continue;
            }
            if (isPlainRar3Or4StoredMemberForArchiveLimitedFallback(entry)) continue;
            return false;
        }
        return sawLimitedCompressedCandidate;
    }

    private static boolean isPlainRar3Or4StoredMemberForArchiveLimitedFallback(
            @NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion < 5
                && !entry.directory
                && !entry.encrypted()
                && !entry.solid
                && !entry.splitBefore
                && !entry.splitAfter
                && RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
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
