package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Random-access view over the logical byte stream of an archive that may span
 * several split volumes (used by EGG, ALZ and the special-coder 7z forward reader).
 *
 * <p>Split ALZip-family archives are a plain byte-level cut of one logical
 * archive; each physical volume contributes a [offset, offset+length) window
 * of its bytes (for EGG: the first volume whole and later volumes after their
 * own header prefixes; for ALZ: segments minus their per-segment headers and
 * trailers). Chain validation and window computation belong to the readers;
 * standard split 7z contributes each physical file without trimming. This class
 * only concatenates the segments and exposes the
 * {@link RandomAccessFile}-like subset the parsers need, so the single-file
 * and multi-volume cases read identically. Data that straddles a volume
 * boundary is handled by the segment-crossing read loop.</p>
 *
 * <p>Positional reads ({@link #readAt}) are synchronized on this object, so a
 * bounded {@link InputStream} handed to a decompressor can share the view with
 * the sequential parser. Bounded views own their cursors and close state, not
 * the volume handles. Physical I/O failures and cancellation retire the owner;
 * caller range errors do not. At most one physical volume is kept open; file
 * length and modification time are rechecked when switching volumes. This bounds
 * descriptor use for large EGG/ALZ/7z chains. Physical/logical bounds are not integrity checks
 * and cannot detect every concurrent modification of the backing files.</p>
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

    private final File[] files;
    private final long[] physicalLengths;
    private final long[] modifiedTimes;
    private RandomAccessFile activeFile;
    private int activeSegment = -1;
    private final long[] segStart;   // logical start offset of each segment
    private final long[] segOffset;  // physical payload offset within each file
    private final long totalLength;
    private long position;
    private boolean closed;
    private IOException failure;
    private final byte[] singleByte = new byte[1]; // Access only while holding this reader's lock.

    SplitVolumeInput(@NonNull List<Segment> segments) throws IOException {
        if (segments.isEmpty()) throw new IOException("split volume set is empty");
        files = new File[segments.size()];
        physicalLengths = new long[segments.size()];
        modifiedTimes = new long[segments.size()];
        segStart = new long[segments.size()];
        segOffset = new long[segments.size()];
        long total = 0L;
        boolean ok = false;
        try {
            for (int i = 0; i < segments.size(); i++) {
                checkReadable();
                Segment seg = segments.get(i);
                if (seg.dataOffset < 0 || seg.length < 0) throw new IOException("Invalid split volume segment");
                files[i] = seg.file.getCanonicalFile();
                if (!files[i].isFile() || !files[i].canRead()) throw new IOException("Split volume unavailable");
                long physicalLength;
                try (RandomAccessFile checking = new RandomAccessFile(files[i], "r")) {
                    physicalLength = checking.length();
                }
                physicalLengths[i] = physicalLength;
                modifiedTimes[i] = files[i].lastModified();
                if (seg.dataOffset > physicalLength || seg.length > physicalLength - seg.dataOffset) {
                    throw new IOException("Split volume segment exceeds physical file bounds");
                }
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

    synchronized long getFilePointer() {
        return position;
    }

    synchronized void seek(long pos) throws IOException {
        checkReadable();
        if (pos < 0) throw new IOException("Negative split-volume seek");
        position = pos;
    }

    synchronized int read() throws IOException {
        int value = readByteAt(position);
        if (value >= 0) position++;
        return value;
    }

    private synchronized int readByteAt(long pos) throws IOException {
        int count = readAt(pos, singleByte, 0, 1);
        return count < 0 ? -1 : (singleByte[0] & 0xff);
    }

    synchronized int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        int n = readAt(position, buffer, offset, length);
        if (n > 0) position += n;
        return n;
    }

    synchronized void readFully(@NonNull byte[] buffer) throws IOException {
        checkReadable();
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
        if (pos < 0) throw new IOException("Negative split-volume read offset");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        checkReadable();
        if (length == 0) return 0;
        if (pos >= totalLength) return -1;
        int done = 0;
        int seg = segmentFor(pos);
        try {
            while (done < length && seg < files.length) {
                checkReadable();
                long segLen = segmentLength(seg);
                long inSeg = pos - segStart[seg];
                if (inSeg >= segLen) { seg++; continue; }
                int want = (int) Math.min((long) (length - done), segLen - inSeg);
                RandomAccessFile raf = fileFor(seg);
                raf.seek(segOffset[seg] + inSeg);
                int n = raf.read(buffer, offset + done, want);
                if (n <= 0) throw new IOException("Split volume shorter than expected");
                done += n;
                pos += n;
                if (n < want) break; // A short OS read is valid; the next read still checks bounds.
            }
        } catch (IOException error) {
            // A caller buffer may contain a partial prefix: never allow a retry to replay it.
            throw retire(error);
        }
        return done;
    }

    private long segmentLength(int seg) {
        long next = (seg + 1 < segStart.length) ? segStart[seg + 1] : totalLength;
        return next - segStart[seg];
    }

    private int segmentFor(long pos) {
        // Upper bound selects the last equal start, skipping zero-length segments correctly.
        int low = 0, high = segStart.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (segStart[middle] <= pos) low = middle + 1;
            else high = middle;
        }
        return Math.max(0, low - 1);
    }

    /** Bounded stream over a [offset, offset+length) window of the logical stream. */
    @NonNull
    synchronized InputStream boundedStream(long offset, long length) throws IOException {
        checkReadable();
        if (offset < 0 || length < 0 || offset > totalLength || length > totalLength - offset) {
            throw new IOException("Invalid split-volume segment window");
        }
        return new BoundedStream(offset, length);
    }

    private final class BoundedStream extends InputStream {
        private long pos;
        private long remaining;
        private boolean streamClosed;

        BoundedStream(long offset, long length) {
            this.pos = offset;
            this.remaining = length;
        }

        @Override
        public synchronized int read() throws IOException {
            checkStreamReadable();
            if (remaining == 0) return -1;
            int value = readByteAt(pos);
            if (value < 0) throw retireUnexpectedEof();
            pos++;
            remaining--;
            return value;
        }

        @Override
        public synchronized int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
            checkStreamReadable();
            if (length == 0) return 0;
            if (remaining <= 0) return -1;
            int want = (int) Math.min((long) length, Math.min(remaining, Integer.MAX_VALUE));
            int n = readAt(pos, buffer, offset, want);
            if (n < 0) throw retireUnexpectedEof();
            if (n > 0) {
                pos += n;
                remaining -= n;
            }
            return n;
        }

        @Override public synchronized long skip(long count) throws IOException {
            checkStreamReadable();
            // Raw positional skip only: container/decoder layers must verify payload integrity.
            long skipped = Math.min(Math.max(0L, count), remaining);
            pos += skipped;
            remaining -= skipped;
            return skipped;
        }

        @Override public synchronized int available() throws IOException {
            checkStreamReadable();
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }

        @Override public synchronized void close() { streamClosed = true; }

        private void checkStreamReadable() throws IOException {
            if (streamClosed) throw new IOException("Split-volume bounded stream closed");
            synchronized (SplitVolumeInput.this) { checkReadable(); }
        }

        private IOException retireUnexpectedEof() {
            synchronized (SplitVolumeInput.this) {
                return retire(new IOException("Truncated split-volume bounded stream"));
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeQuietly();
    }

    private void checkReadable() throws IOException {
        if (closed) throw new IOException("Split volume set closed");
        if (failure != null) throw new IOException("Split volume reader is unusable after failure", failure);
        if (Thread.currentThread().isInterrupted()) {
            throw retire(new InterruptedIOException("Split volume read cancelled"));
        }
    }

    private IOException retire(IOException error) {
        if (failure == null) failure = error;
        closeQuietly();
        return error;
    }

    private RandomAccessFile fileFor(int segment) throws IOException {
        if (activeSegment == segment) return activeFile;
        closeQuietly();
        File file = files[segment];
        if (!file.isFile() || file.length() != physicalLengths[segment]
                || file.lastModified() != modifiedTimes[segment]) {
            throw new IOException("Split volume changed during read: " + file.getName());
        }
        activeFile = new RandomAccessFile(file, "r");
        if (activeFile.length() != physicalLengths[segment]) {
            throw new IOException("Split volume size changed during open");
        }
        activeSegment = segment;
        return activeFile;
    }

    private void closeQuietly() {
        if (activeFile != null) {
            try { activeFile.close(); } catch (IOException ignored) { }
            activeFile = null;
        }
        activeSegment = -1;
    }
}
