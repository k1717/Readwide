package com.readwide.manager.archive;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.util.*;

public class SplitArchiveRound2Test {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File file(String name, byte... bytes)throws Exception {File f=new File(temp.getRoot(),name);Files.write(f.toPath(),bytes);return f;}
    private void io(Action a)throws Exception {try{a.run();fail("Expected IOException");}catch(IOException expected){}}
    interface Action {void run()throws Exception;}
    @Test public void recognizesMinimumThreeDigitWidthWithoutOverflowOrAliases() {
        for(String s:new String[]{"book.7z.001","book.7z.999","book.7z.1000","book.cb7.12345","BOOK.7Z.2147483647"})assertTrue(s,SevenZSplitVolumeResolver.isSevenZSplitPartName(s));
        for(String s:new String[]{"book.zip.001","book.7z.000","book.7z.0001","book.7z.2147483648","book.7z.99999999999999999999"})assertFalse(s,SevenZSplitVolumeResolver.isSevenZSplitPartName(s));
    }
    @Test public void collectsThousandAndFirstVolumeFromMiddleSelection()throws Exception {
        for(int i=1;i<=1001;i++)file(String.format(Locale.ROOT,"book.7z.%03d",i),(byte)i);
        SevenZSplitVolumeResolver.VolumeSet set=SevenZSplitVolumeResolver.resolve(new File(temp.getRoot(),"book.7z.1000"));
        assertEquals(1001,set.parts.size()); assertEquals("book.7z.001",set.firstPart.getName());
        assertEquals("book.7z.1000",set.parts.get(999).getName());
    }
    @Test public void findsGapBeyondOldMaximumWithoutIteratingToHugeOrdinal()throws Exception {
        file("book.7z.001",(byte)1);file("book.7z.2147483647",(byte)2);
        io(()->SevenZSplitVolumeResolver.resolve(new File(temp.getRoot(),"book.7z.001")));
    }
    @Test public void genericZipNumericResolverAlsoAcceptsFourDigitTail()throws Exception {
        for(int i=1;i<=1000;i++)file(String.format(Locale.ROOT,"book.zip.%03d",i),(byte)i);
        assertEquals(1000,SevenZSplitVolumeResolver.resolveNumeric(new File(temp.getRoot(),"book.zip.001")).parts.size());
    }
    @Test public void genericGapKeepsClassifierMessage()throws Exception {
        File first=file("book.zip.001");file("book.zip.003");
        try {SevenZSplitVolumeResolver.resolveNumeric(first);fail();}catch(IOException e){assertTrue(e.getMessage().contains("Missing numeric split archive part"));}
    }
    @Test public void resolvesUnambiguousMixedCaseVolumes()throws Exception {
        file("BOOK.7z.001",(byte)1);File second=file("book.7Z.002",(byte)2);
        assertEquals(2,SevenZSplitVolumeResolver.resolve(second).parts.size());
    }
    @Test public void rejectsAmbiguousCaseCollisions()throws Exception {
        File first=file("BOOK.7z.001");File lower=file("book.7z.001");
        if(Files.isSameFile(first.toPath(),lower.toPath()))return; // case-insensitive filesystem
        io(()->SevenZSplitVolumeResolver.resolve(first));
    }
    @Test public void nonexistentSelectedPartDoesNotResolveAnUnrelatedPrefix()throws Exception {
        file("book.7z.001");io(()->SevenZSplitVolumeResolver.resolve(new File(temp.getRoot(),"book.7z.002")));
    }
    @Test public void resolverPreservesCancellation()throws Exception {
        File first=file("book.7z.001");Thread.currentThread().interrupt();
        try{io(()->SevenZSplitVolumeResolver.resolve(first));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
    }
    @Test public void channelCrossesEmptyAndNonemptyVolumesAndSupportsDirectBuffers()throws Exception {
        List<File> parts=Arrays.asList(file("a",(byte)1,(byte)2),file("empty"),file("b",(byte)3,(byte)4),file("tail"));
        try(SplitSeekableByteChannel c=new SplitSeekableByteChannel(parts)) {
            ByteBuffer b=ByteBuffer.allocateDirect(4);assertEquals(4,c.read(b));b.flip();byte[] all=new byte[4];b.get(all);assertArrayEquals(new byte[]{1,2,3,4},all);
            assertEquals(-1,c.read(ByteBuffer.allocate(1)));assertEquals(0,c.read(ByteBuffer.allocate(0)));
            c.position(1);b=ByteBuffer.allocate(2);assertEquals(2,c.read(b));assertArrayEquals(new byte[]{2,3},b.array());
            c.position(Long.MAX_VALUE);assertEquals(-1,c.read(ByteBuffer.allocate(1)));assertEquals(4,c.size());
        }
    }
    @Test public void channelPreservesCallerBufferLimitAndOffset()throws Exception {
        try(SplitSeekableByteChannel c=new SplitSeekableByteChannel(Arrays.asList(file("a",(byte)1),file("b",(byte)2)))) {
            ByteBuffer b=ByteBuffer.allocate(10);b.position(3);b.limit(5);assertEquals(2,c.read(b));assertEquals(5,b.limit());assertEquals(5,b.position());assertEquals(1,b.get(3));
        }
    }
    @Test public void channelRetiresOnTruncationAndCannotReplayRepairedData()throws Exception {
        File a=file("a",(byte)1),b=file("b",(byte)2);
        try(SplitSeekableByteChannel c=new SplitSeekableByteChannel(Arrays.asList(a,b))) {
            Files.write(b.toPath(),new byte[0]);io(()->c.read(ByteBuffer.allocate(2)));Files.write(b.toPath(),new byte[]{2});io(()->c.position(0));
        }
    }
    @Test public void channelRetiresOnCancellationAndPreservesFlag()throws Exception {
        try(SplitSeekableByteChannel c=new SplitSeekableByteChannel(Arrays.asList(file("a",(byte)1)))) {
            Thread.currentThread().interrupt();try{io(()->c.read(ByteBuffer.allocate(1)));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
            io(()->c.read(ByteBuffer.allocate(1)));
        }
    }
    @Test public void readOnlyAndClosedChannelContracts()throws Exception {
        SplitSeekableByteChannel c=new SplitSeekableByteChannel(Arrays.asList(file("a",(byte)1)));
        try{c.position(-1);fail();}catch(IllegalArgumentException expected){}
        try{c.write(ByteBuffer.allocate(1));fail();}catch(NonWritableChannelException expected){}
        try{c.truncate(0);fail();}catch(NonWritableChannelException expected){}
        assertEquals(1,c.read(ByteBuffer.allocate(1)));c.close();c.close();assertFalse(c.isOpen());
        try{c.position();fail();}catch(ClosedChannelException expected){}
    }
    @Test public void randomizedReadsAgreeWithConcatenatedReference()throws Exception {
        Random r=new Random(326897);List<File> parts=new ArrayList<>();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int i=0;i<63;i++){byte[] p=new byte[r.nextInt(50)];r.nextBytes(p);parts.add(file("v"+i,p));bytes.write(p);}
        byte[] expected=bytes.toByteArray();
        try(SplitSeekableByteChannel c=new SplitSeekableByteChannel(parts)) {
            for(int i=0;i<5000;i++){int offset=r.nextInt(expected.length+10),length=r.nextInt(100);c.position(offset);ByteBuffer b=ByteBuffer.allocate(length);int n=c.read(b);int want=length==0?0:offset>=expected.length?-1:Math.min(length,expected.length-offset);assertEquals(want,n);if(n>0)assertArrayEquals(Arrays.copyOfRange(expected,offset,offset+n),Arrays.copyOf(b.array(),n));}
        }
    }
    @Test public void manyVolumesHoldAtMostOneDescriptorInBothReaders()throws Exception {
        List<File> parts=new ArrayList<>();List<SplitVolumeInput.Segment> segments=new ArrayList<>();
        for(int i=0;i<1100;i++){File f=file("v"+i,(byte)i);parts.add(f);segments.add(new SplitVolumeInput.Segment(f,0,1));}
        File fd=new File("/proc/self/fd");String[] before=fd.list();
        try(SplitSeekableByteChannel c=new SplitSeekableByteChannel(parts)){assertEquals(1100,c.read(ByteBuffer.allocate(1100)));if(before!=null)assertTrue(fd.list().length-before.length<=2);}
        try(SplitVolumeInput in=new SplitVolumeInput(segments)){byte[] all=new byte[1100];in.readFully(all);for(int i=0;i<all.length;i++)assertEquals((byte)i,all[i]);if(before!=null)assertTrue(fd.list().length-before.length<=2);}
    }
}
