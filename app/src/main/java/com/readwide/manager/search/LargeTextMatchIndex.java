package com.readwide.manager.search;

import androidx.annotation.NonNull;
import java.io.*;
import java.util.Arrays;

/** Exact position index: bounded primitive arrays, then fixed-width disk records. */
final class LargeTextMatchIndex implements AutoCloseable {
    private final String filePath, query, optionsSignature, transformSignature;
    private final long fileLength, fileLastModified;
    private final boolean collapseBlankLines;
    private final int[] positions, lines;
    private int count;
    private File diskFile;
    private RandomAccessFile disk;
    private boolean closed;

    LargeTextMatchIndex(File file, String query, String options, boolean collapse,
                       String transform, int[] positions, int[] lines) {
        filePath = file.getAbsolutePath(); fileLength = file.length(); fileLastModified = file.lastModified();
        this.query = query; optionsSignature = options; collapseBlankLines = collapse;
        transformSignature = transform; this.positions = positions; this.lines = lines; count = positions.length;
    }

    synchronized boolean matches(File file, String query, String options, boolean collapse, String transform) {
        return !closed && fileStateUnchanged(file) && this.query.equals(query)
                && optionsSignature.equals(options) && collapseBlankLines == collapse
                && transformSignature.equals(transform);
    }
    boolean fileStateUnchanged(File file) {
        return filePath.equals(file.getAbsolutePath()) && fileLength == file.length()
                && fileLastModified == file.lastModified();
    }
    int size() { return count; }

    synchronized LargeTextSearchResult nearest(int startPosition, boolean forward) throws IOException {
        checkOpen();
        if (count == 0) return new LargeTextSearchResult(-1, 1, 0, 0);
        int low = 0, high = count, target = Math.max(0, startPosition);
        while (low < high) {
            int mid = (low + high) >>> 1, position = positionAt(mid);
            if (position < target || (!forward && position == target)) low = mid + 1;
            else high = mid;
        }
        int index = forward ? (low == count ? 0 : low) : (low == 0 ? count - 1 : low - 1);
        return resultAt(index);
    }
    synchronized LargeTextSearchResult occurrence(int occurrence) throws IOException {
        checkOpen();
        return occurrence < 1 || occurrence > count ? new LargeTextSearchResult(-1, 1, 0, count)
                : resultAt(occurrence - 1);
    }
    private int positionAt(int index) throws IOException {
        if (disk == null) return positions[index];
        disk.seek(index * 8L); return disk.readInt();
    }
    private LargeTextSearchResult resultAt(int index) throws IOException {
        if (disk == null) return new LargeTextSearchResult(positions[index], lines[index], index + 1, count);
        disk.seek(index * 8L);
        return new LargeTextSearchResult(disk.readInt(), disk.readInt(), index + 1, count);
    }
    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Search index closed");
        if (disk != null && (diskFile == null || !diskFile.isFile() || disk.length() != count * 8L))
            throw new IOException("Search index cache unavailable");
    }
    @Override public synchronized void close() {
        closed = true;
        if (disk != null) try { disk.close(); } catch (IOException ignored) { }
        disk = null;
        if (diskFile != null) diskFile.delete();
        diskFile = null;
    }

    static final class Builder implements AutoCloseable {
        private final int limit;
        private final File directory;
        private int[] positions, lines;
        private int size;
        private boolean overflow;
        private File temporary;
        private DataOutputStream output;
        Builder(int limit) { this(limit, null); }
        Builder(int limit, File directory) {
            this.limit = Math.max(0, limit); this.directory = directory;
            positions = new int[Math.min(this.limit, 256)]; lines = new int[positions.length];
        }
        void add(int position, int line) {
            if (overflow) return;
            try {
                if (size == Integer.MAX_VALUE) throw new IOException("Search ordinal range exhausted");
                if (output == null && size >= limit) {
                    if (directory == null) throw new IOException("No index cache directory");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create search cache");
                    requireSpace();
                    temporary = File.createTempFile("matches-", ".idx", directory);
                    output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary), 65536));
                    for (int i = 0; i < size; i++) { output.writeInt(positions[i]); output.writeInt(lines[i]); }
                    positions = new int[0]; lines = new int[0];
                }
                if (output != null) {
                    if ((size & 4095) == 0) requireSpace();
                    output.writeInt(position); output.writeInt(Math.max(1, line));
                } else {
                    if (positions.length <= size) {
                        int capacity = (int) Math.min(limit, Math.max(size + 1L, Math.max(16L, positions.length * 2L)));
                        positions = Arrays.copyOf(positions, capacity); lines = Arrays.copyOf(lines, capacity);
                    }
                    positions[size] = position; lines[size] = Math.max(1, line);
                }
                size++;
            } catch (IOException | SecurityException failure) {
                overflow = true; close(); positions = new int[0]; lines = new int[0]; size = 0;
            }
        }
        private void requireSpace() throws IOException {
            if (directory.getUsableSpace() < 64L * 1024 * 1024) throw new IOException("Search cache storage reserve");
        }
        boolean overflowed() { return overflow; }
        LargeTextMatchIndex build(@NonNull File file, @NonNull String query, @NonNull String options,
                                  boolean collapse, @NonNull String transform) throws IOException {
            if (overflow) throw new IOException("Search index unavailable");
            if (output == null) return new LargeTextMatchIndex(file, query, options, collapse, transform,
                    Arrays.copyOf(positions, size), Arrays.copyOf(lines, size));
            output.close(); output = null;
            LargeTextMatchIndex index = new LargeTextMatchIndex(file, query, options, collapse, transform,
                    new int[0], new int[0]);
            index.disk = new RandomAccessFile(temporary, "r");
            index.count = size; index.diskFile = temporary; temporary = null;
            return index;
        }
        @Override public void close() {
            if (output != null) try { output.close(); } catch (IOException ignored) { }
            output = null;
            if (temporary != null) temporary.delete();
            temporary = null;
        }
    }
}
