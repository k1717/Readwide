package com.readwide.manager.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

final class UriCopyIntegrity {
    private UriCopyIntegrity() {}

    static final class Metadata {
        static final Metadata UNKNOWN = new Metadata(-1L, 0L);

        final long size;
        final long lastModified;

        Metadata(long size, long lastModified) {
            this.size = size >= 0L ? size : -1L;
            this.lastModified = Math.max(0L, lastModified);
        }
    }

    static long copy(InputStream input, OutputStream output, Metadata before, long limit)
            throws IOException {
        checkCancelled();
        if (limit < 0L || before.size > limit) {
            throw new IOException("External file exceeds size limit");
        }
        long maximum = before.size >= 0L ? before.size : limit;
        long copied = 0L;
        byte[] buffer = new byte[8192];
        while (true) {
            checkCancelled();
            // Read one byte past the bound to detect growth without writing it.
            long remaining = maximum - copied;
            int requested = remaining < buffer.length ? (int) remaining + 1 : buffer.length;
            int read = input.read(buffer, 0, requested);
            if (read < 0) break;
            if (read == 0) {
                checkCancelled();
                int single = input.read();
                if (single < 0) break;
                buffer[0] = (byte) single;
                read = 1;
            }
            checkCancelled();
            if (read > remaining) {
                throw new IOException(before.size >= 0L
                        ? "External file changed during copy" : "External file exceeds size limit");
            }
            output.write(buffer, 0, read);
            copied += read;
        }
        checkCancelled();
        requireLength(before, copied);
        return copied;
    }

    static void verify(Metadata before, Metadata after, long copied) throws IOException {
        checkCancelled();
        requireLength(before, copied);
        requireLength(after, copied);
        if ((before.size >= 0L && after.size < 0L)
                || (before.lastModified > 0L && before.lastModified != after.lastModified)) {
            throw new IOException("External file changed or cannot be verified after copy");
        }
    }

    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("URI copy cancelled");
        }
    }

    private static void requireLength(Metadata metadata, long copied) throws IOException {
        if (metadata.size >= 0L && metadata.size != copied) {
            throw new IOException("External file length changed during copy");
        }
    }
}
