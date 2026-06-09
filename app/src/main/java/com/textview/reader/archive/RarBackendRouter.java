package com.textview.reader.archive;

import androidx.annotation.NonNull;

/**
 * Narrow RAR backend decision table.
 *
 * <p>This class is intentionally metadata-only. It does not extract anything and does not make
 * support claims. It exists so the reader, diagnostics, and docs can share the same conservative
 * boundary: libarchive remains the normal compressed RAR backend; first-party Java handles stored
 * and the verified non-solid classic-LZ fallback only.</p>
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
                            "RAR5 stored split payload has a limited first-party path");
                }
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED,
                        "RAR5 stored payload has a limited first-party path");
            }
            return RarBackendDecision.libarchive(
                    "RAR5 compressed payload is libarchive-primary; first-party Java has no RAR5 compressed decoder");
        }

        if (RarFeatureClassifier.isRar3Or4StoredMethod(entry.method)) {
            if (entry.splitBefore || entry.splitAfter) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_STORED_SPLIT,
                        "RAR3/RAR4 stored split payload has a validated first-party stored-split path");
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
                        "RAR3/RAR4 visible-header encrypted compressed split may be decrypted/rebuilt and delegated to libarchive");
            }
            if (RarFeatureClassifier.isRar3Or4EncryptedRewriteCandidate(entry)) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_REWRITE,
                        "RAR3/RAR4 visible-header encrypted candidate may use the rewrite/decrypt helper");
            }
            return RarBackendDecision.unsupported(
                    RarBackendRoute.Kind.CLEAN_UNSUPPORTED_ENCRYPTED_COMPRESSED,
                    "RAR3/RAR4 encrypted compressed payload is not part of the FOSS default first-party decoder");
        }

        if (entry.splitBefore || entry.splitAfter) {
            if (RarFeatureClassifier.isRar3Or4CompressedSplitRewriteCandidate(entry)) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR4_COMPRESSED_SPLIT_REWRITE,
                        "RAR3/RAR4 visible-header compressed split may be rebuilt as a temporary single-volume RAR4 and delegated to libarchive");
            }
            return RarBackendDecision.unsupported(
                    RarBackendRoute.Kind.CLEAN_UNSUPPORTED_COMPRESSED_SPLIT,
                    "RAR3/RAR4 compressed split payload is not a verified first-party fallback");
        }

        if (entry.solid) {
            return RarBackendDecision.unsupported(
                    RarBackendRoute.Kind.CLEAN_UNSUPPORTED_SOLID,
                    "RAR3/RAR4 compressed solid payload remains diagnostic/scaffold-only without a real eligible classic-LZ fixture matrix");
        }

        if (Rar3FirstPartyArchiveExtractor.isLimitedNonSolidClassicLzFallbackCandidate(entry)) {
            return RarBackendDecision.firstParty(
                    RarBackendRoute.Kind.TRY_FIRST_PARTY_CLASSIC_LZ_NON_SOLID,
                    "RAR3/RAR4 non-solid classic-LZ payload has limited real-fixture CRC coverage");
        }

        return RarBackendDecision.libarchive(
                "RAR3/RAR4 compressed payload is owned by libarchive unless a narrower first-party gate accepts it");
    }
}
