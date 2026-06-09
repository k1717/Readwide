package com.textview.reader;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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

    ReaderTtsController(@NonNull ReaderActivity activity) {
        this.activity = activity;
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
        LinearLayout languageBox = makeOptionBox();
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

        addPercentSlider(languageBox,
                activity.getString(R.string.tts_speed),
                activity.prefs != null ? activity.prefs.getTtsSpeechRatePercent() : 100,
                value -> {
                    if (activity.prefs != null) activity.prefs.setTtsSpeechRatePercent(value);
                    applySpeechParameters();
                },
                fg,
                sub);
        addPercentSlider(languageBox,
                activity.getString(R.string.tts_pitch),
                activity.prefs != null ? activity.prefs.getTtsPitchPercent() : 100,
                value -> {
                    if (activity.prefs != null) activity.prefs.setTtsPitchPercent(value);
                    applySpeechParameters();
                },
                fg,
                sub);

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
        for (TextView actionButton : new TextView[]{stopButton, pageButton, continuousButton}) {
            actionButton.setTextSize(12.5f);
            actionButton.setPadding(activity.dpToPx(8), 0, activity.dpToPx(8), 0);
            actionButton.setSingleLine(true);
        }

        actionRow.addView(stopButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
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
        speechGeneration++;
        lastQueuedUtteranceId = "";
        queuedSegments.clear();
        clearTtsHighlight();
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

    void stopForManualNavigation() {
        if (active || pendingStart) {
            stop(false);
        }
    }

    void release() {
        stop(false);
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

    void handlePlaybackCommand(@NonNull String action) {
        if (TtsPlaybackService.ACTION_STOP.equals(action)) {
            stop(true);
        } else if (TtsPlaybackService.ACTION_PLAY_PAUSE.equals(action)) {
            if (active || pendingStart) {
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
        activity.handler.postDelayed(() -> start(resumeContinuous), 420L);
    }

    @Override
    public void onInit(int status) {
        activity.runOnUiThread(() -> {
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

    private void startNow(boolean continuousMode) {
        if (tts == null || !initialized) return;
        if (!applySelectedLanguage(true)) {
            active = false;
            pendingStart = false;
            return;
        }
        applySpeechParameters();
        active = true;
        continuous = continuousMode;
        int generation = ++speechGeneration;
        updatePlaybackNotification(true);
        ShortToast.show(activity, continuousMode ? R.string.tts_continuous_started : R.string.tts_started);
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
            if (!active || utteranceId == null) return;
            int segmentIndex = segmentIndexFromUtteranceId(utteranceId);
            if (segmentIndex < 0 || segmentIndex >= queuedSegments.size()) return;
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
            if (!active || !TextUtils.equals(utteranceId, lastQueuedUtteranceId)) return;
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
            if (!active || !isCurrentGenerationUtterance(utteranceId)) return;
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
        activity.handler.postDelayed(() -> start(wasContinuous), NEXT_PAGE_DELAY_MS);
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

    private LinearLayout makeOptionBox() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(10), activity.dpToPx(10),
                activity.dpToPx(10), activity.dpToPx(2));
        box.setBackground(roundedPanelBackground(activity.dialogStyler().readerDialogPanelColor(), 14));
        return box;
    }

    private interface PercentValueCallback {
        void onChanged(int value);
    }

    private void addPercentSlider(LinearLayout parent,
                                  String title,
                                  int initialValue,
                                  @NonNull PercentValueCallback callback,
                                  int fg,
                                  int sub) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dpToPx(8), activity.dpToPx(8),
                activity.dpToPx(8), activity.dpToPx(6));
        box.setBackground(roundedPanelBackground(activity.dialogStyler().readerDialogBgColor(), 10));

        TextView label = new TextView(activity);
        label.setText(percentLabel(title, initialValue));
        label.setTextColor(fg);
        label.setTextSize(13f);
        label.setGravity(Gravity.CENTER);
        label.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        label.setIncludeFontPadding(false);
        box.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(22)));

        SeekBar seek = new SeekBar(activity);
        seek.setMax(150);
        seek.setProgress(Math.max(50, Math.min(200, initialValue)) - 50);
        seek.setPadding(activity.dpToPx(16), 0, activity.dpToPx(16), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.max(50, Math.min(200, progress + 50));
                label.setText(percentLabel(title, value));
                if (fromUser) callback.onChanged(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                callback.onChanged(Math.max(50, Math.min(200, seekBar.getProgress() + 50)));
            }
        });
        box.addView(seek, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                activity.dpToPx(34)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, activity.dpToPx(8));
        parent.addView(box, lp);
    }

    private String percentLabel(String title, int value) {
        return title + ": " + Math.max(50, Math.min(200, value)) + "%";
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

    @NonNull
    private GradientDrawable roundedPanelBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(activity.dpToPx(radiusDp));
        drawable.setStroke(0, Color.TRANSPARENT);
        return drawable;
    }
}
