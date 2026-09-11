package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
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
        try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
            copyToStream(raf, size, out, progress);
            out.flush();
        }
    }

    static void copySegmentsToFile(@NonNull List<RarCryptoStreams.EncryptedSegment> segments,
                                   @NonNull File outFile,
                                   @Nullable FileOperationProgress progress) throws IOException {
        try (RarPackedInputStream in = new RarPackedInputStream(segments, progress);
             OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (progress != null) progress.addDoneBytes(count);
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
        verifyCrc(entry, outFile, null);
    }

    static void verifyCrc(@NonNull RarArchiveReader.RarEntry entry,
                          @NonNull File outFile, @Nullable Rar5Crypto.Secrets secrets) throws IOException {
        requireSupportedDataCheck(entry);
        if (entry.dataCrc < 0 && entry.blake2sp == null) return;
        DataCheck check = new DataCheck(entry);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream in = new FileInputStream(outFile)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
                check.update(buffer, 0, read);
            }
        }
        if (!check.matches(secrets)) {
            try { outFile.delete(); } catch (SecurityException ignored) {}
            if (entry.encrypted()) throw new ArchiveSupport.PasswordRequiredException();
            throw new IOException("RAR entry checksum mismatch");
        }
    }

    static boolean crcMatches(@NonNull RarArchiveReader.RarEntry entry, long plainCrc,
                               @Nullable Rar5Crypto.Secrets secrets) throws IOException {
        requireSupportedDataCheck(entry);
        if (entry.dataCrc < 0) return true;
        long actual = plainCrc & 0xffffffffL;
        if (!hasPlaintextCrc(entry)) {
            if (secrets == null) throw new IOException("RAR5 checksum key is required");
            actual = Rar5Crypto.tweakCrc32(actual, secrets);
        }
        return actual == (entry.dataCrc & 0xffffffffL);
    }

    static boolean hasPlaintextCrc(@NonNull RarArchiveReader.RarEntry entry) {
        // Password-check data (flag 1) and key-dependent data checksums (flag 2)
        // are independent RAR5 fields. A password check must not bypass a plain CRC.
        return !(entry.rarVersion >= 5 && entry.encryption != null
                && (entry.encryption.flags & 0x0002L) != 0L);
    }

    static void requireSupportedDataCheck(@NonNull RarArchiveReader.RarEntry entry) throws IOException {
        if (entry.blake2sp != null && entry.blake2sp.length != 32) {
            throw new IOException("Invalid RAR5 BLAKE2sp checksum size");
        }
        if (!hasPlaintextCrc(entry) && entry.dataCrc < 0 && entry.blake2sp == null
                && entry.encryption.check.length != 12) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 encrypted entry has neither a supported checksum nor password-check data");
        }
    }

    /** Checks every declared supported checksum; a good CRC cannot mask a bad hash. */
    static final class DataCheck {
        private final RarArchiveReader.RarEntry entry;
        private final CRC32 crc = new CRC32();
        private final RarBlake2sp blake;

        DataCheck(RarArchiveReader.RarEntry entry) throws IOException {
            requireSupportedDataCheck(entry);
            this.entry = entry;
            blake = entry.blake2sp == null ? null : new RarBlake2sp();
        }

        void update(byte[] bytes, int offset, int length) {
            if (entry.dataCrc >= 0) crc.update(bytes, offset, length);
            if (blake != null) blake.update(bytes, offset, length);
        }

        boolean matches(@Nullable Rar5Crypto.Secrets secrets) throws IOException {
            if (!crcMatches(entry, crc.getValue(), secrets)) return false;
            if (blake == null) return true;
            byte[] actual = blake.digest();
            if (!hasPlaintextCrc(entry)) {
                if (secrets == null) throw new IOException("RAR5 checksum key is required");
                actual = Rar5Crypto.tweakBlake2sp(actual, secrets);
            }
            return java.security.MessageDigest.isEqual(actual, entry.blake2sp);
        }
    }
}
