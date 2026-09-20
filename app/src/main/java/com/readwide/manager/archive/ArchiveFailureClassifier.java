package com.readwide.manager.archive;

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
        // A typed error is more reliable than words in a filename. Walk wrapper
        // causes with identity-based cycle detection before considering messages.
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = e; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.io.InterruptedIOException) return ArchiveSupport.ExtractionFailure.FAILED;
            if (cause instanceof ArchiveSupport.PasswordRequiredException) return ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED;
            if (cause instanceof ArchiveSupport.UnsupportedArchiveFeatureException) return ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE;
            if (cause instanceof java.io.EOFException
                    || cause instanceof Rar5CompressedArchiveExtractor.ChecksumException) {
                return ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE;
            }
        }
        seen.clear();
        for (Throwable cause = e; cause != null && seen.add(cause); cause = cause.getCause()) {
            ArchiveSupport.ExtractionFailure result = classifyMessage(cause.getMessage());
            if (result != ArchiveSupport.ExtractionFailure.FAILED) return result;
        }
        return ArchiveSupport.ExtractionFailure.FAILED;
    }

    private static ArchiveSupport.ExtractionFailure classifyMessage(String message) {
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
                "unexpected eof",
                "unexpected egg eof",
                "unexpected alz eof",
                "unexpected rar eof",
                "unexpected 7z eof",
                "missing 7z split volume",
                "missing numeric split archive part",
                "first numeric split archive part is missing",
                "missing split archive part",
                "missing split volume",
                "missing volume",
                "invalid signature",
                "invalid egg signature",
                "invalid egg volume signature",
                "invalid alz volume signature",
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
