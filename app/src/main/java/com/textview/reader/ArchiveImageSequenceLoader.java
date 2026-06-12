package com.textview.reader;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

        Result(@NonNull ArrayList<String> imagePaths,
               @NonNull ArrayList<String> displayNames,
               @NonNull ArrayList<String> entryPaths,
               int selectedIndex,
               boolean selectedReady,
               @Nullable ArchiveSupport.ExtractionResult extractionResult) {
            this.imagePaths = imagePaths;
            this.displayNames = displayNames;
            this.entryPaths = entryPaths;
            this.selectedIndex = selectedIndex;
            this.selectedReady = selectedReady;
            this.extractionResult = extractionResult;
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
        boolean sensitiveCache = PasswordChars.hasPassword(password);
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
        if (targetIndex >= 0 && targetIndex < sequence.size()) {
            ArchiveSupport.EntryInfo targetEntry = sequence.get(targetIndex);
            File targetFile = outputFileForEntry(context, archiveFile, targetEntry, sensitiveCache);
            selectedResult = ensureEntryReady(
                    archiveFile,
                    targetEntry.path,
                    targetFile,
                    password,
                    sensitiveCache);
            selectedReady = selectedResult.success;
        }

        int openIndex = targetIndex;
        if (!selectedReady && ArchiveSupport.getSupportedArchiveType(archiveFile) != ArchiveSupport.Type.RAR
                && shouldTryAlternateImageEntry(selectedResult, PasswordChars.hasPassword(password))) {
            for (int i = 0; i < sequence.size(); i++) {
                if (i == targetIndex) continue;
                ArchiveSupport.EntryInfo imageEntry = sequence.get(i);
                File outFile = outputFileForEntry(context, archiveFile, imageEntry, sensitiveCache);
                ArchiveSupport.ExtractionResult fallbackResult = ensureEntryReady(
                        archiveFile,
                        imageEntry.path,
                        outFile,
                        password,
                        sensitiveCache);
                if (fallbackResult.success && outFile.exists() && outFile.isFile() && outFile.length() > 0L) {
                    selectedReady = true;
                    openIndex = i;
                    break;
                }
            }
        }

        return new Result(imagePaths, displayNames, entryPaths, openIndex, selectedReady, selectedResult);
    }

    @NonNull
    private static ArchiveSupport.ExtractionResult ensureEntryReady(@NonNull File archiveFile,
                                                                    @NonNull String entryPath,
                                                                    @NonNull File outFile,
                                                                    @Nullable char[] password,
                                                                    boolean sensitiveCache) {
        return ArchiveImageEntryCache.ensureReady(
                archiveFile,
                entryPath,
                outFile,
                password,
                sensitiveCache,
                null);
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
        boolean sensitiveCache = PasswordChars.hasPassword(password);
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
                    sensitiveCache);
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
        return new Result(imagePaths, displayNames, entryPaths,
                extractedSelectedIndex, selectedExtracted, selectedResult);
    }
}
