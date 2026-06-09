package com.textview.reader.controller;

import android.os.Handler;

import androidx.annotation.NonNull;

/**
 * Owns the timer state for automatic page turning.
 *
 * <p>The activity still owns UI construction, paging, and toast text.  This
 * controller only tracks whether auto-turn is running and when the next tick
 * should fire, which keeps timer lifecycle out of ReaderActivity.</p>
 */
public final class AutoPageTurnController {
    public interface Callback {
        boolean isDestroyed();
        int getDisplayedTotalPageCount();
        int getDisplayedCurrentPageNumber();
        int getIntervalSeconds();
        void pageForwardFromAutoPageTurn();
        void onAutoPageTurnStarted();
        void onAutoPageTurnStopped();
        void onAutoPageTurnEndReached();
    }

    private final Handler handler;
    private final Callback callback;
    private boolean running;

    private final Runnable turnRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || callback.isDestroyed()) return;

            int total = Math.max(1, callback.getDisplayedTotalPageCount());
            int current = Math.max(1, callback.getDisplayedCurrentPageNumber());
            if (current >= total) {
                stop(true);
                callback.onAutoPageTurnEndReached();
                return;
            }

            callback.pageForwardFromAutoPageTurn();
            scheduleNext();
        }
    };

    public AutoPageTurnController(@NonNull Handler handler, @NonNull Callback callback) {
        this.handler = handler;
        this.callback = callback;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        running = true;
        handler.removeCallbacks(turnRunnable);
        callback.onAutoPageTurnStarted();
        scheduleNext();
    }

    public void stop(boolean notify) {
        if (!running && !notify) return;
        running = false;
        handler.removeCallbacks(turnRunnable);
        if (notify) callback.onAutoPageTurnStopped();
    }

    public void stopForManualNavigation() {
        if (running) stop(true);
    }

    public void release() {
        running = false;
        handler.removeCallbacks(turnRunnable);
    }

    private void scheduleNext() {
        if (!running) return;
        int seconds = Math.max(2, callback.getIntervalSeconds());
        handler.removeCallbacks(turnRunnable);
        handler.postDelayed(turnRunnable, seconds * 1000L);
    }
}
