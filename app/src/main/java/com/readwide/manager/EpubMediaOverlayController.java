package com.readwide.manager;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Foreground-only playback for the basic OPF-linked EPUB media-overlay path.
 *
 * <p>This deliberately does not pretend to be a complete SMIL engine. It plays
 * the validated document-order text/audio cues produced by {@link EpubSmilParser},
 * follows local spine targets, and highlights their fragment IDs. Leaving the
 * activity pauses playback; there is no background service or remote media.</p>
 */
final class EpubMediaOverlayController {
    private static final long PROGRESS_POLL_MS = 45L;

    private final DocumentPageActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;

    private MediaPlayer player;
    private boolean playerPrepared;
    private boolean active;
    private boolean paused;
    private boolean pausedByAudioFocus;
    private boolean waitingForPageLoad;
    private int pageIndex = -1;
    private int cueIndex = -1;
    private int generation;
    private String preparedAudioPath = "";

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (!active) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (pausedByAudioFocus) {
                pausedByAudioFocus = false;
                resume();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (!paused) {
                pausedByAudioFocus = true;
                pause();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            stop(false);
        }
    };

    private final Runnable progressPoll = new Runnable() {
        @Override public void run() {
            if (!active || paused || !playerPrepared || player == null) return;
            EpubSmilParser.Cue cue = currentCue();
            if (cue == null) {
                stop(false);
                return;
            }
            try {
                if (player.getCurrentPosition() + 12L >= cue.clipEndMs) {
                    player.pause();
                    advanceCue();
                    return;
                }
            } catch (IllegalStateException ignored) {
                stop(false);
                return;
            }
            handler.postDelayed(this, PROGRESS_POLL_MS);
        }
    };

    EpubMediaOverlayController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
        audioManager = (AudioManager) activity.getSystemService(
                android.content.Context.AUDIO_SERVICE);
    }

    boolean hasOverlayForPage(int index) {
        return index >= 0 && index < activity.pages.size()
                && activity.pages.get(index) != null
                && activity.pages.get(index).hasMediaOverlay();
    }

    boolean isActive() {
        return active;
    }

    boolean isPaused() {
        return active && paused;
    }

    void toggleCurrentPage() {
        if (active) {
            if (paused) resume(); else pause();
            return;
        }
        startPage(activity.currentPage, false);
    }

    void onDisplayedPageChanged(int oldPage, int newPage) {
        if (!active || oldPage == newPage || newPage == pageIndex) return;
        // A manual page turn transfers narration only when the destination has
        // its own declared overlay. Otherwise stop instead of speaking a hidden
        // previous page.
        if (!hasOverlayForPage(newPage)) {
            stop(false);
            return;
        }
        startPage(newPage, true);
    }

    void onPrimaryPageLoaded(@Nullable WebView view) {
        if (!active || !waitingForPageLoad
                || pageIndex != activity.currentPage || view == null) {
            return;
        }
        waitingForPageLoad = false;
        if (!paused) prepareCurrentCue();
    }

    void pauseForBackground() {
        if (active && !paused) pause();
    }

    void pause() {
        if (!active || paused) return;
        paused = true;
        handler.removeCallbacks(progressPoll);
        if (playerPrepared && player != null) {
            try { player.pause(); } catch (IllegalStateException ignored) {}
        }
        activity.ttsUpdateFloatingCard();
    }

    void resume() {
        if (!active || !paused) return;
        paused = false;
        if (waitingForPageLoad) {
            activity.ttsUpdateFloatingCard();
            return;
        }
        if (playerPrepared && player != null) {
            requestAudioFocus();
            try {
                player.start();
                handler.removeCallbacks(progressPoll);
                handler.post(progressPoll);
            } catch (IllegalStateException ignored) {
                prepareCurrentCue();
            }
        } else {
            prepareCurrentCue();
        }
        activity.ttsUpdateFloatingCard();
    }

    void stop(boolean userInitiated) {
        generation++;
        active = false;
        paused = false;
        pausedByAudioFocus = false;
        waitingForPageLoad = false;
        pageIndex = -1;
        cueIndex = -1;
        preparedAudioPath = "";
        handler.removeCallbacks(progressPoll);
        releasePlayer();
        abandonAudioFocus();
        clearHighlight();
        activity.ttsUpdateFloatingCard();
    }

    void release() {
        stop(false);
        handler.removeCallbacksAndMessages(null);
    }

    private void startPage(int targetPage, boolean waitForPageLoad) {
        if (!hasOverlayForPage(targetPage)) return;
        if (activity.documentTtsController != null
                && activity.documentTtsController.isActive()) {
            activity.documentTtsController.stop(true);
        }
        generation++;
        releasePlayer();
        preparedAudioPath = "";
        active = true;
        paused = false;
        pausedByAudioFocus = false;
        pageIndex = targetPage;
        cueIndex = firstCueForPage(targetPage);
        waitingForPageLoad = waitForPageLoad;
        if (cueIndex < 0) {
            advanceToNextOverlayPage();
            return;
        }
        if (!waitingForPageLoad) prepareCurrentCue();
        activity.ttsUpdateFloatingCard();
    }

    private int firstCueForPage(int targetPage) {
        if (!hasOverlayForPage(targetPage)) return -1;
        DocumentPageActivity.Page page = activity.pages.get(targetPage);
        for (int i = 0; i < page.mediaOverlayTimeline.cues.size(); i++) {
            EpubSmilParser.Cue cue = page.mediaOverlayTimeline.cues.get(i);
            if (activity.epubSourcePathMatches(page.sourcePath, cue.textPath)) return i;
        }
        return -1;
    }

    @Nullable
    private EpubSmilParser.Cue currentCue() {
        if (!hasOverlayForPage(pageIndex)) return null;
        EpubSmilParser.Timeline timeline = activity.pages.get(pageIndex).mediaOverlayTimeline;
        return cueIndex >= 0 && cueIndex < timeline.cues.size()
                ? timeline.cues.get(cueIndex) : null;
    }

    private void prepareCurrentCue() {
        if (!active || paused || waitingForPageLoad) return;
        EpubSmilParser.Cue cue = currentCue();
        if (cue == null) {
            advanceToNextOverlayPage();
            return;
        }
        int cuePage = activity.findEpubPageBySourcePath(cue.textPath);
        if (cuePage != pageIndex) {
            advanceCue();
            return;
        }
        highlight(cue);
        if (cue.audioPath.equals(preparedAudioPath) && playerPrepared && player != null) {
            startPreparedCue(cue);
            return;
        }

        final int expectedGeneration = generation;
        final String audioPath = cue.audioPath;
        activity.submitDocumentTask(() -> {
            File audio = null;
            try {
                audio = activity.extractEpubMediaOverlayAudio(audioPath);
            } catch (Exception ignored) {
                // Unsupported/corrupt local audio skips the cue rather than the book.
            }
            final File preparedFile = audio;
            activity.runOnUiThread(() -> {
                if (!active || paused || expectedGeneration != generation
                        || !audioPath.equals(currentCueAudioPath())) {
                    return;
                }
                if (preparedFile == null) {
                    advanceCue();
                    return;
                }
                preparePlayer(preparedFile, audioPath, expectedGeneration);
            });
        });
    }

    private String currentCueAudioPath() {
        EpubSmilParser.Cue cue = currentCue();
        return cue != null ? cue.audioPath : "";
    }

    private void preparePlayer(@NonNull File file,
                               @NonNull String audioPath,
                               int expectedGeneration) {
        releasePlayer();
        MediaPlayer next = new MediaPlayer();
        player = next;
        playerPrepared = false;
        preparedAudioPath = audioPath;
        try {
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            next.setDataSource(file.getAbsolutePath());
            next.setOnPreparedListener(mp -> {
                if (!active || expectedGeneration != generation || mp != player) {
                    releasePlayer();
                    return;
                }
                playerPrepared = true;
                EpubSmilParser.Cue cue = currentCue();
                if (cue == null) {
                    stop(false);
                    return;
                }
                if (!paused) startPreparedCue(cue);
            });
            next.setOnCompletionListener(mp -> {
                if (active && mp == player) advanceCue();
            });
            next.setOnErrorListener((mp, what, extra) -> {
                if (mp == player && active) {
                    releasePlayer();
                    preparedAudioPath = "";
                    advanceCue();
                }
                return true;
            });
            next.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            advanceCue();
        }
    }

    private void startPreparedCue(@NonNull EpubSmilParser.Cue cue) {
        if (!active || paused || player == null || !playerPrepared) return;
        requestAudioFocus();
        MediaPlayer target = player;
        try {
            int start = (int) Math.min(Integer.MAX_VALUE, cue.clipBeginMs);
            handler.removeCallbacks(progressPoll);
            // MediaPlayer seek is asynchronous on OEM implementations. Starting
            // immediately can briefly leak the previous cue before the requested
            // clip position is committed, especially when consecutive SMIL cues
            // reuse one audio file. Start only from the matching seek callback.
            target.setOnSeekCompleteListener(mp -> {
                if (!active || paused || mp != player || currentCue() != cue) return;
                try {
                    mp.start();
                    handler.removeCallbacks(progressPoll);
                    handler.post(progressPoll);
                } catch (IllegalStateException ignored) {
                    releasePlayer();
                    advanceCue();
                }
            });
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                target.seekTo(start, MediaPlayer.SEEK_CLOSEST);
            } else {
                target.seekTo(start);
            }
        } catch (IllegalStateException ignored) {
            releasePlayer();
            advanceCue();
        }
    }

    private void advanceCue() {
        if (!active) return;
        handler.removeCallbacks(progressPoll);
        EpubSmilParser.Timeline timeline = hasOverlayForPage(pageIndex)
                ? activity.pages.get(pageIndex).mediaOverlayTimeline : null;
        if (timeline == null) {
            stop(false);
            return;
        }
        int next = cueIndex + 1;
        DocumentPageActivity.Page ownerPage = activity.pages.get(pageIndex);
        while (next < timeline.cues.size()) {
            EpubSmilParser.Cue cue = timeline.cues.get(next);
            if (activity.epubSourcePathMatches(ownerPage.sourcePath, cue.textPath)) {
                cueIndex = next;
                prepareCurrentCue();
                return;
            }
            next++;
        }
        advanceToNextOverlayPage();
    }

    private void advanceToNextOverlayPage() {
        for (int i = Math.max(0, pageIndex + 1); i < activity.pages.size(); i++) {
            if (!hasOverlayForPage(i)) continue;
            generation++;
            releasePlayer();
            preparedAudioPath = "";
            pageIndex = i;
            cueIndex = firstCueForPage(i);
            if (cueIndex < 0) continue;
            waitingForPageLoad = true;
            // Media-overlay progression must not be rejected by the short
            // gesture page-turn lock. This is a semantic page change, not a
            // second user gesture, so load without the slide/lock direction.
            activity.showPage(i, 0);
            activity.ttsUpdateFloatingCard();
            return;
        }
        stop(false);
    }

    private void highlight(@NonNull EpubSmilParser.Cue cue) {
        WebView target = activity.webView;
        if (target == null || cue.textFragment.isEmpty()) return;
        activity.evaluateEpubJavascript(
                target,
                activity.currentPage,
                EpubMediaOverlayJavascript.highlight(
                        cue.textFragment,
                        activity.epubPackageResources.mediaOverlayActiveClass));
    }

    private void clearHighlight() {
        WebView target = activity.webView;
        if (target == null || !"EPUB".equals(activity.docType)) return;
        activity.evaluateEpubJavascript(
                target,
                activity.currentPage,
                EpubMediaOverlayJavascript.clear(
                        activity.epubPackageResources.mediaOverlayActiveClass));
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
    }

    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
    }

    private void releasePlayer() {
        playerPrepared = false;
        MediaPlayer old = player;
        player = null;
        if (old == null) return;
        try { old.setOnPreparedListener(null); } catch (Throwable ignored) {}
        try { old.setOnCompletionListener(null); } catch (Throwable ignored) {}
        try { old.setOnErrorListener(null); } catch (Throwable ignored) {}
        try { old.setOnSeekCompleteListener(null); } catch (Throwable ignored) {}
        try { old.stop(); } catch (Throwable ignored) {}
        try { old.release(); } catch (Throwable ignored) {}
    }
}
