package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdSuccessorContextTest {
    @Test
    public void rootContextIsRegisteredAsInitialSuccessorNodeSeparateFromStateArray() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                false);

        assertEquals(0, source.rootContextForTest().contextPointer());
        assertNotEquals(source.rootContextForTest().stateArrayPointer(),
                source.rootContextForTest().contextPointer());
        assertEquals(1, source.registeredSuccessorContextCountForTest());
        assertSame(source.rootContextForTest(),
                source.successorContextForTest(source.rootContextForTest().contextPointer()));
    }

    @Test
    public void explicitSuccessorCreationAttachesContextToDecodedState() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                false);
        RarPpmdContext root = source.rootContextForTest();
        root.insertOrUpdateState('A', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        RarPpmdContext successor = source.ensureSuccessorForStateForTest(root, 'A');
        RarPpmdStateRecord state = root.findState('A');

        assertNotNull(state);
        assertTrue(state.hasSuccessor());
        assertEquals(successor.contextPointer(), state.successorPointer());
        assertEquals(root.contextPointer(), successor.suffixPointer());
        assertSame(successor, source.successorContextForTest(state.successorPointer()));
        assertEquals(1, source.allocatedSuccessorContextCountForTest());
        assertEquals(2, source.registeredSuccessorContextCountForTest());
    }

    @Test
    public void diagnosticDecodeCreatesSuccessorForDecodedContextState() throws Exception {
        RarPpmdRangeDecoder decoder = rangeDecoderForCount(0, 2);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('A');
        RarPpmdContext order1 = source.ensureOrder1ContextForTest('A');
        order1.insertOrUpdateState('B', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertEquals('B', source.decodeSymbol());

        RarPpmdStateRecord decodedState = order1.findState('B');
        assertNotNull(decodedState);
        assertTrue(decodedState.hasSuccessor());
        RarPpmdContext successor = source.successorContextForTest(decodedState.successorPointer());
        RarPpmdContext decodedOrder1Suffix = source.order1ContextForTest('B');
        assertNotNull(successor);
        assertNotNull(decodedOrder1Suffix);
        assertEquals(decodedOrder1Suffix.contextPointer(), successor.suffixPointer());
        assertEquals(1, source.modelForTest().diagnosticSuccessorCreationCountForTest());
        assertTrue(source.modelForTest().diagnostic().contains("successorContexts="));
    }


    @Test
    public void successorTraversalCandidateIsUsedBeforeSyntheticOrderContexts() throws Exception {
        RarPpmdRangeDecoder decoder = rangeDecoderForCount(0, 2);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('A');
        RarPpmdContext root = source.rootContextForTest();
        root.insertOrUpdateState('A', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdContext successor = source.ensureSuccessorForStateForTest(root, 'A');
        successor.insertOrUpdateState('B', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertSame(successor, source.successorTraversalCandidateForTest('A', -1));
        assertEquals('B', source.decodeSymbol());

        assertEquals(1, source.modelForTest().diagnosticSuccessorTraversalCountForTest());
        assertEquals('B', source.symbolHistoryForTest(0));
        assertTrue(source.modelForTest().diagnosticContextUpdateCountForTest() > 0);
    }

    @Test
    public void orderTwoSuccessorTraversalCandidateIsPreferredWhenPresent() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                false);
        RarPpmdContext order1 = source.ensureOrder1ContextForTest('N');
        order1.insertOrUpdateState('O', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        RarPpmdContext order2Successor = source.ensureSuccessorForStateForTest(order1, 'O');

        assertSame(order2Successor, source.successorTraversalCandidateForTest('N', 'O'));
    }

    @Test
    public void rootDecodedStateSuccessorUsesOrderOneContextInsteadOfEmptyGenericContext() throws Exception {
        RarPpmdRangeDecoder decoder = rangeDecoderForCount(0, 257);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.fromPackedPayload(
                new byte[] {(byte) 0xa7, 0x18, 0, 0, 0, 0});
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticWithOptionsForTest(
                decoder, state, header, RarPpmdDiagnosticOptions.rarPrimaryRootCursor());

        assertEquals(0, source.decodeSymbol());

        RarPpmdStateRecord rootState = source.rootContextForTest().findState(0);
        RarPpmdContext order1 = source.order1ContextForTest(0);
        assertNotNull(rootState);
        assertNotNull(order1);
        assertEquals(order1.contextPointer(), rootState.successorPointer());
    }

    @Test
    public void orderOneDecodedStateSuccessorUsesOrderTwoContextWithDecodedSymbolSuffix() throws Exception {
        RarPpmdRangeDecoder decoder = rangeDecoderForCount(0, 2);
        Rar3PpmdState state = new Rar3PpmdState();
        Rar3PpmdBlockHeader header = Rar3PpmdBlockHeader.syntheticForTest(false);
        Rar3PpmdModelSymbolSource source = Rar3PpmdModelSymbolSource.diagnosticContextFallbackForTest(
                decoder, state, header);
        source.seedPreviousSymbolForTest('A');
        RarPpmdContext ownerOrder1 = source.ensureOrder1ContextForTest('A');
        ownerOrder1.insertOrUpdateState('B', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertEquals('B', source.decodeSymbol());

        RarPpmdStateRecord ownerState = ownerOrder1.findState('B');
        RarPpmdContext order2 = source.order2ContextForTest('B', 'A');
        RarPpmdContext decodedSymbolSuffix = source.order1ContextForTest('B');
        assertNotNull(ownerState);
        assertNotNull(order2);
        assertNotNull(decodedSymbolSuffix);
        assertEquals(order2.contextPointer(), ownerState.successorPointer());
        assertEquals(decodedSymbolSuffix.contextPointer(), order2.suffixPointer());
    }



    @Test
    public void pendingTextSuccessorMaterializesBeforeTraversal() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                false);
        RarPpmdContext root = source.rootContextForTest();
        root.insertOrUpdateState('Q', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        root.requireState('Q').setPendingTextSuccessor('Q');
        assertTrue(root.requireState('Q').hasPendingTextSuccessor());

        source.seedPreviousSymbolForTest('Q');
        RarPpmdContext candidate = source.successorTraversalCandidateForTest('Q', -1);
        assertTrue(candidate == null);

        RarPpmdContext materialized = source.materializedSuccessorTraversalCandidateForTest();
        assertNotNull(materialized);
        assertTrue(root.requireState('Q').hasContextSuccessor());
        assertEquals(materialized.contextPointer(), root.requireState('Q').successorPointer());
    }


    @Test
    public void seededPendingTextSuccessorMaterializationAddsDiagnosticState() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                false);
        RarPpmdContext root = source.rootContextForTest();
        root.insertOrUpdateState('Q', 1, RarPpmdStateRecord.NO_SUCCESSOR);
        root.requireState('Q').setPendingTextSuccessor('Q');
        source.seedPreviousSymbolForTest('Q');

        RarPpmdContext materialized = source.seededMaterializedSuccessorTraversalCandidateForTest();

        assertNotNull(materialized);
        assertTrue(root.requireState('Q').hasContextSuccessor());
        assertEquals(materialized.contextPointer(), root.requireState('Q').successorPointer());
        assertNotNull(materialized.findState('Q'));
        assertEquals(1, materialized.findState('Q').frequency());
    }

    @Test
    public void createSuccessorsHelperDoesNotDuplicateSeedState() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64);
        RarPpmdContext context = new RarPpmdContext();
        context.attachContextPointer(allocator.allocUnits(1));
        context.insertOrUpdateState('A', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        assertFalse(RarPpmdCreateSuccessors.seedContextIfMissing(context, 'A', allocator));
        assertEquals(1, context.findState('A').frequency());
        assertTrue(RarPpmdCreateSuccessors.seedContextIfMissing(context, 'B', allocator));
        assertNotNull(context.findState('B'));
    }

    @Test
    public void repeatedSuccessorRequestReusesExistingContext() throws Exception {
        Rar3PpmdModelSymbolSource source = new Rar3PpmdModelSymbolSource(
                new RarPpmdByteInput.ArrayInput(new byte[] {1, 2, 3, 4}),
                false);
        RarPpmdContext root = source.rootContextForTest();
        root.insertOrUpdateState('Z', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        RarPpmdContext first = source.ensureSuccessorForStateForTest(root, 'Z');
        RarPpmdContext second = source.ensureSuccessorForStateForTest(root, 'Z');

        assertSame(first, second);
        assertNotEquals(RarPpmdStateRecord.NO_SUCCESSOR, root.findState('Z').successorPointer());
        assertEquals(1, source.allocatedSuccessorContextCountForTest());
    }

    private static RarPpmdRangeDecoder rangeDecoderForCount(int count, int scale) {
        long range = 0xffff_ffffL;
        long unit = Long.divideUnsigned(range, scale);
        long code = unit * count;
        return new RarPpmdRangeDecoder(new RarPpmdByteInput.ArrayInput(new byte[8]), 0, code, range);
    }
}
