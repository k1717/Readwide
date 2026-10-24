package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.EOFException;

import org.junit.Test;

public class RarBitInputTest {
    @Test public void optionalLookaheadRetainsShortCleanTailWithoutConsumingOrPadding() throws Exception {
        RarBitInput input = new RarBitInput(new java.io.ByteArrayInputStream(new byte[]{5}));
        input.skipBits(5);
        assertFalse(input.hasBits(8));
        assertEquals(5, input.bitsRead()); assertEquals(3, input.remainingBits());
        assertTrue(input.hasBits(3)); assertEquals(5, input.readBits(3));
        assertFalse(input.hasBits(1)); assertTrue(input.hasBits(0));
        assertThrows(EOFException.class, input::readBit);
    }

    @Test public void optionalLookaheadDoesNotHidePhysicalOrDeclaredTruncation() throws Exception {
        RarBitInput declared = new RarBitInput(new java.io.ByteArrayInputStream(new byte[]{0}), 2);
        declared.skipBits(7);
        EOFException truncated = assertThrows(EOFException.class, () -> declared.hasBits(8));
        assertSame(truncated, assertThrows(EOFException.class, () -> declared.hasBits(1)));
        EOFException physical = new EOFException("volume truncated");
        RarBitInput unknown = new RarBitInput(new java.io.InputStream() {
            int calls;
            @Override public int read() throws java.io.IOException {
                if (calls++ == 0) return 0;
                throw physical;
            }
        });
        unknown.skipBits(7);
        assertSame(physical, assertThrows(EOFException.class, () -> unknown.hasBits(8)));
        assertSame(physical, assertThrows(EOFException.class, unknown::readBit));
    }

    @Test public void resumedBoundedStreamDiscoversEofWithoutOwningOrPaddingInput() throws Exception {
        java.io.ByteArrayInputStream source = new java.io.ByteArrayInputStream(new byte[]{42,7}) {
            @Override public void close() { fail("Caller owns payload"); }
        };
        RarBitInput input = new RarBitInput(source);
        assertEquals(42,input.peekBits(8));
        java.io.InputStream bytes = input.alignedBytes();
        byte[] out = new byte[4]; assertEquals(2,bytes.read(out));
        assertArrayEquals(new byte[]{42,7,0,0},out);
        assertEquals(0,input.remainingBits()); assertEquals(16,input.bitsRead());
        assertEquals(-1,bytes.read()); assertEquals(-1,bytes.read(out)); bytes.close();
        assertThrows(EOFException.class,input::readBit);
    }

    @Test public void resumedStreamFailureIsNotOrdinaryEofAndCannotResume() throws Exception {
        java.io.IOException failure = new java.io.IOException("physical read failed");
        RarBitInput input = new RarBitInput(new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw failure; }
        });
        assertSame(failure,assertThrows(java.io.IOException.class,() -> input.alignedBytes().read()));
        assertSame(failure,assertThrows(java.io.IOException.class,input::readBit));
    }

    @Test
    public void readBits_crossesByteBoundary() throws Exception {
        RarBitInput input = new RarBitInput(new byte[] {(byte) 0b1010_1100, (byte) 0b0111_0000});

        assertEquals(0b101, input.readBits(3));
        assertEquals(3L, input.bitsRead());
        assertEquals(0b01100_011, input.readBits(8));
        assertEquals(5, input.remainingBits());
    }

    @Test
    public void peekBits_doesNotConsume() throws Exception {
        RarBitInput input = new RarBitInput(new byte[] {(byte) 0b1110_0000});

        assertEquals(0b111, input.peekBits(3));
        assertEquals(0L, input.bitsRead());
        assertEquals(0b111, input.readBits(3));
    }

    @Test(expected = EOFException.class)
    public void readBits_pastEndThrows() throws Exception {
        new RarBitInput(new byte[] {0}).readBits(9);
    }

    @Test public void arrayAndStreamMatchIndependentMsbReadsAtEveryAlignment() throws Exception {
        byte[] data=new byte[64];new java.util.Random(17018).nextBytes(data);
        for(int start=0;start<8;start++) for(int width=0;width<=24;width++) {
            for(boolean streamed:new boolean[]{false,true}) {
                RarBitInput input=streamed
                        ? new RarBitInput(new java.io.ByteArrayInputStream(data),data.length)
                        : new RarBitInput(data);
                input.skipBits(start);
                int expected=0;
                for(int bit=start;bit<start+width;bit++) expected=(expected<<1)|((data[bit/8]>>>(7-bit%8))&1);
                assertEquals(expected,input.peekBits(width));assertEquals(start,input.bitsRead());
                assertEquals(expected,input.readBits(width));assertEquals(start+width,input.bitsRead());
                input.alignToByte();assertEquals(((start+width+7)/8)*8,input.bitsRead());
            }
        }
    }

    @Test public void alignedByteViewPreservesPeekedBytesAndDoesNotOwnTheSource() throws Exception {
        java.io.ByteArrayInputStream source=new java.io.ByteArrayInputStream(new byte[]{(byte)0xab,0x12,0x34,0x56,0x78}) {
            @Override public void close() {fail("Bit reader must not close its source");}
        };
        RarBitInput input=new RarBitInput(source,4);
        assertEquals(0xab1234,input.peekBits(24));assertEquals(0xa,input.readBits(4));
        try {input.alignedBytes();fail("Alignment must be explicit");} catch(java.io.IOException expected) {}
        input.alignToByte();
        java.io.InputStream bytes=input.alignedBytes();
        assertEquals(0x12,bytes.read());
        assertEquals(3,input.readBits(4));
        try {bytes.read();fail("A retained byte view must recheck alignment");} catch(java.io.IOException expected) {}
        assertEquals(4,input.readBits(4));assertEquals(0x56,bytes.read());assertEquals(-1,bytes.read());
        bytes.close();assertEquals(0x78,source.read()); // Exact bound, not physical EOF.
    }

    @Test public void declaredEofDoesNotConsumeBitsAndPhysicalFailureCannotResume() throws Exception {
        RarBitInput array=new RarBitInput(new byte[]{42});
        try {array.peekBits(9);fail();} catch(EOFException expected) {assertEquals(0,array.bitsRead());}
        assertEquals(42,array.readBits(8));
        java.io.IOException sentinel=new java.io.IOException("source failed");
        RarBitInput stream=new RarBitInput(new java.io.InputStream() {
            int reads;
            @Override public int read() throws java.io.IOException {if(reads++==0)return 42;throw sentinel;}
        },2);
        try {stream.peekBits(16);fail();} catch(java.io.IOException expected) {assertSame(sentinel,expected);}
        assertEquals(0,stream.bitsRead());
        try {stream.readBits(8);fail();} catch(java.io.IOException expected) {assertSame(sentinel,expected);}
    }

    @Test public void bulkByteViewDoesNotSwallowFailureAfterPartialRead() throws Exception {
        java.io.IOException sentinel=new java.io.IOException("second byte failed");
        RarBitInput input=new RarBitInput(new java.io.InputStream() {
            int read;
            @Override public int read() throws java.io.IOException {if(read++==0)return 7;throw sentinel;}
        },2);
        try {input.alignedBytes().read(new byte[2]);fail();}
        catch(java.io.IOException expected) {assertSame(sentinel,expected);}
    }

    @Test public void longDeclaredInputHasNo256MiBBitCounterOverflow() throws Exception {
        long packed=(long)Integer.MAX_VALUE+17;
        RarBitInput input=new RarBitInput(new java.io.ByteArrayInputStream(new byte[]{7}),packed);
        assertEquals(packed*8,input.remainingBits());
        assertEquals(7,input.readBits(8));assertEquals(packed*8-8,input.remainingBits());
        try {input.readBit();fail();} catch(EOFException expected) {assertEquals(8,input.bitsRead());}
    }

    @Test public void interruptionFailsBeforeFetchingPayload() throws Exception {
        RarBitInput input=new RarBitInput(new java.io.InputStream() {
            @Override public int read() {fail("Cancelled input must not read");return 0;}
        },1);
        Thread.currentThread().interrupt();
        try {input.readBit();fail();}
        catch(java.io.IOException expected) {assertTrue(expected.getMessage().contains("cancelled"));}
        finally {Thread.interrupted();}
    }

    @Test public void uncheckedFailureAfterPartialPeekIsTerminal() throws Exception {
        for (Throwable failure : new Throwable[]{new IllegalStateException("read"), new AssertionError("read")}) {
            int[] reads = {0};
            RarBitInput input = new RarBitInput(new java.io.InputStream() {
                @Override public int read() {
                    if (++reads[0] == 1) return 42;
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    throw (Error) failure;
                }
            }, 2);
            assertSame(failure, assertThrows(Throwable.class, () -> input.peekBits(16)));
            assertEquals(0, input.bitsRead());
            assertSame(failure, assertThrows(Throwable.class, () -> input.readBits(8)));
            assertSame(failure, assertThrows(Throwable.class, () -> input.alignedBytes().read()));
            assertEquals(2, reads[0]);
        }
    }

    @Test public void invalidScalarByteCannotContaminateReservoir() {
        RarBitInput input = new RarBitInput(new java.io.InputStream() {
            @Override public int read() { return 256; }
        }, 1);
        java.io.IOException failure = assertThrows(java.io.IOException.class, () -> input.readBits(8));
        assertTrue(failure.getMessage().contains("source byte"));
        assertEquals(0, input.bitsRead());
        assertEquals(8, input.remainingBits());
        assertSame(failure, assertThrows(java.io.IOException.class, () -> input.readBits(1)));
    }

    @Test public void cancellationRemainsTypedAfterThreadFlagIsCleared() throws Exception {
        RarBitInput input = new RarBitInput(new byte[]{1});
        Thread.currentThread().interrupt();
        java.io.InterruptedIOException failure;
        try { failure = assertThrows(java.io.InterruptedIOException.class, input::readBit); }
        finally { Thread.interrupted(); }
        assertSame(failure, assertThrows(java.io.InterruptedIOException.class, input::readBit));
        assertSame(failure, assertThrows(java.io.InterruptedIOException.class,
                () -> ArchiveCancellation.rethrowIfCancelled(new java.io.IOException("wrapped", failure), null)));
    }

    @Test public void interruptionMidPeekRetiresAlreadyFetchedBits() {
        int[] reads = {0};
        RarBitInput input = new RarBitInput(new java.io.InputStream() {
            @Override public int read() { reads[0]++; Thread.currentThread().interrupt(); return 42; }
        }, 2);
        java.io.InterruptedIOException failure;
        try { failure = assertThrows(java.io.InterruptedIOException.class, () -> input.peekBits(16)); }
        finally { Thread.interrupted(); }
        assertEquals(1, reads[0]);
        assertEquals(0, input.bitsRead());
        assertSame(failure, assertThrows(java.io.InterruptedIOException.class, () -> input.readBits(8)));
    }
}
