package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;

/** Sequential, bounded reads across archive-volume payloads; never assembles a file in RAM. */
final class RarPackedInputStream extends InputStream {
    private final List<RarCryptoStreams.EncryptedSegment> segments;
    private final FileOperationProgress progress;
    private final Rar5Crypto.Secrets checksumSecrets;
    private RandomAccessFile file;
    private long remaining;
    private int index;
    private boolean closed;
    private IOException failure;
    private RarStoredPayloadIO.DataCheck packedCheck;
    private final byte[] single = new byte[1];

    RarPackedInputStream(List<RarCryptoStreams.EncryptedSegment> segments,
                         FileOperationProgress progress) {
        this(segments, progress, null);
    }

    RarPackedInputStream(List<RarCryptoStreams.EncryptedSegment> segments,
                         FileOperationProgress progress, Rar5Crypto.Secrets checksumSecrets) {
        this.segments = segments;
        this.progress = progress;
        this.checksumSecrets = checksumSecrets;
    }

    @Override public int read() throws IOException {
        return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (closed) throw new IOException("RAR packed stream is closed");
        if (failure != null) throw failure;
        if (bytes == null) throw new NullPointerException("bytes");
        if ((offset | length) < 0 || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
        if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
        try {
            while (remaining == 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
                closeVolume();
                if (index == segments.size()) return -1;
                RarCryptoStreams.EncryptedSegment segment = segments.get(index++);
                if (segment.offset < 0 || segment.encryptedSize < 0) {
                    throw new IOException("Invalid RAR packed segment bounds");
                }
                file = new RandomAccessFile(segment.archive, "r");
                long size = file.length();
                if (segment.offset > size || segment.encryptedSize > size - segment.offset) {
                    closeVolume();
                    throw new EOFException("RAR packed segment extends past its volume");
                }
                file.seek(segment.offset);
                remaining = segment.encryptedSize;
                packedCheck = segment.packedCheck == null ? null
                        : new RarStoredPayloadIO.DataCheck(segment.packedCheck);
                if (remaining == 0) verifyPackedCheck();
            }
            int count = file.read(bytes, offset, (int) Math.min(length, remaining));
            if (count < 0) throw new EOFException("Truncated RAR packed segment");
            remaining -= count;
            if (packedCheck != null) packedCheck.update(bytes, offset, count);
            // Fail on the boundary read, not on a later read that the caller may never make.
            if (remaining == 0) verifyPackedCheck();
            return count;
        } catch (IOException error) {
            failure = error;
            packedCheck = null;
            try { closeVolume(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private void verifyPackedCheck() throws IOException {
        if (packedCheck == null) return;
        RarStoredPayloadIO.DataCheck check = packedCheck;
        packedCheck = null;
        if (!check.matches(checksumSecrets)) throw new IOException("RAR intermediate packed-volume checksum mismatch");
    }

    private void closeVolume() throws IOException {
        if (file != null) {
            RandomAccessFile previous = file;
            file = null;
            previous.close();
        }
    }

    @Override public void close() throws IOException {
        closed = true;
        closeVolume();
    }
}
