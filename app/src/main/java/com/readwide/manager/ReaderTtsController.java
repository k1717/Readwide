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

    private final ReaderActivity activity;

    private TextToSpeech tts;
    private boolean initialized = false;
    private boolean initializing = false;
    private boolean pendingStart = false;
    private boolean pendingContinuous = false;
    private boolean pendingVoiceDialog = false;
    private boolean active = false;
    private boolean continuous = false;
    private boolean notificationPermissionRequested = false;
    private int speechGeneration = 0;
    private String lastQueuedUtteranceId = "";
    private final ArrayList<TtsSpeechSegment> queuedSegments = new ArrayList<>();

    // Real pause/resume: keep active, remember where we stopped, continue from there.
    private boolean paused = false;
    private boolean pausedForFocusLoss = false;     // paused due to transient focus loss (auto-resume on regain)
    private TtsSleepTimerDialog sleepTimerDialog;

    private TtsSleepTimerDialog sleepTimerDialog() {
        if (sleepTimerDialog == null) {
            sleepTimerDialog = new TtsSleepTimerDialog(activity, this);
        }
        return sleepTimerDialog;
    }
    private int currentSegmentIndex = 0;            // segment currently being spoken
    private int pausedSegmentIndex = 0;             // segment to resume from
    private int currentSpeechGeneration = 0;        // generation of the queued page

    // Sleep timer (counts playback time only; paused time does not accrue).
    private long sleepTimerTargetMs = 0L;       // 0 = timer off
    private long sleepTimerAccruedMs = 0L;      // accumulated speaking time
    private long sleepTimerSegmentStartMs = 0L; // start time of the current utterance (0 = not speaking)
    private boolean sleepTimerFinishSentence = true;

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

    ReaderTtsController(@NonNull ReaderActivity activity) {
        this.activity = activity;
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
        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);
        final int sub = activity.dialogStyler().readerDialogSubTextColor(bg);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_title), bg, fg);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(activity);
        desc.setText(R.string.tts_description);
        desc.setTextColor(sub);
        desc.setTextSize(13f);
        desc.setLineSpacing(0, 1.08f);
        desc.setPadding(activity.dpToPx(18), activity.dpToPx(4),
                activity.dpToPx(18), activity.dpToPx(14));
        panel.addView(desc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        LinearLayout languageBox = TtsDialogViews.makeOptionBox(activity);
        final TextView[] voiceButtonRef = new TextView[1];
        TextView languageButton = activity.dialogStyler().makeReaderActionRow(
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

        TextView voiceButton = activity.dialogStyler().makeReaderActionRow(
                currentVoiceRowLabel(), fg);
        voiceButtonRef[0] = voiceButton;
        voiceButton.setGravity(Gravity.CENTER);
        voiceButton.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        voiceButton.setOnClickListener(v -> showVoiceDialog(() ->
                voiceButton.setText(currentVoiceRowLabel())));
        languageBox.addView(voiceButton);

        if (hasResumeStateForCurrentFile()) {
            TextView resumeButton = activity.dialogStyler().makeReaderActionRow(
                    resumeRowLabel(), fg);
            resumeButton.setGravity(Gravity.CENTER);
            resumeButton.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
            resumeButton.setOnClickListener(v -> {
                resumeFromSavedState();
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
            languageBox.addView(resumeButton);
        }

        TtsDialogViews.addPercentSlider(activity, languageBox,
                activity.getString(R.string.tts_speed),
                activity.prefs != null ? activity.prefs.getTtsSpeechRatePercent() : 100,
                value -> {
                    if (activity.prefs != null) activity.prefs.setTtsSpeechRatePercent(value);
                    applySpeechParameters();
                },
                fg,
                sub);
        TtsDialogViews.addPercentSlider(activity, languageBox,
                activity.getString(R.string.tts_pitch),
                activity.prefs != null ? activity.prefs.getTtsPitchPercent() : 100,
                value -> {
                    if (activity.prefs != null) activity.prefs.setTtsPitchPercent(value);
                    applySpeechParameters();
                },
                fg,
                sub);

        TextView sleepTimerButton = activity.dialogStyler().makeReaderActionRow(
                sleepTimerDialog().rowLabel(), fg);
        sleepTimerButton.setGravity(Gravity.CENTER);
        sleepTimerButton.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        sleepTimerButton.setOnClickListener(v -> sleepTimerDialog().show(() ->
                sleepTimerButton.setText(sleepTimerDialog().rowLabel())));
        languageBox.addView(sleepTimerButton);

        TextView systemSettings = activity.dialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_android_settings), fg);
        systemSettings.setGravity(Gravity.CENTER);
        systemSettings.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        systemSettings.setOnClickListener(v -> openAndroidTtsSettings());
        languageBox.addView(systemSettings);

        TextView addVoice = activity.dialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_add_voice_model), fg);
        addVoice.setGravity(Gravity.CENTER);
        addVoice.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        addVoice.setOnClickListener(v -> openAndroidTtsVoiceInstall());
        languageBox.addView(addVoice);

        panel.addView(languageBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams optionLp = (LinearLayout.LayoutParams) languageBox.getLayoutParams();
        optionLp.setMargins(activity.dpToPx(18), 0, activity.dpToPx(18), activity.dpToPx(10));
        languageBox.setLayoutParams(optionLp);

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setBackground(activity.dialogStyler().positionedActionPanelBackground(
                activity.dialogStyler().dialogActionPanelFillColor(bg),
                activity.dialogStyler().dialogActionPanelLineColor(bg)));
        actionRow.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), 0);

        TextView stopButton = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.tts_stop), sub, Gravity.CENTER);
        TextView pageButton = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.tts_read_page), fg, Gravity.CENTER);
        TextView continuousButton = activity.dialogStyler().makeReaderDialogActionText(
                activity.getString(R.string.tts_read_continuous), fg, Gravity.CENTER);

        // Pause/Resume is only meaningful while playback is active.
        TextView pauseButton = null;
        if (active) {
            pauseButton = activity.dialogStyler().makeReaderDialogActionText(
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
            actionButton.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), 0);
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
                activity.dpToPx(54)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
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
        if (activity.readerView == null || TextUtils.isEmpty(activity.readerView.getTextContent())) {
            ShortToast.show(activity, R.string.tts_no_text);
            return;
        }

        activity.stopAutoPageTurn(false);
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
        active = false;
        paused = false;
        pausedForFocusLoss = false;
        speechGeneration++;
        lastQueuedUtteranceId = "";
        queuedSegments.clear();
        clearTtsHighlight();
        activity.updateTtsFloatingCard();
        unregisterNoisyReceiver();
        abandonAudioFocus();
        sleepTimerSegmentStartMs = 0L;
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
        return active || pendingStart;
    }

    /** True pause: stop speaking but keep state/focus/timer so we can resume in place. */
    void pausePlayback() {
        if (!active || paused) return;
        pausedForFocusLoss = false;
        sleepTimerAccrueSegment(); // stop accruing playback time while paused
        pausedSegmentIndex = currentSegmentIndex;
        paused = true;
        if (tts != null) tts.stop(); // flushes the queue; segments are remembered
        activity.updateTtsFloatingCard();
        // Persist the paused position so it survives leaving the app.
        if (activity.prefs != null && activity.filePath != null
                && pausedSegmentIndex >= 0 && pausedSegmentIndex < queuedSegments.size()) {
            TtsSpeechSegment seg = queuedSegments.get(pausedSegmentIndex);
            activity.prefs.setTtsLastPlaybackState(
                    activity.filePath,
                    seg.startChar,
                    activity.getDisplayedCurrentPageNumber(),
                    continuous);
        }
        updatePlaybackNotification(false);
    }

    /** Resume from the segment we paused on, without reloading the page or jumping. */
    void resumePlayback() {
        if (!active || !paused) return;
        paused = false;
        if (tts == null || queuedSegments.isEmpty()) {
            // Fall back to a fresh start if we lost the queue somehow.
            start(continuous);
            return;
        }
        if (!applySelectedLanguage(false)) {
            stop(false);
            return;
        }
        applySpeechParameters();
        int from = Math.max(0, Math.min(pausedSegmentIndex, queuedSegments.size() - 1));
        speakQueuedFrom(from);
        updatePlaybackNotification(true);
        activity.updateTtsFloatingCard();
    }

    /** Re-enqueue the already-segmented current page starting at the given index. */
    private void speakQueuedFrom(int fromIndex) {
        if (tts == null || queuedSegments.isEmpty()) return;
        int generation = currentSpeechGeneration;
        lastQueuedUtteranceId = utteranceId(generation, queuedSegments.size() - 1);
        for (int i = fromIndex; i < queuedSegments.size(); i++) {
            Bundle params = new Bundle();
            String utteranceId = utteranceId(generation, i);
            int queueMode = (i == fromIndex) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = tts.speak(queuedSegments.get(i).speechText, queueMode, params, utteranceId);
            if (result == TextToSpeech.ERROR) {
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
        } else if (TtsPlaybackService.ACTION_PLAY_PAUSE.equals(action)) {
            if (active && !paused) {
                pausePlayback();
            } else if (active && paused) {
                resumePlayback();
            } else if (pendingStart) {
                stopInternal(false, false);
            } else if (hasResumeStateForCurrentFile()) {
                resumeFromSavedState();
            } else {
                start(continuous || (activity.prefs != null && activity.prefs.getTtsLastContinuous()));
            }
        } else if (TtsPlaybackService.ACTION_NEXT.equals(action)) {
            movePageFromRemote(+1);
        } else if (TtsPlaybackService.ACTION_PREVIOUS.equals(action)) {
            movePageFromRemote(-1);
        }
    }

    private boolean hasResumeStateForCurrentFile() {
        if (activity.prefs == null || activity.filePath == null) return false;
        return TextUtils.equals(activity.filePath, activity.prefs.getTtsLastFilePath())
                && activity.prefs.getTtsLastCharPosition() > 0;
    }

    private String resumeRowLabel() {
        int page = activity.prefs != null ? activity.prefs.getTtsLastPageNumber() : 1;
        return activity.getString(R.string.tts_resume_from_page, Math.max(1, page));
    }

    private void resumeFromSavedState() {
        if (!hasResumeStateForCurrentFile()) {
            start(activity.prefs != null && activity.prefs.getTtsLastContinuous());
            return;
        }
        int charPosition = activity.prefs.getTtsLastCharPosition();
        boolean resumeContinuous = activity.prefs.getTtsLastContinuous();
        activity.jumpToAbsoluteCharPosition(charPosition,
                activity.prefs.getTtsLastPageNumber(),
                activity.getDisplayedTotalPageCount());
        activity.handler.postDelayed(() -> {
            if (!activity.activityDestroyed) start(resumeContinuous);
        }, 420L);
    }

    @Override
    public void onInit(int status) {
        activity.runOnUiThread(() -> {
            if (activity.activityDestroyed) {
                initializing = false;
                pendingStart = false;
                pendingVoiceDialog = false;
                return;
            }
            initializing = false;
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
        if (activity.prefs != null) {
            int minutes = activity.prefs.getTtsSleepTimerMinutes();
            sleepTimerTargetMs = minutes > 0 ? minutes * 60_000L : 0L;
            sleepTimerFinishSentence = activity.prefs.getTtsSleepTimerFinishSentence();
        } else {
            sleepTimerTargetMs = 0L;
            sleepTimerFinishSentence = true;
        }
        sleepTimerAccruedMs = 0L;
        sleepTimerSegmentStartMs = 0L;
    }

    /** Re-read timer settings mid-playback (e.g. user changed the timer while playing). */
    void refreshSleepTimerFromPrefs() {
        if (!active || activity.prefs == null) return;
        int minutes = activity.prefs.getTtsSleepTimerMinutes();
        sleepTimerTargetMs = minutes > 0 ? minutes * 60_000L : 0L;
        sleepTimerFinishSentence = activity.prefs.getTtsSleepTimerFinishSentence();
    }

    /** Mark the start of an utterance for playback-time accrual. */
    private void sleepTimerOnSegmentStart() {
        sleepTimerSegmentStartMs = android.os.SystemClock.elapsedRealtime();
    }

    /** Accrue the time spent speaking the segment that just ended/was interrupted. */
    private void sleepTimerAccrueSegment() {
        if (sleepTimerSegmentStartMs > 0L) {
            long now = android.os.SystemClock.elapsedRealtime();
            long delta = now - sleepTimerSegmentStartMs;
            if (delta > 0) sleepTimerAccruedMs += delta;
            sleepTimerSegmentStartMs = 0L;
        }
    }

    /** @return true if the timer has elapsed and playback was stopped. */
    private boolean sleepTimerCheckAndMaybeStop() {
        if (sleepTimerTargetMs <= 0L) return false;
        if (sleepTimerAccruedMs >= sleepTimerTargetMs) {
            // Reached at a sentence boundary, so finish-sentence is honored here.
            stop(false);
            clearTtsHighlight();
            TtsPlaybackService.stop(activity.getApplicationContext());
            ShortToast.show(activity, R.string.tts_sleep_timer_finished);
            return true;
        }
        return false;
    }

    private void startNow(boolean continuousMode) {
        if (tts == null || !initialized) return;
        if (!applySelectedLanguage(true)) {
            active = false;
            pendingStart = false;
            return;
        }
        applySpeechParameters();
        active = true;
        paused = false;
        continuous = continuousMode;
        if (!requestAudioFocus()) {
            // Another app holds exclusive audio focus; don't start over it.
            active = false;
            ShortToast.show(activity, R.string.tts_stopped);
            return;
        }
        registerNoisyReceiver();
        initSleepTimer();
        int generation = ++speechGeneration;
        updatePlaybackNotification(true);
        ShortToast.show(activity, continuousMode ? R.string.tts_continuous_started : R.string.tts_started);
        activity.updateTtsFloatingCard();
        speakCurrentPage(generation, 0);
    }

    private void speakCurrentPage(int generation, int partitionRetryCount) {
        if (!active || generation != speechGeneration || activity.activityDestroyed) return;

        if (activity.largeTextEstimateActive
                && activity.largeTextPartitionSwitchState.isInProgress()) {
            if (partitionRetryCount == 0) {
                ShortToast.show(activity, R.string.tts_waiting_for_page);
            }
            if (partitionRetryCount < MAX_PARTITION_RETRIES) {
                activity.handler.postDelayed(
                        () -> speakCurrentPage(generation, partitionRetryCount + 1),
                        PARTITION_RETRY_DELAY_MS);
            } else {
                stop(false);
            }
            return;
        }

        VisiblePage page = currentVisiblePage();
        if (page.isEmpty()) {
            if (continuous && canAdvancePage()) {
                advanceAndSpeakNextPage(generation);
            } else {
                stop(false);
                ShortToast.show(activity, R.string.tts_no_text);
            }
            return;
        }

        queueSpeechSegments(page, generation);
    }

    private void queueSpeechSegments(@NonNull VisiblePage page, int generation) {
        if (tts == null) return;

        List<TtsSpeechSegment> segments = TtsSegmenter.segmentPage(
                page.text,
                page.startChar,
                MAX_TTS_SEGMENT_CHARS);
        if (segments.isEmpty()) {
            stop(false);
            return;
        }

        queuedSegments.clear();
        queuedSegments.addAll(segments);
        currentSpeechGeneration = generation;
        currentSegmentIndex = 0;
        lastQueuedUtteranceId = utteranceId(generation, segments.size() - 1);
        for (int i = 0; i < segments.size(); i++) {
            Bundle params = new Bundle();
            String utteranceId = utteranceId(generation, i);
            int queueMode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = tts.speak(segments.get(i).speechText, queueMode, params, utteranceId);
            if (result == TextToSpeech.ERROR) {
                stop(false);
                ShortToast.show(activity, R.string.tts_engine_unavailable);
                return;
            }
        }
    }

    private void handleUtteranceStart(String utteranceId) {
        activity.runOnUiThread(() -> {
            if (activity.activityDestroyed || !active || utteranceId == null) return;
            // Sleep timer: account for the segment that just finished, then stop at
            // this sentence boundary if the playback-time target has been reached.
            sleepTimerAccrueSegment();
            if (sleepTimerCheckAndMaybeStop()) return;
            sleepTimerOnSegmentStart();
            int segmentIndex = segmentIndexFromUtteranceId(utteranceId);
            if (segmentIndex < 0 || segmentIndex >= queuedSegments.size()) return;
            currentSegmentIndex = segmentIndex;
            TtsSpeechSegment segment = queuedSegments.get(segmentIndex);
            if (activity.readerView != null) {
                activity.readerView.setTtsHighlightRange(segment.startChar, segment.endChar);
            }
            if (activity.prefs != null && activity.filePath != null) {
                activity.prefs.setTtsLastPlaybackState(
                        activity.filePath,
                        segment.startChar,
                        activity.getDisplayedCurrentPageNumber(),
                        continuous);
            }
            updatePlaybackNotification(true);
        });
    }

    private void handleUtteranceDone(String utteranceId) {
        activity.runOnUiThread(() -> {
            if (activity.activityDestroyed || !active || !TextUtils.equals(utteranceId, lastQueuedUtteranceId)) return;
            sleepTimerAccrueSegment();
            if (sleepTimerCheckAndMaybeStop()) return;
            int generation = speechGeneration;
            if (continuous && canAdvancePage()) {
                advanceAndSpeakNextPage(generation);
            } else {
                stop(false);
                clearTtsHighlight();
                TtsPlaybackService.stop(activity.getApplicationContext());
                ShortToast.show(activity, R.string.tts_finished);
            }
        });
    }

    private void handleUtteranceError(String utteranceId) {
        activity.runOnUiThread(() -> {
            if (activity.activityDestroyed || !active || !isCurrentGenerationUtterance(utteranceId)) return;
            stop(false);
            clearTtsHighlight();
            ShortToast.show(activity, R.string.tts_engine_unavailable);
        });
    }

    private void advanceAndSpeakNextPage(int generation) {
        int beforePage = activity.getDisplayedCurrentPageNumber();
        int beforeChar = activity.getCurrentCharPosition();
        clearTtsHighlight();
        activity.pageBy(+1, true);
        activity.handler.postDelayed(() -> {
            if (!active || generation != speechGeneration || activity.activityDestroyed) return;
            int afterPage = activity.getDisplayedCurrentPageNumber();
            int afterChar = activity.getCurrentCharPosition();
            if (afterPage == beforePage && afterChar == beforeChar && !canAdvancePage()) {
                stop(false);
                clearTtsHighlight();
                ShortToast.show(activity, R.string.tts_finished);
                return;
            }
            speakCurrentPage(generation, 0);
        }, NEXT_PAGE_DELAY_MS);
    }

    private void movePageFromRemote(int direction) {
        boolean wasContinuous = continuous || (activity.prefs != null && activity.prefs.getTtsLastContinuous());
        stopInternal(false, false);
        activity.pageBy(direction, true);
        activity.handler.postDelayed(() -> {
            if (!activity.activityDestroyed) start(wasContinuous);
        }, NEXT_PAGE_DELAY_MS);
    }

    private boolean canAdvancePage() {
        int total = Math.max(1, activity.getDisplayedTotalPageCount());
        int current = Math.max(1, Math.min(total, activity.getDisplayedCurrentPageNumber()));
        return current < total;
    }

    @NonNull
    private VisiblePage currentVisiblePage() {
        if (activity.readerView == null) return VisiblePage.EMPTY;
        String content = activity.readerView.getTextContent();
        if (content == null || content.isEmpty()) return VisiblePage.EMPTY;

        int start = Math.max(0, Math.min(content.length(), activity.readerView.getCurrentCharPosition()));
        int end = Math.max(start, Math.min(content.length(),
                activity.readerView.getCharPositionAfterCurrentVisibleContent()));
        if (end <= start && start < content.length()) {
            end = Math.min(content.length(), start + MAX_TTS_SEGMENT_CHARS);
        }

        return new VisiblePage(start, content.substring(start, end));
    }

    private int segmentIndexFromUtteranceId(String utteranceId) {
        if (utteranceId == null) return -1;
        int last = utteranceId.lastIndexOf('_');
        if (last < 0 || last >= utteranceId.length() - 1) return -1;
        try {
            return Integer.parseInt(utteranceId.substring(last + 1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean isCurrentGenerationUtterance(String utteranceId) {
        return utteranceId != null
                && utteranceId.startsWith("reader_tts_" + speechGeneration + "_");
    }

    private String utteranceId(int generation, int chunkIndex) {
        return "reader_tts_" + generation + "_" + chunkIndex;
    }

    private void clearTtsHighlight() {
        if (activity.readerView != null) {
            activity.readerView.clearTtsHighlight();
        }
    }

    private void updatePlaybackNotification(boolean isPlaying) {
        String path = activity.filePath != null ? activity.filePath : "";
        String title = path.isEmpty() ? activity.getString(R.string.tts_title) : new File(path).getName();
        int page = Math.max(1, activity.getDisplayedCurrentPageNumber());
        int total = Math.max(1, activity.getDisplayedTotalPageCount());
        String subtitle = activity.getString(R.string.tts_notification_page, page, total);
        TtsPlaybackService.startOrUpdate(
                activity.getApplicationContext(),
                path,
                title,
                subtitle,
                isPlaying,
                continuous);
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
        static final VisiblePage EMPTY = new VisiblePage(0, "");
        final int startChar;
        final String text;

        VisiblePage(int startChar, @NonNull String text) {
            this.startChar = Math.max(0, startChar);
            this.text = text;
        }

        boolean isEmpty() {
            return TtsSegmenter.normalizeForSpeech(text).isEmpty();
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
        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_language), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(4), pad, activity.dpToPx(8));

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
        activity.dialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ttsLanguageListHeightPx()));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
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
        int compactCap = activity.dpToPx(330);
        int screenCap = Math.round(screenHeight * 0.42f);
        return Math.max(activity.dpToPx(220), Math.min(compactCap, screenCap));
    }

    private void addLanguageRow(LinearLayout list,
                                String tag,
                                String label,
                                int fg,
                                android.app.Dialog[] dialogRef,
                                @NonNull Runnable afterSelect) {
        String current = activity.prefs != null ? activity.prefs.getTtsLanguageTag() : "system";
        String rowText = tag.equals(current) ? activity.getString(R.string.tts_language_selected, label) : label;
        TextView row = activity.dialogStyler().makeReaderActionRow(rowText, fg);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        row.setOnClickListener(v -> {
            if (activity.prefs != null) activity.prefs.setTtsLanguageTag(tag);
            if (activity.prefs != null) activity.prefs.setTtsVoiceName("");
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
        String voiceName = activity.prefs != null ? activity.prefs.getTtsVoiceName() : "";
        if (voiceName == null || voiceName.trim().isEmpty()) {
            return activity.getString(R.string.tts_voice_auto);
        }
        return voiceName;
    }

    @NonNull
    private String selectedLanguageLabel() {
        String tag = activity.prefs != null ? activity.prefs.getTtsLanguageTag() : "system";
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
            if (result != TextToSpeech.ERROR) return true;
            if (showUnsupportedToast) {
                ShortToast.show(activity, R.string.tts_voice_unavailable);
            }
        }

        Locale locale = selectedTtsLocale();
        int result = tts.setLanguage(locale);
        boolean supported = result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED;
        if (!supported && showUnsupportedToast) {
            ShortToast.show(activity, activity.getString(R.string.tts_language_unavailable, selectedLanguageLabel()));
        }
        return supported;
    }

    private void applySpeechParameters() {
        if (tts == null) return;
        int rate = activity.prefs != null ? activity.prefs.getTtsSpeechRatePercent() : 100;
        int pitch = activity.prefs != null ? activity.prefs.getTtsPitchPercent() : 100;
        tts.setSpeechRate(Math.max(0.5f, Math.min(2.0f, rate / 100f)));
        tts.setPitch(Math.max(0.5f, Math.min(2.0f, pitch / 100f)));
    }

    @NonNull
    private Locale selectedTtsLocale() {
        String tag = activity.prefs != null ? activity.prefs.getTtsLanguageTag() : "system";
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

        activity.dialogStyler().syncReaderDialogThemeSnapshot();
        final int bg = activity.dialogStyler().readerDialogBgColor();
        final int fg = activity.dialogStyler().readerDialogTextColor(bg);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);

        TextView title = activity.dialogStyler().makeReaderDialogTitle(
                activity.getString(R.string.tts_voice), bg, fg);
        outer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(14);
        list.setPadding(pad, activity.dpToPx(4), pad, activity.dpToPx(8));

        final android.app.Dialog[] ref = new android.app.Dialog[1];
        addVoiceRow(list, "", activity.getString(R.string.tts_voice_auto), fg, ref, afterSelect);

        TextView addVoice = activity.dialogStyler().makeReaderActionRow(
                activity.getString(R.string.tts_add_voice_model), fg);
        addVoice.setGravity(Gravity.CENTER);
        addVoice.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        addVoice.setOnClickListener(v -> openAndroidTtsVoiceInstall());
        list.addView(addVoice);

        List<Voice> voices = matchingVoices();
        if (voices.isEmpty()) {
            TextView empty = activity.dialogStyler().makeReaderActionRow(
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
        activity.dialogStyler().constrainDialogScrollArea(scroll, list);
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(330)));

        android.app.Dialog dialog = activity.dialogStyler().createNarrowPositionedReaderDialog(
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
        String current = activity.prefs != null ? activity.prefs.getTtsVoiceName() : "";
        boolean selected = TextUtils.equals(current, voiceName);
        TextView row = activity.dialogStyler().makeReaderActionRow(
                selected ? activity.getString(R.string.tts_language_selected, label) : label, fg);
        row.setGravity(Gravity.CENTER);
        row.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        row.setSingleLine(false);
        row.setMaxLines(2);
        row.setOnClickListener(v -> {
            if (activity.prefs != null) activity.prefs.setTtsVoiceName(voiceName);
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
        String selected = activity.prefs != null ? activity.prefs.getTtsVoiceName() : "";
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
