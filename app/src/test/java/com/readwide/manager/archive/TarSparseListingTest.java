package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Old GNU sparse layout from Commons Compress's documented header structure. */
public final class TarSparseListingTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    @Before @After public void clear(){TarEntryIndex.clear();}
    @Test public void publicListingReportsExpandedSizeOnColdAndCachedReads()throws Exception{
        File archive=fixture(20,5,new byte[]{1,2,3});
        for(int i=0;i<2;i++)assertEquals(20,ArchiveSupport.listEntries(archive,null).get(0).size);
    }
    @Test public void largeSparseListingDoesNotUseStoredBytesAsLogicalSize()throws Exception{
        File archive=fixture(4L*1024*1024*1024,0,new byte[]{1});
        assertTrue(archive.length()<4096);assertEquals(4L*1024*1024*1024,TarEntryIndex.get(archive).listing.get(0).size);
    }
    @Test public void sparseExtractionStillExpandsHolesThroughPublicRoute()throws Exception{
        File archive=fixture(20,5,new byte[]{1,2,3}),output=temporary.newFile();
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,"sparse.bin",output,null);
        assertTrue(result.failure+": "+result.detail,result.success);
        byte[] expected=new byte[20];expected[5]=1;expected[6]=2;expected[7]=3;
        assertArrayEquals(expected,Files.readAllBytes(output.toPath()));
        assertEquals(expected.length,TarEntryIndex.get(archive).listing.get(0).size);
    }
    @Test public void directSparseIndexStillDefersToEstablishedDecoder()throws Exception{
        File archive=fixture(20,5,new byte[]{1,2,3}),output=temporary.newFile();Files.write(output.toPath(),new byte[]{42});
        assertNull(TarEntryIndex.extract(archive,"sparse.bin",output));assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }
    @Test public void ordinaryEntrySizeRemainsStoredSize()throws Exception{
        File archive=temporary.newFile("regular.tar");
        try(TarArchiveOutputStream out=new TarArchiveOutputStream(new FileOutputStream(archive))){
            TarArchiveEntry entry=new TarArchiveEntry("regular.bin");entry.setSize(3);out.putArchiveEntry(entry);out.write(new byte[]{1,2,3});out.closeArchiveEntry();
        }
        assertEquals(3,ArchiveSupport.listEntries(archive,null).get(0).size);
    }
    @Test public void storedPayloadBoundsRemainChecked()throws Exception{
        File archive=fixture(20,5,new byte[]{1,2,3});try(RandomAccessFile raw=new RandomAccessFile(archive,"rw")){raw.setLength(513);}
        assertThrows(IOException.class,()->TarEntryIndex.get(archive));
    }
    private File fixture(long logicalSize,long offset,byte[] bytes)throws Exception{
        TarArchiveEntry entry=new TarArchiveEntry("sparse.bin",(byte)'S');entry.setSize(bytes.length);
        byte[] header=new byte[512];entry.writeEntryHeader(header);
        System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII),0,header,257,6);header[263]=' ';header[264]=0;
        octal(header,386,12,offset);octal(header,398,12,bytes.length);
        octal(header,410,12,logicalSize);octal(header,422,12,0);octal(header,483,12,logicalSize);
        Arrays.fill(header,148,156,(byte)' ');long sum=0;for(byte value:header)sum+=value&255;
        byte[] checksum=String.format(Locale.ROOT,"%06o\0 ",sum).getBytes(StandardCharsets.US_ASCII);System.arraycopy(checksum,0,header,148,8);
        File archive=temporary.newFile("sparse-"+System.nanoTime()+".tar");
        try(OutputStream out=new FileOutputStream(archive)){out.write(header);out.write(bytes);out.write(new byte[(512-bytes.length%512)%512]);out.write(new byte[1024]);}
        // Establish fixture semantics using the upstream decoder before app assertions.
        try(TarFile tar=new TarFile(archive)){TarArchiveEntry parsed=tar.getEntries().get(0);assertTrue(parsed.isSparse());assertEquals(logicalSize,parsed.getRealSize());assertEquals(bytes.length,parsed.getSize());}
        return archive;
    }
    private static void octal(byte[] header,int offset,int length,long value){
        byte[] text=String.format(Locale.ROOT,"%0"+(length-1)+"o",value).getBytes(StandardCharsets.US_ASCII);
        assertEquals(length-1,text.length);System.arraycopy(text,0,header,offset,text.length);header[offset+length-1]=0;
    }
}
