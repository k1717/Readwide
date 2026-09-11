package com.readwide.manager.archive;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import com.readwide.manager.util.FileOperationProgress;

/** One logical RAR3/RAR4 packed stream, including AES and ordered split segments. */
final class Rar3PpmdPayload implements AutoCloseable {
    final InputStream input;
    final RarArchiveReader.RarEntry checksumEntry;

    private Rar3PpmdPayload(InputStream input, RarArchiveReader.RarEntry checksumEntry) {
        this.input = input;
        this.checksumEntry = checksumEntry;
    }

    static Rar3PpmdPayload open(RarArchiveReader.RarEntry first,
            List<RarArchiveReader.RarEntry> entries, char[] password,
            FileOperationProgress progress) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
        if (first.rarVersion != 4 || first.directory || first.splitBefore) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR3 PPMd logical entry head");
        }
        List<RarArchiveReader.RarEntry> chain = first.splitAfter
                ? RarVolumeChain.build(first, entries) : java.util.Collections.singletonList(first);
        long packedSize = 0;
        for (int i = 0; i < chain.size(); i++) {
            RarArchiveReader.RarEntry part = chain.get(i);
            if (part.rarVersion != 4 || part.directory || part.method != first.method
                    || part.solid != first.solid || part.encrypted() != first.encrypted()
                    || part.packedSize < 0 || part.unpackedSize != first.unpackedSize
                    || (i > 0 && !part.splitBefore)
                    || (first.encrypted() && !RarVolumeChain.sameRar4Encryption(first.encryption, part.encryption))) {
                throw new IOException("Inconsistent RAR3 PPMd split payload");
            }
            if (part.packedSize > Long.MAX_VALUE - packedSize) throw new IOException("RAR packed size overflow");
            packedSize += part.packedSize;
        }
        RarArchiveReader.RarEntry last = RarVolumeChain.last(chain);
        if (last.splitAfter || last.unpackedSize < 0 || last.dataCrc < 0) {
            throw new IOException("RAR3 PPMd payload is incomplete or lacks a checksum");
        }
        Cipher cipher = null;
        if (first.encrypted()) {
            if (!first.encryption.isRar4Aes()) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Unsupported RAR3 PPMd encryption");
            }
            if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
            if (packedSize % 16 != 0) throw new IOException("RAR3 PPMd AES payload is not block aligned");
            cipher = Rar3Crypto.createAesCbcDecryptCipher(password, first.encryption.salt);
        }
        InputStream raw = new BufferedInputStream(new RarPackedInputStream(
                RarVolumeChain.payloadSegments(chain), progress), 64 * 1024);
        return new Rar3PpmdPayload(cipher == null ? raw
                : new BufferedInputStream(new CipherInputStream(raw, cipher), 64 * 1024), last);
    }

    @Override public void close() throws IOException { input.close(); }
}
