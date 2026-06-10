package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Bounded InputStream over a RandomAccessFile segment. Archive decoders use this
 * to avoid loading a whole compressed member into memory before streaming it into
 * a decompressor. The default constructor owns and closes the RandomAccessFile;
 * the package-private shared constructor leaves the caller-owned file open.
 */
final class RandomAccessFileBoundedInputStream extends InputStream {
    private final RandomAccessFile raf;
    private final boolean closeRaf;
    private long position;
    private long remaining;
    private boolean closed;

    RandomAccessFileBoundedInputStream(@NonNull RandomAccessFile raf,
                                       long offset,
                                       long length) throws IOException {
        this(raf, offset, length, true);
    }

    RandomAccessFileBoundedInputStream(@NonNull RandomAccessFile raf,
                                       long offset,
                                       long length,
                                       boolean closeRaf) throws IOException {
        if (offset < 0L || length < 0L) throw new IOException("Invalid archive segment");
        this.raf = raf;
        this.closeRaf = closeRaf;
        this.position = offset;
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int read = read(one, 0, 1);
        return read <= 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (buffer == null) throw new NullPointerException("buffer");
        if ((offset | length) < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        if (remaining <= 0L) return -1;
        int toRead = (int) Math.min((long) length, Math.min(remaining, Integer.MAX_VALUE));
        int read;
        synchronized (raf) {
            raf.seek(position);
            read = raf.read(buffer, offset, toRead);
        }
        if (read > 0) {
            position += read;
            remaining -= read;
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (closeRaf) raf.close();
        }
    }
}
