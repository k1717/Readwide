package com.readwide.manager.util;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Path checks for shared internal storage and mounted SD/USB volumes. */
public final class SharedStoragePaths {
    private SharedStoragePaths() {}

    public static boolean containsAny(File primary, List<File> additional, List<File> items) {
        if (items == null) return false;
        for (File item : items) {
            if (contains(primary, item)) return true;
            if (additional != null) {
                for (File root : additional) {
                    if (contains(root, item)) return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(File root, File item) {
        if (root == null || item == null) return false;
        try {
            String parent = root.getCanonicalPath();
            String path = item.getCanonicalPath();
            String prefix = parent.endsWith(File.separator) ? parent : parent + File.separator;
            return path.equals(parent) || path.startsWith(prefix);
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    public static void addReadableRoot(List<File> roots, File candidate, File primary) {
        if (candidate == null) return;
        try {
            File canonical = candidate.getCanonicalFile();
            if (!canonical.isDirectory() || !canonical.canRead()
                    || (primary != null && canonical.equals(primary.getCanonicalFile()))) return;
            for (File existing : roots) {
                if (canonical.equals(existing.getCanonicalFile())) return;
            }
            roots.add(canonical);
        } catch (IOException | SecurityException unavailable) {
            // A detached or inaccessible volume must not prevent listing the others.
        }
    }

    public static File rootFromAppFiles(File directory, String packageName) {
        if (directory == null || packageName == null || packageName.isEmpty()) return null;
        String suffix = File.separator + "Android" + File.separator + "data"
                + File.separator + packageName + File.separator + "files";
        String path = directory.getAbsolutePath();
        if (!path.endsWith(suffix) || path.length() <= suffix.length()) return null;
        return new File(path.substring(0, path.length() - suffix.length()));
    }
}
