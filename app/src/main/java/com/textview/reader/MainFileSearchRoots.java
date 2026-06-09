package com.textview.reader;

import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class MainFileSearchRoots {
    private final MainActivity activity;

    MainFileSearchRoots(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    @NonNull
    List<File> build(boolean allStorage) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (!allStorage && !activity.homeMode && activity.currentDirectory != null && activity.currentDirectory.exists()) {
            paths.add(activity.currentDirectory.getAbsolutePath());
        } else if (allStorage
                && !activity.homeMode
                && activity.currentDirectory != null
                && activity.currentDirectory.exists()
                && activity.currentDirectory.isDirectory()
                && activity.currentDirectory.canRead()) {
            paths.add(activity.currentDirectory.getAbsolutePath());
        } else {
            File internal = Environment.getExternalStorageDirectory();
            if (internal != null) paths.add(internal.getAbsolutePath());
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) paths.add(downloads.getAbsolutePath());
            for (File sd : activity.detectExternalSdCards()) paths.add(sd.getAbsolutePath());
            if (activity.prefs != null) paths.addAll(activity.prefs.getRecentFolders(10));
        }

        List<File> roots = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            File root = normalizedReadableRoot(new File(path));
            if (root == null) continue;
            addRootWithoutNestedDuplicates(roots, root);
        }
        return roots;
    }

    @Nullable
    private File normalizedReadableRoot(@NonNull File root) {
        try {
            File canonical = root.getCanonicalFile();
            return canonical.exists() && canonical.canRead() ? canonical : null;
        } catch (Exception ignored) {
            return root.exists() && root.canRead() ? root : null;
        }
    }

    private void addRootWithoutNestedDuplicates(@NonNull List<File> roots, @NonNull File candidate) {
        String candidatePath = normalizedPath(candidate);
        for (int i = roots.size() - 1; i >= 0; i--) {
            String existingPath = normalizedPath(roots.get(i));
            if (isSameOrChildPath(candidatePath, existingPath)) return;
            if (isSameOrChildPath(existingPath, candidatePath)) roots.remove(i);
        }
        roots.add(candidate);
    }

    @NonNull
    private String normalizedPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    private boolean isSameOrChildPath(@NonNull String path, @NonNull String parent) {
        String p = path.replace('\\', '/');
        String root = parent.replace('\\', '/');
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        while (root.endsWith("/") && root.length() > 1) root = root.substring(0, root.length() - 1);
        return p.equals(root) || p.startsWith(root + "/");
    }
}
