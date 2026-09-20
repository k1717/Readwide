package com.readwide.manager;

/** Geometry shared by PDF zoom priority and edge-swipe admission. */
final class PdfPageGestureMath {
    private PdfPageGestureMath() {}

    static boolean isZoomed(float scale, float fitScale) {
        return fitScale > 0f && scale > fitScale * 1.01f;
    }

    static boolean atLeftEdge(float left) {
        return left >= -1f;
    }

    static boolean atRightEdge(float left, float drawnWidth, int viewportWidth) {
        return left + drawnWidth <= viewportWidth + 1f;
    }

    static int pageSwipeDirection(float dx, float dy, int viewportWidth,
                                  boolean startedAtLeft, boolean startedAtRight,
                                  float left, float drawnWidth) {
        if (viewportWidth <= 0 || drawnWidth <= 0f
                || !Float.isFinite(left) || !Float.isFinite(drawnWidth)) return 0;
        // Preserve the existing swipe distance and horizontal-dominance thresholds.
        if (!(Math.abs(dx) > viewportWidth * 0.18f
                && Math.abs(dx) > Math.abs(dy) * 1.3f)) return 0;
        if (dx < 0 && startedAtRight && atRightEdge(left, drawnWidth, viewportWidth)) return 1;
        if (dx > 0 && startedAtLeft && atLeftEdge(left)) return -1;
        return 0;
    }
}
