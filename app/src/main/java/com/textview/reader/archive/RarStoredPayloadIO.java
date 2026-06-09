package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.zip.CRC32;

/** Shared byte-copy and CRC helpers for stored RAR payloads. */
final class RarStoredPayloadIO {
    private static final int BUFFER_SIZE = 1024 * 64;

    private RarStoredPayloadIO() {}

    static void copyPlainEntryToFile(@NonNull RandomAccessFile raf,
                                     long size,
                                     @NonNull File outFile,
                                     @Nullable FileOperationProgress progress) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            copyToStream(raf, size, out, progress);
            out.flush();
        }
    }

    static void copySegmentsToFile(@NonNull List<RarCryptoStreams.EncryptedSegment> segments,
                                   @NonNull File outFile,
                                   @Nullable FileOperationProgress progress) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            for (RarCryptoStreams.EncryptedSegment segment : segments) {
                if (segment == null) continue;
                try (RandomAccessFile raf = new RandomAccessFile(segment.archive, "r")) {
                    raf.seek(segment.offset);
                    copyToStream(raf, segment.encryptedSize, out, progress);
                }
            }
            out.flush();
        }
    }

    static void copyToStream(@NonNull RandomAccessFile raf,
                             long size,
                             @NonNull OutputStream out,
                             @Nullable FileOperationProgress progress) throws IOException {
        if (size < 0L) throw new IOException("Invalid RAR stored payload size");
        long remaining = size;
        byte[] buffer = new byte[BUFFER_SIZE];
        while (remaining > 0L) {
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            int request = (int) Math.min(buffer.length, remaining);
            int read = raf.read(buffer, 0, request);
            if (read < 0) throw new EOFException("Unexpected EOF in RAR entry");
            out.write(buffer, 0, read);
            remaining -= read;
            if (progress != null) progress.addDoneBytes(read);
        }
    }

    static void verifyCrc(@NonNull RarArchiveReader.RarEntry entry,
                          @NonNull File outFile) throws IOException {
        if (entry.dataCrc < 0) return;
        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream in = new FileInputStream(outFile)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc32.update(buffer, 0, read);
            }
        }
        if ((crc32.getValue() & 0xffffffffL) != entry.dataCrc) {
            try { outFile.delete(); } catch (SecurityException ignored) {}
            if (entry.encrypted()) throw new ArchiveSupport.PasswordRequiredException();
            throw new IOException("RAR entry CRC mismatch");
        }
    }
}
