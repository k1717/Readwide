package com.readwide.manager.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageDecodeHelperTest {
    @Test public void drawableMemoryRetryReducesAllocationAndReturnsSuccess() throws Exception {
        java.util.List<Integer> scales = new java.util.ArrayList<>();
        String result = ImageDecodeHelper.decodeWithMemoryRetry(scale -> {
            scales.add(scale);
            if (scale < 4) throw new OutOfMemoryError("synthetic allocation failure");
            return "ready";
        });
        assertEquals("ready", result);
        assertEquals(java.util.Arrays.asList(1, 2, 4), scales);
    }

    @Test public void drawableMemoryRetryIsBounded() throws Exception {
        java.util.List<Integer> scales = new java.util.ArrayList<>();
        Object result = ImageDecodeHelper.decodeWithMemoryRetry(scale -> {
            scales.add(scale);
            throw new OutOfMemoryError("synthetic allocation failure");
        });
        org.junit.Assert.assertNull(result);
        assertEquals(java.util.Arrays.asList(1, 2, 4, 8, 16, 32, 64, 128), scales);
    }

    @Test public void drawableMemoryRetryDoesNotHideDecodeErrors() {
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> ImageDecodeHelper.decodeWithMemoryRetry(scale -> { throw new java.io.IOException("bad data"); }));
        org.junit.Assert.assertThrows(LinkageError.class,
                () -> ImageDecodeHelper.decodeWithMemoryRetry(scale -> { throw new LinkageError("missing decoder"); }));
    }

    @Test public void drawableMemoryRetryHonorsCancellationWithoutDecoding() throws Exception {
        Thread.currentThread().interrupt();
        try {
            org.junit.Assert.assertNull(ImageDecodeHelper.decodeWithMemoryRetry(scale -> {
                throw new AssertionError("cancelled request decoded");
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

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
