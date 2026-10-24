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

    private static TtsHost liveHost() {
        TtsHost host;
        synchronized (TtsPlaybackBridge.class) {
            host = activeHost.get();
        }
        if (host == null || host.ttsHostActivity().isFinishing() || host.isTtsHostDestroyed()) {
            return null;
        }
        return host;
    }

    static boolean hasLiveHost() {
        return liveHost() != null;
    }

    /** Only a notification body tap may foreground the existing session without reloading. */
    static boolean isNotificationReturnTo(TtsHost host, android.content.Intent intent,
                                           String pathExtra) {
        if (host == null || intent == null || intent.getAction() != null || intent.getData() != null
                || !intent.getBooleanExtra(TtsPlaybackService.EXTRA_RETURN_TO_PLAYBACK, false)
                || liveHost() != host) return false;
        String path = intent.getStringExtra(pathExtra);
        if (path == null || path.isEmpty() || !path.equals(host.ttsHostFilePath())) return false;
        android.os.Bundle extras = intent.getExtras();
        if (extras == null) return false;
        for (String key : extras.keySet()) {
            // Explicit bookmark, URI, position or autostart requests must retain
            // their normal load behavior even if a copied intent carries a marker.
            if (!pathExtra.equals(key) && !TtsPlaybackService.EXTRA_RETURN_TO_PLAYBACK.equals(key)) return false;
        }
        return true;
    }

    static boolean dispatch(String action) {
        TtsHost host = liveHost();
        if (host == null) return false;
        host.ttsHostActivity().runOnUiThread(() -> host.ttsHandlePlaybackCommand(action));
        return true;
    }
}
