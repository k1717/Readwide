package com.readwide.manager;

import android.content.ComponentCallbacks2;
import android.os.Bundle;
import android.content.Intent;
import com.readwide.manager.model.ReaderState;

import androidx.annotation.NonNull;

final class ReaderLifecycleController {
    private final ReaderActivity activity;
    static final String STATE_VIEWPORT = "txt_current_viewport";
    private Bundle pendingViewport;

    ReaderLifecycleController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void onSaveInstanceState(@NonNull Bundle outState) {
        ReaderState state = activity.captureReaderViewport();
        Bundle viewport = null;
        if (state != null) {
            viewport = viewportBundle(activity.getIntent(), state);
        } else if (activity.backgroundTextMemoryReleased && activity.backgroundTextRestoreIntent != null) {
            Intent target = activity.backgroundTextRestoreIntent;
            ReaderState trimmed = new ReaderState(target.getStringExtra(ReaderActivity.EXTRA_FILE_PATH));
            trimmed.setCharPosition(target.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, 0));
            trimmed.setAnchorTextBefore(target.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
            trimmed.setAnchorTextAfter(target.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER));
            viewport = viewportBundle(activity.getIntent(), trimmed);
        } else if (pendingViewport != null && matchesLaunch(activity.getIntent(), pendingViewport)) {
            viewport = pendingViewport; // Recreated again before this load finishes.
        }
        if (viewport != null) outState.putBundle(STATE_VIEWPORT, viewport);
        if (activity.textContentReadyForPersistence && !activity.backgroundTextMemoryReleased) {
            activity.cacheLoadedTextSnapshot();
            outState.putBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, true);
        } else {
            outState.putBoolean(ReaderActivity.STATE_RESTORE_FROM_MEMORY, false);
        }
    }

    Intent intentForRecreation(Bundle saved) {
        pendingViewport = saved != null ? saved.getBundle(STATE_VIEWPORT) : null;
        return restoreViewportIntent(activity.getIntent(), pendingViewport);
    }

    void rememberLoadTarget(Intent target) {
        pendingViewport = null;
        int position = target.getIntExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, -1);
        if (position < 0) return;
        ReaderState state = new ReaderState();
        state.setCharPosition(position);
        state.setAnchorTextBefore(target.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE));
        state.setAnchorTextAfter(target.getStringExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER));
        pendingViewport = viewportBundle(activity.getIntent(), state);
    }

    static Bundle viewportBundle(Intent launch, ReaderState state) {
        Bundle out = new Bundle();
        out.putString("path", launch.getStringExtra(ReaderActivity.EXTRA_FILE_PATH));
        out.putString("uri", launch.getStringExtra(ReaderActivity.EXTRA_FILE_URI));
        out.putInt("position", state.getCharPosition());
        out.putString("before", state.getAnchorTextBefore());
        out.putString("after", state.getAnchorTextAfter());
        return out;
    }

    private static boolean matchesLaunch(Intent launch, Bundle viewport) {
        return java.util.Objects.equals(launch.getStringExtra(ReaderActivity.EXTRA_FILE_PATH), viewport.getString("path"))
                && java.util.Objects.equals(launch.getStringExtra(ReaderActivity.EXTRA_FILE_URI), viewport.getString("uri"));
    }

    /** A cached old screen must not override the instance state's newer destination. */
    static boolean snapshotMatchesSavedViewport(Intent launch, Bundle saved, int snapshotChar) {
        Bundle viewport = saved != null ? saved.getBundle(STATE_VIEWPORT) : null;
        // Keep compatibility with states saved before viewport bundles existed.
        return viewport == null || (matchesLaunch(launch, viewport)
                && viewport.getInt("position", -1) >= 0
                && viewport.getInt("position", -1) == snapshotChar);
    }

    static Intent restoreViewportIntent(Intent launch, Bundle viewport) {
        if (viewport == null || !matchesLaunch(launch, viewport)) return launch;
        Intent restored = new Intent(launch);
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE);
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE);
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE);
        restored.removeExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES);
        restored.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, viewport.getInt("position", 0));
        restored.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, viewport.getString("before", ""));
        restored.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, viewport.getString("after", ""));
        // Rebuild coordinates/page totals; context can recover an edited/reflowed file.
        restored.putExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, true);
        return restored;
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
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Only drop the reader's text and caches when the app is actually in the
            // background LRU list. TRIM_MEMORY_RUNNING_LOW / RUNNING_CRITICAL arrive
            // while the app is still foregrounded; force-trimming there blanks the
            // page the user is reading, and nothing restores it because no onResume
            // follows while foregrounded. Foreground memory pressure must not trim.
            activity.cancelBackgroundMemoryTrim();
            activity.trimReaderMemoryForBackground(true);
        }
    }

    void onDestroy() {
        activity.dismissPageMoveBubble();
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
        if (activity.readerToolbarController != null) {
            activity.readerToolbarController.release();
            activity.readerToolbarController = null;
        }
        activity.handler.removeCallbacksAndMessages(null);
        if (activity.readerShellController != null) activity.readerShellController.cancelViewerBackToast();
        activity.saveReadingState();
        if (!activity.backgroundTextMemoryReleased) {
            activity.cacheLoadedTextSnapshot();
        }
        activity.releaseReaderMemory();
        activity.partitionReader().closeForwardCursor();
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
