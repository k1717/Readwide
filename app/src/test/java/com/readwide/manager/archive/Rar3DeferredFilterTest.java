package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic descriptor/prefix carry; not producer evidence for arbitrary cross-file output. */
public class Rar3DeferredFilterTest {
    private static final int WINDOW = Rar3PpmdFilterOutput.MAX_FILTER_BYTES;

    @Test public void completeRunRejectsUnstartedAndPartialWorkButAllowsConsumedQueue() throws Exception {
        for (boolean partial : new boolean[]{false, true}) {
            Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
            if (partial) seedPartial(state); else seed(state);
            assertThrows(IOException.class, state::requireEmpty);
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (Rar3PpmdFilterOutput out = output(target, 2, state)) {
                if (!partial) out.write(1);
                out.write(2); out.finish(true);
                assertThrows(IOException.class, state::requireEmpty); // Sink still owns the lease.
            }
            state.requireEmpty(); assertArrayEquals(new byte[]{-1, -3}, target.toByteArray());
        }
    }

    @Test public void oversizedStandardOrCustomResultIsNotSplitOrPublishedToNextFile() throws Exception {
        for (boolean custom : new boolean[]{false, true}) {
            Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
            Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(target, 1, programs, WINDOW, state, true)) {
                if (custom) out.acceptParsed(programs.parse(0xa0, Rar3VirtualMachineTest.resizingRecord(2, 4), 0, WINDOW));
                else out.queue(delta(0, 2));
                IOException failure = assertThrows(IOException.class, () -> out.write(new byte[]{1, 2}));
                assertSame(failure, assertThrows(IOException.class, () -> out.finish(true)));
            }
            assertEquals(0, target.size()); state.requireEmpty(); assertPlainAfterResetOrFailure(state);
        }
    }

    @Test public void receivingFileCannotSplitCarriedFilteredResultAgain() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        seedPartial(state);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(target, 1,
                new Rar3VmFilter.ProgramState(), WINDOW, state, true)) {
            assertThrows(IOException.class, () -> out.write(2));
        }
        assertEquals(0, target.size()); state.requireEmpty(); assertPlainAfterResetOrFailure(state);
    }

    private static Rar3PpmdFilterOutput output(OutputStream target, long size,
            Rar3PpmdFilterOutput.DeferredFilters state) {
        return new Rar3PpmdFilterOutput(target, size, new Rar3VmFilter.ProgramState(), WINDOW, state);
    }

    private static Rar3VmFilter.PendingFilter delta(long start, int length) {
        Rar3VmFilter.PendingFilter filter = new Rar3VmFilter.PendingFilter();
        filter.type = Rar3VmFilter.StandardFilter.DELTA;
        filter.blockStartAbs = start; filter.blockLength = length; filter.initR[0] = 1;
        return filter;
    }

    private static void seed(Rar3PpmdFilterOutput.DeferredFilters state) throws Exception {
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(), 1, state)) {
            out.queue(delta(1, 2)); out.write(42); out.finish(true);
        }
    }

    private static void seedPartial(Rar3PpmdFilterOutput.DeferredFilters state) throws Exception {
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new ByteArrayOutputStream(), 1,
                new Rar3VmFilter.ProgramState(), WINDOW, state, true)) {
            out.queue(delta(1, 2)); out.write(new byte[]{42, 1});
            assertTrue(out.canFinishEntry()); out.finish(true);
        }
    }

    @Test public void prefetchedPrefixBelongsToNextFileAndIsNotCountedTwice() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        seedPartial(state);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target, 3, state)) {
            assertEquals(1, out.rawBytesUntilBoundary()); assertTrue(out.changedLength());
            out.write(new byte[]{2, 9}); out.finish(true);
            assertEquals(2, out.rawBytes()); assertEquals(3, out.outputBytes());
        }
        assertArrayEquals(new byte[]{-1, -3, 9}, target.toByteArray());
    }

    @Test public void prefixCanCrossEmptySolidEntryWithoutMovingItsRawOrigin() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        seedPartial(state);
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new ByteArrayOutputStream(), 0,
                new Rar3VmFilter.ProgramState(), WINDOW, state, true)) { out.finish(true); }
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target, 2, state)) { out.write(2); out.finish(true); }
        assertArrayEquals(new byte[]{-1, -3}, target.toByteArray());
    }

    @Test public void partialPrefixKeepsZeroResultChainAndNextRawRegion() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new ByteArrayOutputStream(), 1,
                programs, WINDOW, state, true)) {
            out.acceptParsed(programs.parse(0xa0,
                    Rar3VirtualMachineTest.deferredResizingRecord(1, 2, 0), 0, WINDOW));
            out.acceptParsed(programs.parse(0xa0,
                    Rar3VirtualMachineTest.deferredResizingRecord(1, 0, 0), 0, WINDOW));
            out.queue(delta(3, 1)); out.write(new byte[]{42, 1}); out.finish(true);
        }
        programs.reset();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target, 1, state)) {
            out.write(new byte[]{2, 3}); out.finish(true);
        }
        assertArrayEquals(new byte[]{-3}, target.toByteArray());
    }

    @Test public void partialBranchFilterUsesReceivingFileOffset() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new ByteArrayOutputStream(), 1,
                new Rar3VmFilter.ProgramState(), WINDOW, state, true)) {
            Rar3VmFilter.PendingFilter filter = delta(1, 6);
            filter.type = Rar3VmFilter.StandardFilter.E8;
            out.queue(filter); out.write(new byte[]{42, (byte) 0xe8, 100}); out.finish(true);
        }
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target, 6, state)) {
            out.write(new byte[]{0, 0, 0, 0}); out.finish(true);
        }
        assertArrayEquals(new byte[]{(byte) 0xe8, 99, 0, 0, 0, 0}, target.toByteArray());
    }

    @Test public void incompletePreviousOutputAndMissingMarkerStillCannotCarryPrefix() throws Exception {
        for (boolean outputShort : new boolean[]{false, true}) {
            Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
            try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new ByteArrayOutputStream(),
                    outputShort ? 1 : 0, new Rar3VmFilter.ProgramState(), WINDOW, state, true)) {
                out.queue(delta(0, 2)); out.write(1);
                assertEquals(!outputShort, out.canFinishEntry());
                assertThrows(IOException.class, () -> out.finish(outputShort));
            }
            assertPlainAfterResetOrFailure(state);
        }
    }

    @Test public void carriedPrefixRequiresAnotherContinuationIfStillIncomplete() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        seedPartial(state);
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(), 0, state)) {
            assertFalse(out.canFinishEntry()); assertThrows(IOException.class, () -> out.finish(true));
        }
        assertPlainAfterResetOrFailure(state);
    }

    @Test public void partialPrefixRejectsLateChainAndOverlappingNewRawRegion() throws Exception {
        for (int start : new int[]{-1, 0}) {
            Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
            seedPartial(state);
            try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(), 2, state)) {
                assertThrows(IOException.class, () -> out.queue(delta(start, 2)));
            }
            assertPlainAfterResetOrFailure(state);
        }
    }

    @Test public void partialPrefixIsDiscardedOnResetAbortCancellationAndFlushFailure() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
            seedPartial(state);
            if (mode == 0) state.reset();
            else {
                OutputStream target = new ByteArrayOutputStream() {
                    @Override public void flush() throws IOException { throw new IOException("flush failed"); }
                };
                try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(target, 0,
                        new Rar3VmFilter.ProgramState(), WINDOW, state, true)) {
                    if (mode == 2) {
                        Thread.currentThread().interrupt();
                        try { assertThrows(IOException.class, () -> out.finish(true)); }
                        finally { Thread.interrupted(); }
                    } else if (mode == 3) assertThrows(IOException.class, () -> out.finish(true));
                }
            }
            assertPlainAfterResetOrFailure(state);
        }
    }

    @Test public void forwardDescriptorSurvivesSeveralEntriesIncludingEmptyEntry() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target, 2, state)) {
            out.queue(delta(5, 2)); out.write(new byte[]{40,41});
            assertTrue(out.canFinishEntry()); assertTrue(out.needsInput()); out.finish(true);
        }
        try (Rar3PpmdFilterOutput out = output(target, 0, state)) { out.finish(true); }
        try (Rar3PpmdFilterOutput out = output(target, 2, state)) {
            out.write(new byte[]{42,43}); out.finish(true);
        }
        try (Rar3PpmdFilterOutput out = output(target, 3, state)) {
            out.write(new byte[]{44,1,2}); out.finish(true);
        }
        assertArrayEquals(new byte[]{40,41,42,43,44,-1,-3}, target.toByteArray());
    }

    @Test public void carryUsesRawRatherThanResizedFinalPosition() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(target, 1, programs, WINDOW, state)) {
            out.acceptParsed(programs.parse(0xa0, Rar3VirtualMachineTest.resizingRecord(2,1),0,WINDOW));
            out.queue(delta(3,2)); out.write(new byte[]{42,43}); out.finish(true);
        }
        try (Rar3PpmdFilterOutput out = output(target,3,state)) {
            out.write(new byte[]{44,1,2}); out.finish(true);
        }
        assertArrayEquals(new byte[]{42,44,-1,-3},target.toByteArray());
    }

    @Test public void carriedBranchFilterUsesNewFileOffsetNotOldRawStart() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),3,state)) {
            Rar3VmFilter.PendingFilter filter = delta(4,6);
            filter.type = Rar3VmFilter.StandardFilter.E8;
            out.queue(filter); out.write(new byte[]{1,2,3}); out.finish(true);
        }
        try (Rar3PpmdFilterOutput out = output(target,7,state)) {
            out.write(new byte[]{42,(byte)0xe8,100,0,0,0,0}); out.finish(true);
        }
        assertArrayEquals(new byte[]{42,(byte)0xe8,98,0,0,0,0},target.toByteArray());
    }

    @Test public void carriedProgramSurvivesSlotResetAndChainsWithNewRecord() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(new ByteArrayOutputStream(),1,programs,WINDOW,state)) {
            out.acceptParsed(programs.parse(0xa0,
                    Rar3VirtualMachineTest.deferredResizingRecord(1,2,4),0,WINDOW));
            out.write(42); out.finish(true);
        }
        programs.reset(); // Queued descriptors still own the original program.
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(target,3,programs,WINDOW,state)) {
            out.acceptParsed(programs.parse(0xa0,Rar3VirtualMachineTest.resizingRecord(4,3),0,WINDOW));
            out.write(new byte[]{1,2}); out.finish(true);
        }
        assertArrayEquals(new byte[]{1,2,0},target.toByteArray());
    }

    @Test public void carriedZeroIntermediateChainKeepsItsOriginalRawExtent() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(target,1,programs,WINDOW,state)) {
            out.acceptParsed(programs.parse(0xa0,
                    Rar3VirtualMachineTest.deferredResizingRecord(1,2,0),0,WINDOW));
            out.acceptParsed(programs.parse(0xa0,
                    Rar3VirtualMachineTest.deferredResizingRecord(1,0,0),0,WINDOW));
            out.write(42); out.finish(true);
        }
        try (Rar3PpmdFilterOutput out = output(target,0,state)) { out.finish(true); }
        try (Rar3PpmdFilterOutput out = output(target,1,state)) {
            // The old descriptors survived resets while parsing the second slot.
            out.queue(delta(2,1)); out.write(new byte[]{1,2,3}); out.finish(true);
            assertEquals(3,out.rawBytes()); assertEquals(1,out.outputBytes());
        }
        assertArrayEquals(new byte[]{42,-3},target.toByteArray());
    }

    @Test public void carryNeedsExplicitBoundaryAndFailureIsSticky() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),1,state)) {
            out.queue(delta(1,2)); out.write(42);
            IOException failure = assertThrows(IOException.class,out::finish);
            assertSame(failure,assertThrows(IOException.class,()->out.finish(true)));
        }
        assertPlainAfterResetOrFailure(state);
    }

    @Test public void partialCurrentRegionCannotBeDeferredAtFileMarker() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target,0,state)) {
            out.queue(delta(0,2)); out.write(1);
            assertFalse(out.canFinishEntry());
            assertThrows(IOException.class,()->out.finish(true));
        }
        assertEquals(0,target.size()); assertPlainAfterResetOrFailure(state);
    }

    @Test public void carryDoesNotAllowAnExtraPlainByteBeyondDeclaredSize() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target,1,state)) {
            out.queue(delta(2,2)); out.write(42);
            assertThrows(IOException.class,()->out.write(43));
        }
        assertArrayEquals(new byte[]{42},target.toByteArray());
    }

    @Test public void carriedRawExtentStillRejectsOverlapAndPendingMetadataOverflow() throws Exception {
        for (boolean overflow : new boolean[]{false,true}) {
            Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
            seed(state);
            try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),2,state)) {
                if (overflow) {
                    for (int i=1;i<Rar3VmFilter.ProgramRetention.MAX_PENDING_FILTERS;i++) out.queue(delta(0,2));
                }
                assertThrows(IOException.class,()->out.queue(delta(overflow ? 0 : 1,2)));
            }
        }
    }

    @Test public void abortResetAndCancellationDiscardCarriedDescriptors() throws Exception {
        for (int mode=0;mode<3;mode++) {
            Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters(); seed(state);
            if (mode==0) state.reset();
            else try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),2,state)) {
                if (mode==2) {
                    Thread.currentThread().interrupt();
                    try { assertThrows(IOException.class,()->out.write(1)); }
                    finally { Thread.interrupted(); }
                }
            }
            assertPlainAfterResetOrFailure(state);
        }
    }

    @Test public void failedFlushDoesNotTransferAndCallerStillOwnsDestination() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        IOException sentinel = new IOException("flush failed");
        OutputStream target = new OutputStream() {
            @Override public void write(int value) {}
            @Override public void flush() throws IOException { throw sentinel; }
            @Override public void close() { fail("Caller owns destination"); }
        };
        try (Rar3PpmdFilterOutput out = output(target,1,state)) {
            out.queue(delta(1,2)); out.write(42);
            assertSame(sentinel,assertThrows(IOException.class,()->out.finish(true)));
        }
        assertPlainAfterResetOrFailure(state);
    }

    @Test public void completedOldCloseCannotReleaseNextEntryLease() throws Exception {
        Rar3PpmdFilterOutput.DeferredFilters state = new Rar3PpmdFilterOutput.DeferredFilters();
        Rar3PpmdFilterOutput old = output(new ByteArrayOutputStream(),1,state);
        old.queue(delta(1,2)); old.write(42); old.finish(true); old.close();
        try (Rar3PpmdFilterOutput next = output(new ByteArrayOutputStream(),2,state)) {
            old.close();
            assertThrows(IllegalStateException.class,()->output(new ByteArrayOutputStream(),2,state));
            next.write(new byte[]{1,2}); next.finish(true);
        }
    }

    @Test public void independentEntryStillRejectsFutureOnlyDescriptor() throws Exception {
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),1,null)) {
            assertThrows(IOException.class,()->out.queue(delta(2,2)));
        }
    }

    private static void assertPlainAfterResetOrFailure(Rar3PpmdFilterOutput.DeferredFilters state) throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(target,2,state)) { out.write(new byte[]{1,2}); out.finish(true); }
        assertArrayEquals(new byte[]{1,2},target.toByteArray());
    }
}
