package com.readwide.manager;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the PDF read-aloud sentence highlight: merges a character
 * range's per-glyph boxes into one box per visual line. Separated so the
 * grouping logic is unit-testable off-device.
 */
final class PdfTtsHighlightMath {

    private PdfTtsHighlightMath() {
    }

    /**
     * Merges the glyph boxes for {@code [startChar, endChar)} into one rectangle
     * per visual line. A glyph starts a new line when its vertical center falls
     * outside the current line's vertical extent (robust for sorted reading
     * order; line breaks show as a vertical shift). Degenerate boxes (zero
     * width/height, e.g. line-separator placeholders) are skipped.
     */
    @NonNull
    static List<RectF> mergeCharBoxes(@NonNull List<RectF> charRects, int startChar, int endChar) {
        List<RectF> out = new ArrayList<>();
        int from = Math.max(0, startChar);
        int to = Math.min(charRects.size(), endChar);
        RectF line = null;
        for (int i = from; i < to; i++) {
            RectF r = charRects.get(i);
            if (r == null || r.width() <= 0f || r.height() <= 0f) continue;
            if (line == null) {
                line = new RectF(r);
                continue;
            }
            float centerY = (r.top + r.bottom) / 2f;
            if (centerY < line.top || centerY > line.bottom) {
                out.add(line);
                line = new RectF(r);
            } else {
                line.union(r);
            }
        }
        if (line != null) out.add(line);
        return out;
    }

    /** Normalizes page-point rects to [0..1] against the page dimensions. */
    @NonNull
    static List<RectF> normalize(@NonNull List<RectF> ptsRects, float wPts, float hPts) {
        List<RectF> out = new ArrayList<>(ptsRects.size());
        if (wPts <= 0f || hPts <= 0f) return out;
        for (RectF r : ptsRects) {
            out.add(new RectF(r.left / wPts, r.top / hPts, r.right / wPts, r.bottom / hPts));
        }
        return out;
    }
}
