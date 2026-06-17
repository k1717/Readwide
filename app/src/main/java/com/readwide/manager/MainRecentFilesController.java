package com.readwide.manager;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.readwide.manager.model.ReaderState;
import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class MainRecentFilesController {
    private static final int DISPLAY_LIMIT = 100;
    private static final int SCAN_LIMIT = 400;

    private final MainActivity activity;

    MainRecentFilesController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void resetRecyclerBeforeReload() {
        if (activity.recentRecyclerView == null) return;
        activity.recentRecyclerView.stopScroll();
        activity.recentRecyclerView.clearAnimation();
        RecyclerView.ItemAnimator animator = activity.recentRecyclerView.getItemAnimator();
        if (animator != null) animator.endAnimations();
    }

    void loadRecentFiles() {
        resetRecyclerBeforeReload();
        if (activity.bookmarkManager == null) {
            applyRecentFiles(new ArrayList<>(), new ArrayList<>());
            return;
        }
        final long token = ++recentLoadToken;
        activity.fileOperationExecutor.execute(() -> {
            List<ReaderState> recent = activity.bookmarkManager.getRecentFiles(SCAN_LIMIT);
            List<File> recentFiles = new ArrayList<>();
            for (ReaderState state : recent) {
                if (recentFiles.size() >= DISPLAY_LIMIT) break;
                File file = visibleRecentFileFor(state);
                if (file != null) recentFiles.add(file);
            }
            activity.runOnUiThread(() -> {
                // Drop stale results if another reload started or the activity is gone.
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (token != recentLoadToken) return;
                applyRecentFiles(recent, recentFiles);
            });
        });
    }

    private volatile long recentLoadToken = 0;

    private void applyRecentFiles(@NonNull List<ReaderState> recent,
                                  @NonNull List<File> recentFiles) {
        if (activity.recentAdapter != null) {
            activity.recentAdapter.setReadingProgressStates(recent);
            int recentSort = activity.prefs != null
                    ? activity.prefs.getRecentSortMode()
                    : PrefsManager.SORT_RECENT_READ;
            if (recentSort == PrefsManager.SORT_RECENT_READ) {
                // BookmarkManager already returns newest first. Avoid DiffUtil
                // holder reuse here because recent rows carry progress badges.
                activity.recentAdapter.setSortEnabled(false);
                activity.recentAdapter.setFilesFastPresorted(recentFiles);
            } else {
                activity.recentAdapter.setSortEnabled(true);
                activity.recentAdapter.setSortMode(recentSort);
                activity.recentAdapter.setFiles(recentFiles);
            }
            activity.recentAdapter.refreshReadingProgress();
            activity.scrollListToTop(activity.recentRecyclerView);
        }
        if (activity.recentEmptyText != null) {
            activity.recentEmptyText.setVisibility(recentFiles.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (activity.recentClearAllButton != null) {
            boolean hasAnyRecent = activity.bookmarkManager != null && activity.bookmarkManager.hasRecentFiles();
            activity.recentClearAllButton.setVisibility(hasAnyRecent ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @Nullable
    private File visibleRecentFileFor(@Nullable ReaderState state) {
        String statePath = state == null ? null : state.getFilePath();
        if (statePath == null || statePath.trim().isEmpty()) return null;

        if (isArchivePreviewCacheStatePath(statePath)) {
            // Internal archive-preview files are temporary cache outputs. Keep
            // the host archive as Recent instead of ranking extracted cache files.
            activity.bookmarkManager.deleteReadingState(statePath);
            return null;
        }

        File file = new File(statePath);
        if (!file.exists()) return null;
        if (FileUtils.isImageFile(file.getName())) {
            // Images are viewable, but they should not occupy reading history.
            activity.bookmarkManager.deleteReadingState(file.getAbsolutePath());
            return null;
        }
        if (activity.activeFileFilter != MainActivity.FILTER_ALL
                && !activity.matchesActiveFileFilter(file.getName(), activity.activeFileFilter)) {
            return null;
        }
        return file;
    }

    private boolean isArchivePreviewCacheStatePath(@NonNull String path) {
        File previewRoot = new File(activity.getCacheDir(), "archive_preview");
        return isSameOrChildPath(path, previewRoot.getAbsolutePath());
    }

    private boolean isSameOrChildPath(@Nullable String candidatePath, @Nullable String rootPath) {
        return com.readwide.manager.util.FileUtils.isSameOrChildPath(candidatePath, rootPath);
    }
}
