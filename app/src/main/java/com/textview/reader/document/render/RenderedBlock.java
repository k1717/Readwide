package com.textview.reader.document.render;

public final class RenderedBlock {
    public enum Type {
        PARAGRAPH,
        TABLE,
        IMAGE,
        HORIZONTAL_LINE,
        UNSUPPORTED_PLACEHOLDER
    }

    public final Type type;
    public final RenderedParagraph paragraph;
    public final RenderedTable table;
    public final RenderedImage image;
    public final RenderedHorizontalLine horizontalLine;
    public final String placeholderText;

    private RenderedBlock(Type type, RenderedParagraph paragraph, RenderedTable table,
                          RenderedImage image, RenderedHorizontalLine horizontalLine,
                          String placeholderText) {
        this.type = type;
        this.paragraph = paragraph;
        this.table = table;
        this.image = image;
        this.horizontalLine = horizontalLine;
        this.placeholderText = placeholderText != null ? placeholderText : "";
    }

    public static RenderedBlock paragraph(RenderedParagraph paragraph) {
        return new RenderedBlock(Type.PARAGRAPH, paragraph, null, null, null, null);
    }

    public static RenderedBlock table(RenderedTable table) {
        return new RenderedBlock(Type.TABLE, null, table, null, null, null);
    }

    public static RenderedBlock image(RenderedImage image) {
        return new RenderedBlock(Type.IMAGE, null, null, image, null, null);
    }

    public static RenderedBlock horizontalLine(RenderedHorizontalLine horizontalLine) {
        return new RenderedBlock(Type.HORIZONTAL_LINE, null, null, null, horizontalLine, null);
    }

    public static RenderedBlock unsupported(String text) {
        return new RenderedBlock(Type.UNSUPPORTED_PLACEHOLDER, null, null, null, null, text);
    }
}
