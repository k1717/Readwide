package com.readwide.codecs;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Test;

/** Android/JNI regression sources; synthetic frames do not establish producer coverage. */
public class LzFamilyInputStreamTest {
    @Test public void inputCallbacksCannotCloseOrReadTheActiveDecoder() throws Exception {
        for(boolean lizard:new boolean[]{false,true})for(boolean close:new boolean[]{false,true}) {
            InputStream[] owner={null};
            InputStream source=new ByteArrayInputStream(frame(lizard,new byte[]{1})) {
                @Override public synchronized int read(byte[] b,int offset,int count) {
                    try {if(close)owner[0].close();else owner[0].read();}
                    catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
                    return super.read(b,offset,count);
                }
            };
            owner[0]=LzFamilyInputStream.open(source,lizard,1);
            try {
                java.io.UncheckedIOException failure=assertThrows(java.io.UncheckedIOException.class,owner[0]::read);
                assertSame(failure,assertThrows(java.io.UncheckedIOException.class,owner[0]::read));
            } finally {owner[0].close();}
        }
    }

    @Test public void cancellationDuringSourceReadCannotPublishPayload() throws Exception {
        for(boolean lizard:new boolean[]{false,true}) {
            InputStream source=new ByteArrayInputStream(frame(lizard,new byte[]{1})) {
                @Override public synchronized int read(byte[] b,int offset,int count) {
                    int result=super.read(b,offset,count);Thread.currentThread().interrupt();return result;
                }
            };
            try(InputStream input=LzFamilyInputStream.open(source,lizard,1)) {
                byte[] target={9};
                try {
                    IOException failure=assertThrows(java.io.InterruptedIOException.class,()->input.read(target));
                    assertEquals(9,target[0]);Thread.interrupted();
                    assertSame(failure,assertThrows(IOException.class,input::read));
                } finally {Thread.interrupted();}
            }
        }
    }

    @Test public void sourceCleanupReentryFailsAndCanRetry() throws Exception {
        for(boolean lizard:new boolean[]{false,true}) {
            InputStream[] owner={null};int[] closes={0};
            InputStream source=new ByteArrayInputStream(frame(lizard,new byte[0])) {
                @Override public void close()throws IOException{if(++closes[0]==1)owner[0].close();}
            };
            owner[0]=LzFamilyInputStream.open(source,lizard,0);
            IOException failure=assertThrows(IOException.class,owner[0]::close);
            assertTrue(failure.getMessage().contains("callback"));
            assertThrows(IOException.class,owner[0]::read);
            owner[0].close();owner[0].close();assertEquals(2,closes[0]);
        }
    }

    @Test public void linkedLz5MatchCanReachBeyond64KiB() throws Exception {
        assertLinkedLz5(90000,70000);
    }
    @Test public void linkedLz5KeepsWindowAcrossHistoryCompaction() throws Exception {
        assertLinkedLz5(9*1024*1024+17,4*1024*1024-1);
    }
    @Test public void malformedLz5OffsetsRejectEvenWithFullDictionary() throws Exception {
        byte[] history=new byte[4*1024*1024];
        for(int distance:new int[]{0,4*1024*1024,0xffffff}) {
            byte[] packed=lz5Linked(history,distance);
            assertThrows(IOException.class,()->decode(packed,false,history.length+14));
        }
    }
    @Test public void truncatedLz5LiteralLengthIsRejected() throws Exception {
        for(int token:new int[]{0x38,0x58}) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();header(out,false,0x60);
            little(out,1);out.write(token);little(out,0);
            byte[] packed=out.toByteArray();
            assertThrows(IOException.class,()->decode(packed,false,16));
        }
    }
    @Test public void skipsBoundedMetadataBetweenStandardAndMtFrames() throws Exception {
        byte[] padding=new byte[70000];Arrays.fill(padding,(byte)0x7f);
        for(boolean lizard:new boolean[]{false,true}) {
            byte[] stream=concat(skippable(0x184d2a51,padding),frame(lizard,new byte[]{1}));
            stream=concat(stream,skippable(0x184d2a5f,new byte[0]));
            stream=concat(stream,mt(frame(lizard,new byte[]{2})));
            stream=concat(stream,skippable(0x184d2a52,new byte[]{3,4}));
            assertArrayEquals(new byte[]{1,2},decode(stream,lizard,2));
        }
    }
    @Test public void metadataCannotReplaceDataOrHideTruncation() throws Exception {
        byte[] only=skippable(0x184d2a51,new byte[0]);
        assertThrows(IOException.class,()->decode(only,false,0));
        byte[] bad=skippable(0x184d2a51,new byte[]{1,2});bad[4]=3;
        assertThrows(IOException.class,()->decode(bad,false,0));
        byte[] huge=only.clone();Arrays.fill(huge,4,8,(byte)0xff);
        assertThrows(IOException.class,()->decode(huge,false,0));
        byte[] invalidMt=mt(frame(false,new byte[0]));invalidMt[4]=8;
        assertThrows(IOException.class,()->decode(invalidMt,false,0));
    }
    @Test public void uncheckedInputFailureRemainsTerminalAfterNativeInitialization() throws Exception {
        RuntimeException sentinel=new IllegalStateException("source failed");
        byte[] packed=frame(false,new byte[100]);
        InputStream source=new ByteArrayInputStream(packed) {
            int calls;
            @Override public synchronized int read(byte[] b,int off,int len) {
                if(calls++>0)throw sentinel;
                return super.read(b,off,Math.min(len,8));
            }
        };
        try(InputStream decoded=LzFamilyInputStream.open(source,false,100)) {
            assertSame(sentinel,assertThrows(IllegalStateException.class,decoded::read));
            assertSame(sentinel,assertThrows(IllegalStateException.class,decoded::read));
        }
    }
    @Test public void failedSourceCloseCanBeRetriedWithoutReopeningDecoder() throws Exception {
        final int[] closes={0};
        IOException sentinel=new IOException("retry close");
        InputStream source=new ByteArrayInputStream(new byte[0]) {
            @Override public void close()throws IOException { if(++closes[0]==1)throw sentinel; }
        };
        InputStream decoded=LzFamilyInputStream.open(source,false,0);
        assertSame(sentinel,assertThrows(IOException.class,decoded::close));
        assertThrows(IOException.class,decoded::read);
        decoded.close();decoded.close();assertEquals(2,closes[0]);
    }

    @Test public void storedFrameForBothCodecs() throws Exception {
        for(boolean lizard:new boolean[]{false,true}) {
            byte[] data={1,2,3,4,5};
            assertArrayEquals(data,decode(frame(lizard,data),lizard,data.length));
        }
    }
    @Test public void outputLargerThanIoBufferAndOneByteInputReads() throws Exception {
        byte[] data=new byte[60000]; for(int i=0;i<data.length;i++)data[i]=(byte)i;
        for(boolean lizard:new boolean[]{false,true}) {
            byte[] stream=concat(frame(lizard,data),frame(lizard,data));
            InputStream slow=new ByteArrayInputStream(stream){
                @Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(len,1));}
            };
            try(InputStream decoded=LzFamilyInputStream.open(slow,lizard,120000)) {
                assertArrayEquals(concat(data,data),readAll(decoded));
            }
        }
    }
    @Test public void boundedMtFramesAreDecodedInOrder() throws Exception {
        for(boolean lizard:new boolean[]{false,true}) {
            byte[] first=frame(lizard,new byte[]{1,2}),second=frame(lizard,new byte[]{3});
            assertArrayEquals(new byte[]{1,2,3},decode(concat(mt(first),mt(second)),lizard,3));
        }
    }
    @Test public void checksumMismatchIsRejected() throws Exception {
        for(boolean lizard:new boolean[]{false,true}) {
            byte[] data=frame(lizard,new byte[]{1,2});data[6]^=1;
            assertThrows(IOException.class,()->decode(data,lizard,2));
        }
    }
    @Test public void truncatedFrameCannotSucceedAtExpectedOutputLength() throws Exception {
        for(boolean lizard:new boolean[]{false,true}) {
            byte[] valid=frame(lizard,new byte[]{1,2});byte[] truncated=Arrays.copyOf(valid,valid.length-1);
            assertThrows(IOException.class,()->decode(truncated,lizard,2));
        }
    }
    @Test public void wrongMtPackedLengthAndTrailingBytesAreRejected() throws Exception {
        byte[] valid=frame(false,new byte[]{1,2});byte[] wrapper=mt(valid);wrapper[8]++;
        assertThrows(IOException.class,()->decode(wrapper,false,2));
        assertThrows(IOException.class,()->decode(concat(valid,new byte[]{1}),false,2));
    }
    @Test public void declaredSizeOverrunAndUnderrunAreRejected() throws Exception {
        byte[] valid=frame(false,new byte[]{1,2});
        assertThrows(IOException.class,()->decode(valid,false,1));
        assertThrows(IOException.class,()->decode(valid,false,3));
    }
    @Test public void zeroProgressInputFailsAndCloseIsIdempotent() throws Exception {
        InputStream zero=new InputStream(){public int read(){return 0;}
            public int read(byte[] b,int o,int n){return 0;}};
        InputStream decoded=LzFamilyInputStream.open(zero,false,0);
        assertThrows(IOException.class,decoded::read); decoded.close(); decoded.close();
        assertThrows(IOException.class,decoded::read);
    }
    @Test public void emptyValidFrameButNotMissingPayloadIsAccepted() throws Exception {
        for(boolean lizard:new boolean[]{false,true}) {
            assertArrayEquals(new byte[0],decode(frame(lizard,new byte[0]),lizard,0));
            assertThrows(IOException.class,()->decode(new byte[0],lizard,0));
        }
    }
    @Test public void cancellationBeforeOpenClosesInput() {
        final boolean[] closed={false};
        InputStream source=new ByteArrayInputStream(new byte[0]){public void close(){closed[0]=true;}};
        Thread.currentThread().interrupt();
        try {assertThrows(IOException.class,()->LzFamilyInputStream.open(source,false,0));}
        finally {Thread.interrupted();}
        assertTrue(closed[0]);
    }
    private static byte[] decode(byte[] packed,boolean lizard,long size)throws Exception {
        try(InputStream input=LzFamilyInputStream.open(new ByteArrayInputStream(packed),lizard,size)){return readAll(input);}
    }
    private static byte[] readAll(InputStream input)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[257];int n;
        while((n=input.read(bytes))!=-1){assertTrue(n>0);out.write(bytes,0,n);}return out.toByteArray();
    }
    private static byte[] frame(boolean lizard,byte[] bytes)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();header(out,lizard,0x60);
        if(bytes.length>0){little(out,0x80000000|bytes.length);out.write(bytes);}
        little(out,0);return out.toByteArray();
    }
    private static void header(ByteArrayOutputStream out,boolean lizard,int flags) {
        little(out,lizard?0x184d2206:0x184d2205);out.write(flags);out.write(0x10);
        int h=0x165667b1+2;
        h=Integer.rotateLeft(h+flags*0x165667b1,11)*0x9e3779b1;
        h=Integer.rotateLeft(h+0x10*0x165667b1,11)*0x9e3779b1;
        h^=h>>>15;h*=0x85ebca77;h^=h>>>13;h*=0xc2b2ae3d;h^=h>>>16;
        out.write((h>>>8)&255);
    }
    private static byte[] skippable(int magic,byte[] data)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();little(out,magic);little(out,data.length);
        out.write(data);return out.toByteArray();
    }
    private static void assertLinkedLz5(int historySize,int distance)throws Exception {
        byte[] history=new byte[historySize];
        for(int i=0;i<history.length;i++)history[i]=(byte)(i*37+(i>>>8)*13);
        byte[] expected=Arrays.copyOf(history,history.length+14);
        System.arraycopy(history,history.length-distance,expected,history.length,8);
        System.arraycopy(new byte[]{9,8,7,6,5,4},0,expected,history.length+8,6);
        assertArrayEquals(expected,decode(lz5Linked(history,distance),false,expected.length));
    }
    /** Hand-encoded LZ5: stored history, one 24-bit-distance match of 8 bytes, 6 final literals. */
    private static byte[] lz5Linked(byte[] history,int distance)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();header(out,false,0x40);
        for(int offset=0;offset<history.length;) {
            int length=Math.min(65536,history.length-offset);
            little(out,0x80000000|length);out.write(history,offset,length);offset+=length;
        }
        byte[] match={0x45,(byte)distance,(byte)(distance>>>8),(byte)(distance>>>16),
                0x30,9,8,7,6,5,4};
        little(out,match.length);out.write(match);little(out,0);return out.toByteArray();
    }
    private static byte[] mt(byte[] frame)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();little(out,0x184d2a50);little(out,4);little(out,frame.length);out.write(frame);return out.toByteArray();
    }
    private static void little(ByteArrayOutputStream out,int n){for(int i=0;i<4;i++)out.write((n>>>(8*i))&255);}
    private static byte[] concat(byte[] a,byte[] b){byte[] out=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,out,a.length,b.length);return out;}
}
