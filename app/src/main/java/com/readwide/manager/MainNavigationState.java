package com.readwide.manager;

import android.os.Bundle;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.Objects;

/** Small recreation snapshot. Never saves file lists, selections or pending operations. */
final class MainNavigationState {
    private static final String KEY = "main_navigation";
    private final MainActivity activity;
    private Bundle pending;
    private Bundle scroll;
    private boolean initialized;

    MainNavigationState(MainActivity activity) { this.activity = activity; }

    void read(Bundle state) { pending = state == null ? null : state.getBundle(KEY); }
    boolean hasPendingRestore() { return pending != null; }

    void navigating() {
        initialized = true;
        scroll = null;
    }

    void cancelScroll() { scroll = null; }

    void save(Bundle state) {
        // Preserve a deferred snapshot through another recreation of the lock screen.
        if (pending != null) { state.putBundle(KEY, new Bundle(pending)); return; }
        if (!initialized) return;
        Bundle value = location();
        value.putBoolean("return_home", activity.searchReturnToHome);
        value.putString("return_directory", path(activity.searchReturnDirectory));
        value.putString("filter_directory", path(activity.fileTypeFilterActivatedDirectory));
        value.putBoolean("term_cleared", activity.filterSearchTermJustCleared);
        RecyclerView list = activity.homeMode ? activity.recentRecyclerView : activity.fileRecyclerView;
        if (scroll != null && sameLocation(scroll)) {
            value.putInt("position", scroll.getInt("position", -1));
            value.putInt("offset", scroll.getInt("offset"));
        } else if (list != null && list.getLayoutManager() instanceof LinearLayoutManager) {
            LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
            int position = manager.findFirstVisibleItemPosition();
            View row = manager.findViewByPosition(position);
            value.putInt("position", position);
            value.putInt("offset", row == null ? 0 : row.getTop() - list.getPaddingTop());
        }
        state.putBundle(KEY, value);
    }

    boolean restore() {
        if (pending == null) return false;
        Bundle value = pending;
        pending = null;
        File directory = file(value.getString("directory"));
        boolean home = value.getBoolean("home", true);
        boolean search = value.getBoolean("search") && !home;
        if (!home && ((directory == null && !search)
                || (directory != null && (!directory.isDirectory() || !directory.canRead())))) {
            activity.showHomeMode();
            return true;
        }
        int filter = value.getInt("filter", MainActivity.FILTER_ALL);
        activity.activeFileFilter = filter >= MainActivity.FILTER_ALL && filter <= MainActivity.FILTER_IMAGE
                ? filter : MainActivity.FILTER_ALL;
        if (home || directory == null) activity.showHomeMode();
        else activity.showBrowseMode(directory);
        activity.fileTypeFilterActivatedDirectory = file(value.getString("filter_directory"));
        if (activity.fileSearchInput != null) {
            activity.fileSearchInput.setText(value.getString("query", ""));
        }
        activity.clearSearchDebounce();
        if (search) {
            // Do not let starting a search overwrite its saved Back destination.
            activity.searchMode = true;
            activity.homeMode = false;
            activity.searchReturnToHome = value.getBoolean("return_home", true);
            activity.searchReturnDirectory = file(value.getString("return_directory"));
            activity.runLiveFileSearchNow();
        }
        activity.filterSearchTermJustCleared = value.getBoolean("term_cleared");
        activity.updateFileSearchClearButtonVisibility();
        scroll = value;
        return true;
    }

    /** Called only after final data has reached the corresponding adapter. */
    boolean restoreScroll(boolean recent) {
        if (scroll == null || recent != activity.homeMode) return false;
        if (!sameLocation(scroll)) { scroll = null; return false; }
        RecyclerView list = recent ? activity.recentRecyclerView : activity.fileRecyclerView;
        if (list == null || list.getAdapter() == null) return false;
        Bundle value = scroll;
        scroll = null;
        int count = list.getAdapter().getItemCount();
        int position = value.getInt("position", -1);
        if (count > 0 && position >= 0 && list.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(
                    Math.min(position, count - 1), value.getInt("offset"));
            return true;
        }
        return false;
    }

    private Bundle location() {
        Bundle value = new Bundle();
        value.putBoolean("home", activity.homeMode);
        value.putBoolean("search", activity.searchMode);
        value.putString("directory", activity.homeMode ? null : path(activity.currentDirectory));
        value.putInt("filter", activity.activeFileFilter);
        value.putString("query", activity.fileSearchInput == null ? ""
                : activity.fileSearchInput.getText().toString().trim());
        return value;
    }

    private boolean sameLocation(Bundle value) {
        Bundle now = location();
        return value.getBoolean("home") == now.getBoolean("home")
                && value.getBoolean("search") == now.getBoolean("search")
                && value.getInt("filter") == now.getInt("filter")
                && Objects.equals(value.getString("directory"), now.getString("directory"))
                && Objects.equals(value.getString("query"), now.getString("query"));
    }

    private static String path(File file) { return file == null ? null : file.getAbsolutePath(); }
    private static File file(String path) { return path == null || path.isEmpty() ? null : new File(path); }
}
