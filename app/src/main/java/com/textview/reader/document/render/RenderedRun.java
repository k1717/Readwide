package com.textview.reader.document.render;

/** A text run with style and optional plain-text anchor range. */
public final class RenderedRun {
    public final String text;
    public final TextStyle style;
    public final int anchorStart;
    public final int anchorEnd;
    public final String linkHref;
    public final String elementId;

    public RenderedRun(String text, TextStyle style, int anchorStart, int anchorEnd) {
        this(text, style, anchorStart, anchorEnd, null, null);
    }

    private RenderedRun(String text, TextStyle style, int anchorStart, int anchorEnd, String linkHref, String elementId) {
        this.text = text != null ? text : "";
        this.style = style != null ? style : TextStyle.plain();
        this.anchorStart = anchorStart;
        this.anchorEnd = anchorEnd;
        this.linkHref = emptyToNull(linkHref);
        this.elementId = safeElementId(elementId);
    }

    public static RenderedRun text(String text) {
        return new RenderedRun(text, TextStyle.plain(), -1, -1);
    }

    public static RenderedRun text(String text, TextStyle style) {
        return new RenderedRun(text, style, -1, -1);
    }

    public static RenderedRun link(String text, TextStyle style, String href, String id) {
        return new RenderedRun(text, style, -1, -1, href, id);
    }

    public boolean hasAnchorRange() {
        return anchorStart >= 0 && anchorEnd >= anchorStart;
    }

    public boolean hasLink() {
        return linkHref != null;
    }

    public boolean hasElementId() {
        return elementId != null;
    }

    private static String emptyToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static String safeElementId(String v) {
        if (v == null) return null;
        String trimmed = v.trim();
        if (trimmed.isEmpty() || trimmed.length() > 96) return null;
        return trimmed.replaceAll("[^A-Za-z0-9_:.-]", "-");
    }
}
