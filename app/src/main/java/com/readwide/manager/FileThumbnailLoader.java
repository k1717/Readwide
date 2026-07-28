package com.readwide.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.util.FileThumbnailMath;
import com.readwide.manager.util.NaturalSort;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bounded background thumbnail decoding for the file browser.
 *
 * <p>This class never prompts for passwords and never performs network I/O.
 * Unsupported, encrypted, damaged, or oversized sources simply return
 * {@code null}, leaving the normal type icon in the row.</p>
 */
public final class FileThumbnailLoader {
    private static final long MAX_ARCHIVE_COVER_ENTRY_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_DISK_CACHE_FILES = 1600;
    private static final int DISK_CACHE_TRIM_INTERVAL = 32;
    private static final int MAX_COVER_CANDIDATES = 8;
    private static final String DISK_CACHE_DIRECTORY = "file_thumbnails_v1";
    private static final String DISK_CACHE_FORMAT = "readwide-thumbnail-v2";
    private static final Semaphore GLOBAL_DECODE_SLOTS = new Semaphore(2, true);
    private static final Object DISK_CACHE_TRIM_LOCK = new Object();
    private static final ConcurrentHashMap<String, KeyLock> KEY_LOCKS =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean INITIAL_CACHE_TRIM_DONE = new AtomicBoolean();
    private static final AtomicInteger CACHE_WRITES = new AtomicInteger();

    private FileThumbnailLoader() {}

    @Nullable
    public static Bitmap decode(@NonNull Context context,
                                @NonNull File file,
                                boolean directory,
                                boolean showHiddenFiles,
                                int maxSidePx) {
        if (maxSidePx <= 0) return null;
        boolean acquired = false;
        try {
            GLOBAL_DECODE_SLOTS.acquire();
            acquired = true;
            if (directory) {
                for (File source : directThumbnailSources(file, showHiddenFiles)) {
                    Bitmap decoded = decodeCachedSource(context, source, maxSidePx);
                    if (decoded != null && !decoded.isRecycled()) return decoded;
                }
                return null;
            }
            return decodeCachedSource(context, file, maxSidePx);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError ignored) {
            // A browser thumbnail is optional. Keep the file-type icon when a
            // platform decoder/backend is absent or the source is malformed.
        } catch (OutOfMemoryError ignored) {
            // Never let a decorative preview terminate the file browser.
        } finally {
            if (acquired) GLOBAL_DECODE_SLOTS.release();
        }
        return null;
    }

    /**
     * Reuses an app-private PNG cache across adapter instances and process
     * restarts. The key includes source identity, size, modification time,
     * requested decode size, and cache format version, so replacing a source
     * naturally creates a new entry without relying on UI-side invalidation.
     */
    @Nullable
    private static Bitmap decodeCachedSource(@NonNull Context context,
                                             @NonNull File source,
                                             int maxSidePx) {
        if (!source.isFile()) return null;
        SourceStamp sourceStamp = SourceStamp.capture(source);
        if (!sourceStamp.regularFile) return null;
        String key = diskCacheKey(sourceStamp, maxSidePx);
        KeyLock keyLock = KEY_LOCKS.compute(key, (ignored, current) -> {
            KeyLock resolved = current != null ? current : new KeyLock();
            resolved.users++;
            return resolved;
        });
        try {
            synchronized (keyLock.monitor) {
                File cacheDirectory = diskCacheDirectory(context);
                File cachedFile = cacheDirectory != null
                        ? new File(cacheDirectory, key + ".png")
                        : null;
                Bitmap cached = readDiskBitmap(cachedFile);
                if (cached != null) {
                    if (sourceStamp.matches(source)) return cached;
                    cached.recycle();
                    return null;
                }

                Bitmap decoded = decodeSource(context, source, maxSidePx);
                // Do not cache or display a bitmap decoded while its source was
                // still being copied/replaced. Its cache key describes the old
                // file state and a partial decode can otherwise look like a
                // persistent, randomly changing cover.
                if (decoded != null && !sourceStamp.matches(source)) {
                    if (!decoded.isRecycled()) decoded.recycle();
                    return null;
                }
                if (decoded != null && !decoded.isRecycled() && cachedFile != null) {
                    writeDiskBitmap(cacheDirectory, cachedFile, decoded);
                }
                return decoded;
            }
        } finally {
            KEY_LOCKS.computeIfPresent(key, (ignored, current) -> {
                if (current != keyLock) return current;
                current.users--;
                return current.users <= 0 ? null : current;
            });
        }
    }

    private static final class KeyLock {
        final Object monitor = new Object();
        int users;
    }

    private static final class SourceStamp {
        @NonNull final String path;
        final long length;
        final long lastModified;
        final boolean regularFile;

        private SourceStamp(@NonNull String path,
                            long length,
                            long lastModified,
                            boolean regularFile) {
            this.path = path;
            this.length = length;
            this.lastModified = lastModified;
            this.regularFile = regularFile;
        }

        @NonNull
        static SourceStamp capture(@NonNull File source) {
            String path;
            try {
                path = source.getCanonicalPath();
            } catch (Exception ignored) {
                path = source.getAbsolutePath();
            }
            return new SourceStamp(
                    path,
                    source.length(),
                    source.lastModified(),
                    source.isFile());
        }

        boolean matches(@NonNull File source) {
            return source.isFile()
                    && source.length() == length
                    && source.lastModified() == lastModified;
        }
    }

    @NonNull
    private static String diskCacheKey(@NonNull SourceStamp source, int maxSidePx) {
        String identity = DISK_CACHE_FORMAT
                + '\n' + source.path
                + '\n' + source.length
                + '\n' + source.lastModified
                + '\n' + maxSidePx;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (Exception unavailable) {
            return Integer.toHexString(identity.hashCode())
                    + '-' + Long.toHexString(source.length)
                    + '-' + Long.toHexString(source.lastModified);
        }
    }

    @Nullable
    private static File diskCacheDirectory(@NonNull Context context) {
        File directory = new File(context.getCacheDir(), DISK_CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        if (INITIAL_CACHE_TRIM_DONE.compareAndSet(false, true)) {
            trimDiskCache(directory);
        }
        return directory;
    }

    @Nullable
    private static Bitmap readDiskBitmap(@Nullable File cachedFile) {
        if (cachedFile == null || !cachedFile.isFile() || cachedFile.length() <= 0L) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(cachedFile.getAbsolutePath(), options);
        if (bitmap == null) {
            //noinspection ResultOfMethodCallIgnored
            cachedFile.delete();
            return null;
        }
        //noinspection ResultOfMethodCallIgnored
        cachedFile.setLastModified(System.currentTimeMillis());
        return bitmap;
    }

    private static void writeDiskBitmap(@NonNull File cacheDirectory,
                                        @NonNull File cachedFile,
                                        @NonNull Bitmap bitmap) {
        synchronized (DISK_CACHE_TRIM_LOCK) {
            File temp = null;
            try {
                temp = File.createTempFile("thumbnail-", ".tmp", cacheDirectory);
                boolean compressed;
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    output.flush();
                    output.getFD().sync();
                }
                if (!compressed || temp.length() <= 0L) return;
                // Both files are in the same app-private directory. POSIX rename
                // atomically replaces an older cache entry, so a crash or failed
                // write can no longer delete the last known-good thumbnail first.
                Os.rename(temp.getAbsolutePath(), cachedFile.getAbsolutePath());
                temp = null;
                int writes = CACHE_WRITES.incrementAndGet();
                if (writes % DISK_CACHE_TRIM_INTERVAL == 0) {
                    trimDiskCache(cacheDirectory);
                }
            } catch (Exception ignored) {
                // A disk-cache write must never prevent the freshly decoded bitmap
                // from being shown by the in-memory adapter cache.
            } finally {
                if (temp != null && temp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            }
        }
    }

    private static void trimDiskCache(@NonNull File cacheDirectory) {
        synchronized (DISK_CACHE_TRIM_LOCK) {
            File[] files = cacheDirectory.listFiles(file -> file != null && file.isFile());
            if (files == null || files.length == 0) return;
            Arrays.sort(files, (left, right) ->
                    Long.compare(left.lastModified(), right.lastModified()));
            long totalBytes = 0L;
            int validFiles = 0;
            for (File file : files) {
                if (file.getName().endsWith(".tmp")) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    continue;
                }
                totalBytes += Math.max(0L, file.length());
                validFiles++;
            }
            int removeIndex = 0;
            while ((totalBytes > MAX_DISK_CACHE_BYTES || validFiles > MAX_DISK_CACHE_FILES)
                    && removeIndex < files.length) {
                File file = files[removeIndex++];
                if (!file.isFile() || file.getName().endsWith(".tmp")) continue;
                String name = file.getName();
                String activeKey = name.endsWith(".png")
                        ? name.substring(0, name.length() - 4)
                        : "";
                // Per-source decoding owns this key. Let a later trim remove it
                // rather than racing BitmapFactory or an atomic cache commit.
                if (!activeKey.isEmpty() && KEY_LOCKS.containsKey(activeKey)) continue;
                long length = Math.max(0L, file.length());
                if (file.delete()) {
                    totalBytes = Math.max(0L, totalBytes - length);
                    validFiles--;
                }
            }
        }
    }

    @Nullable
    private static Bitmap decodeSource(@NonNull Context context,
                                       @NonNull File file,
                                       int maxSidePx) {
        if (!file.isFile()) return null;
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (FileThumbnailMath.isThumbnailImageName(lower)) {
            return decodeBitmapFile(file, maxSidePx);
        }
        if (lower.endsWith(".pdf")) {
            return decodePdfFirstPage(file, maxSidePx);
        }
        if (lower.endsWith(".epub")) {
            return decodeEpubCover(file, maxSidePx);
        }
        if (FileThumbnailMath.isThumbnailArchiveName(lower)) {
            return decodeArchiveCover(context, file, maxSidePx);
        }
        return null;
    }

    @Nullable
    private static List<File> directThumbnailSources(@NonNull File directory,
                                                     boolean showHiddenFiles) {
        File[] children;
        try {
            children = directory.listFiles(child -> child != null
                    && child.isFile()
                    && (showHiddenFiles || !child.getName().startsWith("."))
                    && FileThumbnailMath.isThumbnailCandidateName(child.getName()));
        } catch (SecurityException denied) {
            return java.util.Collections.emptyList();
        }
        if (children == null || children.length == 0) {
            return java.util.Collections.emptyList();
        }
        ArrayList<File> preferredImages = new ArrayList<>();
        ArrayList<File> bookCandidates = new ArrayList<>();
        for (File child : children) {
            if (FileThumbnailMath.isThumbnailImageName(child.getName())) {
                preferredImages.add(child);
            } else {
                bookCandidates.add(child);
            }
        }
        preferredImages.sort((left, right) ->
                NaturalSort.compare(left.getName(), right.getName()));
        bookCandidates.sort((left, right) ->
                NaturalSort.compare(left.getName(), right.getName()));
        ArrayList<File> ordered = new ArrayList<>(
                Math.min(MAX_COVER_CANDIDATES,
                        preferredImages.size() + bookCandidates.size()));
        for (File source : preferredImages) {
            if (ordered.size() >= MAX_COVER_CANDIDATES) break;
            ordered.add(source);
        }
        for (File source : bookCandidates) {
            if (ordered.size() >= MAX_COVER_CANDIDATES) break;
            ordered.add(source);
        }
        return ordered;
    }

    @Nullable
    private static Bitmap decodeBitmapFile(@NonNull File source, int maxSidePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = sampledOptions(bounds.outWidth, bounds.outHeight, maxSidePx);
        return BitmapFactory.decodeFile(source.getAbsolutePath(), options);
    }

    @Nullable
    private static Bitmap decodeEpubCover(@NonNull File epub, int maxSidePx) {
        try (ZipFile zip = new ZipFile(epub)) {
            String coverPath = DocumentArchiveUtils.findEpubCoverImagePath(zip);
            if (coverPath == null || coverPath.isEmpty()) return null;
            ZipEntry entry = zip.getEntry(coverPath);
            if (entry == null || entry.isDirectory()) return null;
            long declaredSize = entry.getSize();
            if (declaredSize <= 0L || declaredSize > MAX_ARCHIVE_COVER_ENTRY_BYTES) {
                return null;
            }
            return decodeZipBitmap(zip, entry, maxSidePx);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    @Nullable
    private static Bitmap decodeZipBitmap(@NonNull ZipFile zip,
                                          @NonNull ZipEntry entry,
                                          int maxSidePx) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = zip.getInputStream(entry)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = sampledOptions(bounds.outWidth, bounds.outHeight, maxSidePx);
        try (InputStream input = zip.getInputStream(entry)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    @NonNull
    private static BitmapFactory.Options sampledOptions(int width,
                                                        int height,
                                                        int maxSidePx) {
        int sample = 1;
        while (width / sample > maxSidePx * 2
                || height / sample > maxSidePx * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return options;
    }

    @Nullable
    private static Bitmap decodePdfFirstPage(@NonNull File pdf, int maxSidePx) {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (renderer.getPageCount() <= 0) return null;
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                int pageWidth = Math.max(1, page.getWidth());
                int pageHeight = Math.max(1, page.getHeight());
                float scale = maxSidePx / (float) Math.max(pageWidth, pageHeight);
                int width = Math.max(1, Math.round(pageWidth * scale));
                int height = Math.max(1, Math.round(pageHeight * scale));
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    @Nullable
    private static Bitmap decodeArchiveCover(@NonNull Context context,
                                             @NonNull File archive,
                                             int maxSidePx) {
        File temp = null;
        try {
            List<ArchiveSupport.EntryInfo> entries = ArchiveSupport.listEntries(archive, null);
            ArrayList<ArchiveSupport.EntryInfo> images = new ArrayList<>();
            for (ArchiveSupport.EntryInfo entry : entries) {
                if (entry == null || entry.directory || entry.path == null) continue;
                String normalized = entry.path.replace('\\', '/');
                String lower = normalized.toLowerCase(Locale.ROOT);
                if (lower.startsWith("__macosx/")
                        || lower.contains("/__macosx/")
                        || FileThumbnailMath.fileName(lower).startsWith("._")
                        || !FileThumbnailMath.isThumbnailImageName(lower)
                        || entry.size <= 0L
                        || entry.size > MAX_ARCHIVE_COVER_ENTRY_BYTES) {
                    continue;
                }
                images.add(entry);
            }
            if (images.isEmpty()) return null;
            images.sort((left, right) -> NaturalSort.compare(left.path, right.path));

            File cacheDir = new File(context.getCacheDir(), "file_thumbnail_entries");
            if (!cacheDir.exists() && !cacheDir.mkdirs()) return null;
            temp = File.createTempFile("cover-", ".image", cacheDir);
            int attempts = Math.min(MAX_COVER_CANDIDATES, images.size());
            for (int i = 0; i < attempts; i++) {
                ArchiveSupport.EntryInfo candidate = images.get(i);
                ArchiveSupport.ExtractionResult result =
                        ArchiveSupport.extractSingleEntryDetailed(
                                archive, candidate.path, temp, null);
                if (!result.success
                        || !temp.isFile()
                        || temp.length() <= 0L
                        || temp.length() > MAX_ARCHIVE_COVER_ENTRY_BYTES) {
                    continue;
                }
                Bitmap decoded = decodeBitmapFile(temp, maxSidePx);
                if (decoded != null && !decoded.isRecycled()) return decoded;
            }
            return null;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (temp != null && temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }
}
