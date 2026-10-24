package com.readwide.manager.util;

/** EPUB-only preference values; independent of TXT's physical alignment. */
public final class EpubTextAlignment {
    public static final int BOOK_DEFAULT = 0;
    public static final int START = 1;
    public static final int JUSTIFY = 2;

    private EpubTextAlignment() {}

    public static int normalize(int value) {
        return value == START || value == JUSTIFY ? value : BOOK_DEFAULT;
    }
}
