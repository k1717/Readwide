package com.readwide.manager.document.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared content-fidelity document model. This model targets L3 preview, not L4 pagination parity. */
public final class RenderedDocument {
    public final String sourceFormat;
    public final String title;
    public final List<RenderedPage> pages;
    public final String plainText;
    public final List<TextAnchor> anchors;

    private RenderedDocument(Builder b) {
        this.sourceFormat = b.sourceFormat != null ? b.sourceFormat : "document";
        this.title = b.title != null ? b.title : "";
        this.pages = immutableCopy(b.pages);
        this.plainText = b.plainText != null ? b.plainText : collectPlainText(this.pages);
        this.anchors = immutableCopy(b.anchors);
        RenderedDocumentLimits.validate(this);
    }

    public static Builder builder(String sourceFormat) { return new Builder(sourceFormat); }

    public int pageCount() { return pages.size(); }

    public static final class Builder {
        private final String sourceFormat;
        private String title;
        private final List<RenderedPage> pages = new ArrayList<>();
        private String plainText;
        private final List<TextAnchor> anchors = new ArrayList<>();

        private Builder(String sourceFormat) { this.sourceFormat = sourceFormat; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder addPage(RenderedPage page) { if (page != null) pages.add(page); return this; }
        public Builder plainText(String plainText) { this.plainText = plainText; return this; }
        public Builder addAnchor(TextAnchor anchor) { if (anchor != null) anchors.add(anchor); return this; }
        public RenderedDocument build() { return new RenderedDocument(this); }
    }

    private static String collectPlainText(List<RenderedPage> pages) {
        StringBuilder sb = new StringBuilder();
        if (pages == null) return "";
        for (RenderedPage page : pages) {
            if (page == null) continue;
            for (RenderedBlock block : page.headerBlocks) collectPlainText(block, sb);
            for (RenderedBlock block : page.blocks) collectPlainText(block, sb);
            for (RenderedBlock block : page.footerBlocks) collectPlainText(block, sb);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static void collectPlainText(RenderedBlock block, StringBuilder out) {
        if (block == null) return;
        if (block.type == RenderedBlock.Type.PARAGRAPH && block.paragraph != null) {
            String text = block.paragraph.plainText().trim();
            if (!text.isEmpty()) out.append(text).append('\n');
        } else if (block.type == RenderedBlock.Type.TABLE && block.table != null) {
            for (List<RenderedTableCell> row : block.table.rows) {
                for (RenderedTableCell cell : row) {
                    for (RenderedBlock nested : cell.blocks) collectPlainText(nested, out);
                }
            }
        } else if (block.type == RenderedBlock.Type.IMAGE && block.image != null && !block.image.altText.isEmpty()) {
            out.append(block.image.altText).append('\n');
        }
    }

    private static <T> List<T> immutableCopy(List<T> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(src));
    }
}
