package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Legacy Commons/first-party ZIP methods must retain the split-volume source. */
@RunWith(Parameterized.class)
public final class ZipLegacySplitPublicRouteTest {
    @Parameterized.Parameters(name="method={0},flags={1}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{1,0},{2,0},{3,0},{4,0},{5,0},{6,0},{6,2},{6,4},{6,6},{9,0},{12,0}});}
    private final ZipxAesArchiveReaderTest fixtures=new ZipxAesArchiveReaderTest();
    @Rule public final TemporaryFolder temporary=fixtures.temp;
    private final int method,flags;
    private static final byte[] CONTENT="Readwide legacy ZIP payload 123456789".getBytes(StandardCharsets.UTF_8);
    public ZipLegacySplitPublicRouteTest(int method,int flags){this.method=method;this.flags=flags;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void splitSelectedWorksFromFinalVolume()throws Exception{
        File[] parts=fixture(true,false);File selected=parts[parts.length-1];for(int attempt=0;attempt<2;attempt++){File out=temporary.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(selected,"page.bin",out,null);assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(CONTENT,Files.readAllBytes(out.toPath()));}
    }
    @Test public void splitBulkUsesTheCompleteVolumeSource()throws Exception{bulk(fixture(true,false)[1]);}
    @Test public void unsplitLegacyArchiveRemainsReadable()throws Exception{
        File archive=fixture(false,false)[0];bulk(archive);File out=temporary.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",out,null).success);assertArrayEquals(CONTENT,Files.readAllBytes(out.toPath()));
    }
    @Test public void splitCrcFailurePreservesSelectedAndBulkOutputs()throws Exception{
        File archive=fixture(true,true)[1],out=temporary.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"original");assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",out,null).success);assertEquals("original",ArchiveTestTextFiles.readString(out.toPath()));
        File directory=temporary.newFolder();ArchiveTestTextFiles.writeString(new File(directory,"keep.bin").toPath(),"original");assertFalse(ArchiveSupport.extractArchiveDetailed(archive,directory,true,null,null).success);assertArrayEquals(new String[]{"keep.bin"},directory.list());
    }
    private void bulk(File archive)throws Exception{
        File out=new File(temporary.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,out,false,null,null);assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(CONTENT,Files.readAllBytes(new File(out,"page.bin").toPath()));
    }
    private File[] fixture(boolean split,boolean bad)throws Exception{
        byte[] packed;
        if(method==1||method==6){Method encode=ZipxAesArchiveReaderTest.class.getDeclaredMethod("legacyLiterals",byte[].class,int.class,int.class);encode.setAccessible(true);packed=(byte[])encode.invoke(null,CONTENT,method,flags);}
        else if(method>=2&&method<=5){packed=ZipReduceInputStreamTest.literals(CONTENT);}
        else{ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(method==12){try(BZip2CompressorOutputStream bz=new BZip2CompressorOutputStream(bytes)){bz.write(CONTENT);}}else{int n=CONTENT.length;bytes.write(1);bytes.write(n&255);bytes.write(n>>>8);bytes.write((~n)&255);bytes.write(((~n)>>>8)&255);bytes.write(CONTENT);}packed=bytes.toByteArray();}
        File archive=temporary.newFile("legacy-"+System.nanoTime()+".zip");try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive)){
            ZipArchiveEntry entry=new ZipArchiveEntry("page.bin");entry.setMethod(method);entry.setSize(CONTENT.length);entry.setCompressedSize(packed.length);CRC32 crc=new CRC32();crc.update(CONTENT);entry.setCrc(crc.getValue()^(bad?1:0));zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed));
        }
        byte[] bytes=Files.readAllBytes(archive.toPath());bytes[6]|=(byte)flags;for(int i=0;i+46<bytes.length;i++){if(bytes[i]=='P'&&bytes[i+1]=='K'&&bytes[i+2]==1&&bytes[i+3]==2){bytes[i+8]|=(byte)flags;break;}}Files.write(archive.toPath(),bytes);
        if(!split)return new File[]{archive};Method cut=ZipxAesArchiveReaderTest.class.getDeclaredMethod("splitZipx",File.class,int[].class);cut.setAccessible(true);return (File[])cut.invoke(fixtures,archive,new int[]{20});
    }
}
