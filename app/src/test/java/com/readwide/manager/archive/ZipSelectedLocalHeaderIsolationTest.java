package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.*;
import static org.junit.Assert.*;

/** A selected ZIPX entry owns its local-header checks; unrelated headers are not dependencies. */
public final class ZipSelectedLocalHeaderIsolationTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void damagedEarlierLocalExtraDoesNotBlockSelectedEntry()throws Exception{selected(fixture(false,false,1));}
    @Test public void damagedLaterLocalExtraDoesNotBlockSelectedEntry()throws Exception{selected(fixture(true,false,1));}
    @Test public void damagedUnrelatedLocalNameDoesNotBlockSelectedEntry()throws Exception{selected(fixture(false,false,2));}
    @Test public void ordinaryUndamagedZipxStillExtracts()throws Exception{selected(fixture(false,false,0));}
    @Test public void validLocalOnlyUnicodeAliasRemainsAvailable()throws Exception{
        File archive=temporary.newFile("local-alias.zip");
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive)){
            zip.setUseLanguageEncodingFlag(false);member(zip,"target.bin","healthy","alias.bin");
        }
        byte[] bytes=Files.readAllBytes(archive.toPath());boolean changed=false;
        for(int at=0;at+46<bytes.length;at++){
            if(u32(bytes,at)!=0x02014b50L)continue;
            int extra=at+46+u16(bytes,at+28),end=extra+u16(bytes,at+30);
            while(extra+4<=end){
                if(u16(bytes,extra)==0x7075){bytes[extra]=0x77;bytes[extra+1]=0x77;changed=true;break;}
                extra+=4+u16(bytes,extra+2);
            }
            break;
        }
        assertTrue(changed);Files.write(archive.toPath(),bytes);selected(archive,"alias.bin");
    }
    @Test public void damagedSelectedHeaderPreservesDestination()throws Exception{
        File archive=fixture(false,true,1);File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"target.bin",output,null).success);assertEquals("original",ArchiveTestTextFiles.readString(output.toPath()));
    }
    @Test public void bulkStillRejectsTheDamagedMemberAndPreservesExistingDirectory()throws Exception{
        File archive=fixture(false,false,1);File output=temporary.newFolder();ArchiveTestTextFiles.writeString(new File(output,"old.bin").toPath(),"original");
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive,output,true,null,null).success);
        assertArrayEquals(new String[]{"old.bin"},output.list());assertEquals("original",ArchiveTestTextFiles.readString(new File(output,"old.bin").toPath()));
    }
    @Test public void listingRemainsMetadataOnlyWithDamagedLocalExtra()throws Exception{
        Set<String> names=new HashSet<>();for(ArchiveSupport.EntryInfo entry:ArchiveSupport.listEntries(fixture(false,false,1),null))names.add(entry.path);
        assertEquals(new HashSet<>(Arrays.asList("other.bin","target.bin")),names);
    }
    private void selected(File archive)throws Exception{
        selected(archive,"target.bin");
    }
    private void selected(File archive,String name)throws Exception{
        for(int i=0;i<2;i++){
            File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");
            ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,name,output,null);
            assertTrue(result.failure+": "+result.detail,result.success);assertEquals("healthy",ArchiveTestTextFiles.readString(output.toPath()));
        }
    }
    private File fixture(boolean targetFirst,boolean damageTarget,int damage)throws Exception{
        File archive=temporary.newFile("local-"+System.nanoTime()+".zip");
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive)){
            if(targetFirst)member(zip,"target.bin","healthy");
            member(zip,"other.bin","other");
            if(!targetFirst)member(zip,"target.bin","healthy");
        }
        if(damage!=0){
            byte[] bytes=Files.readAllBytes(archive.toPath());String wanted=damageTarget?"target.bin":"other.bin";boolean changed=false;
            for(int at=0;at+30<bytes.length;at++){
                if(u32(bytes,at)!=0x04034b50L)continue;int n=u16(bytes,at+26);
                if(!wanted.equals(new String(bytes,at+30,n,StandardCharsets.UTF_8)))continue;
                int field=at+(damage==1?28:26);bytes[field]=(byte)255;bytes[field+1]=(byte)255;changed=true;break;
            }
            assertTrue(changed);Files.write(archive.toPath(),bytes);
        }
        return archive;
    }
    private static void member(ZipArchiveOutputStream zip,String name,String text)throws Exception{
        member(zip,name,text,null);
    }
    private static void member(ZipArchiveOutputStream zip,String name,String text,String unicode)throws Exception{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
        try(XZOutputStream stream=new XZOutputStream(packed,new LZMA2Options())){stream.write(data);}
        ZipArchiveEntry entry=new ZipArchiveEntry(name);entry.setMethod(95);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());
        if(unicode!=null)entry.addExtraField(new UnicodePathExtraField(unicode,name.getBytes(StandardCharsets.UTF_8)));
        zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
    }
    private static int u16(byte[] bytes,int at){return (bytes[at]&255)|((bytes[at+1]&255)<<8);}
    private static long u32(byte[] bytes,int at){return (bytes[at]&255L)|((bytes[at+1]&255L)<<8)|((bytes[at+2]&255L)<<16)|((bytes[at+3]&255L)<<24);}
}
