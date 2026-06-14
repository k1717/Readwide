package com.textview.reader.document.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RenderedParagraph {
    public final ParagraphStyle style;
    public final List<RenderedRun> runs;

    public RenderedParagraph(ParagraphStyle style, List<RenderedRun> runs) {
        this.style = style != null ? style : ParagraphStyle.normal();
        this.runs = immutableCopy(runs);
    }

    public static RenderedParagraph of(RenderedRun... runs) {
        List<RenderedRun> list = new ArrayList<>();
        if (runs != null) {
            Collections.addAll(list, runs);
        }
        return new RenderedParagraph(ParagraphStyle.normal(), list);
    }

    public String plainText() {
        StringBuilder sb = new StringBuilder();
        for (RenderedRun run : runs) {
            if (run != null) sb.append(run.text);
        }
        return sb.toString();
    }

    private static <T> List<T> immutableCopy(List<T> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(src));
    }
}
