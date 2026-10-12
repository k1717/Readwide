package com.readwide.manager.archive;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveException;

/** One streaming Zstandard coder, using the already bundled Android native library. */
final class LibarchiveZstdInputStream extends InputStream {
    private static final int BLOCK_SIZE = 64 * 1024;
    private final InputStream input;
    // The input callback's buffer must stay valid until libarchive requests the next block.
    private final byte[] scratch = new byte[BLOCK_SIZE];
    private final ByteBuffer inputBlock = ByteBuffer.allocateDirect(BLOCK_SIZE);
    private final ByteBuffer outputBlock = ByteBuffer.allocateDirect(BLOCK_SIZE);
    private final byte[] single = new byte[1];
    private long archive;
    private boolean sawInput, eof, closed;
    private IOException failure;

    private LibarchiveZstdInputStream(InputStream input) {
        this.input = input;
        outputBlock.limit(0);
    }

    /** Owns input after construction starts, including native-open failure cleanup. */
    static InputStream open(InputStream input) throws IOException {
        if (input == null) throw new NullPointerException("input");
        LibarchiveZstdInputStream stream = null;
        try {
            stream = new LibarchiveZstdInputStream(input);
            stream.openNative();
            return stream;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (stream != null) stream.close(); else input.close();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void openNative() throws IOException {
        checkCancelled();
        if (!LibarchiveNativeBridge.isAvailable()) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("Native Zstandard decoder is unavailable");
        }
        archive = Archive.readNew();
        // Force exactly one filter. Auto-bidding can recursively decode a .zst file
        // stored as the payload, corrupting the bytes the 7z coder must return.
        Archive.readAppendFilter(archive, Archive.FILTER_ZSTD);
        Archive.readSupportFormatRaw(archive);
        Archive.readSupportFormatEmpty(archive);
        Archive.readSetCallbackData(archive, this);
        Archive.readSetReadCallback(archive,
                (ignored, source) -> ((LibarchiveZstdInputStream) source).readInput());
        Archive.readOpen1(archive);
        eof = Archive.readNextHeader(archive) == 0L;
        // A valid empty frame is allowed; a missing packed frame is not.
        if (!sawInput) throw new EOFException("Missing 7z Zstandard frame");
    }

    private ByteBuffer readInput() throws ArchiveException {
        try {
            checkCancelled();
            int count = input.read(scratch);
            if (count == 0 || count < -1 || count > scratch.length) {
                throw new IOException("Invalid or zero-progress Zstandard input read");
            }
            inputBlock.clear();
            if (count > 0) {
                sawInput = true;
                inputBlock.put(scratch, 0, count);
            }
            inputBlock.flip();
            return inputBlock;
        } catch (IOException e) {
            throw new ArchiveException(Archive.ERRNO_FATAL, "Zstandard input read failed", e);
        }
    }

    @Override public int read() throws IOException {
        return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
    }

    @Override public int read(byte[] data, int offset, int length) throws IOException {
        if (data == null) throw new NullPointerException("data");
        if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("Zstandard stream is closed");
        if (failure != null) throw failure;
        if (length == 0) return 0;
        try {
            checkCancelled();
            if (!outputBlock.hasRemaining()) {
                if (eof) return -1;
                outputBlock.clear();
                Archive.readData(archive, outputBlock);
                outputBlock.flip();
                if (!outputBlock.hasRemaining()) {
                    eof = true;
                    return -1;
                }
            }
            int count = Math.min(length, outputBlock.remaining());
            outputBlock.get(data, offset, count);
            return count;
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        long handle = archive;
        archive = 0L;
        // Free native callbacks before releasing their Java input. No native close
        // callback is registered, so ownership cannot be duplicated on open failure.
        try {
            if (handle != 0L) Archive.readFree(handle);
        } catch (IOException | RuntimeException | Error failure) {
            try { input.close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        input.close();
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("7z extraction cancelled");
    }
}
