package com.readwide.manager.archive;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.rules.TemporaryFolder;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.*;
import com.readwide.manager.util.FileOperationProgress;
public class ArchiveStreamContractRound7Test {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void rejectsZeroProgressImmediately()throws Exception{final int[] reads={0};InputStream in=new InputStream(){public int read(){return -1;}public int read(byte[] b){if(++reads[0]==1)return 0;throw new AssertionError("retried zero progress");}};assertThrows(IOException.class,()->ArchiveSupport.writeArchiveEntryStream(in,new File(temp.getRoot(),"out")));assertEquals(1,reads[0]);}
 @Test public void rejectsReadCountLargerThanBuffer()throws Exception{InputStream in=new InputStream(){public int read(){return -1;}public int read(byte[] b){return b.length+1;}};assertThrows(IOException.class,()->ArchiveSupport.writeArchiveEntryStream(in,new File(temp.getRoot(),"out")));}
 @Test public void rejectsInvalidNegativeRead()throws Exception{InputStream in=new InputStream(){public int read(){return -1;}public int read(byte[] b){return -2;}};assertThrows(IOException.class,()->ArchiveSupport.writeArchiveEntryStream(in,new File(temp.getRoot(),"out")));}
 @Test public void scalarAccountingRejectsZero(){assertThrows(IOException.class,()->ArchiveSupport.checkedAddDecodedStreamBytes(10,0));}
 @Test public void scalarAccountingRejectsNegative(){assertThrows(IOException.class,()->ArchiveSupport.checkedAddDecodedStreamBytes(10,-2));}
 @Test public void overflowRemainsChecked(){assertThrows(IOException.class,()->ArchiveSupport.checkedAddDecodedStreamBytes(Long.MAX_VALUE-1,2));}
 @Test public void nullProgressCancellationIsObserved()throws Exception{Thread.currentThread().interrupt();try{assertThrows(IOException.class,()->ArchiveSupport.writeArchiveEntryStream(new ByteArrayInputStream(new byte[]{1}),new File(temp.getRoot(),"out")));}finally{Thread.interrupted();}}
 @Test public void randomShortReadsPreserveBytes()throws Exception{byte[] data=Round4Fixtures.data(300003);for(int chunk:new int[]{1,3,127,8191}){InputStream in=new ByteArrayInputStream(data){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(chunk,len));}};File out=new File(temp.getRoot(),"out"+chunk);assertTrue(ArchiveSupport.writeArchiveEntryStream(in,out));assertArrayEquals(data,Files.readAllBytes(out.toPath()));}}
 @Test public void emptyInputCreatesEmptyFile()throws Exception{File f=new File(temp.getRoot(),"empty");assertTrue(ArchiveSupport.writeArchiveEntryStream(new ByteArrayInputStream(new byte[0]),f));assertEquals(0,f.length());}
}
