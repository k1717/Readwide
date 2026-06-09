package com.textview.reader;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.model.ReaderState;
import com.textview.reader.util.FileUtils;
import com.textview.reader.util.PrefsManager;

import java.io.File;
import java.io.IOException;
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

        List<ReaderState> recent = activity.bookmarkManager.getRecentFiles(SCAN_LIMIT);
        List<File> recentFiles = new ArrayList<>();
        for (ReaderState state : recent) {
            if (recentFiles.size() >= DISPLAY_LIMIT) break;
            File file = visibleRecentFileFor(state);
            if (file != null) recentFiles.add(file);
        }
        applyRecentFiles(recent, recentFiles);
    }

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
        if (candidatePath == null || rootPath == null) return false;
        String candidate = candidatePath.trim();
        String root = rootPath.trim();
        if (candidate.isEmpty() || root.isEmpty()) return false;

        try {
            candidate = new File(candidate).getCanonicalPath();
            root = new File(root).getCanonicalPath();
        } catch (IOException ignored) {
            candidate = new File(candidate).getAbsolutePath();
            root = new File(root).getAbsolutePath();
        }

        if (candidate.equals(root)) return true;
        String normalizedRoot = root.endsWith(File.separator) ? root : root + File.separator;
        return candidate.startsWith(normalizedRoot);
    }
}
