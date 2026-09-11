package com.readwide.manager.archive;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarFile;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metadata-only random access for ordinary uncompressed TAR members. No open handles are cached. */
final class TarEntryIndex {
    private static final int MAX_ARCHIVES = 3;
    // Cache admission limits, not archive/file-size limits. Larger indexes remain usable uncached.
    private static final int MAX_ENTRIES = 20000;
    private static final long MAX_NAME_CHARS = 1024 * 1024;
    private static final Map<String, Index> INDEXES = new LinkedHashMap<>(4, 0.75f, true);

    private TarEntryIndex() { }

    static final class Index {
        final String path;
        final long length, modified;
        final List<ArchiveSupport.EntryInfo> listing;
        final Map<String, Slice> files;
        final boolean cacheable;

        Index(String path, long length, long modified, List<ArchiveSupport.EntryInfo> listing,
              Map<String, Slice> files, boolean cacheable) {
            this.path = path; this.length = length; this.modified = modified;
            this.listing = Collections.unmodifiableList(listing);
            this.files = Collections.unmodifiableMap(files);
            this.cacheable = cacheable;
        }

        boolean matches(File file) throws IOException {
            return path.equals(file.getCanonicalPath()) && length == file.length()
                    && modified == file.lastModified();
        }
    }

    private static final class Slice {
        final long offset, size;
        final boolean sparse;
        Slice(long offset, long size, boolean sparse) {
            this.offset = offset; this.size = size; this.sparse = sparse;
        }
    }

    static Index get(File archive) throws IOException {
        checkpoint();
        String path = archive.getCanonicalPath();
        synchronized (INDEXES) {
            Index cached = INDEXES.get(path);
            if (cached != null && cached.matches(archive)) return cached;
            INDEXES.remove(path);
        }
        long length = archive.length(), modified = archive.lastModified();
        List<ArchiveSupport.EntryInfo> listing = new ArrayList<>();
        Map<String, Slice> files = new LinkedHashMap<>();
        long nameChars = 0;
        // Commons handles PAX/GNU long names and sparse layout while seeking over payloads.
        // Own the RandomAccessFile too, including when TarFile construction fails.
        try (RandomAccessFile raw = new RandomAccessFile(archive, "r");
             TarFile tar = new TarFile(raw.getChannel())) {
            for (TarArchiveEntry entry : tar.getEntries()) {
                checkpoint();
                if (!entry.isCheckSumOK()) throw new IOException("Invalid TAR header checksum");
                if (entry.isSymbolicLink() || entry.isLink()) continue;
                if (!entry.isDirectory() && !entry.isFile() && !entry.isSparse()) continue;
                String name = ArchiveSupport.sanitizeEntryPathForList(entry.getName());
                if (name == null) continue;
                long size = entry.getSize(), offset = entry.getDataOffset();
                if (size < 0 || offset < 0 || offset > length || size > length - offset) {
                    throw new EOFException("TAR member exceeds archive bounds");
                }
                if (!entry.isDirectory() && files.containsKey(name)) continue;
                listing.add(new ArchiveSupport.EntryInfo(name, entry.isDirectory(), size, 0L));
                nameChars += name.length();
                if (!entry.isDirectory()) {
                    // Match streaming extraction: the first readable occurrence wins.
                    files.put(name, new Slice(offset, size, entry.isSparse()));
                }
            }
        }
        Index built = new Index(path, length, modified, listing, files,
                listing.size() <= MAX_ENTRIES && nameChars <= MAX_NAME_CHARS);
        if (!built.matches(archive)) throw new IOException("TAR changed while indexing");
        synchronized (INDEXES) {
            Index concurrent = INDEXES.get(path);
            if (concurrent != null && concurrent.matches(archive)) return concurrent;
            if (built.cacheable) {
                INDEXES.put(path, built);
                while (INDEXES.size() > MAX_ARCHIVES) INDEXES.remove(INDEXES.keySet().iterator().next());
            }
        }
        return built;
    }

    /** null means the requested sparse member needs the established stream decoder. */
    static Boolean extract(File archive, String name, File target) throws IOException {
        return extract(get(archive), archive, name, target);
    }

    static Boolean extract(Index index, File archive, String name, File target) throws IOException {
        Slice slice = index.files.get(name);
        if (slice == null) return false;
        if (slice.sparse) return null;
        try (RandomAccessFile input = new RandomAccessFile(archive, "r");
             RarOutputFileGuard guard = RarOutputFileGuard.forTarget(target)) {
            if (!index.matches(archive) || input.length() != index.length) throw new IOException("TAR changed before extraction");
            input.seek(slice.offset);
            try (OutputStream output = ArchiveSupport.openExtractionOutputStream(target)) {
                long remaining = slice.size;
                byte[] buffer = new byte[64 * 1024];
                while (remaining > 0) {
                    checkpoint();
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException("Truncated TAR member");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            checkpoint();
            if (!index.matches(archive) || input.length() != index.length) throw new IOException("TAR changed during extraction");
            guard.commit();
        } catch (IOException failure) {
            throw new ExtractionException(failure);
        }
        return true;
    }

    static final class ExtractionException extends IOException {
        ExtractionException(IOException cause) { super(cause.getMessage(), cause); }
    }

    static void release(File archive) {
        try { synchronized (INDEXES) { INDEXES.remove(archive.getCanonicalPath()); } }
        catch (IOException ignored) { }
    }

    static void clear() { synchronized (INDEXES) { INDEXES.clear(); } }

    private static void checkpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("TAR extraction cancelled");
    }
}
