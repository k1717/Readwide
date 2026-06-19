package com.readwide.manager.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileSortUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A file-list row paired with the metadata needed to display and diff it.
 *
 * <p>The whole point of this model is to compute each file's {@code isDirectory},
 * size, last-modified time, and sort date <em>once</em> — on the background load
 * thread, where the directory is already being walked — so that the adapter's
 * {@code onBindViewHolder} and {@code DiffUtil} comparisons never have to call
 * {@link File#isDirectory()}, {@link File#length()}, {@link File#lastModified()},
 * or {@code Files.readAttributes(...)} again on the UI thread. Those per-row
 * filesystem stats were a measurable source of scroll jank on large, FUSE-backed
 * folders such as Downloads.</p>
 *
 * <p>The wrapped {@link File} is kept so existing call sites (click callbacks,
 * archive/image opening, bookmarks) keep working unchanged.</p>
 */
public final class FileListItem {

    @NonNull private final File file;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final long sortDate;
    @NonNull private final String name;
    @NonNull private final String absolutePath;
    @Nullable private final String displayLocation;

    public FileListItem(@NonNull File file,
                        boolean directory,
                        long size,
                        long lastModified,
                        long sortDate) {
        this(file, directory, size, lastModified, sortDate, null);
    }

    public FileListItem(@NonNull File file,
                        boolean directory,
                        long size,
                        long lastModified,
                        long sortDate,
                        @Nullable String displayLocation) {
        this.file = file;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.sortDate = sortDate;
        this.name = file.getName();
        this.absolutePath = file.getAbsolutePath();
        this.displayLocation = displayLocation;
    }

    /**
     * Builds an item by stat-ing the file once. Intended to be called from a
     * background thread (folder load / search), not the UI thread.
     */
    @NonNull
    public static FileListItem from(@NonNull File file) {
        return build(file, file.isDirectory(), null);
    }

    /**
     * Like {@link #from(File)} but reuses an already-known directory flag, so the
     * caller (e.g. the folder-load walk, which already called
     * {@link File#isDirectory()} to bucket the entry) does not pay a second
     * isDirectory() stat. Intended for background threads.
     */
    @NonNull
    public static FileListItem fromKnownDirectory(@NonNull File file, boolean isDirectory) {
        return build(file, isDirectory, null);
    }

    /**
     * Like {@link #from(File)} but also carries a precomputed search-result
     * location label (the parent folder shown under the file name in search
     * mode). Computing it here, on the background search thread, keeps the
     * adapter from resolving the canonical parent path on the UI thread while
     * binding and scrolling search rows.
     */
    @NonNull
    public static FileListItem withLocation(@NonNull File file, @Nullable String displayLocation) {
        return build(file, file.isDirectory(), displayLocation);
    }

    @NonNull
    private static FileListItem build(@NonNull File file, boolean directory, @Nullable String displayLocation) {
        long size = directory ? 0L : Math.max(0L, file.length());
        long modified = Math.max(0L, file.lastModified());
        long sortDate = FileSortUtils.filesystemSortDate(file);
        return new FileListItem(file, directory, size, modified, sortDate, displayLocation);
    }

    /**
     * Builds items for a whole list, stat-ing each file once. Intended for
     * background threads (folder load, file search, recent list) so the UI
     * thread never stats files when the list is handed to the adapter.
     */
    @NonNull
    public static List<FileListItem> fromList(@NonNull List<File> files) {
        List<FileListItem> out = new ArrayList<>(files.size());
        for (File f : files) {
            if (f != null) out.add(from(f));
        }
        return out;
    }

    @NonNull public File getFile() { return file; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }
    public long getLastModified() { return lastModified; }
    public long getSortDate() { return sortDate; }
    @NonNull public String getName() { return name; }
    @NonNull public String getAbsolutePath() { return absolutePath; }
    @Nullable public String getDisplayLocation() { return displayLocation; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileListItem)) return false;
        FileListItem other = (FileListItem) o;
        return absolutePath.equals(other.absolutePath);
    }

    @Override
    public int hashCode() {
        return absolutePath.hashCode();
    }
}
