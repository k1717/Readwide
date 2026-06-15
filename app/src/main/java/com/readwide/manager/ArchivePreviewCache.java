package com.readwide.manager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Builds stable-but-content-aware cache paths for archive preview extraction.
 *
 * <p>The cache namespace includes the archive canonical path, length, and modified time so a
 * replaced archive at the same path does not normally reuse stale extracted preview files. Each
 * entry file name also includes a hash of the full internal entry path, avoiding collisions such as
 * {@code a/b.png} and {@code a_b.png} after filesystem-name sanitization.</p>
 */
final class ArchivePreviewCache {
    private static final String ROOT_NAME = "archive_preview";
    private static final String SENSITIVE_ROOT_NAME = "archive_preview_sensitive";
    private static final int MAX_SAFE_BASE_CHARS = 96;
    private static final int MAX_ARCHIVE_CACHE_DIRS = 32;
    private static final long MAX_ARCHIVE_CACHE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ARCHIVE_CACHE_AGE_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final int MAX_SENSITIVE_ARCHIVE_CACHE_DIRS = 8;
    private static final long MAX_SENSITIVE_ARCHIVE_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SENSITIVE_ARCHIVE_CACHE_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final long SAFE_PRUNE_THROTTLE_MS = 6L * 60L * 60L * 1000L;
    private static final long SENSITIVE_PRUNE_THROTTLE_MS = 30L * 60L * 1000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Object PRUNE_LOCK = new Object();
    private static long lastSafePruneAtMs = 0L;
    private static long lastSensitivePruneAtMs = 0L;

    private ArchivePreviewCache() {}

    @NonNull
    static File outputFileForEntry(@NonNull Context context,
                                   @NonNull File archiveFile,
                                   @NonNull String entryPath) {
        return outputFileForEntry(context, archiveFile, entryPath, false);
    }

    @NonNull
    static File outputFileForEntry(@NonNull Context context,
                                   @NonNull File archiveFile,
                                   @NonNull String entryPath,
                                   boolean sensitive) {
        File root = new File(context.getCacheDir(), sensitive ? SENSITIVE_ROOT_NAME : ROOT_NAME);
        File archiveDir = new File(root, archiveFingerprint(archiveFile));
        // Touch the archive cache directory when it is used so pruning prefers
        // old/inactive archives. This is intentionally best-effort.
        try {
            if (!archiveDir.exists()) archiveDir.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            archiveDir.setLastModified(System.currentTimeMillis());
        } catch (SecurityException ignored) {
        }
        return new File(archiveDir, cacheFileNameForEntry(entryPath));
    }


    @NonNull
    static String cacheFileNameForEntry(@NonNull String entryPath) {
        String baseName = safeBaseName(entryPath);
        String entryHash = sha256Hex(normalizeEntryPath(entryPath));
        return entryHash.substring(0, 16) + "_" + baseName;
    }

    static void pruneOtherArchiveCaches(@NonNull Context context, @NonNull File activeArchiveFile) {
        String active = archiveFingerprint(activeArchiveFile);
        long now = System.currentTimeMillis();
        boolean pruneSafe;
        boolean pruneSensitive;
        synchronized (PRUNE_LOCK) {
            pruneSafe = now - lastSafePruneAtMs > SAFE_PRUNE_THROTTLE_MS;
            pruneSensitive = now - lastSensitivePruneAtMs > SENSITIVE_PRUNE_THROTTLE_MS;
            if (pruneSafe) lastSafePruneAtMs = now;
            if (pruneSensitive) lastSensitivePruneAtMs = now;
        }
        if (pruneSafe) {
            pruneRoot(new File(context.getCacheDir(), ROOT_NAME), active,
                    MAX_ARCHIVE_CACHE_DIRS,
                    MAX_ARCHIVE_CACHE_BYTES,
                    MAX_ARCHIVE_CACHE_AGE_MS);
        }
        if (pruneSensitive) {
            pruneRoot(new File(context.getCacheDir(), SENSITIVE_ROOT_NAME), active,
                    MAX_SENSITIVE_ARCHIVE_CACHE_DIRS,
                    MAX_SENSITIVE_ARCHIVE_CACHE_BYTES,
                    MAX_SENSITIVE_ARCHIVE_CACHE_AGE_MS);
        }
    }

    private static void pruneRoot(@NonNull File root,
                                  @NonNull String activeFingerprint,
                                  int maxDirs,
                                  long maxBytes,
                                  long maxAgeMs) {
        File[] children = root.listFiles();
        if (children == null || children.length == 0) return;
        Arrays.sort(children, Comparator.comparingLong(File::lastModified));

        long now = System.currentTimeMillis();
        int dirCount = 0;
        long totalBytes = 0L;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            dirCount++;
            long childSize = sizeOf(child);
            totalBytes = safeAdd(totalBytes, childSize);
            if (!activeFingerprint.equals(child.getName()) && now - child.lastModified() > maxAgeMs) {
                deleteRecursively(child);
                dirCount--;
                totalBytes = Math.max(0L, totalBytes - childSize);
            }
        }

        if (dirCount <= maxDirs && totalBytes <= maxBytes) return;
        File[] refreshed = root.listFiles();
        if (refreshed == null || refreshed.length == 0) return;
        Arrays.sort(refreshed, Comparator.comparingLong(File::lastModified));
        for (File child : refreshed) {
            if (dirCount <= maxDirs && totalBytes <= maxBytes) break;
            if (child == null || !child.isDirectory()) continue;
            if (activeFingerprint.equals(child.getName())) continue;
            long childSize = sizeOf(child);
            deleteRecursively(child);
            dirCount--;
            totalBytes = Math.max(0L, totalBytes - childSize);
        }
    }

    @NonNull
    static String archiveFingerprint(@NonNull File archiveFile) {
        String canonical;
        try {
            canonical = archiveFile.getCanonicalPath();
        } catch (IOException | SecurityException ignored) {
            canonical = archiveFile.getAbsolutePath();
        }
        String material = canonical + "\n" + archiveFile.length() + "\n" + archiveFile.lastModified();
        return sha256Hex(material).substring(0, 24);
    }

    @NonNull
    static String normalizeEntryPath(@NonNull String entryPath) {
        String normalized = entryPath.replace('\\', '/').trim();
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        return normalized;
    }

    @NonNull
    private static String safeBaseName(@NonNull String entryPath) {
        String normalized = normalizeEntryPath(entryPath);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.length() == 0 || ".".equals(name) || "..".equals(name)) name = "archive_entry";
        if (name.length() > MAX_SAFE_BASE_CHARS) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                String ext = name.substring(dot);
                int stemMax = Math.max(16, MAX_SAFE_BASE_CHARS - ext.length());
                name = name.substring(0, Math.min(stemMax, dot)) + ext;
            } else {
                name = name.substring(0, MAX_SAFE_BASE_CHARS);
            }
        }
        return name;
    }

    @NonNull
    private static String sha256Hex(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int byteValue = bytes[i] & 0xff;
                out[i * 2] = HEX[byteValue >>> 4];
                out[i * 2 + 1] = HEX[byteValue & 0x0f];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by Android", e);
        }
    }

    private static long sizeOf(@Nullable File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());
        File[] children = file.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) total = safeAdd(total, sizeOf(child));
        return total;
    }

    private static long safeAdd(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE) return Long.MAX_VALUE;
        if (right < 0L || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        try { file.delete(); } catch (SecurityException ignored) {}
    }
}
