package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Per-target rollback for extraction. Existing outputs are moved to a reserved
 * sibling directory until commit. This is not a crash-consistent transaction,
 * nor does it serialize concurrent writers to the same destination.
 */
final class RarOutputFileGuard implements AutoCloseable {
    private final File outFile;
    @Nullable private final File backupFile;
    @Nullable private final File backupDirectory;
    private boolean committed;
    private boolean rollbackStarted;
    private boolean restored;
    private boolean cleanupCompleted;

    private RarOutputFileGuard(@NonNull File outFile, @Nullable File backupFile,
                               @Nullable File backupDirectory) {
        this.outFile = outFile;
        this.backupFile = backupFile;
        this.backupDirectory = backupDirectory;
    }

    @NonNull
    static RarOutputFileGuard forTarget(@NonNull File outFile) throws IOException {
        ensureParentDirectory(outFile);
        File backup = null, directory = null;
        if (outFile.exists()) {
            if (!outFile.isFile()) throw new IOException("Extraction output target is not a file: " + outFile.getName());
            // mkdir atomically reserves a namespace; exists()+rename to an
            // unreserved random filename can overwrite another caller's file.
            // A fixed short name also works for nearly NAME_MAX-sized targets.
            directory = reserveBackupDirectory(outFile);
            backup = new File(directory, "original");
            try {
                if (!outFile.renameTo(backup)) {
                    throw new IOException("Failed to protect existing extraction output: " + outFile.getName());
                }
            } catch (IOException | SecurityException error) {
                IOException failure = error instanceof IOException ? (IOException) error
                        : new IOException("Cannot protect extraction output", error);
                try { deletePartial(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        return new RarOutputFileGuard(outFile, backup, directory);
    }

    void commit() {
        if (cleanupCompleted || rollbackStarted) throw new IllegalStateException("Extraction guard already closing or closed");
        committed = true;
    }

    @Override
    public void close() throws IOException {
        try { closeChecked(); }
        catch (SecurityException denied) { throw new IOException("Extraction rollback/cleanup access denied", denied); }
    }

    private void closeChecked() throws IOException {
        if (cleanupCompleted) return;
        if (committed) {
            if (backupFile != null) deletePartial(backupFile);
        } else if (!restored) {
            rollbackStarted = true;
            // Missing recovery data is an error. Do not delete the current file
            // and falsely report restoration when the backup disappeared.
            if (backupFile != null && !backupFile.isFile()) {
                throw new IOException("Extraction backup is unavailable: " + backupFile);
            }
            deletePartial(outFile);
            if (backupFile != null && !backupFile.renameTo(outFile)) {
                throw new IOException("Failed to restore extraction output; original retained at: " + backupFile);
            }
            // Directory deletion can fail after restoration. Retrying close
            // must only retry cleanup, NEVER delete the restored original.
            restored = true;
        }
        if (backupDirectory != null) deletePartial(backupDirectory);
        cleanupCompleted = true;
    }

    static void deletePartial(@NonNull File outFile) throws IOException {
        try {
            if (!outFile.exists()) return;
            if (outFile.delete()) return;
            throw new IOException("Failed to remove extraction temporary output: " + outFile);
        } catch (SecurityException denied) {
            throw new IOException("Cannot remove extraction temporary output: " + outFile, denied);
        }
    }

    static void ensureParentDirectory(@NonNull File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create output directory");
        }
    }

    @NonNull
    private static File reserveBackupDirectory(@NonNull File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        for (int attempt = 0; attempt < 32; attempt++) {
            File candidate = new File(parent, ".readwide-backup-" + UUID.randomUUID());
            if (candidate.mkdir()) return candidate;
            if (!candidate.exists()) throw new IOException("Cannot reserve extraction backup directory");
        }
        throw new IOException("Cannot allocate extraction backup directory");
    }
}
