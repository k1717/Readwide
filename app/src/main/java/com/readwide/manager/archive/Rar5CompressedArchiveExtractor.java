package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Extracts compressed entries from the RAR5 container (RAR 5/6 algorithm
 * version 0 and bounded RAR 7 algorithm version 1) with the
 * first-party {@link Rar5CompressedDecoder}.
 *
 * <p>Solid semantics: a solid entry's window depends on every compressed
 * entry since the start of its solid run, so those predecessors are decoded
 * first (outputs discarded, window carried). Every primer entry must pass
 * its own CRC check before the target is attempted. Stored entries do not
 * touch the window and are skipped in chains.</p>
 *
 * <p>Failure policy: {@code tryExtract*} methods return {@code false} only
 * when this extractor does not apply; once decoding starts, any
 * inconsistency or CRC mismatch is a thrown error. No fallback output and
 * no partially written targets (guarded).</p>
 */
final class Rar5CompressedArchiveExtractor {
    private Rar5CompressedArchiveExtractor() {}

    /** Per-entry packed/unpacked guards for in-memory decoding. */
    private static final long MAX_PACKED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 256L * 1024 * 1024;

    /**
     * @return true if the entry was extracted (CRC-verified) to outFile;
     *         false if this extractor does not apply to the entry
     * @throws IOException on decode failure, CRC mismatch, or cancellation
     */
    static boolean tryExtractEntry(@NonNull RarArchiveReader.RarEntry target,
                                   @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                   @NonNull File outFile,
                                   @Nullable char[] password,
                                   @Nullable FileOperationProgress progress) throws IOException {
        List<RarArchiveReader.RarEntry> chain = buildSolidChain(target, allEntries);
        if (chain == null) {
            return false;
        }

        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        try {
            for (int i = 0; i < chain.size(); i++) {
                RarArchiveReader.RarEntry entry = chain.get(i);
                boolean isTarget = i == chain.size() - 1;
                if (progress != null && !progress.checkpoint()) {
                    throw new IOException("RAR extraction cancelled");
                }
                byte[] data = decodeOne(decoder, entry, allEntries, password);
                if (progress != null) {
                    progress.addDoneBytes(entry.unpackedSize > 0 ? entry.unpackedSize : 0);
                }
                if (isTarget) {
                    try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                        try (OutputStream out = ArchiveSupport.openExtractionOutputStream(outFile)) {
                            out.write(data);
                        }
                        verifyCrcIfPlaintext(entry, outFile);
                        guard.commit();
                    }
                    return true;
                }
            }
        } catch (Rar5CompressedDecoder.Rar5DataException e) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 decode failed: " + e.getMessage());
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR5 solid chain did not contain the target entry");
    }

    /**
     * Extracts a whole archive whose compressed members are all first-party
     * decodable RAR5-container entries (stored members are delegated to the
     * stored path). One shared decoder pass keeps solid window state
     * without re-priming per entry.
     *
     * @return true if every entry was extracted (each CRC-verified);
     *         false if this extractor does not apply to the archive
     */
    static boolean tryExtractArchive(@NonNull List<RarArchiveReader.RarEntry> entries,
                                     @NonNull File targetDir,
                                     @Nullable char[] password,
                                     @Nullable FileOperationProgress progress,
                                     @Nullable ArchiveExtractionProgressTracker entryProgress) throws IOException {
        boolean sawCompressed = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.splitBefore) {
                continue;
            }
            if (entry.rarVersion != 5) {
                return false;
            }
            if (entry.method == 0) {
                continue; // stored, handled by the stored path below
            }
            if (!isEligibleCompressed(entry)) {
                return false;
            }
            sawCompressed = true;
        }
        if (!sawCompressed) {
            return false;
        }

        Rar5CompressedDecoder decoder = new Rar5CompressedDecoder();
        boolean sawEntry = false;
        try {
            for (RarArchiveReader.RarEntry entry : entries) {
                if (entry == null || entry.splitBefore) {
                    continue;
                }
                if (progress != null && !progress.checkpoint()) {
                    return false;
                }
                if (entryProgress != null) {
                    if (entry.directory || entry.path.endsWith("/")) entryProgress.onDirectory(entry.path);
                    else entryProgress.onFile(entry.path);
                } else if (progress != null) {
                    progress.setDetail(entry.path);
                }
                File out = RarArchiveReader.resolveOutput(targetDir, entry.path);
                if (out == null) {
                    return false;
                }
                sawEntry = true;
                if (entry.directory || entry.path.endsWith("/")) {
                    if (!out.exists() && !out.mkdirs()) {
                        return false;
                    }
                    continue;
                }
                if (entry.method == 0) {
                    RarArchiveReader.extractStoredEntry(entry, out, password, entries, progress);
                    continue;
                }
                byte[] data = decodeOne(decoder, entry, entries, password);
                try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(out)) {
                    try (OutputStream fos = ArchiveSupport.openExtractionOutputStream(out)) {
                        fos.write(data);
                    }
                    verifyCrcIfPlaintext(entry, out);
                    guard.commit();
                }
                if (progress != null) {
                    progress.addDoneBytes(entry.unpackedSize > 0 ? entry.unpackedSize : 0);
                }
            }
        } catch (Rar5CompressedDecoder.Rar5DataException e) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 decode failed: " + e.getMessage());
        }
        return sawEntry;
    }

    /** Decodes one compressed entry and enforces its CRC before returning. */
    @NonNull
    private static byte[] decodeOne(@NonNull Rar5CompressedDecoder decoder,
                                    @NonNull RarArchiveReader.RarEntry entry,
                                    @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                    @Nullable char[] password) throws IOException {
        byte[] packed = readPackedPayload(entry, allEntries, password);
        byte[] data = decoder.decodeEntry(packed, entry.unpackedSize, entry.rar5CompressionInfo);
        if (entry.dataCrc >= 0 && hasPlaintextCrc(entry)) {
            CRC32 crc = new CRC32();
            crc.update(data);
            if (crc.getValue() != (entry.dataCrc & 0xFFFFFFFFL)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR5 entry failed CRC verification: " + entry.path);
            }
        }
        return data;
    }

    private static void verifyCrcIfPlaintext(@NonNull RarArchiveReader.RarEntry entry,
                                             @NonNull File outFile) throws IOException {
        if (hasPlaintextCrc(entry)) {
            RarStoredPayloadIO.verifyCrc(entry, outFile);
        }
    }

    private static boolean hasPlaintextCrc(@NonNull RarArchiveReader.RarEntry entry) {
        return !(entry.rarVersion >= 5
                && entry.encryption != null
                && entry.encryption.check.length > 0);
    }

    /**
     * Builds the ordered list of compressed entries that must be decoded —
     * the start of the target's solid run through the target itself — or
     * returns null when this extractor does not apply.
     */
    @Nullable
    private static List<RarArchiveReader.RarEntry> buildSolidChain(
            @NonNull RarArchiveReader.RarEntry target,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) {
        if (target.rarVersion != 5 || target.directory || target.method == 0) {
            return null;
        }
        if (!isEligibleCompressed(target)) {
            return null;
        }
        int targetIndex = indexOfEntry(target, allEntries);
        if (targetIndex < 0) {
            return null;
        }

        // Walk backwards to the start of the solid run: the most recent
        // compressed file entry whose solid flag is clear.
        int startIndex = -1;
        for (int i = targetIndex; i >= 0; i--) {
            RarArchiveReader.RarEntry entry = allEntries.get(i);
            if (entry == null || entry.directory || entry.method == 0) {
                continue; // stored entries do not touch the window
            }
            if (!entry.solid) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            return null; // the run start is missing
        }

        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        for (int i = startIndex; i <= targetIndex; i++) {
            RarArchiveReader.RarEntry entry = allEntries.get(i);
            if (entry == null || entry.directory || entry.method == 0) {
                continue;
            }
            if (!isEligibleCompressed(entry)) {
                return null;
            }
            chain.add(entry);
        }
        if (chain.isEmpty() || chain.get(chain.size() - 1) != target) {
            return null;
        }
        return chain;
    }

    private static boolean isEligibleCompressed(@NonNull RarArchiveReader.RarEntry entry) {
        if (entry.rarVersion != 5 || entry.directory) {
            return false;
        }
        // Encrypted entries are allowed only for RAR5 AES-256 with a usable
        // encryption record; the packed bytes are AES-CBC decrypted before
        // being handed to the decompressor. splitBefore/splitAfter are handled
        // by the volume-chain payload assembly, so they are no longer excluded.
        if (entry.encrypted()) {
            RarArchiveReader.EncryptionInfo enc = entry.encryption;
            if (enc == null || !enc.isRar5Aes256()) {
                return false;
            }
        }
        if (entry.method < 1 || entry.method > 5) {
            return false;
        }
        long info = entry.rar5CompressionInfo;
        if (!isSupportedCompressionInfo(info)) {
            return false;
        }
        if (entry.packedSize < 1 || entry.packedSize > MAX_PACKED_BYTES) {
            return false;
        }
        if (entry.unpackedSize < 0 || entry.unpackedSize > MAX_UNPACKED_BYTES) {
            return false;
        }
        return entry.sourceArchive != null;
    }

    static boolean isSupportedCompressionInfo(long info) {
        if (info < 0) {
            return false;
        }
        int algorithmVersion = (int) (info & 0x3F);
        if (algorithmVersion != 0 && algorithmVersion != 1) {
            return false;
        }
        try {
            Rar5CompressedDecoder.declaredWindowSize(info);
        } catch (Rar5CompressedDecoder.Rar5DataException invalidHeader) {
            return false;
        }
        return true;
    }

    private static int indexOfEntry(@NonNull RarArchiveReader.RarEntry target,
                                    @NonNull List<RarArchiveReader.RarEntry> allEntries) {
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the packed (compressed) bytes for an entry, decrypting them when
     * the entry is RAR5 AES-256 encrypted and stitching multi-volume split
     * payloads together. For non-encrypted single-volume entries this is a
     * plain seek+read; for everything else the volume chain is assembled and
     * (when encrypted) AES-CBC decrypted into the compressed byte stream that
     * the decompressor consumes.
     */
    /**
     * Walks back to the first part of a split entry's volume chain. The first
     * part is the entry sharing the same path that is not a split
     * continuation ({@code splitBefore == false}).
     */
    @NonNull
    private static RarArchiveReader.RarEntry resolveSplitHead(
            @NonNull RarArchiveReader.RarEntry entry,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) {
        if (!entry.splitBefore) {
            return entry;
        }
        int index = allEntries.indexOf(entry);
        for (int i = index; i >= 0; i--) {
            RarArchiveReader.RarEntry candidate = allEntries.get(i);
            if (candidate == null || candidate.directory) continue;
            if (candidate.path.equals(entry.path) && !candidate.splitBefore) {
                return candidate;
            }
        }
        return entry; // fall back; fromFirstEntry will validate completeness
    }

    @NonNull
    private static byte[] readPackedPayload(@NonNull RarArchiveReader.RarEntry entry,
                                            @NonNull List<RarArchiveReader.RarEntry> allEntries,
                                            @Nullable char[] password)
            throws IOException {
        if (entry.sourceArchive == null) {
            throw new IOException("RAR5 entry source volume is missing");
        }

        boolean split = entry.splitBefore || entry.splitAfter;
        if (!entry.encrypted() && !split) {
            byte[] packed = new byte[(int) entry.packedSize];
            try (RandomAccessFile raf = new RandomAccessFile(entry.sourceArchive, "r")) {
                raf.seek(entry.dataOffset);
                raf.readFully(packed);
            }
            return packed;
        }

        // Assemble the (possibly multi-volume) packed byte segments.
        List<RarCryptoStreams.EncryptedSegment> segments;
        long packedTotal;
        RarArchiveReader.RarEntry cryptoEntry = entry;
        if (split) {
            Rar5CompressedSplitPayload splitPayload = buildCompressedSplitPayload(
                    resolveSplitHead(entry, allEntries), allEntries);
            segments = splitPayload.segments;
            packedTotal = splitPayload.packedTotal;
            cryptoEntry = splitPayload.first;
        } else {
            segments = java.util.Collections.singletonList(
                    new RarCryptoStreams.EncryptedSegment(
                            entry.sourceArchive, entry.dataOffset, entry.packedSize));
            packedTotal = entry.packedSize;
        }
        if (packedTotal < 0 || packedTotal > MAX_PACKED_BYTES) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 packed payload is outside supported bounds");
        }

        java.io.ByteArrayOutputStream packedOut =
                new java.io.ByteArrayOutputStream((int) packedTotal);

        if (cryptoEntry.encrypted()) {
            RarArchiveReader.EncryptionInfo enc = cryptoEntry.encryption;
            if (enc == null || !enc.isRar5Aes256()) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR5 entry encryption is not AES-256");
            }
            if (password == null || password.length == 0) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
            Rar5Crypto.Secrets secrets =
                    Rar5Crypto.deriveSecrets(password, enc.kdfCount, enc.salt);
            if (!Rar5Crypto.passwordMatches(secrets, enc.check)) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
            javax.crypto.Cipher cipher = Rar5Crypto.createAesCbcDecryptCipher(secrets, enc.iv);
            // For compressed entries the plaintext length equals the encrypted
            // (block-aligned) length; the decompressor decides how much of it
            // to consume, so we keep the full decrypted compressed stream.
            RarCryptoStreams.decryptSegmentsToStream(
                    segments,
                    cipher,
                    packedOut,
                    -1L,
                    "RAR5 AES decrypt failed",
                    null,
                    false);
        } else {
            for (RarCryptoStreams.EncryptedSegment segment : segments) {
                try (RandomAccessFile raf = new RandomAccessFile(segment.archive, "r")) {
                    raf.seek(segment.offset);
                    long remaining = segment.encryptedSize;
                    byte[] buffer = new byte[8192];
                    while (remaining > 0) {
                        int n = raf.read(buffer, 0,
                                (int) Math.min(buffer.length, remaining));
                        if (n < 0) throw new IOException("RAR5 split payload truncated");
                        packedOut.write(buffer, 0, n);
                        remaining -= n;
                    }
                }
            }
        }
        return packedOut.toByteArray();
    }

    @NonNull
    private static Rar5CompressedSplitPayload buildCompressedSplitPayload(
            @NonNull RarArchiveReader.RarEntry first,
            @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        if (first.rarVersion != 5 || first.method == 0 || first.directory) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split payload expected");
        }
        List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(first, allEntries);
        if (!RarVolumeChain.isComplete(chain)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Incomplete RAR5 compressed split payload");
        }

        boolean encrypted = first.encrypted();
        long packedTotal = 0L;
        for (int i = 0; i < chain.size(); i++) {
            RarArchiveReader.RarEntry part = chain.get(i);
            validateCompressedSplitPart(first, part, i, chain.size(), encrypted);
            if (Long.MAX_VALUE - packedTotal < part.packedSize) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR5 compressed split payload is too large");
            }
            packedTotal += part.packedSize;
        }
        return new Rar5CompressedSplitPayload(
                first,
                RarVolumeChain.payloadSegments(chain),
                packedTotal);
    }

    private static void validateCompressedSplitPart(@NonNull RarArchiveReader.RarEntry first,
                                                    @NonNull RarArchiveReader.RarEntry part,
                                                    int index,
                                                    int count,
                                                    boolean encrypted) throws IOException {
        if (part.directory) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Directory entry cannot be a RAR5 compressed split payload part");
        }
        if (part.rarVersion != 5 || part.method == 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split payload expected");
        }
        if (!part.path.equals(first.path)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split continuation path mismatch");
        }
        if (part.method != first.method
                || part.rar5CompressionInfo != first.rar5CompressionInfo) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 compressed split parameters changed between volumes");
        }
        if (encrypted != part.encrypted()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Mixed encrypted and plain RAR5 compressed split payload is not supported");
        }
        if (encrypted && (first.encryption == null
                || !RarVolumeChain.sameRar5Encryption(first.encryption, part.encryption))) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR5 encrypted split parameters changed between volumes");
        }
        if (part.packedSize < 0L || part.dataOffset < 0L || part.sourceArchive == null) {
            throw new IOException("Invalid RAR5 compressed split segment bounds");
        }

        if (count == 1) {
            if (part.splitBefore || part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Invalid one-part RAR5 compressed split chain");
            }
        } else if (index == 0) {
            if (part.splitBefore || !part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Invalid first RAR5 compressed split part flags");
            }
        } else if (index == count - 1) {
            if (!part.splitBefore || part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Invalid last RAR5 compressed split part flags");
            }
        } else if (!part.splitBefore || !part.splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Invalid middle RAR5 compressed split part flags");
        }
    }

    private static final class Rar5CompressedSplitPayload {
        final RarArchiveReader.RarEntry first;
        final List<RarCryptoStreams.EncryptedSegment> segments;
        final long packedTotal;

        Rar5CompressedSplitPayload(@NonNull RarArchiveReader.RarEntry first,
                                   @NonNull List<RarCryptoStreams.EncryptedSegment> segments,
                                   long packedTotal) {
            this.first = first;
            this.segments = segments;
            this.packedTotal = packedTotal;
        }
    }
}
