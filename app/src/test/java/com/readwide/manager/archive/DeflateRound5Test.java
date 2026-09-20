package com.readwide.manager.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class DeflateRound5Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void ownedDeflateRoundTripsAcrossRandomChunkBoundaries()throws Exception{
        Random random=new Random(5771);
        for(int i=0;i<120;i++){
            byte[] raw=new byte[random.nextInt(20000)];random.nextBytes(raw);byte[] packed=Round4Fixtures.deflate(raw);
            InputStream shortInput=new ByteArrayInputStream(packed){@Override public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(n,1+random.nextInt(113)));}};
            try(InputStream stream=new OwnedDeflateInputStream(shortInput)){assertArrayEquals(raw,stream.readAllBytes());}
        }
    }
    @Test public void truncatedDeflateFailureStaysTerminal()throws Exception{
        try(InputStream s=new OwnedDeflateInputStream(new ByteArrayInputStream(new byte[0]))){IOException first=assertThrows(IOException.class,()->s.read());assertSame(first,assertThrows(IOException.class,()->s.read()));}
    }
    @Test public void deflateInvalidDataFailureStaysTerminal()throws Exception{
        try(InputStream s=new OwnedDeflateInputStream(new ByteArrayInputStream(new byte[]{7}))){IOException first=assertThrows(IOException.class,()->s.read());assertSame(first,assertThrows(IOException.class,()->s.read()));}
    }
    @Test public void deflateCancellationStaysTerminalAfterFlagCleared()throws Exception{
        try(InputStream s=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{1})))){
            IOException first;try{Thread.currentThread().interrupt();first=assertThrows(InterruptedIOException.class,()->s.read());}finally{Thread.interrupted();}
            assertSame(first,assertThrows(IOException.class,()->s.read()));
        }
    }
    @Test public void deflateZeroProgressCannotResumeAfterFailure()throws Exception{
        byte[] packed=Round4Fixtures.deflate(new byte[]{1});InputStream input=new ByteArrayInputStream(packed){boolean first=true;@Override public synchronized int read(byte[] b,int o,int n){if(first){first=false;return 0;}return super.read(b,o,n);}};
        try(InputStream s=new OwnedDeflateInputStream(input)){IOException first=assertThrows(IOException.class,()->s.read());assertSame(first,assertThrows(IOException.class,()->s.read()));}
    }
    @Test public void deflateInvalidArgumentsDoNotPoisonStream()throws Exception{
        try(InputStream s=new OwnedDeflateInputStream(new ByteArrayInputStream(Round4Fixtures.deflate(new byte[]{5})))){
            assertThrows(IndexOutOfBoundsException.class,()->s.read(new byte[1],-1,1));assertThrows(NullPointerException.class,()->s.read(null,0,1));assertEquals(0,s.read(new byte[1],1,0));assertEquals(5,s.read());assertEquals(-1,s.read());
        }
    }
    @Test public void deflateCloseIsIdempotentAndDisablesReads()throws Exception{
        int[] calls={0};InputStream input=new ByteArrayInputStream(new byte[0]){public void close(){calls[0]++;}};InputStream s=new OwnedDeflateInputStream(input);s.close();s.close();assertEquals(1,calls[0]);assertThrows(IOException.class,()->s.read());
    }
    @Test public void sevenZRegistryUsesOwnedJdkDeflate()throws Exception{
        byte[] raw=Round4Fixtures.data(10000);SevenZCoderRegistry.Prepared prepared=SevenZCoderRegistry.require(new byte[]{4,1,8}).prepare(new byte[0],raw.length);
        try(InputStream s=prepared.open(new InputStream[]{new ByteArrayInputStream(Round4Fixtures.deflate(raw))},null)){assertTrue(s instanceof OwnedDeflateInputStream);assertArrayEquals(raw,s.readAllBytes());}
    }
    @Test public void sevenZDeflateRejectsNonemptyProperties()throws Exception{assertThrows(IOException.class,()->SevenZCoderRegistry.require(new byte[]{4,1,8}).prepare(new byte[]{1},0));}
    private void decode(int width,boolean aes,boolean header,boolean two)throws Exception{
        byte[] data=Round4Fixtures.data(10003);File f=Round5Fixtures.compressedSevenZ(temp.newFolder(),data,width,aes,header,two,false),dir=temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(f,dir,aes?Round4Fixtures.PASSWORD:null,null,null));
        if(two){assertArrayEquals(Arrays.copyOfRange(data,0,data.length/2),Files.readAllBytes(new File(dir,"1.bin").toPath()));assertArrayEquals(Arrays.copyOfRange(data,data.length/2,data.length),Files.readAllBytes(new File(dir,"2.bin").toPath()));}
        else assertArrayEquals(data,Files.readAllBytes(new File(dir,"data.bin").toPath()));
    }
    @Test public void completeDeflate7zExtracts()throws Exception{decode(0,false,false,false);}
    @Test public void completeDeflateSwap2Solid7zExtracts()throws Exception{decode(2,false,false,true);}
    @Test public void completeDeflateSwap4Solid7zExtracts()throws Exception{decode(4,false,false,true);}
    @Test public void completeAesDeflateSwap2EncodedHeader7zExtracts()throws Exception{decode(2,true,true,true);}
    @Test public void completeAesDeflateSwap4EncodedHeader7zExtracts()throws Exception{decode(4,true,true,true);}
    @Test public void completeDeflateEncodedHeader7zExtracts()throws Exception{decode(0,false,true,false);}
    @Test public void corruptDeflateSolid7zRestoresAllOutputs()throws Exception{
        File f=Round5Fixtures.compressedSevenZ(temp.newFolder(),Round4Fixtures.data(4000),4,true,true,true,true),dir=temp.newFolder();byte[] old={9,8};
        for(String n:new String[]{"1.bin","2.bin"})Files.write(new File(dir,n).toPath(),old);
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(f,dir,Round4Fixtures.PASSWORD,null,null));
        for(String n:new String[]{"1.bin","2.bin"})assertArrayEquals(old,Files.readAllBytes(new File(dir,n).toPath()));assertEquals(2,dir.list().length);
    }
}
