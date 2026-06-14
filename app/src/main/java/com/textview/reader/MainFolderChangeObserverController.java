package com.textview.reader;

import android.os.FileObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Watches the currently visible browse folder and refreshes the adapter when an
 * external app changes that folder. This covers cases such as a browser or file
 * manager downloading/moving a file into the folder while Readwide is already
 * showing it.
 */
final class MainFolderChangeObserverController {
    private static final long FOLDER_CHANGE_RELOAD_DELAY_MS = 700L;
    private static final int WATCH_MASK = FileObserver.CREATE
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO
            | FileObserver.CLOSE_WRITE
            | FileObserver.MODIFY
            | FileObserver.ATTRIB
            | FileObserver.DELETE_SELF
            | FileObserver.MOVE_SELF;

    private final MainActivity activity;
    @Nullable private FileObserver observer;
    @Nullable private String watchedPath;
    private final Runnable reloadRunnable = this::reloadVisibleFolderAfterChange;

    MainFolderChangeObserverController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void syncToVisibleFolder() {
        if (!activity.shouldWatchVisibleFolderForChanges()) {
            stop();
            return;
        }
        File dir = activity.currentDirectory;
        if (dir == null) {
            stop();
            return;
        }
        watch(dir);
    }

    @SuppressWarnings("deprecation")
    private void watch(@NonNull File dir) {
        String path = dir.getAbsolutePath();
        if (path.equals(watchedPath) && observer != null) return;
        stop();
        watchedPath = path;
        observer = new FileObserver(path, WATCH_MASK) {
            @Override
            public void onEvent(int event, @Nullable String childPath) {
                handleFolderEvent(path, event, childPath);
            }
        };
        try {
            observer.startWatching();
        } catch (RuntimeException ignored) {
            observer = null;
            watchedPath = null;
        }
    }

    void stop() {
        activity.fileSearchHandler.removeCallbacks(reloadRunnable);
        if (observer != null) {
            try { observer.stopWatching(); } catch (RuntimeException ignored) {}
        }
        observer = null;
        watchedPath = null;
    }

    private void handleFolderEvent(@NonNull String eventDirectoryPath,
                                   int event,
                                   @Nullable String childPath) {
        int masked = event & WATCH_MASK;
        if (masked == 0) return;
        if (childPath == null && (masked & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) == 0) {
            return;
        }
        activity.fileSearchHandler.post(() -> scheduleVisibleFolderReload(eventDirectoryPath));
    }

    private void scheduleVisibleFolderReload(@NonNull String eventDirectoryPath) {
        if (activity.activityDestroyed || !activity.shouldWatchVisibleFolderForChanges()) return;
        File dir = activity.currentDirectory;
        if (dir == null || !eventDirectoryPath.equals(dir.getAbsolutePath())) return;
        activity.fileSearchHandler.removeCallbacks(reloadRunnable);
        activity.fileSearchHandler.postDelayed(reloadRunnable, FOLDER_CHANGE_RELOAD_DELAY_MS);
    }

    private void reloadVisibleFolderAfterChange() {
        if (activity.activityDestroyed) return;
        if (!activity.shouldWatchVisibleFolderForChanges()) {
            syncToVisibleFolder();
            return;
        }
        File dir = activity.currentDirectory;
        if (dir == null || !dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            stop();
            activity.showHomeMode();
            return;
        }

        if (activity.searchMode) {
            activity.runLiveFileSearchNow();
        } else {
            activity.refreshCurrentDirectoryWithoutClearing(dir);
        }
        syncToVisibleFolder();
    }
}
