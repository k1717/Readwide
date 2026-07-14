package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;

/**
 * Forward image reader backed by libarchive (via {@link LibarchiveNativeBridge}).
 *
 * <p>libarchive is a strictly forward, single-pass reader with no random access, which is exactly
 * the access pattern the shared {@link SequentialArchiveImageReader} expects: open once, iterate
 * entries forward, and decode each entry exactly once. Wrapping it here lets RAR/CBR reuse the same
 * incremental, cache-as-you-go paging path as 7z and TAR instead of re-extracting the whole archive
 * on first access and on every later cache miss.</p>
 *
 * <p>This reader is used for libarchive-eligible RAR and selected 7z paths (gated by
 * {@link ArchiveSupport#isForwardImageReadableType(java.io.File)}). Any libarchive limitation - an
 * encryption variant or a compression case it cannot decode - surfaces as an {@link IOException},
 * which makes the sequential reader abandon the stream and fall back to the existing whole-archive
 * extraction. Correctness therefore never depends on libarchive covering every entry; the forward
 * reader is a pure performance path.</p>
 */
final class LibarchiveForwardReader implements ArchiveSupport.ForwardArchiveReader {

    @NonNull
    private final LibarchiveNativeBridge.ForwardStream stream;
    @Nullable
    private final Closeable owner;

    LibarchiveForwardReader(@NonNull LibarchiveNativeBridge.ForwardStream stream) {
        this(stream, null);
    }

    LibarchiveForwardReader(@NonNull LibarchiveNativeBridge.ForwardStream stream,
                            @Nullable Closeable owner) {
        this.stream = stream;
        this.owner = owner;
    }

    @Override
    @Nullable
    public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
        LibarchiveNativeBridge.ForwardStreamEntry entry = stream.nextEntry();
        if (entry == null) {
            return null;
        }
        // Only a regular file with a safe (non-null, sanitized) path carries readable data.
        boolean hasData = entry.regularFile && entry.path != null;
        return new ArchiveSupport.ForwardEntry(entry.path, entry.directory, hasData);
    }

    @Override
    public int read(@NonNull byte[] buffer) throws IOException {
        return stream.read(buffer);
    }

    @Override
    public boolean drainCurrentEntry(long maxDecodedBytes) throws IOException {
        return stream.drainCurrentEntry(maxDecodedBytes);
    }

    @Override
    public boolean skipsUnreadEntryOnAdvance() {
        return canSkipUnreadEntryWithoutDecode();
    }

    /** Solid RAR/7z state requires decode-and-discard, not a header-only skip. */
    static boolean canSkipUnreadEntryWithoutDecode() {
        return false;
    }

    @Override
    public void close() throws IOException {
        try {
            stream.close();
        } finally {
            if (owner != null) owner.close();
        }
    }
}
