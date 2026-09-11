package com.readwide.manager;

import java.util.List;
import java.util.function.Function;

/** Checks glyph-run text in-place; matching lengths alone do not prove alignment. */
final class PdfGlyphText {
    private PdfGlyphText() { }

    static <T> boolean matchesRun(String run, List<T> glyphs, Function<T, String> unicode) {
        if (run == null || glyphs == null) return false;
        int offset = 0;
        for (T glyph : glyphs) {
            if (glyph == null) return false;
            String text = unicode.apply(glyph);
            if (text == null || text.isEmpty()) continue;
            if (text.length() > run.length() - offset
                    || !run.regionMatches(offset, text, 0, text.length())) return false;
            offset += text.length();
        }
        return offset == run.length();
    }
}
