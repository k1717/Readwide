package com.readwide.manager;

import java.lang.ref.WeakReference;

/**
 * Routes remote playback commands (notification actions, media buttons) from
 * {@link TtsPlaybackService} to whichever {@link TtsHost} currently owns
 * read-aloud. Hosts register on startup/resume ({@code ReaderActivity}) or when
 * read-aloud is opened ({@code DocumentPageActivity}) and unregister on destroy.
 */
final class TtsPlaybackBridge {
    private static WeakReference<TtsHost> activeHost = new WeakReference<>(null);

    private TtsPlaybackBridge() {
    }

    static synchronized void register(TtsHost host) {
        activeHost = new WeakReference<>(host);
    }

    static synchronized void unregister(TtsHost host) {
        TtsHost current = activeHost.get();
        if (current == null || current == host) {
            activeHost = new WeakReference<>(null);
        }
    }

    static boolean dispatch(String action) {
        TtsHost host;
        synchronized (TtsPlaybackBridge.class) {
            host = activeHost.get();
        }
        if (host == null || host.ttsHostActivity().isFinishing() || host.isTtsHostDestroyed()) {
            return false;
        }
        host.ttsHostActivity().runOnUiThread(() -> host.ttsHandlePlaybackCommand(action));
        return true;
    }
}
