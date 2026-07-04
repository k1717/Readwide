package com.readwide.manager;

import android.content.Intent;
import android.view.View;

import androidx.annotation.NonNull;

import com.readwide.manager.util.MarkdownTtsFollowMath;

/**
 * Read-aloud integration for the document viewer, extracted from
 * {@link DocumentPageActivity} (which had grown past 4,000 lines with this
 * logic inline). Owns everything about wiring the shared
 * {@link ReaderTtsController} into the document viewer that is not part of the
 * {@code TtsHost} interface itself: the dialog/autostart entry points, the
 * off-thread text-buffer build, the toolbar button visibility, and Markdown's
 * approximate scroll-following.
 *
 * <p>The interface methods stay on the activity (it implements {@code TtsHost})
 * and delegate their Markdown branches here. Playback state that outlives this
 * class's concerns - the {@code ReaderTtsController} instance (released in the
 * activity's {@code onDestroy}), the text buffer (read by the interface
 * methods), and the Markdown speech anchor (read by
 * {@link DocumentTtsTextSource}) - stays on the activity; this class holds only
 * the state that no one else reads: the autostart arm/attempt flags, the
 * in-flight-build flag, and the follow throttle position.</p>
 */
final class DocumentTtsIntegrationController {

    private final DocumentPageActivity activity;

    /** True while an EXTRA_AUTOSTART_TTS intent is waiting for the document to load. */
    private boolean pendingAutoStartTts = false;
    /** The autostart extra is honored once per activity instance. */
    private boolean autoStartTtsConsumed = false;
    private int autoStartTtsAttempts = 0;

    /** True while the plain-text buffer is being built off the main thread. */
    private boolean textBuilding = false;

    /**
     * Last source offset the Markdown follow scrolled to; -1 = none yet. Used
     * both as the search hint for {@link MarkdownTtsFollowMath} and as the
     * throttle reference so following moves about once per visual page.
     */
    private int lastMarkdownTtsFollowOffset = -1;

    DocumentTtsIntegrationController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    // ---- Entry points ------------------------------------------------------

    /**
     * Called from the activity's {@code loadFromIntent} after the previous
     * document's playback state was reset. Arms auto-start when the intent
     * carries {@link DocumentPageActivity#EXTRA_AUTOSTART_TTS} (the
     * "continue reading aloud" resume from the main screen), honored once per
     * activity instance.
     */
    void onLoadFromIntent(Intent intent) {
        if (!autoStartTtsConsumed && intent != null
                && intent.getBooleanExtra(DocumentPageActivity.EXTRA_AUTOSTART_TTS, false)) {
            autoStartTtsConsumed = true;
            pendingAutoStartTts = true;
            autoStartTtsAttempts = 0;
            scheduleAutoStartCheck();
        }
    }

    /**
     * True while the plain-text buffer build is in flight; the activity's
     * {@code isTtsTextTemporarilyUnavailable()} (TtsHost) reports this so the
     * playback controller shows its "waiting for page" retry path instead of
     * failing when playback starts during a build.
     */
    boolean isTextBuilding() {
        return textBuilding;
    }

    /** Stop playback and drop the text buffer; the next open rebuilds both. */
    void reset() {
        if (activity.documentTtsController != null) {
            activity.documentTtsController.stop(false);
        }
        activity.documentTtsTextSource = null;
        textBuilding = false;
        lastMarkdownTtsFollowOffset = -1;
        activity.markdownTtsAnchorCharPosition = -1;
        // A new document invalidates any autostart still pending for the previous
        // one; loadFromIntent re-arms it when the new intent carries the extra.
        pendingAutoStartTts = false;
    }

    /**
     * Entry point from the toolbar button and the More dialog. Builds the
     * plain-text buffer off the main thread on first use (Html.fromHtml per
     * page can be slow on big books), then opens the standard TTS dialog. Also
     * claims the remote bridge so notification/media-button commands reach the
     * owning activity.
     */
    void showDialogEntry() {
        if (!activity.documentSupportsTts()) return;
        TtsPlaybackBridge.register(activity);
        if (activity.documentTtsTextSource != null) {
            activity.documentTts().showDialog();
            return;
        }
        if (textBuilding) {
            ShortToast.show(activity, R.string.tts_waiting_for_page);
            return;
        }
        buildTextSourceThen(() -> activity.documentTts().showDialog());
    }

    /**
     * Shows or hides the bottom-toolbar TTS button. Read-aloud support is only
     * known once pages exist (Markdown included - it fills {@code pages} with
     * its single rendered page), so this runs after wiring and again from the
     * activity's {@code showPage} - the common funnel every document passes
     * through once its pages are ready.
     */
    void updateButtonVisibility() {
        View button = activity.findViewById(R.id.btn_document_tts);
        if (button == null) return;
        button.setVisibility(activity.documentSupportsTts() ? View.VISIBLE : View.GONE);
    }

    // ---- Auto-start on resume ---------------------------------------------

    // Auto-start read-aloud for a "continue reading aloud" resume launched from
    // the main screen. The document (and, for Markdown, its rendered page) loads
    // asynchronously, so poll briefly until pages are ready, then build the text
    // buffer off-thread and begin playback in the saved continuous mode. Gives up
    // quietly after a few seconds if nothing becomes readable.
    private void scheduleAutoStartCheck() {
        View anchor = activity.webView != null
                ? activity.webView : activity.getWindow().getDecorView();
        anchor.postDelayed(() -> {
            if (activity.activityDestroyed || !pendingAutoStartTts) return;
            if (activity.documentSupportsTts()) {
                pendingAutoStartTts = false;
                autostart();
            } else if (++autoStartTtsAttempts <= 40) {
                scheduleAutoStartCheck();
            } else {
                pendingAutoStartTts = false;
            }
        }, 150L);
    }

    /**
     * Builds the read-aloud text buffer if needed (off the main thread), then
     * starts playback. Mirrors {@link #showDialogEntry} but begins playing
     * instead of opening the dialog.
     */
    private void autostart() {
        if (!activity.documentSupportsTts()) return;
        TtsPlaybackBridge.register(activity);
        if (activity.documentTtsTextSource != null) {
            autoStartOrResumeNow();
            return;
        }
        if (textBuilding) {
            // A build is already in flight (e.g. user opened the dialog); retry
            // shortly so autostart rides the same buffer once it's ready. The
            // generation check drops the retry if a different document loads in
            // the meantime - otherwise the closure would auto-play the new
            // document the user never asked to hear.
            final int retryGeneration = activity.loadGeneration;
            View anchor = activity.webView != null
                    ? activity.webView : activity.getWindow().getDecorView();
            anchor.postDelayed(() -> {
                if (!activity.activityDestroyed && retryGeneration == activity.loadGeneration) {
                    autostart();
                }
            }, 150L);
            return;
        }
        buildTextSourceThen(this::autoStartOrResumeNow);
    }

    private void autoStartOrResumeNow() {
        activity.documentTts().autoStartOrResume(
                activity.prefs != null && activity.prefs.getTtsLastContinuous());
    }

    /**
     * Shared off-thread buffer build for the dialog and autostart entry points
     * (they previously duplicated this block verbatim). Snapshot the pages and
     * the load generation, build on the document executor, and run
     * {@code onReady} on the UI thread only if the same document is still
     * loaded.
     */
    private void buildTextSourceThen(@NonNull Runnable onReady) {
        textBuilding = true;
        final int generation = activity.loadGeneration;
        final java.util.List<DocumentPageActivity.Page> snapshot =
                new java.util.ArrayList<>(activity.pages);
        activity.submitDocumentTask(() -> {
            DocumentTtsTextSource built = DocumentTtsTextSource.build(activity, snapshot);
            activity.runOnUiThread(() -> {
                if (activity.activityDestroyed) return;
                if (generation != activity.loadGeneration) {
                    // A different document loaded while we were extracting.
                    textBuilding = false;
                    return;
                }
                activity.documentTtsTextSource = built;
                textBuilding = false;
                onReady.run();
            });
        });
    }

    // ---- Markdown approximate following -------------------------------------

    /**
     * Called as each read-aloud segment starts speaking (via the text source's
     * highlight callback). For Markdown it does two things: advances the speech
     * anchor ({@code markdownTtsAnchorCharPosition}, the position a restart or
     * the end-of-document convergence reads from), and drives approximate
     * following - scrolling only when the spoken position crosses into a
     * different visual page, so the view keeps up without jerking on every
     * sentence. No-op for paged documents, which follow by turning pages at
     * prefetch boundaries instead.
     */
    void onSegmentSpoken(int charPosition, int segmentEndChar) {
        if (!activity.isMarkdownDocument() || activity.documentTtsTextSource == null
                || activity.markdownSourceText == null || activity.markdownSourceText.isEmpty()) {
            return;
        }
        // Track progress past the segment being spoken, so a stop/restart
        // continues from here and the end-of-document state is reachable.
        activity.markdownTtsAnchorCharPosition =
                Math.max(activity.markdownTtsAnchorCharPosition, segmentEndChar);
        String plain = activity.documentTtsTextSource.getTextContent();
        if (plain == null || plain.isEmpty()) return;
        int sourceOffset = activity.clampMarkdownSourceOffset(
                MarkdownTtsFollowMath.approximateSourceOffset(
                        plain, charPosition, activity.markdownSourceText,
                        lastMarkdownTtsFollowOffset));
        // Throttle: only scroll when the target moved enough to likely change the
        // visible viewport. markdownSourceText length / max(1, visualTotalPages)
        // approximates one visual page worth of source chars.
        int total = Math.max(1, activity.markdownVisualTotalPages);
        int perPage = Math.max(1, activity.markdownSourceText.length() / total);
        if (lastMarkdownTtsFollowOffset >= 0
                && Math.abs(sourceOffset - lastMarkdownTtsFollowOffset) < perPage / 2) {
            return;
        }
        lastMarkdownTtsFollowOffset = sourceOffset;
        double ratio = (double) sourceOffset / (double) activity.markdownSourceText.length();
        int fallbackPage = Math.max(0, Math.min(total - 1, (int) Math.round(ratio * (total - 1))));
        activity.scrollMarkdownToSourceOffset(sourceOffset, true, fallbackPage);
    }

    /** Scrolls to approximately the currently spoken position (anchor-aware). */
    void followToCurrentSpokenPosition() {
        if (activity.documentTtsTextSource == null) return;
        followToSourceOffsetForChar(activity.documentTtsTextSource.getCurrentCharPosition());
    }

    /**
     * Maps a read-aloud char position (plain-text buffer space) to a raw Markdown
     * source offset via {@link MarkdownTtsFollowMath} and scrolls there, falling
     * back to a proportional visual page if the JS offset scroll can't resolve.
     */
    void followToSourceOffsetForChar(int charPosition) {
        if (activity.documentTtsTextSource == null || activity.markdownSourceText == null
                || activity.markdownSourceText.isEmpty()) {
            return;
        }
        String plain = activity.documentTtsTextSource.getTextContent();
        if (plain == null || plain.isEmpty()) return;
        int sourceOffset = MarkdownTtsFollowMath.approximateSourceOffset(
                plain, charPosition, activity.markdownSourceText, lastMarkdownTtsFollowOffset);
        sourceOffset = activity.clampMarkdownSourceOffset(sourceOffset);
        lastMarkdownTtsFollowOffset = sourceOffset;
        // Proportional fallback page for when __rwMdScrollToOffset can't place it.
        int total = Math.max(1, activity.markdownVisualTotalPages);
        double ratio = activity.markdownSourceText.length() > 0
                ? (double) sourceOffset / (double) activity.markdownSourceText.length() : 0.0;
        int fallbackPage = Math.max(0, Math.min(total - 1, (int) Math.round(ratio * (total - 1))));
        activity.scrollMarkdownToSourceOffset(sourceOffset, true, fallbackPage);
    }
}
