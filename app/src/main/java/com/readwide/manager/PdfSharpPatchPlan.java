package com.readwide.manager;

/** Pure sizing and reuse rules for optional viewport sharpening. */
final class PdfSharpPatchPlan {
    static final long MAX_PIXELS = 8_000_000L;

    final float leftPts;
    final float topPts;
    final float widthPts;
    final float heightPts;
    final int width;
    final int height;

    private PdfSharpPatchPlan(float left, float top, float widthPts, float heightPts,
                              int width, int height) {
        this.leftPts = left;
        this.topPts = top;
        this.widthPts = widthPts;
        this.heightPts = heightPts;
        this.width = width;
        this.height = height;
    }

    static boolean needsSharpen(float bitmapToScreenScale) {
        // At <= 1 each screen pixel already has at least one source pixel.
        return Float.isFinite(bitmapToScreenScale) && bitmapToScreenScale > 1f;
    }

    static PdfSharpPatchPlan create(int pageWidth, int pageHeight,
                                    float left, float top, float right, float bottom,
                                    float displayScale) {
        if (pageWidth <= 0 || pageHeight <= 0 || !validRect(left, top, right, bottom)
                || !Float.isFinite(displayScale) || displayScale <= 0f) return null;
        float widthPts = Math.max(1f, (right - left) * pageWidth);
        float heightPts = Math.max(1f, (bottom - top) * pageHeight);
        float scale = Math.max(0.2f, displayScale);
        PdfRenderSize.Dimensions size = PdfRenderSize.capToPixels(
                Math.max(1, Math.round(widthPts * scale)),
                Math.max(1, Math.round(heightPts * scale)), MAX_PIXELS);
        return new PdfSharpPatchPlan(left * pageWidth, top * pageHeight,
                widthPts, heightPts, size.width, size.height);
    }

    static boolean canReuse(int cachedWidth, int cachedHeight,
                            float cachedLeft, float cachedTop, float cachedRight, float cachedBottom,
                            float left, float top, float right, float bottom,
                            PdfSharpPatchPlan requested) {
        if (requested == null || cachedWidth <= 0 || cachedHeight <= 0
                || !validRect(cachedLeft, cachedTop, cachedRight, cachedBottom)
                || !validRect(left, top, right, bottom)
                || cachedLeft > left || cachedTop > top
                || cachedRight < right || cachedBottom < bottom) return false;
        // Compare actual pixel density on BOTH axes, not the requested zoom.
        // The pixel cap may have reduced a previous patch's effective density.
        double availableWidth = cachedWidth * (double) (right - left) / (cachedRight - cachedLeft);
        double availableHeight = cachedHeight * (double) (bottom - top) / (cachedBottom - cachedTop);
        return availableWidth + 0.001 >= requested.width
                && availableHeight + 0.001 >= requested.height;
    }

    private static boolean validRect(float left, float top, float right, float bottom) {
        return Float.isFinite(left) && Float.isFinite(top)
                && Float.isFinite(right) && Float.isFinite(bottom)
                && left >= 0f && top >= 0f && right <= 1f && bottom <= 1f
                && right > left && bottom > top;
    }
}
