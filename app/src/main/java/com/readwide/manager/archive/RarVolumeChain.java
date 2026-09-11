package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * RAR multi-volume helpers.
 *
 * <p>Keep split-chain discovery, segment conversion, and per-volume consistency checks
 * outside {@link RarArchiveReader}. RAR unpacking work is already complicated enough;
 * the main reader should stay a parser/router rather than becoming a multi-volume state
 * machine as additional decoder cases are added.</p>
 */
final class RarVolumeChain {
    private RarVolumeChain() {}

    @NonNull
    static List<RarArchiveReader.RarEntry> build(@NonNull RarArchiveReader.RarEntry first,
                                                  @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        chain.add(first);
        int cursor = allEntries.indexOf(first) + 1;
        if (cursor == 0 && first.splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR split head is missing");
        }
        Set<RarArchiveReader.RarEntry> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(first);
        RarArchiveReader.RarEntry current = first;
        while (current.splitAfter) {
            RarArchiveReader.RarEntry next = null;
            while (cursor < allEntries.size()) {
                RarArchiveReader.RarEntry candidate = allEntries.get(cursor++);
                if (candidate == null || candidate.directory) continue;
                if (candidate.splitBefore && candidate.path.equals(first.path)) {
                    if (!seen.add(candidate)) {
                        throw new RarArchiveReader.UnsupportedRarFeatureException(
                                "Repeated RAR split continuation");
                    }
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR split continuation");
            }
            chain.add(next);
            current = next;
        }
        return chain;
    }

    static boolean isComplete(@NonNull List<RarArchiveReader.RarEntry> chain) {
        return !chain.isEmpty() && !chain.get(chain.size() - 1).splitAfter;
    }

    static boolean containsEncryptedPart(@NonNull List<RarArchiveReader.RarEntry> chain) {
        for (RarArchiveReader.RarEntry part : chain) {
            if (part != null && part.encrypted()) return true;
        }
        return false;
    }

    @NonNull
    static RarArchiveReader.RarEntry last(@NonNull List<RarArchiveReader.RarEntry> chain) throws IOException {
        if (chain.isEmpty()) throw new RarArchiveReader.UnsupportedRarFeatureException("Empty RAR split chain");
        return chain.get(chain.size() - 1);
    }

    @NonNull
    static List<RarCryptoStreams.EncryptedSegment> payloadSegments(
            @NonNull List<RarArchiveReader.RarEntry> chain) throws IOException {
        List<RarCryptoStreams.EncryptedSegment> segments = new ArrayList<>(chain.size());
        for (RarArchiveReader.RarEntry part : chain) {
            File source = part.sourceArchive;
            if (source == null) throw new IOException("RAR entry source volume is missing");
            // Intermediate checks cover bytes as stored, before any AES decryption.
            // Final members describe the decoded logical file, not this segment.
            // RAR4 uses all-one CRC as an absent intermediate-check sentinel.
            boolean absentLegacyCheck = part.rarVersion < 5 && part.dataCrc == 0xffffffffL;
            RarArchiveReader.RarEntry packedCheck = part.splitAfter && !absentLegacyCheck
                    && (part.dataCrc >= 0 || part.blake2sp != null) ? part : null;
            segments.add(new RarCryptoStreams.EncryptedSegment(source, part.dataOffset, part.packedSize,
                    packedCheck));
        }
        return segments;
    }

    static void validateStoredPart(@NonNull RarArchiveReader.RarEntry part, boolean encrypted) throws IOException {
        boolean stored = part.rarVersion < 5
                ? RarFeatureClassifier.isRar3Or4StoredMethod(part.method)
                : part.method == 0;
        if (part.directory || part.solid || !stored) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Unsupported RAR split payload");
        }
        if (encrypted != part.encrypted()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Mixed encrypted and plain RAR split payload is not supported");
        }
    }

    static boolean sameRar4Encryption(@NonNull RarArchiveReader.EncryptionInfo expected,
                                      @Nullable RarArchiveReader.EncryptionInfo actual) {
        return actual != null
                && actual.isRar4Aes()
                && Arrays.equals(expected.salt, actual.salt);
    }

    static boolean sameRar5Encryption(@NonNull RarArchiveReader.EncryptionInfo expected,
                                      @Nullable RarArchiveReader.EncryptionInfo actual) {
        return actual != null
                && actual.isRar5Aes256()
                && expected.version == actual.version
                // Flags describe per-volume password/checksum records, not the AES stream.
                && expected.kdfCount == actual.kdfCount
                && Arrays.equals(expected.salt, actual.salt)
                && Arrays.equals(expected.iv, actual.iv)
                && Arrays.equals(expected.check, actual.check);
    }
}
