package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;

/** ZIP disk-relative views over the existing one-open-volume archive channel. */
final class ZipVolumeChannels {
    private ZipVolumeChannels() {}

    static SeekableByteChannel open(List<File> files, long[] lengths) throws IOException {
        SplitSeekableByteChannel owner = new SplitSeekableByteChannel(files);
        try {
            owner.requireVolumeLengths(lengths);
            List<SeekableByteChannel> views = new ArrayList<>(files.size());
            long start = 0;
            for (long length : lengths) {
                if (length < 0 || length > Long.MAX_VALUE - start) throw new IOException("Invalid ZIP volume length");
                views.add(new Window(owner, start, length));
                start += length;
            }
            if (owner.size() != start) throw new IOException("ZIP volumes changed before open");
            // Keep Commons' split signature and disk-to-logical-offset handling.
            return new ZipSplitReadOnlySeekableByteChannel(views);
        } catch (IOException | RuntimeException | Error failure) {
            try { owner.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static final class Window implements SeekableByteChannel {
        private final SplitSeekableByteChannel owner;
        private final long start, length;
        private long position;
        private boolean closed;

        Window(SplitSeekableByteChannel owner, long start, long length) {
            this.owner = owner; this.start = start; this.length = length;
        }
        private void checkOpen() throws IOException {
            if (closed || !owner.isOpen()) throw new ClosedChannelException();
            owner.position(); // Also propagate cancellation and a prior physical-read failure.
        }
        @Override public int read(ByteBuffer output) throws IOException {
            synchronized (owner) {
                checkOpen();
                if (output.isReadOnly()) throw new IllegalArgumentException("Read-only destination buffer");
                if (!output.hasRemaining()) return 0;
                if (position >= length) return -1;
                int limit = output.limit();
                try {
                    output.limit(output.position() + (int) Math.min(output.remaining(), length - position));
                    owner.position(start + position);
                    int count = owner.read(output);
                    if (count < 0) throw new IOException("Truncated ZIP volume");
                    position += count;
                    return count;
                } finally { output.limit(limit); }
            }
        }
        @Override public long position() throws IOException { synchronized (owner) { checkOpen(); return position; } }
        @Override public SeekableByteChannel position(long value) throws IOException {
            synchronized (owner) {
                checkOpen();
                if (value < 0) throw new IllegalArgumentException("Negative ZIP volume position");
                position = value; return this;
            }
        }
        @Override public long size() throws IOException { synchronized (owner) { checkOpen(); return length; } }
        @Override public boolean isOpen() { synchronized (owner) { return !closed && owner.isOpen(); } }
        @Override public int write(ByteBuffer input) throws IOException { checkOpen(); throw new NonWritableChannelException(); }
        @Override public SeekableByteChannel truncate(long value) throws IOException { checkOpen(); throw new NonWritableChannelException(); }
        @Override public void close() throws IOException {
            synchronized (owner) { closed = true; owner.close(); }
        }
    }
}
