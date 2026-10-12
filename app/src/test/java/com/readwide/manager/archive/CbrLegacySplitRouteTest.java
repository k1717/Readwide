package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Valid in-process stored RAR4 split payloads with the supported CBR base suffix. */
public final class CbrLegacySplitRouteTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    @Test public void publicSelectedFromCbrReadsContinuation()throws Exception{extract(false,"CBR");}
    @Test public void publicSelectedFromContinuationFindsCbrBase()throws Exception{extract(true,"CBR");}
    @Test public void ordinaryRarBaseKeepsWorking()throws Exception{extract(false,"rar");}
    private void extract(boolean later,String extension)throws Exception{
        File[] files=fixture(extension);File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(files[later?1:0],"page.bin",output,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(output.toPath()));
    }
    @Test public void sourceSnapshotIncludesCbrAndContinuation()throws Exception{
        File[] files=fixture("cbr");ArchiveSourceSnapshot source=ArchiveSourceSnapshot.capture(files[0]);
        assertTrue(source.containsFile(files[0]));assertTrue(source.containsFile(files[1]));
        assertTrue(files[1].setLastModified(files[1].lastModified()+5000));assertFalse(source.matches(files[0]));
    }
    @Test public void competingRarAndCbrBasesAreRejected()throws Exception{
        File[] files=fixture("cbr");File rar=new File(files[0].getParentFile(),"book.rar");Files.copy(files[0].toPath(),rar.toPath());
        for(File selected:new File[]{files[0],files[1],rar})assertThrows(IOException.class,()->RarArchiveLocator.collectReadableVolumes(selected));
    }
    @Test public void missingContinuationPreservesExistingSelectedOutput()throws Exception{
        File[] files=fixture("cbr");assertTrue(files[1].delete());File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(files[0],"page.bin",output,null).success);assertEquals("original",ArchiveTestTextFiles.readString(output.toPath()));
    }
    @Test public void standaloneCbrIsNotRedirectedToCoexistingRar()throws Exception{
        File directory=temporary.newFolder();File cbr=new File(directory,"book.cbr"),rar=new File(directory,"book.rar");Files.write(cbr.toPath(),new byte[0]);Files.write(rar.toPath(),new byte[0]);
        assertEquals(Collections.singletonList(cbr),RarArchiveLocator.collectReadableVolumes(cbr));
        assertEquals(Collections.singletonList(rar),RarArchiveLocator.collectReadableVolumes(rar));
    }
    private File[] fixture(String extension)throws Exception{
        File directory=temporary.newFolder();File first=new File(directory,"book."+extension),second=new File(directory,"book.r00");
        write(first,new byte[]{1,2},2,crc(new byte[]{1,2}),2,true);
        write(second,new byte[]{3,4},1,crc(new byte[]{1,2,3,4}),4,false);return new File[]{first,second};
    }
    private static void write(File file,byte[] data,int flags,long crc,long size,boolean first)throws Exception{
        byte[] name="page.bin".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream body=new ByteArrayOutputStream();
        le(body,data.length,4);le(body,size,4);body.write(1);le(body,crc,4);le(body,0,4);body.write(29);body.write(0x30);le(body,name.length,2);le(body,0,4);body.write(name);
        try(OutputStream out=new FileOutputStream(file)){
            out.write(new byte[]{0x52,0x61,0x72,0x21,0x1a,7,0});out.write(header(0x73,1|(first?0x100:0),new byte[6]));
            out.write(header(0x74,0x8000|flags,body.toByteArray()));out.write(data);out.write(header(0x7b,first?1:0,new byte[0]));
        }
    }
    private static byte[] header(int type,int flags,byte[] body)throws Exception{
        ByteArrayOutputStream raw=new ByteArrayOutputStream();raw.write(type);le(raw,flags,2);le(raw,body.length+7,2);raw.write(body);
        ByteArrayOutputStream result=new ByteArrayOutputStream();le(result,crc(raw.toByteArray()),2);result.write(raw.toByteArray());return result.toByteArray();
    }
    private static long crc(byte[] bytes){CRC32 crc=new CRC32();crc.update(bytes);return crc.getValue();}
    private static void le(OutputStream out,long value,int count)throws IOException{for(int i=0;i<count;i++)out.write((int)(value>>>(8*i))&255);}
}
