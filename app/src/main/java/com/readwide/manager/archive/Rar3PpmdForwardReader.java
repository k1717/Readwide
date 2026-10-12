package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns a forward-reading RAR3/RAR4 PPMd session and its verified-entry spool.
 * Routing and checked payload decoding stay in {@link Rar3PpmdSolidArchiveExtractor}.
 * Unread compressed entries still advance and verify the shared solid model.
 */
final class Rar3PpmdForwardReader implements ArchiveSupport.ForwardArchiveReader {
    private final List<RarArchiveReader.RarEntry> entries;
    private final RarVolumeChain.Index chains;
    private final File directory;
    private final char[] password;
    private Rar3PpmdSolidStreamDecoder decoder;
    private RarArchiveReader.RarEntry current;
    private int index;
    private File spool;
    private java.io.InputStream input;
    private long verifiedSize, remainingBytes;
    private boolean decoded, failed, closed;

    Rar3PpmdForwardReader(List<RarArchiveReader.RarEntry> entries, char[] password, File directory,
            RarVolumeChain.Index chains) {
        this.entries = new ArrayList<>(entries); this.directory = directory;
        this.chains = chains;
        this.password = password == null ? null : password.clone();
    }

    private void checkpoint() throws IOException {
        if (closed || failed) throw new IOException("RAR3 PPMd forward reader is closed or failed");
        if (Thread.currentThread().isInterrupted()) {
            IOException failure = new IOException("RAR extraction cancelled");
            retire(failure);
            throw failure;
        }
    }

    @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
        checkpoint();
        drainCurrentEntry(Long.MAX_VALUE);
        do { current = index < entries.size() ? entries.get(index++) : null; }
        while (current != null && current.splitBefore);
        decoded = false;
        return current == null ? null : new ArchiveSupport.ForwardEntry(
                current.path, current.directory, !current.directory);
    }

    private void decode(boolean retain) throws IOException {
        checkpoint();
        if (current == null || current.directory || decoded) return;
        try {
            List<RarArchiveReader.RarEntry> chain = chains.chain(current);
            boolean stored = RarFeatureClassifier.isRar3Or4StoredMethod(current.method);
            if (!stored && !current.solid) decoder = new Rar3PpmdSolidStreamDecoder();
            if (!stored && decoder == null) throw new IOException("Missing RAR3 PPMd solid primer");
            if (retain || stored) {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create RAR spool directory");
                spool = File.createTempFile("rar3_ppmd_verified_", ".spool", directory);
                if (stored) RarArchiveReader.extractStoredEntry(current, spool, password, chain, null);
                else Rar3PpmdSolidArchiveExtractor.writeVerifiedEntry(decoder, current, chain, password, spool, null);
                if (retain) {
                    verifiedSize = remainingBytes = spool.length();
                    input = new java.io.BufferedInputStream(new java.io.FileInputStream(spool));
                } else clearSpool();
            } else {
                // Skipping output must still advance the model/window and check the primer CRC.
                Rar3PpmdSolidArchiveExtractor.discardVerifiedEntry(decoder, current, chain, password, null);
            }
            decoded = true;
        } catch (IOException | RuntimeException | Error failure) {
            retire(failure);
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IOException("RAR3 PPMd forward decode failed", failure);
        }
    }

    @Override public int read(byte[] buffer) throws IOException {
        checkpoint();
        if (buffer.length == 0) return 0;
        decode(true);
        try {
            if (input == null) return -1;
            if (spool.length() != verifiedSize) throw new IOException("Truncated or changed verified RAR spool");
            if (remainingBytes == 0) return -1;
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remainingBytes));
            if (count <= 0) throw new IOException("Truncated verified RAR spool");
            remainingBytes -= count;
            return count;
        } catch (IOException | RuntimeException | Error failure) {
            retire(failure);
            throw failure;
        }
    }

    @Override public boolean drainCurrentEntry(long maximum) throws IOException {
        checkpoint();
        if (current != null && !current.directory && !decoded && current.unpackedSize > maximum) {
            throw new IOException("RAR entry exceeds requested drain bound");
        }
        decode(false);
        try { clearSpool(); }
        catch (IOException | RuntimeException | Error failure) { retire(failure); throw failure; }
        return true;
    }

    private void retire(Throwable failure) {
        failed = true;
        try { close(); }
        catch (IOException | RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    private void clearSpool() throws IOException {
        Throwable failure = null;
        try { if (input != null) { input.close(); input = null; } }
        catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; }
        finally { remainingBytes = verifiedSize = 0; }
        if (spool != null) {
            try {
                if (spool.exists() && !spool.delete()) throw new IOException("Cannot delete verified RAR spool");
                spool = null;
            } catch (IOException | RuntimeException | Error cleanup) {
                Throwable deletion = cleanup instanceof SecurityException
                        ? new IOException("Cannot delete verified RAR spool", cleanup) : cleanup;
                if (failure == null) failure = deletion;
                else if (failure != deletion) failure.addSuppressed(deletion);
            }
        }
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Override public void close() throws IOException {
        if (closed && spool == null && input == null) return;
        closed = true;
        decoder = null;
        if (password != null) java.util.Arrays.fill(password, '\0');
        clearSpool();
    }
}
