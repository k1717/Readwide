package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes the small first-party RAR3/RAR4 special cases that sit between the generic stored
 * reader and the libarchive primary backend.
 *
 * <p>The full RAR3/RAR4 compressed unpacker is intentionally not hidden here. This class only
 * owns the cases that can be safely rewritten into an ordinary temporary RAR4 and then handed
 * back to libarchive, plus the archive-wide loop that applies those cases without growing
 * {@link RarArchiveReader} again.</p>
 */
final class Rar3Or4SpecialCaseExtractor {
    private Rar3Or4SpecialCaseExtractor() {}

    static boolean tryExtractArchiveWithDecryptedCopy(@NonNull File archive,
                                                      @NonNull List<RarArchiveReader.RarEntry> entries,
                                                      @NonNull File targetDir,
                                                      @Nullable char[] password,
                                                      @Nullable FileOperationProgress progress,
                                                      @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        if (!RarLibarchiveFallback.isAvailable()) return false;
        if (password == null || password.length == 0) return false;
        if (!RarFeatureClassifier.hasRar3Or4EncryptedRewriteCandidate(entries)) return false;
        File decrypted = null;
        try {
            decrypted = Rar4EncryptedPayloadRewriter.buildDecryptedCopy(archive, password, progress);
            boolean extracted = RarLibarchiveFallback.extractArchiveIntoDirectory(
                    decrypted, targetDir, null, progress, entryProgress);
            if (extracted) verifyExtractedArchiveCrc(entries, targetDir);
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
            return false;
        } finally {
            deleteQuietly(decrypted);
        }
    }

    static boolean tryExtractEntryWithDecryptedCopy(@NonNull RarArchiveReader.RarEntry entry,
                                                    @NonNull File outFile,
                                                    @Nullable char[] password,
                                                    @Nullable FileOperationProgress progress) throws IOException {
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
                RarStoredPayloadIO.verifyCrc(entry, outFile);
                guard.commit();
            }
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
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
        if (!isArchiveWideNonSolidSpecialCaseAllowed(entries, password)) return false;
        List<OutputMark> writtenOutputs = new ArrayList<>();
        boolean success = false;
        try {
            boolean sawEntry = false;
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
                boolean existedBefore = out.exists();
                if (tryExtractEntryWithDecryptedCopy(entry, out, password, progress)) {
                    writtenOutputs.add(new OutputMark(out, existedBefore));
                    continue;
                }
                if (tryExtractSplitEntryWithDecryptedCopy(entry, entries, out, password, progress)) {
                    writtenOutputs.add(new OutputMark(out, existedBefore));
                    continue;
                }
                if (tryExtractSplitEntryWithRewrittenCopy(entry, entries, out, progress)) {
                    writtenOutputs.add(new OutputMark(out, existedBefore));
                    continue;
                }
                if (Rar3FirstPartyArchiveExtractor.tryExtractSingleEntryLimitedFallback(entry, entries, out, progress)) {
                    writtenOutputs.add(new OutputMark(out, existedBefore));
                    continue;
                }
                if (RarFeatureClassifier.isUnsupportedRar3Or4Payload(entry)) return false;
                RarArchiveReader.extractStoredEntry(entry, out, password, entries, progress);
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
        boolean sawPayload = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory) continue;
            if (entry.rarVersion >= 5) return false;
            if (entry.splitBefore) continue;
            if (entry.solid && !RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) return false;

            if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
                if (entry.encrypted()) {
                    if (password == null || password.length == 0) return false;
                    if (!RarFeatureClassifier.isFirstPartyRar3Or4EncryptedStoredEntry(entry)) return false;
                }
                sawPayload = true;
                continue;
            }

            if (entry.encrypted()) {
                if (password == null || password.length == 0) return false;
                if (entry.splitAfter) {
                    List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(entry, entries);
                    if (!RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(chain)) return false;
                    sawPayload = true;
                    continue;
                }
                if (RarFeatureClassifier.isRar3Or4EncryptedRewriteCandidate(entry)) {
                    sawPayload = true;
                    continue;
                }
                return false;
            }

            if (entry.splitAfter) {
                List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(entry, entries);
                if (!RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(chain)) return false;
                sawPayload = true;
                continue;
            }

            if (Rar3FirstPartyArchiveExtractor.isLimitedNonSolidClassicLzFallbackCandidate(entry)) {
                sawPayload = true;
                continue;
            }

            return false;
        }
        return sawPayload;
    }

    static boolean tryExtractSplitEntryWithDecryptedCopy(@NonNull RarArchiveReader.RarEntry entry,
                                                         @NonNull List<RarArchiveReader.RarEntry> entries,
                                                         @NonNull File outFile,
                                                         @Nullable char[] password,
                                                         @Nullable FileOperationProgress progress) throws IOException {
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
                    password,
                    progress);
            boolean extracted = RarLibarchiveFallback.extractSingleEntry(
                    decrypted,
                    entry.path,
                    outFile,
                    null,
                    progress);
            if (extracted) {
                RarStoredPayloadIO.verifyCrc(RarVolumeChain.last(chain), outFile);
                guard.commit();
            }
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
            return false;
        } finally {
            deleteQuietly(decrypted);
        }
    }

    static boolean tryExtractSplitEntryWithRewrittenCopy(@NonNull RarArchiveReader.RarEntry entry,
                                                         @NonNull List<RarArchiveReader.RarEntry> entries,
                                                         @NonNull File outFile,
                                                         @Nullable FileOperationProgress progress) throws IOException {
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
                    progress);
            boolean extracted = RarLibarchiveFallback.extractSingleEntry(
                    rewritten,
                    entry.path,
                    outFile,
                    null,
                    progress);
            if (extracted) {
                RarStoredPayloadIO.verifyCrc(RarVolumeChain.last(chain), outFile);
                guard.commit();
            }
            return extracted;
        } catch (ArchiveSupport.PasswordRequiredException e) {
            throw e;
        } catch (IOException | SecurityException e) {
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
                                                  @NonNull File targetDir) throws IOException {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore || entry.dataCrc < 0) continue;
            File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
            if (out == null || !out.isFile()) continue;
            RarStoredPayloadIO.verifyCrc(entry, out);
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
