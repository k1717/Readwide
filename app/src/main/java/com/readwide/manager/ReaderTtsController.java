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
            sleepTimerDialog = new TtsSleepTimerDialog(host, this);
        }
        return sleepTimerDialog;
    }
    private int currentSegmentIndex = 0;            // segment currently being spoken
    private int pausedSegmentIndex = 0;             // segment to resume from
    private int currentSpeechGeneration = 0;        // generation of the queued page

    // Cross-page pre-buffering: how much of the next page to queue ahead, and the
    // index in queuedSegments where the prefetched next-page segments begin
    // (-1 = nothing prefetched). When speech crosses this boundary the UI page is
    // turned to follow, and the normal end-of-page advance is skipped because the
    // next page is already playing from the same queue.
    private static final int PREFETCH_NEXT_PAGE_CHARS = 1400;
    private int prefetchedNextPageBoundaryIndex = -1;
    private boolean crossedPrefetchBoundary = false;

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
        active = false;
        paused = false;
        pausedForFocusLoss = false;
        speechGeneration++;
        lastQueuedUtteranceId = "";
        queuedSegments.clear();
        clearTtsHighlight();
        host.ttsUpdateFloatingCard();
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
        host.ttsUpdateFloatingCard();
        // Persist the paused position so it survives leaving the app.
        if (host.ttsHostPrefs() != null && host.ttsHostFilePath() != null
                && pausedSegmentIndex >= 0 && pausedSegmentIndex < queuedSegments.size()) {
            TtsSpeechSegment seg = queuedSegments.get(pausedSegmentIndex);
            host.ttsHostPrefs().setTtsLastPlaybackState(
                    host.ttsHostFilePath(),
                    seg.startChar,
                    host.ttsDisplayedCurrentPageNumber(),
                    continuous,
                    host.ttsHostPrefs().getTtsSleepTimerMinutes());
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
        // The paused queue may already contain prefetched next-page segments.
        // Rather than reconstruct the boundary bookkeeping across a pause, just
        // replay the queue as-is; the end-of-queue advance handles the following
        // page and a fresh page queue re-arms prefetch.
        prefetchedNextPageBoundaryIndex = -1;
        crossedPrefetchBoundary = false;
        int from = Math.max(0, Math.min(pausedSegmentIndex, queuedSegments.size() - 1));
        speakQueuedFrom(from);
        updatePlaybackNotification(true);
        host.ttsUpdateFloatingCard();
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
                start(continuous || (host.ttsHostPrefs() != null && host.ttsHostPrefs().getTtsLastContinuous()));
            }
        } else if (TtsPlaybackService.ACTION_NEXT.equals(action)) {
            movePageFromRemote(+1);
        } else if (TtsPlaybackService.ACTION_PREVIOUS.equals(action)) {
            movePageFromRemote(-1);
        }
    }

    private boolean hasResumeStateForCurrentFile() {
        if (host.ttsHostPrefs() == null || host.ttsHostFilePath() == null) return false;
        return TextUtils.equals(host.ttsHostFilePath(), host.ttsHostPrefs().getTtsLastFilePath())
                && host.ttsHostPrefs().getTtsLastCharPosition() > 0;
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
        host.ttsJumpToAbsoluteCharPosition(charPosition,
                host.ttsHostPrefs().getTtsLastPageNumber(),
                host.ttsDisplayedTotalPageCount());
        host.ttsHostHandler().postDelayed(() -> {
            if (!host.isTtsHostDestroyed()) start(resumeContinuous);
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
        if (host.ttsHostPrefs() != null) {
            int minutes = host.ttsHostPrefs().getTtsSleepTimerMinutes();
            sleepTimerTargetMs = minutes > 0 ? minutes * 60_000L : 0L;
            sleepTimerFinishSentence = host.ttsHostPrefs().getTtsSleepTimerFinishSentence();
        } else {
            sleepTimerTargetMs = 0L;
            sleepTimerFinishSentence = true;
        }
        sleepTimerAccruedMs = 0L;
        sleepTimerSegmentStartMs = 0L;
    }

    /** Re-read timer settings mid-playback (e.g. user changed the timer while playing). */
    void refreshSleepTimerFromPrefs() {
        if (!active || host.ttsHostPrefs() == null) return;
        int minutes = host.ttsHostPrefs().getTtsSleepTimerMinutes();
        sleepTimerTargetMs = minutes > 0 ? minutes * 60_000L : 0L;
        sleepTimerFinishSentence = host.ttsHostPrefs().getTtsSleepTimerFinishSentence();
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
        host.ttsUpdateFloatingCard();
        speakCurrentPage(generation, 0);
    }

    private void speakCurrentPage(int generation, int partitionRetryCount) {
        if (!active || generation != speechGeneration || host.isTtsHostDestroyed()) return;

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
                ttsSegmentChars(),
                ttsPauseReduction());
        if (segments.isEmpty()) {
            stop(false);
            return;
        }

        queuedSegments.clear();
        queuedSegments.addAll(segments);
        currentSpeechGeneration = generation;
        currentSegmentIndex = 0;
        prefetchedNextPageBoundaryIndex = -1;
        crossedPrefetchBoundary = false;
        lastQueuedUtteranceId = utteranceId(generation, segments.size() - 1);
        android.util.Log.d(TTS_LOG_TAG, "queue page: " + segments.size() + " segments, gen=" + generation
                + ", first lens=" + firstSegmentLengths(segments));
        for (int i = 0; i < segments.size(); i++) {
            Bundle params = new Bundle();
            String utteranceId = utteranceId(generation, i);
            int queueMode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = tts.speak(segments.get(i).speechText, queueMode, params, utteranceId);
            if (result == TextToSpeech.ERROR) {
                android.util.Log.d(TTS_LOG_TAG, "speak() ERROR at segment " + i + "/" + segments.size()
                        + " (queue page), stopping");
                stop(false);
                ShortToast.show(activity, R.string.tts_engine_unavailable);
                return;
            }
        }
        // Neural engines (sherpa-onnx/VoxSherpa, Kokoro/Piper) synthesize with
        // enough latency that the gap between the current page's last utterance
        // and the first utterance of the next page is audible. When the whole
        // document text is already in memory (i.e. not the lazily partitioned
        // large-text path), append the next page's opening segments onto the
        // same engine queue now, so the engine keeps synthesizing across the
        // boundary instead of going silent while we turn the page. The UI page
        // turn still happens later, driven by onStart of the first prefetched
        // segment, so highlight and saved-position tracking are unaffected.
        maybePrefetchNextPageSegments(generation);
    }

    /**
     * Append the beginning of the next page's text to the live queue so playback
     * does not stall at the page seam. No-op unless continuous mode is on, the
     * document is not the lazily partitioned large-text kind, and the reader can
     * hand us the full text with a known boundary.
     */
    private void maybePrefetchNextPageSegments(int generation) {
        if (!continuous || tts == null) return;
        if (!host.isTtsTextFullyResident()) return; // text is not fully resident
        TtsTextSource source = host.ttsTextSource();
        if (source == null) return;
        if (!canAdvancePage()) return;
        if (queuedSegments.isEmpty()) return;

        String content = source.getTextContent();
        if (content == null || content.isEmpty()) return;
        int nextStart = queuedSegments.get(queuedSegments.size() - 1).endChar;
        if (nextStart < 0 || nextStart >= content.length()) return;

        int nextEnd = Math.min(content.length(), nextStart + PREFETCH_NEXT_PAGE_CHARS);
        if (nextEnd <= nextStart) return;
        String nextText = content.substring(nextStart, nextEnd);
        List<TtsSpeechSegment> nextSegments = TtsSegmenter.segmentPage(
                nextText, nextStart, ttsSegmentChars(), ttsPauseReduction());
        if (nextSegments.isEmpty()) return;

        prefetchedNextPageBoundaryIndex = queuedSegments.size();
        android.util.Log.d(TTS_LOG_TAG, "prefetch next page: " + nextSegments.size()
                + " segments from char " + nextStart);
        int base = queuedSegments.size();
        queuedSegments.addAll(nextSegments);
        lastQueuedUtteranceId = utteranceId(generation, queuedSegments.size() - 1);
        for (int i = 0; i < nextSegments.size(); i++) {
            Bundle params = new Bundle();
            String utteranceId = utteranceId(generation, base + i);
            int result = tts.speak(nextSegments.get(i).speechText,
                    TextToSpeech.QUEUE_ADD, params, utteranceId);
            if (result == TextToSpeech.ERROR) {
                // Non-fatal: the current page is already queued and will play;
                // drop the prefetch and let the normal page-turn path continue.
                android.util.Log.d(TTS_LOG_TAG, "speak() ERROR at prefetch segment " + i
                        + "/" + nextSegments.size() + " (non-fatal, dropping prefetch)");
                while (queuedSegments.size() > base) {
                    queuedSegments.remove(queuedSegments.size() - 1);
                }
                prefetchedNextPageBoundaryIndex = -1;
                lastQueuedUtteranceId = utteranceId(generation, queuedSegments.size() - 1);
                return;
            }
        }
    }

    private void handleUtteranceStart(String utteranceId) {
        activity.runOnUiThread(() -> {
            if (host.isTtsHostDestroyed() || !active || utteranceId == null) return;
            // Sleep timer: account for the segment that just finished, then stop at
            // this sentence boundary if the playback-time target has been reached.
            sleepTimerAccrueSegment();
            if (sleepTimerCheckAndMaybeStop()) return;
            sleepTimerOnSegmentStart();
            int segmentIndex = segmentIndexFromUtteranceId(utteranceId);
            if (segmentIndex < 0 || segmentIndex >= queuedSegments.size()) return;

            // If speech has reached the prefetched next-page segments, catch the
            // UI up to the page that is already being spoken, then chain another
            // prefetch so the following seam is covered too. jumpToAbsoluteChar
            // only scrolls the reader; it does not touch the engine queue, so
            // audio keeps flowing without a gap.
            if (prefetchedNextPageBoundaryIndex >= 0
                    && segmentIndex >= prefetchedNextPageBoundaryIndex
                    && !crossedPrefetchBoundary) {
                crossedPrefetchBoundary = true;
                TtsSpeechSegment boundarySegment = queuedSegments.get(prefetchedNextPageBoundaryIndex);
                host.ttsJumpToAbsoluteCharPosition(boundarySegment.startChar);
                prefetchedNextPageBoundaryIndex = -1;
                int generation = currentSpeechGeneration;
                // Defer the follow-on prefetch until after the reader has settled
                // on the new page so canAdvancePage()/getTextContent() reflect it.
                host.ttsHostHandler().post(() -> {
                    if (!active || host.isTtsHostDestroyed()
                            || generation != currentSpeechGeneration) return;
                    crossedPrefetchBoundary = false;
                    maybePrefetchNextPageSegments(generation);
                });
            }

            currentSegmentIndex = segmentIndex;
            TtsSpeechSegment segment = queuedSegments.get(segmentIndex);
            TtsTextSource source = host.ttsTextSource();
            if (source != null) {
                source.setTtsHighlightRange(segment.startChar, segment.endChar);
            }
            if (host.ttsHostPrefs() != null && host.ttsHostFilePath() != null) {
                host.ttsHostPrefs().setTtsLastPlaybackState(
                        host.ttsHostFilePath(),
                        segment.startChar,
                        host.ttsDisplayedCurrentPageNumber(),
                        continuous,
                        host.ttsHostPrefs().getTtsSleepTimerMinutes());
            }
            updatePlaybackNotification(true);
        });
    }

    private void handleUtteranceDone(String utteranceId) {
        activity.runOnUiThread(() -> {
            if (host.isTtsHostDestroyed() || !active || !TextUtils.equals(utteranceId, lastQueuedUtteranceId)) return;
            sleepTimerAccrueSegment();
            if (sleepTimerCheckAndMaybeStop()) return;
            int generation = speechGeneration;
            if (continuous && canAdvancePage()) {
                advanceAndSpeakNextPage(generation);
            } else {
                // Whole document finished reading aloud — nothing left to resume, so
                // drop the saved playback state that drives the main-screen prompt.
                if (host.ttsHostPrefs() != null) host.ttsHostPrefs().clearTtsLastPlaybackState();
                stop(false);
                clearTtsHighlight();
                TtsPlaybackService.stop(activity.getApplicationContext());
                ShortToast.show(activity, R.string.tts_finished);
            }
        });
    }

    private void handleUtteranceError(String utteranceId) {
        activity.runOnUiThread(() -> {
            android.util.Log.d(TTS_LOG_TAG, "onError: id=" + utteranceId
                    + ", active=" + active + ", gen=" + speechGeneration);
            if (host.isTtsHostDestroyed() || !active || !isCurrentGenerationUtterance(utteranceId)) return;
            stop(false);
            clearTtsHighlight();
            ShortToast.show(activity, R.string.tts_engine_unavailable);
        });
    }

    /** Lengths of the first few segments, for the queue diagnostics log line. */
    private static String firstSegmentLengths(@NonNull List<TtsSpeechSegment> segments) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(3, segments.size()); i++) {
            if (i > 0) sb.append(',');
            sb.append(segments.get(i).speechText.length());
        }
        return sb.append(']').toString();
    }

    private void advanceAndSpeakNextPage(int generation) {
        int beforePage = host.ttsDisplayedCurrentPageNumber();
        int beforeChar = host.ttsCurrentCharPosition();
        clearTtsHighlight();
        host.ttsHostPageBy(+1);
        host.ttsHostHandler().postDelayed(() -> {
            if (!active || generation != speechGeneration || host.isTtsHostDestroyed()) return;
            int afterPage = host.ttsDisplayedCurrentPageNumber();
            int afterChar = host.ttsCurrentCharPosition();
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
        boolean wasContinuous = continuous || (host.ttsHostPrefs() != null && host.ttsHostPrefs().getTtsLastContinuous());
        stopInternal(false, false);
        host.ttsHostPageBy(direction);
        host.ttsHostHandler().postDelayed(() -> {
            if (!host.isTtsHostDestroyed()) start(wasContinuous);
        }, NEXT_PAGE_DELAY_MS);
    }

    private boolean canAdvancePage() {
        int total = Math.max(1, host.ttsDisplayedTotalPageCount());
        int current = Math.max(1, Math.min(total, host.ttsDisplayedCurrentPageNumber()));
        return current < total;
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
