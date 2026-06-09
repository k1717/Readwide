package com.textview.reader.model;

/**
 * A small decoded section of a large text file.
 * Keeping text in chunks prevents Android from creating one enormous TextView layout.
 */
public class TextChunk {
    private final int index;
    private final int startChar;
    private final String text;

    public TextChunk(int index, int startChar, String text) {
        this.index = index;
        this.startChar = startChar;
        this.text = text;
    }

    public int getIndex() { return index; }
    public int getStartChar() { return startChar; }
    public String getText() { return text; }
    public int length() { return text != null ? text.length() : 0; }
    public int getEndChar() { return startChar + length(); }
}
