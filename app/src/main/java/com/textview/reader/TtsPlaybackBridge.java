package com.textview.reader;

import java.lang.ref.WeakReference;

final class TtsPlaybackBridge {
    private static WeakReference<ReaderActivity> activeReader = new WeakReference<>(null);

    private TtsPlaybackBridge() {
    }

    static synchronized void register(ReaderActivity activity) {
        activeReader = new WeakReference<>(activity);
    }

    static synchronized void unregister(ReaderActivity activity) {
        ReaderActivity current = activeReader.get();
        if (current == null || current == activity) {
            activeReader = new WeakReference<>(null);
        }
    }

    static boolean dispatch(String action) {
        ReaderActivity activity;
        synchronized (TtsPlaybackBridge.class) {
            activity = activeReader.get();
        }
        if (activity == null || activity.isFinishing() || activity.activityDestroyed) {
            return false;
        }
        activity.runOnUiThread(() -> activity.handleTtsPlaybackCommand(action));
        return true;
    }
}
