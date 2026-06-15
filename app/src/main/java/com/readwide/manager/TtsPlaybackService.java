package com.readwide.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.session.PlaybackState;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

public class TtsPlaybackService extends Service {
    static final String ACTION_START = "com.readwide.manager.tts.START";
    static final String ACTION_REFRESH = "com.readwide.manager.tts.REFRESH";
    static final String ACTION_PLAY_PAUSE = "com.readwide.manager.tts.PLAY_PAUSE";
    static final String ACTION_STOP = "com.readwide.manager.tts.STOP";
    static final String ACTION_NEXT = "com.readwide.manager.tts.NEXT";
    static final String ACTION_PREVIOUS = "com.readwide.manager.tts.PREVIOUS";
    static final String EXTRA_FILE_PATH = "file_path";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_SUBTITLE = "subtitle";
    static final String EXTRA_PLAYING = "playing";
    static final String EXTRA_CONTINUOUS = "continuous";

    private static final String CHANNEL_ID = "tts_playback";
    private static final int NOTIFICATION_ID = 2202;

    private MediaSession mediaSession;
    private String filePath = "";
    private String title = "";
    private String subtitle = "";
    private boolean playing = false;
    private boolean continuous = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        mediaSession = new MediaSession(this, "Readwide TTS");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { dispatch(ACTION_PLAY_PAUSE); }
            @Override public void onPause() { dispatch(ACTION_PLAY_PAUSE); }
            @Override public void onStop() { dispatch(ACTION_STOP); }
            @Override public void onSkipToNext() { dispatch(ACTION_NEXT); }
            @Override public void onSkipToPrevious() { dispatch(ACTION_PREVIOUS); }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent != null
                        ? mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        : null;
                if (event == null || event.getAction() != KeyEvent.ACTION_UP) {
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    dispatch(ACTION_NEXT);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    dispatch(ACTION_PREVIOUS);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK
                        || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY
                        || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    dispatch(ACTION_PLAY_PAUSE);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_STOP) {
                    dispatch(ACTION_STOP);
                    return true;
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        mediaSession.setActive(true);
        updateMediaSessionState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action) || ACTION_PLAY_PAUSE.equals(action)
                || ACTION_NEXT.equals(action) || ACTION_PREVIOUS.equals(action)) {
            boolean delivered = TtsPlaybackBridge.dispatch(action);
            if (!delivered) {
                if (ACTION_STOP.equals(action)) {
                    stopSelf();
                } else {
                    playing = false;
                    subtitle = getString(R.string.tts_action_remote_unavailable);
                    startForeground(NOTIFICATION_ID, buildNotification());
                }
            }
            return START_STICKY;
        }

        updateState(intent);
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    private void dispatch(String action) {
        TtsPlaybackBridge.dispatch(action);
    }

    private void updateState(Intent intent) {
        if (intent == null) return;
        filePath = intent.getStringExtra(EXTRA_FILE_PATH) != null ? intent.getStringExtra(EXTRA_FILE_PATH) : filePath;
        title = intent.getStringExtra(EXTRA_TITLE) != null ? intent.getStringExtra(EXTRA_TITLE) : title;
        subtitle = intent.getStringExtra(EXTRA_SUBTITLE) != null ? intent.getStringExtra(EXTRA_SUBTITLE) : subtitle;
        playing = intent.getBooleanExtra(EXTRA_PLAYING, playing);
        continuous = intent.getBooleanExtra(EXTRA_CONTINUOUS, continuous);
        updateMediaSessionState();
    }

    private Notification buildNotification() {
        updateMediaSessionState();
        Intent openIntent = new Intent(this, ReaderActivity.class);
        if (filePath != null && !filePath.isEmpty()) {
            openIntent.putExtra(ReaderActivity.EXTRA_FILE_PATH, filePath);
        }
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_bottom_tts)
                .setContentTitle(title == null || title.isEmpty() ? getString(R.string.tts_title) : title)
                .setContentText(subtitle == null || subtitle.isEmpty() ? getString(R.string.tts_notification_running) : subtitle)
                .setContentIntent(contentIntent)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(actionIcon("previous"), getString(R.string.tts_action_previous), commandIntent(ACTION_PREVIOUS))
                .addAction(actionIcon("play"), playing ? getString(R.string.tts_action_pause) : getString(R.string.tts_action_resume), commandIntent(ACTION_PLAY_PAUSE))
                .addAction(actionIcon("next"), getString(R.string.tts_action_next), commandIntent(ACTION_NEXT))
                .addAction(actionIcon("stop"), getString(R.string.tts_stop), commandIntent(ACTION_STOP));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(1, 3));
        }
        return builder.build();
    }

    private void updateMediaSessionState() {
        if (mediaSession == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        long actions = PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
    }

    private PendingIntent commandIntent(String action) {
        Intent intent = new Intent(this, TtsPlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private int actionIcon(String type) {
        if ("stop".equals(type)) return R.drawable.ic_tts_stop;
        if ("previous".equals(type)) return android.R.drawable.ic_media_previous;
        if ("next".equals(type)) return android.R.drawable.ic_media_next;
        return playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.tts_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.tts_notification_channel_description));
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    static void startOrUpdate(Context context,
                              String filePath,
                              String title,
                              String subtitle,
                              boolean playing,
                              boolean continuous) {
        Intent intent = new Intent(context, TtsPlaybackService.class);
        intent.setAction(ACTION_REFRESH);
        intent.putExtra(EXTRA_FILE_PATH, filePath);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_SUBTITLE, subtitle);
        intent.putExtra(EXTRA_PLAYING, playing);
        intent.putExtra(EXTRA_CONTINUOUS, continuous);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Background-start restrictions should not crash foreground reading.
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, TtsPlaybackService.class));
    }
}
