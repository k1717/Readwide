package com.textview.reader;

import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.textview.reader.adapter.DrawerEntryAdapter;
import com.textview.reader.model.DrawerEntry;
import com.textview.reader.model.ReaderState;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MainDrawerController {
    private final MainActivity activity;

    MainDrawerController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void setupDrawerStorageList() {
        activity.drawerFixedList = activity.findViewById(R.id.drawer_fixed_list);
        activity.drawerStorageList = activity.findViewById(R.id.drawer_storage_list);
        activity.drawerShortcutList = activity.findViewById(R.id.drawer_shortcut_list);
        activity.drawerRecentFoldersHeader = activity.findViewById(R.id.drawer_recent_folders_header);
        activity.drawerRecentFoldersTitle = activity.findViewById(R.id.drawer_recent_folders_title);
        activity.drawerRecentFoldersClearButton = activity.findViewById(R.id.drawer_recent_folders_clear);
        setupDrawerSystemInsets();

        activity.drawerFixedEntryAdapter = new DrawerEntryAdapter();
        activity.drawerShortcutEntryAdapter = new DrawerEntryAdapter();
        activity.drawerEntryAdapter = new DrawerEntryAdapter();
        activity.drawerFixedEntryAdapter.setUseShortcutBoxColor(false);
        activity.drawerEntryAdapter.setUseShortcutBoxColor(false);
        activity.drawerShortcutEntryAdapter.setUseShortcutBoxColor(true);

        if (activity.drawerFixedList != null) {
            activity.drawerFixedList.setLayoutManager(new LinearLayoutManager(activity));
            activity.drawerFixedList.setAdapter(activity.drawerFixedEntryAdapter);
            activity.drawerFixedList.setNestedScrollingEnabled(false);
        }
        if (activity.drawerStorageList != null) {
            activity.drawerStorageList.setLayoutManager(new LinearLayoutManager(activity));
            activity.drawerStorageList.setAdapter(activity.drawerEntryAdapter);
        }
        if (activity.drawerShortcutList != null) {
            activity.drawerShortcutList.setLayoutManager(new LinearLayoutManager(activity));
            activity.drawerShortcutList.setAdapter(activity.drawerShortcutEntryAdapter);
            activity.drawerShortcutList.setNestedScrollingEnabled(true);
        }

        DrawerEntryAdapter.OnEntryClickListener clickListener = activity::queueDrawerNavigation;
        DrawerEntryAdapter.OnEntryLongClickListener longClickListener = activity::handleDrawerEntryLongClick;
        activity.drawerFixedEntryAdapter.setListener(clickListener);
        activity.drawerShortcutEntryAdapter.setListener(clickListener);
        activity.drawerEntryAdapter.setListener(clickListener);
        activity.drawerFixedEntryAdapter.setLongClickListener(longClickListener);
        activity.drawerShortcutEntryAdapter.setLongClickListener(longClickListener);
        activity.drawerEntryAdapter.setLongClickListener(longClickListener);

        if (activity.drawerRecentFoldersClearButton != null) {
            activity.drawerRecentFoldersClearButton.setOnClickListener(v -> activity.showClearAllRecentFoldersDialog());
        }

        rebuildDrawerStorageEntries();
    }

    private void setupDrawerSystemInsets() {
        View navDrawer = activity.findViewById(R.id.nav_drawer);
        if (navDrawer == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            navDrawer.setOnApplyWindowInsetsListener((v, insets) -> {
                int topInset = insets != null ? insets.getSystemWindowInsetTop() : 0;
                int bottomInset = insets != null ? insets.getSystemWindowInsetBottom() : 0;
                boolean changed = activity.drawerTopInsetPx != topInset || activity.drawerBottomInsetPx != bottomInset;
                activity.drawerTopInsetPx = topInset;
                activity.drawerBottomInsetPx = bottomInset;
                if (v.getPaddingTop() != topInset || v.getPaddingBottom() != bottomInset) {
                    v.setPadding(v.getPaddingLeft(), topInset, v.getPaddingRight(), bottomInset);
                }
                if (changed) {
                    Toolbar toolbar = activity.findViewById(R.id.toolbar);
                    if (toolbar != null) {
                        activity.applyMainReadableTheme(toolbar);
                    }
                }
                return insets;
            });
            navDrawer.requestApplyInsets();
        }
    }

    void rebuildDrawerStorageEntries() {
        List<DrawerEntry> fixedEntries = new ArrayList<>();

        // Built-in storage shortcuts belong to the bottom-adjacent shortcut zone.
        fixedEntries.add(new DrawerEntry(
                DrawerEntry.ACTION_RECENT,
                R.drawable.ic_recent,
                activity.getString(R.string.recent),
                null,
                null));

        File internal = Environment.getExternalStorageDirectory();
        if (internal != null) {
            fixedEntries.add(new DrawerEntry(
                    DrawerEntry.ACTION_INTERNAL,
                    R.drawable.ic_storage_internal,
                    activity.getString(R.string.internal_storage),
                    internal.getAbsolutePath(),
                    internal.getAbsolutePath()));
        }

        for (File sd : detectExternalSdCards()) {
            fixedEntries.add(new DrawerEntry(
                    DrawerEntry.ACTION_EXTERNAL_SD,
                    R.drawable.ic_storage_sdcard,
                    activity.getString(R.string.external_storage),
                    sd.getAbsolutePath(),
                    sd.getAbsolutePath()));
        }

        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null) {
            fixedEntries.add(new DrawerEntry(
                    DrawerEntry.ACTION_DOWNLOADS,
                    R.drawable.ic_download,
                    activity.getString(R.string.downloads),
                    downloads.getAbsolutePath(),
                    downloads.getAbsolutePath()));
        }

        // Bottom-adjacent shortcut zone: built-in storage shortcuts plus user-added folder shortcuts.
        // This zone is independent from the recent-folder list and is pinned directly above
        // File Open / Bookmarks / Settings. It keeps five visible rows and scrolls internally
        // instead of growing when more shortcuts are added.
        List<DrawerEntry> shortcutEntries = new ArrayList<>(fixedEntries);
        addShortcutFolderEntries(shortcutEntries);
        addShortcutPlaceholderRows(shortcutEntries);

        List<DrawerEntry> recentFolderEntries = new ArrayList<>();
        addRecentFolderEntries(recentFolderEntries);

        if (activity.drawerEntryAdapter != null) activity.drawerEntryAdapter.setEntries(recentFolderEntries);
        if (activity.drawerFixedEntryAdapter != null) activity.drawerFixedEntryAdapter.setEntries(new ArrayList<>());
        if (activity.drawerShortcutEntryAdapter != null) activity.drawerShortcutEntryAdapter.setEntries(shortcutEntries);

        if (activity.drawerStorageList != null) {
            applyRecentFolderListHeight(activity.drawerStorageList, recentFolderEntries.size());
        }
        if (activity.drawerFixedList != null) {
            applyFixedRowListHeight(activity.drawerFixedList, 0, 0);
        }
        if (activity.drawerShortcutList != null) {
            applyFixedRowListHeight(activity.drawerShortcutList, shortcutEntries.size(), 5);
        }
        if (activity.drawerRecentFoldersHeader != null) {
            // Keep the Recent folders header anchored even after clearing the list.
            // Only the clear action disappears so the title position/shape does not jump.
            activity.drawerRecentFoldersHeader.setVisibility(View.VISIBLE);
        }
        if (activity.drawerRecentFoldersTitle != null) {
            activity.drawerRecentFoldersTitle.setText(R.string.recent_folders);
        }
        if (activity.drawerRecentFoldersClearButton != null) {
            activity.drawerRecentFoldersClearButton.setVisibility(recentFolderEntries.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void applyFixedRowListHeight(@NonNull RecyclerView list, int itemCount, int maxRows) {
        android.view.ViewGroup.LayoutParams lp = list.getLayoutParams();
        if (lp == null) return;

        int rows = Math.max(0, Math.min(itemCount, maxRows));
        lp.height = rows <= 0 ? 0 : activity.dpToPx(rows * 48);
        list.setLayoutParams(lp);
        list.setVisibility(rows <= 0 ? View.GONE : View.VISIBLE);
        list.setNestedScrollingEnabled(itemCount > maxRows);
        list.setOverScrollMode(itemCount > maxRows
                ? View.OVER_SCROLL_IF_CONTENT_SCROLLS
                : View.OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(itemCount > maxRows);
    }

    private void applyRecentFolderListHeight(@NonNull RecyclerView list, int itemCount) {
        android.view.ViewGroup.LayoutParams rawLp = list.getLayoutParams();
        if (rawLp == null) return;

        if (rawLp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rawLp;
            lp.height = 0;
            lp.weight = 1f;
            list.setLayoutParams(lp);
        } else {
            rawLp.height = 0;
            list.setLayoutParams(rawLp);
        }

        // Keep this flexible list visible even when it is empty. It acts as the
        // spacer above the bottom shortcut zone, so Recent/Internal/External/
        // Downloads and user folder shortcuts stay attached to the bottom buttons
        // instead of floating under the recent-folder section.
        list.setVisibility(View.VISIBLE);
        list.setNestedScrollingEnabled(true);
        list.setOverScrollMode(itemCount > 0
                ? View.OVER_SCROLL_IF_CONTENT_SCROLLS
                : View.OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(itemCount > 0);
    }

    private void addShortcutPlaceholderRows(@NonNull List<DrawerEntry> entries) {
        // The bottom shortcut/storage zone is a fixed-height scrollable window.
        // Keep five visible rows so it stays attached above File Open / Bookmarks /
        // Settings, but never let added shortcuts expand the drawer vertically.
        final int visibleShortcutRows = 5;
        int missing = Math.max(0, visibleShortcutRows - entries.size());
        for (int i = 0; i < missing; i++) {
            entries.add(new DrawerEntry(
                    DrawerEntry.ACTION_FOLDER_SHORTCUT,
                    R.drawable.ic_folder,
                    getShortcutPlaceholderTitle(),
                    null,
                    null));
        }
    }

    private String getShortcutPlaceholderTitle() {
        return activity.getString(R.string.shortcut_placeholder);
    }

    private void addShortcutFolderEntries(@NonNull List<DrawerEntry> entries) {
        if (activity.prefs == null) return;

        for (String path : activity.prefs.getFolderShortcuts(30)) {
            if (path == null || path.trim().isEmpty()) continue;
            File folder = new File(path.trim());
            if (!folder.exists() || !folder.isDirectory() || !folder.canRead()) continue;
            if (isBuiltInDrawerPath(folder.getAbsolutePath())) continue;

            String name = folder.getName();
            if (name.isEmpty()) name = folder.getAbsolutePath();
            entries.add(new DrawerEntry(
                    DrawerEntry.ACTION_FOLDER_SHORTCUT,
                    R.drawable.ic_folder,
                    name,
                    folder.getAbsolutePath(),
                    folder.getAbsolutePath()));
            if (entries.size() >= 30) break;
        }
    }

    private void addRecentFolderEntries(@NonNull List<DrawerEntry> entries) {
        if (activity.bookmarkManager == null && activity.prefs == null) return;

        LinkedHashSet<String> recentPaths = new LinkedHashSet<>();
        String lastDirectory = activity.prefs != null ? activity.prefs.getLastDirectory() : null;
        if (lastDirectory != null && !lastDirectory.trim().isEmpty()) {
            recentPaths.add(lastDirectory);
        }

        if (activity.prefs != null) {
            recentPaths.addAll(activity.prefs.getRecentFolders(16));
        }

        if (activity.bookmarkManager != null) {
            for (ReaderState state : activity.bookmarkManager.getRecentFiles(50)) {
                File file = new File(state.getFilePath());
                File parent = file.isDirectory() ? file : file.getParentFile();
                if (parent != null) recentPaths.add(parent.getAbsolutePath());
                if (recentPaths.size() >= 12) break;
            }
        }

        List<DrawerEntry> folderEntries = new ArrayList<>();
        for (String path : recentPaths) {
            if (path == null || path.trim().isEmpty()) continue;
            File folder = new File(path);
            if (!folder.exists() || !folder.isDirectory() || !folder.canRead()) continue;
            if (isBuiltInDrawerPath(folder.getAbsolutePath())) continue;
            if (activity.prefs != null && activity.prefs.isRecentFolderHidden(folder.getAbsolutePath())) continue;
            if (activity.prefs != null && activity.prefs.isFolderShortcut(folder.getAbsolutePath())) continue;
            String name = folder.getName();
            if (name.isEmpty()) name = folder.getAbsolutePath();
            folderEntries.add(new DrawerEntry(
                    DrawerEntry.ACTION_RECENT_FOLDER,
                    R.drawable.ic_folder,
                    name,
                    folder.getAbsolutePath(),
                    folder.getAbsolutePath()));
            if (folderEntries.size() >= 10) break;
        }

        entries.addAll(folderEntries);
    }

    boolean isBuiltInDrawerPath(@NonNull String path) {
        File internal = Environment.getExternalStorageDirectory();
        if (internal != null && path.equals(internal.getAbsolutePath())) return true;

        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null && path.equals(downloads.getAbsolutePath())) return true;

        if (path.equals("/storage")) return true;

        for (File sd : detectExternalSdCards()) {
            if (path.equals(sd.getAbsolutePath())) return true;
        }
        return false;
    }

    /**
     * Locate non-emulated external storage volumes (SD cards, USB OTG).
     * Cached after the first successful detection so the drawer rebuild
     * does not re-scan /storage and re-query getExternalFilesDirs() up to
     * 50 times per onResume.
     */
    List<File> detectExternalSdCards() {
        if (activity.cachedSdCards != null) return activity.cachedSdCards;

        Set<String> seen = new LinkedHashSet<>();
        List<File> result = new ArrayList<>();

        // Method 1: scan /storage for siblings of "emulated"
        File storage = new File("/storage");
        File[] storageChildren = storage.listFiles();
        if (storageChildren != null) {
            for (File f : storageChildren) {
                String name = f.getName();
                if (name.equals("emulated") || name.equals("self")
                        || name.equals("enc_emulated") || name.startsWith(".")) continue;
                if (!f.isDirectory() || !f.canRead()) continue;
                String path = f.getAbsolutePath();
                if (seen.add(path)) result.add(f);
            }
        }

        // Method 2: derive from getExternalFilesDirs (skip the first = internal)
        File[] appDirs = ContextCompat.getExternalFilesDirs(activity, null);
        if (appDirs.length > 1) {
            for (int i = 1; i < appDirs.length; i++) {
                File d = appDirs[i];
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int idx = p.indexOf("/Android");
                if (idx > 0) {
                    File root = new File(p.substring(0, idx));
                    if (root.exists() && root.canRead() && seen.add(root.getAbsolutePath())) {
                        result.add(root);
                    }
                }
            }
        }

        activity.cachedSdCards = result;
        return activity.cachedSdCards;
    }

    void setupDrawerBottomActions() {
        View openFile = activity.findViewById(R.id.drawer_btn_open_file);
        View bookmarks = activity.findViewById(R.id.drawer_btn_bookmarks);
        View settings = activity.findViewById(R.id.drawer_btn_settings);

        if (openFile != null) {
            openFile.setOnClickListener(v -> {
                runActionThenCloseDrawerInBackground(() ->
                        activity.openFileLauncher.launch(getSupportedOpenMimeTypes()));
            });
        }
        if (bookmarks != null) {
            bookmarks.setOnClickListener(v -> {
                runActionThenCloseDrawerInBackground(() ->
                        activity.startActivity(new Intent(activity, BookmarkListActivity.class)));
            });
        }
        if (settings != null) {
            settings.setOnClickListener(v -> {
                runActionThenCloseDrawerInBackground(() ->
                        activity.startActivity(new Intent(activity, SettingsActivity.class)));
            });
        }
    }

    private void runActionThenCloseDrawerInBackground(@NonNull Runnable action) {
        action.run();
        if (activity.drawerLayout == null
                || !activity.drawerLayout.isDrawerVisible(GravityCompat.START)) {
            return;
        }
        activity.drawerLayout.post(activity::closeDrawerAfterSelection);
    }

    private String[] getSupportedOpenMimeTypes() {
        return new String[]{
                "text/plain",
                "text/*",
                "application/pdf",
                "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-word.document.macroEnabled.12",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
                "application/vnd.ms-word.template.macroEnabled.12",
                "image/*",
                "application/octet-stream"
        };
    }
}
