package com.textview.reader.document.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RenderedTable {
    public final List<List<RenderedTableCell>> rows;
    public final Float widthPercent;
    public final List<Float> columnWidthPercents;

    public RenderedTable(List<List<RenderedTableCell>> rows, Float widthPercent) {
        this(rows, widthPercent, null);
    }

    public RenderedTable(List<List<RenderedTableCell>> rows, Float widthPercent, List<Float> columnWidthPercents) {
        this.rows = immutableRows(rows);
        this.widthPercent = widthPercent;
        this.columnWidthPercents = immutableFloats(columnWidthPercents);
    }

    public static RenderedTable ofRows(List<List<RenderedTableCell>> rows) {
        return new RenderedTable(rows, null, null);
    }

    private static List<List<RenderedTableCell>> immutableRows(List<List<RenderedTableCell>> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        List<List<RenderedTableCell>> copy = new ArrayList<>();
        for (List<RenderedTableCell> row : src) {
            if (row == null) copy.add(Collections.<RenderedTableCell>emptyList());
            else copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<Float> immutableFloats(List<Float> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(src));
    }
}
