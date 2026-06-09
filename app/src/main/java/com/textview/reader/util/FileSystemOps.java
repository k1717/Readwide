package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Low-level file-system operations shared by main-screen file actions.
 *
 * This class intentionally contains no Android UI code. Callers decide how to
 * show progress, conflicts, errors, and bookmark/recent-file side effects.
 */
public final class FileSystemOps {

    private static final int COPY_BUFFER_BYTES = 1024 * 64;

    private FileSystemOps() {
    }

    public static boolean move(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite) {
        return move(source, destination, overwrite, null);
    }

    public static boolean move(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress) {
        return move(source, destination, overwrite, progress, true);
    }

    public static boolean move(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress,
                               boolean assignTotalBytes) {
        FileTreeProgressTracker tracker = assignTotalBytes
                ? FileTreeProgressTracker.create(progress, source)
                : null;
        return move(source, destination, overwrite, progress, assignTotalBytes, tracker);
    }

    public static boolean move(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress,
                               boolean assignTotalBytes,
                               @Nullable FileTreeProgressTracker tracker) {
        if (destination.exists()) {
            if (!overwrite || sameCanonicalFile(source, destination)) return false;
            if (!delete(destination)) return false;
        }

        long totalBytes = measureBytes(source);
        if (progress != null) {
            if (assignTotalBytes) {
                progress.setTotalBytes(totalBytes);
                if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            }
            if (!progress.checkpoint()) return false;
        }

        try {
            if (progress == null && source.renameTo(destination)) {
                return true;
            }
        } catch (SecurityException ignored) {
        }

        boolean copied = copy(source, destination, false, progress, totalBytes, false, tracker);
        if (!copied) return false;

        boolean deleted = delete(source);
        if (deleted && progress != null && assignTotalBytes) progress.markComplete();
        return deleted;
    }

    public static boolean copy(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite) {
        return copy(source, destination, overwrite, null);
    }

    public static boolean copy(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress) {
        return copy(source, destination, overwrite, progress, -1L, true);
    }

    public static boolean copy(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress,
                               boolean assignTotalBytes) {
        return copy(source, destination, overwrite, progress, -1L, assignTotalBytes);
    }

    public static boolean copy(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress,
                               boolean assignTotalBytes,
                               @Nullable FileTreeProgressTracker tracker) {
        return copy(source, destination, overwrite, progress, -1L, assignTotalBytes, tracker);
    }

    private static boolean copy(@NonNull File source,
                                @NonNull File destination,
                                boolean overwrite,
                                @Nullable FileOperationProgress progress,
                                long knownTotalBytes,
                                boolean assignTotalBytes) {
        FileTreeProgressTracker tracker = assignTotalBytes
                ? FileTreeProgressTracker.create(progress, source)
                : null;
        return copy(source, destination, overwrite, progress, knownTotalBytes, assignTotalBytes, tracker);
    }

    private static boolean copy(@NonNull File source,
                                @NonNull File destination,
                                boolean overwrite,
                                @Nullable FileOperationProgress progress,
                                long knownTotalBytes,
                                boolean assignTotalBytes,
                                @Nullable FileTreeProgressTracker tracker) {
        if (!source.exists()) return false;
        if (destination.exists()) {
            if (!overwrite || sameCanonicalFile(source, destination)) return false;
            if (!delete(destination)) return false;
        }
        if (progress != null) {
            if (assignTotalBytes) {
                progress.setTotalBytes(knownTotalBytes >= 0L ? knownTotalBytes : measureBytes(source));
                if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            }
            if (!progress.checkpoint()) return false;
        }
        if (source.isDirectory()) {
            return copyDirectoryRecursively(source, destination, progress, tracker);
        }
        if (source.isFile()) {
            return copyRegularFile(source, destination, progress, tracker);
        }
        return false;
    }

    public static boolean delete(@NonNull File target) {
        return delete(target, null);
    }

    public static boolean delete(@NonNull File target, @Nullable FileOperationProgress progress) {
        return delete(target, progress, true);
    }

    public static boolean delete(@NonNull File target,
                                 @Nullable FileOperationProgress progress,
                                 boolean assignTotalBytes) {
        FileTreeProgressTracker tracker = assignTotalBytes
                ? FileTreeProgressTracker.create(progress, target)
                : null;
        return delete(target, progress, assignTotalBytes, tracker);
    }

    public static boolean delete(@NonNull File target,
                                 @Nullable FileOperationProgress progress,
                                 boolean assignTotalBytes,
                                 @Nullable FileTreeProgressTracker tracker) {
        if (progress != null && assignTotalBytes) {
            progress.setTotalBytes(measureBytes(target));
            if (tracker == null) tracker = FileTreeProgressTracker.create(progress, target);
        }
        return deleteRecursively(target, progress, tracker);
    }

    public static boolean deleteAll(@NonNull List<File> targets, @Nullable FileOperationProgress progress) {
        FileTreeProgressTracker tracker = null;
        if (progress != null) {
            long totalBytes = 0L;
            for (File target : targets) {
                if (target == null) continue;
                totalBytes += measureBytes(target);
                if (totalBytes < 0L) {
                    totalBytes = Long.MAX_VALUE;
                    break;
                }
            }
            progress.setTotalBytes(totalBytes);
            tracker = FileTreeProgressTracker.create(progress, targets);
        }
        boolean allDeleted = true;
        for (File target : targets) {
            if (target == null || !target.exists()) continue;
            if (!deleteRecursively(target, progress, tracker)) allDeleted = false;
            if (progress != null && progress.isCancelled()) return false;
        }
        return allDeleted;
    }

    private static boolean deleteRecursively(@NonNull File target, @Nullable FileOperationProgress progress) {
        return deleteRecursively(target, progress, null);
    }

    private static boolean deleteRecursively(@NonNull File target,
                                             @Nullable FileOperationProgress progress,
                                             @Nullable FileTreeProgressTracker tracker) {
        if (!target.exists()) return true;
        if (progress != null) {
            if (tracker != null) {
                if (target.isDirectory()) tracker.onDirectory(target);
                else tracker.onFile(target);
            } else {
                progress.setDetail(target.getName());
                progress.setFolder(parentDisplayName(target));
            }
            if (!progress.checkpoint()) return false;
        }
        if (target.isDirectory()) {
            File[] children;
            try {
                children = target.listFiles();
            } catch (SecurityException ignored) {
                return false;
            }
            if (children == null) return false;
            for (File child : children) {
                if (!deleteRecursively(child, progress, tracker)) return false;
            }
        }
        long bytes = target.isFile() ? Math.max(0L, target.length()) : 0L;
        try {
            boolean deleted = target.delete();
            if (deleted && progress != null) progress.addDoneBytes(bytes);
            return deleted;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static int countExistingTargets(@NonNull List<File> targets) {
        int count = 0;
        for (File target : targets) {
            if (target != null && target.exists()) count++;
        }
        return count;
    }

    private static int countTopLevelDirectories(@NonNull List<File> targets) {
        int count = 0;
        for (File target : targets) {
            if (target != null && target.isDirectory()) count++;
        }
        return count;
    }

    public static boolean isSameOrDescendant(@NonNull File ancestor,
                                             @NonNull File candidate) {
        try {
            File ancestorCanonical = ancestor.getCanonicalFile();
            File current = candidate.getCanonicalFile();
            while (current != null) {
                if (ancestorCanonical.equals(current)) return true;
                current = current.getParentFile();
            }
            return false;
        } catch (IOException ignored) {
            String ancestorPath = ancestor.getAbsolutePath();
            String candidatePath = candidate.getAbsolutePath();
            return candidatePath.equals(ancestorPath)
                    || candidatePath.startsWith(ancestorPath + File.separator);
        }
    }

    public static boolean sameCanonicalFile(@NonNull File a, @NonNull File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static boolean copyDirectoryRecursively(@NonNull File sourceDir,
                                                    @NonNull File destinationDir,
                                                    @Nullable FileOperationProgress progress,
                                                    @Nullable FileTreeProgressTracker tracker) {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) return false;
        if (isSameOrDescendant(sourceDir, destinationDir)) return false;
        if (progress != null) {
            if (tracker != null) tracker.onDirectory(sourceDir);
            if (!progress.checkpoint()) return false;
        }
        if (!destinationDir.exists()) {
            try {
                if (!destinationDir.mkdirs()) return false;
            } catch (SecurityException ignored) {
                return false;
            }
        }
        File[] children;
        try {
            children = sourceDir.listFiles();
        } catch (SecurityException ignored) {
            return false;
        }
        if (children == null) return false;
        for (File child : children) {
            if (progress != null && !progress.checkpoint()) {
                delete(destinationDir);
                return false;
            }
            File childDestination = new File(destinationDir, child.getName());
            boolean ok = child.isDirectory()
                    ? copyDirectoryRecursively(child, childDestination, progress, tracker)
                    : copyRegularFile(child, childDestination, progress, tracker);
            if (!ok) {
                delete(destinationDir);
                return false;
            }
        }
        return true;
    }

    private static boolean copyRegularFile(@NonNull File source,
                                           @NonNull File destination,
                                           @Nullable FileOperationProgress progress) {
        return copyRegularFile(source, destination, progress, null);
    }

    private static boolean copyRegularFile(@NonNull File source,
                                           @NonNull File destination,
                                           @Nullable FileOperationProgress progress,
                                           @Nullable FileTreeProgressTracker tracker) {
        File parent = destination.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory()) return false;
        if (progress != null) {
            if (tracker != null) tracker.onFile(source);
            else {
                progress.setDetail(source.getName());
                progress.setFolder(parentDisplayName(source));
            }
            if (!progress.checkpoint()) return false;
        }
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        boolean copied = false;
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) {
                    copied = false;
                    break;
                }
                out.write(buffer, 0, read);
                if (progress != null) progress.addDoneBytes(read);
            }
            out.flush();
            copied = destination.length() == source.length();
        } catch (IOException | SecurityException ignored) {
            copied = false;
        }

        if (!copied) {
            try {
                destination.delete();
            } catch (SecurityException ignored) {
            }
        }
        return copied;
    }

    public static long measureBytes(@NonNull File target) {
        if (!target.exists()) return 0L;
        if (target.isFile()) return Math.max(0L, target.length());
        if (!target.isDirectory()) return 0L;
        File[] children;
        try {
            children = target.listFiles();
        } catch (SecurityException ignored) {
            return 0L;
        }
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            total += measureBytes(child);
            if (total < 0L) return Long.MAX_VALUE;
        }
        return total;
    }

    @NonNull
    private static String parentDisplayName(@NonNull File file) {
        File parent = file.getParentFile();
        if (parent == null) return "";
        String name = parent.getName();
        return name == null || name.length() == 0 ? parent.getAbsolutePath() : name;
    }
}
