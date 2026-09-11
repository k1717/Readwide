package com.readwide.manager.archive;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Tests the symbol-record/output boundary, not a new compressed PPMd fixture. */
public class Rar3PpmdFilterOutputTest {
    @Test public void mixedSinkPreservesClassicRegionsLargerThanPpmdOnlyGuard() throws Exception {
        int length = Rar3PpmdFilterOutput.MAX_FILTER_BYTES + 1;
        byte[] raw = new byte[length]; Arrays.fill(raw,(byte)1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Rar3VmFilter.PendingFilter descriptor = filter(Rar3VmFilter.StandardFilter.DELTA,0,length,1);
        try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(bytes,length,
                new Rar3VmFilter.ProgramState(),4 * 1024 * 1024)) {
            out.queue(descriptor); out.write(raw); out.finish();
        }
        assertArrayEquals(Rar3VmFilter.apply(descriptor.type,raw,length,descriptor.initR,0),bytes.toByteArray());
    }

    private Rar3PpmdFilterOutput output(OutputStream out, long size) {
        return new Rar3PpmdFilterOutput(out,size,new Rar3VmFilter.ProgramState());
    }

    @Test public void deltaWaitsForWholeBlockAndHistoryKeepsUnfilteredBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Rar3PpmdSolidStreamDecoder.HistoryWindow history = new Rar3PpmdSolidStreamDecoder.HistoryWindow(8);
        try (Rar3PpmdFilterOutput out = output(bytes,6)) {
            out.queue(filter(Rar3VmFilter.StandardFilter.DELTA,0,3,1));
            history.literal(1,out); history.literal(2,out);
            assertEquals(0,bytes.size());
            history.literal(3,out);
            assertArrayEquals(new byte[]{-1,-3,-6},bytes.toByteArray());
            history.match(3,3,out);
            out.finish();
            assertArrayEquals(new byte[]{-1,-3,-6,1,2,3},bytes.toByteArray());
        }
    }

    @Test public void identicalBlockFiltersChainBeforePublication() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(bytes,3)) {
            out.queue(filter(Rar3VmFilter.StandardFilter.DELTA,0,3,1));
            out.queue(filter(Rar3VmFilter.StandardFilter.DELTA,0,3,1));
            out.write(new byte[]{1,2,3}); out.finish();
            assertArrayEquals(new byte[]{1,4,10},bytes.toByteArray());
        }
    }

    @Test public void allSixFiltersMatchExistingPrimitivesAtTheirFileOffset() throws Exception {
        byte[] raw = {(byte)0xe8,100,0,0,0,(byte)0xe9,100,0,0,0,1,2,3,4,5,6,7,8};
        for (Rar3VmFilter.StandardFilter type : Rar3VmFilter.StandardFilter.values()) {
            if (type == Rar3VmFilter.StandardFilter.NONE) continue;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Rar3VmFilter.PendingFilter filter = filter(type,2,raw.length,type == Rar3VmFilter.StandardFilter.RGB ? 6 : 2);
            byte[] expected = Rar3VmFilter.apply(type,raw.clone(),raw.length,filter.initR.clone(),2);
            try (Rar3PpmdFilterOutput out = output(bytes,raw.length+2)) {
                out.queue(filter); out.write(new byte[]{42,43}); out.write(raw); out.finish();
                assertArrayEquals(expected,Arrays.copyOfRange(bytes.toByteArray(),2,bytes.size()));
            }
        }
    }

    @Test public void symbolRecordsReuseProgramsAcrossEntriesAndAllLengthEncodings() throws Exception {
        Rar3VmFilter.ProgramState programs = new Rar3VmFilter.ProgramState();
        // First record defines E8; later records reuse its saved block length.
        for (int entry=0;entry<3;entry++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (Rar3PpmdFilterOutput out = new Rar3PpmdFilterOutput(bytes,6,programs)) {
                byte[] record = record(entry == 0,entry == 2 ? 300 : 0);
                ByteArrayInputStream symbols = new ByteArrayInputStream(record);
                out.readRecord(symbols::read);
                assertEquals(-1,symbols.read());
                out.write(new byte[]{(byte)0xe8,100,0,0,0,0}); out.finish();
                assertArrayEquals(new byte[]{(byte)0xe8,99,0,0,0,0},bytes.toByteArray());
            }
            assertEquals(1,programs.programCount());
        }
    }

    @Test public void partialOverlapCrossEntryAndExcessivePendingFiltersFail() throws Exception {
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),10)) {
            out.queue(filter(Rar3VmFilter.StandardFilter.E8,1,6,0));
            try { out.queue(filter(Rar3VmFilter.StandardFilter.E8,2,6,0)); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("Overlapping")); }
        }
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),10)) {
            try { out.queue(filter(Rar3VmFilter.StandardFilter.E8,9,6,0)); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("cross-entry")); }
        }
        try (Rar3PpmdFilterOutput out = output(new ByteArrayOutputStream(),6)) {
            for(int i=0;i<1024;i++) out.queue(filter(Rar3VmFilter.StandardFilter.E8,0,6,0));
            try { out.queue(filter(Rar3VmFilter.StandardFilter.E8,0,6,0)); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("memory limit")); }
        }
    }

    @Test public void incompleteBlockAndTruncatedRecordNeverPublishHeldBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(bytes,6)) {
            out.queue(filter(Rar3VmFilter.StandardFilter.E8,0,6,0)); out.write(1);
            try { out.finish(); fail(); }
            catch (IOException expected) { assertEquals(0,bytes.size()); }
            try { out.write(2); fail("Failed output must remain failed"); }
            catch (IOException expected) { assertEquals(0,bytes.size()); }
        }
        try (Rar3PpmdFilterOutput out = output(bytes,6)) {
            ByteArrayInputStream symbols = new ByteArrayInputStream(new byte[]{7,0,6,1});
            try { out.readRecord(symbols::read); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("Truncated")); }
        }
    }

    @Test public void pendingResetFailsAndQueuedRegistersAreOwned() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(Rar3PpmdFilterOutput out=output(bytes,3)) {
            Rar3VmFilter.PendingFilter pending=filter(Rar3VmFilter.StandardFilter.DELTA,0,3,1);
            out.queue(pending); pending.initR[0]=3;
            out.write(new byte[]{1,2,3});out.finish();
            assertArrayEquals(new byte[]{-1,-3,-6},bytes.toByteArray());
        }
        bytes.reset();
        try(Rar3PpmdFilterOutput out=output(bytes,6)) {
            out.queue(filter(Rar3VmFilter.StandardFilter.E8,0,6,0));
            ByteArrayInputStream reset=new ByteArrayInputStream(record(true,0));
            try {out.readRecord(reset::read);fail("A reset must not silently discard pending transformations");}
            catch(IOException expected) {assertTrue(expected.getMessage().contains("reset with pending"));}
            assertEquals(0,bytes.size());
        }
    }

    @Test public void outputFailureCannotResumeOrCloseTheCallerDestination() throws Exception {
        IOException sentinel = new IOException("destination failure");
        OutputStream target = new OutputStream() {
            @Override public void write(int value) throws IOException { throw sentinel; }
            @Override public void close() { fail("Caller owns destination"); }
        };
        try (Rar3PpmdFilterOutput out = output(target,6)) {
            out.queue(filter(Rar3VmFilter.StandardFilter.E8,0,6,0));
            try { out.write(new byte[6]); fail(); } catch (IOException expected) { assertSame(sentinel,expected); }
            try { out.finish(); fail(); } catch (IOException expected) { assertSame(sentinel,expected); }
        }
    }

    @Test public void longFileSizesDoNotAllocateAWholeEntryAndCancellationAborts() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Rar3PpmdFilterOutput out = output(bytes,(long)Integer.MAX_VALUE+1)) {
            out.queue(filter(Rar3VmFilter.StandardFilter.E8,Integer.MAX_VALUE-8L,6,0));
            out.write(42); assertEquals(1,bytes.size());
            Thread.currentThread().interrupt();
            try { out.write(43); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
            finally { Thread.interrupted(); }
        }
    }

    private static Rar3VmFilter.PendingFilter filter(Rar3VmFilter.StandardFilter type,long start,int length,int parameter) {
        Rar3VmFilter.PendingFilter filter = new Rar3VmFilter.PendingFilter();
        filter.type=type; filter.blockStartAbs=start; filter.fileOffset=start; filter.blockLength=length;
        filter.initR[0]=parameter; filter.initR[4]=length;
        return filter;
    }

    private static byte[] record(boolean define,int globals) {
        Bits bits = new Bits(); bits.data(define ? 0 : 1); bits.data(0);
        if(define) {
            bits.data(6); bits.data(53);
            byte[] program=new byte[53];
            byte[] tail={(byte)0xfe,0x53,(byte)0xaa,(byte)0xa9,(byte)0xae};
            System.arraycopy(tail,0,program,48,5);
            for(byte value:program) bits.write(value&255,8);
        }
        if(globals>0) {bits.data(globals);for(int i=0;i<globals;i++)bits.write(0,8);}
        byte[] code=bits.bytes();
        int flags=0x80 | (define ? 0x20 : 0) | (globals>0 ? 8 : 0);
        ByteArrayOutputStream transport=new ByteArrayOutputStream();
        if(code.length<=6) transport.write(flags | (code.length-1));
        else if(code.length<=262) {transport.write(flags|6);transport.write(code.length-7);}
        else {transport.write(flags|7);transport.write(code.length>>>8);transport.write(code.length&255);}
        transport.write(code,0,code.length); return transport.toByteArray();
    }

    private static final class Bits {
        final ByteArrayOutputStream out=new ByteArrayOutputStream(); int current,count;
        void data(int value) {
            if(value<16) write(value,6);
            else if(value<256) {write(1,2);write(value,8);}
            else {write(2,2);write(value,16);}
        }
        void write(int value,int n) {
            for(int i=n-1;i>=0;i--) {
                current=(current<<1)|((value>>>i)&1);
                if(++count==8) {out.write(current);current=0;count=0;}
            }
        }
        byte[] bytes() {if(count>0) {out.write(current<<(8-count));count=0;}return out.toByteArray();}
    }
}
