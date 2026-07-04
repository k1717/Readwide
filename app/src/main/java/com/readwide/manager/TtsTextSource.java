package com.readwide.manager;

import androidx.annotation.Nullable;

/**
 * The text surface read-aloud speaks from. {@link ReaderTtsController} talks to
 * this instead of a concrete view/activity so the same playback controller can
 * drive the plain-text reader ({@code CustomReaderView}, which already had all
 * of these methods) and the WebView document viewer
 * ({@code DocumentTtsTextSource} over {@code DocumentPageActivity} pages).
 *
 * <p>Character positions are absolute offsets into {@link #getTextContent()}.
 * A "visible page" is the half-open range
 * {@code [getCurrentCharPosition(), getCharPositionAfterCurrentVisibleContent())};
 * the controller segments that range into utterances and, when the host says
 * the text is fully resident, prefetches past its end across the page seam.</p>
 */
public interface TtsTextSource {

    /** The full reading text this source currently holds ({@code null}/empty = nothing to read). */
    @Nullable
    String getTextContent();

    /** Absolute char offset where the currently displayed content starts. */
    int getCurrentCharPosition();

    /** Absolute char offset just past the currently displayed content. */
    int getCharPositionAfterCurrentVisibleContent();

    /**
     * Highlight the utterance being spoken, if this source can render one.
     * Sources without a highlight surface (e.g. the WebView document viewer's
     * first version) may no-op.
     */
    void setTtsHighlightRange(int startChar, int endChar);

    /** Remove any read-aloud highlight. Must be safe to call when none is set. */
    void clearTtsHighlight();
}
