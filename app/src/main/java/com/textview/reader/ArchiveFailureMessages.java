package com.textview.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.archive.ArchiveSupport;

import java.io.File;

final class ArchiveFailureMessages {
    private ArchiveFailureMessages() {}

    static int unsupportedFeatureMessageRes(@Nullable File archive) {
        ArchiveSupport.Type type = archive == null ? null : ArchiveSupport.getSupportedArchiveType(archive);
        if (type == ArchiveSupport.Type.RAR) return R.string.archive_extract_unsupported_rar;
        if (type == ArchiveSupport.Type.ALZ) return R.string.archive_extract_unsupported_alz;
        if (type == ArchiveSupport.Type.EGG) return R.string.archive_extract_unsupported_egg;
        return R.string.archive_extract_unsupported_feature;
    }

    static int extractionFailureMessageRes(@Nullable File archive,
                                           @NonNull ArchiveSupport.ExtractionResult result) {
        if (result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED) {
            return R.string.archive_password_failed;
        }
        if (result.failure == ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE) {
            return unsupportedFeatureMessageRes(archive);
        }
        return R.string.archive_extract_failed;
    }

    static int entryFailureMessageRes(@Nullable File archive,
                                      @Nullable ArchiveSupport.ExtractionResult result) {
        if (result != null && result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED) {
            return R.string.archive_password_failed;
        }
        if (result != null && result.failure == ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE) {
            return unsupportedFeatureMessageRes(archive);
        }
        return R.string.archive_entry_open_failed;
    }
}
