package com.textview.reader.document.render;

/** Image reference for fixed HTML output. The src should already point to a local WebView-safe resource. */
public final class RenderedImage {
    public final String src;
    public final String altText;
    public final Float widthPt;
    public final Float heightPt;
    public final boolean downgradedFromFloating;

    public RenderedImage(String src, String altText, Float widthPt, Float heightPt, boolean downgradedFromFloating) {
        this.src = src != null ? src : "";
        this.altText = altText != null ? altText : "";
        this.widthPt = widthPt;
        this.heightPt = heightPt;
        this.downgradedFromFloating = downgradedFromFloating;
    }
}
