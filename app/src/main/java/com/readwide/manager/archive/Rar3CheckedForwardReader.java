package com.readwide.manager.archive;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Verified forward fallback for the existing plain RAR4 mixed-capable decoder. */
final class Rar3CheckedForwardReader implements ArchiveSupport.ForwardArchiveReader {
    private final Rar3DecodePlan plan;
    private final Rar3FirstPartyArchiveExtractor.PlannedDecoder decoder;
    private final File directory;
    private int index;
    private RarArchiveReader.RarEntry current;
    private Rar3DecodePlan.Step currentStep;
    private File spool;
    private InputStream input;
    private long verifiedSize, remainingBytes;
    private boolean decoded;
    private boolean failed;
    private boolean closed;

    static ArchiveSupport.ForwardArchiveReader open(File archive, File directory,
                                                    boolean nativeAvailable) throws IOException {
        if (nativeAvailable || RarArchiveLocator.detectRarVersion(archive) != 4) return null;
        return open(RarArchiveReader.readEntries(archive, null), directory, false);
    }

    static ArchiveSupport.ForwardArchiveReader open(List<RarArchiveReader.RarEntry> entries,
                                                    File directory, boolean nativeAvailable) throws IOException {
        if (nativeAvailable || directory == null) return null;
        Rar3DecodePlan plan = Rar3DecodePlan.forArchive(entries);
        return plan == null ? null : new Rar3CheckedForwardReader(plan, directory);
    }

    private Rar3CheckedForwardReader(Rar3DecodePlan plan, File directory) {
        this.plan = plan;
        this.decoder = new Rar3FirstPartyArchiveExtractor.PlannedDecoder(plan);
        this.directory = directory;
    }

    private void checkpoint() throws IOException {
        if (closed || failed) throw new IOException("RAR checked forward reader is closed or failed");
        if (Thread.currentThread().isInterrupted()) {
            IOException failure = new IOException("RAR extraction cancelled");
            retire(failure);
            throw failure;
        }
    }

    @Override public ArchiveSupport.ForwardEntry nextEntry() throws IOException {
        checkpoint();
        drainCurrentEntry(Long.MAX_VALUE);
        currentStep = index < plan.size() ? plan.step(index++) : null;
        current = currentStep == null ? null : currentStep.entry;
        decoded = false;
        return current == null ? null : new ArchiveSupport.ForwardEntry(
                current.path, current.directory, !current.directory);
    }

    private void decode(boolean retain) throws IOException {
        checkpoint();
        if (current == null || decoded) return;
        try {
            if (current.directory) {
                decoder.extractNext(null, null);
                decoded = true;
                return;
            }
            boolean stored = currentStep.action == Rar3DecodePlan.Action.STORED;
            if (retain || stored) {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Cannot create RAR spool directory");
                }
                spool = File.createTempFile("rar3_checked_verified_", ".spool", directory);
                decoder.extractNext(spool, null);
                // Do not publish any bytes before the entire entry passes size/CRC checks.
                if (retain) {
                    verifiedSize = remainingBytes = spool.length();
                    input = new BufferedInputStream(new FileInputStream(spool));
                } else clearSpool();
            } else {
                // Skipped compressed pages still advance and verify solid history,
                // but do not need a decoded file or a whole-entry heap buffer.
                decoder.extractNext(null, null);
            }
            decoded = true;
        } catch (IOException | RuntimeException | Error failure) {
            retire(failure);
            throw failure;
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
        try {
            clearSpool();
        } catch (IOException failure) {
            retire(failure);
            throw failure;
        }
        return true;
    }

    private void retire(Throwable failure) {
        failed = true;
        decoder.retire();
        try { clearSpool(); }
        catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }

    private void clearSpool() throws IOException {
        IOException failure = null;
        try { if (input != null) input.close(); }
        catch (IOException error) { failure = error; }
        finally { input = null; remainingBytes = verifiedSize = 0; }
        if (spool != null) {
            if (spool.exists() && !spool.delete()) {
                IOException cleanup = new IOException("Cannot delete checked RAR spool");
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            } else spool = null;
        }
        if (failure != null) throw failure;
    }

    @Override public void close() throws IOException {
        if (closed && spool == null && input == null) return;
        closed = true;
        decoder.retire();
        clearSpool();
    }
}
