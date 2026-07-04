package com.readwide.manager.util;

/**
 * Target matching for the reader's background memory restore.
 *
 * <p>When the reader is trimmed in the background it stores a restore intent
 * for the file it was showing. If the same activity instance is then pointed
 * at a different file (onNewIntent), that stored intent is stale: executing
 * it would silently reopen the previous file over the new one. Before a
 * restore runs, the restore target must therefore be compared against the
 * reader's current target.</p>
 *
 * <p>The fallback rule is deliberately strict. When the current intent names
 * an explicit path or URI, only that intent decides the match - the loaded
 * file path is <em>not</em> consulted, because during a file switch it can
 * still point at the previous file and would wrongly let the previous file's
 * restore through. The loaded-file fallback applies only when the current
 * intent carries no target at all.</p>
 */
public final class ReaderRestoreTargetMath {
    private ReaderRestoreTargetMath() {
    }

    public static boolean matchesRestoreTarget(String restorePath,
                                               String restoreUri,
                                               String currentPath,
                                               String currentUri,
                                               String loadedFilePath) {
        if (!isBlank(currentPath) || !isBlank(currentUri)) {
            return matchesCurrentTarget(restorePath, restoreUri, currentPath, currentUri);
        }
        return matchesLoadedFile(restorePath, restoreUri, loadedFilePath);
    }

    private static boolean matchesCurrentTarget(String restorePath,
                                                String restoreUri,
                                                String currentPath,
                                                String currentUri) {
        if (!isBlank(restorePath) && !isBlank(currentPath) && restorePath.equals(currentPath)) {
            return true;
        }
        return !isBlank(restoreUri) && !isBlank(currentUri) && restoreUri.equals(currentUri);
    }

    private static boolean matchesLoadedFile(String restorePath,
                                             String restoreUri,
                                             String loadedFilePath) {
        if (isBlank(loadedFilePath)) return false;
        if (!isBlank(restorePath)) return restorePath.equals(loadedFilePath);
        return !isBlank(restoreUri) && restoreUri.equals(loadedFilePath);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
