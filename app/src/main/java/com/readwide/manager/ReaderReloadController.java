package com.readwide.manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.model.ReaderState;

import java.io.File;

final class ReaderReloadController {
    private final ReaderActivity activity;

    ReaderReloadController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    boolean maybeReloadForPhysicallyEditedOriginalTxtFile() {
        if (activity.filePath == null || activity.filePath.isEmpty() || activity.readerView == null) return false;
        if (activity.appliedTextDisplayRuleSignature.isEmpty()) return false;
        SharedPreferences markerPrefs = activity.getSharedPreferences(ReaderActivity.TXT_ACTUAL_FILE_EDIT_PREFS, Context.MODE_PRIVATE);
        String modifiedPath = markerPrefs.getString(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_PATH, "");
        long token = markerPrefs.getLong(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_TOKEN, 0L);
        File currentFile = new File(activity.filePath).getAbsoluteFile();
        boolean markedEdit = token > 0L && modifiedPath != null && !modifiedPath.isEmpty()
                && currentFile.equals(new File(modifiedPath).getAbsoluteFile());
        boolean sourceChanged = activity.contentsSourceLength >= 0L
                && activity.contentsSourceModified >= 0L
                && !ReaderLoadedTextSnapshotController.sourceMatchesContent(currentFile,
                        activity.contentsSourceLength, activity.contentsSourceModified);
        if (!markedEdit && !sourceChanged) return false;

        if (markedEdit) markerPrefs.edit()
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_PATH)
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_TOKEN)
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_LENGTH)
                .remove(ReaderActivity.KEY_TXT_ACTUAL_FILE_EDIT_LAST_MODIFIED)
                .apply();

        Intent reloadIntent = contentReloadIntent(currentFile.getAbsolutePath());
        activity.clearLoadedTextSnapshot();
        activity.resetLargeTextExactPageIndex();
        activity.clearLargeTextPartitionCache();
        activity.invalidateTxtTtsCheckpoint();
        activity.loadFileFromIntent(reloadIntent);
        return true;
    }

    /** UI-thread admission gate for disk-derived results and cached partitions. */
    boolean ensureLoadedTextCurrent() {
        if (activity.activityDestroyed || activity.filePath == null
                || activity.contentsSourceLength < 0L || activity.contentsSourceModified < 0L) return false;
        if (maybeReloadForPhysicallyEditedOriginalTxtFile()) return false;
        if (!currentTextDisplayRuleSignature().equals(activity.appliedTextDisplayRuleSignature)) {
            maybeReloadForTextDisplayRuleChange();
            return false;
        }
        if (activity.largeTextEstimateActive && maybeReloadForLargeTextPartitionModeChange()) return false;
        return true;
    }

    /** Read-only ownership check, also safe during final lifecycle persistence. */
    boolean loadedTextMatchesCurrentSourceAndTransform() {
        return activity.filePath != null && !activity.filePath.isEmpty()
                && ReaderLoadedTextSnapshotController.sourceMatchesContent(new File(activity.filePath),
                        activity.contentsSourceLength, activity.contentsSourceModified)
                && currentTextDisplayRuleSignature().equals(activity.appliedTextDisplayRuleSignature);
    }

    boolean maybeReloadForLargeTextPartitionModeChange() {
        if (activity.prefs == null) return false;

        int currentMode = activity.getLargeTextPartitionMode();
        if (activity.appliedLargeTextPartitionMode == Integer.MIN_VALUE) {
            activity.appliedLargeTextPartitionMode = currentMode;
            return false;
        }
        if (activity.appliedLargeTextPartitionMode == currentMode) return false;

        if (!activity.largeTextEstimateActive || activity.readerView == null || activity.filePath == null || activity.filePath.isEmpty()) {
            activity.appliedLargeTextPartitionMode = currentMode;
            return false;
        }

        // Policy replacement is a new load, not an ordinary adjacent handoff.
        // Capture its owned destination before invalidating the old window.
        // The normal load path clears old totals/cache, waits for layout and
        // indexes the replacement. Only acceptLoad publishes its applied mode;
        // a failed read cannot relabel the old window with the requested mode.
        Intent reload = contentReloadIntent(activity.filePath);
        reload.removeExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION);
        activity.clearLoadedTextSnapshot();
        activity.loadFileFromIntent(reload);
        return true;
    }

    void maybeReloadForTextDisplayRuleChange() {
        if (activity.filePath == null || activity.filePath.isEmpty() || activity.readerView == null) return;
        // A fresh load owns its target until acceptLoad validates the policy.
        // onResume must not replace that target with the old/empty viewport.
        if (activity.appliedTextDisplayRuleSignature.isEmpty()) return;
        String current = textContentTransformSignatureForPath(activity.filePath);
        if (current.equals(activity.appliedTextDisplayRuleSignature)) return;
        activity.invalidateTxtTtsCheckpoint();
        Intent reloadIntent = contentReloadIntent(activity.filePath);
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

    private Intent contentReloadIntent(String path) {
        ReaderState target = activity.captureReaderViewport();
        int position = target != null ? target.getCharPosition()
                : Math.max(0, activity.getBookmarkSaveCharPosition());
        String before = target != null ? target.getAnchorTextBefore() : activity.getAnchorTextBefore(position);
        String after = target != null ? target.getAnchorTextAfter() : activity.getAnchorTextAfter(position);
        Intent reload = new Intent(activity.getIntent());
        reload.putExtra(ReaderActivity.EXTRA_FILE_PATH, path);
        reload.removeExtra(ReaderActivity.EXTRA_FILE_URI);
        reload.putExtra(ReaderActivity.EXTRA_JUMP_TO_POSITION, position);
        reload.putExtra(ReaderActivity.EXTRA_JUMP_PREFER_ANCHOR_PARTITION, true);
        reload.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_LINE);
        reload.removeExtra(ReaderActivity.EXTRA_JUMP_PARTITION_START_BYTE);
        // Both the launch bookmark and the previous page model are obsolete.
        // Remote queued targets intentionally have no context in the old window.
        reload.removeExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE);
        reload.removeExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER);
        if (before != null && !before.isEmpty()) reload.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_BEFORE, before);
        if (after != null && !after.isEmpty()) reload.putExtra(ReaderActivity.EXTRA_JUMP_ANCHOR_AFTER, after);
        reload.removeExtra(ReaderActivity.EXTRA_JUMP_DISPLAY_PAGE);
        reload.removeExtra(ReaderActivity.EXTRA_JUMP_TOTAL_PAGES);
        return reload;
    }

    String textContentTransformSignatureForPath(String path) {
        if (path == null || path.isEmpty()) return "none";
        boolean collapse = activity.prefs != null && activity.prefs.isCollapseBlankLinesEnabled();
        return TextDisplayRuleManager.getExactSignature(activity.getApplicationContext(), path)
                + "|collapseBlank=" + collapse
                + "|manualEncoding=" + manualEncodingSignature(path);
    }

    private String manualEncodingSignature(String path) {
        String encoding = activity.prefs == null ? null : com.readwide.manager.util.FileUtils
                .normalizeManualEncodingName(activity.prefs.getManualTextEncodingForFile(new File(path)));
        // Do not include the auto-detection cache: initial loading populates it.
        // Manual decoding policy can change through backup restore while the
        // viewer is stopped, without changing the source bytes or display rules.
        return encoding == null ? "auto" : encoding;
    }

    String currentTextDisplayRuleSignature() {
        return textContentTransformSignatureForPath(activity.filePath);
    }

    void requestTextDisplayRuleContentRefreshOnWindowClose() {
        activity.pendingTxtDisplayRuleContentRefresh = true;
    }

    void acknowledgeTextDisplayRuleWindowNoContentChange() {
        // Never re-tag old content with a new transform. The exact signature
        // already ignores metadata that does not affect this file's text.
        if (!currentTextDisplayRuleSignature().equals(activity.appliedTextDisplayRuleSignature))
            requestTextDisplayRuleContentRefreshOnWindowClose();
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
