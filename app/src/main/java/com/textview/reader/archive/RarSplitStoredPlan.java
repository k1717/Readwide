package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validated plan for the limited first-party stored split RAR path. */
final class RarSplitStoredPlan {
    enum Kind {
        PLAIN_STORED,
        RAR4_AES_STORED,
        RAR5_AES_STORED
    }

    private final List<RarArchiveReader.RarEntry> chain;
    private final Kind kind;
    private final long totalPackedSize;
    private final long unpackedSize;

    private RarSplitStoredPlan(@NonNull List<RarArchiveReader.RarEntry> chain,
                               @NonNull Kind kind,
                               long totalPackedSize,
                               long unpackedSize) {
        this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
        this.kind = kind;
        this.totalPackedSize = totalPackedSize;
        this.unpackedSize = unpackedSize;
    }

    @NonNull
    static RarSplitStoredPlan fromFirstEntry(@NonNull RarArchiveReader.RarEntry first,
                                             @NonNull List<RarArchiveReader.RarEntry> allEntries) throws IOException {
        List<RarArchiveReader.RarEntry> chain = RarVolumeChain.build(first, allEntries);
        return fromChain(chain);
    }

    @NonNull
    static RarSplitStoredPlan fromChain(@NonNull List<RarArchiveReader.RarEntry> chain) throws IOException {
        if (chain.isEmpty()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Empty RAR split chain");
        }
        if (!RarVolumeChain.isComplete(chain)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Incomplete RAR split payload");
        }

        RarArchiveReader.RarEntry first = chain.get(0);
        RarArchiveReader.RarEntry last = RarVolumeChain.last(chain);
        boolean encrypted = first.encrypted();
        Kind kind = classifyKind(first);
        long totalPackedSize = 0L;

        for (int i = 0; i < chain.size(); i++) {
            RarArchiveReader.RarEntry part = chain.get(i);
            validateCommonPart(first, part, i, chain.size(), encrypted);
            validateKind(kind, first.encryption, part);
            if (Long.MAX_VALUE - totalPackedSize < part.packedSize) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("RAR split stored payload is too large");
            }
            totalPackedSize += part.packedSize;
        }

        long unpackedSize = last.unpackedSize;
        if (unpackedSize < 0L) {
            throw new IOException("Invalid RAR split stored unpacked size");
        }
        return new RarSplitStoredPlan(chain, kind, totalPackedSize, unpackedSize);
    }

    @NonNull
    List<RarArchiveReader.RarEntry> chain() {
        return chain;
    }

    @NonNull
    Kind kind() {
        return kind;
    }

    boolean encrypted() {
        return kind != Kind.PLAIN_STORED;
    }

    long totalPackedSize() {
        return totalPackedSize;
    }

    long unpackedSize() {
        return unpackedSize;
    }

    @NonNull
    RarArchiveReader.RarEntry crcEntry() throws IOException {
        return RarVolumeChain.last(chain);
    }

    @NonNull
    List<RarCryptoStreams.EncryptedSegment> payloadSegments() throws IOException {
        return RarVolumeChain.payloadSegments(chain);
    }

    private static void validateCommonPart(@NonNull RarArchiveReader.RarEntry first,
                                           @NonNull RarArchiveReader.RarEntry part,
                                           int index,
                                           int count,
                                           boolean encrypted) throws IOException {
        if (part.directory) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Directory entry cannot be a RAR split payload part");
        }
        if (part.solid) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Solid RAR split payload is not supported in the stored split path");
        }
        if (!isStoredMethod(part)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR compressed split payload is not supported by the stored split path");
        }
        if (!part.path.equals(first.path)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR split continuation path mismatch");
        }
        if (part.rarVersion != first.rarVersion) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR split version changed between volumes");
        }
        if (part.method != first.method) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR split method changed between volumes");
        }
        if (encrypted != part.encrypted()) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Mixed encrypted and plain RAR split payload is not supported");
        }
        if (part.packedSize < 0L || part.dataOffset < 0L) {
            throw new IOException("Invalid RAR split stored segment bounds");
        }
        if (part.sourceArchive == null) {
            throw new IOException("RAR entry source volume is missing");
        }

        if (count == 1) {
            if (part.splitBefore || part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid one-part RAR split chain");
            }
        } else if (index == 0) {
            if (part.splitBefore || !part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid first RAR split part flags");
            }
        } else if (index == count - 1) {
            if (!part.splitBefore || part.splitAfter) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid last RAR split part flags");
            }
        } else if (!part.splitBefore || !part.splitAfter) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid middle RAR split part flags");
        }
    }

    private static void validateKind(@NonNull Kind kind,
                                     @Nullable RarArchiveReader.EncryptionInfo expected,
                                     @NonNull RarArchiveReader.RarEntry part) throws IOException {
        switch (kind) {
            case PLAIN_STORED:
                if (part.encrypted()) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "Mixed encrypted and plain RAR split payload is not supported");
                }
                return;
            case RAR4_AES_STORED:
                if (expected == null || !RarVolumeChain.sameRar4Encryption(expected, part.encryption)) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "RAR3/RAR4 encrypted split parameters changed between volumes");
                }
                return;
            case RAR5_AES_STORED:
                if (expected == null || !RarVolumeChain.sameRar5Encryption(expected, part.encryption)) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException(
                            "RAR5 encrypted split parameters changed between volumes");
                }
                return;
            default:
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Encrypted split RAR payload is not supported yet");
        }
    }

    @NonNull
    private static Kind classifyKind(@NonNull RarArchiveReader.RarEntry first) throws IOException {
        if (!first.encrypted()) return Kind.PLAIN_STORED;
        RarArchiveReader.EncryptionInfo encryption = first.encryption;
        if (encryption == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Encrypted split RAR payload is missing encryption metadata");
        }
        if (first.rarVersion < 5 && encryption.isRar4Aes()) return Kind.RAR4_AES_STORED;
        if (encryption.isRar5Aes256()) return Kind.RAR5_AES_STORED;
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "Encrypted split RAR payload is not supported yet");
    }

    private static boolean isStoredMethod(@NonNull RarArchiveReader.RarEntry entry) {
        if (entry.rarVersion < 5) return RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
        return entry.method == 0;
    }
}
