package com.readwide.manager;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.util.FileUtils;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Session-scoped forward reader for solid/sequential archives (7z and the TAR family).
 *
 * <p>Solid/sequential archives have no cheap random per-entry access: extracting one image at
 * a time re-opens the archive and re-decompresses the shared stream from the start up to that
 * entry, which is O(n) per page and O(n^2) for a full read-through. This reader keeps one
 * forward stream open for the whole viewing session and decodes each entry exactly once,
 * caching every image it passes into the shared preview cache. Paging forward then costs one
 * entry decode; pages already passed are cache hits that do not touch the stream. A passed page
 * whose cache file was later evicted is re-read with a one-off single-entry extraction (one page,
 * not the whole archive), since the open stream can only move forward.</p>
 *
 * <p>The reader is a pure optimization. {@link #ensureImageReady} always falls back to the
 * existing whole-archive extraction in {@link ArchiveImageEntryCache#ensureReady} when the
 * reader is unavailable or fails, so a reader problem can only cost performance, never
 * correctness.</p>
 */
final class SequentialArchiveImageReader implements Closeable {

    private static final String TAG = "ReadwideArchiveImg";
    // Per-entry decoded-bytes ceiling, mirroring the single-entry extraction safety limit.
    private static final long MAX_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    @NonNull private final Context appContext;
    @NonNull private final File archiveFile;
    private final boolean sensitiveCache;
    @Nullable private final char[] password;
    @Nullable private final Set<String> verifiedSensitivePaths;

    private final Object lock = new Object();
    // Every image entry the reader has already advanced past, by sanitized path. A
    // request for one of these is behind the open forward stream, so it is served by a
    // one-off single-entry extraction rather than scanning the stream forward to the
    // end (which can never reach a passed entry) and then exhausting the reader.
    private final HashSet<String> passedEntries = new HashSet<>();

    @Nullable private ArchiveSupport.ForwardArchiveReader reader;
    private boolean openAttempted;
    private boolean exhausted;
    private boolean broken;
    private boolean closed;

    private SequentialArchiveImageReader(@NonNull Context context,
                                         @NonNull File archiveFile,
                                         @Nullable char[] password,
                                         boolean sensitiveCache,
                                         @Nullable Set<String> verifiedSensitivePaths) {
        this.appContext = context.getApplicationContext();
        this.archiveFile = archiveFile;
        this.password = PasswordChars.cloneOf(password);
        this.sensitiveCache = sensitiveCache;
        this.verifiedSensitivePaths = verifiedSensitivePaths;
    }

    /** Creates a reader for forward-readable archives only; returns null otherwise. */
    @Nullable
    static SequentialArchiveImageReader openIfSupported(@NonNull Context context,
                                                        @NonNull File archiveFile,
                                                        @Nullable char[] password,
                                                        boolean sensitiveCache,
                                                        @Nullable Set<String> verifiedSensitivePaths) {
        if (!archiveFile.exists() || !archiveFile.isFile()) return null;
        if (!ArchiveSupport.isForwardImageReadableType(archiveFile)) return null;
        return new SequentialArchiveImageReader(context, archiveFile, password, sensitiveCache, verifiedSensitivePaths);
    }

    /**
     * Ensures the cache file for an entry exists, using a forward reader for sequential
     * archives and always falling back to whole-archive extraction. A non-null sessionReader
     * keeps the stream open across calls; pass null for a one-shot extraction.
     */
    static ArchiveSupport.ExtractionResult ensureImageReady(@NonNull Context context,
                                    @NonNull File archiveFile,
                                    @NonNull String entryPath,
                                    @NonNull File outFile,
                                    @Nullable char[] password,
                                    boolean sensitiveCache,
                                    @Nullable Set<String> verifiedSensitivePaths,
                                    @Nullable SequentialArchiveImageReader sessionReader) {
        if (ArchiveImageEntryCache.isReadyImageFileForHandoff(entryPath, outFile)) {
            return ArchiveSupport.ExtractionResult.success();
        }
        if (ArchiveSupport.isForwardImageReadableType(archiveFile)) {
            SequentialArchiveImageReader reader = sessionReader;
            SequentialArchiveImageReader ownReader = null;
            if (reader == null) {
                ownReader = openIfSupported(context, archiveFile, password, sensitiveCache, verifiedSensitivePaths);
                reader = ownReader;
            }
            try {
                if (reader != null && reader.ensureExtracted(entryPath)) {
                    return ArchiveSupport.ExtractionResult.success();
                }
            } finally {
                if (ownReader != null) ownReader.close();
            }
        }
        // Forward reader missed or failed: return the whole-archive result unchanged so the real
        // failure reason (e.g. PASSWORD_REQUIRED for an encrypted RAR) reaches the caller, which
        // needs it to drive the password prompt rather than a generic failure.
        return ArchiveImageEntryCache.ensureReady(
                archiveFile, entryPath, outFile, password, sensitiveCache, verifiedSensitivePaths);
    }

    /** Returns true if the requested entry is now extracted and cached. */
    boolean ensureExtracted(@NonNull String entryPath) {
        return ensureExtracted(entryPath, true);
    }

    /**
     * Returns true if the requested entry is now extracted and cached.
     *
     * <p>{@code extractBehindFrontier} controls what happens for a page the forward stream has
     * already passed (its cache file was evicted). On-demand requests pass {@code true} so the
     * page is re-read as a single member. Background prefetch passes {@code false} so it never
     * runs a single-entry decode under the reader lock, which would block an on-demand page that
     * is waiting for the same lock; the behind page is left for the on-demand path instead.</p>
     */
    boolean ensureExtracted(@NonNull String entryPath, boolean extractBehindFrontier) {
        File outFile = ArchivePreviewCache.outputFileForEntry(appContext, archiveFile, entryPath, sensitiveCache);
        if (ArchiveImageEntryCache.isReadyImageFileForHandoff(entryPath, outFile)) {
            return true;
        }
        synchronized (lock) {
            if (closed || broken) return false;
            // Another thread may have cached this entry while we waited for the lock.
            if (ArchiveImageEntryCache.isReadyImageFileForHandoff(entryPath, outFile)) {
                return true;
            }
            if (passedEntries.contains(entryPath)) {
                // Behind the open forward stream: the entry was passed earlier and its
                // cache file has since been evicted (or it never extracted). Re-read just
                // this one member instead of re-extracting the whole archive - but only for
                // on-demand requests; prefetch skips it to keep the lock free.
                if (!extractBehindFrontier) return false;
                return extractPassedEntryLocked(entryPath, outFile);
            }
            if (exhausted) return false;
            if (!ensureOpenLocked()) return false;
            return advanceUntilLocked(entryPath);
        }
    }

    private boolean ensureOpenLocked() {
        if (reader != null) return true;
        if (openAttempted) return false;
        openAttempted = true;
        Throwable openFailure = null;
        try {
            reader = ArchiveSupport.openForwardReader(archiveFile, password);
        } catch (Throwable t) {
            reader = null;
            openFailure = t;
        }
        if (reader == null) {
            broken = true;
            // Observability: no forward reader for this archive, so paging uses the whole-archive
            // fallback. Logged at warn level so it survives the release proguard strip of i/d/v,
            // letting device testing on a minified build confirm which path actually runs.
            Log.w(TAG, "Forward image reader unavailable, using whole-archive extraction: "
                    + archiveFile.getName(), openFailure);
            return false;
        }
        Log.w(TAG, "Forward image reader engaged: " + archiveFile.getName());
        return true;
    }

    private boolean advanceUntilLocked(@NonNull String targetEntryPath) {
        ArchiveSupport.ForwardArchiveReader activeReader = reader;
        if (activeReader == null) return false;
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            ArchiveSupport.ForwardEntry entry;
            while ((entry = activeReader.nextEntry()) != null) {
                String path = entry.path;
                if (path == null || entry.directory || !entry.hasData || !FileUtils.isImageFile(path)) {
                    drainCurrentLocked(activeReader, buffer);
                    continue;
                }
                File outFile = ArchivePreviewCache.outputFileForEntry(appContext, archiveFile, path, sensitiveCache);
                boolean extracted;
                if (ArchiveImageEntryCache.isReadyImageFileForHandoff(path, outFile)) {
                    // Already cached, but the stream must still be consumed to advance.
                    drainCurrentLocked(activeReader, buffer);
                    extracted = true;
                } else {
                    extracted = extractCurrentLocked(activeReader, path, outFile, buffer);
                }
                // Mark this image as passed (cached, freshly extracted, or unreadable):
                // the stream has moved beyond it, so a later request must not advance.
                passedEntries.add(path);
                if (path.equals(targetEntryPath)) return extracted;
            }
            exhausted = true;
            return false;
        } catch (IOException | RuntimeException e) {
            // A mid-entry failure leaves the stream misaligned; abandon the reader so the
            // caller falls back to whole-archive extraction.
            broken = true;
            closeReaderLocked();
            // Observability: this is the case device testing most needs to see - the forward
            // reader started but libarchive could not decode some entry (e.g. an encryption or
            // compression variant), so the rest of the archive degrades to whole-archive.
            Log.w(TAG, "Forward image reader failed mid-archive, falling back to whole-archive: "
                    + archiveFile.getName(), e);
            return false;
        }
    }

    private void drainCurrentLocked(@NonNull ArchiveSupport.ForwardArchiveReader activeReader,
                                    @NonNull byte[] buffer) throws IOException {
        long total = 0L;
        int read;
        while ((read = activeReader.read(buffer)) > 0) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new IOException("Sequential archive entry exceeds the extraction safety limit");
            }
        }
    }

    private boolean extractCurrentLocked(@NonNull ArchiveSupport.ForwardArchiveReader activeReader,
                                         @NonNull String path,
                                         @NonNull File outFile,
                                         @NonNull byte[] buffer) throws IOException {
        File parent = outFile.getParentFile();
        if (parent == null) {
            drainCurrentLocked(activeReader, buffer);
            return false;
        }
        if (!parent.exists() && !parent.mkdirs()) {
            drainCurrentLocked(activeReader, buffer);
            return false;
        }
        File tmpFile = File.createTempFile("seq_archive_image_", ".extracting", parent);
        boolean committed = false;
        try {
            long total = 0L;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmpFile))) {
                int read;
                while ((read = activeReader.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_ENTRY_BYTES) {
                        throw new IOException("Sequential archive entry exceeds the extraction safety limit");
                    }
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            committed = ArchiveImageEntryCache.commitReadyImageFile(
                    path, tmpFile, outFile, sensitiveCache, verifiedSensitivePaths);
            return committed;
        } finally {
            if (!committed) deleteQuietly(tmpFile);
        }
    }

    /**
     * Extracts a single entry that the forward stream has already passed. Opens a
     * fresh archive stream for just this member (independent of the open forward
     * reader), so it costs one decode for that page instead of re-extracting the whole
     * archive. Returns false on any failure, leaving the caller to fall back.
     */
    private boolean extractPassedEntryLocked(@NonNull String entryPath, @NonNull File outFile) {
        File parent = outFile.getParentFile();
        if (parent == null) return false;
        if (!parent.exists() && !parent.mkdirs()) return false;
        File tmpFile;
        try {
            tmpFile = File.createTempFile("seq_archive_back_", ".extracting", parent);
        } catch (IOException e) {
            return false;
        }
        boolean committed = false;
        try {
            ArchiveSupport.ExtractionResult result =
                    ArchiveSupport.extractSingleEntryDetailed(archiveFile, entryPath, tmpFile, password);
            if (!result.success) {
                return false;
            }
            committed = ArchiveImageEntryCache.commitReadyImageFile(
                    entryPath, tmpFile, outFile, sensitiveCache, verifiedSensitivePaths);
            return committed;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (!committed) deleteQuietly(tmpFile);
        }
    }

    private void closeReaderLocked() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
            reader = null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            closeReaderLocked();
            PasswordChars.clear(password);
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file == null) return;
        try {
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (SecurityException ignored) {
        }
    }
}
