package com.readwide.manager.archive;

import androidx.annotation.NonNull;

/**
 * Per-entry RAR backend diagnostic table.
 *
 * <p>This class is intentionally metadata-only. It does not extract anything and does not make
 * archive-wide support claims. Split/solid eligibility also depends on the archive-level
 * plans, which can admit cases not decided by this single-entry table. libarchive is the
 * primary backend; first-party Java provides stored and compressed fallback decoders.</p>
 */
final class RarBackendRouter {
    private RarBackendRouter() {}

    @NonNull
    static RarBackendDecision decideEntry(@NonNull RarArchiveReader.RarEntry entry) {
        if (entry.directory) {
            return RarBackendDecision.firstParty(
                    RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED,
                    "directory metadata is handled without compressed payload decoding");
        }
        if (entry.rarVersion >= 5) {
            if (entry.method == 0) {
                if (entry.splitBefore || entry.splitAfter) {
                    return RarBackendDecision.firstParty(
                            RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED_SPLIT,
                            "RAR5 stored split payload uses first-party chain, size and checksum checks");
                }
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED,
                        "RAR5 stored payload uses the first-party stored decoder");
            }
            if (entry.encrypted()) {
                // AES-encrypted RAR5 compressed payloads have no libarchive path
                // because the bundled backend does not decrypt RAR5, so the
                // first-party Rar5 AES-decrypt + decompress path is the only route;
                // it covers the bounded RAR5-container v0/v1 decode subset and cleanly
                // reports anything outside it as unsupported.
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR5_COMPRESSED,
                        "RAR5 encrypted compressed payload uses the first-party AES decoder for algorithm versions 0/1; the bundled libarchive does not decrypt RAR5");
            }
            return RarBackendDecision.libarchive(
                    "RAR5 compressed payload is libarchive-primary, with first-party fallback for algorithm versions 0/1");
        }

        if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
            if (entry.splitBefore || entry.splitAfter) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED_SPLIT,
                        "RAR3/RAR4 stored split payload uses first-party chain, size and checksum checks");
            }
            if (entry.encrypted() && !RarFeatureClassifier.isFirstPartyRar3Or4EncryptedStoredEntry(entry)) {
                return RarBackendDecision.unsupported(
                        RarBackendRoute.Kind.CLEAN_UNSUPPORTED_ENCRYPTED_COMPRESSED,
                        "RAR3/RAR4 stored entry uses unsupported encryption metadata");
            }
            return RarBackendDecision.firstParty(
                    RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED,
                    "RAR3/RAR4 stored payload is handled by the first-party stored path");
        }

        if (entry.encrypted()) {
            if (RarFeatureClassifier.isRar3Or4EncryptedCompressedSplitRewriteCandidate(entry)) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_ENCRYPTED_COMPRESSED_SPLIT_REWRITE,
                        "RAR3/RAR4 encrypted compressed split candidate: rewrite/decrypt route, then independent AES/split LZ/PPMd decoding");
            }
            if (RarFeatureClassifier.isRar3Or4EncryptedRewriteCandidate(entry)) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_REWRITE,
                        "RAR3/RAR4 encrypted candidate: rewrite/decrypt route, then independent AES LZ/PPMd decoding");
            }
            return RarBackendDecision.unsupported(
                    RarBackendRoute.Kind.CLEAN_UNSUPPORTED_ENCRYPTED_COMPRESSED,
                    "No independent encrypted-entry route selected here; AES/split solid entries require archive-level planning");
        }

        if (entry.splitBefore || entry.splitAfter) {
            if (RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(entry)) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_COMPRESSED_SPLIT_REWRITE,
                        "RAR3/RAR4 compressed split candidate: rewrite route, then independent split LZ/PPMd decoding");
            }
            return RarBackendDecision.unsupported(
                    RarBackendRoute.Kind.CLEAN_UNSUPPORTED_COMPRESSED_SPLIT,
                    "No independent split-entry route selected here; solid entries require archive-level planning");
        }

        if (entry.solid) {
            if (Rar3PpmdBlockProbe.isPpmdPayload(entry)) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_SOLID_SEQUENTIAL,
                        "RAR3/RAR4 solid PPMd candidate; archive-level planning checks predecessors, split parts and encryption");
            }
            return RarBackendDecision.unsupported(
                    RarBackendRoute.Kind.CLEAN_UNSUPPORTED_SOLID,
                    "Solid LZ/mixed-mode decoding requires an archive-level history plan, not this entry-only classifier");
        }

        if (Rar3FirstPartyArchiveExtractor.isLimitedNonSolidClassicLzFallbackCandidate(entry)) {
            return RarBackendDecision.firstParty(
                    RarBackendRoute.Kind.TRY_FIRST_PARTY_CLASSIC_LZ_NON_SOLID,
                    "RAR3/RAR4 independent classic-LZ candidate; decoding checks declared size and checksum");
        }

        return RarBackendDecision.libarchive(
                "RAR3/RAR4 compressed payload uses libarchive first; archive-level fallback planning follows a backend failure");
    }
}
