package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class PdfSpreadHighlightMathTest {

    private static final float EPSILON = 0.00001f;

    @Test
    public void mapsBothPagesAndPreservesGap() {
        PdfSpreadHighlightMath.Layout layout = new PdfSpreadHighlightMath.Layout(
                4, 5, 1220, 800,
                0, 0, 600, 800,
                620, 0, 600, 800);

        assertArrayEquals(new float[]{0f, 0f, 600f / 1220f, 1f},
                layout.map(4, 0f, 0f, 1f, 1f), EPSILON);
        assertArrayEquals(new float[]{620f / 1220f, 0f, 1f, 1f},
                layout.map(5, 0f, 0f, 1f, 1f), EPSILON);
    }

    @Test
    public void mapsMixedPageSizesWithVerticalCentering() {
        PdfSpreadHighlightMath.Layout layout = new PdfSpreadHighlightMath.Layout(
                10, 11, 1010, 1000,
                0, 0, 400, 1000,
                410, 350, 600, 300);

        assertArrayEquals(new float[]{410f / 1010f, 0.35f, 1f, 0.65f},
                layout.map(11, 0f, 0f, 1f, 1f), EPSILON);
        assertArrayEquals(new float[]{560f / 1010f, 0.425f, 860f / 1010f, 0.575f},
                layout.map(11, 0.25f, 0.25f, 0.75f, 0.75f), EPSILON);
    }

    @Test
    public void usesFinalPostPixelCapGeometryWithoutRecomputingScale() {
        // Final renderer values after a large spread was shrunk under 12M px.
        PdfSpreadHighlightMath.Layout layout = new PdfSpreadHighlightMath.Layout(
                20, 21, 4536, 2400,
                0, 0, 1800, 2400,
                1836, 400, 2700, 1600);

        assertArrayEquals(new float[]{1836f / 4536f, 400f / 2400f, 1f, 2000f / 2400f},
                layout.map(21, 0f, 0f, 1f, 1f), EPSILON);
    }

    @Test
    public void rejectsUnknownPageAndInvalidGeometry() {
        PdfSpreadHighlightMath.Layout valid = new PdfSpreadHighlightMath.Layout(
                0, 1, 100, 100,
                0, 0, 45, 100,
                55, 0, 45, 100);
        assertNull(valid.map(2, 0f, 0f, 1f, 1f));

        PdfSpreadHighlightMath.Layout invalidBitmap = new PdfSpreadHighlightMath.Layout(
                0, 1, 0, 100,
                0, 0, 45, 100,
                55, 0, 45, 100);
        assertNull(invalidBitmap.map(0, 0f, 0f, 1f, 1f));

        PdfSpreadHighlightMath.Layout invalidPage = new PdfSpreadHighlightMath.Layout(
                0, 1, 100, 100,
                0, 0, 0, 100,
                55, 0, 45, 100);
        assertNull(invalidPage.map(0, 0f, 0f, 1f, 1f));

        PdfSpreadHighlightMath.Layout outsideBitmap = new PdfSpreadHighlightMath.Layout(
                0, 1, 100, 100,
                0, 0, 45, 100,
                60, 0, 45, 100);
        assertNull(outsideBitmap.map(1, 0f, 0f, 1f, 1f));
    }
}
