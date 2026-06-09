package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Protects an extraction target while a backend is writing it.
 *
 * <p>New outputs are deleted unless committed. Existing outputs are moved aside before the
 * extraction starts and restored if the extraction does not commit. This prevents failed RAR
 * fallback attempts from deleting or leaving a truncated file that existed before extraction.</p>
 */
final class RarOutputFileGuard implements AutoCloseable {
    private final File outFile;
    @Nullable
    private final File backupFile;
    private boolean committed;

    private RarOutputFileGuard(@NonNull File outFile, @Nullable File backupFile) {
        this.outFile = outFile;
        this.backupFile = backupFile;
    }

    @NonNull
    static RarOutputFileGuard forTarget(@NonNull File outFile) throws IOException {
        ensureParentDirectory(outFile);
        File backup = null;
        if (outFile.exists()) {
            if (outFile.isDirectory()) {
                throw new IOException("Extraction output target is a directory: " + outFile.getName());
            }
            backup = uniqueBackupFile(outFile);
            if (!outFile.renameTo(backup)) {
                throw new IOException("Failed to protect existing extraction output: " + outFile.getName());
            }
        }
        return new RarOutputFileGuard(outFile, backup);
    }

    void commit() {
        committed = true;
    }

    @Override
    public void close() throws IOException {
        if (committed) {
            deleteBackupQuietly();
            return;
        }
        deletePartial(outFile);
        restoreBackup();
    }

    static void deletePartial(@NonNull File outFile) throws IOException {
        if (!outFile.exists()) return;
        if (outFile.delete()) return;
        throw new IOException("Failed to remove partial extraction output: " + outFile.getName());
    }

    static void ensureParentDirectory(@NonNull File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create output directory");
    }

    @NonNull
    private static File uniqueBackupFile(@NonNull File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent == null) throw new IOException("Output file has no parent");
        String name = outFile.getName();
        for (int i = 0; i < 1000; i++) {
            File candidate = new File(parent, name + ".rar-extract-backup-" + System.nanoTime() + '-' + i);
            if (!candidate.exists()) return candidate;
        }
        throw new IOException("Cannot allocate extraction backup file: " + name);
    }

    private void restoreBackup() throws IOException {
        if (backupFile == null || !backupFile.exists()) return;
        if (outFile.exists()) deletePartial(outFile);
        if (!backupFile.renameTo(outFile)) {
            throw new IOException("Failed to restore previous extraction output: " + outFile.getName());
        }
    }

    private void deleteBackupQuietly() {
        if (backupFile == null || !backupFile.exists()) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            backupFile.delete();
        } catch (SecurityException ignored) {
        }
    }
}
