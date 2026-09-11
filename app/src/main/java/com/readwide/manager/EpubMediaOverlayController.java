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
    private final EpubPlaybackGate playbackGate = new EpubPlaybackGate();
    private EpubSmilParser.Cue positionedCue;
    private EpubSmilParser.Cue seekingCue;
    private boolean cueCompleted;
    private boolean waitingForPageLoad;
    private int pageIndex = -1;
    private int cueIndex = -1;
    private int generation;
    private String preparedAudioPath = "";

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (!active) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (playbackGate.focusGain()) resume();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            playbackGate.transientLoss(!paused);
            pauseInternal();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            stop(false);
        }
    };

    private final Runnable progressPoll = new Runnable() {
        @Override public void run() {
            if (!active || paused || !playbackGate.canPlay() || !playerPrepared || player == null) return;
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
        if (activity.isEpubPlaybackForeground()) playbackGate.enterForeground();
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

    void onForeground() {
        playbackGate.enterForeground();
        if (active && pageIndex != activity.currentPage) {
            if (hasOverlayForPage(activity.currentPage)) startPage(activity.currentPage, false);
            else stop(false);
        }
    }

    void pauseForBackground() {
        playbackGate.leaveForeground();
        pauseInternal();
        abandonAudioFocus();
    }

    void pause() {
        playbackGate.cancelAutoResume();
        pauseInternal();
        abandonAudioFocus();
    }

    private void pauseInternal() {
        if (!active || paused) return;
        paused = true;
        handler.removeCallbacks(progressPoll);
        if (playerPrepared && player != null) {
            try { player.pause(); } catch (IllegalStateException ignored) {}
        }
        activity.ttsUpdateFloatingCard();
    }

    void resume() {
        if (!active || !paused || !playbackGate.isForeground()) return;
        if (!requestAudioFocus()) return;
        playbackGate.cancelAutoResume();
        paused = false;
        if (cueCompleted) {
            cueCompleted = false;
            advanceCue();
            return;
        }
        if (waitingForPageLoad) {
            activity.ttsUpdateFloatingCard();
            return;
        }
        if (playerPrepared && player != null) {
            EpubSmilParser.Cue cue = currentCue();
            if (cue == null) { stop(false); return; }
            if (positionedCue != cue) {
                if (seekingCue != cue) startPreparedCue(cue);
                activity.ttsUpdateFloatingCard();
                return;
            }
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
        playbackGate.cancelAutoResume();
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
        playbackGate.leaveForeground();
        stop(false);
        handler.removeCallbacksAndMessages(null);
    }

    private void startPage(int targetPage, boolean waitForPageLoad) {
        if (!playbackGate.isForeground() || !hasOverlayForPage(targetPage)) return;
        boolean keepPaused = active && paused;
        if (activity.documentTtsController != null
                && activity.documentTtsController.isActive()) {
            activity.documentTtsController.stop(true);
        }
        generation++;
        releasePlayer();
        preparedAudioPath = "";
        active = true;
        paused = keepPaused;
        playbackGate.cancelAutoResume();
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
        if (!active || paused || !playbackGate.isForeground() || waitingForPageLoad) return;
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

        // A different audio file must not leave the previous player available
        // to resume() while extraction is pending. Supersede older worker jobs
        // too, including a job queued before a pause/resume during extraction.
        releasePlayer();
        preparedAudioPath = "";
        final int expectedGeneration = ++generation;
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
                if (!active || paused || !playbackGate.isForeground() || expectedGeneration != generation
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
                    // A queued callback from a released player must never release its replacement.
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
                if (active && mp == player) {
                    cueCompleted = true;
                    if (!paused && playbackGate.canPlay()) advanceCue();
                }
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
        if (!active || paused || !playbackGate.isForeground() || player == null || !playerPrepared) return;
        if (!requestAudioFocus()) { pauseInternal(); return; }
        MediaPlayer target = player;
        cueCompleted = false;
        positionedCue = null;
        seekingCue = cue;
        try {
            int start = (int) Math.min(Integer.MAX_VALUE, cue.clipBeginMs);
            handler.removeCallbacks(progressPoll);
            // MediaPlayer seek is asynchronous on OEM implementations. Starting
            // immediately can briefly leak the previous cue before the requested
            // clip position is committed, especially when consecutive SMIL cues
            // reuse one audio file. Start only from the matching seek callback.
            target.setOnSeekCompleteListener(mp -> {
                if (!active || mp != player || currentCue() != cue) return;
                seekingCue = null;
                positionedCue = cue;
                if (paused || !playbackGate.canPlay()) return;
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
        if (!active || paused || !playbackGate.isForeground()) return;
        // Completion belongs to the old cue, not to a pending next-file load.
        cueCompleted = false;
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
        if (!active || paused || !playbackGate.isForeground()) return;
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

    private boolean requestAudioFocus() {
        if (!playbackGate.isForeground()) return false;
        if (playbackGate.canPlay()) return true;
        boolean granted = audioManager != null && audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return playbackGate.recordFocusRequest(granted);
    }

    private void abandonAudioFocus() {
        playbackGate.abandonFocus();
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
    }

    private void releasePlayer() {
        cueCompleted = false;
        positionedCue = null;
        seekingCue = null;
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
