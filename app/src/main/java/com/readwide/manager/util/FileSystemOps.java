package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

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

    /**
     * Renames {@code source} to a new name in the same directory, correctly
     * handling case-only changes on case-insensitive/case-preserving file
     * systems (FAT32/exFAT and the sdcardfs/FUSE layers Android often mounts
     * external storage through).
     *
     * <p>On such a file system {@code new File(dir, "test").renameTo(new File(
     * dir, "tESt"))} resolves the destination to the <em>same</em> entry as the
     * source, so the rename is treated as renaming a file onto itself: it
     * reports success but the stored name never changes. A rename that also adds
     * or removes characters works, which is exactly the reported symptom
     * (test -> tESt fails, test -> tEStt works). To force the directory entry to
     * be rewritten, a case-only rename is performed in two hops through a unique
     * temporary name in the same directory.</p>
     *
     * @return true if the entry now has the requested name
     */
    public static boolean renameInPlace(@NonNull File source, @NonNull String newName) {
        if (!source.exists() || newName.isEmpty() || newName.equals(".") || newName.equals("..")
                || newName.indexOf('/') >= 0 || newName.indexOf('\\') >= 0
                || newName.indexOf('\0') >= 0) return false;
        File parent = source.getParentFile();
        if (parent == null) return false;
        File target = new File(parent, newName);

        if (source.getName().equals(newName)) {
            // Already the exact name (including case); nothing to do.
            return true;
        }

        boolean caseOnlyChange = source.getName().equalsIgnoreCase(newName);
        if (target.exists()) {
            // Canonical paths alone need not preserve case on every Android
            // mount. A separately listed target is a collision, even when its
            // name differs only by case (or it is a hard link to the source).
            String[] entries = parent.list();
            if (entries == null) return false;
            boolean sourceListed = false;
            for (String entry : entries) {
                if (entry.equals(newName)) return false;
                if (entry.equals(source.getName())) sourceListed = true;
            }
            if (!caseOnlyChange || !sourceListed) return false;
        }
        if (!caseOnlyChange) {
            // Different name (not just case): a plain rename is correct. Refuse to
            // clobber an unrelated existing entry.
            if (target.exists() && !sameCanonicalFile(source, target)) return false;
            return source.renameTo(target);
        }

        // Case-only change: hop through a unique temporary name so the file
        // system actually rewrites the entry instead of collapsing source and
        // destination to the same inode.
        File temp = null;
        for (int i = 0; i < 10000; i++) {
            File candidate = new File(parent, ".rwrename_" + System.nanoTime() + "_" + i);
            if (!candidate.exists()) {
                temp = candidate;
                break;
            }
        }
        if (temp == null) return false;

        if (!source.renameTo(temp)) {
            return false;
        }
        if (!target.exists() && temp.renameTo(target)) {
            return true;
        }
        // Second hop failed: restore the original name so we don't leave the
        // entry stranded under the temporary name.
        //noinspection ResultOfMethodCallIgnored
        if (!source.exists()) temp.renameTo(source);
        return false;
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
        if (!canTransfer(source, destination, overwrite)
                || (progress != null && !progress.checkpoint())) return false;

        long totalBytes = measureBytes(source);
        if (progress != null) {
            if (assignTotalBytes) {
                progress.setTotalBytes(totalBytes);
                if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            }
            if (!progress.checkpoint()) return false;
        }

        try {
            if (progress == null && !destination.exists() && source.renameTo(destination)) {
                return true;
            }
        } catch (SecurityException ignored) {
        }

        boolean copied = copy(source, destination, overwrite, progress, totalBytes, false, tracker);
        if (!copied) return false;

        // The replacement is committed. A late cancellation keeps the source
        // too; never remove it after a cancelled or incomplete staging copy.
        if (progress != null && !progress.checkpoint()) return false;

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
        if (!canTransfer(source, destination, overwrite)
                || (progress != null && !progress.checkpoint())) return false;
        if (progress != null) {
            if (assignTotalBytes) {
                progress.setTotalBytes(knownTotalBytes >= 0L ? knownTotalBytes : measureBytes(source));
                if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            }
            if (!progress.checkpoint()) return false;
        }
        return stageAndCommit(source, destination, overwrite, progress, tracker);
    }

    /** Reject either containment direction before creating or replacing anything. */
    static boolean canTransfer(@NonNull File source, @NonNull File destination, boolean overwrite) {
        try {
            if (!source.exists() || (!source.isFile() && !source.isDirectory())) return false;
            File src = source.getCanonicalFile();
            File dst = destination.getCanonicalFile();
            if (isSameOrDescendant(src, dst) || isSameOrDescendant(dst, src)) return false;
            File parent = destination.getAbsoluteFile().getParentFile();
            return parent != null && parent.isDirectory() && (!destination.exists() || overwrite);
        } catch (IOException | SecurityException ignored) {
            return false; // Do not use an unresolved path for an overwrite.
        }
    }

    private static boolean stageAndCommit(File source, File destination, boolean overwrite,
                                          FileOperationProgress progress, FileTreeProgressTracker tracker) {
        File parent = destination.getAbsoluteFile().getParentFile();
        File transaction = new File(parent, ".rwtransfer_" + UUID.randomUUID());
        File staged = new File(transaction, "replacement");
        File previous = new File(transaction, "previous");
        boolean ownsTransaction = false;
        boolean backedUp = false;
        boolean committed = false;
        try {
            if (progress != null && !progress.checkpoint()) return false;
            if (!transaction.mkdir()) return false;
            ownsTransaction = true;
            boolean copied = source.isDirectory()
                    ? copyDirectoryRecursively(source, staged, progress, tracker)
                    : copyRegularFile(source, staged, progress, tracker);
            if (!copied || (progress != null && !progress.checkpoint())) return false;
            if (!canTransfer(source, destination, overwrite)) return false;

            // Only rename after all streams have closed and staging succeeded.
            // Cancellation is intentionally not observed between these renames:
            // complete the short commit/rollback sequence first.
            if (destination.exists()) {
                if (!destination.renameTo(previous)) return false;
                backedUp = true;
            }
            if (destination.exists() || !staged.renameTo(destination)) return false;
            committed = true;
            return true;
        } catch (SecurityException ignored) {
            return false;
        } finally {
            if (ownsTransaction) {
                if (backedUp && !committed) {
                    // If rollback itself is denied, preserve previous in the
                    // transaction directory for recovery; never clean it away.
                    try {
                        if (!destination.exists()) previous.renameTo(destination);
                    } catch (SecurityException ignored) { }
                }
                delete(staged);
                if (committed) delete(previous);
                // Nonrecursive: a failed rollback/cleanup retains the backup.
                try { transaction.delete(); } catch (SecurityException ignored) { }
            }
        }
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
        boolean cancelled = false;
        long expectedBytes = source.length();
        long writtenBytes = 0L;
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) {
                    cancelled = true;
                    break;
                }
                out.write(buffer, 0, read);
                writtenBytes += read;
                if (progress != null) progress.addDoneBytes(read);
            }
            out.flush();
            copied = !cancelled && (progress == null || progress.checkpoint())
                    && writtenBytes == expectedBytes && destination.length() == expectedBytes
                    && source.length() == expectedBytes;
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
