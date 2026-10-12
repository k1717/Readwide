package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;

/** Synthetic VM programs, not a claim of producer/custom-filter interoperability. */
public class Rar3VirtualMachineTest {
    @Test public void customByteWritePreservesOtherBytes() throws Exception {
        Code code = new Code(); code.op(0,true).memory(0).immediate(0xa5,true); code.op(22,false);
        assertArrayEquals(new byte[]{(byte)0xa5,2}, apply(code,new byte[]{1,2},new byte[0],0));
    }
    @Test public void copiesFullOffsetLowWordFromR6() throws Exception {
        Code code=new Code(); code.op(0,false).memory(0).register(6); code.op(22,false);
        assertArrayEquals(new byte[]{4,3,2,1},apply(code,new byte[4],new byte[0],0x101020304L));
    }
    @Test public void persistentGlobalsSurviveProgramReuse() throws Exception {
        Code code=new Code();
        code.op(0,true).memory(0).memory(0x3c040);
        code.op(6,true).memory(0x3c040);
        code.op(0,false).memory(0x3c030).immediate(1,false); code.op(22,false);
        Rar3VirtualMachine.Program program=Rar3VirtualMachine.compile(code.finish());
        assertArrayEquals(new byte[]{7},Rar3VirtualMachine.apply(program,new byte[1],1,registers(1),new byte[]{7},0));
        assertArrayEquals(new byte[]{8},Rar3VirtualMachine.apply(program,new byte[1],1,registers(1),new byte[]{99},1));
    }
    @Test public void shift32DoesNotWrapAsAJavaShift() throws Exception {
        Code code=new Code(); code.op(0,false).register(0).immediate(-1,false);
        code.op(25,false).register(0).immediate(32,false);
        code.op(0,false).memory(0).register(0); code.op(22,false);
        assertArrayEquals(new byte[4],apply(code,new byte[4],new byte[0],0));
    }
    @Test public void executionCounterIsNotTheOverridableR5() throws Exception {
        Code code=new Code(); code.op(0,false).memory(0).memory(0x3c02c); code.op(22,false);
        Rar3VirtualMachine.Program program=Rar3VirtualMachine.compile(code.finish());
        int[] r=registers(4);r[5]=99;
        assertArrayEquals(new byte[4],Rar3VirtualMachine.apply(program,new byte[4],4,r,new byte[0],0));
        assertArrayEquals(new byte[]{1,0,0,0},Rar3VirtualMachine.apply(program,new byte[4],4,r,new byte[0],4));
    }
    @Test public void streamingOutputPreservesExplicitR4() throws Exception {
        Code code=new Code(); code.op(0,false).memory(0).register(4); code.op(22,false);
        Rar3VmFilter.ProgramState state=new Rar3VmFilter.ProgramState();
        Rar3VmFilter.ProgramState.Parsed parsed=state.parse(0xa0,record(code.finish(),4),0,0x40000);
        parsed.filter.initR[4]=0x01020304;
        ByteArrayOutputStream target=new ByteArrayOutputStream();
        try(Rar3PpmdFilterOutput output=new Rar3PpmdFilterOutput(target,4,state)) {
            output.acceptParsed(parsed);output.write(new byte[4]);output.finish();
        }
        assertArrayEquals(new byte[]{4,3,2,1},target.toByteArray());
    }
    @Test public void movzxImmediateKeepsEncodedOperandWidth() throws Exception {
        Code code=new Code();code.op(32,false).memory(0).immediate(0x10203,false);code.op(22,false);
        assertArrayEquals(new byte[]{3,2,1,0},apply(code,new byte[4],new byte[0],0));
    }
    @Test public void infiniteProgramFailsExplicitly() throws Exception {
        Code code=new Code(); code.op(8,false).immediate(0,false);
        Rar3VirtualMachine.Program program=Rar3VirtualMachine.compile(code.finish());
        IOException error=assertThrows(IOException.class,()->Rar3VirtualMachine.apply(program,new byte[1],1,registers(1),new byte[0],0,8));
        assertTrue(error.getMessage().contains("budget"));
    }
    @Test public void invalidChecksumFailsBeforeExecution() throws Exception {
        Code code=new Code(); code.op(22,false); byte[] bytes=code.finish(); bytes[0]^=1;
        assertThrows(IOException.class,()->Rar3VirtualMachine.compile(bytes));
    }
    @Test public void invalidOutputRangeDoesNotPublish() throws Exception {
        Code code=new Code(); code.op(0,false).memory(0x3c020).immediate(0x3ffff,false);
        code.op(22,false);
        assertThrows(IOException.class,()->apply(code,new byte[2],new byte[0],0));
    }
    @Test public void oversizedSavedGlobalsAreRejected() throws Exception {
        Code code=new Code(); code.op(0,false).memory(0x3c030).immediate(0x2000,false); code.op(22,false);
        assertThrows(IOException.class,()->apply(code,new byte[1],new byte[0],0));
    }
    @Test public void cancellationIsTerminal() throws Exception {
        Code code=new Code(); code.op(22,false);
        Rar3VirtualMachine.Program program=Rar3VirtualMachine.compile(code.finish());
        Thread.currentThread().interrupt();
        try { assertThrows(IOException.class,()->Rar3VirtualMachine.apply(program,new byte[1],1,registers(1),new byte[0],0)); }
        finally { Thread.interrupted(); }
    }
    @Test public void customProgramFlowsThroughRecordParserAndStreamingOutput() throws Exception {
        Code program=new Code(); program.op(0,true).memory(0).immediate(9,true); program.op(22,false);
        Rar3VmFilter.ProgramState state=new Rar3VmFilter.ProgramState();
        byte[] record=record(program.finish(),2);
        Rar3VmFilter.ProgramState.Parsed parsed=state.parse(0xa0,record,0,0x40000);
        assertNotNull(parsed.filter.customProgram);
        ByteArrayOutputStream target=new ByteArrayOutputStream();
        try(Rar3PpmdFilterOutput output=new Rar3PpmdFilterOutput(target,2,state)) {
            output.acceptParsed(parsed); output.write(new byte[]{1,2}); output.finish();
        }
        assertArrayEquals(new byte[]{9,2},target.toByteArray());
    }
    @Test public void shortenedOutputMustStillReachDeclaredFinalSize() throws Exception {
        Code program=new Code(); program.op(0,false).memory(0x3c01c).immediate(1,false); program.op(22,false);
        Rar3VmFilter.ProgramState state=new Rar3VmFilter.ProgramState();
        Rar3VmFilter.ProgramState.Parsed parsed=state.parse(0xa0,record(program.finish(),2),0,0x40000);
        ByteArrayOutputStream target=new ByteArrayOutputStream();
        try(Rar3PpmdFilterOutput output=new Rar3PpmdFilterOutput(target,2,state)) {
            output.acceptParsed(parsed); output.write(new byte[]{1,2});
            IOException failure = assertThrows(IOException.class, output::finish);
            assertSame(failure, assertThrows(IOException.class, () -> output.write(1)));
        }
        assertArrayEquals(new byte[]{1}, target.toByteArray());
    }

    @Test public void queuedCustomProgramsKeepTheirIdentityAcrossSlotResets() throws Exception {
        Code first = new Code(); first.op(0,true).memory(0).immediate(9,true); first.op(22,false);
        Code second = new Code(); second.op(0,true).memory(1).immediate(8,true); second.op(22,false);
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target,2,state)) {
            Rar3VmFilter.ProgramState.Parsed a = state.parse(0xa0,record(first.finish(),2),0,0x40000);
            output.acceptParsed(a);
            Rar3VmFilter.ProgramState.Parsed b = state.parse(0xa0,record(second.finish(),2),0,0x40000);
            output.acceptParsed(b);
            assertNotSame(a.filter.customProgram,b.filter.customProgram);
            assertEquals(1,state.programCount());
            output.write(new byte[]{1,2}); output.finish();
        }
        assertArrayEquals(new byte[]{9,8},target.toByteArray());
    }

    @Test public void resetsCannotBypassTheQueuedInstructionMetadataBudget() throws Exception {
        byte[] inner = manyNoOps(); // 4097 instructions; 31 fit, 32 exceed 131072.
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target,1,state)) {
            for (int i=0;i<31;i++) output.acceptParsed(state.parse(0xa0,record(inner,1),0,0x40000));
            Rar3VmFilter.ProgramState.Parsed overflow = state.parse(0xa0,record(inner,1),0,0x40000);
            IOException failure = assertThrows(IOException.class,()->output.acceptParsed(overflow));
            assertTrue(failure.getMessage().contains("instruction memory limit"));
            assertSame(failure,assertThrows(IOException.class,()->output.write(1)));
            assertEquals(0,target.size());
        }
    }

    @Test public void sharedProgramsAreChargedOnceAndReleasedAfterTheirLastUse() throws Exception {
        byte[] inner = manyNoOps();
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target,2,state)) {
            Rar3VmFilter.ProgramState.Parsed shared = state.parse(0xa0,record(inner,1),0,0x40000);
            for (int i=0;i<40;i++) output.acceptParsed(shared);
            output.write(42);
            // If the first group's reference count leaked, the last new program
            // would incorrectly exceed the same metadata budget.
            for (int i=0;i<31;i++) output.acceptParsed(state.parse(0xa0,record(inner,1),1,0x40000));
            output.write(43); output.finish();
        }
        assertArrayEquals(new byte[]{42,43},target.toByteArray());
    }

    @Test public void expandingThenShrinkingChainPublishesOnlyFinalResult() throws Exception {
        for (boolean bulk : new boolean[]{false, true}) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 3, new Rar3VmFilter.ProgramState())) {
                queueResize(output, 0, 3, 5, 0);
                queueResize(output, 0, 5, 3, 2);
                output.write(1); assertEquals(0, target.size());
                if (bulk) output.write(new byte[]{2, 3});
                else { output.write(2); assertEquals(0, target.size()); output.write(3); }
                output.finish();
            }
            assertArrayEquals(new byte[]{3, 0, 0}, target.toByteArray());
        }
    }

    @Test public void shrinkingThenExpandingChainKeepsRawHistory() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        Rar3PpmdSolidStreamDecoder.HistoryWindow history = new Rar3PpmdSolidStreamDecoder.HistoryWindow(8);
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 8, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 2, 0);
            queueResize(output, 0, 2, 4, 0);
            for (int value = 1; value <= 4; value++) history.literal(value, output);
            history.match(4, 4, output); output.finish();
        }
        assertArrayEquals(new byte[]{1, 2, 0, 0, 1, 2, 3, 4}, target.toByteArray());
    }

    @Test public void adjacentRegionUsesOriginalExtentAfterExpandedIntermediate() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 8, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 6, 0);
            queueResize(output, 0, 6, 4, 0);
            queueResize(output, 4, 4, 4, 0);
            output.write(new byte[]{1, 2}); assertEquals(0, target.size());
            output.write(new byte[]{3, 4, 5, 6, 7, 8}); output.finish();
        }
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, target.toByteArray());
    }

    @Test public void shrinkingIntermediateCannotHideRawOverlap() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 8, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 2, 0);
            queueResize(output, 0, 2, 4, 0);
            IOException failure = assertThrows(IOException.class, () -> queueResize(output, 3, 1, 1, 0));
            assertTrue(failure.getMessage().contains("Overlapping"));
            assertSame(failure, assertThrows(IOException.class, () -> output.write(new byte[8])));
        }
        assertEquals(0, target.size());
    }

    @Test public void chainInputMismatchFailsBeforePublishingAndStaysFailed() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 4, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 2, 0);
            queueResize(output, 0, 3, 4, 0);
            IOException failure = assertThrows(IOException.class, () -> output.write(new byte[4]));
            assertTrue(failure.getMessage().contains("chain input length"));
            assertSame(failure, assertThrows(IOException.class, output::finish));
        }
        assertEquals(0, target.size());
    }

    @Test public void netChangingChainsCheckFinalSizeInsteadOfRawSize() throws Exception {
        for (int finalLength : new int[]{0, 3, 5}) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, finalLength, new Rar3VmFilter.ProgramState())) {
                queueResize(output, 0, 4, 2, 0);
                queueResize(output, 0, 2, finalLength, 0);
                output.write(new byte[4]); output.finish();
                assertEquals(4, output.rawBytes());
                assertEquals(finalLength, output.outputBytes());
            }
            assertEquals(finalLength, target.size());
        }
    }

    @Test public void expansionBeyondDeclaredFinalSizeFailsBeforeBlockPublication() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 3, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 2, 4, 0);
            IOException failure = assertThrows(IOException.class, () -> output.write(new byte[]{1,2}));
            assertTrue(failure.getMessage().contains("final entry size"));
            assertSame(failure, assertThrows(IOException.class, output::finish));
        }
        assertEquals(0, target.size());
    }

    @Test public void resizedRegionsKeepRawHistoryAndPlainSuffix() throws Exception {
        for (boolean bulk : new boolean[]{false,true}) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 9, new Rar3VmFilter.ProgramState())) {
                queueResize(output, 0, 4, 2, 0);
                queueResize(output, 4, 2, 4, 0);
                byte[] raw = {1,2,3,4,5,6,7,8,9};
                if (bulk) output.write(raw); else for (byte value : raw) output.write(value);
                output.finish(); assertTrue(output.changedLength());
            }
            assertArrayEquals(new byte[]{1,2,5,6,0,0,7,8,9}, target.toByteArray());
        }
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        Rar3PpmdSolidStreamDecoder.HistoryWindow history = new Rar3PpmdSolidStreamDecoder.HistoryWindow(8);
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 6, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 2, 0);
            for (int value = 1; value <= 4; value++) history.literal(value, output);
            history.match(4,4,output); output.finish();
        }
        assertArrayEquals(new byte[]{1,2,1,2,3,4}, target.toByteArray());
    }

    @Test public void branchFilterOffsetUsesPublishedBytesAfterShrinking() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 8, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 2, 0);
            Rar3VmFilter.PendingFilter next = new Rar3VmFilter.PendingFilter();
            next.type = Rar3VmFilter.StandardFilter.E8; next.blockStartAbs = 4; next.fileOffset = 4;
            next.blockLength = 6; output.queue(next);
            output.write(new byte[]{1,2,3,4,(byte)0xe8,100,0,0,0,0}); output.finish();
        }
        assertArrayEquals(new byte[]{1,2,(byte)0xe8,97,0,0,0,0}, target.toByteArray());
    }

    @Test public void zeroLengthFilteredRegionDoesNotConsumeFinalOutputBudget() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 1, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 0, 0);
            output.write(new byte[]{1,2,3,4}); assertTrue(output.needsInput());
            assertEquals(4, output.rawBytes()); assertEquals(0, output.outputBytes());
            output.write(42); output.finish();
        }
        assertArrayEquals(new byte[]{42}, target.toByteArray());
    }

    static byte[] resizingRecord(int inputLength, int resultLength) {
        return record(resize(resultLength, 0).finish(), inputLength);
    }

    static byte[] deferredResizingRecord(int relativeStart, int inputLength, int resultLength) {
        byte[] inner = resize(resultLength, 0).finish();
        Code b = new Code(false);
        b.number(0); b.number(relativeStart); b.number(inputLength); b.number(inner.length);
        for (byte value : inner) b.bits(value & 255, 8);
        return b.raw();
    }

    @Test public void customVmFileOffsetUsesPublishedBytesAfterShrinking() throws Exception {
        Code code = new Code(); code.op(0,true).memory(0).register(6); code.op(22,false);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 3, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 2, 0);
            output.acceptParsed(new Rar3VmFilter.ProgramState().parse(0xa0, record(code.finish(),1), 4, 0x40000));
            output.write(new byte[]{1,2,3,4,99}); output.finish();
        }
        assertArrayEquals(new byte[]{1,2,2}, target.toByteArray());
    }

    @Test public void standardFilterConsumesResizedIntermediate() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 3, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 3, 5, 0);
            Rar3VmFilter.PendingFilter delta = new Rar3VmFilter.PendingFilter();
            delta.type = Rar3VmFilter.StandardFilter.DELTA; delta.blockLength = 5;
            delta.initR[0] = 1; delta.initR[4] = 99;
            output.queue(delta);
            queueResize(output, 0, 5, 3, 2);
            output.write(new byte[]{1, 2, 3}); output.finish();
        }
        assertArrayEquals(new byte[]{-6, -6, -6}, target.toByteArray());
    }

    @Test public void symbolCodedResizeRecordsReachTheSharedQueue() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 3, new Rar3VmFilter.ProgramState())) {
            output.write(42);
            readResizeRecord(output, 2, 4, 0);
            readResizeRecord(output, 4, 2, 1);
            output.write(new byte[]{1, 2}); output.finish();
        }
        assertArrayEquals(new byte[]{42, 2, 0}, target.toByteArray());
    }

    @Test public void nextQueueAfterCompletedChainGetsItsOwnRawExtent() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 4, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 2, 5, 0); queueResize(output, 0, 5, 2, 0);
            output.write(new byte[]{1, 2});
            queueResize(output, 2, 2, 3, 0); queueResize(output, 2, 3, 2, 0);
            output.write(new byte[]{3, 4}); output.finish();
        }
        assertArrayEquals(new byte[]{1, 2, 3, 4}, target.toByteArray());
    }

    @Test public void invalidIntermediateRangeRetiresTheWholeChain() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput output = new Rar3PpmdFilterOutput(target, 4, new Rar3VmFilter.ProgramState())) {
            queueResize(output, 0, 4, 2, 0x3ffff);
            queueResize(output, 0, 2, 4, 0);
            IOException failure = assertThrows(IOException.class, () -> output.write(new byte[4]));
            assertSame(failure, assertThrows(IOException.class, output::finish));
            assertSame(failure, assertThrows(IOException.class, () -> output.write(1)));
        }
        assertEquals(0, target.size());
    }

    private static Code resize(int length, int start) {
        Code code = new Code();
        code.op(0, false).memory(0x3c01c).immediate(length, false);
        code.op(0, false).memory(0x3c020).immediate(start, false);
        code.op(22, false); return code;
    }

    private static void queueResize(Rar3PpmdFilterOutput output, long start,
                                    int inputLength, int resultLength, int resultStart) throws Exception {
        Rar3VmFilter.ProgramState state = new Rar3VmFilter.ProgramState();
        output.acceptParsed(state.parse(0xa0,
                record(resize(resultLength, resultStart).finish(), inputLength), start, 0x40000));
    }

    private static void readResizeRecord(Rar3PpmdFilterOutput output,
                                         int inputLength, int resultLength, int resultStart) throws Exception {
        byte[] code = record(resize(resultLength, resultStart).finish(), inputLength);
        ByteArrayOutputStream symbols = new ByteArrayOutputStream();
        if (code.length <= 6) symbols.write(0xa0 | (code.length - 1));
        else if (code.length <= 262) { symbols.write(0xa6); symbols.write(code.length - 7); }
        else { symbols.write(0xa7); symbols.write(code.length >>> 8); symbols.write(code.length); }
        symbols.write(code);
        java.io.ByteArrayInputStream source = new java.io.ByteArrayInputStream(symbols.toByteArray());
        output.readRecord(source::read); assertEquals(-1, source.read());
    }

    private static byte[] manyNoOps() {
        Code code = new Code();
        for (int i=0;i<4096;i++) code.op(39,false);
        code.op(22,false);
        return code.finish();
    }

    private static byte[] record(byte[] inner,int length) {
        Code b=new Code(false); b.number(0); b.number(0); b.number(length); b.number(inner.length);
        for(byte value:inner) b.bits(value&255,8);
        return b.raw();
    }
    private static byte[] apply(Code code,byte[] input,byte[] global,long offset)throws Exception {
        return Rar3VirtualMachine.apply(Rar3VirtualMachine.compile(code.finish()),input,input.length,registers(input.length),global,offset);
    }
    private static int[] registers(int length) { int[] r=new int[7];r[3]=0x3c000;r[4]=length;return r; }
    private static final class Code {
        final ByteArrayOutputStream data=new ByteArrayOutputStream(); int value,used;
        Code(){this(true);} Code(boolean header){if(header)bits(0,1);}
        Code op(int op,boolean small){
            if(op<8)bits(op,4);else bits(op+24,6);
            if(op<=3||op==6||op==7||(op>=9&&op<=12)||(op>=23&&op<=27)||(op>=34&&op<=38))bits(small?1:0,1);
            return this;
        }
        Code memory(int address){bits(7,4);number(address);return this;}
        Code register(int index){bits(1,1);bits(index,3);return this;}
        Code immediate(int n,boolean small){bits(0,2);if(small)bits(n,8);else number(n);return this;}
        void number(int n){if(n>=0&&n<16){bits(0,2);bits(n,4);}else if(n>=16&&n<256){bits(1,2);bits(n,8);}
            else if(n>=0&&n<65536){bits(2,2);bits(n,16);}else{bits(3,2);bits(n,32);}}
        void bits(int n,int count){for(int i=count-1;i>=0;i--){value=(value<<1)|((n>>>i)&1);if(++used==8){data.write(value);value=used=0;}}}
        byte[] raw(){if(used!=0){data.write(value<<(8-used));value=used=0;}return data.toByteArray();}
        byte[] finish(){byte[] body=raw(),out=new byte[body.length+1];for(int i=0;i<body.length;i++){out[0]^=body[i];out[i+1]=body[i];}return out;}
    }
}
