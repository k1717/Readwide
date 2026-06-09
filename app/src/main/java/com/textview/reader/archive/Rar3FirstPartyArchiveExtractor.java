package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

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
     * Product fallback gate: only verified non-solid classic-LZ entries are allowed here.
     * Solid sequencing helpers below remain available for diagnostics/probes, but are not
     * exposed as live fallback support.
     */
    static boolean tryExtractArchiveLimitedFallback(@NonNull List<RarArchiveReader.RarEntry> entries,
                                                    @NonNull File targetDir,
                                                    @Nullable char[] password,
                                                    @Nullable FileOperationProgress progress,
                                                    @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (password != null && password.length > 0) return false;
        if (!isArchiveLimitedFallbackAllowed(entries)) return false;
        return tryExtractArchive(entries, targetDir, password, progress, entryProgress);
    }

    static boolean tryExtractSingleEntryLimitedFallback(@NonNull RarArchiveReader.RarEntry target,
                                                       @NonNull List<RarArchiveReader.RarEntry> entries,
                                                       @NonNull File outFile,
                                                       @Nullable FileOperationProgress progress) throws IOException {
        if (!isLimitedNonSolidClassicLzFallbackCandidate(target)) return false;
        return tryExtractSingleEntry(target, entries, outFile, progress);
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
        File tempDir = null;
        try {
            tempDir = createTempDir(outFile);
            for (RarArchiveReader.RarEntry entry : plan.sequenceEntries()) {
                File out = entry == target ? outFile : File.createTempFile("rar3-solid-primer-", ".bin", tempDir);
                if (!entry.solid) solidState.reset();
                extractCompressedSolidSequence(entry, out, solidState, progress);
                if (entry != target && out.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
                if (entry == target) return true;
            }
            return false;
        } finally {
            deleteTree(tempDir);
        }
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
        if (!isFirstPartyCompressedCandidate(entry) || entry.solid) return false;
        // RAR3/RAR4 methods 0x31..0x35 cover both classic-LZ and PPMd. The live
        // Java fallback is only the verified classic-LZ subset; PPMd must stay
        // libarchive-owned until the statistical model is implemented and CRC-verified.
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
        if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
        Rar3UnpackContext context = Rar3UnpackContext.forSolidSequenceEntry(
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
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            Rar3Unpacker.unpack(context, outFile, progress);
            guard.commit();
        }
    }

    @NonNull
    private static File createTempDir(@NonNull File outFile) throws IOException {
        File base = outFile.getParentFile();
        if (base == null) base = new File(System.getProperty("java.io.tmpdir"));
        File dir = File.createTempFile("rar3-solid-primer-", ".tmp", base);
        if (!dir.delete() || !dir.mkdirs()) throw new IOException("Could not create RAR solid temp directory");
        return dir;
    }

    private static void deleteTree(@Nullable File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (SecurityException ignored) {
        }
    }
}
