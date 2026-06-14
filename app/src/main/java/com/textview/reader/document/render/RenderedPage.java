package com.textview.reader.document.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RenderedPage {
    public final int pageIndex;
    public final float widthPt;
    public final float heightPt;
    public final float marginTopPt;
    public final float marginRightPt;
    public final float marginBottomPt;
    public final float marginLeftPt;
    public final List<RenderedBlock> headerBlocks;
    public final List<RenderedBlock> footerBlocks;
    public final List<RenderedBlock> blocks;

    private RenderedPage(Builder b) {
        this.pageIndex = b.pageIndex;
        this.widthPt = b.widthPt > 0 ? b.widthPt : 595f;
        this.heightPt = b.heightPt > 0 ? b.heightPt : 842f;
        this.marginTopPt = b.marginTopPt >= 0 ? b.marginTopPt : 54f;
        this.marginRightPt = b.marginRightPt >= 0 ? b.marginRightPt : 54f;
        this.marginBottomPt = b.marginBottomPt >= 0 ? b.marginBottomPt : 54f;
        this.marginLeftPt = b.marginLeftPt >= 0 ? b.marginLeftPt : 54f;
        this.headerBlocks = immutableCopy(b.headerBlocks);
        this.footerBlocks = immutableCopy(b.footerBlocks);
        this.blocks = immutableCopy(b.blocks);
    }

    public static Builder builder(int pageIndex) { return new Builder(pageIndex); }

    public static final class Builder {
        private final int pageIndex;
        private float widthPt = 595f;
        private float heightPt = 842f;
        private float marginTopPt = 54f;
        private float marginRightPt = 54f;
        private float marginBottomPt = 54f;
        private float marginLeftPt = 54f;
        private final List<RenderedBlock> headerBlocks = new ArrayList<>();
        private final List<RenderedBlock> footerBlocks = new ArrayList<>();
        private final List<RenderedBlock> blocks = new ArrayList<>();

        private Builder(int pageIndex) { this.pageIndex = Math.max(0, pageIndex); }
        public Builder pageSizePt(float width, float height) { this.widthPt = width; this.heightPt = height; return this; }
        public Builder marginsPt(float top, float right, float bottom, float left) {
            this.marginTopPt = top; this.marginRightPt = right; this.marginBottomPt = bottom; this.marginLeftPt = left; return this;
        }
        public Builder addHeaderBlock(RenderedBlock block) { if (block != null) headerBlocks.add(block); return this; }
        public Builder addFooterBlock(RenderedBlock block) { if (block != null) footerBlocks.add(block); return this; }
        public Builder addBlock(RenderedBlock block) { if (block != null) blocks.add(block); return this; }
        public RenderedPage build() { return new RenderedPage(this); }
    }

    private static <T> List<T> immutableCopy(List<T> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(src));
    }
}
