package com.textview.reader.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared sorting code for main file lists and archive previews. */
public final class FileSortUtils {
    private FileSortUtils() {}

    public static void sortMainFiles(@NonNull List<File> target, int sortMode) {
        sortMainFiles(null, target, sortMode);
    }

    public static void sortMainFiles(@Nullable Context context, @NonNull List<File> target, int sortMode) {
        Map<String, Long> dateCache = sortMode == PrefsManager.SORT_DATE_NEW
                || sortMode == PrefsManager.SORT_DATE_OLD
                ? buildDateCache(context, target)
                : Collections.emptyMap();
        Collections.sort(target, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            switch (sortMode) {
                case PrefsManager.SORT_NAME_DESC:
                    return NaturalSort.compare(b.getName(), a.getName());
                case PrefsManager.SORT_DATE_NEW: {
                    int cmp = Long.compare(cachedFileSortDate(dateCache, b), cachedFileSortDate(dateCache, a));
                    return cmp != 0 ? cmp : NaturalSort.compare(a.getName(), b.getName());
                }
                case PrefsManager.SORT_DATE_OLD: {
                    int cmp = Long.compare(cachedFileSortDate(dateCache, a), cachedFileSortDate(dateCache, b));
                    return cmp != 0 ? cmp : NaturalSort.compare(a.getName(), b.getName());
                }
                case PrefsManager.SORT_SIZE_LARGE: {
                    int cmp = Long.compare(fileSortSize(b), fileSortSize(a));
                    return cmp != 0 ? cmp : NaturalSort.compare(a.getName(), b.getName());
                }
                case PrefsManager.SORT_SIZE_SMALL: {
                    int cmp = Long.compare(fileSortSize(a), fileSortSize(b));
                    return cmp != 0 ? cmp : NaturalSort.compare(a.getName(), b.getName());
                }
                case PrefsManager.SORT_TYPE: {
                    int cmp = fileExtension(a.getName()).compareTo(fileExtension(b.getName()));
                    return cmp != 0 ? cmp : NaturalSort.compare(a.getName(), b.getName());
                }
                case PrefsManager.SORT_NAME_ASC:
                default:
                    return NaturalSort.compare(a.getName(), b.getName());
            }
        });
    }

    public static void sortArchiveImageSequence(@NonNull List<ArchiveSupport.EntryInfo> target) {
        Collections.sort(target, (a, b) -> {
            int cmp = NaturalSort.compare(archiveImageSequenceKey(a), archiveImageSequenceKey(b));
            if (cmp != 0) return cmp;
            cmp = NaturalSort.compare(a.name(), b.name());
            if (cmp != 0) return cmp;
            cmp = Long.compare(a.size, b.size);
            if (cmp != 0) return cmp;
            return Long.compare(a.timeMillis, b.timeMillis);
        });
    }

    @NonNull
    private static String archiveImageSequenceKey(@NonNull ArchiveSupport.EntryInfo entry) {
        String path = entry.path == null ? "" : entry.path.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        while (path.contains("//")) path = path.replace("//", "/");
        return path;
    }

    public static void sortArchiveEntries(@NonNull List<ArchiveSupport.EntryInfo> target, int sortMode) {
        Collections.sort(target, (a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            switch (sortMode) {
                case PrefsManager.SORT_NAME_DESC:
                    return NaturalSort.compare(b.name(), a.name());
                case PrefsManager.SORT_DATE_NEW: {
                    int cmp = Long.compare(b.timeMillis, a.timeMillis);
                    return cmp != 0 ? cmp : NaturalSort.compare(a.name(), b.name());
                }
                case PrefsManager.SORT_DATE_OLD: {
                    int cmp = Long.compare(a.timeMillis, b.timeMillis);
                    return cmp != 0 ? cmp : NaturalSort.compare(a.name(), b.name());
                }
                case PrefsManager.SORT_SIZE_LARGE: {
                    int cmp = Long.compare(b.size, a.size);
                    return cmp != 0 ? cmp : NaturalSort.compare(a.name(), b.name());
                }
                case PrefsManager.SORT_SIZE_SMALL: {
                    int cmp = Long.compare(a.size, b.size);
                    return cmp != 0 ? cmp : NaturalSort.compare(a.name(), b.name());
                }
                case PrefsManager.SORT_TYPE: {
                    int cmp = fileExtension(a.name()).compareTo(fileExtension(b.name()));
                    return cmp != 0 ? cmp : NaturalSort.compare(a.name(), b.name());
                }
                case PrefsManager.SORT_NAME_ASC:
                default:
                    return NaturalSort.compare(a.name(), b.name());
            }
        });
    }

    private static long fileSortSize(@NonNull File file) {
        return file.isDirectory() ? 0L : file.length();
    }

    public static long fileSortDate(@NonNull File file) {
        return fileSortDate(null, file);
    }

    public static long fileSortDate(@Nullable Context context, @NonNull File file) {
        long added = fileDownloadedTimeMillis(context, file);
        if (added > 0L) return added;
        long modified = Math.max(0L, file.lastModified());
        long created = fileCreationTimeMillis(file);
        return Math.max(modified, created);
    }

    public static long fileDownloadedTimeMillis(@Nullable Context context, @NonNull File file) {
        return mediaStoreDateAddedMillis(context, file);
    }

    public static long fileCreationTimeMillis(@NonNull File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0L;
        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            return attrs.creationTime() != null ? Math.max(0L, attrs.creationTime().toMillis()) : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    @NonNull
    private static Map<String, Long> buildDateCache(@Nullable Context context, @NonNull List<File> files) {
        Map<String, Long> cache = new HashMap<>(Math.max(16, files.size() * 2));
        for (File file : files) {
            if (file == null) continue;
            cache.put(file.getAbsolutePath(), fileSortDate(context, file));
        }
        return cache;
    }

    private static long cachedFileSortDate(@NonNull Map<String, Long> cache, @NonNull File file) {
        Long value = cache.get(file.getAbsolutePath());
        return value != null ? value : fileSortDate(file);
    }

    private static long mediaStoreDateAddedMillis(@Nullable Context context, @NonNull File file) {
        if (context == null || !file.isFile()) return 0L;
        String path = file.getAbsolutePath();
        Uri[] uris = mediaStoreUrisFor(file.getName());
        for (Uri uri : uris) {
            long value = queryMediaStoreDateAdded(context, uri, path);
            if (value > 0L) return value;
        }
        return 0L;
    }

    @NonNull
    private static Uri[] mediaStoreUrisFor(@NonNull String name) {
        if (FileUtils.isImageFile(name)) {
            return new Uri[] {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Files.getContentUri("external")
            };
        }
        if (FileUtils.isVideoFile(name)) {
            return new Uri[] {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Files.getContentUri("external")
            };
        }
        return new Uri[] {MediaStore.Files.getContentUri("external")};
    }

    private static long queryMediaStoreDateAdded(@NonNull Context context, @NonNull Uri uri, @NonNull String path) {
        String[] projection = new String[] {MediaStore.MediaColumns.DATE_ADDED};
        String selection = MediaStore.MediaColumns.DATA + "=?";
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                projection,
                selection,
                new String[] {path},
                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return secondsToMillisIfNeeded(cursor.getLong(0));
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private static long secondsToMillisIfNeeded(long value) {
        if (value <= 0L) return 0L;
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    @NonNull
    private static String fileExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
