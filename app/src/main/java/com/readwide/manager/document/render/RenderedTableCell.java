package com.readwide.manager.document.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RenderedTableCell {
    public final List<RenderedBlock> blocks;
    public final int colSpan;
    public final int rowSpan;
    public final String borderColor;
    public final Boolean borderVisible;
    // Per-edge visibility. When any of these is non-null the renderer emits
    // explicit per-side borders (border-top/right/bottom/left) instead of the
    // all-or-nothing borderVisible box. Lets partially-ruled cells (e.g. Korean
    // form tables that draw only a top/bottom rule) render faithfully.
    public final Boolean borderTop;
    public final Boolean borderRight;
    public final Boolean borderBottom;
    public final Boolean borderLeft;
    public final String backgroundColor;
    public final Float widthPercent;
    public final Float minHeightPt;
    public final Float paddingTopPt;
    public final Float paddingRightPt;
    public final Float paddingBottomPt;
    public final Float paddingLeftPt;

    private RenderedTableCell(Builder b) {
        this.blocks = immutableCopy(b.blocks);
        this.colSpan = Math.max(1, b.colSpan);
        this.rowSpan = Math.max(1, b.rowSpan);
        this.borderColor = b.borderColor;
        this.borderVisible = b.borderVisible;
        this.borderTop = b.borderTop;
        this.borderRight = b.borderRight;
        this.borderBottom = b.borderBottom;
        this.borderLeft = b.borderLeft;
        this.backgroundColor = b.backgroundColor;
        this.widthPercent = b.widthPercent;
        this.minHeightPt = b.minHeightPt;
        this.paddingTopPt = b.paddingTopPt;
        this.paddingRightPt = b.paddingRightPt;
        this.paddingBottomPt = b.paddingBottomPt;
        this.paddingLeftPt = b.paddingLeftPt;
    }

    public static Builder builder() { return new Builder(); }

    public static RenderedTableCell text(String text) {
        return builder().addBlock(RenderedBlock.paragraph(RenderedParagraph.of(RenderedRun.text(text)))).build();
    }

    public static final class Builder {
        private final List<RenderedBlock> blocks = new ArrayList<>();
        private int colSpan = 1;
        private int rowSpan = 1;
        private String borderColor;
        private Boolean borderVisible;
        private Boolean borderTop;
        private Boolean borderRight;
        private Boolean borderBottom;
        private Boolean borderLeft;
        private String backgroundColor;
        private Float widthPercent;
        private Float minHeightPt;
        private Float paddingTopPt;
        private Float paddingRightPt;
        private Float paddingBottomPt;
        private Float paddingLeftPt;

        public Builder addBlock(RenderedBlock block) { if (block != null) blocks.add(block); return this; }
        public Builder colSpan(int v) { this.colSpan = Math.max(1, v); return this; }
        public Builder rowSpan(int v) { this.rowSpan = Math.max(1, v); return this; }
        public Builder borderColor(String v) { this.borderColor = RenderStyleUtil.emptyToNull(v); return this; }
        public Builder borderVisible(boolean v) { this.borderVisible = v; return this; }
        public Builder borderEdges(Boolean top, Boolean right, Boolean bottom, Boolean left) {
            this.borderTop = top;
            this.borderRight = right;
            this.borderBottom = bottom;
            this.borderLeft = left;
            return this;
        }
        public Builder backgroundColor(String v) { this.backgroundColor = RenderStyleUtil.emptyToNull(v); return this; }
        public Builder widthPercent(float v) { this.widthPercent = v > 0 ? v : null; return this; }
        public Builder minHeightPt(float v) { this.minHeightPt = v > 0 ? v : null; return this; }
        public Builder paddingPt(Float top, Float right, Float bottom, Float left) {
            this.paddingTopPt = positiveOrNull(top);
            this.paddingRightPt = positiveOrNull(right);
            this.paddingBottomPt = positiveOrNull(bottom);
            this.paddingLeftPt = positiveOrNull(left);
            return this;
        }
        public RenderedTableCell build() { return new RenderedTableCell(this); }
        private static Float positiveOrNull(Float v) { return v != null && v >= 0f ? v : null; }
    }

    private static <T> List<T> immutableCopy(List<T> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(src));
    }
}
