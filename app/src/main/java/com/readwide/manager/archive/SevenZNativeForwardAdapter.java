package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/** Owns a private spool for 7z coders/behaviors that need the dedicated container path. */
final class SevenZNativeForwardAdapter implements ArchiveSupport.ForwardArchiveReader {
    private final ArchiveSupport.ForwardArchiveReader delegate;
    private final File directory;
    private boolean closed, delegateClosed, directoryRemoved;

    static ArchiveSupport.ForwardArchiveReader open(String[] paths, char[] password) throws IOException {
        if (paths.length == 0 || (password != null && password.length != 0)) return null;
        File archive = new File(paths[0]);
        String name = archive.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".7z") && !name.endsWith(".cb7")
                && !SevenZSplitVolumeResolver.isSevenZSplitPart(archive)) return null;
        // Create a private directory only after the parser confirms an affected coder.
        String temporary = System.getProperty("java.io.tmpdir");
        if (temporary == null || temporary.isEmpty()) return null;
        File directory = new File(temporary, "readwide_7z_forward_" + UUID.randomUUID());
        ArchiveSupport.ForwardArchiveReader reader =
                SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive, password, directory);
        if (reader == null) return null;
        boolean created = false;
        try {
            if (!directory.mkdir()) throw new IOException("Cannot create private 7z spool directory");
            created = true;
            return new SevenZNativeForwardAdapter(reader, directory);
        } catch (IOException | RuntimeException | Error failure) {
            try { reader.close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            // Only remove an empty directory owned by this attempt.
            try {
                if (created && directory.isDirectory() && !directory.delete())
                    throw new IOException("Cannot remove 7z spool directory");
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private SevenZNativeForwardAdapter(ArchiveSupport.ForwardArchiveReader delegate, File directory) {
        this.delegate = delegate;
        this.directory = directory;
    }

    @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
        if (closed) return null;
        try {
            ArchiveSupport.ForwardEntry entry = delegate.nextEntry();
            if (entry == null) close();
            return entry;
        } catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
    }
    @Override public int read(byte[] buffer) throws IOException {
        if (closed) return -1;
        try { return delegate.read(buffer); }
        catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
    }
    @Override public boolean drainCurrentEntry(long maximum) throws IOException {
        if (closed) return true;
        try { return delegate.drainCurrentEntry(maximum); }
        catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
    }
    @Override public boolean skipsUnreadEntryOnAdvance() { return true; }

    private void retire(Throwable failure) {
        try { close(); }
        catch (IOException | RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }
    @Override public void close() throws IOException {
        if (closed && delegateClosed && directoryRemoved) return;
        closed = true;
        Throwable failure = null;
        // Reads remain retired while callers can retry unfinished cleanup.
        if (!delegateClosed) {
            try { delegate.close(); delegateClosed = true; }
            catch (IOException | RuntimeException | Error e) { failure = e; }
        }
        if (!directoryRemoved) {
            try {
                if (directory.exists() && !directory.delete()) throw new IOException("Cannot remove 7z spool directory");
                directoryRemoved = true;
            } catch (IOException | RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
}
