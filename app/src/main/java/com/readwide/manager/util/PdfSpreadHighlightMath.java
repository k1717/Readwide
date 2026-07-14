package com.readwide.manager.util;

/**
 * Pure coordinate conversion for PDF search/read-aloud overlays on a
 * two-page composite bitmap. It deliberately accepts the renderer's final
 * post-cap pixel geometry: recomputing from PDF point sizes would lose rounding,
 * the inter-page gap, vertical centering, and any memory-cap shrink.
 */
public final class PdfSpreadHighlightMath {

    private PdfSpreadHighlightMath() {
    }

    /** Immutable placement of both source pages inside one composite bitmap. */
    public static final class Layout {
        public final int leftPageIndex;
        public final int rightPageIndex;
        public final int bitmapWidth;
        public final int bitmapHeight;
        public final int leftX;
        public final int leftY;
        public final int leftWidth;
        public final int leftHeight;
        public final int rightX;
        public final int rightY;
        public final int rightWidth;
        public final int rightHeight;

        public Layout(int leftPageIndex, int rightPageIndex,
                      int bitmapWidth, int bitmapHeight,
                      int leftX, int leftY, int leftWidth, int leftHeight,
                      int rightX, int rightY, int rightWidth, int rightHeight) {
            this.leftPageIndex = leftPageIndex;
            this.rightPageIndex = rightPageIndex;
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
            this.leftX = leftX;
            this.leftY = leftY;
            this.leftWidth = leftWidth;
            this.leftHeight = leftHeight;
            this.rightX = rightX;
            this.rightY = rightY;
            this.rightWidth = rightWidth;
            this.rightHeight = rightHeight;
        }

        /**
         * Maps page-normalized [left, top, right, bottom] coordinates into
         * composite-normalized coordinates. Returns {@code null} for a page not
         * represented by this layout or for invalid bitmap/page dimensions.
         * Values are not clamped: a glyph box extending slightly beyond a page
         * edge retains the same geometry and is clipped naturally by the view.
         */
        public float[] map(int pageIndex, float left, float top, float right, float bottom) {
            if (bitmapWidth <= 0 || bitmapHeight <= 0 || leftPageIndex == rightPageIndex) {
                return null;
            }
            final int x;
            final int y;
            final int width;
            final int height;
            if (pageIndex == leftPageIndex) {
                x = leftX;
                y = leftY;
                width = leftWidth;
                height = leftHeight;
            } else if (pageIndex == rightPageIndex) {
                x = rightX;
                y = rightY;
                width = rightWidth;
                height = rightHeight;
            } else {
                return null;
            }
            if (width <= 0 || height <= 0 || x < 0 || y < 0
                    || (long) x + width > bitmapWidth
                    || (long) y + height > bitmapHeight) {
                return null;
            }
            return new float[]{
                    (x + left * width) / bitmapWidth,
                    (y + top * height) / bitmapHeight,
                    (x + right * width) / bitmapWidth,
                    (y + bottom * height) / bitmapHeight
            };
        }
    }
}
