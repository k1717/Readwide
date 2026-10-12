package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-owner RAR5/RAR7 forward-reading session for the archive image viewer.
 * Owns its decoder history, password copy and verified-entry spool. Codec and
 * split-payload validation stay in {@link Rar5CompressedArchiveExtractor}; no
 * entry bytes are exposed before that shared extraction path finishes checking them.
 */
final class Rar5ForwardReader implements ArchiveSupport.ForwardArchiveReader {
    private final List<RarArchiveReader.RarEntry> entries;
    private final RarVolumeChain.Index chains;
    private final java.util.Map<RarArchiveReader.RarEntry, Long> historyStarts;
    private final char[] password;
    private final File directory;
    private Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
    private int index;
    private RarArchiveReader.RarEntry current;
    private File spool;
    private InputStream input;
    private long verifiedSize, remainingBytes;
    private boolean decoded;
    private boolean closed;
    private boolean failed;

    Rar5ForwardReader(List<RarArchiveReader.RarEntry> entries, char[] password, File directory,
            RarVolumeChain.Index chains) throws IOException {
        this.entries = new ArrayList<>(entries);
        this.historyStarts = Rar5CompressedArchiveExtractor.planDictionaryStarts(this.entries);
        this.chains = chains;
        this.password = password == null ? null : password.clone();
        this.directory = directory;
    }

    private void checkpoint() throws IOException {
        if (closed || failed) throw new IOException("RAR forward reader is closed or failed");
        if (Thread.currentThread().isInterrupted()) {
            IOException failure = new IOException("RAR extraction cancelled");
            retire(failure);
            throw failure;
        }
    }

    @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
        checkpoint();
        drainCurrentEntry(Long.MAX_VALUE);
        clearSpool();
        current = null;
        decoded = false;
        while (index < entries.size()) {
            RarArchiveReader.RarEntry entry = entries.get(index++);
            if (entry.splitBefore) continue;
            current = entry;
            return new ArchiveSupport.ForwardEntry(entry.path, entry.directory, !entry.directory);
        }
        return null;
    }

    private void decode(boolean retain, long maximum) throws IOException {
        checkpoint();
        if (current == null || current.directory || decoded) return;
        try {
            List<RarArchiveReader.RarEntry> chain = chains.chain(current);
            // Preserve readable earlier entries; validate this file before creating its spool.
            if (current.method != 0 && current.splitAfter) Rar5CompressedArchiveExtractor.validateCompressedSplitChain(current, chain);
            if (retain || current.method == 0) {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create RAR spool directory");
                spool = File.createTempFile("rar5_verified_entry_", ".spool", directory);
                if (current.method == 0) {
                    RarArchiveReader.extractStoredEntry(current, spool, password, chain, null);
                    if (!retain && spool.length() > maximum) {
                        throw new IOException("RAR entry exceeds requested drain bound");
                    }
                } else {
                    try (OutputStream out = ArchiveSupport.openExtractionOutputStream(spool)) {
                        Rar5CompressedArchiveExtractor.decodeOne(decoder, current, chain, password, out, null,
                                historyStarts.getOrDefault(current, 0L));
                    }
                }
                if (retain) {
                    verifiedSize = remainingBytes = spool.length();
                    input = new java.io.BufferedInputStream(new java.io.FileInputStream(spool));
                } else clearSpool();
            } else {
                // Non-image predecessors still prime the solid window and verify their CRC.
                // Unknown-size entries cannot be bounded by metadata: count actual
                // decoded bytes before accepting them, without creating an output spool.
                OutputStream discard = new OutputStream() {
                    long remaining = maximum;
                    @Override public void write(int value) throws IOException {
                        if (remaining == 0) throw new IOException("RAR entry exceeds requested drain bound");
                        remaining--;
                    }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (length > remaining) throw new IOException("RAR entry exceeds requested drain bound");
                        remaining -= length;
                    }
                };
                Rar5CompressedArchiveExtractor.decodeOne(decoder, current, chain, password, discard, null,
                        historyStarts.getOrDefault(current, 0L));
            }
            decoded = true;
        } catch (IOException | RuntimeException | Error failure) {
            retire(failure); // Never reuse partially advanced solid history.
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IOException("RAR5 forward decode failed", failure);
        }
    }

    @Override public int read(byte[] buffer) throws IOException {
        checkpoint();
        if (buffer.length == 0) return 0;
        decode(true, Long.MAX_VALUE);
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
        if (maximum < 0) throw new IOException("RAR entry exceeds requested drain bound");
        if (current != null && !current.directory && !decoded) {
            // Stored plain output is exactly the sum of its packed segments,
            // even when the final size is unknown. Resolve before spooling;
            // encrypted stored tails need checksum resolution. Their lower bound
            // rejects oversized drains before spooling; the final <=15-byte
            // uncertainty is checked against verified spool length before acceptance.
            List<RarArchiveReader.RarEntry> chain = chains.chain(current);
            long size;
            if (current.method == 0) {
                RarSplitStoredPlan plan = RarSplitStoredPlan.fromChain(chain);
                size = plan.unpackedSize();
                if (size == -1) size = Math.max(0, plan.totalPackedSize() - 15);
            } else size = RarVolumeChain.last(chain).unpackedSize;
            if (size < -1 || size > maximum) {
                throw new IOException("RAR entry exceeds requested drain bound");
            }
        }
        decode(false, maximum);
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
        if (closed && spool == null && input == null && decoder == null) return;
        closed = true;
        if (password != null) java.util.Arrays.fill(password, '\0');
        Throwable failure = null;
        try {
            if (decoder != null) decoder.close();
            decoder = null;
        }
        catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; throw cleanup; }
        finally {
            try { clearSpool(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (failure == null) throw cleanup;
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
    }
}
