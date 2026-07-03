package com.readwide.manager.util;

public final class ReaderRestoreTargetMath {
    private ReaderRestoreTargetMath() {}

    public static boolean matchesCurrentTarget(String restorePath,
                                               String restoreUri,
                                               String currentPath,
                                               String currentUri) {
        if (!isBlank(currentPath)) {
            return same(restorePath, currentPath);
        }
        if (!isBlank(currentUri)) {
            return same(restoreUri, currentUri);
        }
        return false;
    }

    public static boolean matchesLoadedFile(String restorePath,
                                            String restoreUri,
                                            String loadedFilePath) {
        if (!isBlank(restorePath) && !isBlank(loadedFilePath)) {
            return same(restorePath, loadedFilePath);
        }
        return false;
    }

    private static boolean same(String a, String b) {
        return !isBlank(a) && !isBlank(b) && a.equals(b);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
