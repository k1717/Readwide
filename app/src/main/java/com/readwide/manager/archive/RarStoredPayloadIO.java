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

    /** Resolve a complete stored file; -1 requires checksum-resolved AES tail handling. */
    static long resolvedUnpackedSize(@NonNull RarArchiveReader.RarEntry last,
                                    long totalPackedSize) throws IOException {
        if (totalPackedSize < 0L) throw new IOException("Invalid RAR stored packed size");
        long size = last.unpackedSize;
        if (size == -1L && last.rarVersion == 5 && last.method == 0) {
            if (last.encrypted()) {
                requireUnknownSizeCheck(last, totalPackedSize);
                return -1L;
            }
            // Stored plain bytes have no codec framing or AES padding. The
            // complete, bounded packed payload is exactly the file content.
            size = totalPackedSize;
        }
        if (size < 0L) throw new IOException("Invalid RAR stored unpacked size");
        if (!last.encrypted() && size != totalPackedSize) {
            throw new IOException(last.splitBefore
                    ? "Stored RAR split size mismatch" : "Stored RAR size mismatch");
        }
        return size;
    }

    private static void requireUnknownSizeCheck(RarArchiveReader.RarEntry last,
                                                long packedSize) throws IOException {
        requireSupportedDataCheck(last);
        if (last.rarVersion != 5 || last.method != 0 || last.unpackedSize != -1
                || last.encryption == null || !last.encryption.isRar5Aes256()
                || last.splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid unknown-size encrypted stored RAR entry");
        }
        if (packedSize < 0 || (packedSize & 15) != 0) {
            throw new IOException("Unknown-size encrypted stored RAR payload is not AES block aligned");
        }
        if (packedSize != 0 && last.dataCrc < 0 && last.blake2sp == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Unknown final encrypted stored RAR size requires a data checksum");
        }
    }

    static void decryptUnknownSizeToFile(RandomAccessFile input, RarArchiveReader.RarEntry entry,
            javax.crypto.Cipher cipher, Rar5Crypto.Secrets secrets, File target,
            FileOperationProgress progress) throws IOException {
        requireUnknownSizeCheck(entry, entry.packedSize);
        validateSourceRange(input, entry.packedSize);
        try (OutputStream destination = ArchiveSupport.openExtractionOutputStream(target);
             UnknownSizeOutput output = new UnknownSizeOutput(destination, entry, entry.packedSize, secrets, progress)) {
            RarCryptoStreams.decryptToStream(input, entry.packedSize, cipher, output, -1,
                    "RAR5 AES decrypt failed", progress, false);
            output.finish();
        }
    }

    static void decryptUnknownSizeSegmentsToFile(List<RarCryptoStreams.EncryptedSegment> segments,
            RarArchiveReader.RarEntry last, long packedSize, javax.crypto.Cipher cipher,
            Rar5Crypto.Secrets secrets, File target, FileOperationProgress progress) throws IOException {
        requireUnknownSizeCheck(last, packedSize);
        try (OutputStream destination = ArchiveSupport.openExtractionOutputStream(target);
             UnknownSizeOutput output = new UnknownSizeOutput(destination, last, packedSize, secrets, progress)) {
            RarCryptoStreams.decryptSegmentsToStream(segments, cipher, output, -1,
                    "RAR5 AES split decrypt failed", progress, false, secrets);
            output.finish();
        }
    }

    /**
     * Keep one AES block, not the entry. Only a unique match of every declared
     * final checksum can distinguish data from 0..15 alignment bytes. Padding
     * byte values and password-check metadata never select a length.
     * The caller's output guard owns rollback; close aborts without committing.
     */
    static final class UnknownSizeOutput extends OutputStream {
        private final OutputStream destination;
        private final DataCheck check;
        private final long packedSize;
        private final Rar5Crypto.Secrets secrets;
        private final FileOperationProgress progress;
        private final byte[] tail = new byte[16];
        private final byte[] single = new byte[1];
        private int tailSize;
        private long received;
        private boolean finished;
        private Throwable failure;

        UnknownSizeOutput(OutputStream destination, RarArchiveReader.RarEntry entry,
                long packedSize, Rar5Crypto.Secrets secrets, FileOperationProgress progress) throws IOException {
            if (destination == null) throw new NullPointerException("destination");
            requireUnknownSizeCheck(entry, packedSize);
            this.destination = destination;
            this.packedSize = packedSize;
            this.secrets = secrets;
            this.progress = progress;
            check = new DataCheck(entry);
        }

        @Override public void write(int value) throws IOException {
            single[0] = (byte) value;
            write(single, 0, 1);
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || length > data.length - offset) throw new IndexOutOfBoundsException();
            try {
                ensureOpen();
                if (length > packedSize - received) throw new IOException("RAR AES output exceeds packed range");
                received += length;
                int publish = length - (tail.length - tailSize);
                if (publish > 0) {
                    int fromTail = Math.min(publish, tailSize);
                    publish(tail, 0, fromTail);
                    tailSize -= fromTail;
                    System.arraycopy(tail, fromTail, tail, 0, tailSize);
                    publish -= fromTail;
                    publish(data, offset, publish);
                    offset += publish;
                    length -= publish;
                }
                System.arraycopy(data, offset, tail, tailSize, length);
                tailSize += length;
            } catch (IOException | RuntimeException | Error error) { failure = error; throw error; }
        }

        private void publish(byte[] data, int offset, int length) throws IOException {
            if (length == 0) return;
            checkpoint(progress);
            destination.write(data, offset, length);
            check.update(data, offset, length);
            if (progress != null) progress.addDoneBytes(length);
        }

        void finish() throws IOException {
            try {
                ensureOpen();
                if (received != packedSize) throw new EOFException("Incomplete RAR AES stored payload");
                int match = -1;
                // Nonempty ciphertext has at least one data byte in the last block:
                // AES alignment padding is shorter than a full block.
                for (int count = 0; count <= tailSize; count++) {
                    checkpoint(progress);
                    if ((tailSize == 0 || count > 0) && check.matchesSnapshot(secrets)) {
                        if (match >= 0) throw new RarArchiveReader.UnsupportedRarFeatureException(
                                "Ambiguous final encrypted stored RAR size");
                        match = count;
                    }
                    if (count < tailSize) check.update(tail, count, 1);
                }
                if (match < 0) throw new ArchiveSupport.PasswordRequiredException();
                checkpoint(progress);
                destination.write(tail, 0, match);
                if (progress != null) progress.addDoneBytes(match);
                destination.flush();
                checkpoint(progress);
                finished = true;
            } catch (IOException | RuntimeException | Error error) { failure = error; throw error; }
        }

        private void ensureOpen() throws IOException {
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            if (finished) throw new IOException("RAR unknown-size output is closed");
            checkpoint(progress);
        }

        @Override public void close() {
            finished = true;
            java.util.Arrays.fill(tail, (byte) 0);
            single[0] = 0;
        }
    }

    private static void checkpoint(FileOperationProgress progress) throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new java.io.InterruptedIOException("RAR extraction cancelled");
        }
    }

    /** Validate a range without consuming bytes or overflowing offset + size. */
    static void validateSourceRange(RandomAccessFile input, long size) throws IOException {
        if (size < 0L) throw new IOException("Invalid RAR stored payload size");
        long position = input.getFilePointer();
        long length = input.length();
        if (position > length || size > length - position) {
            throw new EOFException("RAR payload range extends past its source");
        }
    }

    static void copyPlainEntryToFile(@NonNull RandomAccessFile raf,
                                     long size,
                                     @NonNull File outFile,
                                     @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        validateSourceRange(raf, size);
        try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
            copyToStream(raf, size, out, progress);
            checkpoint(progress);
            out.flush();
            checkpoint(progress);
        }
    }

    static void copySegmentsToFile(@NonNull List<RarCryptoStreams.EncryptedSegment> segments,
                                   @NonNull File outFile,
                                   @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        try (RarPackedInputStream in = new RarPackedInputStream(segments, progress);
             OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = in.read(buffer)) != -1) {
                checkpoint(progress);
                out.write(buffer, 0, count);
                if (progress != null) progress.addDoneBytes(count);
            }
            checkpoint(progress);
            out.flush();
            checkpoint(progress);
        }
    }

    static void copyToStream(@NonNull RandomAccessFile raf,
                             long size,
                             @NonNull OutputStream out,
                             @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        validateSourceRange(raf, size);
        long remaining = size;
        byte[] buffer = new byte[BUFFER_SIZE];
        while (remaining > 0L) {
            checkpoint(progress);
            int request = (int) Math.min(buffer.length, remaining);
            int read = raf.read(buffer, 0, request);
            if (read < 0) throw new EOFException("Unexpected EOF in RAR entry");
            checkpoint(progress);
            out.write(buffer, 0, read);
            remaining -= read;
            if (progress != null) progress.addDoneBytes(read);
        }
        checkpoint(progress);
    }

    static void verifyCrc(@NonNull RarArchiveReader.RarEntry entry,
                          @NonNull File outFile) throws IOException {
        verifyCrc(entry, outFile, null);
    }

    static void verifyCrc(@NonNull RarArchiveReader.RarEntry entry,
                          @NonNull File outFile, @Nullable Rar5Crypto.Secrets secrets) throws IOException {
        verifyCrc(entry, outFile, secrets, null);
    }

    static void verifyCrc(@NonNull RarArchiveReader.RarEntry entry,
                          @NonNull File outFile, @Nullable Rar5Crypto.Secrets secrets,
                          @Nullable FileOperationProgress progress) throws IOException {
        checkpoint(progress);
        requireSupportedDataCheck(entry);
        if (entry.dataCrc < 0 && entry.blake2sp == null) return;
        DataCheck check = new DataCheck(entry);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream in = new FileInputStream(outFile)) {
            while (true) {
                checkpoint(progress);
                int read = in.read(buffer);
                if (read < 0) break;
                check.update(buffer, 0, read);
            }
        }
        checkpoint(progress);
        boolean matches = check.matches(secrets);
        checkpoint(progress);
        if (!matches) {
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
            return matches(secrets, false);
        }

        boolean matchesSnapshot(@Nullable Rar5Crypto.Secrets secrets) throws IOException {
            return matches(secrets, true);
        }

        private boolean matches(@Nullable Rar5Crypto.Secrets secrets, boolean snapshot) throws IOException {
            if (!crcMatches(entry, crc.getValue(), secrets)) return false;
            if (blake == null) return true;
            byte[] actual = snapshot ? blake.snapshotDigest() : blake.digest();
            if (!hasPlaintextCrc(entry)) {
                if (secrets == null) throw new IOException("RAR5 checksum key is required");
                actual = Rar5Crypto.tweakBlake2sp(actual, secrets);
            }
            return java.security.MessageDigest.isEqual(actual, entry.blake2sp);
        }
    }
}
