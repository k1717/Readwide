package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveCancellation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Low-level file-system operations shared by main-screen file actions.
 *
 * This class intentionally contains no Android UI code. Callers decide how to
 * show progress, conflicts, errors, and bookmark/recent-file side effects.
 */
public final class FileSystemOps {

    private static final int COPY_BUFFER_BYTES = 1024 * 64;

    public enum TransferResult {
        FAILED,
        COMPLETED,
        COPIED_SOURCE_REMAINS
    }

    interface SourceCheck {
        void recheck() throws IOException;
    }

    public static final class OverwriteTarget {
        private final File target;
        private final DestinationParent parent;
        private final DestinationSnapshot snapshot;

        private OverwriteTarget(File target, DestinationParent parent,
                                DestinationSnapshot snapshot) {
            this.target = target.getAbsoluteFile();
            this.parent = parent;
            this.snapshot = snapshot;
        }

        public void recheck(File destination) throws IOException {
            if (!target.equals(destination.getAbsoluteFile())) throw new IOException("Overwrite target changed");
            parent.recheck();
            snapshot.recheck(null);
            parent.recheck();
        }
    }

    public static OverwriteTarget captureOverwriteTarget(File target) throws IOException {
        DestinationParent parent = DestinationParent.capture(target);
        if (!FileTreeWalk.existsNoFollow(target)) throw new IOException("Overwrite target disappeared");
        OverwriteTarget approval = new OverwriteTarget(target, parent,
                DestinationSnapshot.capture(target, null));
        approval.recheck(target);
        return approval;
    }

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
        if (!isSingleEntryName(newName)) return false;
        try {
            if (!FileTreeWalk.existsNoFollow(source)) return false;
            File parent = source.getParentFile();
            if (parent == null) return false;
            if (source.getName().equals(newName)) return true;
            File target = new File(parent, newName);
            boolean caseOnly = source.getName().equalsIgnoreCase(newName);
            if (FileTreeWalk.existsNoFollow(target)
                    && (!caseOnly || !isCaseAlias(parent, source.getName(), newName))) return false;
            if (caseOnly) return renameThroughTemporary(source, target, parent);
            // Inspect without following links: a dangling target still occupies a name.
            return !FileTreeWalk.existsNoFollow(target) && source.renameTo(target);
        } catch (IOException | SecurityException unavailable) {
            ArchiveCancellation.restoreIfCancelled(unavailable, null);
            return false;
        }
    }

    public static boolean isSingleEntryName(@NonNull String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private static boolean isCaseAlias(File parent, String sourceName, String targetName) {
        // Case-insensitive lookup may find the source. A separately listed target
        // is instead a collision, including a hard link or dangling symbolic link.
        String[] names = parent.list();
        if (names == null) return false;
        boolean sourceListed = false;
        for (String name : names) {
            if (name.equals(targetName)) return false;
            if (name.equals(sourceName)) sourceListed = true;
        }
        return sourceListed;
    }

    private static boolean renameThroughTemporary(File source, File target, File parent) throws IOException {
        File temporary = new File(parent, ".rwrename_" + UUID.randomUUID());
        // No retries are needed for a UUID collision; fail without taking ownership.
        if (FileTreeWalk.existsNoFollow(temporary) || !source.renameTo(temporary)) return false;
        boolean committed = false;
        try {
            committed = !FileTreeWalk.existsNoFollow(target) && temporary.renameTo(target);
            return committed;
        } finally {
            if (!committed) restoreRenameSource(temporary, source);
        }
    }

    private static void restoreRenameSource(File temporary, File source) {
        try {
            // Never replace a source name claimed meanwhile, including a dangling
            // link. If rollback is denied, leave the temporary entry for recovery.
            if (!FileTreeWalk.existsNoFollow(source)) temporary.renameTo(source);
        } catch (IOException | SecurityException unavailable) {
            ArchiveCancellation.restoreIfCancelled(unavailable, null);
        }
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
        return moveWithResult(source, destination, overwrite, progress, assignTotalBytes, tracker)
                == TransferResult.COMPLETED;
    }

    @NonNull
    public static TransferResult moveWithResult(@NonNull File source,
                                                @NonNull File destination,
                                                boolean overwrite,
                                                @Nullable FileOperationProgress progress,
                                                boolean assignTotalBytes,
                                                @Nullable FileTreeProgressTracker tracker) {
        return moveWithResult(source, destination, overwrite, progress, assignTotalBytes, tracker, null);
    }

    static TransferResult moveWithResult(@NonNull File source, @NonNull File destination,
                                         boolean overwrite, @Nullable FileOperationProgress progress,
                                         boolean assignTotalBytes, @Nullable FileTreeProgressTracker tracker,
                                         @Nullable SourceCheck sourceCheck) {
        if (!canTransfer(source, destination, overwrite)) return TransferResult.FAILED;
        DestinationParent destinationParent;
        try { destinationParent = DestinationParent.capture(destination); }
        catch (IOException | SecurityException unavailable) { return TransferResult.FAILED; }
        if (progress != null && !progress.checkpoint()) return TransferResult.FAILED;

        if (progress != null) {
            if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            if (!tracker.isReady()) return TransferResult.FAILED;
            if (assignTotalBytes) progress.setTotalBytes(tracker.totalBytes());
            if (!progress.checkpoint()) return TransferResult.FAILED;
        }

        try {
            if (sourceCheck != null) sourceCheck.recheck();
            destinationParent.recheck();
            if (!canTransfer(source, destination, overwrite)) return TransferResult.FAILED;
            if (!FileTreeWalk.existsNoFollow(destination) && source.renameTo(destination)) {
                // The rename is the commit point. Late cancellation must not turn
                // a successful move into failure and lose its metadata updates.
                finishCommittedMove(source, tracker, progress, assignTotalBytes);
                return TransferResult.COMPLETED;
            }
        } catch (IOException | SecurityException ignored) {
        }

        // Only the entries actually copied belong to the removal phase. A fresh
        // recursive delete would also erase files added while copying a folder.
        List<CopiedSourceEntry> copiedSources = new ArrayList<>();
        CommittedDestination copiedDestination = new CommittedDestination(destination, destinationParent);
        boolean copied = stageAndCommit(source, destination, overwrite, progress, tracker,
                copiedSources, sourceCheck, destinationParent, copiedDestination);
        if (!copied) return TransferResult.FAILED;

        try {
            // A committed destination may contain the only copy of leaves already
            // removed from the source. Never offer this as an ordinary overwrite retry.
            if (progress != null && !progress.checkpoint()) return TransferResult.COPIED_SOURCE_REMAINS;
            if (!deleteCopiedSources(copiedSources, copiedDestination, progress)) {
                return TransferResult.COPIED_SOURCE_REMAINS;
            }
            finishCommittedMove(source, null, progress, assignTotalBytes);
            return TransferResult.COMPLETED;
        } catch (RuntimeException reportingFailure) {
            ArchiveCancellation.restoreIfCancelled(reportingFailure, progress);
            return TransferResult.COPIED_SOURCE_REMAINS;
        }
    }

    private static boolean finishCommittedMove(File source, FileTreeProgressTracker tracker,
                                                FileOperationProgress progress, boolean assignTotalBytes) {
        try {
            if (tracker != null) tracker.onMoved(source);
            if (progress != null && assignTotalBytes) progress.markComplete();
        } catch (RuntimeException reportingFailure) {
            // The filesystem change is already committed. A nonfatal UI/progress
            // failure must not strand its queue item or lose saved-path updates.
            ArchiveCancellation.restoreIfCancelled(reportingFailure, progress);
        }
        return true;
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
        return copyWithSourceCheck(source, destination, overwrite, progress, assignTotalBytes, tracker, null);
    }

    static boolean copyWithSourceCheck(@NonNull File source, @NonNull File destination,
                                       boolean overwrite, @Nullable FileOperationProgress progress,
                                       boolean assignTotalBytes, @Nullable FileTreeProgressTracker tracker,
                                       @Nullable SourceCheck sourceCheck) {
        if (!canTransfer(source, destination, overwrite)) return false;
        DestinationParent destinationParent;
        try { destinationParent = DestinationParent.capture(destination); }
        catch (IOException | SecurityException unavailable) { return false; }
        if (progress != null && !progress.checkpoint()) return false;
        if (progress != null) {
            if (tracker == null) tracker = FileTreeProgressTracker.create(progress, source);
            if (!tracker.isReady()) return false;
            if (assignTotalBytes) progress.setTotalBytes(tracker.totalBytes());
            if (!progress.checkpoint()) return false;
        }
        return stageAndCommit(source, destination, overwrite, progress, tracker,
                null, sourceCheck, destinationParent, null);
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
                                          FileOperationProgress progress, FileTreeProgressTracker tracker,
                                          List<CopiedSourceEntry> copiedSources, SourceCheck sourceCheck,
                                          DestinationParent destinationParent,
                                          CommittedDestination copiedDestination) {
        File parent = destination.getAbsoluteFile().getParentFile();
        File transaction = new File(parent, ".rwtransfer_" + UUID.randomUUID());
        File staged = new File(transaction, "replacement");
        File previous = new File(transaction, "previous");
        boolean ownsTransaction = false;
        boolean backedUp = false;
        boolean committed = false;
        DestinationSnapshot destinationSnapshot = null;
        File transactionLocation = null;
        FileTreeWalk.Entry transactionEntry = null;
        try {
            destinationParent.recheck();
            destinationSnapshot = DestinationSnapshot.capture(destination, progress);
            if (progress != null && !progress.checkpoint()) return false;
            if (sourceCheck != null) sourceCheck.recheck();
            destinationParent.recheck();
            if (!canTransfer(source, destination, overwrite)) return false;
            if (!transaction.mkdir()) return false;
            ownsTransaction = true;
            transactionLocation = transaction.getCanonicalFile();
            transactionEntry = captureEntry(transaction);
            boolean copied = copyTree(source, staged, progress, tracker, copiedSources);
            if (!copied || (progress != null && !progress.checkpoint())) return false;
            if (copiedDestination != null) copiedDestination.capture(staged, progress);
            if (sourceCheck != null) sourceCheck.recheck();
            destinationParent.recheck();
            if (!canTransfer(source, destination, overwrite)) return false;
            destinationSnapshot.recheck(progress);
            destinationParent.recheck();
            transactionEntry.recheck();

            // Only rename after all streams have closed and staging succeeded.
            // Cancellation is intentionally not observed between these renames:
            // complete the short commit/rollback sequence first.
            if (FileTreeWalk.existsNoFollow(destination)) {
                if (!overwrite || !destination.renameTo(previous)) return false;
                backedUp = true;
            }
            if (FileTreeWalk.existsNoFollow(destination) || !staged.renameTo(destination)) return false;
            committed = true;
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        } finally {
            if (ownsTransaction && ownsTransferDirectory(destinationParent, transactionEntry)) {
                if (backedUp && !committed) {
                    // If rollback itself is denied, preserve previous in the
                    // transaction directory for recovery; never clean it away.
                    try {
                        if (!FileTreeWalk.existsNoFollow(destination)) previous.renameTo(destination);
                    } catch (IOException | SecurityException ignored) { }
                }
                delete(staged);
                if (committed && backedUp && destinationSnapshot != null) {
                    destinationSnapshot.deleteBackup(previous, transactionLocation);
                }
                // Nonrecursive: a failed rollback/cleanup retains the backup.
                try { transaction.delete(); } catch (SecurityException ignored) { }
            }
        }
    }

    private static FileTreeWalk.Entry captureEntry(File file) throws IOException {
        FileTreeWalk.Entry[] captured = {null};
        FileTreeWalk.walk(file, () -> true, entry -> {
            captured[0] = entry;
            return false;
        });
        if (captured[0] == null) throw new IOException("Cannot inspect transfer path");
        return captured[0];
    }

    private static boolean ownsTransferDirectory(DestinationParent parent, FileTreeWalk.Entry transaction) {
        try {
            if (transaction == null) return false;
            parent.recheck();
            transaction.recheck();
            return transaction.kind == FileTreeWalk.Kind.DIRECTORY;
        } catch (IOException | SecurityException changed) {
            return false;
        }
    }

    private static final class DestinationParent {
        private final File path;
        private final File canonical;
        private final FileTreeWalk.Entry entry;

        private DestinationParent(File path, File canonical, FileTreeWalk.Entry entry) {
            this.path = path;
            this.canonical = canonical;
            this.entry = entry;
        }

        static DestinationParent capture(File destination) throws IOException {
            File path = destination.getAbsoluteFile().getParentFile();
            if (path == null) throw new IOException("Destination has no parent");
            File canonical = path.getCanonicalFile();
            FileTreeWalk.Entry entry = captureEntry(canonical);
            if (entry.kind != FileTreeWalk.Kind.DIRECTORY) throw new IOException("Destination parent unavailable");
            DestinationParent parent = new DestinationParent(path, canonical, entry);
            parent.recheck();
            return parent;
        }

        void recheck() throws IOException {
            if (!canonical.equals(path.getCanonicalFile())) throw new IOException("Destination parent changed");
            entry.recheck();
        }
    }

    private static final class DestinationSnapshot {
        private final List<DestinationEntry> entries = new ArrayList<>();

        static DestinationSnapshot capture(File destination, FileOperationProgress progress) throws IOException {
            DestinationSnapshot snapshot = new DestinationSnapshot();
            boolean complete = FileTreeWalk.walk(destination,
                    () -> !Thread.currentThread().isInterrupted()
                            && (progress == null || progress.checkpoint()), entry -> {
                        snapshot.entries.add(new DestinationEntry(entry));
                        return true;
                    });
            if (!complete || snapshot.entries.isEmpty()) throw new IOException("Cannot inspect overwrite destination");
            snapshot.recheck(progress);
            return snapshot;
        }

        void recheck(FileOperationProgress progress) throws IOException {
            for (DestinationEntry entry : entries) {
                if (Thread.currentThread().isInterrupted()
                        || (progress != null && !progress.checkpoint())) {
                    throw new java.io.InterruptedIOException("Overwrite cancelled");
                }
                entry.recheck();
            }
        }

        void deleteBackup(File root, File transactionLocation) {
            try {
                if (transactionLocation == null) return;
                File expectedRoot = new File(transactionLocation, root.getName());
                for (DestinationEntry entry : entries) entry.recheckAt(root, expectedRoot, true);
                for (int i = entries.size() - 1; i >= 0; i--) {
                    // New children never become cleanup targets. A changed backup
                    // stays in the transaction directory for recovery.
                    entries.get(0).recheckAt(root, expectedRoot, false);
                    DestinationEntry entry = entries.get(i);
                    entry.recheckAt(root, expectedRoot, false);
                    if (!entry.relocated(root).delete()) return;
                }
            } catch (IOException | SecurityException changed) {
                // The replacement is committed; preserve the uncertain backup.
            }
        }
    }

    private static final class CommittedDestination {
        private final File root;
        private final File expectedRoot;
        private final DestinationParent parent;
        private final Map<String, DestinationEntry> entries = new HashMap<>();
        private DestinationSnapshot snapshot;

        CommittedDestination(File root, DestinationParent parent) {
            this.root = root;
            this.parent = parent;
            expectedRoot = new File(parent.canonical, root.getName());
        }

        void capture(File staged, FileOperationProgress progress) throws IOException {
            snapshot = DestinationSnapshot.capture(staged, progress);
            for (DestinationEntry entry : snapshot.entries) entries.put(entry.entry.relative, entry);
        }

        void recheckAll(FileOperationProgress progress) throws IOException {
            if (snapshot == null) throw new IOException("Copied destination was not captured");
            parent.recheck();
            for (DestinationEntry entry : snapshot.entries) {
                if (progress != null && !progress.checkpoint()) {
                    throw new java.io.InterruptedIOException("Move cancelled");
                }
                parent.recheck();
                entry.recheckAt(root, expectedRoot, true);
            }
            parent.recheck();
        }

        void recheck(String relative) throws IOException {
            DestinationEntry rootEntry = entries.get("");
            DestinationEntry entry = entries.get(relative);
            if (rootEntry == null || entry == null) throw new IOException("Copied destination is incomplete");
            parent.recheck();
            rootEntry.recheckAt(root, expectedRoot, false);
            entry.recheckAt(root, expectedRoot, true);
            parent.recheck();
        }
    }

    private static final class DestinationEntry {
        private final FileTreeWalk.Entry entry;
        private final FileMutationGuard fileGuard;
        private final String[] children;

        DestinationEntry(FileTreeWalk.Entry entry) throws IOException {
            this.entry = entry;
            if (entry.kind != FileTreeWalk.Kind.MISSING && entry.identity == null) {
                throw new IOException("Cannot identify overwrite destination");
            }
            fileGuard = entry.kind == FileTreeWalk.Kind.FILE
                    ? FileMutationGuard.capture(entry.file) : null;
            children = entry.kind == FileTreeWalk.Kind.DIRECTORY ? sortedChildren(entry.file) : null;
            recheck();
        }

        void recheck() throws IOException {
            entry.recheck();
            if (fileGuard != null) fileGuard.recheck();
            if (children != null && !Arrays.equals(children, sortedChildren(entry.file))) {
                throw new IOException("Overwrite destination children changed");
            }
            entry.recheck();
        }

        File relocated(File root) {
            return entry.relative.isEmpty() ? root : new File(root, entry.relative);
        }

        void recheckAt(File root, File expectedRoot, boolean checkChildren) throws IOException {
            File candidate = relocated(root);
            File expectedParent = relocated(expectedRoot).getAbsoluteFile().getParentFile();
            checkParent(candidate, expectedParent);
            FileTreeWalk.Entry[] current = {null};
            FileTreeWalk.walk(candidate, () -> true, item -> {
                current[0] = item;
                return false;
            });
            if (current[0] == null || current[0].kind != entry.kind
                    || !Objects.equals(current[0].identity, entry.identity)) {
                throw new IOException("Overwrite backup replaced");
            }
            if (fileGuard != null) {
                // Renaming the root changes its ctime; child file stamps do not change.
                fileGuard.recheckAt(candidate, entry.relative.isEmpty());
            }
            if (checkChildren && children != null
                    && !Arrays.equals(children, sortedChildren(candidate))) {
                throw new IOException("Overwrite backup children changed");
            }
            current[0].recheck();
            checkParent(candidate, expectedParent);
        }

        private static void checkParent(File candidate, File expectedParent) throws IOException {
            File parent = candidate.getAbsoluteFile().getParentFile();
            if (parent == null || !parent.getCanonicalFile().equals(expectedParent)) {
                throw new IOException("Overwrite backup parent changed");
            }
        }

        private static String[] sortedChildren(File directory) throws IOException {
            String[] names = directory.list();
            if (names == null) throw new IOException("Cannot list overwrite destination");
            Arrays.sort(names);
            return names;
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
                                    @Nullable FileTreeProgressTracker tracker,
                                    @Nullable List<CopiedSourceEntry> copiedSources) {
        // One scratch buffer per operation, shared sequentially by all its files.
        // Independent copy operations never share mutable buffers.
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try {
            return FileTreeWalk.walk(source, () -> progress == null || progress.checkpoint(), entry -> {
                entry.recheck();
                CopiedSourceEntry copiedEntry = new CopiedSourceEntry(entry);
                File output = entry.relative.isEmpty() ? destination : new File(destination, entry.relative);
                if (entry.kind == FileTreeWalk.Kind.DIRECTORY) {
                    if (tracker != null) tracker.onDirectory(entry.file);
                    if (!output.mkdir()) return false; // Every output belongs to the new staging tree.
                    if (copiedSources != null) copiedSources.add(copiedEntry);
                    return true;
                }
                if (entry.kind == FileTreeWalk.Kind.FILE) {
                    if (!copyRegularFile(copiedEntry, output, progress, tracker, buffer)) return false;
                    if (copiedSources != null) {
                        copiedEntry.recheck();
                        copiedSources.add(copiedEntry);
                    }
                    return true;
                }
                // Do not dereference links or treat devices/FIFOs as regular files.
                // Failure rolls back the staged copy, leaving the destination intact.
                return false;
            });
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean deleteCopiedSources(List<CopiedSourceEntry> copiedSources,
                                                CommittedDestination copiedDestination,
                                                FileOperationProgress progress) {
        try {
            copiedDestination.recheckAll(progress);
            Map<File, Integer> copiedChildCounts = new HashMap<>();
            for (CopiedSourceEntry copied : copiedSources) {
                File parent = copied.entry.file.getAbsoluteFile().getParentFile();
                copiedChildCounts.put(parent, copiedChildCounts.getOrDefault(parent, 0) + 1);
            }
            // Detect an already changed source before removing any copied leaf.
            for (CopiedSourceEntry copied : copiedSources) {
                if (progress != null && !progress.checkpoint()) return false;
                copied.recheck();
                if (copied.entry.kind == FileTreeWalk.Kind.DIRECTORY) {
                    String[] children = copied.entry.file.list();
                    int expected = copiedChildCounts.getOrDefault(copied.entry.file.getAbsoluteFile(), 0);
                    if (children == null || children.length != expected) return false;
                    copied.recheck();
                }
            }
            // Reverse preorder is children-first. Nonrecursive directory delete
            // fails if a new, uncopied child appeared; that child is never visited.
            for (int i = copiedSources.size() - 1; i >= 0; i--) {
                if (progress != null && !progress.checkpoint()) return false;
                CopiedSourceEntry copied = copiedSources.get(i);
                // A pause or volume detach can invalidate a previously committed copy.
                // Check only its matching output here; the full tree was checked above.
                copiedDestination.recheck(copied.entry.relative);
                copied.recheck();
                if (!copied.entry.file.delete()) return false;
            }
            return true;
        } catch (IOException | SecurityException changed) {
            // The destination is committed. Retain any remaining source entries
            // and report a partial move instead of deleting uncertain content.
            return false;
        }
    }

    private static final class CopiedSourceEntry {
        final FileTreeWalk.Entry entry;
        final FileMutationGuard mutationGuard;

        CopiedSourceEntry(FileTreeWalk.Entry entry) throws IOException {
            this.entry = entry;
            mutationGuard = entry.kind == FileTreeWalk.Kind.FILE ? FileMutationGuard.capture(entry.file) : null;
            recheck();
        }

        void recheck() throws IOException {
            entry.recheck();
            if (mutationGuard != null) mutationGuard.recheck();
            if (entry.kind == FileTreeWalk.Kind.FILE && entry.file.length() != entry.bytes) {
                throw new IOException("Copied source changed: " + entry.file);
            }
        }
    }

    private static boolean copyRegularFile(@NonNull CopiedSourceEntry copiedEntry,
                                           @NonNull File destination,
                                           @Nullable FileOperationProgress progress,
                                           @Nullable FileTreeProgressTracker tracker,
                                           @NonNull byte[] buffer) throws IOException {
        FileTreeWalk.Entry entry = copiedEntry.entry;
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
        copiedEntry.recheck();
        long expectedBytes = entry.bytes;
        long writtenBytes = 0L;
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            copiedEntry.mutationGuard.verifyOpened(in);
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress != null && !progress.checkpoint()) {
                    cancelled = true;
                    break;
                }
                if (read > expectedBytes - writtenBytes) {
                    throw new IOException("Source grew during copy");
                }
                out.write(buffer, 0, read);
                writtenBytes += read;
                if (progress != null) progress.addDoneBytes(read);
            }
            out.flush();
            if (progress != null && !progress.checkpoint()) cancelled = true;
            // Check after progress callbacks and after the final read: both the
            // opened descriptor and its name must still refer to the captured version.
            copiedEntry.mutationGuard.verifyOpened(in);
            copiedEntry.recheck();
            copied = !cancelled
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
