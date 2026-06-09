package com.textview.reader;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opens comic-style archives from the main file list directly into ImageReaderActivity.
 *
 * ArchiveBrowserActivity remains the explicit folder-preview UI. This controller keeps
 * the automatic comic/image route from first drawing the archive entry list and then
 * immediately covering it with the image viewer, which caused a visible preview flash.
 */
final class MainArchiveImageOpenController {
    private final MainActivity activity;

    MainArchiveImageOpenController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    boolean openDirectImageViewerIfComicArchive(@NonNull File archiveFile) {
        if (!shouldOpenDirectlyAsImageSequence(archiveFile)) return false;
        activity.markPreserveBrowseStateForViewerReturn(archiveFile);
        activity.mainImageOpen().showImageOpenLoadingWindow();
        activity.fileOperationExecutor.execute(() -> openDirectImageViewerInBackground(archiveFile));
        return true;
    }

    private boolean shouldOpenDirectlyAsImageSequence(@NonNull File archiveFile) {
        if (archiveFile == null || !archiveFile.exists() || !archiveFile.isFile()) return false;
        if (!ArchiveSupport.isSupportedArchive(archiveFile)) return false;
        if (isComicBookArchiveName(archiveFile.getName())) return true;
        return activity.prefs != null && activity.prefs.shouldOpenGenericArchivesAsComics();
    }

    private static boolean isComicBookArchiveName(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cbz")
                || lower.endsWith(".cbr")
                || lower.endsWith(".cb7")
                || lower.matches(".*\\.cb7\\.\\d{3}$")
                || lower.endsWith(".cbt");
    }

    private void openDirectImageViewerInBackground(@NonNull File archiveFile) {
        DirectOpenResult result;
        try {
            ArchivePreviewCache.pruneOtherArchiveCaches(activity.getApplicationContext(), archiveFile);
            int sortMode = activity.prefs != null
                    ? activity.prefs.getArchiveSortMode()
                    : PrefsManager.SORT_NAME_ASC;
            List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archiveFile, null);
            List<ArchiveSupport.EntryInfo> images = collectArchiveImages(entries, sortMode);
            if (images.isEmpty()) {
                result = DirectOpenResult.fallbackToPreview();
            } else {
                int targetIndex = resolveSavedImageIndex(archiveFile, images);
                ArchiveImageSequenceLoader.Result loaded = ArchiveImageSequenceLoader.loadLazy(
                        activity.getApplicationContext(),
                        archiveFile,
                        images,
                        targetIndex);
                if (loaded.selectedReady && !loaded.imagePaths.isEmpty()) {
                    result = DirectOpenResult.openImages(loaded);
                } else if (requiresPassword(archiveFile, loaded.extractionResult)) {
                    result = DirectOpenResult.fallbackToPreview();
                } else {
                    result = DirectOpenResult.failure(loaded.extractionResult);
                }
            }
        } catch (ArchiveSupport.PasswordRequiredException e) {
            result = DirectOpenResult.fallbackToPreview();
        } catch (ArchiveSupport.UnsupportedArchiveFeatureException e) {
            result = DirectOpenResult.unsupported();
        } catch (Exception e) {
            result = DirectOpenResult.openFailed();
        }

        DirectOpenResult finalResult = result;
        activity.fileSearchHandler.post(() -> handleDirectOpenResult(archiveFile, finalResult));
    }

    @NonNull
    private static List<ArchiveSupport.EntryInfo> collectArchiveImages(@NonNull List<ArchiveSupport.EntryInfo> entries,
                                                                       int sortMode) {
        ArrayList<ArchiveSupport.EntryInfo> images = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : entries) {
            if (entry == null || entry.directory) continue;
            if (FileUtils.isImageFile(entry.name())) images.add(entry);
        }
        ArchiveEntryListController.sort(images, sortMode);
        return images;
    }

    private int resolveSavedImageIndex(@NonNull File archiveFile,
                                       @NonNull List<ArchiveSupport.EntryInfo> images) {
        String requestedPath = activity.prefs != null
                ? activity.prefs.getArchiveLastImageEntryPath(archiveFile.getAbsolutePath())
                : "";
        if (requestedPath != null && requestedPath.trim().length() > 0) {
            for (int i = 0; i < images.size(); i++) {
                if (requestedPath.equals(images.get(i).path)) return i;
            }
        }
        return 0;
    }

    private boolean requiresPassword(@NonNull File archiveFile,
                                     @Nullable ArchiveSupport.ExtractionResult result) {
        return ArchiveSupport.canUsePassword(archiveFile)
                && result != null
                && result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED;
    }

    private void handleDirectOpenResult(@NonNull File archiveFile,
                                        @NonNull DirectOpenResult result) {
        if (activity.activityDestroyed || activity.isFinishing()) {
            activity.hideImageOpenLoadingWindow();
            return;
        }
        activity.hideImageOpenLoadingWindow();
        switch (result.action) {
            case DirectOpenResult.ACTION_OPEN_IMAGES:
                openImageReader(archiveFile, result.loaderResult);
                break;
            case DirectOpenResult.ACTION_FALLBACK_PREVIEW:
                openArchivePreview(archiveFile);
                break;
            case DirectOpenResult.ACTION_UNSUPPORTED:
                ShortToast.show(activity, ArchiveFailureMessages.unsupportedFeatureMessageRes(archiveFile));
                break;
            case DirectOpenResult.ACTION_OPEN_FAILED:
                ShortToast.show(activity, R.string.archive_open_failed);
                break;
            case DirectOpenResult.ACTION_ENTRY_FAILED:
            default:
                ShortToast.show(activity, ArchiveFailureMessages.entryFailureMessageRes(archiveFile, result.extractionResult));
                break;
        }
    }

    private void openImageReader(@NonNull File archiveFile,
                                 @Nullable ArchiveImageSequenceLoader.Result result) {
        if (result == null || result.imagePaths.isEmpty()) {
            openArchivePreview(archiveFile);
            return;
        }
        int safeIndex = Math.max(0, Math.min(result.selectedIndex, result.imagePaths.size() - 1));
        ArrayList<String> sequencePaths = new ArrayList<>(result.imagePaths);
        ArrayList<String> sequenceNames = new ArrayList<>(result.displayNames);
        ArrayList<String> sequenceEntryPaths = new ArrayList<>(result.entryPaths);
        String token = ImageSequenceHandoffStore.put(() ->
                new ImageSequenceHandoffStore.Sequence(sequencePaths, sequenceNames, sequenceEntryPaths));
        Intent intent = new Intent(activity, ImageReaderActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(ImageReaderActivity.EXTRA_FILE_PATH, result.imagePaths.get(safeIndex));
        intent.putExtra(ImageReaderActivity.EXTRA_SEQUENCE_HANDOFF_TOKEN, token);
        intent.putExtra(ImageReaderActivity.EXTRA_SOURCE_ARCHIVE_PATH, archiveFile.getAbsolutePath());
        intent.putExtra(ImageReaderActivity.EXTRA_ALLOW_FILE_OPS, false);
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.image_viewer_enter, R.anim.image_viewer_hold);
        activity.finishIfReturnToViewerMode();
    }

    private void openArchivePreview(@NonNull File archiveFile) {
        Intent intent = new Intent(activity, ArchiveBrowserActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(ArchiveBrowserActivity.EXTRA_ARCHIVE_PATH, archiveFile.getAbsolutePath());
        activity.markPreserveBrowseStateForViewerReturn(archiveFile);
        activity.startActivity(intent);
        activity.finishIfReturnToViewerMode();
    }

    private static final class DirectOpenResult {
        static final int ACTION_OPEN_IMAGES = 1;
        static final int ACTION_FALLBACK_PREVIEW = 2;
        static final int ACTION_UNSUPPORTED = 3;
        static final int ACTION_OPEN_FAILED = 4;
        static final int ACTION_ENTRY_FAILED = 5;

        final int action;
        @Nullable final ArchiveImageSequenceLoader.Result loaderResult;
        @Nullable final ArchiveSupport.ExtractionResult extractionResult;

        private DirectOpenResult(int action,
                                 @Nullable ArchiveImageSequenceLoader.Result loaderResult,
                                 @Nullable ArchiveSupport.ExtractionResult extractionResult) {
            this.action = action;
            this.loaderResult = loaderResult;
            this.extractionResult = extractionResult;
        }

        static DirectOpenResult openImages(@NonNull ArchiveImageSequenceLoader.Result result) {
            return new DirectOpenResult(ACTION_OPEN_IMAGES, result, null);
        }

        static DirectOpenResult fallbackToPreview() {
            return new DirectOpenResult(ACTION_FALLBACK_PREVIEW, null, null);
        }

        static DirectOpenResult unsupported() {
            return new DirectOpenResult(ACTION_UNSUPPORTED, null, null);
        }

        static DirectOpenResult openFailed() {
            return new DirectOpenResult(ACTION_OPEN_FAILED, null, null);
        }

        static DirectOpenResult failure(@Nullable ArchiveSupport.ExtractionResult result) {
            return new DirectOpenResult(ACTION_ENTRY_FAILED, null, result);
        }
    }
}
