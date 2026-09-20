package com.readwide.manager.model;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReaderStateSnapshotTest {
    @Test public void copyPreservesEveryPersistedFieldWithoutChangingTimestamp() {
        ReaderState state = populated();
        ReaderState copy = state.copy();
        assertNotSame(state, copy);
        assertEquals("book.pdf", copy.getFilePath());
        assertEquals(7, copy.getCharPosition());
        assertEquals(123, copy.getScrollY());
        assertEquals(8, copy.getPageNumber());
        assertEquals(80, copy.getTotalPages());
        assertEquals(12345678901L, copy.getFileLength());
        assertEquals(12345678902L, copy.getLastReadAt());
        assertEquals("PDF_PAGE", copy.getEncoding());
        assertEquals("anchor", copy.getContentAnchorJson());
        assertEquals("layout", copy.getPresentationSignature());
        assertEquals("before", copy.getAnchorTextBefore());
        assertEquals("after", copy.getAnchorTextAfter());
    }

    @Test public void copyPreservesUnsetValues() {
        ReaderState copy = new ReaderState().copy();
        assertNull(copy.getFilePath());
        assertNull(copy.getEncoding());
        assertNull(copy.getContentAnchorJson());
        assertNull(copy.getPresentationSignature());
        assertNull(copy.getAnchorTextBefore());
        assertNull(copy.getAnchorTextAfter());
        assertEquals(0L, copy.getLastReadAt());
        assertEquals(0L, copy.getFileLength());
        assertEquals(0, copy.getCharPosition());
        assertEquals(0, copy.getScrollY());
        assertEquals(0, copy.getPageNumber());
        assertEquals(0, copy.getTotalPages());
    }

    @Test public void laterMutationsDoNotChangeOwnedSnapshotOrOriginal() {
        ReaderState state = populated();
        ReaderState copy = state.copy();
        state.setFilePath("renamed.pdf");
        state.setCharPosition(22);
        state.setContentAnchorJson("new anchor");
        assertEquals("book.pdf", copy.getFilePath());
        assertEquals(7, copy.getCharPosition());
        assertEquals("anchor", copy.getContentAnchorJson());
        copy.setPageNumber(70);
        copy.setLastReadAt(999L);
        assertEquals(8, state.getPageNumber());
        assertEquals(12345678902L, state.getLastReadAt());
    }

    private static ReaderState populated() {
        ReaderState state = new ReaderState("book.pdf");
        state.setCharPosition(7);
        state.setScrollY(123);
        state.setPageNumber(8);
        state.setTotalPages(80);
        state.setFileLength(12345678901L);
        state.setLastReadAt(12345678902L);
        state.setEncoding("PDF_PAGE");
        state.setContentAnchorJson("anchor");
        state.setPresentationSignature("layout");
        state.setAnchorTextBefore("before");
        state.setAnchorTextAfter("after");
        return state;
    }
}
