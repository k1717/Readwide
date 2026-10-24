package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.util.List;
import com.readwide.manager.util.FileOperationProgress;

/** Connects bounded AES/split input to the existing independent LZ/PPMd decoder. */
final class Rar3IndependentPayloadExtractor {
    private Rar3IndependentPayloadExtractor() {}

    static boolean isCandidate(RarArchiveReader.RarEntry entry) {
        return entry != null && RarFeatureClassifier.hasRar3DecoderVersion(entry) && !entry.directory
                && !entry.solid && !entry.splitBefore && entry.sourceArchive != null
                && (entry.encrypted() || entry.splitAfter)
                && (!entry.encrypted() || entry.encryption.isRar4Aes())
                && RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method);
    }

    static boolean tryExtract(RarArchiveReader.RarEntry entry,
            List<RarArchiveReader.RarEntry> entries, File destination, char[] password,
            FileOperationProgress progress) throws IOException {
        if (!isCandidate(entry)) return false;
        if (Thread.currentThread().isInterrupted() || (progress != null && !progress.checkpoint())) {
            throw new IOException("RAR extraction cancelled");
        }
        // One open/decrypt operation supplies both the mode probe and decoder. No
        // temporary decrypted archive, extra key derivation or whole-payload array.
        try (Rar3PackedPayload payload = Rar3PackedPayload.open(entry, entries, password, progress)) {
            payload.input.mark(2);
            int first = payload.input.read();
            int second = payload.input.read();
            payload.input.reset();
            if (first < 0 || second < 0) throw new IOException("Truncated RAR compressed start");
            boolean ppmd = (first & 0x80) != 0;
            if (ppmd && (first & 0x20) == 0) return false; // No prior model for an independent entry.
            Rar3UnpackContext context = Rar3UnpackContext.forLogicalPayload(entry, payload);
            if (ppmd) context.requireFileBoundary();
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(destination)) {
                Rar3Unpacker.unpack(context, destination, progress);
                guard.commit();
            }
            return true;
        }
    }
}
