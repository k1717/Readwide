package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Raw Deflate stream with deterministic native inflater cleanup, including failed closes. */
final class OwnedDeflateInputStream extends InflaterInputStream {
    private boolean released;
    private Throwable failure;
    OwnedDeflateInputStream(InputStream source) { super(java.util.Objects.requireNonNull(source, "source"), new Inflater(true), 64 * 1024); }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        java.util.Objects.requireNonNull(buffer, "buffer");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        if (released) throw new IOException("Deflate stream is closed");
        rethrowFailure();
        if (length == 0) return 0;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Deflate decoding cancelled");
            return super.read(buffer, offset, length);
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        }
    }
    @Override public int available() throws IOException {
        if (released) throw new IOException("Deflate stream is closed");
        rethrowFailure();
        return super.available();
    }
    private void rethrowFailure() throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
    @Override protected void fill() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Deflate decoding cancelled");
        // InflaterInputStream.fill() passes the source count to setInput()
        // before returning. Validate here so a broken source cannot produce an
        // unchecked bounds exception or poison the inflater state first.
        int count = in.read(buf, 0, buf.length);
        if (count == -1) throw new java.io.EOFException("Unexpected end of Deflate input");
        if (count <= 0 || count > buf.length) throw new IOException("Invalid or zero-progress Deflate source read");
        len = count;
        inf.setInput(buf, 0, len);
    }
    @Override public void close() throws IOException {
        if (released) return;
        released = true;
        try { super.close(); }
        finally { inf.end(); }
    }
}
