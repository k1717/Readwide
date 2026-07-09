package com.readwide.manager.util;

import android.graphics.RectF;

import com.tom_roush.pdfbox.text.TextPosition;

/**
 * Single source of the per-glyph highlight box used by both the PDF text
 * search engine and the read-aloud glyph extractor. The two previously carried
 * byte-identical private copies; keeping the formula here guarantees search
 * highlights and read-aloud highlights can never disagree about glyph bounds.
 */
public final class PdfGlyphBoxMath {

    private PdfGlyphBoxMath() {
    }

    /**
     * Box for one glyph in PDF point space with a top-left origin.
     * getHeightDir is roughly the cap height, which alone looks like a thin
     * band; pad up past the ascenders (0.32h) and down past the baseline
     * (0.42h) so a highlight covers the full line including descenders.
     */
    public static RectF glyphBox(TextPosition tp) {
        float left = tp.getXDirAdj();
        float width = tp.getWidthDirAdj();
        float h = tp.getHeightDir();
        float top = tp.getYDirAdj() - h - h * 0.32f;
        float bottom = tp.getYDirAdj() + h * 0.42f;
        return new RectF(left, top, left + width, bottom);
    }
}
