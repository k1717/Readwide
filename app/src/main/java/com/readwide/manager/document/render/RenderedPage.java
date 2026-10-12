package com.readwide.manager.document.render;

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
        this.widthPt = finite(b.widthPt) && b.widthPt >= 1f ? b.widthPt : 595f;
        this.heightPt = finite(b.heightPt) && b.heightPt >= 1f ? b.heightPt : 842f;
        float top = safeMargin(b.marginTopPt), right = safeMargin(b.marginRightPt);
        float bottom = safeMargin(b.marginBottomPt), left = safeMargin(b.marginLeftPt);
        // Invalid source margins must not collapse the content area or produce invalid CSS.
        if ((double) left + right >= widthPt) left = right = Math.min(54f, widthPt / 10f);
        if ((double) top + bottom >= heightPt) top = bottom = Math.min(54f, heightPt / 10f);
        this.marginTopPt = top;
        this.marginRightPt = right;
        this.marginBottomPt = bottom;
        this.marginLeftPt = left;
        this.headerBlocks = immutableCopy(b.headerBlocks);
        this.footerBlocks = immutableCopy(b.footerBlocks);
        this.blocks = immutableCopy(b.blocks);
    }

    public static Builder builder(int pageIndex) { return new Builder(pageIndex); }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static float safeMargin(float value) { return finite(value) && value >= 0 ? value : 54f; }

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
