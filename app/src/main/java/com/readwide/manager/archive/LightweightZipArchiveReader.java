package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
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
        checkCancelled();
        validateOutputTargets(targetDir, entries);
        boolean sawEntry = false;
        List<ZipEntryInfo> files = new ArrayList<>();
        for (ZipEntryInfo entry : entries) {
            checkCancelled();
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
        List<ZipEntryInfo> entries = readEntries(archive);
        try (ZipFile zip = new ZipFile(archive)) {
            for (ZipEntryInfo info : entries) {
                checkCancelled();
                if (!info.directory && info.path.equals(normalized)) {
                    if (!isSupportedMethod(info.method)) return false;
                    return extractEntry(zip, info, outFile);
                }
            }
        }
        return false;
    }

    @NonNull
    private static List<ZipEntryInfo> readEntries(@NonNull File archive) throws IOException {
        checkCancelled();
        List<ZipEntryInfo> result = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled();
                ZipEntry entry = entries.nextElement();
                if (entry == null) continue;
                ZipEntryInfo info = fromZipEntry(entry);
                if (info == null) throw new IOException("Unsafe ZIP entry path");
                String key = info.directory ? info.path.substring(0, info.path.length() - 1) : info.path;
                if (!paths.add(key)) throw new IOException("Conflicting ZIP entry paths");
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
                entry.getName(), entry.getCrc(), path,
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
        CompletionService<Void> completed = new ExecutorCompletionService<>(executor);
        List<Future<Void>> futures = new ArrayList<>(workers);
        AtomicInteger next = new AtomicInteger();
        IOException failure = null;
        boolean interrupted = false;
        try {
            // O(workers) tasks, one parsed ZIP handle per worker, not per entry.
            for (int worker = 0; worker < workers; worker++) {
                futures.add(completed.submit(() -> {
                    try (ZipFile zip = new ZipFile(archive)) {
                        for (;;) {
                            checkCancelled();
                            int index = next.getAndIncrement();
                            if (index >= files.size()) return null;
                            ZipEntryInfo entry = files.get(index);
                            if (!extractEntry(zip, entry, resolveOutput(targetDir, entry.path))) {
                                throw new IOException("ZIP entry extraction failed");
                            }
                        }
                    }
                }));
            }
            for (int worker = 0; worker < workers; worker++) completed.take().get();
        } catch (InterruptedException cancelled) {
            interrupted = true;
            failure = new InterruptedIOException("ZIP extraction cancelled");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            failure = cause instanceof IOException ? (IOException) cause : new IOException("ZIP worker failed", cause);
        } finally {
            for (Future<Void> future : futures) future.cancel(true);
            executor.shutdownNow();
            // Never return while another worker can still write/rollback output.
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) throw failure;
        checkCancelled();
        return true;
    }

    private static boolean extractEntry(@NonNull ZipFile zip,
                                        @NonNull ZipEntryInfo info,
                                        @Nullable File outFile) throws IOException {
        checkCancelled();
        if (outFile == null) return false;
        // The raw archive name is a lookup key, not the normalized output path.
        ZipEntry entry = zip.getEntry(info.rawName);
        if (entry == null || entry.isDirectory()) return false;
        if (info.size < 0 || info.crc < 0 || entry.getSize() != info.size
                || entry.getCrc() != info.crc || entry.getMethod() != info.method) {
            throw new IOException("ZIP entry metadata changed or is incomplete");
        }
        try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
            CRC32 crc = new CRC32();
            long written = 0;
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                 OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled();
                    if (read > info.size - written) throw new IOException("ZIP entry exceeds declared size");
                    out.write(buffer, 0, read);
                    crc.update(buffer, 0, read);
                    written += read;
                }
                if (written != info.size || crc.getValue() != info.crc) {
                    throw new IOException("ZIP entry size or CRC mismatch");
                }
                out.flush();
            }
            checkCancelled();
            guard.commit();
        }
        return true;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP extraction cancelled");
    }

    private static void validateOutputTargets(File targetDir, List<ZipEntryInfo> entries) throws IOException {
        Map<String, Boolean> targets = new LinkedHashMap<>();
        for (ZipEntryInfo entry : entries) {
            checkCancelled();
            File output = resolveOutput(targetDir, entry.path);
            if (output == null) throw new IOException("Unsafe ZIP output path");
            String canonical = output.getCanonicalPath();
            if (targets.put(canonical, entry.directory) != null) throw new IOException("Conflicting ZIP output paths");
        }
        for (String path : targets.keySet()) {
            for (File parent = new File(path).getParentFile(); parent != null; parent = parent.getParentFile()) {
                if (Boolean.FALSE.equals(targets.get(parent.getPath()))) {
                    throw new IOException("ZIP file conflicts with an output directory");
                }
            }
        }
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
        if (entryName.length() == 0 || entryName.indexOf('\0') >= 0) return null;
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
        final String rawName;
        final long crc;
        final String path;
        final boolean directory;
        final long size;
        final long compressedSize;
        final long timeMillis;
        final int method;

        ZipEntryInfo(@NonNull String rawName, long crc, @NonNull String path,
                     boolean directory,
                     long size,
                     long compressedSize,
                     long timeMillis,
                     int method) {
            this.rawName = rawName;
            this.crc = crc;
            this.path = directory && !path.endsWith("/") ? path + "/" : path;
            this.directory = directory;
            this.size = size;
            this.compressedSize = compressedSize;
            this.timeMillis = timeMillis;
            this.method = method;
        }
    }
}
