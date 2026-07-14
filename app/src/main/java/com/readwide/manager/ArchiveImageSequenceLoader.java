package com.readwide.manager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Background archive-image extraction planner/loader.
 *
 * ArchiveBrowserActivity owns the UI; this class owns the file/cache extraction
 * loops used to build an image sequence for ImageReaderActivity.
 */
final class ArchiveImageSequenceLoader {
    private ArchiveImageSequenceLoader() {
    }

    static final class Result {
        final ArrayList<String> imagePaths;
        final ArrayList<String> displayNames;
        final ArrayList<String> entryPaths;
        final int selectedIndex;
        final boolean selectedReady;
        @Nullable final ArchiveSupport.ExtractionResult extractionResult;
        @NonNull private final Set<String> verifiedSensitivePaths;
        @Nullable final String archivePathSnapshot;
        final long archiveLengthSnapshot;
        final long archiveLastModifiedSnapshot;
        @Nullable private SequentialArchiveImageReader preparedReader;

        Result(@NonNull ArrayList<String> imagePaths,
               @NonNull ArrayList<String> displayNames,
               @NonNull ArrayList<String> entryPaths,
               int selectedIndex,
               boolean selectedReady,
               @Nullable ArchiveSupport.ExtractionResult extractionResult) {
            this(imagePaths, displayNames, entryPaths, selectedIndex, selectedReady,
                    extractionResult, null, null);
        }

        Result(@NonNull ArrayList<String> imagePaths,
               @NonNull ArrayList<String> displayNames,
               @NonNull ArrayList<String> entryPaths,
               int selectedIndex,
               boolean selectedReady,
               @Nullable ArchiveSupport.ExtractionResult extractionResult,
               @Nullable SequentialArchiveImageReader preparedReader) {
            this(imagePaths, displayNames, entryPaths, selectedIndex, selectedReady,
                    extractionResult, preparedReader, null);
        }

        Result(@NonNull ArrayList<String> imagePaths,
               @NonNull ArrayList<String> displayNames,
               @NonNull ArrayList<String> entryPaths,
               int selectedIndex,
               boolean selectedReady,
               @Nullable ArchiveSupport.ExtractionResult extractionResult,
               @Nullable SequentialArchiveImageReader preparedReader,
               @Nullable Set<String> verifiedSensitivePaths) {
            this(imagePaths, displayNames, entryPaths, selectedIndex, selectedReady,
                    extractionResult, preparedReader, verifiedSensitivePaths,
                    null, -1L, -1L);
        }

        Result(@NonNull ArrayList<String> imagePaths,
               @NonNull ArrayList<String> displayNames,
               @NonNull ArrayList<String> entryPaths,
               int selectedIndex,
               boolean selectedReady,
               @Nullable ArchiveSupport.ExtractionResult extractionResult,
               @Nullable SequentialArchiveImageReader preparedReader,
               @Nullable Set<String> verifiedSensitivePaths,
               @Nullable String archivePathSnapshot,
               long archiveLengthSnapshot,
               long archiveLastModifiedSnapshot) {
            this.imagePaths = imagePaths;
            this.displayNames = displayNames;
            this.entryPaths = entryPaths;
            this.selectedIndex = selectedIndex;
            this.selectedReady = selectedReady;
            this.extractionResult = extractionResult;
            this.preparedReader = preparedReader;
            this.verifiedSensitivePaths = verifiedSensitivePaths != null
                    ? new HashSet<>(verifiedSensitivePaths) : new HashSet<>();
            this.archivePathSnapshot = archivePathSnapshot;
            this.archiveLengthSnapshot = archiveLengthSnapshot;
            this.archiveLastModifiedSnapshot = archiveLastModifiedSnapshot;
        }

        @NonNull
        synchronized Set<String> snapshotVerifiedSensitivePaths() {
            return new HashSet<>(verifiedSensitivePaths);
        }

        @Nullable
        synchronized SequentialArchiveImageReader takePreparedReader() {
            SequentialArchiveImageReader reader = preparedReader;
            preparedReader = null;
            return reader;
        }

        synchronized void closePreparedReader() {
            if (preparedReader != null) {
                preparedReader.close();
                preparedReader = null;
            }
        }
    }

    @NonNull
    static File outputFileForEntry(@NonNull Context context,
                                   @NonNull File archiveFile,
                                   @NonNull ArchiveSupport.EntryInfo entry) {
        return outputFileForEntry(context, archiveFile, entry, false);
    }

    @NonNull
    static File outputFileForEntry(@NonNull Context context,
                                   @NonNull File archiveFile,
                                   @NonNull ArchiveSupport.EntryInfo entry,
                                   boolean sensitive) {
        return ArchivePreviewCache.outputFileForEntry(context, archiveFile, entry.path, sensitive);
    }

    @NonNull
    static Result loadLazy(@NonNull Context context,
                           @NonNull File archiveFile,
                           @NonNull List<ArchiveSupport.EntryInfo> sequence,
                           int targetIndex) {
        return loadLazy(context, archiveFile, sequence, targetIndex, null);
    }

    @NonNull
    static Result loadLazy(@NonNull Context context,
                           @NonNull File archiveFile,
                           @NonNull List<ArchiveSupport.EntryInfo> sequence,
                           int targetIndex,
                           @Nullable char[] password) {
        String archivePathSnapshot = archiveFile.getAbsolutePath();
        long archiveLengthSnapshot = archiveFile.length();
        long archiveLastModifiedSnapshot = archiveFile.lastModified();
        boolean sensitiveCache = PasswordChars.hasPassword(password);
        Set<String> verifiedSensitivePaths = new HashSet<>();
        ArrayList<String> imagePaths = new ArrayList<>();
        ArrayList<String> displayNames = new ArrayList<>();
        ArrayList<String> entryPaths = new ArrayList<>();
        for (ArchiveSupport.EntryInfo imageEntry : sequence) {
            File outFile = outputFileForEntry(context, archiveFile, imageEntry, sensitiveCache);
            imagePaths.add(outFile.getAbsolutePath());
            displayNames.add(imageEntry.name());
            entryPaths.add(imageEntry.path);
        }

        boolean selectedReady = false;
        ArchiveSupport.ExtractionResult selectedResult = null;
        SequentialArchiveImageReader preparedReader = null;
        if (targetIndex >= 0 && targetIndex < sequence.size()) {
            ArchiveSupport.EntryInfo targetEntry = sequence.get(targetIndex);
            File targetFile = outputFileForEntry(context, archiveFile, targetEntry, sensitiveCache);
            if (ArchiveSupport.isForwardImageReadableType(archiveFile)) {
                // Sequential archives: extract only up to the target via a forward reader,
                // avoiding whole-archive decompression for the first page. Falls back to
                // whole-archive extraction internally and returns its real result, so a failure
                // reason such as PASSWORD_REQUIRED still drives the password prompt.
                // Keep the already-positioned reader for the viewer even when the
                // archive uses a password. The handoff store already owns and clears
                // the password snapshot on every consume/discard/failure path, and
                // SequentialArchiveImageReader clears its private clone on close.
                // Closing here would make an encrypted RAR/7z decode from byte zero
                // once in this loader and then a second time in ImageReaderActivity.
                preparedReader = SequentialArchiveImageReader.openIfSupported(
                        context, archiveFile, password, sensitiveCache, null);
                try {
                    selectedResult = SequentialArchiveImageReader.ensureImageReady(
                            context,
                            archiveFile,
                            targetEntry.path,
                            targetFile,
                            password,
                            sensitiveCache,
                            verifiedSensitivePaths,
                            preparedReader);
                    if (preparedReader != null) {
                        // Merge every path the forward reader actually decoded
                        // into the loader-owned handoff set before deciding
                        // whether the open reader itself remains reusable.
                        preparedReader.attachVerifiedSensitivePaths(verifiedSensitivePaths);
                    }
                } catch (RuntimeException | Error failure) {
                    if (preparedReader != null) preparedReader.close();
                    throw failure;
                }
                if (preparedReader != null
                        && (!selectedResult.success
                        || !preparedReader.isReusableForHandoff(archiveFile))) {
                    preparedReader.close();
                    preparedReader = null;
                }
            } else {
                selectedResult = ensureEntryReady(
                        archiveFile,
                        targetEntry.path,
                        targetFile,
                        password,
                        sensitiveCache,
                        verifiedSensitivePaths);
            }
            selectedReady = selectedResult.success;
        }

        int openIndex = targetIndex;
        if (!selectedReady && ArchiveSupport.getSupportedArchiveType(archiveFile) != ArchiveSupport.Type.RAR
                && shouldTryAlternateImageEntry(selectedResult, PasswordChars.hasPassword(password))) {
            if (preparedReader != null) {
                preparedReader.close();
                preparedReader = null;
            }
            for (int i = 0; i < sequence.size(); i++) {
                if (i == targetIndex) continue;
                ArchiveSupport.EntryInfo imageEntry = sequence.get(i);
                File outFile = outputFileForEntry(context, archiveFile, imageEntry, sensitiveCache);
                ArchiveSupport.ExtractionResult fallbackResult = ensureEntryReady(
                        archiveFile,
                        imageEntry.path,
                        outFile,
                        password,
                        sensitiveCache,
                        verifiedSensitivePaths);
                if (fallbackResult.success && outFile.exists() && outFile.isFile() && outFile.length() > 0L) {
                    selectedReady = true;
                    openIndex = i;
                    break;
                }
            }
        }

        if (!SequentialArchiveImageReader.matchesArchiveSnapshot(
                archiveFile,
                archivePathSnapshot,
                archiveLengthSnapshot,
                archiveLastModifiedSnapshot)) {
            if (preparedReader != null) preparedReader.close();
            return new Result(
                    imagePaths,
                    displayNames,
                    entryPaths,
                    openIndex,
                    false,
                    ArchiveSupport.ExtractionResult.failed(
                            ArchiveSupport.ExtractionFailure.FAILED,
                            "Archive changed while preparing comic preview"),
                    null,
                    null);
        }
        return new Result(imagePaths, displayNames, entryPaths, openIndex, selectedReady,
                selectedResult, preparedReader, verifiedSensitivePaths,
                archivePathSnapshot, archiveLengthSnapshot, archiveLastModifiedSnapshot);
    }

    @NonNull
    private static ArchiveSupport.ExtractionResult ensureEntryReady(@NonNull File archiveFile,
                                                                    @NonNull String entryPath,
                                                                    @NonNull File outFile,
                                                                    @Nullable char[] password,
                                                                    boolean sensitiveCache,
                                                                    @Nullable Set<String> verifiedSensitivePaths) {
        return ArchiveImageEntryCache.ensureReady(
                archiveFile,
                entryPath,
                outFile,
                password,
                sensitiveCache,
                verifiedSensitivePaths);
    }

    static boolean shouldTryAlternateImageEntry(@Nullable ArchiveSupport.ExtractionResult result) {
        return shouldTryAlternateImageEntry(result, false);
    }

    static boolean shouldTryAlternateImageEntry(@Nullable ArchiveSupport.ExtractionResult result,
                                                boolean passwordProvided) {
        if (result == null || result.success) return false;
        if (result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED) return false;
        if (result.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD) return false;
        if (passwordProvided && result.failure == ArchiveSupport.ExtractionFailure.FAILED) return false;
        return true;
    }

    @NonNull
    static Result loadFully(@NonNull Context context,
                            @NonNull File archiveFile,
                            @NonNull List<ArchiveSupport.EntryInfo> sequence,
                            int targetIndex,
                            @Nullable char[] password) {
        String archivePathSnapshot = archiveFile.getAbsolutePath();
        long archiveLengthSnapshot = archiveFile.length();
        long archiveLastModifiedSnapshot = archiveFile.lastModified();
        boolean sensitiveCache = PasswordChars.hasPassword(password);
        Set<String> verifiedSensitivePaths = new HashSet<>();
        ArrayList<String> imagePaths = new ArrayList<>();
        ArrayList<String> displayNames = new ArrayList<>();
        ArrayList<String> entryPaths = new ArrayList<>();
        int extractedSelectedIndex = 0;
        boolean selectedExtracted = false;
        ArchiveSupport.ExtractionResult selectedResult = null;
        for (int i = 0; i < sequence.size(); i++) {
            ArchiveSupport.EntryInfo imageEntry = sequence.get(i);
            File outFile = outputFileForEntry(context, archiveFile, imageEntry, sensitiveCache);
            ArchiveSupport.ExtractionResult result = ensureEntryReady(
                    archiveFile,
                    imageEntry.path,
                    outFile,
                    password,
                    sensitiveCache,
                    verifiedSensitivePaths);
            boolean ok = result.success;
            if (ok && outFile.exists()) {
                if (i == targetIndex) {
                    extractedSelectedIndex = imagePaths.size();
                    selectedExtracted = true;
                }
                imagePaths.add(outFile.getAbsolutePath());
                displayNames.add(imageEntry.name());
                entryPaths.add(imageEntry.path);
            } else if (i == targetIndex) {
                selectedResult = result;
            }
        }
        if (ArchiveSupport.getSupportedArchiveType(archiveFile) != ArchiveSupport.Type.RAR
                && !selectedExtracted && !imagePaths.isEmpty() && selectedResult != null
                && selectedResult.failure == ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE) {
            extractedSelectedIndex = 0;
            selectedExtracted = true;
        }
        if (!SequentialArchiveImageReader.matchesArchiveSnapshot(
                archiveFile,
                archivePathSnapshot,
                archiveLengthSnapshot,
                archiveLastModifiedSnapshot)) {
            return new Result(
                    imagePaths,
                    displayNames,
                    entryPaths,
                    extractedSelectedIndex,
                    false,
                    ArchiveSupport.ExtractionResult.failed(
                            ArchiveSupport.ExtractionFailure.FAILED,
                            "Archive changed while preparing comic preview"),
                    null,
                    null);
        }
        return new Result(imagePaths, displayNames, entryPaths,
                extractedSelectedIndex, selectedExtracted, selectedResult,
                null, verifiedSensitivePaths,
                archivePathSnapshot, archiveLengthSnapshot, archiveLastModifiedSnapshot);
    }
}
