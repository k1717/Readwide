package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Random-access view over the logical byte stream of an archive that may span
 * several split volumes (used by the EGG and ALZ readers).
 *
 * <p>Split ALZip-family archives are a plain byte-level cut of one logical
 * archive; each physical volume contributes a [offset, offset+length) window
 * of its bytes (for EGG: the first volume whole and later volumes after their
 * own header prefixes; for ALZ: segments minus their per-segment headers and
 * trailers). Chain validation and window computation belong to the readers;
 * this class only concatenates the segments and exposes the
 * {@link RandomAccessFile}-like subset the parsers need, so the single-file
 * and multi-volume cases read identically. Data that straddles a volume
 * boundary is handled by the segment-crossing read loop.</p>
 *
 * <p>Positional reads ({@link #readAt}) are synchronized on this object, so a
 * bounded {@link InputStream} handed to a decompressor can share the view with
 * the sequential parser.</p>
 */
final class SplitVolumeInput implements Closeable {

    /** One physical volume's contribution to the logical stream. */
    static final class Segment {
        final File file;
        final long dataOffset; // physical offset of this volume's payload
        final long length;     // payload length (file length - dataOffset)

        Segment(@NonNull File file, long dataOffset, long length) {
            this.file = file;
            this.dataOffset = dataOffset;
            this.length = length;
        }
    }

    private final RandomAccessFile[] files;
    private final long[] segStart;   // logical start offset of each segment
    private final long[] segOffset;  // physical payload offset within each file
    private final long totalLength;
    private long position;
    private boolean closed;

    SplitVolumeInput(@NonNull List<Segment> segments) throws IOException {
        if (segments.isEmpty()) throw new IOException("split volume set is empty");
        files = new RandomAccessFile[segments.size()];
        segStart = new long[segments.size()];
        segOffset = new long[segments.size()];
        long total = 0L;
        boolean ok = false;
        try {
            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                if (seg.dataOffset < 0 || seg.length < 0) throw new IOException("Invalid split volume segment");
                files[i] = new RandomAccessFile(seg.file, "r");
                segStart[i] = total;
                segOffset[i] = seg.dataOffset;
                if (Long.MAX_VALUE - total < seg.length) throw new IOException("Split volume set too large");
                total += seg.length;
            }
            ok = true;
        } finally {
            if (!ok) closeQuietly();
        }
        totalLength = total;
    }

    long length() {
        return totalLength;
    }

    long getFilePointer() {
        return position;
    }

    void seek(long pos) throws IOException {
        if (pos < 0) throw new IOException("Negative split-volume seek");
        position = pos;
    }

    int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xff);
    }

    int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        int n = readAt(position, buffer, offset, length);
        if (n > 0) position += n;
        return n;
    }

    void readFully(@NonNull byte[] buffer) throws IOException {
        int done = 0;
        while (done < buffer.length) {
            int n = read(buffer, done, buffer.length - done);
            if (n < 0) throw new IOException("Unexpected EOF in split volume set");
            done += n;
        }
    }

    int readUnsignedByte() throws IOException {
        int v = read();
        if (v < 0) throw new IOException("Unexpected EOF in split volume set");
        return v;
    }

    /**
     * Positional read from the logical stream. Returns the number of bytes
     * read, or -1 at end of stream. Crosses segment boundaries as needed.
     */
    synchronized int readAt(long pos, @NonNull byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new IOException("Split volume set closed");
        if (pos < 0) throw new IOException("Negative split-volume read offset");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        if (pos >= totalLength) return -1;
        int done = 0;
        int seg = segmentFor(pos);
        while (done < length && seg < files.length) {
            long segLen = segmentLength(seg);
            long inSeg = pos - segStart[seg];
            if (inSeg >= segLen) { seg++; continue; }
            int want = (int) Math.min((long) (length - done), segLen - inSeg);
            RandomAccessFile raf = files[seg];
            int n;
            synchronized (raf) {
                raf.seek(segOffset[seg] + inSeg);
                n = raf.read(buffer, offset + done, want);
            }
            if (n < 0) throw new IOException("Split volume shorter than expected");
            done += n;
            pos += n;
            if (n < want) break; // short read from the OS; return what we have
        }
        return done;
    }

    private long segmentLength(int seg) {
        long next = (seg + 1 < segStart.length) ? segStart[seg + 1] : totalLength;
        return next - segStart[seg];
    }

    private int segmentFor(long pos) {
        // Volumes are few (typically < 100); a linear scan is fine and simple.
        for (int i = segStart.length - 1; i >= 0; i--) {
            if (pos >= segStart[i]) return i;
        }
        return 0;
    }

    /** Bounded stream over a [offset, offset+length) window of the logical stream. */
    @NonNull
    InputStream boundedStream(long offset, long length) throws IOException {
        if (offset < 0 || length < 0) throw new IOException("Invalid split-volume segment window");
        return new BoundedStream(offset, length);
    }

    private final class BoundedStream extends InputStream {
        private long pos;
        private long remaining;

        BoundedStream(long offset, long length) {
            this.pos = offset;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) return -1;
            int want = (int) Math.min((long) length, Math.min(remaining, Integer.MAX_VALUE));
            int n = readAt(pos, buffer, offset, want);
            if (n > 0) {
                pos += n;
                remaining -= n;
            }
            return n;
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        for (RandomAccessFile raf : files) {
            if (raf == null) continue;
            try {
                raf.close();
            } catch (IOException ignored) {
            }
        }
    }
}
