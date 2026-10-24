package com.readwide.manager.util;

/** Display-only vertical progress within one rendered EPUB section. */
public final class EpubSectionProgressMath {
    private EpubSectionProgressMath() {}

    /**
     * Returns 0..100, or -1 when the metrics are unavailable or no scrolling is
     * needed. The caller supplies metrics from the same ready WebView; this
     * does not calculate pages, change scrolling or define a saved position.
     *
     * <p>The native lower edge is authoritative because CSS content height may
     * lose fractional pixels when converted to the WebView's native range.
     * A positive offset at that edge is 100%; while native downward scrolling
     * remains possible, the display never reaches 100%.</p>
     */
    public static int percent(int contentHeightCss, float scale, int viewportPx,
                              int scrollY, boolean canScrollDown) {
        if (contentHeightCss <= 0 || viewportPx <= 0 || scale <= 0f
                || Float.isNaN(scale) || Float.isInfinite(scale)) {
            return -1;
        }
        // Match the existing native fast-scroll range. Converting before the
        // multiplication avoids int/float overflow; Math.round saturates at
        // Long.MAX_VALUE for an exceptionally large finite scaled range.
        long range = Math.round(contentHeightCss * (double) scale);
        if (range <= viewportPx) return -1;
        long maxOffset = range - viewportPx;
        long offset = Math.max(0L, Math.min(maxOffset, (long) scrollY));
        if (offset == 0L) return 0;
        if (!canScrollDown) return 100;
        // Positive truncation is floor. Avoid both premature rounding to 100
        // and overflow in a hypothetical integer offset * 100 calculation.
        int percent = (int) (offset * 100.0d / maxOffset);
        return Math.max(0, Math.min(99, percent));
    }
}
