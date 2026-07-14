package com.readwide.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PdfRenderSizeTest {
    @Test
    public void ordinaryBitmapWithinCapIsUnchanged() {
        PdfRenderSize.Dimensions size = PdfRenderSize.capToPixels(1200, 1800, 8_000_000L);

        assertEquals(1200, size.width);
        assertEquals(1800, size.height);
    }

    @Test
    public void largeBitmapPreservesAspectAndHonorsCap() {
        PdfRenderSize.Dimensions size = PdfRenderSize.capToPixels(12_000, 18_000, 8_000_000L);

        assertTrue(size.pixels() <= 8_000_000L);
        double ratio = size.width / (double) size.height;
        assertEquals(2d / 3d, ratio, 0.002d);
    }

    @Test
    public void ultraThinPageCannotEscapePixelCapAfterMinimumWidthRounding() {
        PdfRenderSize.Dimensions size = PdfRenderSize.capToPixels(1, 100_000_000, 8_000_000L);

        assertEquals(1, size.width);
        assertTrue(size.height <= 32_766);
        assertTrue(size.pixels() <= 8_000_000L);
    }

    @Test
    public void integerMaximumDimensionsRemainPositiveAndBounded() {
        PdfRenderSize.Dimensions size = PdfRenderSize.capToPixels(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 12_000_000L);

        assertTrue(size.width > 0);
        assertTrue(size.height > 0);
        assertTrue(size.width <= 32_766);
        assertTrue(size.height <= 32_766);
        assertTrue(size.pixels() <= 12_000_000L);
    }
}
