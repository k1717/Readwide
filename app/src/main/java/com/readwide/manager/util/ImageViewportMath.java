package com.readwide.manager.util;

/** Image placement inside the visible area; scale and paging rules are separate. */
public final class ImageViewportMath {
    private ImageViewportMath() {}

    /** Keep the screen's vertical center unless a panel would cover the image. */
    public static float centeredTop(float displayedHeight, int viewHeight, int topInset, int bottomInset) {
        if (viewHeight <= 0 || displayedHeight <= 0f
                || Float.isNaN(displayedHeight) || Float.isInfinite(displayedHeight)) return 0f;
        int top = Math.max(0, topInset);
        int bottom = Math.max(0, bottomInset);
        float contentHeight = (float) viewHeight - top - bottom;
        if (contentHeight <= 0f) return 0f;
        float remaining = contentHeight - displayedHeight;
        if (remaining <= 0f) return remaining * 0.5f;
        // ImageView already offsets its canvas by the top padding.
        float screenCenteredTop = (viewHeight - displayedHeight) * 0.5f - top;
        return Math.max(0f, Math.min(remaining, screenCenteredTop));
    }
}
