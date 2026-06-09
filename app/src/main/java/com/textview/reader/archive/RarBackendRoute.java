package com.textview.reader.archive;

/**
 * Stable backend routing labels for RAR extraction/listing diagnostics.
 *
 * <p>The labels describe which engine is allowed to try a case first. They are deliberately
 * conservative: libarchive owns broad compressed RAR, while first-party Java paths only cover
 * stored/split and verified narrow development fallbacks.</p>
 */
final class RarBackendRoute {
    enum Kind {
        TRY_LIBARCHIVE,
        TRY_FIRST_PARTY_STORED,
        TRY_FIRST_PARTY_STORED_SPLIT,
        TRY_FIRST_PARTY_RAR4_REWRITE,
        TRY_FIRST_PARTY_RAR4_COMPRESSED_SPLIT_REWRITE,
        TRY_FIRST_PARTY_RAR4_ENCRYPTED_COMPRESSED_SPLIT_REWRITE,
        TRY_FIRST_PARTY_CLASSIC_LZ_NON_SOLID,
        CLEAN_UNSUPPORTED_PPMD,
        CLEAN_UNSUPPORTED_VM,
        CLEAN_UNSUPPORTED_SOLID,
        CLEAN_UNSUPPORTED_COMPRESSED_SPLIT,
        CLEAN_UNSUPPORTED_ENCRYPTED_COMPRESSED,
        CLEAN_UNSUPPORTED_RAR5_FIRST_PARTY,
        CLEAN_UNSUPPORTED_UNKNOWN
    }

    private RarBackendRoute() {}
}
