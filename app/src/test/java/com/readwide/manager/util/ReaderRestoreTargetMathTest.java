package com.readwide.manager.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ReaderRestoreTargetMath}: the guard that keeps a
 * background restore intent recorded for one file from reopening it after
 * the reader switched to another file. The critical case is the strict
 * fallback rule - an explicit current-intent target must be authoritative
 * even while the loaded file path still points at the previous file.
 */
public class ReaderRestoreTargetMathTest {

    @Test
    public void matchingPathsMatch() {
        assertTrue(ReaderRestoreTargetMath.matchesRestoreTarget(
                "/books/a.txt", null,
                "/books/a.txt", null,
                null));
    }

    @Test
    public void matchingUrisMatch() {
        assertTrue(ReaderRestoreTargetMath.matchesRestoreTarget(
                null, "content://provider/a",
                null, "content://provider/a",
                null));
    }

    @Test
    public void differentCurrentPathDoesNotMatch() {
        assertFalse(ReaderRestoreTargetMath.matchesRestoreTarget(
                "/books/a.txt", null,
                "/books/b.txt", null,
                null));
    }

    @Test
    public void differentCurrentUriDoesNotMatch() {
        assertFalse(ReaderRestoreTargetMath.matchesRestoreTarget(
                null, "content://provider/a",
                null, "content://provider/b",
                null));
    }

    @Test
    public void loadedFileFallbackMatchesWhenCurrentTargetIsEmpty() {
        assertTrue(ReaderRestoreTargetMath.matchesRestoreTarget(
                "/books/a.txt", null,
                null, null,
                "/books/a.txt"));
    }

    @Test
    public void loadedFileFallbackRejectsDifferentFileWhenCurrentTargetIsEmpty() {
        assertFalse(ReaderRestoreTargetMath.matchesRestoreTarget(
                "/books/a.txt", null,
                null, null,
                "/books/b.txt"));
    }

    @Test
    public void loadedFileFallbackDoesNotOverrideExplicitCurrentMismatch() {
        // The current intent explicitly targets B; the loaded file path still
        // says A because the switch is mid-flight. A's restore must NOT pass.
        assertFalse(ReaderRestoreTargetMath.matchesRestoreTarget(
                "/books/a.txt", null,
                "/books/b.txt", null,
                "/books/a.txt"));
    }

    @Test
    public void restorePathAgainstCurrentUriOnlyDoesNotMatch() {
        // Dimension mismatch: a path-typed restore target cannot be assumed
        // equal to a uri-typed current target.
        assertFalse(ReaderRestoreTargetMath.matchesRestoreTarget(
                "/books/a.txt", null,
                null, "content://provider/a",
                "/books/a.txt"));
    }

    @Test
    public void blankStringsAreTreatedAsMissing() {
        assertTrue(ReaderRestoreTargetMath.matchesRestoreTarget(
                "/books/a.txt", "",
                " ", "",
                "/books/a.txt"));
        assertFalse(ReaderRestoreTargetMath.matchesRestoreTarget(
                "", null,
                "", null,
                null));
    }
}
