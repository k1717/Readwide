package com.readwide.manager;

import androidx.annotation.NonNull;

import com.readwide.manager.model.LargeTextLinePartitionResult;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PageIndexCacheManager;

import java.io.File;

final class ReaderLargeTextCacheController {
    private final ReaderActivity activity;

    ReaderLargeTextCacheController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void recordFileAccess(@NonNull File file, @NonNull String loadedFilePath) {
        try {
            PageIndexCacheManager.getInstance(activity)
                    .recordFileAccess(file, PageIndexCacheManager.EXACT_PAGE_CACHE_LAYOUT);
        } catch (RuntimeException ignored) {
            // Cache bookkeeping is best-effort and must never break file opening.
        }
    }

    boolean shouldUseFastOpen(@NonNull File file) {
        return file.isFile()
                && file.length() >= ReaderActivity.LARGE_TEXT_FAST_OPEN_THRESHOLD_BYTES
                && FileUtils.isTextFile(file.getName());
    }

    void clearPartitionCache() {
        activity.largeTextPartitionCache.clear();
    }

    void cachePartition(@NonNull LargeTextLinePartitionResult partition) {
        activity.largeTextPartitionCache.cache(partition);
    }

    LargeTextLinePartitionResult getPartitionByStartLine(int startLine) {
        return activity.largeTextPartitionCache.getNormalByStartLine(
                startLine, activity.getLargeTextPartitionLines());
    }

    LargeTextLinePartitionResult getManualHandoffPartitionByStartLine(int startLine) {
        return activity.largeTextPartitionCache.getManualHandoffByStartLine(
                startLine, activity.getLargeTextPartitionLines());
    }

    LargeTextLinePartitionResult getPartitionForChar(int absoluteCharPosition) {
        return activity.largeTextPartitionCache.getNormalOwnerForChar(absoluteCharPosition);
    }

}
