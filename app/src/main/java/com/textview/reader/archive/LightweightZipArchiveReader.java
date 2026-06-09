package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

final class LightweightZipArchiveReader {
    private static final int BUFFER_SIZE = 1024 * 64;
    private static final int MIN_PARALLEL_ENTRIES = 4;

    private LightweightZipArchiveReader() {}

    static boolean canHandleWithoutPassword(@NonNull File archive) {
        try {
            List<ZipEntryInfo> entries = readEntries(archive);
            for (ZipEntryInfo entry : entries) {
                if (!entry.directory && !isSupportedMethod(entry.method)) return false;
            }
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    @NonNull
    static List<ArchiveSupport.EntryInfo> listEntries(@NonNull File archive) throws IOException {
        List<ArchiveSupport.EntryInfo> result = new ArrayList<>();
        for (ZipEntryInfo entry : readEntries(archive)) {
            result.add(new ArchiveSupport.EntryInfo(entry.path, entry.directory, entry.size, entry.timeMillis));
        }
        return withSyntheticDirectories(result);
    }

    static boolean extractArchiveIntoDirectory(@NonNull File archive,
                                               @NonNull File targetDir) throws IOException {
        List<ZipEntryInfo> entries = readEntries(archive);
        boolean sawEntry = false;
        List<ZipEntryInfo> files = new ArrayList<>();
        for (ZipEntryInfo entry : entries) {
            File out = resolveOutput(targetDir, entry.path);
            if (out == null) return false;
            sawEntry = true;
            if (entry.directory || entry.path.endsWith("/")) {
                if (!out.exists() && !out.mkdirs()) return false;
            } else {
                if (!isSupportedMethod(entry.method)) return false;
                files.add(entry);
            }
        }
        if (files.isEmpty()) return sawEntry;
        if (files.size() < MIN_PARALLEL_ENTRIES) {
            try (ZipFile zip = new ZipFile(archive)) {
                for (ZipEntryInfo entry : files) {
                    if (!extractEntry(zip, entry, resolveOutput(targetDir, entry.path))) return false;
                }
            }
            return sawEntry;
        }
        return extractFilesInParallel(archive, targetDir, files) && sawEntry;
    }

    static boolean extractSingleEntry(@NonNull File archive,
                                      @NonNull String entryPath,
                                      @NonNull File outFile) throws IOException {
        String normalized = sanitizeEntryPath(entryPath);
        if (normalized == null || normalized.endsWith("/")) return false;
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(normalized);
            if (entry == null || entry.isDirectory()) return false;
            if (!isSupportedMethod(entry.getMethod())) return false;
            ZipEntryInfo info = fromZipEntry(entry);
            return extractEntry(zip, info, outFile);
        }
    }

    @NonNull
    private static List<ZipEntryInfo> readEntries(@NonNull File archive) throws IOException {
        List<ZipEntryInfo> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null) continue;
                ZipEntryInfo info = fromZipEntry(entry);
                if (info == null) continue;
                result.add(info);
            }
        } catch (ZipException e) {
            throw new IOException(e);
        }
        return result;
    }

    @Nullable
    private static ZipEntryInfo fromZipEntry(@NonNull ZipEntry entry) {
        String path = sanitizeEntryPath(entry.getName());
        if (path == null) return null;
        boolean directory = entry.isDirectory() || path.endsWith("/");
        return new ZipEntryInfo(
                path,
                directory,
                entry.getSize(),
                entry.getCompressedSize(),
                entry.getTime(),
                entry.getMethod());
    }

    private static boolean extractFilesInParallel(@NonNull File archive,
                                                  @NonNull File targetDir,
                                                  @NonNull List<ZipEntryInfo> files) throws IOException {
        int workers = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (ZipEntryInfo entry : files) {
                File out = resolveOutput(targetDir, entry.path);
                if (out == null) return false;
                Callable<Boolean> task = () -> {
                    try (ZipFile zip = new ZipFile(archive)) {
                        return extractEntry(zip, entry, out);
                    }
                };
                futures.add(executor.submit(task));
            }
            for (Future<Boolean> future : futures) {
                try {
                    if (!future.get()) return false;
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean extractEntry(@NonNull ZipFile zip,
                                        @NonNull ZipEntryInfo info,
                                        @Nullable File outFile) throws IOException {
        if (outFile == null) return false;
        ZipEntry entry = zip.getEntry(info.path);
        if (entry == null || entry.isDirectory()) return false;
        File parent = outFile.getParentFile();
        if (parent == null) return false;
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) return false;
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return true;
    }

    private static boolean isSupportedMethod(int method) {
        return method == ZipEntry.STORED || method == ZipEntry.DEFLATED;
    }

    @Nullable
    private static File resolveOutput(@NonNull File targetDir, @NonNull String entryPath) throws IOException {
        String path = sanitizeEntryPath(entryPath);
        if (path == null) return null;
        File out = new File(targetDir, path);
        return isSameOrDescendant(targetDir, out) ? out : null;
    }

    @Nullable
    private static String sanitizeEntryPath(String rawEntryName) {
        if (rawEntryName == null) return null;
        String entryName = rawEntryName.trim().replace('\\', '/');
        while (entryName.startsWith("./")) entryName = entryName.substring(2);
        while (entryName.contains("//")) entryName = entryName.replace("//", "/");
        if (entryName.length() == 0) return null;
        if (entryName.startsWith("/")
                || entryName.equals("..")
                || entryName.startsWith("../")
                || entryName.contains("/../")
                || entryName.endsWith("/..")
                || entryName.matches("^[A-Za-z]:.*")) {
            return null;
        }
        return entryName;
    }

    private static boolean isSameOrDescendant(@NonNull File ancestor, @NonNull File candidate) throws IOException {
        File ancestorCanonical = ancestor.getCanonicalFile();
        File current = candidate.getCanonicalFile();
        while (current != null) {
            if (ancestorCanonical.equals(current)) return true;
            current = current.getParentFile();
        }
        return false;
    }

    @NonNull
    private static List<ArchiveSupport.EntryInfo> withSyntheticDirectories(@NonNull List<ArchiveSupport.EntryInfo> entries) {
        Map<String, ArchiveSupport.EntryInfo> map = new LinkedHashMap<>();
        for (ArchiveSupport.EntryInfo entry : entries) {
            String path = entry.path;
            int slash = path.indexOf('/');
            while (slash >= 0) {
                String dir = path.substring(0, slash + 1);
                if (!map.containsKey(dir)) map.put(dir, new ArchiveSupport.EntryInfo(dir, true, -1L, 0L));
                slash = path.indexOf('/', slash + 1);
            }
            map.put(path, entry);
        }
        return new ArrayList<>(map.values());
    }

    private static final class ZipEntryInfo {
        final String path;
        final boolean directory;
        final long size;
        final long compressedSize;
        final long timeMillis;
        final int method;

        ZipEntryInfo(@NonNull String path,
                     boolean directory,
                     long size,
                     long compressedSize,
                     long timeMillis,
                     int method) {
            this.path = directory && !path.endsWith("/") ? path + "/" : path;
            this.directory = directory;
            this.size = size;
            this.compressedSize = compressedSize;
            this.timeMillis = timeMillis;
            this.method = method;
        }
    }
}
