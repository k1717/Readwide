package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.EOFException;

import org.junit.Test;

public class RarBitInputTest {
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
}
