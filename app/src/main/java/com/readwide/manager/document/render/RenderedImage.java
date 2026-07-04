package com.readwide.manager.document.render;

/** Image reference for fixed HTML output. The src should already point to a local WebView-safe resource. */
public final class RenderedImage {
    public final String src;
    public final String altText;
    public final Float widthPt;
    public final Float heightPt;
    public final boolean downgradedFromFloating;
    /**
     * True when the source picture existed but could not be rendered (e.g. an
     * HWP WMF/EMF/OLE vector image with no raster form). Rather than dropping
     * it silently, the renderer draws a language-neutral placeholder frame at
     * the picture's authored size so the reader sees that an image belongs
     * there. Placeholder instances have an empty {@link #src}.
     */
    public final boolean unrenderablePlaceholder;

    public RenderedImage(String src, String altText, Float widthPt, Float heightPt, boolean downgradedFromFloating) {
        this(src, altText, widthPt, heightPt, downgradedFromFloating, false);
    }

    public RenderedImage(String src, String altText, Float widthPt, Float heightPt,
                         boolean downgradedFromFloating, boolean unrenderablePlaceholder) {
        this.src = src != null ? src : "";
        this.altText = altText != null ? altText : "";
        this.widthPt = widthPt;
        this.heightPt = heightPt;
        this.downgradedFromFloating = downgradedFromFloating;
        this.unrenderablePlaceholder = unrenderablePlaceholder;
    }

    /** A placeholder for a picture that exists but has no WebView-renderable raster form. */
    public static RenderedImage placeholder(Float widthPt, Float heightPt) {
        return new RenderedImage("", "", widthPt, heightPt, false, true);
    }
}
