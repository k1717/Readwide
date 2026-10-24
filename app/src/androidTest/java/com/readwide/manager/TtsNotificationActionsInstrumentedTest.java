package com.readwide.manager;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Builds notification data only; does not start a service, post or send an action. */
@RunWith(AndroidJUnit4.class)
public class TtsNotificationActionsInstrumentedTest {
    @Test public void playingActionsKeepOrderIconsLabelsAndCommands() {
        check(true);
    }

    @Test public void pausedActionsKeepResumeIconAndCommand() {
        check(false);
    }

    @Test public void legacyBuilderPreservesChannelFreeNotificationData() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            TestService service = new TestService(context);
            try {
                Method create = TtsPlaybackService.class.getDeclaredMethod("createLegacyNotificationBuilder");
                create.setAccessible(true);
                Notification.Builder builder = (Notification.Builder) create.invoke(service);
                Notification notification = builder.setSmallIcon(R.drawable.ic_bottom_tts)
                        .setContentTitle("Narration").setOnlyAlertOnce(true).setShowWhen(false).build();
                assertEquals("Narration", notification.extras.getCharSequence(Notification.EXTRA_TITLE));
                assertTrue((notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Inspect data only: a channel-free notification is never posted on API 26+.
                    assertNull(notification.getChannelId());
                }
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }

    private static void check(boolean playing) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Assume.assumeFalse(TtsPlaybackBridge.hasLiveHost());
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            TestService service = new TestService(context);
            String[] commands = {
                    TtsPlaybackService.ACTION_PREVIOUS, TtsPlaybackService.ACTION_PLAY_PAUSE,
                    TtsPlaybackService.ACTION_NEXT, TtsPlaybackService.ACTION_STOP
            };
            Notification notification = null;
            try {
                Method update = TtsPlaybackService.class.getDeclaredMethod("updateState", Intent.class);
                update.setAccessible(true);
                update.invoke(service, new Intent()
                        .putExtra(TtsPlaybackService.EXTRA_TITLE, "Narration")
                        .putExtra(TtsPlaybackService.EXTRA_PLAYING, playing));
                Method build = TtsPlaybackService.class.getDeclaredMethod("buildNotification");
                build.setAccessible(true);
                notification = (Notification) build.invoke(service);

                assertNotNull(notification);
                assertNotNull(notification.contentIntent);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    assertEquals("tts_playback", notification.getChannelId());
                }
                assertEquals(playing, (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
                assertEquals(4, notification.actions.length);
                int[] labels = { R.string.tts_action_previous,
                        playing ? R.string.tts_action_pause : R.string.tts_action_resume,
                        R.string.tts_action_next, R.string.tts_stop };
                int[] icons = { android.R.drawable.ic_media_previous,
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        android.R.drawable.ic_media_next, R.drawable.ic_tts_stop };
                for (int i = 0; i < commands.length; i++) {
                    Notification.Action action = notification.actions[i];
                    assertEquals(context.getString(labels[i]), action.title.toString());
                    assertNotNull(action.getIcon());
                    assertNotNull(action.getIcon().loadDrawable(context));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        assertEquals(icons[i], action.getIcon().getResId());
                    }
                    PendingIntent expected = PendingIntent.getService(context, commands[i].hashCode(),
                            new Intent(context, TtsPlaybackService.class).setAction(commands[i]),
                            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                    assertNotNull(expected);
                    assertEquals(expected, action.actionIntent);
                    for (int j = 0; j < i; j++) {
                        assertNotEquals(notification.actions[j].actionIntent, action.actionIntent);
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            } finally {
                if (notification != null) {
                    if (notification.contentIntent != null) notification.contentIntent.cancel();
                    if (notification.actions != null) {
                        for (Notification.Action action : notification.actions) {
                            if (action.actionIntent != null) action.actionIntent.cancel();
                        }
                    }
                }
                // Also release partial builder output if reflection failed before returning it.
                for (String command : commands) {
                    PendingIntent pending = PendingIntent.getService(context, command.hashCode(),
                            new Intent(context, TtsPlaybackService.class).setAction(command),
                            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                    if (pending != null) pending.cancel();
                }
                PendingIntent content = PendingIntent.getActivity(context, 0,
                        new Intent(context, ReaderActivity.class),
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (content != null) content.cancel();
            }
        });
    }

    private static final class TestService extends TtsPlaybackService {
        TestService(Context context) { attachBaseContext(context); }
    }
}
