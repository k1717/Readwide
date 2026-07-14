package com.readwide.manager.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageDecodeHelperTest {
    @Test
    public void prefetchProfileHonorsEightMillionPixelCap() {
        int sample = ImageDecodeHelper.chooseBitmapSampleSize(
                8_000, 8_000, 1_080, 1_920,
                0L,
                ImageDecodeHelper.PREFETCH_MAX_BITMAP_PIXELS,
                true);

        assertTrue(sample >= 4);
        assertTrue((long) (8_000 / sample) * (8_000 / sample)
                <= ImageDecodeHelper.PREFETCH_MAX_BITMAP_PIXELS);
    }

    @Test
    public void moderateComicPagePrefetchMatchesDisplayScale() {
        int sample = ImageDecodeHelper.chooseBitmapSampleSize(
                2_000, 3_000, 1_080, 1_920,
                0L,
                ImageDecodeHelper.PREFETCH_MAX_BITMAP_PIXELS,
                true);

        assertEquals(2, sample);
    }

    @Test
    public void detailDirectOriginalThresholdPreservesExistingFullQualityRule() {
        int sample = ImageDecodeHelper.chooseBitmapSampleSize(
                6_000, 6_000, 1_080, 1_920, 48_000_000L, 48_000_000L, false);

        assertEquals(1, sample);
    }

    @Test
    public void invalidBoundsRemainSafe() {
        assertEquals(1, ImageDecodeHelper.chooseBitmapSampleSize(
                0, 10, 1, 1, 0L, 4_000_000L, true));
    }

    @Test
    public void animatedCandidatesAreNotStoredAsStaticFullQualityPrefetch() {
        assertTrue(ImageDecodeHelper.shouldSkipAnimatedBitmapPrefetch("page.gif", 28));
        assertTrue(ImageDecodeHelper.shouldSkipAnimatedBitmapPrefetch("page.WEBP", 35));
        assertFalse(ImageDecodeHelper.shouldSkipAnimatedBitmapPrefetch("page.jpg", 35));
        assertFalse(ImageDecodeHelper.shouldSkipAnimatedBitmapPrefetch("page.gif", 27));
    }
}
