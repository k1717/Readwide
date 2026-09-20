package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Objects;

/**
 * Read-only concatenation with at most ONE physical file open at a time.
 * Opening every part eagerly exhausts descriptors for large volume sets.
 * Metadata is checked on each physical open, and truncation/I/O failure retires
 * the reader. This is not a content hash or protection against every file race.
 */
final class SplitSeekableByteChannel implements SeekableByteChannel {
    private final File[] parts;
    private final long[] starts;
    private final long[] lengths;
    private final long[] modified;
    private final long size;
    private long position;
    private RandomAccessFile current;
    private int currentIndex = -1;
    private boolean open = true;
    private IOException failure;

    SplitSeekableByteChannel(List<File> volumes) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        if (volumes.isEmpty()) throw new IOException("Split archive has no volumes");
        parts = new File[volumes.size()];
        starts = new long[parts.length];
        lengths = new long[parts.length];
        modified = new long[parts.length];
        long total = 0;
        for (int i = 0; i < parts.length; i++) {
            checkReadable();
            File part = Objects.requireNonNull(volumes.get(i), "volume").getCanonicalFile();
            if (!part.isFile() || !part.canRead()) throw new IOException("Split volume unavailable: " + part.getName());
            long length = part.length();
            if (length < 0 || Long.MAX_VALUE - total < length) throw new IOException("Split archive too large");
            parts[i] = part;
            starts[i] = total;
            lengths[i] = length;
            modified[i] = part.lastModified();
            total += length;
        }
        size = total;
    }

    @Override public synchronized int read(ByteBuffer dst) throws IOException {
        Objects.requireNonNull(dst, "dst");
        checkReadable();
        if (dst.isReadOnly()) throw new IllegalArgumentException("Read-only destination buffer");
        if (!dst.hasRemaining()) return 0;
        if (position >= size) return -1;
        int total = 0;
        try {
            while (dst.hasRemaining() && position < size) {
                checkReadable();
                int index = segmentFor(position);
                select(index);
                long inPart = position - starts[index];
                int count = (int) Math.min(dst.remaining(), lengths[index] - inPart);
                int oldLimit = dst.limit();
                int read;
                try {
                    dst.limit(dst.position() + count);
                    read = current.getChannel().read(dst, inPart);
                } finally {
                    dst.limit(oldLimit);
                }
                if (read <= 0) throw new IOException("Split volume is shorter than expected: " + parts[index].getName());
                position += read;
                total += read;
            }
            return total;
        } catch (IOException error) {
            retire(error);
            throw error;
        }
    }

    private int segmentFor(long offset) {
        int low = 0, high = starts.length;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (starts[mid] <= offset) low = mid + 1; else high = mid;
        }
        return Math.max(0, low - 1); // last equal start skips empty volumes
    }

    private void select(int index) throws IOException {
        if (currentIndex == index) return;
        if (current != null) { current.close(); current = null; currentIndex = -1; }
        File part = parts[index];
        if (!part.isFile() || part.length() != lengths[index] || part.lastModified() != modified[index]) {
            throw new IOException("Split volume changed during read: " + part.getName());
        }
        current = new RandomAccessFile(part, "r");
        if (current.length() != lengths[index]) throw new IOException("Split volume size changed: " + part.getName());
        currentIndex = index;
    }

    private void checkReadable() throws IOException {
        if (!open) throw new ClosedChannelException();
        if (failure != null) throw new IOException("Split channel unusable after failure", failure);
        if (Thread.currentThread().isInterrupted()) {
            InterruptedIOException error = new InterruptedIOException("Split archive read cancelled");
            retire(error);
            throw error;
        }
    }

    private void retire(IOException error) {
        if (failure == null) failure = error;
        if (current != null) {
            try { current.close(); } catch (IOException closing) { error.addSuppressed(closing); }
            current = null;
            currentIndex = -1;
        }
    }

    @Override public synchronized long position() throws IOException { checkReadable(); return position; }
    @Override public synchronized SeekableByteChannel position(long value) throws IOException {
        checkReadable();
        if (value < 0) throw new IllegalArgumentException("Negative position");
        position = value;
        return this;
    }
    @Override public synchronized long size() throws IOException { checkReadable(); return size; }
    @Override public synchronized boolean isOpen() { return open; }
    @Override public synchronized int write(ByteBuffer src) throws IOException { checkReadable(); throw new NonWritableChannelException(); }
    @Override public synchronized SeekableByteChannel truncate(long size) throws IOException { checkReadable(); throw new NonWritableChannelException(); }
    @Override public synchronized void close() throws IOException {
        if (!open) return;
        open = false;
        if (current != null) {
            try { current.close(); } finally { current = null; currentIndex = -1; }
        }
    }
}
