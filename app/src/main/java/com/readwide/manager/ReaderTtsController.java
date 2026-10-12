package com.readwide.manager;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ReaderTtsController implements TextToSpeech.OnInitListener {
    private static final int MAX_TTS_SEGMENT_CHARS = 700;
    private static final long NEXT_PAGE_DELAY_MS = 320L;
    private static final long PARTITION_RETRY_DELAY_MS = 220L;
    private static final int MAX_PARTITION_RETRIES = 28;
    private static final int REQUEST_TTS_NOTIFICATION_PERMISSION = 2202;
    private static final String ACTION_ANDROID_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS";
    // Logcat tag for read-aloud diagnostics (engine init, language/voice results,
    // queue sizes, speak() failures, dropped callbacks). Helps triage "silent
    // fail" reports from release builds via `adb logcat -s ReadwideTts`.
    private static final String TTS_LOG_TAG = "ReadwideTts";

    private final TtsHost host;
    private final androidx.appcompat.app.AppCompatActivity activity;

    private TextToSpeech tts;
    private boolean initialized = false;
    private boolean initializing = false;
    private boolean pendingStart = false;
    private boolean pendingResume = false;
    private boolean pendingContinuous = false;
    private boolean pendingVoiceDialog = false;
    private boolean active = false;
    private boolean continuous = false;
    private boolean notificationPermissionRequested = false;
    private int speechGeneration = 0;
    private String lastQueuedUtteranceId = "";
    private final TtsSpeechQueue queuedSegments = new TtsSpeechQueue();
    private String queueText = "";
    private int queueTextCursor;
    private int queueTextLimit;
    private int queueBaseChar;
    private String queuePath = "", queueSignature = "";
    private int nextSpeechChar = -1;
    private int requestedStartChar = -1;
    private String speakingUtteranceId = "";
    private String lastStartedUtteranceId = "";

    // Real pause/resume: keep active, remember where we stopped, continue from there.
    private boolean paused = false;
    private boolean pausedForFocusLoss = false;     // paused due to transient focus loss (auto-resume on regain)
    private TtsSleepTimerDialog sleepTimerDialog;

    private TtsSleepTimerDialog sleepTimerDialog() {
        if (sleepTimerDialog == null) {
            sleepTimerDialog = new TtsSleepTimerDialog(host, this);
        }
        return sleepTimerDialog;
    }
    private int currentSegmentIndex = 0;            // segment currently being spoken
    private int pausedSegmentIndex = 0;             // segment to resume from
    private int currentSpeechGeneration = 0;        // generation of the queued page

    // Current window plus one lookahead; -1 means no lookahead is queued.
    // Retiring the spoken prefix adjusts the index, never the engine sequence ID.
    private static final int PREFETCH_NEXT_PAGE_CHARS = 1400;
    private static final int MAX_WINDOW_SEGMENTS = 32;
    private int prefetchedNextPageBoundaryIndex = -1;

    // Sleep timer (counts playback time only; paused time does not accrue).
    private final TtsPlaybackTimer playbackTimer = new TtsPlaybackTimer();
    private boolean sleepTimerAtSentenceBoundary = true;
    private Runnable sleepTimerDeadline;

    // Audio focus + headphone-unplug handling.
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean audioFocusHeld = false;
    private boolean noisyReceiverRegistered = false;
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                // Headphones unplugged / BT disconnected — pause (don't stop) so the
                // audio doesn't blast the speaker and the user can resume after
                // reconnecting. There's no system signal when audio is replugged, so
                // resume is manual (floating card / notification / media button).
                if (active && !paused) {
                    pausePlayback();
                }
            }
        }
    };
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener;

    ReaderTtsController(@NonNull TtsHost host) {
        this.host = host;
        this.activity = host.ttsHostActivity();
        this.audioFocusListener = focusChange -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // Permanent loss — another app took over audio. Stop and yield.
                    if (active) {
                        this.activity.runOnUiThread(() -> {
                            if (!active) return;
                            pausedForFocusLoss = false;
                            stop(false);
                            clearTtsHighlight();
                            TtsPlaybackService.stop(this.activity.getApplicationContext());
                        });
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Transient loss (phone call, nav prompt, notification). Pause and
                    // remember to auto-resume when focus comes back.
                    if (active && !paused) {
                        this.activity.runOnUiThread(() -> {
                            if (!active || paused) return;
                            pausePlayback();
                            pausedForFocusLoss = true;
                        });
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    // Focus returned after a transient loss — resume if we paused for it.
                    if (pausedForFocusLoss && active && paused) {
                        this.activity.runOnUiThread(() -> {
                            if (!pausedForFocusLoss || !active || !paused) return;
                            pausedForFocusLoss = false;
                            resumePlayback();
                        });
                    }
                    break;
                default:
                    break;
            }
        };
    }

    void showDialog() {
        host.ttsHostDialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = host.ttsHostDialogStyler().readerDialogBgColor();
        final int fg = host.ttsHostDialogStyler().readerDialogTextColor(bg);
        final int sub = host.ttsHostDialogStyler().readerDialogSubTextColor(bg);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        TextView title = host.ttsHostDialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_title), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(activity);
        desc.setText(R.string.tts_description);
        desc.setTextColor(sub);
        desc.setTextSize(13f);
        desc.setLineSpacing(0, 1.08f);
        desc.setPadding(host.ttsHostDpToPx(18), host.ttsHostDpToPx(4),
                host.ttsHostDpToPx(18), host.ttsHostDpToPx(14));
        panel.addView(desc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        LinearLayout languageBox = TtsDialogViews.makeOptionBox(host);
        final TextView[] voiceButtonRef = new TextView[1];
        TextView languageButton = host.ttsHostDialogStyler().makeReaderActionRow(
                currentLanguageRowLabel(), fg);
        languageButton.setGravity(Gravity.CENTER);
        languageButton.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        languageButton.setOnClickListener(v -> showLanguageDialog(() -> {
            languageButton.setText(currentLanguageRowLabel());
            if (voiceButtonRef[0] != null) {
                voiceButtonRef[0].setText(currentVoiceRowLabel());
            }
        }));
        languageBox.addView(languageButton);

        TextView voiceButton = host.ttsHostDialogStyler().makeReaderActionRow(
                currentVoiceRowLabel(), fg);
        voiceButtonRef[0] = voiceButton;
        voiceButton.setGravity(Gravity.CENTER);
        voiceButton.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        voiceButton.setOnClickListener(v -> showVoiceDialog(() ->
                voiceButton.setText(currentVoiceRowLabel())));
        languageBox.addView(voiceButton);

        if (hasResumeStateForCurrentFile()) {
            TextView resumeButton = host.ttsHostDialogStyler().makeReaderActionRow(
                    resumeRowLabel(), fg);
            resumeButton.setGravity(Gravity.CENTER);
            resumeButton.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
            resumeButton.setOnClickListener(v -> {
                resumeFromSavedState();
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
            languageBox.addView(resumeButton);
        }

        TtsDialogViews.addPercentSlider(host, languageBox,
                activity.getString(R.string.tts_speed),
                host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsSpeechRatePercent() : 100,
                value -> {
                    if (host.ttsHostPrefs() != null) host.ttsHostPrefs().setTtsSpeechRatePercent(value);
                    applySpeechParameters();
                },
                fg,
                sub);
        TtsDialogViews.addPercentSlider(host, languageBox,
                activity.getString(R.string.tts_pitch),
                host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsPitchPercent() : 100,
                value -> {
                    if (host.ttsHostPrefs() != null) host.ttsHostPrefs().setTtsPitchPercent(value);
                    applySpeechParameters();
                },
                fg,
                sub);

        // Phrase length (chunk size) and pause reduction are tap-to-cycle rows:
        // both are text-level controls that take effect on the next queued page,
        // so they need no live re-synthesis. They help high-latency neural engines
        // (e.g. Kokoro) that either over-pause at punctuation or add latency on
        // long chunks; see issue #7.
        final TextView[] phraseRowRef = new TextView[1];
        TextView phraseRow = host.ttsHostDialogStyler().makeReaderActionRow(
                phraseLengthRowLabel(), fg);
        phraseRow.setGravity(Gravity.CENTER);
        phraseRow.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        phraseRowRef[0] = phraseRow;
        phraseRow.setOnClickListener(v -> {
            if (host.ttsHostPrefs() != null) {
                int next = (host.ttsHostPrefs().getTtsPhraseLengthLevel() + 1) % 3;
                host.ttsHostPrefs().setTtsPhraseLengthLevel(next);
            }
            if (phraseRowRef[0] != null) phraseRowRef[0].setText(phraseLengthRowLabel());
        });
        languageBox.addView(phraseRow);

        final TextView[] pauseRowRef = new TextView[1];
        TextView pauseRow = host.ttsHostDialogStyler().makeReaderActionRow(
                pauseReductionRowLabel(), fg);
        pauseRow.setGravity(Gravity.CENTER);
        pauseRow.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        pauseRowRef[0] = pauseRow;
        pauseRow.setOnClickListener(v -> {
            if (host.ttsHostPrefs() != null) {
                int next = (host.ttsHostPrefs().getTtsPauseReduction() + 1) % 3;
                host.ttsHostPrefs().setTtsPauseReduction(next);
            }
            if (pauseRowRef[0] != null) pauseRowRef[0].setText(pauseReductionRowLabel());
        });
        languageBox.addView(pauseRow);

        TextView sleepTimerButton = host.ttsHostDialogStyler().makeReaderActionRow(
                sleepTimerDialog().rowLabel(), fg);
        sleepTimerButton.setGravity(Gravity.CENTER);
        sleepTimerButton.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        sleepTimerButton.setOnClickListener(v -> sleepTimerDialog().show(() ->
                sleepTimerButton.setText(sleepTimerDialog().rowLabel())));
        languageBox.addView(sleepTimerButton);

        TextView systemSettings = host.ttsHostDialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_android_settings), fg);
        systemSettings.setGravity(Gravity.CENTER);
        systemSettings.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        systemSettings.setOnClickListener(v -> openAndroidTtsSettings());
        languageBox.addView(systemSettings);

        TextView addVoice = host.ttsHostDialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_add_voice_model), fg);
        addVoice.setGravity(Gravity.CENTER);
        addVoice.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        addVoice.setOnClickListener(v -> openAndroidTtsVoiceInstall());
        languageBox.addView(addVoice);

        panel.addView(languageBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams optionLp = (LinearLayout.LayoutParams) languageBox.getLayoutParams();
        optionLp.setMargins(host.ttsHostDpToPx(18), 0, host.ttsHostDpToPx(18), host.ttsHostDpToPx(10));
        languageBox.setLayoutParams(optionLp);

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(host.ttsHostDialogStyler().positionedActionPanelBackground(
                host.ttsHostDialogStyler().dialogActionPanelFillColor(bg),
                host.ttsHostDialogStyler().dialogActionPanelLineColor(bg)));
        actionRow.setPadding(host.ttsHostDpToPx(8), 0, host.ttsHostDpToPx(8), 0);

        TextView stopButton = host.ttsHostDialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.tts_stop), sub, Gravity.CENTER);
        TextView pageButton = host.ttsHostDialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.tts_read_page), fg, Gravity.CENTER);
        TextView continuousButton = host.ttsHostDialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.tts_read_continuous), fg, Gravity.CENTER);

        // Pause/Resume is only meaningful while playback is active.
        TextView pauseButton = null;
        if (active) {
            pauseButton = host.ttsHostDialogStyler().makeReaderDialogActionText(
                    activity.getString(paused ? R.string.tts_resume : R.string.tts_pause),
                    fg, Gravity.CENTER);
        }

        java.util.ArrayList<TextView> actionButtons = new java.util.ArrayList<>();
        actionButtons.add(stopButton);
        if (pauseButton != null) actionButtons.add(pauseButton);
        actionButtons.add(pageButton);
        actionButtons.add(continuousButton);
        for (TextView actionButton : actionButtons) {
            actionButton.setTextSize(12.5f);
            actionButton.setPadding(host.ttsHostDpToPx(8), 0, host.ttsHostDpToPx(8), 0);
            actionButton.setSingleLine(true);
        }

        actionRow.addView(stopButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        if (pauseButton != null) {
            actionRow.addView(pauseButton, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        }
        actionRow.addView(pageButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        actionRow.addView(continuousButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        panel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                host.ttsHostDpToPx(54)));

        android.app.Dialog dialog = host.ttsHostDialogStyler().createNarrowPositionedReaderDialog(
                panel,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                74,
                0.78f,
                460,
                true);
        dialogRef[0] = dialog;

        stopButton.setOnClickListener(v -> {
            stop(true);
            dialog.dismiss();
        });
        if (pauseButton != null) {
            pauseButton.setOnClickListener(v -> {
                if (paused) resumePlayback();
                else pausePlayback();
                dialog.dismiss();
            });
        }
        pageButton.setOnClickListener(v -> {
            start(false);
            dialog.dismiss();
        });
        continuousButton.setOnClickListener(v -> {
            start(true);
            dialog.dismiss();
        });
        dialog.show();
    }

    void start(boolean continuousMode) {
        start(continuousMode, -1);
    }

    private void start(boolean continuousMode, int absoluteStart) {
        requestedStartChar = absoluteStart;
        pendingResume = false;
        TtsTextSource source = host.ttsTextSource();
        if (source == null || TextUtils.isEmpty(source.getTextContent())) {
            ShortToast.show(activity, R.string.tts_no_text);
            return;
        }

        host.ttsStopAutoPageTurn();
        requestNotificationPermissionIfNeeded();
        pendingStart = true;
        pendingContinuous = continuousMode;

        if (tts == null) {
            ensureEngine();
            return;
        }

        if (!initialized) {
            ShortToast.show(activity, R.string.tts_initializing);
            return;
        }

        startNow(continuousMode);
    }

    void stop(boolean showToast) {
        stopInternal(showToast, true);
    }

    private void stopInternal(boolean showToast, boolean stopService) {
        pendingStart = false;
        pendingResume = false;
        active = false;
        paused = false;
        pausedForFocusLoss = false;
        speechGeneration++;
        lastQueuedUtteranceId = "";
        queuedSegments.clear();
        queueText = "";
        queueTextCursor = queueTextLimit = 0;
        queueBaseChar = 0;
        queuePath = queueSignature = "";
        nextSpeechChar = requestedStartChar = -1;
        speakingUtteranceId = "";
        lastStartedUtteranceId = "";
        cancelSleepTimerDeadline();
        clearTtsHighlight();
        host.ttsUpdateFloatingCard();
        unregisterNoisyReceiver();
        abandonAudioFocus();
        playbackTimer.pause(android.os.SystemClock.elapsedRealtime());
        if (tts != null) {
            tts.stop();
        }
        if (stopService) {
            TtsPlaybackService.stop(activity.getApplicationContext());
            continuous = false;
        } else {
            updatePlaybackNotification(false);
        }
        if (showToast) {
            ShortToast.show(activity, R.string.tts_stopped);
        }
    }



    void release() {
        stop(false);
        unregisterNoisyReceiver();
        abandonAudioFocus();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        initialized = false;
        initializing = false;
    }

    boolean isActive() {
        return active || pendingStart || pendingResume;
    }

    /** True pause: stop speaking but keep state/focus/timer so we can resume in place. */
    void pausePlayback() {
        pausedForFocusLoss = false;
        if (pendingStart || pendingResume) {
            stopInternal(false, false);
            return;
        }
        if (!active || paused) return;
        sleepTimerAccrueSegment(); // stop accruing playback time while paused
        boolean completedCurrent = speakingUtteranceId.isEmpty()
                && currentSegmentIndex >= 0 && currentSegmentIndex < queuedSegments.size()
                && TextUtils.equals(lastStartedUtteranceId,
                        utteranceId(currentSpeechGeneration, queuedSegments.sequenceAt(currentSegmentIndex)));
        pausedSegmentIndex = completedCurrent && currentSegmentIndex + 1 < queuedSegments.size()
                ? currentSegmentIndex + 1 : currentSegmentIndex;
        paused = true;
        // Invalidate engine callbacks immediately, including ones already posted to UI.
        currentSpeechGeneration = ++speechGeneration;
        speakingUtteranceId = "";
        if (tts != null) tts.stop(); // flushes the queue; segments are remembered
        host.ttsUpdateFloatingCard();
        // Persist the paused position so it survives leaving the app.
        if (host.ttsHostPrefs() != null && host.ttsHostFilePath() != null
                && pausedSegmentIndex >= 0 && pausedSegmentIndex < queuedSegments.size()) {
            TtsSpeechSegment seg = queuedSegments.get(pausedSegmentIndex);
            saveQueueCheckpoint(queueAbsolute(seg.startChar));
        } else if (nextSpeechChar >= 0) {
            saveQueueCheckpoint(nextSpeechChar);
        }
        updatePlaybackNotification(false);
    }

    /** Resume from the segment we paused on, without reloading the page or jumping. */
    void resumePlayback() {
        if (!active || !paused) return;
        paused = false;
        if (tts == null) {
            // Fall back to a fresh start if we lost the engine.
            start(continuous);
            return;
        }
        if (!applySelectedLanguage(false)) {
            stop(false);
            return;
        }
        applySpeechParameters();
        if (queuedSegments.isEmpty()) {
            // A partition/page transition may have been paused before its text
            // was ready. Rebuild at that destination, retaining the sleep timer.
            currentSpeechGeneration = ++speechGeneration;
            speakCurrentPage(currentSpeechGeneration, 0);
            if (active) {
                updatePlaybackNotification(true);
                host.ttsUpdateFloatingCard();
            }
            return;
        }
        // Keep the lookahead boundary across resume; it drives UI following and
        // prevents appending a second lookahead before the first is reached.
        int from = Math.max(0, Math.min(pausedSegmentIndex, queuedSegments.size() - 1));
        currentSegmentIndex = from;
        currentSpeechGeneration = ++speechGeneration;
        speakQueuedFrom(from);
        if (!active) return; // speak() failure already stopped the session.
        updatePlaybackNotification(true);
        host.ttsUpdateFloatingCard();
    }

    /** Re-enqueue the already-segmented current page starting at the given index. */
    private void speakQueuedFrom(int fromIndex) {
        if (tts == null || queuedSegments.isEmpty()) return;
        int generation = currentSpeechGeneration;
        lastQueuedUtteranceId = utteranceId(generation, queuedSegments.sequenceAt(queuedSegments.size() - 1));
        for (int i = fromIndex; i < queuedSegments.size(); i++) {
            Bundle params = new Bundle();
            String utteranceId = utteranceId(generation, queuedSegments.sequenceAt(i));
            int queueMode = (i == fromIndex) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = tts.speak(queuedSegments.get(i).speechText, queueMode, params, utteranceId);
            if (result == TextToSpeech.ERROR) {
                android.util.Log.d(TTS_LOG_TAG, "speak() ERROR at segment " + i
                        + " (resume from " + fromIndex + "), stopping");
                stop(false);
                ShortToast.show(activity, R.string.tts_engine_unavailable);
                return;
            }
        }
    }

    boolean isPaused() {
        return paused;
    }

    void handlePlaybackCommand(@NonNull String action) {
        if (TtsPlaybackService.ACTION_STOP.equals(action)) {
            stop(true);
        } else if (TtsPlaybackService.ACTION_PLAY.equals(action)) {
            if (active) {
                if (paused) resumePlayback();
            } else if (!pendingStart && !pendingResume) {
                if (hasResumeStateForCurrentFile()) {
                    resumeFromSavedState();
                } else {
                    start(continuous || (host.ttsHostPrefs() != null && host.ttsHostPrefs().getTtsLastContinuous()));
                }
            }
        } else if (TtsPlaybackService.ACTION_PAUSE.equals(action)) {
            pausePlayback();
        } else if (TtsPlaybackService.ACTION_PLAY_PAUSE.equals(action)) {
            if (active && !paused) {
                pausePlayback();
            } else if (active && paused) {
                resumePlayback();
            } else if (pendingStart || pendingResume) {
                stopInternal(false, false);
            } else if (hasResumeStateForCurrentFile()) {
                resumeFromSavedState();
            } else {
                start(continuous || (host.ttsHostPrefs() != null && host.ttsHostPrefs().getTtsLastContinuous()));
            }
        } else if (TtsPlaybackService.ACTION_NEXT.equals(action)) {
            movePageFromRemote(+1);
        } else if (TtsPlaybackService.ACTION_PREVIOUS.equals(action)) {
            movePageFromRemote(-1);
        }
    }

    private boolean hasResumeStateForCurrentFile() {
        if (host.ttsHostPrefs() == null) return false;
        if (host.isTtsTextTemporarilyUnavailable()) return false;
        if (!hasSavedCheckpoint(host.ttsHostFilePath(), host.ttsHostPrefs().getTtsLastFilePath(),
                host.ttsHostPrefs().getTtsLastTimestamp())) return false;
        String signature = host.ttsCheckpointSignature();
        if (!signature.isEmpty() && !signature.equals(host.ttsHostPrefs().getTtsLastContentSignature())) {
            // Legacy partition-relative checkpoints cannot be safely interpreted
            // as absolute offsets. Font/reflow changes do not affect this signature.
            host.ttsHostPrefs().clearTtsLastPlaybackState();
            return false;
        }
        return true;
    }

    static boolean hasSavedCheckpoint(String path, String savedPath, long timestamp) {
        // Offset zero is a real checkpoint (including page one), not an absence marker.
        return path != null && !path.isEmpty() && path.equals(savedPath) && timestamp > 0L;
    }

    private String resumeRowLabel() {
        int page = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsLastPageNumber() : 1;
        return activity.getString(R.string.tts_resume_from_page, Math.max(1, page));
    }

    /**
     * Entry point for auto-start-on-open (the "continue reading aloud" resume
     * launched from the main screen). If saved playback state exists for the
     * current file, resume from that exact position (which also scrolls the host
     * to follow); otherwise start from the top. Public so host activities can
     * trigger it once their text buffer is ready.
     */
    void autoStartOrResume(boolean continuousMode) {
        if (hasResumeStateForCurrentFile()) {
            resumeFromSavedState();
        } else {
            start(continuousMode);
        }
    }

    private void resumeFromSavedState() {
        if (!hasResumeStateForCurrentFile()) {
            start(host.ttsHostPrefs() != null && host.ttsHostPrefs().getTtsLastContinuous());
            return;
        }
        int charPosition = host.ttsHostPrefs().getTtsLastCharPosition();
        boolean resumeContinuous = host.ttsHostPrefs().getTtsLastContinuous();
        if (active || paused || pendingStart) stopInternal(false, false);
        host.ttsJumpToAbsoluteCharPosition(charPosition,
                host.ttsHostPrefs().getTtsLastPageNumber(),
                host.ttsDisplayedTotalPageCount());
        // Make the delayed restart cancellable. A Stop command or a newer resume
        // request increments speechGeneration, so this task cannot resurrect TTS
        // after the user has explicitly stopped it while the page is settling.
        int resumeRequestGeneration = ++speechGeneration;
        pendingResume = true;
        host.ttsHostHandler().postDelayed(() -> {
            if (!host.isTtsHostDestroyed()
                    && speechGeneration == resumeRequestGeneration
                    && pendingResume && !active && !paused) {
                pendingResume = false;
                start(resumeContinuous, host.ttsUsesAbsoluteTextContinuation() ? charPosition : -1);
            }
        }, 420L);
    }

    @Override
    public void onInit(int status) {
        activity.runOnUiThread(() -> {
            if (host.isTtsHostDestroyed()) {
                initializing = false;
                pendingStart = false;
                pendingVoiceDialog = false;
                return;
            }
            initializing = false;
            android.util.Log.d(TTS_LOG_TAG, "engine init: status=" + status
                    + (status == TextToSpeech.SUCCESS ? " (SUCCESS)" : " (FAILED)")
                    + ", engine=" + (tts != null ? tts.getDefaultEngine() : "null"));
            if (status != TextToSpeech.SUCCESS || tts == null) {
                initialized = false;
                pendingStart = false;
                ShortToast.show(activity, R.string.tts_engine_unavailable);
                return;
            }

            initialized = true;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    handleUtteranceStart(utteranceId);
                }

                @Override
                public void onDone(String utteranceId) {
                    handleUtteranceDone(utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    handleUtteranceError(utteranceId);
                }
            });

            if (pendingStart) {
                boolean startContinuous = pendingContinuous;
                pendingStart = false;
                startNow(startContinuous);
            } else if (pendingVoiceDialog) {
                pendingVoiceDialog = false;
                showVoiceDialog(null);
            }
        });
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return true; // can't manage focus; don't block playback
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .setWillPauseWhenDucked(true)
                    .build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(audioFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return audioFocusHeld;
    }

    private void abandonAudioFocus() {
        if (audioManager == null || !audioFocusHeld) { audioFocusHeld = false; return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusListener);
        }
        audioFocusHeld = false;
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        try {
            activity.registerReceiver(becomingNoisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        } catch (Exception ignored) { }
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        try {
            activity.unregisterReceiver(becomingNoisyReceiver);
        } catch (Exception ignored) { }
        noisyReceiverRegistered = false;
    }

    private void initSleepTimer() {
        int minutes = host.ttsHostPrefs() == null ? 0 : host.ttsHostPrefs().getTtsSleepTimerMinutes();
        boolean finish = host.ttsHostPrefs() == null || host.ttsHostPrefs().getTtsSleepTimerFinishSentence();
        playbackTimer.reset(minutes, finish);
        sleepTimerAtSentenceBoundary = true;
        cancelSleepTimerDeadline();
    }

    /** Re-read settings without losing elapsed playback or charging paused time. */
    void refreshSleepTimerFromPrefs() {
        if (!active || host.ttsHostPrefs() == null) return;
        playbackTimer.configure(host.ttsHostPrefs().getTtsSleepTimerMinutes(),
                host.ttsHostPrefs().getTtsSleepTimerFinishSentence());
        scheduleSleepTimerDeadline();
    }

    private void sleepTimerOnSegmentStart() {
        playbackTimer.start(android.os.SystemClock.elapsedRealtime());
        sleepTimerAtSentenceBoundary = false;
        scheduleSleepTimerDeadline();
    }

    private void sleepTimerAccrueSegment() {
        cancelSleepTimerDeadline();
        playbackTimer.pause(android.os.SystemClock.elapsedRealtime());
    }

    private boolean sleepTimerCheckAndMaybeStop() {
        if (!playbackTimer.shouldStop(android.os.SystemClock.elapsedRealtime(), sleepTimerAtSentenceBoundary)) {
            return false;
        }
        stop(false);
        ShortToast.show(activity, R.string.tts_sleep_timer_finished);
        return true;
    }

    private void cancelSleepTimerDeadline() {
        if (sleepTimerDeadline != null) {
            host.ttsHostHandler().removeCallbacks(sleepTimerDeadline);
            sleepTimerDeadline = null;
        }
    }

    private void scheduleSleepTimerDeadline() {
        cancelSleepTimerDeadline();
        if (!active || paused) return;
        long delay = playbackTimer.deadlineDelay(android.os.SystemClock.elapsedRealtime());
        if (delay < 0L) return;
        int generation = speechGeneration;
        sleepTimerDeadline = () -> {
            if (!active || paused || host.isTtsHostDestroyed() || generation != speechGeneration) return;
            sleepTimerAccrueSegment();
            if (!sleepTimerCheckAndMaybeStop()) sleepTimerOnSegmentStart();
        };
        host.ttsHostHandler().postDelayed(sleepTimerDeadline, delay);
    }

    private void startNow(boolean continuousMode) {
        if (tts == null || !initialized) return;
        // Retire the old queue before language/focus checks or an asynchronous page wait.
        // Merely clearing active leaves already submitted speech playing after a failed restart.
        int initialChar = requestedStartChar;
        stop(false);
        nextSpeechChar = initialChar;
        if (!applySelectedLanguage(true)) {
            return;
        }
        applySpeechParameters();
        active = true;
        paused = false;
        continuous = continuousMode;
        if (!requestAudioFocus()) {
            // Another app holds exclusive audio focus; don't start over it.
            stop(false);
            ShortToast.show(activity, R.string.tts_stopped);
            return;
        }
        registerNoisyReceiver();
        initSleepTimer();
        int generation = ++speechGeneration;
        updatePlaybackNotification(true);
        ShortToast.show(activity, continuousMode ? R.string.tts_continuous_started : R.string.tts_started);
        host.ttsUpdateFloatingCard();
        speakCurrentPage(generation, 0);
    }

    private void speakCurrentPage(int generation, int partitionRetryCount) {
        if (!active || paused || generation != speechGeneration || host.isTtsHostDestroyed()) return;

        if (host.isTtsTextTemporarilyUnavailable()) {
            if (partitionRetryCount == 0) {
                ShortToast.show(activity, R.string.tts_waiting_for_page);
            }
            if (partitionRetryCount < MAX_PARTITION_RETRIES) {
                host.ttsHostHandler().postDelayed(
                        () -> speakCurrentPage(generation, partitionRetryCount + 1),
                        PARTITION_RETRY_DELAY_MS);
            } else {
                stop(false);
            }
            return;
        }

        VisiblePage page = currentVisiblePage();
        if (nextSpeechChar >= 0 && host.ttsUsesAbsoluteTextContinuation()) {
            TtsTextSource source = host.ttsTextSource();
            String text = source != null ? source.getTextContent() : null;
            int base = host.ttsTextBaseCharPosition();
            long local = (long) nextSpeechChar - base;
            if (text == null || local < 0 || local >= text.length()) {
                if (!host.ttsHasTextAfter(nextSpeechChar)) {
                    handleNoSpeakableTextOnCurrentPage(generation);
                    return;
                }
                if (partitionRetryCount >= MAX_PARTITION_RETRIES) { stop(false); return; }
                host.ttsJumpToAbsoluteCharPosition(nextSpeechChar);
                host.ttsHostHandler().postDelayed(() -> speakCurrentPage(generation, partitionRetryCount + 1),
                        PARTITION_RETRY_DELAY_MS);
                return;
            }
            int start = (int) local;
            int end = page.endChar > start ? page.endChar
                    : (int) Math.min(text.length(), (long) start + PREFETCH_NEXT_PAGE_CHARS);
            page = new VisiblePage(start, end);
        }
        if (page.isEmpty()) {
            if (host.ttsUsesAbsoluteTextContinuation()) {
                // No old queue/page may determine EOF or the next window here.
                TtsTextSource source = host.ttsTextSource();
                queueText = source != null && source.getTextContent() != null ? source.getTextContent() : "";
                queueBaseChar = host.ttsTextBaseCharPosition();
                queuePath = host.ttsHostFilePath();
                queueSignature = host.ttsCheckpointSignature();
                queueTextLimit = page.endChar;
            }
            handleNoSpeakableTextOnCurrentPage(generation);
            return;
        }

        queueSpeechSegments(page);
    }

    private void handleNoSpeakableTextOnCurrentPage(int generation) {
        if (continuous && !host.isTtsTextFullyResident() && canAdvancePage()) {
            advanceAndSpeakNextPage(generation);
            return;
        }
        if (continuous) {
            // Reaching a blank final page is still a normal end-of-document,
            // not a "no text" error for the whole book.
            if (host.ttsHostPrefs() != null) host.ttsHostPrefs().clearTtsLastPlaybackState();
            stop(false);
            clearTtsHighlight();
            TtsPlaybackService.stop(activity.getApplicationContext());
            ShortToast.show(activity, R.string.tts_finished);
            return;
        }
        stop(false);
        ShortToast.show(activity, R.string.tts_no_text);
    }

    private void queueSpeechSegments(@NonNull VisiblePage page) {
        if (tts == null) return;
        TtsTextSource source = host.ttsTextSource();
        if (source == null || source.getTextContent() == null) return;
        queueText = source.getTextContent();
        queueBaseChar = host.ttsTextBaseCharPosition();
        queuePath = host.ttsHostFilePath();
        queueSignature = host.ttsCheckpointSignature();
        nextSpeechChar = -1;
        queueTextCursor = Math.min(queueText.length(), page.startChar);
        queueTextLimit = continuous && host.isTtsTextFullyResident()
                ? queueText.length() : Math.min(queueText.length(), page.endChar);
        queuedSegments.clear();
        // A new page queue must not reuse IDs from a completed page.
        currentSpeechGeneration = ++speechGeneration;
        currentSegmentIndex = pausedSegmentIndex = 0;
        speakingUtteranceId = "";
        prefetchedNextPageBoundaryIndex = -1;
        appendSpeechWindow(currentSpeechGeneration, true);
        if (!active) return;
        if (queuedSegments.isEmpty()) {
            handleNoSpeakableTextOnCurrentPage(currentSpeechGeneration);
            return;
        }
        maybePrefetchNextPageSegments(currentSpeechGeneration);
    }

    /**
     * Retain at most the current bounded window and one lookahead window.
     * Single-page and partitioned TXT playback may refill within the visible
     * source range, but only fully resident continuous text crosses page seams.
     */
    private void maybePrefetchNextPageSegments(int generation) {
        if (!active || paused || tts == null || generation != speechGeneration
                || prefetchedNextPageBoundaryIndex >= 0 || queueTextCursor >= queueTextLimit) return;
        appendSpeechWindow(generation, false);
    }

    private void appendSpeechWindow(int generation, boolean flush) {
        List<TtsSpeechSegment> nextSegments = Collections.emptyList();
        int nextEnd = queueTextCursor;
        while (nextSegments.isEmpty() && nextEnd < queueTextLimit) {
            int nextStart = nextEnd;
            nextEnd = TtsSegmenter.windowEnd(queueText, nextStart, queueTextLimit,
                    PREFETCH_NEXT_PAGE_CHARS);
            nextSegments = TtsSegmenter.segmentPage(queueText.substring(nextStart, nextEnd),
                    nextStart, ttsSegmentChars(), ttsPauseReduction());
            // Whitespace / muted separators still advance the source cursor.
            if (nextSegments.isEmpty()) queueTextCursor = nextEnd;
        }
        if (nextSegments.isEmpty()) return;
        if (nextSegments.size() > MAX_WINDOW_SEGMENTS) {
            // Many one-word sentences must not flood the engine's Binder queue.
            nextSegments = nextSegments.subList(0, MAX_WINDOW_SEGMENTS);
            nextEnd = nextSegments.get(nextSegments.size() - 1).endChar;
        }

        int base = queuedSegments.size();
        queuedSegments.addAll(nextSegments);
        if (!flush) prefetchedNextPageBoundaryIndex = base;
        int successful = 0;
        for (int i = 0; i < nextSegments.size(); i++) {
            String id = utteranceId(generation, queuedSegments.sequenceAt(base + i));
            int mode = flush && i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = tts.speak(nextSegments.get(i).speechText, mode, new Bundle(), id);
            if (result == TextToSpeech.ERROR) {
                while (queuedSegments.size() > base + successful) {
                    queuedSegments.remove(queuedSegments.size() - 1);
                }
                if (successful == 0) prefetchedNextPageBoundaryIndex = -1;
                // Retry only the unaccepted suffix, never replay accepted speech.
                if (successful > 0) queueTextCursor = nextSegments.get(successful - 1).endChar;
                if (flush || queuedSegments.isEmpty()) {
                    stop(false);
                    ShortToast.show(activity, R.string.tts_engine_unavailable);
                }
                return;
            }
            successful++;
            lastQueuedUtteranceId = id;
        }
        queueTextCursor = nextEnd;
    }

    private void handleUtteranceStart(String utteranceId) {
        activity.runOnUiThread(() -> {
            if (host.isTtsHostDestroyed() || !active || paused
                    || !isCurrentGenerationUtterance(utteranceId)
                    || TextUtils.equals(utteranceId, lastStartedUtteranceId)) return;
            int segmentIndex = segmentIndexFromUtteranceId(utteranceId);
            if (segmentIndex < 0 || segmentIndex >= queuedSegments.size()) return;
            // Usually onDone accounts for each utterance. If an engine omits it,
            // use the following onStart as a conservative timing fallback.
            if (!speakingUtteranceId.isEmpty()) {
                sleepTimerAccrueSegment();
                sleepTimerAtSentenceBoundary = TtsSegmenter.endsSentence(queueText,
                        queuedSegments.get(currentSegmentIndex).endChar);
            }
            if (sleepTimerCheckAndMaybeStop()) return;

            boolean crossed = prefetchedNextPageBoundaryIndex >= 0
                    && segmentIndex >= prefetchedNextPageBoundaryIndex;
            queuedSegments.discardBefore(segmentIndex);
            if (prefetchedNextPageBoundaryIndex >= 0) {
                prefetchedNextPageBoundaryIndex = Math.max(0, prefetchedNextPageBoundaryIndex - segmentIndex);
            }
            currentSegmentIndex = pausedSegmentIndex = 0;
            speakingUtteranceId = utteranceId;
            lastStartedUtteranceId = utteranceId;
            sleepTimerOnSegmentStart();
            TtsSpeechSegment segment = queuedSegments.get(0);
            TtsTextSource source = host.ttsTextSource();

            if (crossed) {
                prefetchedNextPageBoundaryIndex = -1;
            }
            if (continuous && host.isTtsTextFullyResident() && source != null
                    && (crossed || segment.startChar >= source.getCharPositionAfterCurrentVisibleContent())) {
                host.ttsJumpToAbsoluteCharPosition(segment.startChar);
            }
            // Also replenish after resume if pause interrupted a deferred refill.
            int generation = currentSpeechGeneration;
            host.ttsHostHandler().post(() -> {
                if (!active || paused || host.isTtsHostDestroyed()
                        || generation != speechGeneration) return;
                maybePrefetchNextPageSegments(generation);
            });

            highlightQueueSegment(source, segment);
            saveQueueCheckpoint(queueAbsolute(segment.startChar));
            updatePlaybackNotification(true);
        });
    }

    private void handleUtteranceDone(String utteranceId) {
        activity.runOnUiThread(() -> {
            if (host.isTtsHostDestroyed() || !active || paused
                    || !isCurrentGenerationUtterance(utteranceId)
                    || !TextUtils.equals(utteranceId, speakingUtteranceId)) return;
            int index = segmentIndexFromUtteranceId(utteranceId);
            if (index < 0 || index >= queuedSegments.size()) return;
            speakingUtteranceId = "";
            sleepTimerAccrueSegment();
            sleepTimerAtSentenceBoundary = TtsSegmenter.endsSentence(queueText, queuedSegments.get(index).endChar);
            if (sleepTimerCheckAndMaybeStop()) return;
            if (!TextUtils.equals(utteranceId, lastQueuedUtteranceId)) return;

            int generation = speechGeneration;
            // Recover a partially accepted lookahead without jumping back to a
            // display-page start. The explicit cursor includes skipped whitespace.
            prefetchedNextPageBoundaryIndex = -1;
            maybePrefetchNextPageSegments(generation);
            if (!active || !TextUtils.equals(utteranceId, lastQueuedUtteranceId)) return;
            if (queueTextCursor < queueTextLimit) {
                stop(false);
                ShortToast.show(activity, R.string.tts_engine_unavailable);
                return;
            }
            if (continuous && !host.isTtsTextFullyResident() && canAdvancePage()) {
                advanceAndSpeakNextPage(generation);
            } else {
                if (host.ttsHostPrefs() != null) host.ttsHostPrefs().clearTtsLastPlaybackState();
                stop(false);
                ShortToast.show(activity, R.string.tts_finished);
            }
        });
    }

    private void handleUtteranceError(String utteranceId) {
        activity.runOnUiThread(() -> {
            android.util.Log.d(TTS_LOG_TAG, "onError: id=" + utteranceId
                    + ", active=" + active + ", gen=" + speechGeneration);
            if (host.isTtsHostDestroyed() || !active || paused
                    || !isCurrentGenerationUtterance(utteranceId)) return;
            int index = segmentIndexFromUtteranceId(utteranceId);
            if (index < 0 || index >= queuedSegments.size()) return;
            stop(false);
            clearTtsHighlight();
            ShortToast.show(activity, R.string.tts_engine_unavailable);
        });
    }


    private void advanceAndSpeakNextPage(int generation) {
        if (generation != speechGeneration) return;
        boolean byCharacter = host.ttsUsesAbsoluteTextContinuation();
        int continuation = queueAbsolute(queueTextLimit);
        int nextGeneration = ++speechGeneration;
        currentSpeechGeneration = nextGeneration;
        queuedSegments.clear();
        queueText = "";
        queueTextCursor = queueTextLimit = 0;
        speakingUtteranceId = lastQueuedUtteranceId = "";
        prefetchedNextPageBoundaryIndex = -1;
        int beforePage = host.ttsDisplayedCurrentPageNumber();
        int beforeChar = host.ttsCurrentCharPosition();
        clearTtsHighlight();
        if (byCharacter) {
            nextSpeechChar = continuation;
            saveQueueCheckpoint(continuation);
            host.ttsJumpToAbsoluteCharPosition(continuation);
        } else {
            host.ttsHostPageBy(+1);
        }
        host.ttsHostHandler().postDelayed(() -> {
            if (!active || paused || nextGeneration != speechGeneration || host.isTtsHostDestroyed()) return;
            int afterPage = host.ttsDisplayedCurrentPageNumber();
            int afterChar = host.ttsCurrentCharPosition();
            if (afterPage == beforePage && afterChar == beforeChar && !canAdvancePage()) {
                stop(false);
                clearTtsHighlight();
                ShortToast.show(activity, R.string.tts_finished);
                return;
            }
            speakCurrentPage(nextGeneration, 0);
        }, NEXT_PAGE_DELAY_MS);
    }

    private void movePageFromRemote(int direction) {
        boolean wasContinuous = continuous || (host.ttsHostPrefs() != null && host.ttsHostPrefs().getTtsLastContinuous());
        stopInternal(false, false);
        int restartGeneration = speechGeneration;
        host.ttsHostPageBy(direction);
        pendingResume = true;
        host.ttsHostHandler().postDelayed(() -> {
            if (!host.isTtsHostDestroyed()
                    && speechGeneration == restartGeneration
                    && pendingResume && !active && !paused) {
                pendingResume = false;
                start(wasContinuous);
            }
        }, NEXT_PAGE_DELAY_MS);
    }

    private boolean canAdvancePage() {
        if (host.ttsUsesAbsoluteTextContinuation()) {
            return host.ttsHasTextAfter(nextSpeechChar >= 0 ? nextSpeechChar : queueAbsolute(queueTextLimit));
        }
        int total = Math.max(1, host.ttsDisplayedTotalPageCount());
        int current = Math.max(1, Math.min(total, host.ttsDisplayedCurrentPageNumber()));
        return current < total;
    }

    private int queueAbsolute(int local) {
        return (int) Math.min(Integer.MAX_VALUE, (long) queueBaseChar + Math.max(0, local));
    }

    private boolean ownsQueueDocument() {
        return TextUtils.equals(queuePath, host.ttsHostFilePath())
                && TextUtils.equals(queueSignature, host.ttsCheckpointSignature());
    }

    private void saveQueueCheckpoint(int absoluteChar) {
        if (host.ttsHostPrefs() == null || queuePath == null || !ownsQueueDocument()) return;
        host.ttsHostPrefs().setTtsLastPlaybackState(queuePath, absoluteChar,
                host.ttsDisplayedCurrentPageNumber(), continuous,
                host.ttsHostPrefs().getTtsSleepTimerMinutes(), host.ttsTextFormatVersion(), queueSignature);
    }

    private void highlightQueueSegment(TtsTextSource source, TtsSpeechSegment segment) {
        if (source == null) return;
        long start = (long) queueBaseChar + segment.startChar - host.ttsTextBaseCharPosition();
        long end = (long) queueBaseChar + segment.endChar - host.ttsTextBaseCharPosition();
        String current = source.getTextContent();
        if (ownsQueueDocument() && current != null && start >= 0 && end > start && end <= current.length()) {
            source.setTtsHighlightRange((int) start, (int) end);
        } else {
            source.clearTtsHighlight();
        }
    }

    @NonNull
    private VisiblePage currentVisiblePage() {
        TtsTextSource source = host.ttsTextSource();
        if (source == null) return VisiblePage.EMPTY;
        String content = source.getTextContent();
        if (content == null || content.isEmpty()) return VisiblePage.EMPTY;

        int start = Math.max(0, Math.min(content.length(), source.getCurrentCharPosition()));
        int end = Math.max(start, Math.min(content.length(),
                source.getCharPositionAfterCurrentVisibleContent()));
        if (end <= start && start < content.length()) {
            end = Math.min(content.length(), start + MAX_TTS_SEGMENT_CHARS);
        }

        return new VisiblePage(start, end);
    }

    private int segmentIndexFromUtteranceId(String utteranceId) {
        if (utteranceId == null) return -1;
        int last = utteranceId.lastIndexOf('_');
        if (last < 0 || last >= utteranceId.length() - 1) return -1;
        try {
            return queuedSegments.indexOfSequence(Integer.parseInt(utteranceId.substring(last + 1)));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean isCurrentGenerationUtterance(String utteranceId) {
        boolean current = utteranceId != null
                && utteranceId.startsWith("reader_tts_" + speechGeneration + "_");
        if (!current) {
            // A callback for a superseded playback (page turned, settings changed,
            // restarted). Dropping it is correct; logging it makes "audio stopped
            // and nothing happened" reports diagnosable from logcat.
            android.util.Log.d(TTS_LOG_TAG, "callback dropped (stale generation): id="
                    + utteranceId + ", current gen=" + speechGeneration);
        }
        return current;
    }

    private String utteranceId(int generation, int chunkIndex) {
        return "reader_tts_" + generation + "_" + chunkIndex;
    }

    private void clearTtsHighlight() {
        TtsTextSource source = host.ttsTextSource();
        if (source != null) {
            source.clearTtsHighlight();
        }
    }

    private void updatePlaybackNotification(boolean isPlaying) {
        String path = host.ttsHostFilePath() != null ? host.ttsHostFilePath() : "";
        String title = path.isEmpty() ? activity.getString(R.string.tts_title) : new File(path).getName();
        int page = Math.max(1, host.ttsDisplayedCurrentPageNumber());
        int total = Math.max(1, host.ttsDisplayedTotalPageCount());
        String subtitle = activity.getString(R.string.tts_notification_page, page, total);
        TtsPlaybackService.startOrUpdate(
                activity.getApplicationContext(),
                path,
                title,
                subtitle,
                isPlaying,
                continuous,
                host.ttsHostKind());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (notificationPermissionRequested) return;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionRequested = true;
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_TTS_NOTIFICATION_PERMISSION);
    }

    private static final class VisiblePage {
        static final VisiblePage EMPTY = new VisiblePage(0, 0);
        final int startChar;
        final int endChar;

        VisiblePage(int startChar, int endChar) {
            this.startChar = Math.max(0, startChar);
            this.endChar = Math.max(this.startChar, endChar);
        }

        boolean isEmpty() {
            return endChar <= startChar;
        }
    }

    private void ensureEngine() {
        if (tts == null && !initializing) {
            initializing = true;
            tts = new TextToSpeech(activity.getApplicationContext(), this);
        }
    }

    private void openAndroidTtsSettings() {
        try {
            Intent intent = new Intent(ACTION_ANDROID_TTS_SETTINGS);
            activity.startActivity(intent);
        } catch (Exception ignored) {
            try {
                activity.startActivity(new Intent("android.settings.SETTINGS"));
            } catch (Exception ignoredAgain) {
                ShortToast.show(activity, R.string.tts_settings_unavailable);
            }
        }
    }

    private void openAndroidTtsVoiceInstall() {
        try {
            Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            activity.startActivity(intent);
        } catch (Exception ignored) {
            openAndroidTtsSettings();
        }
    }

    private void showLanguageDialog(@NonNull Runnable afterSelect) {
        host.ttsHostDialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = host.ttsHostDialogStyler().readerDialogBgColor();
        final int fg = host.ttsHostDialogStyler().readerDialogTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = host.ttsHostDialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_language), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = host.ttsHostDpToPx(14);
        list.setPadding(pad, host.ttsHostDpToPx(4), pad, host.ttsHostDpToPx(8));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        addLanguageRow(list, "system", activity.getString(R.string.tts_language_system), fg, ref, afterSelect);
        addLanguageRow(list, "en", activity.getString(R.string.language_english), fg, ref, afterSelect);
        addLanguageRow(list, "ko", activity.getString(R.string.language_korean), fg, ref, afterSelect);
        addLanguageRow(list, "ja", activity.getString(R.string.language_japanese), fg, ref, afterSelect);
        addLanguageRow(list, "zh-CN", activity.getString(R.string.language_chinese_simplified), fg, ref, afterSelect);
        addLanguageRow(list, "zh-TW", activity.getString(R.string.language_chinese_traditional), fg, ref, afterSelect);
        addLanguageRow(list, "es", activity.getString(R.string.language_spanish), fg, ref, afterSelect);
        addLanguageRow(list, "fr", activity.getString(R.string.language_french), fg, ref, afterSelect);
        addLanguageRow(list, "de", activity.getString(R.string.language_german), fg, ref, afterSelect);
        addLanguageRow(list, "it", activity.getString(R.string.language_italian), fg, ref, afterSelect);
        addLanguageRow(list, "pt", activity.getString(R.string.language_portuguese), fg, ref, afterSelect);
        addLanguageRow(list, "ru", activity.getString(R.string.language_russian), fg, ref, afterSelect);
        addLanguageRow(list, "ar", activity.getString(R.string.language_arabic), fg, ref, afterSelect);
        addLanguageRow(list, "hi", activity.getString(R.string.language_hindi), fg, ref, afterSelect);
        addLanguageRow(list, "id", activity.getString(R.string.language_indonesian), fg, ref, afterSelect);
        addLanguageRow(list, "vi", activity.getString(R.string.language_vietnamese), fg, ref, afterSelect);
        addLanguageRow(list, "th", activity.getString(R.string.language_thai), fg, ref, afterSelect);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        host.ttsHostDialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ttsLanguageListHeightPx()));

        android.app.Dialog dialog = host.ttsHostDialogStyler().createNarrowPositionedReaderDialog(
                outer,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                74,
                0.78f,
                420,
                true);
        ref[0] = dialog;
        dialog.show();
    }

    private int ttsLanguageListHeightPx() {
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int compactCap = host.ttsHostDpToPx(330);
        int screenCap = Math.round(screenHeight * 0.42f);
        return Math.max(host.ttsHostDpToPx(220), Math.min(compactCap, screenCap));
    }

    private void addLanguageRow(LinearLayout list,
                                String tag,
                                String label,
                                int fg,
                                android.app.Dialog[] dialogRef,
                                @NonNull Runnable afterSelect) {
        String current = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsLanguageTag() : "system";
        String rowText = tag.equals(current) ? activity.getString(R.string.tts_language_selected, label) : label;
        TextView row = host.ttsHostDialogStyler().makeReaderActionRow(rowText, fg);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        row.setOnClickListener(v -> {
            if (host.ttsHostPrefs() != null) host.ttsHostPrefs().setTtsLanguageTag(tag);
            if (host.ttsHostPrefs() != null) host.ttsHostPrefs().setTtsVoiceName("");
            if (tts != null && initialized) applySelectedLanguage(false);
            afterSelect.run();
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        list.addView(row);
    }

    @NonNull
    private String currentLanguageRowLabel() {
        return activity.getString(R.string.tts_language_current, selectedLanguageLabel());
    }

    @NonNull
    private String currentVoiceRowLabel() {
        return activity.getString(R.string.tts_voice_current, selectedVoiceLabel());
    }

    @NonNull
    private String selectedVoiceLabel() {
        String voiceName = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsVoiceName() : "";
        if (voiceName == null || voiceName.trim().isEmpty()) {
            return activity.getString(R.string.tts_voice_auto);
        }
        return voiceName;
    }

    @NonNull
    private String selectedLanguageLabel() {
        String tag = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsLanguageTag() : "system";
        switch (tag) {
            case "ko":
                return activity.getString(R.string.language_korean);
            case "en":
                return activity.getString(R.string.language_english);
            case "ja":
                return activity.getString(R.string.language_japanese);
            case "zh-CN":
                return activity.getString(R.string.language_chinese_simplified);
            case "zh-TW":
                return activity.getString(R.string.language_chinese_traditional);
            case "es":
                return activity.getString(R.string.language_spanish);
            case "fr":
                return activity.getString(R.string.language_french);
            case "de":
                return activity.getString(R.string.language_german);
            case "it":
                return activity.getString(R.string.language_italian);
            case "pt":
                return activity.getString(R.string.language_portuguese);
            case "ru":
                return activity.getString(R.string.language_russian);
            case "ar":
                return activity.getString(R.string.language_arabic);
            case "hi":
                return activity.getString(R.string.language_hindi);
            case "id":
                return activity.getString(R.string.language_indonesian);
            case "vi":
                return activity.getString(R.string.language_vietnamese);
            case "th":
                return activity.getString(R.string.language_thai);
            default:
                return activity.getString(R.string.tts_language_system);
        }
    }

    private boolean applySelectedLanguage(boolean showUnsupportedToast) {
        if (tts == null) return false;
        Voice selectedVoice = findSelectedVoice();
        if (selectedVoice != null) {
            int result = tts.setVoice(selectedVoice);
            android.util.Log.d(TTS_LOG_TAG, "setVoice(" + selectedVoice.getName() + ") -> "
                    + (result != TextToSpeech.ERROR ? "OK" : "ERROR"));
            if (result != TextToSpeech.ERROR) return true;
            if (showUnsupportedToast) {
                ShortToast.show(activity, R.string.tts_voice_unavailable);
            }
        }

        Locale locale = selectedTtsLocale();
        int result = tts.setLanguage(locale);
        boolean supported = result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED;
        android.util.Log.d(TTS_LOG_TAG, "setLanguage(" + locale + ") -> " + result
                + (supported ? " (supported)" : " (unsupported, trying fallbacks)"));
        if (supported) return true;

        // The chosen locale isn't available on this engine. Rather than refusing
        // to speak (which reads to the user as broken TTS producing no audio),
        // fall back to whatever language the engine already has loaded, then to
        // the default locale. Neural engines such as sherpa-onnx/VoxSherpa often
        // report a specific locale as NOT_SUPPORTED while still speaking fine
        // with their own default voice, so this recovers real audio in the
        // common case instead of going silent.
        Locale engineDefault = engineDefaultLanguage();
        if (engineDefault != null) {
            int fallback = tts.setLanguage(engineDefault);
            android.util.Log.d(TTS_LOG_TAG, "fallback setLanguage(engine default "
                    + engineDefault + ") -> " + fallback);
            if (fallback != TextToSpeech.LANG_MISSING_DATA
                    && fallback != TextToSpeech.LANG_NOT_SUPPORTED) {
                if (showUnsupportedToast) {
                    ShortToast.show(activity,
                            activity.getString(R.string.tts_language_unavailable, selectedLanguageLabel()));
                }
                return true;
            }
        }
        int deviceDefault = tts.setLanguage(Locale.getDefault());
        boolean deviceOk = deviceDefault != TextToSpeech.LANG_MISSING_DATA
                && deviceDefault != TextToSpeech.LANG_NOT_SUPPORTED;
        android.util.Log.d(TTS_LOG_TAG, "fallback setLanguage(device default "
                + Locale.getDefault() + ") -> " + deviceDefault + (deviceOk ? " (using it)" : " (giving up)"));
        if (!deviceOk && showUnsupportedToast) {
            ShortToast.show(activity,
                    activity.getString(R.string.tts_language_unavailable, selectedLanguageLabel()));
        }
        return deviceOk;
    }

    @androidx.annotation.Nullable
    private Locale engineDefaultLanguage() {
        if (tts == null) return null;
        try {
            Voice defaultVoice = tts.getDefaultVoice();
            if (defaultVoice != null && defaultVoice.getLocale() != null) {
                return defaultVoice.getLocale();
            }
        } catch (Exception ignored) {
            // Some engines throw from getDefaultVoice(); ignore and fall through.
        }
        return null;
    }

    private void applySpeechParameters() {
        if (tts == null) return;
        int rate = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsSpeechRatePercent() : 100;
        int pitch = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsPitchPercent() : 100;
        tts.setSpeechRate(Math.max(0.5f, Math.min(2.0f, rate / 100f)));
        tts.setPitch(Math.max(0.5f, Math.min(2.0f, pitch / 100f)));
    }

    /** Target chunk size in chars for the segmenter, from the phrase-length pref. */
    private int ttsSegmentChars() {
        if (host.ttsHostPrefs() == null) return MAX_TTS_SEGMENT_CHARS;
        return TtsSegmenter.phraseLengthToChars(host.ttsHostPrefs().getTtsPhraseLengthLevel());
    }

    /** Pause-reduction level (0/1/2) from prefs, for the segmenter's text transform. */
    private int ttsPauseReduction() {
        return host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsPauseReduction() : 0;
    }

    private String phraseLengthRowLabel() {
        int level = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsPhraseLengthLevel() : 2;
        switch (level) {
            case 0: return activity.getString(R.string.tts_phrase_length_short);
            case 1: return activity.getString(R.string.tts_phrase_length_medium);
            default: return activity.getString(R.string.tts_phrase_length_long);
        }
    }

    private String pauseReductionRowLabel() {
        int level = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsPauseReduction() : 0;
        switch (level) {
            case 1: return activity.getString(R.string.tts_pause_reduction_medium);
            case 2: return activity.getString(R.string.tts_pause_reduction_aggressive);
            default: return activity.getString(R.string.tts_pause_reduction_off);
        }
    }

    @NonNull
    private Locale selectedTtsLocale() {
        String tag = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsLanguageTag() : "system";
        switch (tag) {
            case "ko":
                return Locale.KOREAN;
            case "en":
                return Locale.ENGLISH;
            case "ja":
                return Locale.JAPANESE;
            case "zh-CN":
                return Locale.SIMPLIFIED_CHINESE;
            case "zh-TW":
                return Locale.TRADITIONAL_CHINESE;
            case "es":
            case "fr":
            case "de":
            case "it":
            case "pt":
            case "ru":
            case "ar":
            case "hi":
            case "id":
            case "vi":
            case "th":
                return Locale.forLanguageTag(tag);
            default:
                return Locale.getDefault();
        }
    }

    private void showVoiceDialog(Runnable afterSelect) {
        if (tts == null || !initialized) {
            pendingVoiceDialog = true;
            ensureEngine();
            ShortToast.show(activity, R.string.tts_initializing);
            return;
        }

        host.ttsHostDialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = host.ttsHostDialogStyler().readerDialogBgColor();
        final int fg = host.ttsHostDialogStyler().readerDialogTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = host.ttsHostDialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_voice), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = host.ttsHostDpToPx(14);
        list.setPadding(pad, host.ttsHostDpToPx(4), pad, host.ttsHostDpToPx(8));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        addVoiceRow(list, "", activity.getString(R.string.tts_voice_auto), fg, ref, afterSelect);

        TextView addVoice = host.ttsHostDialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_add_voice_model), fg);
        addVoice.setGravity(Gravity.CENTER);
        addVoice.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        addVoice.setOnClickListener(v -> openAndroidTtsVoiceInstall());
        list.addView(addVoice);

        List<Voice> voices = matchingVoices();
        if (voices.isEmpty()) {
            TextView empty = host.ttsHostDialogStyler().makeReaderActionRow(
                    activity.getString(R.string.tts_no_matching_voices), fg);
            empty.setGravity(Gravity.CENTER);
            empty.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
            list.addView(empty);
        } else {
            int limit = Math.min(80, voices.size());
            for (int i = 0; i < limit; i++) {
                Voice voice = voices.get(i);
                addVoiceRow(list, voice.getName(), voiceDisplayLabel(voice), fg, ref, afterSelect);
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        host.ttsHostDialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                host.ttsHostDpToPx(330)));

        android.app.Dialog dialog = host.ttsHostDialogStyler().createNarrowPositionedReaderDialog(
                outer,
                bg,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                74,
                0.88f,
                520,
                true);
        ref[0] = dialog;
        dialog.show();
    }

    private void addVoiceRow(LinearLayout list,
                             String voiceName,
                             String label,
                             int fg,
                             android.app.Dialog[] dialogRef,
                             Runnable afterSelect) {
        String current = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsVoiceName() : "";
        boolean selected = TextUtils.equals(current, voiceName);
        TextView row = host.ttsHostDialogStyler().makeReaderActionRow(
                selected ? activity.getString(R.string.tts_language_selected, label) : label, fg);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        row.setSingleLine(false);
        row.setMaxLines(2);
        row.setOnClickListener(v -> {
            if (host.ttsHostPrefs() != null) host.ttsHostPrefs().setTtsVoiceName(voiceName);
            applySelectedLanguage(false);
            if (afterSelect != null) afterSelect.run();
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        list.addView(row);
    }

    @NonNull
    private List<Voice> matchingVoices() {
        if (tts == null || tts.getVoices() == null) return Collections.emptyList();
        Set<Voice> voiceSet = tts.getVoices();
        Locale target = selectedTtsLocale();
        String targetLanguage = target != null ? target.getLanguage() : "";
        ArrayList<Voice> result = new ArrayList<>();
        for (Voice voice : voiceSet) {
            if (voice == null || voice.getLocale() == null) continue;
            String voiceLanguage = voice.getLocale().getLanguage();
            if (targetLanguage.isEmpty() || targetLanguage.equalsIgnoreCase(voiceLanguage)) {
                result.add(voice);
            }
        }
        if (result.isEmpty()) {
            for (Voice voice : voiceSet) {
                if (voice != null) result.add(voice);
            }
        }
        result.sort((a, b) -> voiceDisplayLabel(a).compareToIgnoreCase(voiceDisplayLabel(b)));
        return result;
    }

    private Voice findSelectedVoice() {
        String selected = host.ttsHostPrefs() != null ? host.ttsHostPrefs().getTtsVoiceName() : "";
        if (selected == null || selected.trim().isEmpty() || tts == null || tts.getVoices() == null) {
            return null;
        }
        for (Voice voice : tts.getVoices()) {
            if (voice != null && selected.equals(voice.getName())) return voice;
        }
        return null;
    }

    @NonNull
    private String voiceDisplayLabel(@NonNull Voice voice) {
        Locale locale = voice.getLocale();
        String language = locale != null ? locale.toLanguageTag() : "";
        String network = voice.isNetworkConnectionRequired()
                ? activity.getString(R.string.tts_voice_network)
                : activity.getString(R.string.tts_voice_offline);
        return voice.getName() + (language.isEmpty() ? "" : " / " + language) + " / " + network;
    }

}
