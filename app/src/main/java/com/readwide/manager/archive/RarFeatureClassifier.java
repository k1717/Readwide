package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized feature gating for the split RAR backend model.
 *
 * <p>RAR extraction intentionally uses several engines: libarchive for normal compressed RAR,
 * first-party crypto/rewrite helpers for visible-header encrypted special cases, and small stored
 * readers for fallback. Keeping these predicates outside {@link RarArchiveReader} prevents the
 * reader from turning back into a giant branch table as more decoder pieces are added.</p>
 */
final class RarFeatureClassifier {
    private RarFeatureClassifier() {}

    static boolean isUnsupportedRar3Or4Payload(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion < 5
                && !entry.directory
                && (!isRar3Or4StoredMethod(entry.method)
                || (entry.encrypted() && !isFirstPartyRar3Or4EncryptedStoredEntry(entry)));
    }

    static boolean isFirstPartyRar3Or4EncryptedStoredEntry(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion < 5
                && !entry.directory
                && isRar3Or4StoredMethod(entry.method)
                && entry.encryption != null
                && entry.encryption.isRar4Aes();
    }

    static boolean isRar3Or4StoredMethod(int method) {
        return method == 0 || method == 0x30;
    }

    static boolean hasUnsupportedRar3Or4Payload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (isUnsupportedRar3Or4Payload(entry)) return true;
        }
        return false;
    }

    static boolean shouldUseRar5CompressedFallback(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion >= 5
                && !entry.directory
                && entry.method != 0;
    }

    static boolean shouldUseRar5FallbackForWholeArchive(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (shouldUseRar5CompressedFallback(entry)) return true;
        }
        return false;
    }


    static boolean hasRar5CompressedSplitPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null
                    && entry.rarVersion >= 5
                    && !entry.directory
                    && entry.method != 0
                    && (entry.splitBefore || entry.splitAfter)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    static RarArchiveReader.UnsupportedRarFeatureException rar5CompressedFallbackFailure(
            @NonNull List<RarArchiveReader.RarEntry> entries,
            @Nullable IOException backendFailure) {
        String message;
        if (hasRar5CompressedSplitPayload(entries)) {
            message = "RAR5 compressed split/multi-volume payload could not be completed by the scoped first-party Java decoder; "
                    + "compressed split members are never routed through the stored-entry fallback";
        } else {
            message = "RAR5 compressed archive could not be completed by the scoped first-party Java decoder; "
                    + "compressed members are not routed through the stored-entry fallback";
        }
        RarBackendDecision decision = firstPayloadDecision(entries);
        if (decision != null) {
            message += " (route: " + decision.diagnostic() + ")";
        }
        if (backendFailure != null && backendFailure.getMessage() != null && !backendFailure.getMessage().isEmpty()) {
            message += " (libarchive: " + backendFailure.getMessage() + ")";
        } else if (!RarLibarchiveFallback.isAvailable()) {
            message += " (libarchive backend unavailable: " + LibarchiveNativeBridge.backendStatus() + ")";
        }
        return new RarArchiveReader.UnsupportedRarFeatureException(message);
    }

    @NonNull
    static RarArchiveReader.UnsupportedRarFeatureException firstPartyRar3Or4Gap(
            @NonNull RarArchiveReader.RarEntry entry,
            @Nullable IOException backendFailure) {
        List<RarArchiveReader.RarEntry> one = new ArrayList<>(1);
        one.add(entry);
        return firstPartyRar3Or4Gap(one, backendFailure);
    }

    @NonNull
    static RarArchiveReader.UnsupportedRarFeatureException firstPartyRar3Or4Gap(
            @NonNull List<RarArchiveReader.RarEntry> entries,
            @Nullable IOException backendFailure) {
        String message;
        if (hasRar3Or4HeaderEncryptedOnly(entries)) {
            message = "RAR3/RAR4 header-encrypted (-hp) archives are detected before entry parsing and delegated to libarchive; "
                    + "the first-party header decryptor is not implemented yet";
        } else if (hasRar3Or4SolidPpmdPayload(entries)) {
            message = "RAR3/RAR4 solid PPMd extraction has a scoped first-party decoder for eligible non-encrypted single-volume solid sets; "
                    + "other compressed-solid variants remain limited or backend-dependent. Stored solid entries do not need dictionary continuation and are handled by the stored-entry path";
        } else if (hasRar3Or4SolidPayload(entries)) {
            message = "RAR3/RAR4 compressed solid extraction is routed through scoped first-party special-case decoders where eligible; "
                    + "classic-LZ solid, split, encrypted, and VM-filtered variants remain limited or backend-dependent. Stored solid entries do not need dictionary continuation and are handled by the stored-entry path";
        } else if (hasRar3Or4EncryptedPayload(entries)) {
            message = "RAR3/RAR4 encrypted extraction is reserved for the first-party password/encryption decoder; "
                    + "that decoder is not implemented yet";
        } else if (hasRar3Or4CompressedSplitPayload(entries)) {
            message = "RAR3/RAR4 compressed split extraction is not implemented in the first-party decoder yet";
        } else if (hasRar3Or4PpmdPayload(entries)) {
            message = "RAR3/RAR4 PPMd compressed payload is detected; eligible non-encrypted single-volume PPMd entries may use the first-party decode-only PPMd path, while unsupported variants remain backend-dependent";
        } else if (hasRar3Or4LimitedClassicLzFallbackPayload(entries)) {
            message = "RAR3/RAR4 non-solid classic-LZ has a narrow first-party fallback gate after libarchive failure; "
                    + "the gate is limited to non-encrypted, non-split, non-solid entries with CRC verification";
        } else if (hasRar3Or4CompressedPayload(entries)) {
            message = "RAR3/RAR4 normal compressed extraction is owned by the bundled libarchive backend; "
                    + "only the verified non-solid classic-LZ subset is allowed through the first-party fallback gate";
        } else {
            message = "RAR3/RAR4 archive requires an unsupported first-party RAR special-case decoder";
        }
        RarBackendDecision decision = firstPayloadDecision(entries);
        if (decision != null) {
            message += " (route: " + decision.diagnostic() + ")";
        }
        String ppmdProbe = firstPpmdProbeDiagnostic(entries);
        if (ppmdProbe != null) {
            message += " (ppmd probe: " + ppmdProbe + ")";
        }
        if (backendFailure != null && backendFailure.getMessage() != null && !backendFailure.getMessage().isEmpty()) {
            message += " (libarchive: " + backendFailure.getMessage() + ")";
        } else if (!RarLibarchiveFallback.isAvailable()) {
            message += " (libarchive backend unavailable: " + LibarchiveNativeBridge.backendStatus() + ")";
        }
        return new RarArchiveReader.UnsupportedRarFeatureException(message);
    }


    @NonNull
    static RarArchiveReader.UnsupportedRarFeatureException libarchivePrimaryRarFailure(
            @NonNull RarArchiveReader.RarEntry entry,
            @Nullable IOException backendFailure) {
        List<RarArchiveReader.RarEntry> one = new ArrayList<>(1);
        one.add(entry);
        return libarchivePrimaryRarFailure(one, backendFailure);
    }

    @NonNull
    static RarArchiveReader.UnsupportedRarFeatureException libarchivePrimaryRarFailure(
            @NonNull List<RarArchiveReader.RarEntry> entries,
            @Nullable IOException backendFailure) {
        String message = "RAR extraction is libarchive-primary; the bundled libarchive backend was attempted first";
        if (hasRar3Or4SolidPpmdPayload(entries)) {
            message += ", and the scoped first-party RAR3/RAR4 solid PPMd decoder also could not complete this archive";
        } else if (hasRar3Or4SolidPayload(entries)) {
            message += ", but it failed on a RAR3/RAR4 solid payload outside the verified first-party solid subset";
        } else if (hasRar3Or4PpmdPayload(entries)) {
            message += ", and the scoped first-party RAR3/RAR4 PPMd decoder also could not complete this archive";
        } else if (hasRar3Or4EncryptedPayload(entries)) {
            message += ", but it failed on an encrypted RAR3/RAR4 payload. First-party encrypted compressed decoding is not live";
        } else if (hasRar3Or4CompressedSplitPayload(entries)) {
            message += ", but it failed on a compressed split RAR3/RAR4 payload. First-party split compressed decoding is not live";
        } else if (hasRar3Or4LimitedClassicLzFallbackPayload(entries)) {
            message += ", and the scoped first-party classic-LZ fallback also could not complete this archive";
        } else if (hasRar3Or4CompressedPayload(entries)) {
            message += ", but it failed on a compressed RAR3/RAR4 payload outside the verified first-party fallback subset";
        } else {
            message += ", but no scoped first-party fallback could complete this RAR case";
        }
        RarBackendDecision decision = firstPayloadDecision(entries);
        if (decision != null) {
            message += " (route: " + decision.diagnostic() + ")";
        }
        String ppmdProbe = firstPpmdProbeDiagnostic(entries);
        if (ppmdProbe != null) {
            message += " (ppmd probe: " + ppmdProbe + ")";
        }
        if (backendFailure != null && backendFailure.getMessage() != null && !backendFailure.getMessage().isEmpty()) {
            message += " (libarchive: " + backendFailure.getMessage() + ")";
        } else if (!RarLibarchiveFallback.isAvailable()) {
            message += " (libarchive backend unavailable: " + LibarchiveNativeBridge.backendStatus() + ")";
        }
        return new RarArchiveReader.UnsupportedRarFeatureException(message);
    }

    @Nullable
    private static RarBackendDecision firstPayloadDecision(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory) continue;
            return RarBackendRouter.decideEntry(entry);
        }
        return null;
    }

    static boolean hasRar3Or4EncryptedRewriteCandidate(@NonNull List<RarArchiveReader.RarEntry> entries) {
        boolean candidate = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.rarVersion >= 5 || entry.directory || !entry.encrypted()) continue;
            if (entry.solid || entry.splitBefore || entry.splitAfter) return false;
            if (entry.method == 0 || RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method)) {
                candidate = true;
            } else {
                return false;
            }
        }
        return candidate;
    }

    static boolean isRar3Or4EncryptedRewriteCandidate(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion < 5
                && !entry.directory
                && entry.encrypted()
                && !entry.solid
                && !entry.splitBefore
                && !entry.splitAfter
                && (entry.method == 0 || RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method));
    }

    static boolean isRar3Or4CompressedSplitRewriteCandidate(
            @NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion < 5
                && !entry.directory
                && !entry.encrypted()
                && !entry.solid
                && !entry.splitBefore
                && entry.splitAfter
                && RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method);
    }

    static boolean isRar3Or4CompressedSplitRewriteCandidate(
            @NonNull List<RarArchiveReader.RarEntry> chain) {
        if (chain.isEmpty()) return false;
        RarArchiveReader.RarEntry first = chain.get(0);
        if (!isRar3Or4CompressedSplitRewriteCandidate(first)) return false;
        for (int i = 0; i < chain.size(); i++) {
            RarArchiveReader.RarEntry part = chain.get(i);
            if (part.rarVersion >= 5 || part.directory || part.solid || part.encrypted()) return false;
            if (part.method != first.method) return false;
            if (!part.path.equals(first.path)) return false;
            if (i == 0) {
                if (part.splitBefore || !part.splitAfter) return false;
            } else if (i == chain.size() - 1) {
                if (!part.splitBefore || part.splitAfter) return false;
            } else if (!part.splitBefore || !part.splitAfter) {
                return false;
            }
        }
        return !chain.get(chain.size() - 1).splitAfter;
    }

    static boolean isRar3Or4EncryptedCompressedSplitRewriteCandidate(
            @NonNull RarArchiveReader.RarEntry entry) {
        return entry.rarVersion < 5
                && !entry.directory
                && entry.encrypted()
                && entry.encryption != null
                && entry.encryption.isRar4Aes()
                && !entry.solid
                && !entry.splitBefore
                && entry.splitAfter
                && RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method);
    }

    static boolean isRar3Or4EncryptedCompressedSplitRewriteCandidate(
            @NonNull List<RarArchiveReader.RarEntry> chain) {
        if (chain.isEmpty()) return false;
        RarArchiveReader.RarEntry first = chain.get(0);
        if (!isRar3Or4EncryptedCompressedSplitRewriteCandidate(first)) return false;
        RarArchiveReader.EncryptionInfo expected = first.encryption;
        if (expected == null) return false;
        for (int i = 0; i < chain.size(); i++) {
            RarArchiveReader.RarEntry part = chain.get(i);
            if (part.rarVersion >= 5 || part.directory || part.solid || !part.encrypted()) return false;
            if (part.method != first.method) return false;
            if (!part.path.equals(first.path)) return false;
            if (!RarVolumeChain.sameRar4Encryption(expected, part.encryption)) return false;
            if (i == 0) {
                if (part.splitBefore || !part.splitAfter) return false;
            } else if (i == chain.size() - 1) {
                if (!part.splitBefore || part.splitAfter) return false;
            } else if (!part.splitBefore || !part.splitAfter) {
                return false;
            }
        }
        return !chain.get(chain.size() - 1).splitAfter;
    }

    private static boolean hasRar3Or4HeaderEncryptedOnly(@NonNull List<RarArchiveReader.RarEntry> entries) {
        return entries.isEmpty();
    }

    private static boolean hasRar3Or4SolidPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.rarVersion < 5 && !entry.directory && entry.solid && entry.method != 0) return true;
        }
        return false;
    }

    private static boolean hasRar3Or4EncryptedPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.rarVersion < 5 && !entry.directory && entry.encrypted()) return true;
        }
        return false;
    }

    private static boolean hasRar3Or4CompressedSplitPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.rarVersion < 5 && !entry.directory && entry.method != 0
                    && (entry.splitBefore || entry.splitAfter)) return true;
        }
        return false;
    }



    private static boolean hasRar3Or4SolidPpmdPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null
                    && entry.rarVersion < 5
                    && !entry.directory
                    && entry.solid
                    && Rar3PpmdBlockProbe.isPpmdPayload(entry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRar3Or4PpmdPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null && Rar3PpmdBlockProbe.isPpmdPayload(entry)) return true;
        }
        return false;
    }

    @Nullable
    private static String firstPpmdProbeDiagnostic(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.rarVersion >= 5 || entry.method == 0) continue;
            Rar3PpmdBlockProbe.Result probe = Rar3PpmdBlockProbe.probe(entry);
            if (probe.isPpmd() || probe.isClassicLz()) return probe.diagnostic();
        }
        return null;
    }

    private static boolean hasRar3Or4LimitedClassicLzFallbackPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry != null && Rar3FirstPartyArchiveExtractor.isLimitedNonSolidClassicLzFallbackCandidate(entry)) return true;
        }
        return false;
    }

    private static boolean hasRar3Or4CompressedPayload(@NonNull List<RarArchiveReader.RarEntry> entries) {
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry.rarVersion < 5 && !entry.directory && entry.method != 0) return true;
        }
        return false;
    }

}
