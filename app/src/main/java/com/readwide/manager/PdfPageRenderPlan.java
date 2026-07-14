package com.readwide.manager;

/**
 * Pure sizing plan shared by visible, neighbor-prefetch, and continuous PDF
 * page renders. Keeping fit/display/allocation math in one place prevents a
 * prefetched page from using different geometry than an on-demand render.
 */
final class PdfPageRenderPlan {
    private PdfPageRenderPlan() {}

    static Plan create(int pageWidthPts,
                       int pageHeightPts,
                       int viewportWidthPx,
                       int viewportHeightPx,
                       float zoom,
                       float supersample,
                       int horizontalReservePx,
                       int verticalReservePx,
                       boolean constrainByHeight,
                       long maxPixels) {
        int safePageWidth = Math.max(1, pageWidthPts);
        int safePageHeight = Math.max(1, pageHeightPts);
        int availableWidth = Math.max(1, viewportWidthPx - Math.max(0, horizontalReservePx));
        int availableHeight = Math.max(1, viewportHeightPx - Math.max(0, verticalReservePx));
        double safeZoom = positiveFiniteOr(zoom, 1d);
        double safeSupersample = positiveFiniteOr(supersample, 1d);

        double fitScale = availableWidth / (double) safePageWidth;
        if (constrainByHeight && viewportHeightPx > 0) {
            fitScale = Math.min(fitScale, availableHeight / (double) safePageHeight);
        }
        fitScale = Math.max(0.000001d, fitScale);

        int intendedDisplayWidth = positiveRoundedInt(
                safePageWidth * fitScale * safeZoom);
        int intendedDisplayHeight = positiveRoundedInt(
                safePageHeight * fitScale * safeZoom);

        double renderScale = Math.max(0.2d, fitScale * safeZoom * safeSupersample);
        int requestedBitmapWidth = positiveRoundedInt(safePageWidth * renderScale);
        int requestedBitmapHeight = positiveRoundedInt(safePageHeight * renderScale);
        PdfRenderSize.Dimensions capped = PdfRenderSize.capToPixels(
                requestedBitmapWidth, requestedBitmapHeight, maxPixels);

        return new Plan(fitScale,
                intendedDisplayWidth,
                intendedDisplayHeight,
                capped.width,
                capped.height);
    }

    private static double positiveFiniteOr(float value, double fallback) {
        return Float.isFinite(value) && value > 0f ? value : fallback;
    }

    private static int positiveRoundedInt(double value) {
        if (!Double.isFinite(value) || value <= 1d) return 1;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return Math.max(1, (int) Math.round(value));
    }

    static final class Plan {
        final double fitScale;
        final int intendedDisplayWidthPx;
        final int intendedDisplayHeightPx;
        final int bitmapWidthPx;
        final int bitmapHeightPx;

        Plan(double fitScale,
             int intendedDisplayWidthPx,
             int intendedDisplayHeightPx,
             int bitmapWidthPx,
             int bitmapHeightPx) {
            this.fitScale = fitScale;
            this.intendedDisplayWidthPx = intendedDisplayWidthPx;
            this.intendedDisplayHeightPx = intendedDisplayHeightPx;
            this.bitmapWidthPx = bitmapWidthPx;
            this.bitmapHeightPx = bitmapHeightPx;
        }
    }
}
