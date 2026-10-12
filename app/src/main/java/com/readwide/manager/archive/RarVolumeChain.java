package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
        checkpoint();
        if (first.directory || first.splitBefore) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR logical entry head");
        }
        List<RarArchiveReader.RarEntry> chain = new ArrayList<>();
        chain.add(first);
        if (!first.splitAfter) return chain;
        boolean foundHead = false;
        Set<RarArchiveReader.RarEntry> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(first);
        // Iterate rather than indexing: caller lists may be linked, and unrelated
        // damaged chains must not prevent extraction of this selected logical file.
        for (RarArchiveReader.RarEntry candidate : allEntries) {
            checkpoint();
            if (!foundHead) {
                if (candidate == first) foundHead = true;
                continue;
            }
            if (candidate == null || candidate.directory || !candidate.path.equals(first.path)) continue;
            if (!candidate.splitBefore) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Ambiguous RAR split head for repeated path");
            }
            if (!seen.add(candidate)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Repeated RAR split continuation");
            }
            chain.add(candidate);
            if (!candidate.splitAfter) return chain;
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                foundHead ? "Missing RAR split continuation" : "RAR split head is missing");
    }

    /** One topology pass for whole-archive/forward operations; no payload I/O or crypto. */
    static Index index(@NonNull List<RarArchiveReader.RarEntry> entries) throws IOException {
        Builder builder = new Builder();
        for (RarArchiveReader.RarEntry entry : entries) builder.accept(entry);
        return builder.finish();
    }

    /** Resolve only requested heads and their open tails, ignoring unrelated damaged chains. */
    static Index indexSelected(@NonNull List<RarArchiveReader.RarEntry> heads,
            @NonNull List<RarArchiveReader.RarEntry> entries) throws IOException {
        Builder builder = new Builder();
        Set<RarArchiveReader.RarEntry> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RarArchiveReader.RarEntry head : heads) {
            checkpoint();
            if (head == null || head.directory || head.splitBefore || !wanted.add(head)) {
                throw new RarArchiveReader.UnsupportedRarFeatureException("Invalid or repeated selected RAR head");
            }
        }
        int remaining = wanted.size();
        if (remaining == 0) return builder.finish();
        for (RarArchiveReader.RarEntry entry : entries) {
            checkpoint();
            if (entry == null || entry.directory) continue;
            if (wanted.contains(entry)) {
                builder.accept(entry); // A duplicate wanted identity fails before decrementing.
                remaining--;
            } else if (builder.pending.containsKey(entry.path)) {
                builder.accept(entry); // Includes conflicting new same-path heads.
            }
            if (remaining == 0 && builder.pending.isEmpty()) return builder.finish();
        }
        checkpoint();
        if (remaining != 0) throw new RarArchiveReader.UnsupportedRarFeatureException("Selected RAR head is missing");
        return builder.finish();
    }

    private static final class Builder {
        private final Map<RarArchiveReader.RarEntry, List<RarArchiveReader.RarEntry>> chains = new IdentityHashMap<>();
        private final Map<String, List<RarArchiveReader.RarEntry>> pending = new HashMap<>();
        private final Set<RarArchiveReader.RarEntry> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        void accept(RarArchiveReader.RarEntry entry) throws IOException {
            checkpoint();
            if (entry == null || entry.directory) return;
            if (!seen.add(entry)) throw new RarArchiveReader.UnsupportedRarFeatureException("Repeated RAR payload entry");
            List<RarArchiveReader.RarEntry> chain = pending.get(entry.path);
            if (entry.splitBefore) {
                if (chain == null) throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR split head");
                chain.add(entry);
                if (!entry.splitAfter) pending.remove(entry.path);
            } else {
                if (chain != null) {
                    throw new RarArchiveReader.UnsupportedRarFeatureException("Ambiguous RAR split head for repeated path");
                }
                chain = new ArrayList<>();
                chain.add(entry);
                chains.put(entry, Collections.unmodifiableList(chain));
                if (entry.splitAfter) pending.put(entry.path, chain);
            }
        }
        Index finish() throws IOException {
            checkpoint();
            if (!pending.isEmpty()) throw new RarArchiveReader.UnsupportedRarFeatureException("Missing RAR split continuation");
            return new Index(chains);
        }
    }

    /** Immutable membership/order; existing payload readers still validate metadata and bytes. */
    static final class Index {
        private final Map<RarArchiveReader.RarEntry, List<RarArchiveReader.RarEntry>> chains;
        private Index(Map<RarArchiveReader.RarEntry, List<RarArchiveReader.RarEntry>> chains) { this.chains = chains; }

        List<RarArchiveReader.RarEntry> chain(RarArchiveReader.RarEntry head) throws IOException {
            checkpoint();
            List<RarArchiveReader.RarEntry> chain = chains.get(head);
            if (chain == null) throw new RarArchiveReader.UnsupportedRarFeatureException("RAR logical head is not in the prepared index");
            return chain;
        }
    }

    private static void checkpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("RAR chain discovery cancelled");
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
