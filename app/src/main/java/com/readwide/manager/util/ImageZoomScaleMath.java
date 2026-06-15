package com.readwide.manager.util;

/** Scale rules shared by the image viewer gesture code and regression tests. */
public final class ImageZoomScaleMath {
    private ImageZoomScaleMath() {}

    /**
     * The pinch-out floor is normally the first visible state.  When a small
     * image opens upscaled above its original 1:1 size, allow the user to shrink
     * back down to 1:1, but never smaller.
     */
    public static float minimumPinchScale(float firstStateScale) {
        if (firstStateScale <= 0f || Float.isNaN(firstStateScale) || Float.isInfinite(firstStateScale)) {
            return 1f;
        }
        return Math.min(firstStateScale, 1f);
    }

    /**
     * Double tap has only two states: a fixed zoom-in target and the initial
     * visible state. Original 1:1 is intentionally reserved for pinch-zoom only
     * when the first visible state is upscaled above original size.
     */
    public static float doubleTapTargetScale(float currentScale,
                                             float firstStateScale,
                                             float zoomInTargetScale,
                                             float epsilon) {
        float safeFirst = sanitizePositive(firstStateScale, 1f);
        float safeTarget = Math.max(safeFirst, sanitizePositive(zoomInTargetScale, safeFirst * 2f));
        float safeCurrent = sanitizePositive(currentScale, safeFirst);
        float safeEpsilon = Math.max(0f, sanitizeFinite(epsilon, 0f));
        if (safeCurrent > safeFirst + safeEpsilon) {
            return safeFirst;
        }
        return safeTarget;
    }

    private static float sanitizePositive(float value, float fallback) {
        if (value <= 0f || Float.isNaN(value) || Float.isInfinite(value)) return fallback;
        return value;
    }

    private static float sanitizeFinite(float value, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return fallback;
        return value;
    }
}
