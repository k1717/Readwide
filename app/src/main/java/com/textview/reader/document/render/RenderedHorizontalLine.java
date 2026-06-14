package com.textview.reader.document.render;

/**
 * A horizontal rule derived from a VML {@code v:line} shape (or a similar
 * full-width separator). Geometry is expressed in points relative to the page
 * so the renderer can reproduce the line's stroke weight and horizontal extent
 * without absolute positioning: the reflow layout keeps the line in reading
 * order but sizes its width and side margins from the original coordinates.
 *
 * <p>All fields are optional hints. When {@link #pageWidthPt} and the extent are
 * known, the renderer can compute the line width as a percentage of the content
 * column and offset it with proportional left/right margins; when they are
 * unknown it falls back to a full-width rule of the given (or default) weight.
 */
public final class RenderedHorizontalLine {
    /** Stroke thickness in points; {@code <= 0} means use the renderer default. */
    public final float thicknessPt;
    /** Left endpoint X in points from the page left edge, or {@code < 0} if unknown. */
    public final float leftPt;
    /** Right endpoint X in points from the page left edge, or {@code < 0} if unknown. */
    public final float rightPt;
    /** Page width in points, or {@code <= 0} if unknown. */
    public final float pageWidthPt;
    /** Optional CSS color (e.g. "#000000"); {@code null} means renderer default. */
    public final String color;

    public RenderedHorizontalLine(float thicknessPt, float leftPt, float rightPt,
                                  float pageWidthPt, String color) {
        this.thicknessPt = thicknessPt;
        this.leftPt = leftPt;
        this.rightPt = rightPt;
        this.pageWidthPt = pageWidthPt;
        this.color = color;
    }

    public boolean hasExtent() {
        return leftPt >= 0 && rightPt > leftPt && pageWidthPt > 0;
    }

    public boolean hasThickness() {
        return thicknessPt > 0;
    }
}
