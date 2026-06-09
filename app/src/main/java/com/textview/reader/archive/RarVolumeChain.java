package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RAR multi-volume helpers.
 *
 * <p>Keep split-chain discovery, segment conversion, and per-volume consistency checks
 * outside {@link RarArchiveReader}. RAR unpacking work is already complicated enough;
 * the main reader should stay a parser/router rather than becoming a multi-volume state
 * machine as each decoder pass adds more cases.</p>
 */
final class RarVolumeChain {
    private RarVolumeChain() {}

    @NonNull
    static List<RarArchiveReader.RarEntry> build(@NonNull RarArchiveReader.RarEntry first,
                                                  @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        chain.add(first);
        RarArchiveReader.RarEntry current = first;
        while (current.splitAfter) {
            RarArchiveReader.RarEntry next = nextPart(current, first, allEntries);
            if (next == null) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR split continuation");
            }
            chain.add(next);
            current = next;
        }
        return chain;
    }

    @Nullable
    private static RarArchiveReader.RarEntry nextPart(@NonNull RarArchiveReader.RarEntry current,
                                                       @NonNull RarArchiveReader.RarEntry first,
                                                       @NonNull List<RarArchiveReader.RarEntry> allEntries) {
        int currentIndex = allEntries.indexOf(current);
        for (int i = Math.max(0, currentIndex + 1); i < allEntries.size(); i++) {
            RarArchiveReader.RarEntry candidate = allEntries.get(i);
            if (candidate == null || candidate.directory) continue;
            if (candidate.splitBefore && candidate.path.equals(first.path)) return candidate;
        }
        return null;
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
            segments.add(new RarCryptoStreams.EncryptedSegment(source, part.dataOffset, part.packedSize));
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
                && expected.flags == actual.flags
                && expected.kdfCount == actual.kdfCount
                && Arrays.equals(expected.salt, actual.salt)
                && Arrays.equals(expected.iv, actual.iv);
    }
}
