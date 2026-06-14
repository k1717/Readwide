package com.textview.reader.document.render;

/**
 * Plain-text range that can be mapped back to rendered HTML for search and bookmarks.
 * This is format-neutral and must not imply exact office pagination.
 */
public final class TextAnchor {
    public final String id;
    public final int start;
    public final int end;
    public final int pageIndex;
    public final String preview;

    public TextAnchor(String id, int start, int end, int pageIndex, String preview) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id is required");
        if (start < 0 || end < start) throw new IllegalArgumentException("invalid anchor range");
        this.id = id;
        this.start = start;
        this.end = end;
        this.pageIndex = pageIndex;
        this.preview = preview != null ? preview : "";
    }
}
