package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Rar3ClassicLzStateTraceTest {
    @Test
    public void recordsLengthDistanceAndOldDistanceState() throws Exception {
        Rar3ClassicLzStateTrace trace = new Rar3ClassicLzStateTrace();
        trace.beginBlock(1, new int[Rar3HuffmanTables.TABLE_SIZE], 2, 0);
        trace.afterTables(tablesWithMainAndDistanceSymbols(), 40);
        trace.recordLiteral();
        trace.recordMatch(
                Rar3DecodeAction.Type.LONG_MATCH,
                33,
                7,
                7,
                -1,
                1,
                8,
                0,
                0,
                0,
                0,
                0,
                0,
                33,
                0,
                0,
                0,
                33,
                7,
                1,
                10,
                4,
                5,
                false,
                Rar3ClassicLzStateTransitionCheck.Result.ok());
        trace.recordMatch(
                Rar3DecodeAction.Type.OLD_DISTANCE_MATCH,
                33,
                4,
                4,
                0,
                8,
                12,
                33,
                0,
                0,
                0,
                33,
                7,
                33,
                0,
                0,
                0,
                33,
                4,
                2,
                -1,
                -1,
                -1,
                false,
                Rar3ClassicLzStateTransitionCheck.Result.ok());
        trace.recordEndBlock(100, 12);

        Rar3ClassicLzStateTrace.Snapshot snapshot = trace.snapshot();
        assertEquals(1, snapshot.literalCount);
        assertEquals(1, snapshot.longMatchCount);
        assertEquals(1, snapshot.oldDistanceMatchCount);
        assertEquals(33, snapshot.maxDistance);
        assertEquals(7, snapshot.maxLength);
        assertTrue(snapshot.compact().contains("old=1"));
        assertTrue(snapshot.matchesMarkdown().contains("LONG_MATCH"));
        assertTrue(snapshot.blocksMarkdown().contains("same-as-previous") || snapshot.blocksMarkdown().contains("first"));
    }

    @Test
    public void separatesChangedTableBoundaryFromRepeatedTableBoundary() throws Exception {
        Rar3ClassicLzStateTrace trace = new Rar3ClassicLzStateTrace();
        int[] old = new int[Rar3HuffmanTables.TABLE_SIZE];
        trace.beginBlock(1, old, 0, 0);
        trace.afterTables(tablesWithMainAndDistanceSymbols(), 10);
        trace.recordEndBlock(20, 1);
        trace.beginBlock(2, old, 20, 1);
        trace.afterTables(tablesWithDifferentMainSymbols(), 35);
        trace.recordEndBlock(50, 2);

        Rar3ClassicLzStateTrace.Snapshot snapshot = trace.snapshot();
        assertEquals(1, snapshot.changedTableBlocks);
        assertTrue(snapshot.markdownStatus().contains("tableChanged=1"));
    }

    private static Rar3HuffmanTables tablesWithMainAndDistanceSymbols() throws Exception {
        int[] lengths = new int[Rar3HuffmanTables.TABLE_SIZE];
        lengths[65] = 1;
        lengths[Rar3HuffmanTables.NC] = 1;
        lengths[Rar3HuffmanTables.NC + Rar3HuffmanTables.DC] = 1;
        lengths[Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC] = 1;
        return tables(lengths);
    }

    private static Rar3HuffmanTables tablesWithDifferentMainSymbols() throws Exception {
        int[] lengths = new int[Rar3HuffmanTables.TABLE_SIZE];
        lengths[66] = 1;
        lengths[Rar3HuffmanTables.NC] = 1;
        lengths[Rar3HuffmanTables.NC + Rar3HuffmanTables.DC] = 1;
        lengths[Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC] = 1;
        return tables(lengths);
    }

    private static Rar3HuffmanTables tables(int[] lengths) throws Exception {
        return new Rar3HuffmanTables(
                false,
                lengths,
                RarCanonicalHuffman.fromCodeLengths(new int[] {1}),
                RarCanonicalHuffman.fromCodeLengths(new int[] {1}),
                RarCanonicalHuffman.fromCodeLengths(new int[] {1}),
                RarCanonicalHuffman.fromCodeLengths(new int[] {1}));
    }
}
