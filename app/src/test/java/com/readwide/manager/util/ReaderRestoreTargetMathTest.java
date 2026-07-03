package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReaderRestoreTargetMathTest {
    @Test
    public void restoreTargetMatchesCurrentFileByPathOrUri() {
        assertEquals(true, ReaderRestoreTargetMath.matchesCurrentTarget(
                "/books/b.txt", null, "/books/b.txt", null));
        assertEquals(true, ReaderRestoreTargetMath.matchesCurrentTarget(
                null, "content://reader/b", null, "content://reader/b"));
        assertEquals(false, ReaderRestoreTargetMath.matchesCurrentTarget(
                "/books/a.txt", null, "/books/b.txt", null));
        assertEquals(false, ReaderRestoreTargetMath.matchesCurrentTarget(
                null, "content://reader/a", null, "content://reader/b"));
    }

    @Test
    public void restoreTargetFallsBackToLoadedFilePathWhenIntentPathIsMissing() {
        assertEquals(true, ReaderRestoreTargetMath.matchesLoadedFile(
                "/books/b.txt", null, "/books/b.txt"));
        assertEquals(false, ReaderRestoreTargetMath.matchesLoadedFile(
                "/books/a.txt", null, "/books/b.txt"));
        assertEquals(false, ReaderRestoreTargetMath.matchesLoadedFile(
                null, "content://reader/b", "/tmp/opened-b.txt"));
        assertEquals(false, ReaderRestoreTargetMath.matchesLoadedFile(
                null, null, "/tmp/opened-b.txt"));
    }
}
