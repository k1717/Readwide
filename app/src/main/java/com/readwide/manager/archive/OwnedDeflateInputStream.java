package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Raw Deflate stream with deterministic native inflater cleanup, including failed closes. */
final class OwnedDeflateInputStream extends InflaterInputStream {
    private final FileOperationProgress progress;
    private boolean released;
    private Throwable failure;
    interface Factory {
        OwnedDeflateInputStream create(InputStream source, Inflater inflater, FileOperationProgress progress);
    }

    static OwnedDeflateInputStream open(InputStream source) { return open(source, null); }

    static OwnedDeflateInputStream open(InputStream source, FileOperationProgress progress) {
        return create(source, progress, OwnedDeflateInputStream::new);
    }

    static OwnedDeflateInputStream create(InputStream source, FileOperationProgress progress, Factory factory) {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(factory, "factory");
        Inflater inflater = new Inflater(true);
        try {
            return java.util.Objects.requireNonNull(factory.create(source, inflater, progress), "stream");
        } catch (RuntimeException | Error failure) {
            // The superclass can fail while allocating its buffers. The caller
            // still owns source until construction succeeds; only release our inflater.
            try { inflater.end(); }
            catch (RuntimeException | Error closeError) {
                if (closeError != failure) failure.addSuppressed(closeError);
            }
            throw failure;
        }
    }

    private OwnedDeflateInputStream(InputStream source, Inflater inflater, FileOperationProgress progress) {
        super(source, inflater, 64 * 1024);
        this.progress = progress;
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        java.util.Objects.requireNonNull(buffer, "buffer");
        if ((offset | length) < 0 || length > buffer.length - offset) throw new IndexOutOfBoundsException();
        if (released) throw new IOException("Deflate stream is closed");
        rethrowFailure();
        if (length == 0) return 0;
        try {
            ArchiveCancellation.checkpoint(progress);
            int count = super.read(buffer, offset, length);
            ArchiveCancellation.checkpoint(progress);
            return count;
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
        ArchiveCancellation.checkpoint(progress);
        // InflaterInputStream.fill() passes the source count to setInput()
        // before returning. Validate here so a broken source cannot produce an
        // unchecked bounds exception or poison the inflater state first.
        int count = in.read(buf, 0, buf.length);
        if (count == -1) throw new java.io.EOFException("Unexpected end of Deflate input");
        if (count <= 0 || count > buf.length) throw new IOException("Invalid or zero-progress Deflate source read");
        // Empty Deflate blocks can require several refills inside one read().
        // Observe token-only cancellation before submitting more packed bytes.
        ArchiveCancellation.checkpoint(progress);
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
