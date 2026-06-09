package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;

/**
 * Small streaming helpers for RAR AES payload handling.
 *
 * <p>RAR3/RAR4 and RAR5 use different KDFs, but after the caller creates the
 * {@link Cipher}, stored encrypted file-data extraction is the same I/O problem:
 * consume all encrypted AES-CBC blocks, write only the declared plaintext length,
 * and discard trailing block padding. Keeping that flow here prevents the RAR
 * archive parser from accumulating another copy of the same byte loop.</p>
 */
final class RarCryptoStreams {
    private static final int BUFFER_SIZE = 64 * 1024;

    private RarCryptoStreams() {}

    static void decryptToFile(@NonNull RandomAccessFile in,
                              long encryptedSize,
                              long plaintextLimit,
                              @NonNull Cipher cipher,
                              @NonNull File outFile,
                              @NonNull String decryptError,
                              @Nullable FileOperationProgress progress,
                              boolean reportWrittenBytes) throws IOException {
        if (plaintextLimit < 0L) throw new IOException("Invalid decrypted RAR stored size");
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            long remaining = decryptToStream(
                    in,
                    encryptedSize,
                    cipher,
                    out,
                    plaintextLimit,
                    decryptError,
                    progress,
                    reportWrittenBytes);
            if (remaining != 0L) throw new IOException("Invalid decrypted RAR stored size");
            out.flush();
        }
    }



    static void decryptSegmentsToFile(@NonNull java.util.List<EncryptedSegment> segments,
                                      long plaintextLimit,
                                      @NonNull Cipher cipher,
                                      @NonNull File outFile,
                                      @NonNull String decryptError,
                                      @Nullable FileOperationProgress progress,
                                      boolean reportWrittenBytes) throws IOException {
        if (plaintextLimit < 0L) throw new IOException("Invalid decrypted split RAR stored size");
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            long remaining = decryptSegmentsToStream(
                    segments,
                    cipher,
                    out,
                    plaintextLimit,
                    decryptError,
                    progress,
                    reportWrittenBytes);
            if (remaining != 0L) throw new IOException("Invalid decrypted split RAR stored size");
            out.flush();
        }
    }

    static long decryptSegmentsToStream(@NonNull java.util.List<EncryptedSegment> segments,
                                        @NonNull Cipher cipher,
                                        @NonNull OutputStream out,
                                        long plaintextLimit,
                                        @NonNull String decryptError,
                                        @Nullable FileOperationProgress progress,
                                        boolean reportWrittenBytes) throws IOException {
        long totalEncryptedSize = 0L;
        for (EncryptedSegment segment : segments) {
            if (segment.encryptedSize < 0L) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Encrypted RAR split data has invalid size");
            }
            if (Long.MAX_VALUE - totalEncryptedSize < segment.encryptedSize) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Encrypted RAR split data is too large");
            }
            totalEncryptedSize += segment.encryptedSize;
        }
        if ((totalEncryptedSize % 16L) != 0L) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Encrypted RAR split data is not AES block aligned");
        }
        long remaining = plaintextLimit;
        for (EncryptedSegment segment : segments) {
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            try (RandomAccessFile in = new RandomAccessFile(segment.archive, "r")) {
                in.seek(segment.offset);
                remaining = decryptSegmentToStream(
                        in,
                        segment.encryptedSize,
                        cipher,
                        out,
                        remaining,
                        progress,
                        reportWrittenBytes);
            }
        }
        remaining = finishCipher(out, cipher, remaining, decryptError, progress, reportWrittenBytes);
        return plaintextLimit < 0L ? -1L : remaining;
    }

    /**
     * Decrypts {@code encryptedSize} bytes from {@code in} and writes them to {@code out}.
     *
     * @param plaintextLimit maximum plaintext bytes to write. Use {@code -1} to write the
     *                       full decrypted stream.
     * @return remaining bytes from {@code plaintextLimit}; always {@code 0} when the limit is -1.
     */
    static long decryptToStream(@NonNull RandomAccessFile in,
                                long encryptedSize,
                                @NonNull Cipher cipher,
                                @NonNull OutputStream out,
                                long plaintextLimit,
                                @NonNull String decryptError,
                                @Nullable FileOperationProgress progress,
                                boolean reportWrittenBytes) throws IOException {
        if (encryptedSize < 0L || (encryptedSize % 16L) != 0L) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Encrypted RAR data is not AES block aligned");
        }
        long encryptedRemaining = encryptedSize;
        long plaintextRemaining = plaintextLimit;
        byte[] input = new byte[BUFFER_SIZE];
        while (encryptedRemaining > 0L) {
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            int request = (int) Math.min(input.length, encryptedRemaining);
            int read = in.read(input, 0, request);
            if (read < 0) throw new EOFException("Unexpected EOF in encrypted RAR entry");
            byte[] chunk = cipher.update(input, 0, read);
            if (chunk != null && chunk.length > 0) {
                plaintextRemaining = writePlaintext(out, chunk, plaintextRemaining, progress, reportWrittenBytes);
            }
            encryptedRemaining -= read;
        }
        plaintextRemaining = finishCipher(out, cipher, plaintextRemaining, decryptError, progress, reportWrittenBytes);
        return plaintextLimit < 0L ? 0L : plaintextRemaining;
    }

    private static long decryptSegmentToStream(@NonNull RandomAccessFile in,
                                               long encryptedSize,
                                               @NonNull Cipher cipher,
                                               @NonNull OutputStream out,
                                               long plaintextLimit,
                                               @Nullable FileOperationProgress progress,
                                               boolean reportWrittenBytes) throws IOException {
        if (encryptedSize < 0L) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Encrypted RAR split data has invalid size");
        }
        long encryptedRemaining = encryptedSize;
        long plaintextRemaining = plaintextLimit;
        byte[] input = new byte[BUFFER_SIZE];
        while (encryptedRemaining > 0L) {
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            int request = (int) Math.min(input.length, encryptedRemaining);
            int read = in.read(input, 0, request);
            if (read < 0) throw new EOFException("Unexpected EOF in encrypted RAR split entry");
            byte[] chunk = cipher.update(input, 0, read);
            if (chunk != null && chunk.length > 0) {
                plaintextRemaining = writePlaintext(out, chunk, plaintextRemaining, progress, reportWrittenBytes);
            }
            encryptedRemaining -= read;
        }
        return plaintextLimit < 0L ? -1L : plaintextRemaining;
    }

    private static long finishCipher(@NonNull OutputStream out,
                                     @NonNull Cipher cipher,
                                     long plaintextRemaining,
                                     @NonNull String decryptError,
                                     @Nullable FileOperationProgress progress,
                                     boolean reportWrittenBytes) throws IOException {
        byte[] tail;
        try {
            tail = cipher.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IOException(decryptError, e);
        }
        if (tail != null && tail.length > 0) {
            plaintextRemaining = writePlaintext(out, tail, plaintextRemaining, progress, reportWrittenBytes);
        }
        return plaintextRemaining;
    }

    static final class EncryptedSegment {
        final File archive;
        final long offset;
        final long encryptedSize;

        EncryptedSegment(@NonNull File archive, long offset, long encryptedSize) {
            this.archive = archive;
            this.offset = offset;
            this.encryptedSize = encryptedSize;
        }
    }

    private static long writePlaintext(@NonNull OutputStream out,
                                       @NonNull byte[] data,
                                       long remaining,
                                       @Nullable FileOperationProgress progress,
                                       boolean reportWrittenBytes) throws IOException {
        int write;
        if (remaining < 0L) {
            write = data.length;
        } else if (remaining == 0L) {
            return 0L;
        } else {
            write = (int) Math.min((long) data.length, remaining);
        }
        out.write(data, 0, write);
        if (reportWrittenBytes && progress != null) progress.addDoneBytes(write);
        return remaining < 0L ? -1L : remaining - write;
    }
}
