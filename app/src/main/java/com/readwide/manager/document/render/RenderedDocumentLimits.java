package com.readwide.manager.document.render;

/** Safety limits for generated L3 preview HTML. */
public final class RenderedDocumentLimits {
    public static final int MAX_PAGES = 5000;
    public static final int MAX_BLOCKS = 250000;
    public static final int MAX_TEXT_CHARS = 8 * 1024 * 1024;

    private RenderedDocumentLimits() {}

    public static void validate(RenderedDocument document) {
        if (document == null) throw new IllegalArgumentException("document is null");
        if (document.pages.size() > MAX_PAGES) throw new IllegalArgumentException("too many rendered pages");
        if (document.plainText.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("rendered text too large");
        int blocks = 0;
        for (RenderedPage page : document.pages) {
            blocks += countBlocks(page);
            if (blocks > MAX_BLOCKS) throw new IllegalArgumentException("too many rendered blocks");
        }
    }

    private static int countBlocks(RenderedPage page) {
        if (page == null) return 0;
        int total = 0;
        for (RenderedBlock block : page.headerBlocks) total += countBlocks(block);
        for (RenderedBlock block : page.blocks) total += countBlocks(block);
        for (RenderedBlock block : page.footerBlocks) total += countBlocks(block);
        return total;
    }

    private static int countBlocks(RenderedBlock block) {
        if (block == null) return 0;
        int total = 1;
        if (block.type == RenderedBlock.Type.TABLE && block.table != null) {
            for (java.util.List<RenderedTableCell> row : block.table.rows) {
                for (RenderedTableCell cell : row) {
                    for (RenderedBlock nested : cell.blocks) total += countBlocks(nested);
                }
            }
        }
        return total;
    }
}
