package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.tukaani.xz.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class TarCompressedSparseMetadataTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{".gz"},{".bz2"},{".xz"},{".lzma"},{".lz4"}});}
    private final TarSparseListingTest fixtures=new TarSparseListingTest();
    @Rule public final TemporaryFolder temp=fixtures.temporary;private final String suffix;
    public TarCompressedSparseMetadataTest(String suffix){this.suffix=suffix;}
    private File fixture(long size)throws Exception{
        Method method=TarSparseListingTest.class.getDeclaredMethod("fixture",long.class,long.class,byte[].class);method.setAccessible(true);byte[] bytes=Files.readAllBytes(((File)method.invoke(fixtures,size,5L,new byte[]{1,2,3})).toPath());
        File file=temp.newFile("sparse-"+System.nanoTime()+".tar"+suffix);OutputStream raw=new FileOutputStream(file),out;
        if(suffix.equals(".gz"))out=new GZIPOutputStream(raw);else if(suffix.equals(".bz2"))out=new BZip2CompressorOutputStream(raw);else if(suffix.equals(".xz"))out=new XZOutputStream(raw,new LZMA2Options(0));else if(suffix.equals(".lzma"))out=new LZMAOutputStream(raw,new LZMA2Options(0),bytes.length);else out=new FramedLZ4CompressorOutputStream(raw);
        try(OutputStream stream=out){stream.write(bytes);}return file;
    }
    @Test public void listingUsesExpandedSizeForCompressedSparseEntry()throws Exception{
        for(long size:new long[]{20,1024*1024}){File file=fixture(size);List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(file,null);assertEquals(1,entries.size());assertEquals("sparse.bin",entries.get(0).path);assertEquals(size,entries.get(0).size);assertTrue(file.length()<4096);}
    }
    @Test public void selectedPayloadLengthMatchesListedSize()throws Exception{
        File file=fixture(20),out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(file,"sparse.bin",out,null).success);byte[] expected=new byte[20];expected[5]=1;expected[6]=2;expected[7]=3;assertArrayEquals(expected,Files.readAllBytes(out.toPath()));assertEquals(out.length(),ArchiveSupport.listEntries(file,null).get(0).size);
    }
    @Test public void bulkStillExpandsSparseHoles()throws Exception{
        File file=fixture(20),out=new File(temp.newFolder(),"out");assertTrue(ArchiveSupport.extractArchiveDetailed(file,out,false,null,null).success);byte[] expected=new byte[20];expected[5]=1;expected[6]=2;expected[7]=3;assertArrayEquals(expected,Files.readAllBytes(new File(out,"sparse.bin").toPath()));
    }
}
