package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Locale;

final class RarSplitStoredFailure {
    enum Code {
        PASSWORD_REQUIRED,
        CANCELLED,
        CRC_MISMATCH,
        INCOMPLETE_CHAIN,
        INVALID_SPLIT_FLAGS,
        MISSING_SOURCE_VOLUME,
        COMPRESSED_SPLIT_UNSUPPORTED,
        SOLID_SPLIT_UNSUPPORTED,
        MIXED_ENCRYPTION,
        ENCRYPTION_PARAMETERS_CHANGED,
        UNSUPPORTED_ENCRYPTION,
        DECRYPT_FAILED,
        IO_FAILURE,
        OTHER
    }

    enum Kind {
        USER_ACTION,
        INVALID_ARCHIVE,
        UNSUPPORTED_FEATURE,
        DATA_ERROR,
        IO_ERROR
    }

    final Code code;
    final Kind kind;
    final String entryPath;
    final String message;

    private RarSplitStoredFailure(@NonNull Code code,
                                  @NonNull Kind kind,
                                  @NonNull String entryPath,
                                  @NonNull String message) {
        this.code = code;
        this.kind = kind;
        this.entryPath = entryPath;
        this.message = message;
    }

    @NonNull
    static RarSplitStoredFailure classify(@Nullable RarArchiveReader.RarEntry first,
                                          @Nullable RarSplitStoredPlan plan,
                                          @NonNull IOException error) {
        String path = first != null ? first.path : "";
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String lower = raw.toLowerCase(Locale.ROOT);

        if (error instanceof ArchiveSupport.PasswordRequiredException) {
            return new RarSplitStoredFailure(Code.PASSWORD_REQUIRED, Kind.USER_ACTION, path, raw);
        }
        if (lower.contains("cancelled") || lower.contains("canceled")) {
            return new RarSplitStoredFailure(Code.CANCELLED, Kind.USER_ACTION, path, raw);
        }
        if (lower.contains("crc") || lower.contains("checksum")) {
            return new RarSplitStoredFailure(Code.CRC_MISMATCH, Kind.DATA_ERROR, path, raw);
        }
        if (lower.contains("missing rar split continuation") || lower.contains("incomplete rar split")) {
            return new RarSplitStoredFailure(Code.INCOMPLETE_CHAIN, Kind.INVALID_ARCHIVE, path, raw);
        }
        if (lower.contains("invalid") && lower.contains("split")) {
            return new RarSplitStoredFailure(Code.INVALID_SPLIT_FLAGS, Kind.INVALID_ARCHIVE, path, raw);
        }
        if (lower.contains("source volume")) {
            return new RarSplitStoredFailure(Code.MISSING_SOURCE_VOLUME, Kind.INVALID_ARCHIVE, path, raw);
        }
        if (lower.contains("compressed split")) {
            return new RarSplitStoredFailure(Code.COMPRESSED_SPLIT_UNSUPPORTED, Kind.UNSUPPORTED_FEATURE, path, raw);
        }
        if (lower.contains("solid rar split") || (first != null && first.solid)) {
            return new RarSplitStoredFailure(Code.SOLID_SPLIT_UNSUPPORTED, Kind.UNSUPPORTED_FEATURE, path, raw);
        }
        if (lower.contains("mixed encrypted")) {
            return new RarSplitStoredFailure(Code.MIXED_ENCRYPTION, Kind.UNSUPPORTED_FEATURE, path, raw);
        }
        if (lower.contains("parameters changed")) {
            return new RarSplitStoredFailure(Code.ENCRYPTION_PARAMETERS_CHANGED, Kind.UNSUPPORTED_FEATURE, path, raw);
        }
        if (lower.contains("encrypted split") || lower.contains("encrypted rar file data")) {
            return new RarSplitStoredFailure(Code.UNSUPPORTED_ENCRYPTION, Kind.UNSUPPORTED_FEATURE, path, raw);
        }
        if (lower.contains("decrypt failed")) {
            return new RarSplitStoredFailure(Code.DECRYPT_FAILED, Kind.DATA_ERROR, path, raw);
        }
        if (plan != null && plan.encrypted() && lower.contains("aes")) {
            return new RarSplitStoredFailure(Code.DECRYPT_FAILED, Kind.DATA_ERROR, path, raw);
        }
        if (error instanceof java.io.EOFException || lower.contains("eof") || lower.contains("read")) {
            return new RarSplitStoredFailure(Code.IO_FAILURE, Kind.IO_ERROR, path, raw);
        }
        return new RarSplitStoredFailure(Code.OTHER, Kind.IO_ERROR, path, raw);
    }

    @NonNull
    static IOException wrap(@Nullable RarArchiveReader.RarEntry first,
                            @Nullable RarSplitStoredPlan plan,
                            @NonNull IOException error) {
        if (error instanceof ArchiveSupport.PasswordRequiredException) return error;
        if (error instanceof ExtractionException) return error;
        return new ExtractionException(classify(first, plan, error), error);
    }

    @NonNull
    String stableMessage() {
        String prefix = "RAR stored split extraction failed";
        if (entryPath.length() > 0) prefix += " for " + entryPath;
        return prefix + " [" + code + "]: " + message;
    }

    static final class ExtractionException extends IOException {
        private final RarSplitStoredFailure failure;

        ExtractionException(@NonNull RarSplitStoredFailure failure, @NonNull IOException cause) {
            super(failure.stableMessage(), cause);
            this.failure = failure;
        }

        @NonNull
        RarSplitStoredFailure failure() {
            return failure;
        }
    }
}
