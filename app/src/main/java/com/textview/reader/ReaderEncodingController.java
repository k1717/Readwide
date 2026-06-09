package com.textview.reader;

import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileUtils;

import java.io.File;

final class ReaderEncodingController {
    private final ReaderActivity activity;

    ReaderEncodingController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    String resolveTextEncodingForFile(@NonNull File file) {
        if (activity.prefs != null) {
            String manual = activity.prefs.getManualTextEncodingForFile(file);
            String normalized = FileUtils.normalizeManualEncodingName(manual);
            if (normalized != null) {
                activity.currentTextEncodingLabel = normalized + " (manual)";
                activity.currentTextEncodingManual = true;
                return normalized;
            }

            String cached = activity.prefs.getCachedAutoTextEncodingForFile(file);
            String normalizedCached = FileUtils.normalizeManualEncodingName(cached);
            if (normalizedCached != null) {
                activity.currentTextEncodingLabel = activity.prefs.getCachedAutoTextEncodingLabelForFile(file);
                if (activity.currentTextEncodingLabel == null || activity.currentTextEncodingLabel.trim().isEmpty()) {
                    activity.currentTextEncodingLabel = normalizedCached + " (auto, cached)";
                }
                activity.currentTextEncodingManual = false;
                return normalizedCached;
            } else if (cached != null) {
                activity.prefs.clearCachedAutoTextEncodingForFile(file);
            }
        }

        FileUtils.EncodingResult result = FileUtils.detectEncodingDetailed(file);
        activity.currentTextEncodingLabel = result.displayLabel();
        activity.currentTextEncodingManual = false;
        if (activity.prefs != null) {
            activity.prefs.setCachedAutoTextEncodingForFile(file, result.charsetName, result.displayLabel());
        }
        return result.charsetName;
    }

    void applyTextEncodingSelection(@NonNull File file, @NonNull String option) {
        String normalized = "Auto".equalsIgnoreCase(option) ? null : FileUtils.normalizeManualEncodingName(option);
        if (!"Auto".equalsIgnoreCase(option) && normalized == null) {
            ShortToast.show(activity, R.string.invalid_text_encoding);
            return;
        }

        if (activity.prefs != null) {
            activity.prefs.setManualTextEncodingForFile(file, normalized);
            activity.prefs.clearCachedAutoTextEncodingForFile(file);
        }

        activity.currentTextEncodingLabel = "";
        activity.currentTextEncodingManual = normalized != null;

        // Encoding changes alter decoded text. Therefore every page/partition
        // model derived from the previous decode must be treated as stale.
        activity.clearLoadedTextSnapshot();
        activity.resetLargeTextExactPageIndex();
        activity.clearLargeTextPartitionCache();
        activity.clearLargeTextSearchTotalCache();
        activity.clearLargeTextQueuedPageDelta();
        activity.resetLargeTextPageDirectionTracking();
        activity.largeTextEstimatedTotalPages = 0;
        activity.largeTextEstimatedBasePageOffset = 0;
        activity.pendingLargeTextCachedDisplayPage = 0;
        activity.pendingLargeTextCachedTotalPages = 0;

        Intent reloadIntent = new Intent(activity.getIntent());
        reloadIntent.putExtra(ReaderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        reloadIntent.removeExtra(ReaderActivity.EXTRA_FILE_URI);
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, 0);
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, 0);
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, 0);

        ShortToast.show(activity, normalized == null ? R.string.text_encoding_auto_restored : R.string.text_encoding_manual_applied);
        activity.loadFileFromIntent(reloadIntent);
    }
}
