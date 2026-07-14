package com.readwide.manager;

/** Overflow-safe bitmap sizing shared by every PDF render path. */
final class PdfRenderSize {
    // Avoid pathological one-dimensional allocations even when the total pixel
    // count is small enough. This is also below common Canvas/GPU dimension limits.
    private static final int MAX_BITMAP_DIMENSION = 32_766;

    private PdfRenderSize() {}

    static Dimensions capToPixels(int requestedWidth,
                                  int requestedHeight,
                                  long maxPixels) {
        long width = Math.max(1L, requestedWidth);
        long height = Math.max(1L, requestedHeight);
        long pixelLimit = Math.max(1L, maxPixels);

        double scale = 1d;
        double pixels = (double) width * (double) height;
        if (pixels > pixelLimit) {
            scale = Math.min(scale, Math.sqrt(pixelLimit / pixels));
        }
        if (width > MAX_BITMAP_DIMENSION) {
            scale = Math.min(scale, MAX_BITMAP_DIMENSION / (double) width);
        }
        if (height > MAX_BITMAP_DIMENSION) {
            scale = Math.min(scale, MAX_BITMAP_DIMENSION / (double) height);
        }

        int cappedWidth = boundedFloor(width * scale);
        int cappedHeight = boundedFloor(height * scale);
        long cappedPixels = (long) cappedWidth * (long) cappedHeight;
        if (cappedPixels > pixelLimit) {
            // max(1, floor(...)) can exceed the cap for ultra-thin pages where
            // one scaled dimension falls below one pixel. Bound the other axis.
            if (cappedWidth >= cappedHeight) {
                cappedWidth = (int) Math.max(1L,
                        Math.min(cappedWidth, pixelLimit / cappedHeight));
            } else {
                cappedHeight = (int) Math.max(1L,
                        Math.min(cappedHeight, pixelLimit / cappedWidth));
            }
        }
        return new Dimensions(cappedWidth, cappedHeight);
    }

    private static int boundedFloor(double value) {
        if (!Double.isFinite(value) || value <= 1d) return 1;
        return Math.max(1, Math.min(MAX_BITMAP_DIMENSION, (int) Math.floor(value)));
    }

    static final class Dimensions {
        final int width;
        final int height;

        Dimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }

        long pixels() {
            return (long) width * (long) height;
        }
    }
}
