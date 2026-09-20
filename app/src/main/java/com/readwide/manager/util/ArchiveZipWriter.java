package com.readwide.manager.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates ordinary ZIP files using the shared iterative, no-follow filesystem walk. */
public final class ArchiveZipWriter {
    private ArchiveZipWriter() { }

    public static boolean create(List<File> sources, File output, FileOperationProgress progress) {
        if (sources == null || sources.isEmpty() || output == null) return false;
        boolean reserved = false;
        boolean complete = false;
        try {
            checkpoint(progress);
            File parent = output.getAbsoluteFile().getParentFile();
            if (parent == null || !parent.isDirectory() || !parent.canWrite()) return false;
            if (FileTreeWalk.existsNoFollow(output)) return false;
            String outputPath = output.getCanonicalPath();
            Set<String> roots = new HashSet<>();
            for (File source : sources) {
                if (source == null || !source.canRead()) return false;
                FileTreeWalk.Kind kind = FileTreeWalk.kind(source);
                if (kind != FileTreeWalk.Kind.FILE && kind != FileTreeWalk.Kind.DIRECTORY) return false;
                String sourcePath = source.getCanonicalPath();
                // Reject before opening the destination: otherwise walking the selected
                // directory can read the ZIP that is currently being written.
                if (outputPath.equals(sourcePath) || (kind == FileTreeWalk.Kind.DIRECTORY
                        && outputPath.startsWith(sourcePath.endsWith(File.separator)
                        ? sourcePath : sourcePath + File.separator))) return false;
                String rootName = safeName(source.getName(), kind == FileTreeWalk.Kind.DIRECTORY);
                if (!roots.add(rootName.endsWith("/") ? rootName.substring(0, rootName.length() - 1) : rootName)) return false;
            }
            FileTreeProgressTracker tracker = progress == null ? null
                    : FileTreeProgressTracker.create(progress, sources);
            if (tracker != null) {
                if (!tracker.isReady()) return false;
                progress.setTotalBytes(tracker.totalBytes());
            }
            // Atomically reserve the destination rather than overwriting a competing
            // writer after an exists() check. No deletion unless this call reserved it.
            if (!output.createNewFile()) return false;
            reserved = true;
            Set<String> names = new HashSet<>();
            byte[] buffer = new byte[64 * 1024];
            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(output)))) {
                for (File source : sources) {
                    final String rootName = source.getName();
                    boolean walked = FileTreeWalk.walk(source,
                            () -> !Thread.currentThread().isInterrupted()
                                    && (progress == null || progress.checkpoint()), entry -> {
                        checkpoint(progress);
                        if (entry.kind != FileTreeWalk.Kind.FILE
                                && entry.kind != FileTreeWalk.Kind.DIRECTORY) {
                            throw new IOException("ZIP creation does not follow links or special files");
                        }
                        entry.recheck();
                        String rawName = rootName + (entry.relative.isEmpty() ? "" : "/" + entry.relative);
                        String name = safeName(rawName, entry.kind == FileTreeWalk.Kind.DIRECTORY);
                        if (!names.add(name)) throw new IOException("Duplicate ZIP entry: " + name);
                        ZipEntry zipEntry = new ZipEntry(name);
                        zipEntry.setTime(Math.max(0L, entry.file.lastModified()));
                        zip.putNextEntry(zipEntry);
                        if (entry.kind == FileTreeWalk.Kind.DIRECTORY) {
                            if (tracker != null) tracker.onDirectory(entry.file);
                        } else {
                            if (tracker != null) tracker.onFile(entry.file);
                            else if (progress != null) progress.setDetail(entry.file.getName());
                            long remaining = entry.bytes;
                            try (InputStream input = new BufferedInputStream(new FileInputStream(entry.file))) {
                                int count;
                                while ((count = input.read(buffer)) != -1) {
                                    checkpoint(progress);
                                    if (count <= 0 || count > buffer.length || count > remaining) {
                                        throw new IOException("Source changed or made invalid ZIP read progress");
                                    }
                                    zip.write(buffer, 0, count);
                                    remaining -= count;
                                    if (progress != null) progress.addDoneBytes(count);
                                }
                            }
                            if (remaining != 0L) throw new IOException("ZIP source truncated during creation");
                            entry.recheck();
                        }
                        zip.closeEntry();
                        return true;
                    });
                    if (!walked) throw new IOException("ZIP creation cancelled or source unreadable");
                }
                checkpoint(progress);
            }
            // Stream close writes the central directory. Success must be decided
            // AFTER it succeeds, so a finalization error removes the partial ZIP.
            complete = true;
            return true;
        } catch (IOException | SecurityException failure) {
            return false;
        } finally {
            if (reserved && !complete) {
                try { output.delete(); } catch (SecurityException ignored) { }
            }
        }
    }

    private static void checkpoint(FileOperationProgress progress) throws IOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new IOException("ZIP creation cancelled");
        }
    }

    private static String safeName(String raw, boolean directory) throws IOException {
        String name = raw.replace(File.separatorChar, '/');
        // Backslash is ambiguous across ZIP readers. Do not reinterpret an actual
        // POSIX filename as a directory, or silently skip it.
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Invalid ZIP source name");
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Invalid ZIP source path");
            }
        }
        if (directory) name += "/";
        if (name.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new IOException("ZIP entry name exceeds its encoded format field");
        }
        return name;
    }
}
