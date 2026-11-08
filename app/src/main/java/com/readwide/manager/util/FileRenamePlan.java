package com.readwide.manager.util;

import java.io.File;
import java.io.IOException;

/** Binds a rename dialog to the selected entry while its name is being edited. */
public final class FileRenamePlan {
    private final FileTreeWalk.Entry entry;
    private final FileMutationGuard regularFile;

    private FileRenamePlan(FileTreeWalk.Entry entry) throws IOException {
        if (entry.identity == null || (entry.kind != FileTreeWalk.Kind.FILE
                && entry.kind != FileTreeWalk.Kind.DIRECTORY && entry.kind != FileTreeWalk.Kind.LINK)) {
            throw new IOException("Cannot identify rename source");
        }
        this.entry = entry;
        regularFile = entry.kind == FileTreeWalk.Kind.FILE ? FileMutationGuard.capture(entry.file) : null;
        entry.recheck();
    }

    public static FileRenamePlan capture(File source) throws IOException {
        FileRenamePlan[] result = new FileRenamePlan[1];
        FileTreeWalk.walk(source, () -> !Thread.currentThread().isInterrupted(), entry -> {
            result[0] = new FileRenamePlan(entry);
            return false;
        });
        if (result[0] == null) throw new IOException("Rename capture interrupted");
        return result[0];
    }

    public boolean isDirectory() { return entry.kind == FileTreeWalk.Kind.DIRECTORY; }

    public boolean isUnchanged() {
        try {
            entry.recheck();
            if (regularFile != null) regularFile.recheck();
            return !Thread.currentThread().isInterrupted();
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    public boolean rename(String newName) {
        return isUnchanged() && FileSystemOps.renameInPlace(entry.file, newName);
    }
}
