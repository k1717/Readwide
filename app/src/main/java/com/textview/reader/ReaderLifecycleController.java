package com.textview.reader;

import android.content.ComponentCallbacks2;
import android.os.Bundle;

import androidx.annotation.NonNull;

final class ReaderLifecycleController {
    private final ReaderActivity activity;

    ReaderLifecycleController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void onSaveInstanceState(@NonNull Bundle outState) {
        if (!activity.backgroundTextMemoryReleased) {
            activity.cacheLoadedTextSnapshot();
            outState.putBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, true);
        } else {
            outState.putBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, false);
        }
    }

    void onPause() {
        activity.stopAutoPageTurn(false);
        if (!activity.isTtsActive()) {
            activity.stopTts(false);
        }
        activity.saveReadingState();
        if (!activity.backgroundTextMemoryReleased) {
            activity.cacheLoadedTextSnapshot();
        }
    }

    void onStop() {
        if (!activity.isTtsActive()) {
            activity.scheduleBackgroundMemoryTrim();
        }
    }

    void onTrimMemory(int level) {
        if (activity.isTtsActive()) {
            return;
        }
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            activity.scheduleBackgroundMemoryTrim();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            activity.cancelBackgroundMemoryTrim();
            activity.trimReaderMemoryForBackground(true);
        }
    }

    void onDestroy() {
        activity.cancelBackgroundMemoryTrim();
        activity.activityDestroyed = true;
        activity.loadGeneration.incrementAndGet();
        activity.invalidateLargeTextExactPageIndexBuild();
        activity.largeTextPartitionSwitchGeneration.incrementAndGet();
        if (activity.autoPageTurnController != null) {
            activity.autoPageTurnController.release();
            activity.autoPageTurnController = null;
        }
        activity.releaseTts();
        activity.handler.removeCallbacksAndMessages(null);
        if (activity.readerShellController != null) activity.readerShellController.cancelViewerBackToast();
        activity.saveReadingState();
        if (!activity.backgroundTextMemoryReleased) {
            activity.cacheLoadedTextSnapshot();
        }
        activity.releaseReaderMemory();
        activity.executor.shutdownNow();
        activity.largeTextPartitionExecutor.shutdownNow();
        activity.largeTextSearchExecutor.shutdownNow();
        activity.largeTextSearchCountExecutor.shutdownNow();
        if (activity.isFinishing()) {
            activity.clearLoadedTextSnapshot();
        }
        TtsPlaybackBridge.unregister(activity);
        ViewerRegistry.unregister(activity);
    }
}
