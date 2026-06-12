package com.textview.reader.util;

public final class ImageSequenceNavigationMath {
    private ImageSequenceNavigationMath() {}

    public static int clampIndex(int index, int itemCount) {
        if (itemCount <= 0) return 0;
        return Math.max(0, Math.min(itemCount - 1, index));
    }

    public static int nextIndex(int currentIndex, int direction, int itemCount) {
        if (itemCount <= 0) return 0;
        int next = currentIndex + direction;
        if (next < 0 || next >= itemCount) return clampIndex(currentIndex, itemCount);
        return next;
    }

    public static int mirroredVisualDelta(int ltrVisualDelta, boolean mirrored) {
        if (ltrVisualDelta == 0) return 0;
        return mirrored ? -ltrVisualDelta : ltrVisualDelta;
    }

    /**
     * Returns the page delta for the image viewer's fixed 33:34:33 tap zones.
     * Left and right zones are visual zones; mirror mode flips their page-turn
     * meaning while leaving the center zone as chrome toggle/no page turn.
     */
    public static int visualTapZoneDelta(float normalizedX, boolean mirrored) {
        if (Float.isNaN(normalizedX)) return 0;
        if (normalizedX < 0.33f) return mirroredVisualDelta(-1, mirrored);
        if (normalizedX >= 0.67f) return mirroredVisualDelta(1, mirrored);
        return 0;
    }
}
