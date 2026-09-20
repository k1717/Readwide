package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Raw Deflate stream with deterministic native inflater cleanup, including failed closes. */
final class OwnedDeflateInputStream extends InflaterInputStream {
    private boolean released;
    private IOException failure;
    OwnedDeflateInputStream(InputStream source) { super(java.util.Objects.requireNonNull(source, "source"), new Inflater(true), 64 * 1024); }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        java.util.Objects.requireNonNull(buffer, "buffer");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        if (released) throw new IOException("Deflate stream is closed");
        if (failure != null) throw failure;
        if (length == 0) return 0;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Deflate decoding cancelled");
            return super.read(buffer, offset, length);
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }
    @Override public int available() throws IOException {
        if (released) throw new IOException("Deflate stream is closed");
        if (failure != null) throw failure;
        return super.available();
    }
    @Override protected void fill() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Deflate decoding cancelled");
        super.fill();
        if (len == 0 || len > buf.length) throw new IOException("Invalid or zero-progress Deflate source read");
    }
    @Override public void close() throws IOException {
        if (released) return;
        released = true;
        try { super.close(); }
        finally { inf.end(); }
    }
}
