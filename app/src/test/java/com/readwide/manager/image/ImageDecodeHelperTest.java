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
    public void archivePreviewProfileIsDenserThanOrdinaryPreview() {
        int ordinary = ImageDecodeHelper.chooseBitmapSampleSize(
                5_000, 7_500,
                1_080 * 2, 1_920 * 2,
                24_000_000L,
                24_000_000L,
                true);
        int archive = ImageDecodeHelper.chooseBitmapSampleSize(
                5_000, 7_500,
                1_080 * ImageDecodeHelper.ARCHIVE_PREVIEW_DISPLAY_SCALE,
                1_920 * ImageDecodeHelper.ARCHIVE_PREVIEW_DISPLAY_SCALE,
                ImageDecodeHelper.ARCHIVE_PREVIEW_MAX_BITMAP_PIXELS,
                ImageDecodeHelper.ARCHIVE_PREVIEW_MAX_BITMAP_PIXELS,
                true);

        assertEquals(4, ordinary);
        assertEquals(2, archive);
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
    public void animatedCandidatesRequireContentClassificationOnSupportedAndroid() {
        assertTrue(ImageDecodeHelper.shouldInspectAnimatedCandidate("page.gif", 28));
        assertTrue(ImageDecodeHelper.shouldInspectAnimatedCandidate("page.WEBP", 35));
        assertFalse(ImageDecodeHelper.shouldInspectAnimatedCandidate("page.jpg", 35));
        assertFalse(ImageDecodeHelper.shouldInspectAnimatedCandidate("page.gif", 27));
    }

    @Test
    public void staticWebpCandidateContinuesIntoBitmapPrefetch() {
        assertFalse(ImageDecodeHelper.shouldSkipPrefetchAfterDrawableClassification(false));
        assertTrue(ImageDecodeHelper.shouldSkipPrefetchAfterDrawableClassification(true));
    }
}
