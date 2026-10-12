package com.readwide.manager.archive;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import com.readwide.manager.util.FileOperationProgress;

/** One logical RAR3/RAR4 packed stream, including AES and ordered split segments. */
final class Rar3PackedPayload implements AutoCloseable {
    final BufferedInputStream input;
    final RarArchiveReader.RarEntry checksumEntry;
    final long packedSize;

    private Rar3PackedPayload(BufferedInputStream input, RarArchiveReader.RarEntry checksumEntry,
                              long packedSize) {
        this.input = input;
        this.checksumEntry = checksumEntry;
        this.packedSize = packedSize;
    }

    static Rar3PackedPayload open(RarArchiveReader.RarEntry first,
            List<RarArchiveReader.RarEntry> entries, char[] password,
            FileOperationProgress progress) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
        if (first.rarVersion != 4 || first.directory || first.splitBefore) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR3/RAR4 logical entry head");
        }
        List<RarArchiveReader.RarEntry> chain = first.splitAfter
                ? RarVolumeChain.build(first, entries) : java.util.Collections.singletonList(first);
        long packedSize = validateMetadata(first, chain);
        RarArchiveReader.RarEntry last = RarVolumeChain.last(chain);
        Cipher cipher = null;
        if (first.encrypted()) {
            if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
            cipher = Rar3Crypto.createAesCbcDecryptCipher(password, first.encryption.salt, progress);
        }
        InputStream owned = new RarPackedInputStream(RarVolumeChain.payloadSegments(chain), progress);
        try {
            BufferedInputStream buffered = new BufferedInputStream(owned, 64 * 1024);
            owned = buffered;
            if (cipher != null) {
                owned = new CipherInputStream(owned, cipher);
                buffered = new BufferedInputStream(owned, 64 * 1024);
                owned = buffered;
            }
            return new Rar3PackedPayload(buffered, last, packedSize);
        } catch (RuntimeException | Error failure) {
            try { owned.close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Metadata only: no password work or physical volume access. */
    static long validateMetadata(RarArchiveReader.RarEntry first,
            List<RarArchiveReader.RarEntry> chain) throws IOException {
        if (first.rarVersion != 4 || first.directory || first.splitBefore) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR3/RAR4 logical entry head");
        }
        if (chain.isEmpty() || chain.get(0) != first) throw new IOException("Missing RAR3/RAR4 payload head");
        long packedSize = 0;
        for (int i = 0; i < chain.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("RAR extraction cancelled");
            RarArchiveReader.RarEntry part = chain.get(i);
            if (part.rarVersion != 4 || part.directory || part.method != first.method
                    || part.solid != first.solid || part.encrypted() != first.encrypted()
                    || part.packedSize < 0 || part.unpackedSize != first.unpackedSize
                    || (i > 0 && !part.splitBefore)
                    || (first.encrypted() && !RarVolumeChain.sameRar4Encryption(first.encryption, part.encryption))) {
                throw new IOException("Inconsistent RAR3/RAR4 split payload");
            }
            if (part.sourceArchive == null || part.dataOffset < 0) throw new IOException("Invalid RAR3/RAR4 source bounds");
            if (part.packedSize > Long.MAX_VALUE - packedSize) throw new IOException("RAR packed size overflow");
            packedSize += part.packedSize;
        }
        RarArchiveReader.RarEntry last = RarVolumeChain.last(chain);
        if (last.splitAfter || last.unpackedSize < 0 || last.dataCrc < 0 || last.dataCrc > 0xffffffffL) {
            throw new IOException("RAR3/RAR4 payload is incomplete or lacks a valid checksum");
        }
        if (first.encrypted()) {
            if (!first.encryption.isRar4Aes()) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Unsupported RAR3/RAR4 encryption");
            }
            if (packedSize % 16 != 0) throw new IOException("RAR3/RAR4 AES payload is not block aligned");
        }
        return packedSize;
    }

    /** Validate every physical segment even when a decoder reaches its output size early. */
    void finish(FileOperationProgress progress) throws IOException {
        byte[] tail = new byte[64 * 1024];
        while (true) {
            if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
                throw new IOException("RAR extraction cancelled");
            }
            int count = input.read(tail);
            if (count < 0) return;
            if (count == 0) throw new IOException("Zero-progress RAR packed read");
        }
    }

    @Override public void close() throws IOException { input.close(); }
}
