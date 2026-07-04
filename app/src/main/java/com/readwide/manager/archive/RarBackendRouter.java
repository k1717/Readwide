package com.readwide.manager.archive;

import androidx.annotation.NonNull;

/**
 * Narrow RAR backend decision table.
 *
 * <p>This class is intentionally metadata-only. It does not extract anything and does not make
 * support claims. It exists so the reader, diagnostics, and docs can share the same conservative
 * boundary: libarchive remains the primary broad RAR backend; first-party Java handles stored
 * entries plus the verified scoped RAR3/RAR4 and RAR5 decode-only fallback paths.</p>
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
            if (entry.encrypted()) {
                // AES-encrypted RAR5 compressed payloads have no libarchive path
                // at all (libarchive 3.7.2 does not decrypt RAR5), so the scoped
                // first-party Rar5 AES-decrypt + decompress path is the only route;
                // it covers the supported RAR5 v5.0 decode subset and cleanly
                // reports anything outside it as unsupported.
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_RAR5_COMPRESSED,
                        "RAR5 encrypted compressed payload is first-party-only (libarchive cannot decrypt RAR5); handled by the scoped RAR5 v5.0 AES decode path");
            }
            return RarBackendDecision.libarchive(
                    "RAR5 compressed payload is libarchive-primary, with scoped first-party Java fallbacks for covered RAR5 v5.0 entries");
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
            if (Rar3PpmdBlockProbe.isPpmdPayload(entry)) {
                return RarBackendDecision.firstParty(
                        RarBackendRoute.Kind.TRY_FIRST_PARTY_SOLID_SEQUENTIAL,
                        "RAR3/RAR4 solid PPMd payload has a scoped first-party sequential decoder for eligible non-encrypted single-volume solid sets");
            }
            return RarBackendDecision.unsupported(
                    RarBackendRoute.Kind.CLEAN_UNSUPPORTED_SOLID,
                    "RAR3/RAR4 compressed solid payload is outside the verified first-party PPMd solid subset unless another scoped special-case decoder accepts it");
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
