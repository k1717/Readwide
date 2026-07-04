package com.readwide.manager;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shared wiring for the floating read-aloud playback card (play/pause + stop),
 * originally built for the TXT reader and reused by the document and PDF
 * viewers so all three behave identically. The card can be dragged anywhere on
 * screen and taps route to whichever button is under the finger.
 *
 * <p>This is pure view/gesture wiring; the owning activity supplies a
 * {@link Controls} callback for the three actions (toggle play/pause, stop,
 * and query paused state) and drives visibility/icon updates through
 * {@link #update}. Kept as a static helper (not a base class) because the three
 * activities already extend different bases.</p>
 */
final class TtsFloatingCardController {

    /** The playback actions the card needs from its owning activity. */
    interface Controls {
        /** True while read-aloud is active (playing or paused). */
        boolean isActive();
        /** True while read-aloud is paused. */
        boolean isPaused();
        /** Toggle between play and pause. */
        void togglePlayPause();
        /** Stop playback entirely. */
        void stop();
    }

    private TtsFloatingCardController() {
    }

    /**
     * Binds the card's buttons and drag/tap gesture handling. Safe to call with
     * a null card (no-op), which lets viewers that have not added the card to
     * their layout opt out silently.
     */
    static void setup(@NonNull Activity activity,
                      @Nullable View card,
                      @Nullable ImageButton playPause,
                      @Nullable ImageButton stop,
                      @NonNull Controls controls) {
        if (card == null) return;

        if (playPause != null) {
            playPause.setOnClickListener(v -> controls.togglePlayPause());
        }
        if (stop != null) {
            stop.setOnClickListener(v -> controls.stop());
        }

        final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        final float[] downRaw = new float[2];
        final float[] startXY = new float[2];
        final boolean[] dragging = {false};
        final View playPauseView = playPause;
        final View stopView = stop;
        card.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    startXY[0] = card.getTranslationX();
                    startXY[1] = card.getTranslationY();
                    dragging[0] = false;
                    return true; // take the whole gesture so dragging works anywhere
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > touchSlop) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        float nx = startXY[0] + dx;
                        float ny = startXY[1] + dy;
                        View parent = (View) card.getParent();
                        if (parent != null) {
                            float maxX = (parent.getWidth() - card.getWidth()) / 2f;
                            float maxY = parent.getHeight() - card.getHeight() - card.getTop();
                            float minY = -card.getTop();
                            if (maxX < 0) maxX = 0;
                            nx = Math.max(-maxX, Math.min(maxX, nx));
                            ny = Math.max(minY, Math.min(maxY, ny));
                        }
                        card.setTranslationX(nx);
                        card.setTranslationY(ny);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragging[0]) {
                        // Treat as a tap: route to the button under the finger.
                        if (playPauseView != null
                                && isPointInsideView(event.getRawX(), event.getRawY(), playPauseView)) {
                            controls.togglePlayPause();
                        } else if (stopView != null
                                && isPointInsideView(event.getRawX(), event.getRawY(), stopView)) {
                            controls.stop();
                        }
                    }
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging[0] = false;
                    return true;
            }
            return false;
        });
    }

    /**
     * Shows or hides the card and refreshes the play/pause icon and its content
     * description to match current playback state. No-op when the card is null.
     */
    static void update(@NonNull Activity activity,
                       @Nullable View card,
                       @Nullable ImageButton playPause,
                       @NonNull Controls controls) {
        if (card == null) return;
        if (controls.isActive()) {
            card.setVisibility(View.VISIBLE);
            if (playPause != null) {
                boolean paused = controls.isPaused();
                playPause.setImageResource(paused
                        ? android.R.drawable.ic_media_play
                        : android.R.drawable.ic_media_pause);
                playPause.setContentDescription(
                        activity.getString(paused ? R.string.tts_resume : R.string.tts_pause));
            }
        } else {
            card.setVisibility(View.GONE);
        }
    }

    private static boolean isPointInsideView(float rawX, float rawY, @NonNull View view) {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + view.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + view.getHeight();
    }
}
