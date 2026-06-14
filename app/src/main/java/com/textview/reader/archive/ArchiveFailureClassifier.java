package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Keeps backend exception-message parsing out of ArchiveSupport's extraction
 * routing code. Message parsing is still a best-effort fallback, but centralizing
 * it makes password / unsupported-feature / corruption boundaries easier to test
 * and tune without touching the archive dispatch paths.
 */
final class ArchiveFailureClassifier {
    private ArchiveFailureClassifier() {}

    @NonNull
    static ArchiveSupport.ExtractionFailure classify(@NonNull Exception e) {
        String message = e.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);

        if (containsAny(lower,
                "bad password",
                "wrong password",
                "invalid password",
                "invalid alz password",
                "password check failed",
                "incorrect password",
                "password verification failed",
                "password verify failed",
                "wrong passphrase",
                "decryption failed",
                "authentication failed",
                "mac check failed")) {
            return ArchiveSupport.ExtractionFailure.BAD_PASSWORD;
        }

        if (containsAny(lower,
                "unsupported encryption",
                "encryption method unsupported",
                "unsupported password method",
                "unsupported encrypted",
                "unsupported encryption method",
                "unsupported decryption",
                "unsupported cipher",
                "unsupported aes",
                "unsupported winzip aes")) {
            return ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE;
        }

        if (containsAny(lower,
                "password required",
                "password is required",
                "requires password",
                "encrypted archive requires password",
                "passphrase required",
                "no password supplied",
                "password has not been set",
                "cannot read encrypted",
                "encrypted content",
                "encrypted header",
                "encrypted archive")) {
            return ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED;
        }

        if (containsAny(lower,
                "not supported",
                "unsupported",
                "unknown compression method",
                "not available yet",
                "unsupported compression method",
                "unsupported method",
                "unsupported feature")) {
            return ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE;
        }

        if (containsAny(lower,
                "crc mismatch",
                "failed crc verification",
                "checksum",
                "truncated",
                "corrupt",
                "unexpected end",
                "missing 7z split volume",
                "missing numeric split archive part",
                "first numeric split archive part is missing",
                "missing split archive part",
                "missing split volume",
                "missing volume",
                "invalid signature",
                "invalid egg signature",
                "invalid alz signature",
                "invalid rar signature",
                "invalid zip signature",
                "invalid header",
                "malformed",
                "not a valid")) {
            return ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE;
        }

        return ArchiveSupport.ExtractionFailure.FAILED;
    }

    private static boolean containsAny(@NonNull String lower, @NonNull String... needles) {
        for (String needle : needles) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }
}
