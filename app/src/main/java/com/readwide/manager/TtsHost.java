package com.readwide.manager;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.readwide.manager.util.PrefsManager;

/**
 * Everything {@link ReaderTtsController} needs from the activity that owns it,
 * so the same controller can run inside {@code ReaderActivity} (plain
 * text/Markdown) and {@code DocumentPageActivity} (EPUB/Word/HWP). All Android
 * plumbing (strings, toasts, dialogs' Context, receivers, permissions) goes
 * through {@link #ttsHostActivity()}; the methods below cover the
 * host-specific surface: preferences, dialog styling, the text source, paging,
 * and playback-adjacent UI.
 *
 * <p>Interface methods are public by definition, so hosts implement them as
 * small public wrappers over their existing package-private members.</p>
 */
interface TtsHost {

    /** The owning activity, used as Context and for UI-thread plumbing. */
    @NonNull
    AppCompatActivity ttsHostActivity();

    /** Shared preferences accessor; may be null very early in the lifecycle. */
    @Nullable
    PrefsManager ttsHostPrefs();

    /** Dialog styling for the TTS dialogs (theme snapshot, rows, panels). */
    @NonNull
    ReaderDialogStyleController ttsHostDialogStyler();

    /** dp-to-px for the host's display. */
    int ttsHostDpToPx(int dp);

    /** The text source to speak from, or null if nothing is loaded yet. */
    @Nullable
    TtsTextSource ttsTextSource();

    /** True once the host activity is destroyed; late callbacks must bail. */
    boolean isTtsHostDestroyed();

    /** Absolute path of the open file (drives resume state and the notification title). */
    @Nullable
    String ttsHostFilePath();

    /** A main-thread handler owned by the host. */
    @NonNull
    Handler ttsHostHandler();

    /**
     * True when {@link TtsTextSource#getTextContent()} holds the whole
     * document, which gates the cross-page prefetch. The lazily partitioned
     * large-text reader returns false.
     */
    boolean isTtsTextFullyResident();

    /**
     * True while the text is momentarily unreadable (e.g. a large-text
     * partition switch is in progress). The controller shows a waiting toast
     * and retries instead of reading a half-swapped buffer.
     */
    boolean isTtsTextTemporarilyUnavailable();

    /** 1-based page number currently displayed. */
    int ttsDisplayedCurrentPageNumber();

    /** Total displayed page count (at least 1). */
    int ttsDisplayedTotalPageCount();

    /**
     * Char offset of the top of the current view, in the host's own position
     * semantics (may differ from the raw text-source value, e.g. the text
     * reader's locator).
     */
    int ttsCurrentCharPosition();

    /** Turn the page by the given signed direction, as read-aloud navigation. */
    void ttsHostPageBy(int direction);

    /** Scroll/turn so the given absolute char position is at the top. Must not touch the engine queue. */
    void ttsJumpToAbsoluteCharPosition(int charPosition);

    /** Position restore for resume-from-saved-state, with the saved display page as a hint. */
    void ttsJumpToAbsoluteCharPosition(int charPosition, int displayPage, int totalPages);

    /** Refresh any always-on playback UI the host shows (no-op if it has none). */
    void ttsUpdateFloatingCard();

    /** Stop any competing auto page-turn feature before read-aloud starts (no-op if none). */
    void ttsStopAutoPageTurn();

    /** Route a remote command (notification/media button) to the host's controller. */
    void ttsHandlePlaybackCommand(@NonNull String action);

    /**
     * Which activity the playback notification's content tap should open:
     * {@link TtsPlaybackService#HOST_READER},
     * {@link TtsPlaybackService#HOST_DOCUMENT}, or
     * {@link TtsPlaybackService#HOST_PDF}.
     */
    @NonNull
    String ttsHostKind();
}
