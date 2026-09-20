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
        return move(source, destination, overwrite, progress, assignTotalBytes, null);
    }

    public static boolean move(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress,
                               boolean assignTotalBytes,
                               @Nullable FileTreeProgressTracker tracker) {
        if (!canTransfer(source, destination, overwrite)
                || (progress != null && !progress.checkpoint())) return false;

        if (progress != null) {
            if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            if (!tracker.isReady()) return false;
            if (assignTotalBytes) progress.setTotalBytes(tracker.totalBytes());
            if (!progress.checkpoint()) return false;
        }

        try {
            if (!FileTreeWalk.existsNoFollow(destination) && source.renameTo(destination)) {
                // The rename is the commit point. Late cancellation must not turn
                // a successful move into failure and lose its metadata updates.
                if (tracker != null) tracker.onMoved(source);
                if (progress != null && assignTotalBytes) progress.markComplete();
                return true;
            }
        } catch (IOException | SecurityException ignored) {
        }

        boolean copied = copy(source, destination, overwrite, progress, false, tracker);
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
        return copy(source, destination, overwrite, progress, true, null);
    }

    public static boolean copy(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress,
                               boolean assignTotalBytes) {
        return copy(source, destination, overwrite, progress, assignTotalBytes, null);
    }

    public static boolean copy(@NonNull File source,
                               @NonNull File destination,
                               boolean overwrite,
                               @Nullable FileOperationProgress progress,
                               boolean assignTotalBytes,
                               @Nullable FileTreeProgressTracker tracker) {
        if (!canTransfer(source, destination, overwrite)
                || (progress != null && !progress.checkpoint())) return false;
        if (progress != null) {
            if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            if (!tracker.isReady()) return false;
            if (assignTotalBytes) progress.setTotalBytes(tracker.totalBytes());
            if (!progress.checkpoint()) return false;
        }
        return stageAndCommit(source, destination, overwrite, progress, tracker);
    }

    /** Reject either containment direction before creating or replacing anything. */
    static boolean canTransfer(@NonNull File source, @NonNull File destination, boolean overwrite) {
        try {
            FileTreeWalk.Kind sourceKind = FileTreeWalk.kind(source);
            FileTreeWalk.Kind destinationKind = FileTreeWalk.kind(destination);
            if (sourceKind != FileTreeWalk.Kind.FILE && sourceKind != FileTreeWalk.Kind.DIRECTORY) return false;
            if (destinationKind == FileTreeWalk.Kind.LINK || destinationKind == FileTreeWalk.Kind.OTHER) return false;
            File src = source.getCanonicalFile();
            File dst = destination.getCanonicalFile();
            if (isSameOrDescendant(src, dst) || isSameOrDescendant(dst, src)) return false;
            File parent = destination.getAbsoluteFile().getParentFile();
            return parent != null && parent.isDirectory()
                    && (destinationKind == FileTreeWalk.Kind.MISSING || overwrite);
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
            boolean copied = copyTree(source, staged, progress, tracker);
            if (!copied || (progress != null && !progress.checkpoint())) return false;
            if (!canTransfer(source, destination, overwrite)) return false;

            // Only rename after all streams have closed and staging succeeded.
            // Cancellation is intentionally not observed between these renames:
            // complete the short commit/rollback sequence first.
            if (FileTreeWalk.existsNoFollow(destination)) {
                if (!destination.renameTo(previous)) return false;
                backedUp = true;
            }
            if (FileTreeWalk.existsNoFollow(destination) || !staged.renameTo(destination)) return false;
            committed = true;
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        } finally {
            if (ownsTransaction) {
                if (backedUp && !committed) {
                    // If rollback itself is denied, preserve previous in the
                    // transaction directory for recovery; never clean it away.
                    try {
                        if (!FileTreeWalk.existsNoFollow(destination)) previous.renameTo(destination);
                    } catch (IOException | SecurityException ignored) { }
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
        return delete(target, progress, assignTotalBytes, null);
    }

    public static boolean delete(@NonNull File target,
                                 @Nullable FileOperationProgress progress,
                                 boolean assignTotalBytes,
                                 @Nullable FileTreeProgressTracker tracker) {
        if (progress != null && assignTotalBytes) {
            if (tracker == null) tracker = FileTreeProgressTracker.create(progress, target);
            if (!tracker.isReady()) return false;
            progress.setTotalBytes(tracker.totalBytes());
        }
        if (tracker != null && !tracker.isReady()) return false;
        return deleteRecursively(target, progress, tracker);
    }

    public static boolean deleteAll(@NonNull List<File> targets, @Nullable FileOperationProgress progress) {
        FileTreeProgressTracker tracker = null;
        if (progress != null) {
            tracker = FileTreeProgressTracker.create(progress, targets);
            if (!tracker.isReady()) return false;
            progress.setTotalBytes(tracker.totalBytes());
        }
        boolean allDeleted = true;
        for (File target : targets) {
            if (target == null) continue;
            if (!deleteRecursively(target, progress, tracker)) allDeleted = false;
            if (progress != null && progress.isCancelled()) return false;
        }
        return allDeleted;
    }

    private static boolean deleteRecursively(@NonNull File target,
                                             @Nullable FileOperationProgress progress,
                                             @Nullable FileTreeProgressTracker tracker) {
        try {
            return FileTreeWalk.walk(target, () -> progress == null || progress.checkpoint(),
                    new FileTreeWalk.Visitor() {
                        @Override public boolean enter(FileTreeWalk.Entry entry) throws IOException {
                            if (entry.kind == FileTreeWalk.Kind.MISSING) return true;
                            if (progress != null) {
                                if (tracker != null) {
                                    if (entry.kind == FileTreeWalk.Kind.DIRECTORY) tracker.onDirectory(entry.file);
                                    else tracker.onFile(entry.file);
                                } else {
                                    progress.setDetail(entry.file.getName());
                                    progress.setFolder(parentDisplayName(entry.file));
                                }
                                if (!progress.checkpoint()) return false;
                            }
                            entry.recheck();
                            if (entry.kind == FileTreeWalk.Kind.DIRECTORY) return true;
                            // A symbolic link is a leaf, including a dangling link.
                            boolean deleted = entry.file.delete();
                            if (deleted && progress != null) progress.addDoneBytes(entry.bytes);
                            return deleted;
                        }

                        @Override public boolean leave(FileTreeWalk.Entry directory) {
                            return directory.file.delete();
                        }
                    });
        } catch (IOException | SecurityException ignored) {
            return false;
        }
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

    private static boolean copyTree(@NonNull File source,
                                    @NonNull File destination,
                                    @Nullable FileOperationProgress progress,
                                    @Nullable FileTreeProgressTracker tracker) {
        // One scratch buffer per operation, shared sequentially by all its files.
        // Independent copy operations never share mutable buffers.
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try {
            return FileTreeWalk.walk(source, () -> progress == null || progress.checkpoint(), entry -> {
                entry.recheck();
                File output = entry.relative.isEmpty() ? destination : new File(destination, entry.relative);
                if (entry.kind == FileTreeWalk.Kind.DIRECTORY) {
                    if (tracker != null) tracker.onDirectory(entry.file);
                    return output.mkdir(); // Every output belongs to the new staging tree.
                }
                if (entry.kind == FileTreeWalk.Kind.FILE) {
                    return copyRegularFile(entry, output, progress, tracker, buffer);
                }
                // Do not dereference links or treat devices/FIFOs as regular files.
                // Failure rolls back the staged copy, leaving the destination intact.
                return false;
            });
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean copyRegularFile(@NonNull FileTreeWalk.Entry entry,
                                           @NonNull File destination,
                                           @Nullable FileOperationProgress progress,
                                           @Nullable FileTreeProgressTracker tracker,
                                           @NonNull byte[] buffer) throws IOException {
        File source = entry.file;
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
        boolean copied = false;
        boolean cancelled = false;
        entry.recheck();
        long expectedBytes = entry.bytes;
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
            entry.recheck();
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
        long[] total = {0L};
        try {
            FileTreeWalk.walk(target, () -> !Thread.currentThread().isInterrupted(), entry -> {
                total[0] = entry.bytes > Long.MAX_VALUE - total[0] ? Long.MAX_VALUE : total[0] + entry.bytes;
                return true;
            });
        } catch (IOException | SecurityException ignored) { }
        return total[0]; // Best-effort file-info size; operations use a checked inventory.
    }

    @NonNull
    private static String parentDisplayName(@NonNull File file) {
        File parent = file.getParentFile();
        if (parent == null) return "";
        String name = parent.getName();
        return name == null || name.length() == 0 ? parent.getAbsolutePath() : name;
    }
}
