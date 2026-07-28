package com.readwide.manager.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BookmarkMergeMathTest {
    @Test
    public void legacyLocationsStillMergeByExactPosition() {
        assertTrue(BookmarkMergeMath.isSameLogicalPosition(6, "", 6, null));
        assertFalse(BookmarkMergeMath.isSameLogicalPosition(6, "", 7, ""));
    }

    @Test
    public void identicalPortableAnchorMerges() {
        String anchor = "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1}";
        assertTrue(BookmarkMergeMath.isSameLogicalPosition(6, anchor, 6, "  " + anchor));
    }

    @Test
    public void sameSpinePageDifferentAnchorsRemainSeparate() {
        String first = "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1}";
        String second = "{\"pageIndex\":6,\"blockIndex\":59,\"charOffset\":2}";
        assertFalse(BookmarkMergeMath.isSameLogicalPosition(6, first, 6, second));
    }

    @Test
    public void presentationOnlyColumnStartDoesNotSplitBackupMergeIdentity() {
        String first = "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1,"
                + "\"columnStartText\":\"縦書きの先頭\"}";
        String second = "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1,"
                + "\"columnStartText\":\"別の表示断片\"}";
        String legacyWithoutLabel =
                "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1}";

        assertTrue(BookmarkMergeMath.isSameLogicalPosition(6, first, 6, second));
        assertTrue(BookmarkMergeMath.isSameLogicalPosition(
                6, first, 6, legacyWithoutLabel));
    }

    @Test
    public void escapedColumnStartTextIsIgnoredButPreciseOffsetStillMatters() {
        String first = "{\"columnStartText\":\"引用 \\\\\"先頭\\\\\"\","
                + "\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1}";
        String same = "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1}";
        String different = "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":2}";

        assertTrue(BookmarkMergeMath.isSameLogicalPosition(6, first, 6, same));
        assertFalse(BookmarkMergeMath.isSameLogicalPosition(
                6, first, 6, different));
    }

    @Test
    public void preciseAnchorDoesNotDisappearBehindLegacyPageRow() {
        String precise = "{\"pageIndex\":6,\"blockIndex\":22,\"charOffset\":1}";
        assertFalse(BookmarkMergeMath.isSameLogicalPosition(6, "", 6, precise));
    }
}
