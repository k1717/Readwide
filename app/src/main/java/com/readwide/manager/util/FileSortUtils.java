package com.readwide.manager.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.model.FileListItem;

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

    // Legacy non-cancellable entry point: for callers that sort on the UI thread or
    // sort short image lists (ImageReader / MainImageOpen / FileAdapter) and don't need
    // to observe cancellation. Background search/load paths call
    // sortMainFilesCancellable directly and check the result.
    public static void sortMainFiles(@Nullable Context context, @NonNull List<File> target, int sortMode) {
        sortMainFilesCancellable(context, target, sortMode);
    }

    /**
     * Cancellable form of {@link #sortMainFiles(Context, List, int)} used by the
     * background search/filter sort. Returns {@code false} (leaving {@code target}
     * unchanged) if a superseded search interrupts this thread — both during key
     * extraction and inside the comparator. The ordering is identical to sortMainFiles.
     */
    public static boolean sortMainFilesCancellable(@Nullable Context context, @NonNull List<File> target, int sortMode) {
        int n = target.size();
        if (n < 2) return true;
        // Date sorting uses filesystem timestamps only here (no per-file MediaStore
        // query). MediaStore DATE_ADDED, when it differs, is folded in afterwards by
        // a batched background pass (batchMediaStoreDateAdded), so a folder of
        // thousands of photos is not N separate ContentResolver IPC calls on the
        // sort path. The context parameter is retained for call-site compatibility.
        boolean needDate = sortMode == PrefsManager.SORT_DATE_NEW || sortMode == PrefsManager.SORT_DATE_OLD;
        boolean needSize = sortMode == PrefsManager.SORT_SIZE_LARGE || sortMode == PrefsManager.SORT_SIZE_SMALL;
        boolean needExt = sortMode == PrefsManager.SORT_TYPE;
        // Schwartzian transform: extract each file's sort attributes ONCE instead
        // of calling File.getName()/isDirectory()/length() inside the comparator,
        // where they would run O(n log n) times (and isDirectory()/length() hit
        // the filesystem). This is the main list-refresh speedup.
        SortKey[] keys = new SortKey[n];
        for (int i = 0; i < n; i++) {
            // Cooperative cancellation: when the search is superseded the executor
            // calls Future.cancel(true), which interrupts this thread. Bail out
            // promptly; the caller discards stale results by search generation.
            if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
            File f = target.get(i);
            SortKey k = new SortKey();
            k.file = f;
            k.name = f.getName();
            k.isDir = f.isDirectory();
            k.date = needDate ? filesystemSortDate(f) : 0L;
            k.size = needSize ? fileSortSize(f) : 0L;
            k.ext = needExt ? fileExtension(k.name) : null;
            keys[i] = k;
        }
        try {
            java.util.Arrays.sort(keys, new java.util.Comparator<SortKey>() {
                private int comparisons;
                @Override public int compare(SortKey a, SortKey b) {
                    if ((++comparisons & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException();
                    }
                    if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                    switch (sortMode) {
                        case PrefsManager.SORT_NAME_DESC:
                            return NaturalSort.compare(b.name, a.name);
                        case PrefsManager.SORT_DATE_NEW: {
                            int cmp = Long.compare(b.date, a.date);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_DATE_OLD: {
                            int cmp = Long.compare(a.date, b.date);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_SIZE_LARGE: {
                            int cmp = Long.compare(b.size, a.size);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_SIZE_SMALL: {
                            int cmp = Long.compare(a.size, b.size);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_TYPE: {
                            int cmp = a.ext.compareTo(b.ext);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_NAME_ASC:
                        default:
                            return NaturalSort.compare(a.name, b.name);
                    }
                }
            });
        } catch (java.util.concurrent.CancellationException cancelled) {
            Thread.currentThread().interrupt();
            return false;
        }
        for (int i = 0; i < n; i++) target.set(i, keys[i].file);
        return true;
    }

    /**
     * Item-based equivalent of {@link #sortMainFiles(Context, List, int)} that sorts
     * FileListItems using the metadata already computed on each item (directory flag,
     * size, sort date, name) instead of re-stat-ing the underlying File. The ordering
     * matches sortMainFiles exactly. No per-item filesystem access happens here, so the
     * only cost is the comparisons. Returns {@code true} on success; returns
     * {@code false} (leaving {@code target} unchanged) if the thread was interrupted —
     * a superseded folder load or re-sort cancels its Future, and the caller then
     * discards the stale work by generation.
     */
    public static boolean sortMainItems(@NonNull List<FileListItem> target, int sortMode) {
        int n = target.size();
        if (n < 2) return true;
        boolean needDate = sortMode == PrefsManager.SORT_DATE_NEW || sortMode == PrefsManager.SORT_DATE_OLD;
        boolean needSize = sortMode == PrefsManager.SORT_SIZE_LARGE || sortMode == PrefsManager.SORT_SIZE_SMALL;
        boolean needExt = sortMode == PrefsManager.SORT_TYPE;
        // Schwartzian transform over each item's already-computed metadata (no
        // filesystem access here). Extracting the type key once also avoids
        // recomputing fileExtension() inside the comparator O(n log n) times.
        ItemSortKey[] keys = new ItemSortKey[n];
        for (int i = 0; i < n; i++) {
            // Cooperative here too: building n keys is cheap (no stat), but for a very
            // large list abandon promptly on cancellation instead of allocating them all.
            if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
            FileListItem item = target.get(i);
            ItemSortKey k = new ItemSortKey();
            k.item = item;
            k.name = item.getName();
            k.isDir = item.isDirectory();
            k.date = needDate ? item.getSortDate() : 0L;
            k.size = needSize ? item.getSize() : 0L;
            k.ext = needExt ? fileExtension(k.name) : null;
            keys[i] = k;
        }
        try {
            java.util.Arrays.sort(keys, new java.util.Comparator<ItemSortKey>() {
                private int comparisons;
                @Override public int compare(ItemSortKey a, ItemSortKey b) {
                    // Unlike the File path the cost here is in the comparisons (metadata
                    // is precomputed), so check for cancellation here: a superseded load
                    // or re-sort calls Future.cancel(true) and we abandon promptly. The
                    // sort runs on this key-array copy, so target keeps its original
                    // order if cancelled (it is written back only on success below).
                    if ((++comparisons & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException();
                    }
                    if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                    switch (sortMode) {
                        case PrefsManager.SORT_NAME_DESC:
                            return NaturalSort.compare(b.name, a.name);
                        case PrefsManager.SORT_DATE_NEW: {
                            int cmp = Long.compare(b.date, a.date);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_DATE_OLD: {
                            int cmp = Long.compare(a.date, b.date);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_SIZE_LARGE: {
                            int cmp = Long.compare(b.size, a.size);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_SIZE_SMALL: {
                            int cmp = Long.compare(a.size, b.size);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_TYPE: {
                            int cmp = a.ext.compareTo(b.ext);
                            return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                        }
                        case PrefsManager.SORT_NAME_ASC:
                        default:
                            return NaturalSort.compare(a.name, b.name);
                    }
                }
            });
        } catch (java.util.concurrent.CancellationException cancelled) {
            Thread.currentThread().interrupt();
            return false;
        }
        for (int i = 0; i < n; i++) target.set(i, keys[i].item);
        return true;
    }

    /**
     * Item-based date sort that prefers MediaStore DATE_ADDED (from a prebuilt batch
     * map, keyed by absolute path) and falls back to each item's already-computed sort
     * date — which is {@link #filesystemSortDate(File)}, so the ordering matches the
     * File-based fallback exactly without re-stat-ing. Reorders the same item objects,
     * so search-result location labels survive refinement. Cancellable like
     * {@link #sortMainItems(List, int)}: returns {@code false} (leaving {@code target}
     * unchanged) if interrupted by a superseded refine.
     */
    public static boolean sortMainItemsWithDateOverrides(@NonNull List<FileListItem> target,
                                                         int sortMode,
                                                         @NonNull Map<String, Long> dateOverrides) {
        int n = target.size();
        if (n < 2) return true;
        boolean newest = sortMode == PrefsManager.SORT_DATE_NEW;
        ItemSortKey[] keys = new ItemSortKey[n];
        for (int i = 0; i < n; i++) {
            if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
            FileListItem item = target.get(i);
            ItemSortKey k = new ItemSortKey();
            k.item = item;
            k.name = item.getName();
            k.isDir = item.isDirectory();
            Long override = dateOverrides.get(item.getAbsolutePath());
            k.date = override != null && override > 0L ? override : item.getSortDate();
            keys[i] = k;
        }
        try {
            java.util.Arrays.sort(keys, new java.util.Comparator<ItemSortKey>() {
                private int comparisons;
                @Override public int compare(ItemSortKey a, ItemSortKey b) {
                    if ((++comparisons & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException();
                    }
                    if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                    int cmp = newest ? Long.compare(b.date, a.date) : Long.compare(a.date, b.date);
                    return cmp != 0 ? cmp : NaturalSort.compare(a.name, b.name);
                }
            });
        } catch (java.util.concurrent.CancellationException cancelled) {
            Thread.currentThread().interrupt();
            return false;
        }
        for (int i = 0; i < n; i++) target.set(i, keys[i].item);
        return true;
    }

    /** Batch MediaStore DATE_ADDED lookup using DATA IN (...) chunks. */
    @NonNull
    public static Map<String, Long> batchMediaStoreDateAdded(@Nullable Context context,
                                                             @NonNull List<File> files) {
        Map<String, Long> out = new HashMap<>();
        if (context == null) return out;
        List<String> paths = new java.util.ArrayList<>();
        for (File f : files) {
            if (f != null && !f.isDirectory()) paths.add(f.getAbsolutePath());
        }
        if (paths.isEmpty()) return out;
        Uri uri = MediaStore.Files.getContentUri("external");
        String[] projection = new String[] {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED};
        final int CHUNK = 400; // stay well under the SQLite variable limit (~999)
        for (int start = 0; start < paths.size(); start += CHUNK) {
            // A superseded refine (navigation, new search/sort) interrupts this thread;
            // bail between chunks rather than running every MediaStore query. The
            // partial map is fine: the caller re-checks interrupt/generation and
            // discards it before using it.
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return out;
            }
            int end = Math.min(paths.size(), start + CHUNK);
            List<String> chunk = paths.subList(start, end);
            StringBuilder sel = new StringBuilder(MediaStore.MediaColumns.DATA).append(" IN (");
            for (int i = 0; i < chunk.size(); i++) sel.append(i == 0 ? "?" : ",?");
            sel.append(")");
            try (Cursor cursor = context.getContentResolver().query(
                    uri, projection, sel.toString(), chunk.toArray(new String[0]), null)) {
                if (cursor == null) continue;
                int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                int dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
                if (dataCol < 0 || dateCol < 0) continue;
                while (cursor.moveToNext()) {
                    if (cursor.isNull(dateCol)) continue;
                    String path = cursor.getString(dataCol);
                    if (path == null) continue;
                    long millis = secondsToMillisIfNeeded(cursor.getLong(dateCol));
                    if (millis > 0L) out.put(path, millis);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Filesystem-only date (no MediaStore): max of lastModified and creation time.
     * Use this for fast, frequent contexts like list-row binding; MediaStore
     * DATE_ADDED is reserved for the file-info screen and date-sort refinement.
     */
    public static long filesystemSortDate(@NonNull File file) {
        long modified = Math.max(0L, file.lastModified());
        long created = fileCreationTimeMillis(file);
        return Math.max(modified, created);
    }

    private static final class SortKey {
        File file;
        String name;
        boolean isDir;
        long date;
        long size;
        String ext;
    }

    private static final class ItemSortKey {
        FileListItem item;
        String name;
        boolean isDir;
        long date;
        long size;
        String ext;
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
