package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/** Bounded archive packed input adapter; Commons still owns all BZip2 decoding. */
final class ArchiveBzip2InputStream extends InputStream {
    private final FileOperationProgress progress;
    private final InputStream decoder;
    private final byte[] single = new byte[1];
    private Throwable failure;
    private boolean closed;

    // The enclosing container owns source if construction fails. This adapter
    // adopts it only on successful return, as the previous Commons call did.
    ArchiveBzip2InputStream(InputStream source, FileOperationProgress progress) throws IOException {
        this.progress = progress;
        Objects.requireNonNull(source, "source");
        ArchiveCancellation.checkpoint(progress);
        // BitInputStream requests scalar bytes. Buffer after decryption, inside
        // the bounded entry/block; never prefetch the next block's cipher state.
        InputStream packed = new BufferedInputStream(new FilterInputStream(source) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                ArchiveCancellation.checkpoint(progress);
                int count = in.read(bytes, offset, length);
                if (count < -1 || count > length || (length > 0 && count == 0)) {
                    throw new IOException("Invalid BZip2 packed input progress");
                }
                ArchiveCancellation.checkpoint(progress);
                return count;
            }
        }, 64 * 1024);
        decoder = new BZip2CompressorInputStream(packed, true);
    }

    @Override public int read() throws IOException {
        int count = read(single, 0, 1);
        return count < 0 ? -1 : single[0] & 255;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if ((offset | length) < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException();
        checkReadable();
        if (length == 0) return 0;
        try {
            ArchiveCancellation.checkpoint(progress);
            int count = decoder.read(bytes, offset, length);
            ArchiveCancellation.checkpoint(progress);
            return count;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        }
    }

    private void checkReadable() throws IOException {
        if (closed) throw new IOException("BZip2 stream is closed");
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Override public int available() throws IOException {
        checkReadable();
        return decoder.available();
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { decoder.close(); }
        finally { single[0] = 0; }
    }
}
