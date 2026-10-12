package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Read-only destination validation; not a lock or a whole-archive transaction. */
final class ArchiveOutputPreflight {
    private final File root;
    private final Map<File, Boolean> kinds = new HashMap<>();

    ArchiveOutputPreflight(File directory) throws IOException {
        root = directory.getCanonicalFile();
        checkKind(root, true);
    }

    File add(String path, boolean directory) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Archive extraction cancelled");
        File output = ArchiveSupport.resolveArchiveEntryOutput(root, path);
        if (output == null) throw new IOException("Invalid archive output path");
        output = output.getCanonicalFile();
        checkKind(output, directory);
        for (File parent = output.equals(root) ? null : output.getParentFile();
                parent != null && !parent.equals(root); parent = parent.getParentFile()) {
            checkKind(parent, true);
        }
        return output;
    }

    private void checkKind(File output, boolean directory) throws IOException {
        Boolean previous = kinds.putIfAbsent(output, directory);
        if ((previous != null && previous != directory)
                || (output.exists() && (directory ? !output.isDirectory() : !output.isFile()))) {
            throw new IOException("Archive file/directory output collision: " + output.getName());
        }
    }

    static Map<RarArchiveReader.RarEntry, File> rar(File directory,
            List<RarArchiveReader.RarEntry> entries) throws IOException {
        ArchiveOutputPreflight plan = new ArchiveOutputPreflight(directory);
        Map<RarArchiveReader.RarEntry, File> outputs = new IdentityHashMap<>();
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null && !entry.splitBefore) {
                outputs.put(entry, plan.add(entry.path, entry.directory || entry.path.endsWith("/")));
            }
        }
        return outputs;
    }
}
