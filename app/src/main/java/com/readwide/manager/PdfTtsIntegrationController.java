package com.readwide.manager;

import android.view.View;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * Read-aloud integration for the PDF viewer, extracted from
 * {@link PdfReaderActivity} (the second-largest file in the codebase). Mirrors
 * {@link DocumentTtsIntegrationController} for the document viewer, and is
 * simpler because the PDF viewer has no Markdown following and no
 * autostart-on-resume: it owns the dialog entry point, the off-thread text
 * extraction/build, and the toolbar button visibility.
 *
 * <p>The {@code TtsHost} interface stays on the activity, along with the
 * playback controller instance (released in the activity's {@code onDestroy})
 * and the text buffer (read by the interface). Only the in-flight-build flag,
 * which nothing else reads, lives here.</p>
 */
final class PdfTtsIntegrationController {

    private final PdfReaderActivity activity;

    /** True while the PDF text is being extracted/built off the main thread. */
    private boolean textBuilding = false;
    /** Invalidates callbacks and autostart polls when singleTop replaces the PDF. */
    private int documentGeneration = 0;

    PdfTtsIntegrationController(@NonNull PdfReaderActivity activity) {
        this.activity = activity;
    }

    /** Clears document-scoped async state before a new PDF intent is loaded. */
    void onDocumentChanged() {
        documentGeneration++;
        textBuilding = false;
        pendingAutoStartTts = false;
        autoStartTtsConsumed = false;
        autoStartTtsAttempts = 0;
    }

    /** True while an EXTRA_AUTOSTART_TTS intent is waiting for the PDF to load. */
    private boolean pendingAutoStartTts = false;
    /** The autostart extra is honored once per loaded PDF intent. */
    private boolean autoStartTtsConsumed = false;
    private int autoStartTtsAttempts = 0;

    /**
     * Arms auto-start when the launching intent carries
     * {@link PdfReaderActivity#EXTRA_AUTOSTART_TTS} (the "continue reading aloud"
     * resume from the main screen), honored once per loaded PDF intent. Called
     * from the activity as it reads its intent.
     */
    void onLoadFromIntent(android.content.Intent intent) {
        if (!autoStartTtsConsumed && intent != null
                && intent.getBooleanExtra(PdfReaderActivity.EXTRA_AUTOSTART_TTS, false)) {
            autoStartTtsConsumed = true;
            pendingAutoStartTts = true;
            autoStartTtsAttempts = 0;
            scheduleAutoStartCheck(documentGeneration);
        }
    }

    /**
     * Polls until the PDF is loaded (has pages), then extracts the text buffer
     * off-thread and begins playback, resuming from the saved position when one
     * exists for this file. Gives up quietly after a few seconds.
     */
    private void scheduleAutoStartCheck(int generation) {
        View anchor = activity.getWindow().getDecorView();
        anchor.postDelayed(() -> {
            if (activity.activityDestroyed || generation != documentGeneration
                    || !pendingAutoStartTts) return;
            if (activity.pdfSupportsTts()) {
                pendingAutoStartTts = false;
                autostart();
            } else if (++autoStartTtsAttempts <= 40) {
                scheduleAutoStartCheck(generation);
            } else {
                pendingAutoStartTts = false;
            }
        }, 150L);
    }

    private void autostart() {
        if (!activity.pdfSupportsTts()) return;
        TtsPlaybackBridge.register(activity);
        boolean continuous = activity.prefs != null && activity.prefs.getTtsLastContinuous();
        if (activity.pdfTtsTextSource != null) {
            if (activity.pdfTtsTextSource.hasAnyText()) {
                activity.pdfTts().autoStartOrResume(continuous);
            }
            return;
        }
        if (textBuilding) {
            final int retryDocumentGeneration = documentGeneration;
            View anchor = activity.getWindow().getDecorView();
            anchor.postDelayed(() -> {
                if (!activity.activityDestroyed
                        && retryDocumentGeneration == documentGeneration) {
                    autostart();
                }
            }, 150L);
            return;
        }
        // Build the buffer, then resume/start. Reuses the same off-thread build
        // as the dialog entry point.
        textBuilding = true;
        final File file = activity.localFile;
        final int count = activity.pageCount;
        final int sourceDocumentGeneration = documentGeneration;
        final boolean continuousMode = continuous;
        activity.executor.execute(() -> {
            PdfTtsTextSource builtOrNull;
            try {
                builtOrNull = PdfTtsTextSource.build(activity, file, count);
            } catch (Throwable t) {
                android.util.Log.w("ReadwideTts", "PDF TTS autostart build failed", t);
                builtOrNull = null;
            }
            final PdfTtsTextSource built = builtOrNull;
            activity.handler.post(() -> {
                if (activity.activityDestroyed
                        || sourceDocumentGeneration != documentGeneration) return;
                textBuilding = false;
                if (built != null) activity.pdfTtsTextSource = built;
                if (built == null || !built.hasAnyText()) {
                    // Scanned/image-only: nothing to auto-play. Stay silent
                    // rather than showing the scanned toast on an automatic path.
                    return;
                }
                activity.pdfTts().autoStartOrResume(continuousMode);
            });
        });
    }

    /**
     * True while the extraction/build is in flight; the activity's
     * {@code isTtsTextTemporarilyUnavailable()} (TtsHost) reports this so the
     * playback controller shows its retry path instead of failing when playback
     * starts during a build.
     */
    boolean isTextBuilding() {
        return textBuilding;
    }

    /**
     * Shows or hides the toolbar read-aloud button. Support depends on having an
     * opened file with a positive page count.
     */
    void updateButtonVisibility() {
        View button = activity.findViewById(R.id.pdf_tts);
        if (button == null) return;
        button.setVisibility(activity.pdfSupportsTts() ? View.VISIBLE : View.GONE);
    }

    /**
     * Entry point (toolbar/menu). Extracts the PDF text off the main thread on
     * first use, then opens the standard read-aloud dialog. If the PDF has no
     * extractable text (scanned/image-only), says so and does not start.
     */
    void showDialogEntry() {
        if (!activity.pdfSupportsTts()) return;
        TtsPlaybackBridge.register(activity);
        if (activity.pdfTtsTextSource != null) {
            if (!activity.pdfTtsTextSource.hasAnyText()) {
                ShortToast.show(activity, localized(
                        "This PDF has no selectable text to read aloud (it looks scanned).",
                        "이 PDF에는 읽어줄 수 있는 텍스트가 없습니다(스캔 문서로 보입니다)."));
                return;
            }
            activity.pdfTts().showDialog();
            return;
        }
        if (textBuilding) {
            ShortToast.show(activity, localized("Preparing read-aloud\u2026", "읽어주기 준비 중\u2026"));
            return;
        }
        textBuilding = true;
        final File file = activity.localFile;
        final int count = activity.pageCount;
        final int sourceDocumentGeneration = documentGeneration;
        activity.executor.execute(() -> {
            PdfTtsTextSource builtOrNull;
            try {
                builtOrNull = PdfTtsTextSource.build(activity, file, count);
                android.util.Log.d("ReadwideTts", "PDF TTS buffer built: pages=" + count
                        + ", hasText=" + builtOrNull.hasAnyText()
                        + ", chars=" + builtOrNull.getTextContent().length());
            } catch (Throwable t) {
                // Never let a build failure escape on the bare executor thread
                // (an uncaught throw there kills the process) or leave
                // textBuilding stuck at true (which would freeze the entry point
                // at "Preparing read-aloud..." forever).
                android.util.Log.w("ReadwideTts", "PDF TTS buffer build failed", t);
                builtOrNull = null;
            }
            final PdfTtsTextSource built = builtOrNull;
            activity.handler.post(() -> {
                if (activity.activityDestroyed
                        || sourceDocumentGeneration != documentGeneration) return;
                textBuilding = false;
                if (built != null) {
                    // Cache even a no-text result so a scanned PDF answers later
                    // taps from the fast path instead of re-extracting each time.
                    activity.pdfTtsTextSource = built;
                }
                if (built == null || !built.hasAnyText()) {
                    ShortToast.show(activity, localized(
                            "This PDF has no selectable text to read aloud (it looks scanned).",
                            "이 PDF에는 읽어줄 수 있는 텍스트가 없습니다(스캔 문서로 보입니다)."));
                    return;
                }
                activity.pdfTts().showDialog();
            });
        });
    }

    private String localized(String english, String korean) {
        return activity.isKoreanUi() ? korean : english;
    }
}
