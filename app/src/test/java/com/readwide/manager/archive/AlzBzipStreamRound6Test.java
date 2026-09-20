package com.readwide.manager.archive;

import org.junit.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class AlzBzipStreamRound6Test {
    private byte[] packed(){return Base64.getDecoder().decode(Round4Fixtures.ALZ_BZ_SINGLE_BLOCK_B64);}
    private byte[] plain(){return "ALZ bzip2 single block payload. ".repeat(40).getBytes(java.nio.charset.StandardCharsets.US_ASCII);}
    private InputStream open()throws Exception{return new AlzBzip2InputStream(new ByteArrayInputStream(packed()));}
    @Test public void positiveArgumentOverflowRejectedWithoutConsumption()throws Exception{
        try(InputStream in=open()){
            assertThrows(IndexOutOfBoundsException.class,()->in.read(new byte[2],1,Integer.MAX_VALUE));
            assertArrayEquals(plain(),in.readAllBytes());
        }
    }
    @Test public void invalidArgumentsNeverConsumeStream()throws Exception{
        try(InputStream in=open()){
            assertThrows(NullPointerException.class,()->in.read(null,0,1));
            assertThrows(IndexOutOfBoundsException.class,()->in.read(new byte[3],-1,1));
            assertThrows(IndexOutOfBoundsException.class,()->in.read(new byte[3],4,0));
            assertArrayEquals(plain(),in.readAllBytes());
        }
    }
    @Test public void cancellationBeforeFirstHeaderRead()throws Exception{
        try {Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->open());}
        finally{Thread.interrupted();}
    }
    @Test public void cancellationRemainsTerminalAfterInterruptIsCleared()throws Exception{
        try(InputStream in=open()){
            Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->in.read());Thread.interrupted();
            assertThrows(IOException.class,()->in.read());
        }finally{Thread.interrupted();}
    }
    @Test public void bulkReadObservesCancellation()throws Exception{
        try(InputStream in=open()){
            Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->in.read(new byte[100]));
        }finally{Thread.interrupted();}
    }
    @Test public void badNextBlockDoesNotTurnIntoEofOnRetry()throws Exception{
        byte[] one=packed();
        // Mutate the actual end framing (bit-aligned) by searching deterministic last-byte variants.
        boolean exercised=false;
        for(int bit=0;bit<8&&!exercised;bit++){
            byte[] bad=one.clone();bad[bad.length-1]^=1<<bit;
            try(InputStream in=new AlzBzip2InputStream(new ByteArrayInputStream(bad))){
                try{in.readAllBytes();}catch(IOException error){exercised=true;assertThrows(IOException.class,()->in.read());}
            }
        }
        assertTrue("must exercise a damaged end marker",exercised);
    }
    @Test public void truncatedInputRetainsFailure()throws Exception{
        byte[] b=packed();
        try(InputStream in=new AlzBzip2InputStream(new ByteArrayInputStream(Arrays.copyOf(b,b.length-2)))){
            assertThrows(IOException.class,()->in.readAllBytes());assertThrows(IOException.class,()->in.read());
        }
    }
    @Test public void compressedSourceUsesBulkReads()throws Exception{
        final int[] calls={0,0};
        InputStream source=new ByteArrayInputStream(packed()){
            @Override public synchronized int read(){calls[0]++;return super.read();}
            @Override public synchronized int read(byte[] b,int o,int n){calls[1]++;return super.read(b,o,n);}
        };
        try(InputStream in=new AlzBzip2InputStream(source)){assertArrayEquals(plain(),in.readAllBytes());}
        assertEquals(0,calls[0]);assertTrue(calls[1]<=2);
    }
    @Test public void shortInputReadsPreserveBitAlignment()throws Exception{
        for(int chunk=1;chunk<=19;chunk++){
            final int cap=chunk;InputStream source=new ByteArrayInputStream(packed()){
                @Override public synchronized int read(byte[]b,int o,int n){return super.read(b,o,Math.min(n,cap));}
            };
            try(InputStream in=new AlzBzip2InputStream(source)){assertArrayEquals(plain(),in.readAllBytes());}
        }
    }
    @Test public void mixedScalarBulkAndSkipReads()throws Exception{
        byte[] expected=plain();Random random=new Random(624);
        for(int run=0;run<40;run++)try(InputStream in=open()){
            int p=0;while(p<expected.length){int n=Math.min(1+random.nextInt(31),expected.length-p);
                if(random.nextBoolean()){byte[]b=new byte[n+4];int got=in.read(b,2,n);assertTrue(got>0);assertArrayEquals(Arrays.copyOfRange(expected,p,p+got),Arrays.copyOfRange(b,2,2+got));p+=got;}
                else{assertEquals(expected[p++]&255,in.read());}
            }assertEquals(-1,in.read());
        }
        try(InputStream in=open()){assertEquals(57,in.skip(57));assertArrayEquals(Arrays.copyOfRange(expected,57,expected.length),in.readAllBytes());}
    }
    @Test public void closeIsIdempotentAndRejectsReads()throws Exception{
        final int[]closed={0};InputStream source=new ByteArrayInputStream(packed()){@Override public void close(){closed[0]++;}};
        InputStream in=new AlzBzip2InputStream(source);in.close();in.close();assertEquals(1,closed[0]);assertThrows(IOException.class,()->in.read());
    }
    @Test public void invalidSourceProgressRejected()throws Exception{
        InputStream source=new ByteArrayInputStream(packed()){@Override public synchronized int read(byte[]b,int o,int n){return 0;}};
        assertThrows(IOException.class,()->new AlzBzip2InputStream(source));
    }
    @Test public void threeBzipBlocksStillDecode260000Bytes()throws Exception{
        byte[] packed=Base64.getDecoder().decode(Round6Fixtures.MULTI_BZIP),expected=new byte[260000];
        for(int i=0;i<expected.length;i++)expected[i]=(byte)(33+i%87);
        try(InputStream in=new AlzBzip2InputStream(new ByteArrayInputStream(packed))){assertArrayEquals(expected,in.readAllBytes());}
    }
    @Test public void multiBlockDecoderHandlesShortCompressedReads()throws Exception{
        byte[] packed=Base64.getDecoder().decode(Round6Fixtures.MULTI_BZIP),expected=new byte[260000];
        for(int i=0;i<expected.length;i++)expected[i]=(byte)(33+i%87);
        InputStream shortInput=new ByteArrayInputStream(packed){@Override public synchronized int read(byte[]b,int o,int n){return super.read(b,o,Math.min(n,7));}};
        try(InputStream in=new AlzBzip2InputStream(shortInput)){assertArrayEquals(expected,in.readAllBytes());}
    }

}
