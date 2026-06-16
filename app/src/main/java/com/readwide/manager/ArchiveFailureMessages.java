package com.readwide.manager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.archive.ArchiveSupport;

import java.io.File;

final class ArchiveFailureMessages {
    private ArchiveFailureMessages() {}

    static int unsupportedFeatureMessageRes(@Nullable File archive) {
        ArchiveSupport.Type type = archive == null ? null : ArchiveSupport.getSupportedArchiveType(archive);
        if (type == ArchiveSupport.Type.RAR) return R.string.archive_extract_unsupported_rar;
        if (type == ArchiveSupport.Type.ALZ) return R.string.archive_extract_unsupported_alz;
        if (type == ArchiveSupport.Type.EGG) return R.string.archive_extract_unsupported_egg;
        if (type == ArchiveSupport.Type.ZIP) return R.string.archive_extract_unsupported_zip;
        if (type == ArchiveSupport.Type.SEVEN_Z) return R.string.archive_extract_unsupported_7z;
        return R.string.archive_extract_unsupported_feature;
    }

    static int extractionFailureMessageRes(@Nullable File archive,
                                           @NonNull ArchiveSupport.ExtractionResult result) {
        if (result.failure == ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED) {
            return R.string.archive_password_failed;
        }
        if (result.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD) {
            return R.string.archive_bad_password;
        }
        if (result.failure == ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE) {
            return R.string.archive_corrupt_or_incomplete;
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
        if (result != null && result.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD) {
            return R.string.archive_bad_password;
        }
        if (result != null && result.failure == ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE) {
            return R.string.archive_corrupt_or_incomplete;
        }
        if (result != null && result.failure == ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE) {
            return unsupportedFeatureMessageRes(archive);
        }
        return R.string.archive_entry_open_failed;
    }

    @NonNull
    static String supportBoundaryDetail(@NonNull Context context,
                                        @Nullable File archive,
                                        @Nullable ArchiveSupport.ExtractionResult result) {
        StringBuilder builder = new StringBuilder();
        ArchiveSupport.Type type = archive == null ? null : ArchiveSupport.getSupportedArchiveType(archive);
        int boundaryRes;
        if (type == ArchiveSupport.Type.RAR) boundaryRes = R.string.archive_support_boundary_rar;
        else if (type == ArchiveSupport.Type.ZIP) boundaryRes = R.string.archive_support_boundary_zip;
        else if (type == ArchiveSupport.Type.SEVEN_Z) boundaryRes = R.string.archive_support_boundary_7z;
        else if (type == ArchiveSupport.Type.ALZ) boundaryRes = R.string.archive_support_boundary_alz;
        else if (type == ArchiveSupport.Type.EGG) boundaryRes = R.string.archive_support_boundary_egg;
        else boundaryRes = R.string.archive_support_boundary_generic;
        builder.append(context.getString(boundaryRes));
        if (result != null) {
            if (result.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD) {
                builder.append("\n\n").append(context.getString(R.string.archive_support_boundary_bad_password));
            } else if (result.failure == ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE) {
                builder.append("\n\n").append(context.getString(R.string.archive_support_boundary_corrupt));
            }
            if (result.detail != null && result.detail.trim().length() > 0) {
                builder.append("\n\n").append(context.getString(R.string.archive_support_boundary_detail_prefix))
                        .append('\n').append(result.detail.trim());
            }
        }
        return builder.toString();
    }

    static boolean shouldShowSupportBoundaryDialog(@Nullable ArchiveSupport.ExtractionResult result) {
        if (result == null) return false;
        return result.failure == ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE
                || result.failure == ArchiveSupport.ExtractionFailure.BAD_PASSWORD
                || result.failure == ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE;
    }
}
