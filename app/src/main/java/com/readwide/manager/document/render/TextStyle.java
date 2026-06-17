package com.readwide.manager.document.render;

/** Text-run styling for L3 content-fidelity preview. */
public final class TextStyle {
    public final String fontFamily;
    public final Float fontSizePt;
    public final Boolean bold;
    public final Boolean italic;
    public final Boolean underline;
    public final Boolean strike;
    public final String color;
    public final String backgroundColor;
    public final VerticalAlign verticalAlign;

    public enum VerticalAlign {
        BASELINE,
        SUPERSCRIPT,
        SUBSCRIPT
    }

    private TextStyle(Builder b) {
        this.fontFamily = b.fontFamily;
        this.fontSizePt = b.fontSizePt;
        this.bold = b.bold;
        this.italic = b.italic;
        this.underline = b.underline;
        this.strike = b.strike;
        this.color = b.color;
        this.backgroundColor = b.backgroundColor;
        this.verticalAlign = b.verticalAlign != null ? b.verticalAlign : VerticalAlign.BASELINE;
    }

    public static TextStyle plain() {
        return new Builder().build();
    }

    public boolean isEmpty() {
        return fontFamily == null && fontSizePt == null && bold == null && italic == null
                && underline == null && strike == null && color == null && backgroundColor == null
                && verticalAlign == VerticalAlign.BASELINE;
    }

    public static final class Builder {
        private String fontFamily;
        private Float fontSizePt;
        private Boolean bold;
        private Boolean italic;
        private Boolean underline;
        private Boolean strike;
        private String color;
        private String backgroundColor;
        private VerticalAlign verticalAlign;

        public Builder fontFamily(String v) { this.fontFamily = RenderStyleUtil.emptyToNull(v); return this; }
        public Builder fontSizePt(float v) { this.fontSizePt = v > 0 ? v : null; return this; }
        public Builder bold(boolean v) { this.bold = v; return this; }
        public Builder italic(boolean v) { this.italic = v; return this; }
        public Builder underline(boolean v) { this.underline = v; return this; }
        public Builder strike(boolean v) { this.strike = v; return this; }
        public Builder color(String v) { this.color = RenderStyleUtil.emptyToNull(v); return this; }
        public Builder backgroundColor(String v) { this.backgroundColor = RenderStyleUtil.emptyToNull(v); return this; }
        public Builder verticalAlign(VerticalAlign v) { this.verticalAlign = v; return this; }
        public TextStyle build() { return new TextStyle(this); }
    }
}
