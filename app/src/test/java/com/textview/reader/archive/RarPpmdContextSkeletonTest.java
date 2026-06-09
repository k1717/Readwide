package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdContextSkeletonTest {
    @Test
    public void contextInsertsUpdatesPromotesAndTracksScale() throws Exception {
        RarPpmdContext context = new RarPpmdContext();
        context.setSuffixPointer(24);

        RarPpmdStateRecord a = context.insertOrUpdateState('A', 2, 48);
        RarPpmdStateRecord b = context.insertOrUpdateState('B', 3, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdStateRecord aAgain = context.insertOrUpdateState('A', 4, 96);

        assertSame(a, aAgain);
        assertEquals(2, context.stateCount());
        assertEquals(9, context.scale());
        assertEquals(24, context.suffixPointer());
        assertEquals(96, a.successorPointer());
        assertEquals('A', context.stateAt(0).symbol());
        assertEquals('B', b.symbol());

        assertTrue(context.promoteState('B'));
        assertEquals('B', context.stateAt(0).symbol());
        assertFalse(context.promoteState('Z'));
    }

    @Test
    public void contextCanReserveSuballocatorStorageForStates() throws Exception {
        RarPpmdContext context = new RarPpmdContext();
        context.insertOrUpdateState('X', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        context.insertOrUpdateState('Y', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(1);

        int pointer = context.allocateStateArray(allocator);

        assertEquals(0, pointer);
        assertEquals(pointer, context.stateArrayPointer());
        assertEquals(2, context.stateArrayUnits());
        assertEquals(2 * RarPpmdSubAllocator.UNIT_SIZE_BYTES, allocator.usedBytes());
    }

    @Test
    public void escapeMaskMarksContextSymbolsAndClearsCheaply() throws Exception {
        RarPpmdContext context = new RarPpmdContext();
        context.insertOrUpdateState('A', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        context.insertOrUpdateState('C', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdEscapeMask mask = new RarPpmdEscapeMask();

        mask.markContext(context);
        mask.mark('A');

        assertTrue(mask.isMasked('A'));
        assertFalse(mask.isMasked('B'));
        assertTrue(mask.isMasked('C'));
        assertEquals(2, mask.maskedCount());

        mask.clear();

        assertFalse(mask.isMasked('A'));
        assertEquals(0, mask.maskedCount());
    }

    @Test
    public void seeContextUpdatesEscapeAndSymbolCountersDeterministically() throws Exception {
        RarPpmdSeeContext see = new RarPpmdSeeContext(16, 2, 1);

        assertEquals(4, see.mean());
        see.updateAfterEscape();

        assertEquals(10, see.summary());
        assertEquals(3, see.shift());
        assertEquals(3, see.count());
        assertEquals(1, see.mean());

        see.updateAfterSymbol();

        assertEquals(9, see.summary());
        assertEquals(4, see.count());
    }

    @Test
    public void modelSymbolSourceOwnsDiagnosticSkeletonButStillFailsCleanly() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                true);

        assertEquals(1, source.rootContextForTest().stateCount());
        assertEquals(1, source.rootContextForTest().stateArrayUnits());
        assertEquals(12, source.subAllocatorForTest().usedBytes());
        assertEquals(0, source.escapeMaskForTest().maskedCount());
        assertEquals(1, source.seeContextForTest().mean());

        try {
            source.decodeSymbol();
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("diagnostic primitives"));
            assertTrue(expected.getMessage().contains("masked-symbol"));
            assertTrue(expected.getMessage().contains("keepOldTable=true"));
            return;
        }
        throw new AssertionError("Full PPMd model must remain a precise first-party gap");
    }
}
