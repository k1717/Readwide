package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.readwide.manager.util.TextDisplayRuleManager;

import java.io.File;

final class ReaderReloadController {
    private final ReaderActivity activity;

    ReaderReloadController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    boolean maybeReloadForPhysicallyEditedOriginalTxtFile() {
        if (activity.filePath == null || activity.filePath.isEmpty() || activity.readerView == null) return false;
        SharedPreferences markerPrefs = activity.getSharedPreferences(ReaderActivity.TXT_ACTUAL_FILE_EDIT_PREFS, Context.MODE_PRIVATE);
        String modifiedPath = markerPrefs.getString(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_PATH, "");
        long token = markerPrefs.getLong(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_TOKEN, 0L);
        if (token <= 0L || modifiedPath == null || modifiedPath.isEmpty()) return false;

        File currentFile = new File(activity.filePath).getAbsoluteFile();
        File modifiedFile = new File(modifiedPath).getAbsoluteFile();
        if (!currentFile.getAbsolutePath().equals(modifiedFile.getAbsolutePath())) return false;

        markerPrefs.edit()
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_PATH)
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_TOKEN)
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_LENGTH)
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_LAST_MODIFIED)
                .apply();

        int currentPosition = Math.max(0, activity.getBookmarkSaveCharPosition());
        String anchorBefore = activity.getAnchorTextBefore(currentPosition);
        String anchorAfter = activity.getAnchorTextAfter(currentPosition);
        Intent reloadIntent = new Intent(activity.getIntent());
        reloadIntent.putExtra(ReaderActivity.EXTRA_FILE_PATH, currentFile.getAbsolutePath());
        reloadIntent.removeExtra(ReaderActivity.EXTRA_FILE_URI);
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, currentPosition);
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, true);
        reloadIntent.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE);
        reloadIntent.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE);
        if (anchorBefore != null && !anchorBefore.isEmpty()) {
            reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, anchorBefore);
        }
        if (anchorAfter != null && !anchorAfter.isEmpty()) {
            reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, anchorAfter);
        }
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, activity.getDisplayedCurrentPageNumber());
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, activity.getDisplayedTotalPageCount());
        activity.clearLoadedTextSnapshot();
        activity.resetLargeTextExactPageIndex();
        activity.clearLargeTextPartitionCache();
        activity.loadFileFromIntent(reloadIntent);
        return true;
    }

    boolean maybeReloadForLargeTextPartitionModeChange() {
        if (activity.prefs == null) return false;

        int currentMode = activity.getLargeTextPartitionMode();
        if (activity.appliedLargeTextPartitionMode == Integer.MIN_VALUE) {
            activity.appliedLargeTextPartitionMode = currentMode;
            return false;
        }
        if (activity.appliedLargeTextPartitionMode == currentMode) return false;

        activity.appliedLargeTextPartitionMode = currentMode;
        activity.resetLargeTextExactPageIndex();
        activity.clearLargeTextPartitionCache();
        activity.resetLargeTextPageDirectionTracking();
        activity.clearLargeTextQueuedPageDelta();

        // The old total/page denominator belongs to the previous partition
        // policy.  Do not carry it through the mode switch as a stable total;
        // let the refreshed partition produce a new immediate estimate, then
        // let the full exact-index rebuild replace it with the authoritative
        // page model.
        activity.largeTextEstimatedTotalPages = 0;
        activity.largeTextEstimatedBasePageOffset = 0;
        activity.pendingLargeTextCachedDisplayPage = 0;
        activity.pendingLargeTextCachedTotalPages = 0;

        if (!activity.largeTextEstimateActive || activity.readerView == null || activity.filePath == null || activity.filePath.isEmpty()) {
            return false;
        }

        int currentPosition = Math.max(0, activity.getBookmarkSaveCharPosition());
        activity.reloadLargeTextPreviewAround(currentPosition, 0, 0, null, null, -1, true);
        // A partition-mode change alters the global page model, not just the
        // visible runtime partition.  Rebuild the full exact page index under
        // the new 4000/400 or 12000/600 model so totalPages, bookmark cached
        // page metadata, slider/go-to-page anchors, and tap paging all converge
        // to the same denominator after the mode switch.
        activity.scheduleLargeTextExactPageIndexingRestart();
        return true;
    }

    void maybeReloadForTextDisplayRuleChange() {
        if (activity.filePath == null || activity.filePath.isEmpty() || activity.readerView == null) return;
        String current = textContentTransformSignatureForPath(activity.filePath);
        if (current.equals(activity.appliedTextDisplayRuleSignature)) return;
        int currentPosition = Math.max(0, activity.getBookmarkSaveCharPosition());
        String anchorBefore = activity.getAnchorTextBefore(currentPosition);
        String anchorAfter = activity.getAnchorTextAfter(currentPosition);
        Intent reloadIntent = new Intent(activity.getIntent());
        reloadIntent.putExtra(ReaderActivity.EXTRA_FILE_PATH, activity.filePath);
        reloadIntent.removeExtra(ReaderActivity.EXTRA_FILE_URI);
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, currentPosition);
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, true);
        reloadIntent.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE);
        reloadIntent.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE);
        if (anchorBefore != null && !anchorBefore.isEmpty()) {
            reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, anchorBefore);
        }
        if (anchorAfter != null && !anchorAfter.isEmpty()) {
            reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, anchorAfter);
        }
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE, activity.getDisplayedCurrentPageNumber());
        reloadIntent.putExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES, activity.getDisplayedTotalPageCount());
        // A blank-line-collapse change (and, to a lesser degree, a display-rule
        // change) shifts character positions, line numbers, and the large-TXT
        // page model, so drop the cached exact page index, partition cache, and
        // search totals before reloading rather than relying only on signature
        // mismatch to avoid stale reuse.
        activity.clearLoadedTextSnapshot();
        activity.resetLargeTextExactPageIndex();
        activity.clearLargeTextPartitionCache();
        activity.clearLargeTextSearchTotalCache();
        activity.clearLargeTextQueuedPageDelta();
        activity.resetLargeTextPageDirectionTracking();
        activity.pendingLargeTextCachedDisplayPage = 0;
        activity.pendingLargeTextCachedTotalPages = 0;
        activity.loadFileFromIntent(reloadIntent);
    }

    String textContentTransformSignatureForPath(String path) {
        if (path == null || path.isEmpty()) return "none";
        boolean collapse = activity.prefs != null && activity.prefs.isCollapseBlankLinesEnabled();
        return TextDisplayRuleManager.getSignature(activity.getApplicationContext(), path)
                + "|collapseBlank=" + collapse;
    }

    String currentTextDisplayRuleSignature() {
        return textContentTransformSignatureForPath(activity.filePath);
    }

    void requestTextDisplayRuleContentRefreshOnWindowClose() {
        activity.pendingTxtDisplayRuleContentRefresh = true;
    }

    void acknowledgeTextDisplayRuleWindowNoContentChange() {
        if (!activity.pendingTxtDisplayRuleContentRefresh) {
            activity.appliedTextDisplayRuleSignature = currentTextDisplayRuleSignature();
        }
    }

    void applyPendingTextDisplayRuleWindowRefresh() {
        if (!activity.pendingTxtDisplayRuleContentRefresh) return;
        activity.pendingTxtDisplayRuleContentRefresh = false;
        maybeReloadForTextDisplayRuleChange();
    }

    boolean sameTextDisplayRuleValue(String a, String b) {
        return TextUtils.equals(a != null ? a : "", b != null ? b : "");
    }
}
