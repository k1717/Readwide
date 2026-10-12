package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Routes the small first-party RAR3/RAR4 special cases that sit between the generic stored
 * reader and the libarchive primary backend.
 *
 * <p>This class owns scoped rewrites into a temporary RAR4 for libarchive and the archive-wide
 * loop that falls back to independent first-party LZ/PPMd decoding when eligible. Compression
 * and AES/split stream handling stay in their dedicated decoders and payload helpers.</p>
 */
final class Rar3Or4SpecialCaseExtractor {
    private Rar3Or4SpecialCaseExtractor() {}

    static boolean tryExtractArchiveWithDecryptedCopy(@NonNull File archive,
                                                      @NonNull List<RarArchiveReader.RarEntry> entries,
                                                      @NonNull File targetDir,
                                                      @Nullable char[] password,
                                                      @Nullable FileOperationProgress progress,
                                                      @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        checkpoint(progress);
        if (!RarLibarchiveFallback.isAvailable()) return false;
        if (password == null || password.length == 0) return false;
        if (!RarFeatureClassifier.hasRar3Or4EncryptedRewriteCandidate(entries)) return false;
        File decrypted = null;
        try {
            decrypted = Rar4EncryptedPayloadRewriter.buildDecryptedCopy(archive, password, progress);
            boolean extracted = RarLibarchiveFallback.extractArchiveIntoDirectory(
                    decrypted, targetDir, null, progress, entryProgress);
            if (extracted) verifyExtractedArchiveCrc(entries, targetDir, progress);
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
            rethrowCancellation(e, progress);
            return false;
        } finally {
            deleteQuietly(decrypted);
        }
    }

    static boolean tryExtractEntryWithDecryptedCopy(@NonNull RarArchiveReader.RarEntry entry,
                                                    @NonNull File outFile,
                                                    @Nullable char[] password,
                                                    @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        if (!RarLibarchiveFallback.isAvailable()) return false;
        if (password == null || password.length == 0) return false;
        if (!RarFeatureClassifier.isRar3Or4EncryptedRewriteCandidate(entry)) return false;
        if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
        File decrypted = null;
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            decrypted = Rar4EncryptedPayloadRewriter.buildDecryptedCopy(entry.sourceArchive, password, progress);
            boolean extracted = RarLibarchiveFallback.extractSingleEntry(
                    decrypted, entry.path, outFile, null, progress);
            if (extracted) {
                RarStoredPayloadIO.verifyCrc(entry, outFile, null, progress);
                guard.commit();
            }
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
            rethrowCancellation(e, progress);
            return false;
        } finally {
            deleteQuietly(decrypted);
        }
    }

    static boolean tryExtractArchiveWithSpecialCases(@NonNull List<RarArchiveReader.RarEntry> entries,
                                                     @NonNull File targetDir,
                                                     @Nullable char[] password,
                                                     @Nullable FileOperationProgress progress,
                                                     @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        checkpoint(progress);
        ArchivePlan plan = prepareArchivePlan(entries, password, Rar3PpmdBlockProbe::probe);
        if (plan == null) return false;
        List<OutputMark> writtenOutputs = new ArrayList<>();
        boolean success = false;
        try {
            boolean sawEntry = false;
            for (PreparedEntry step : plan.steps) {
                RarArchiveReader.RarEntry entry = step.entry;
                checkpoint(progress);
                if (entryProgress != null) {
                    if (entry.directory) entryProgress.onDirectory(entry.path);
                    else entryProgress.onFile(entry.path);
                } else if (progress != null) {
                    progress.setDetail(entry.path);
                }
                File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
                if (out == null) throw new IOException("Invalid RAR output path");
                sawEntry = true;
                if (entry.directory) {
                    RarOutputFileGuard.ensureDirectory(out);
                    continue;
                }
                boolean existedBefore = out.exists();
                if (!step.extract(out, password, progress)) return false;
                writtenOutputs.add(new OutputMark(out, existedBefore));
            }
            success = sawEntry;
            return sawEntry;
        } finally {
            if (!success) deleteNewOutputs(writtenOutputs);
        }
    }

    static boolean isArchiveWideNonSolidSpecialCaseAllowed(@NonNull List<RarArchiveReader.RarEntry> entries,
                                                           @Nullable char[] password) throws IOException {
        return prepareArchivePlan(entries, password, Rar3PpmdBlockProbe::probe) != null;
    }

    enum Route { DIRECTORY, STORED, PLAIN_COMPRESSED, AES, SPLIT, AES_SPLIT }

    static final class ArchivePlan {
        final List<PreparedEntry> steps;
        ArchivePlan(List<PreparedEntry> steps) { this.steps = Collections.unmodifiableList(steps); }
    }

    static final class PreparedEntry {
        final RarArchiveReader.RarEntry entry;
        final Route route;
        final List<RarArchiveReader.RarEntry> chain;
        final Rar3DecodePlan plainPlan;

        PreparedEntry(RarArchiveReader.RarEntry entry, Route route,
                List<RarArchiveReader.RarEntry> chain, Rar3DecodePlan plainPlan) {
            this.entry = entry; this.route = route;
            this.chain = Collections.unmodifiableList(chain); this.plainPlan = plainPlan;
        }

        boolean extract(File out, char[] password, FileOperationProgress progress) throws IOException {
            switch (route) {
                case STORED:
                    RarArchiveReader.extractStoredEntry(entry, out, password, chain, progress);
                    return true;
                case PLAIN_COMPRESSED:
                    // Reuse the one-entry plan: no second probe or archive-wide target scan.
                    new Rar3FirstPartyArchiveExtractor.PlannedDecoder(plainPlan).extractNext(out, progress);
                    return true;
                case AES:
                    if (tryExtractEntryWithDecryptedCopy(entry, out, password, progress)) return true;
                    break;
                case SPLIT:
                    if (tryExtractSplitEntryWithRewrittenCopy(entry, chain, out, progress)) return true;
                    break;
                case AES_SPLIT:
                    if (tryExtractSplitEntryWithDecryptedCopy(entry, chain, out, password, progress)) return true;
                    break;
                default:
                    throw new IOException("RAR directory step cannot be decoded as a file");
            }
            checkpoint(progress);
            return Rar3IndependentPayloadExtractor.tryExtract(entry, chain, out, password, progress);
        }
    }

    /** Preflight all routes before writing. Solid compressed history belongs to other planners. */
    static ArchivePlan prepareArchivePlan(List<RarArchiveReader.RarEntry> source, char[] password,
            Rar3DecodePlan.StartProbe probe) throws IOException {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        IdentityHashMap<RarArchiveReader.RarEntry, RarArchiveReader.RarEntry> snapshots = new IdentityHashMap<>();
        for (RarArchiveReader.RarEntry entry : source) {
            checkpoint(null);
            // Keep identity through copying so the index can detect a reused part.
            if (entry != null) entries.add(snapshots.computeIfAbsent(entry, Rar3DecodePlan::snapshot));
        }
        RarVolumeChain.Index chains = RarVolumeChain.index(entries);
        List<PreparedEntry> steps = new ArrayList<>();
        boolean sawPayload = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            checkpoint(null);
            if (entry.directory) {
                steps.add(new PreparedEntry(entry, Route.DIRECTORY, Collections.singletonList(entry), null));
                continue;
            }
            if (entry.rarVersion >= 5) return null;
            if (entry.splitBefore) continue; // Membership was checked before preparing any route.
            boolean stored = RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
            if (entry.solid && !stored) return null;
            if (entry.encrypted() && (password == null || password.length == 0)) return null;
            List<RarArchiveReader.RarEntry> chain = chains.chain(entry);
            Route route;
            Rar3DecodePlan plainPlan = null;
            if (stored) {
                if (entry.encrypted() && !RarFeatureClassifier.isFirstPartyRar3Or4EncryptedStoredEntry(entry)) return null;
                route = Route.STORED;
            } else if (entry.encrypted() && entry.splitAfter) {
                if (!RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(chain)) return null;
                route = Route.AES_SPLIT;
            } else if (entry.encrypted()) {
                if (!RarFeatureClassifier.isRar3Or4EncryptedRewriteCandidate(entry)) return null;
                route = Route.AES;
            } else if (entry.splitAfter) {
                if (!RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(chain)) return null;
                route = Route.SPLIT;
            } else {
                plainPlan = Rar3DecodePlan.compile(Collections.singletonList(entry), probe);
                if (plainPlan == null) return null;
                route = Route.PLAIN_COMPRESSED;
            }
            steps.add(new PreparedEntry(entry, route, chain, plainPlan));
            sawPayload = true;
        }
        return sawPayload ? new ArchivePlan(steps) : null;
    }

    private static void checkpoint(FileOperationProgress progress) throws IOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new java.io.InterruptedIOException("RAR extraction cancelled");
        }
    }

    /** A cancelled rewrite is terminal, not an invitation to start another backend. */
    private static void rethrowCancellation(Exception failure, FileOperationProgress progress) throws IOException {
        if (failure instanceof java.io.InterruptedIOException) throw (IOException) failure;
        if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled())) {
            java.io.InterruptedIOException cancelled = new java.io.InterruptedIOException("RAR extraction cancelled");
            cancelled.initCause(failure);
            throw cancelled;
        }
    }

    static boolean tryExtractSplitEntryWithDecryptedCopy(@NonNull RarArchiveReader.RarEntry entry,
                                                         @NonNull List<RarArchiveReader.RarEntry> entries,
                                                         @NonNull File outFile,
                                                         @Nullable char[] password,
                                                         @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        if (!RarLibarchiveFallback.isAvailable()) return false;
        if (password == null || password.length == 0) return false;
        if (!RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(entry)) return false;
        List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(entry, entries);
        if (!RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(chain)) return false;
        File decrypted = null;
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
            decrypted = Rar4EncryptedSplitPayloadRewriter.buildSingleEntryDecryptedCopy(
                    entry.sourceArchive,
                    entry.dataOffset,
                    RarVolumeChain.payloadSegments(chain),
                    RarVolumeChain.last(chain).dataCrc,
                    password,
                    progress);
            boolean extracted = RarLibarchiveFallback.extractSingleEntry(
                    decrypted,
                    entry.path,
                    outFile,
                    null,
                    progress);
            if (extracted) {
                RarStoredPayloadIO.verifyCrc(RarVolumeChain.last(chain), outFile, null, progress);
                guard.commit();
            }
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
            rethrowCancellation(e, progress);
            return false;
        } finally {
            deleteQuietly(decrypted);
        }
    }

    static boolean tryExtractSplitEntryWithRewrittenCopy(@NonNull RarArchiveReader.RarEntry entry,
                                                         @NonNull List<RarArchiveReader.RarEntry> entries,
                                                         @NonNull File outFile,
                                                         @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        if (!RarLibarchiveFallback.isAvailable()) return false;
        if (!RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(entry)) return false;
        List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(entry, entries);
        if (!RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(chain)) return false;
        File rewritten = null;
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            if (entry.sourceArchive == null) throw new IOException("RAR entry source volume is missing");
            rewritten = Rar4SplitPayloadRewriter.buildSingleEntryCopy(
                    entry.sourceArchive,
                    entry.dataOffset,
                    RarVolumeChain.payloadSegments(chain),
                    RarVolumeChain.last(chain).dataCrc,
                    progress);
            boolean extracted = RarLibarchiveFallback.extractSingleEntry(
                    rewritten,
                    entry.path,
                    outFile,
                    null,
                    progress);
            if (extracted) {
                RarStoredPayloadIO.verifyCrc(RarVolumeChain.last(chain), outFile, null, progress);
                guard.commit();
            }
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
            rethrowCancellation(e, progress);
            return false;
        } finally {
            deleteQuietly(rewritten);
        }
    }

    private static void deleteNewOutputs(@NonNull List<OutputMark> outputs) {
        for (int i = outputs.size() - 1; i >= 0; i--) {
            OutputMark mark = outputs.get(i);
            if (mark.existedBefore || mark.file == null || !mark.file.exists()) continue;
            try {
                //noinspection ResultOfMethodCallIgnored
                mark.file.delete();
            } catch (SecurityException ignored) {
            }
        }
    }

    private static final class OutputMark {
        final File file;
        final boolean existedBefore;

        OutputMark(@NonNull File file, boolean existedBefore) {
            this.file = file;
            this.existedBefore = existedBefore;
        }
    }

    private static void verifyExtractedArchiveCrc(@NonNull List<RarArchiveReader.RarEntry> entries,
                                                  @NonNull File targetDir,
                                                  @Nullable FileOperationProgress progress) throws IOException {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore || entry.dataCrc < 0) continue;
            File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
            if (out == null || !out.isFile()) continue;
            RarStoredPayloadIO.verifyCrc(entry, out, null, progress);
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file == null || !file.exists()) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (SecurityException ignored) {
        }
    }
}
