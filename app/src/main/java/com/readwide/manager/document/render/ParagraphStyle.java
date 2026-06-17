package com.readwide.manager.document.render;

/** Paragraph styling shared by DOCX/HWPX/HWP L3 preview extractors. */
public final class ParagraphStyle {
    public final Alignment alignment;
    public final Float marginTopPt;
    public final Float marginBottomPt;
    public final Float marginLeftPt;
    public final Float marginRightPt;
    public final Float textIndentPt;
    public final Float lineHeightMultiplier;
    public final Float lineHeightPt;
    public final ListType listType;
    public final Integer listLevel;
    public final String listLabel;
    public final String backgroundColor;

    public enum Alignment {
        LEFT,
        CENTER,
        RIGHT,
        JUSTIFY
    }

    /**
     * Logical list marker type for content-fidelity rendering.
     * This is not intended to reproduce Word pagination or exact marker metrics.
     */
    public enum ListType {
        NONE,
        BULLET,
        ORDERED
    }

    private ParagraphStyle(Builder b) {
        this.alignment = b.alignment != null ? b.alignment : Alignment.LEFT;
        this.marginTopPt = b.marginTopPt;
        this.marginBottomPt = b.marginBottomPt;
        this.marginLeftPt = b.marginLeftPt;
        this.marginRightPt = b.marginRightPt;
        this.textIndentPt = b.textIndentPt;
        this.lineHeightMultiplier = b.lineHeightMultiplier;
        this.lineHeightPt = b.lineHeightPt;
        this.listType = b.listType != null ? b.listType : ListType.NONE;
        this.listLevel = b.listLevel;
        this.listLabel = RenderStyleUtil.emptyToNull(b.listLabel);
        this.backgroundColor = RenderStyleUtil.emptyToNull(b.backgroundColor);
    }

    public static ParagraphStyle normal() {
        return new Builder().build();
    }

    public boolean isDefault() {
        return alignment == Alignment.LEFT && marginTopPt == null && marginBottomPt == null
                && marginLeftPt == null && marginRightPt == null && textIndentPt == null
                && lineHeightMultiplier == null && lineHeightPt == null && listType == ListType.NONE
                && listLevel == null && listLabel == null && backgroundColor == null;
    }

    public boolean isListItem() {
        return listType != ListType.NONE && listLabel != null;
    }

    public static final class Builder {
        private Alignment alignment;
        private Float marginTopPt;
        private Float marginBottomPt;
        private Float marginLeftPt;
        private Float marginRightPt;
        private Float textIndentPt;
        private Float lineHeightMultiplier;
        private Float lineHeightPt;
        private ListType listType;
        private Integer listLevel;
        private String listLabel;
        private String backgroundColor;

        public Builder alignment(Alignment v) { this.alignment = v; return this; }
        public Builder marginTopPt(float v) { this.marginTopPt = v; return this; }
        public Builder marginBottomPt(float v) { this.marginBottomPt = v; return this; }
        public Builder marginLeftPt(float v) { this.marginLeftPt = v; return this; }
        public Builder marginRightPt(float v) { this.marginRightPt = v; return this; }
        public Builder textIndentPt(float v) { this.textIndentPt = v; return this; }
        public Builder lineHeightMultiplier(float v) {
            this.lineHeightMultiplier = v > 0 ? v : null;
            if (this.lineHeightMultiplier != null) this.lineHeightPt = null;
            return this;
        }
        public Builder lineHeightPt(float v) {
            this.lineHeightPt = v > 0 ? v : null;
            if (this.lineHeightPt != null) this.lineHeightMultiplier = null;
            return this;
        }
        public Builder list(ListType type, int level, String label) {
            this.listType = type != null ? type : ListType.NONE;
            this.listLevel = Math.max(0, level);
            this.listLabel = RenderStyleUtil.emptyToNull(label);
            return this;
        }
        public Builder backgroundColor(String v) { this.backgroundColor = RenderStyleUtil.emptyToNull(v); return this; }
        public ParagraphStyle build() { return new ParagraphStyle(this); }
    }
}
