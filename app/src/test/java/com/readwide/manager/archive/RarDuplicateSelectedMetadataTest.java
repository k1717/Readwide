package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class RarDuplicateSelectedMetadataTest {
    @Parameterized.Parameters(name="RAR{0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{4},{5}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();private final int version;
    public RarDuplicateSelectedMetadataTest(int version){this.version=version;}
    private File fixture(boolean alias,boolean folder)throws Exception{
        ByteArrayOutputStream archive=new ByteArrayOutputStream();archive.write(new byte[]{'R','a','r','!',0x1a,7});archive.write(version==4?0:1);if(version==5)archive.write(0);
        archive.write(version==4?header4(0x73,0,new byte[6]):header5(new byte[]{1,0,0}));
        for(int i=0;i<2;i++){
            byte[] data=i==0?new byte[]{1,2,3}:new byte[]{4,5,6,7,8};String path=(folder?"folder/":"")+"page.bin";if(alias&&i==1)path="./"+path;byte[] name=path.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream body=new ByteArrayOutputStream();
            if(version==4){le(body,data.length,4);le(body,data.length,4);body.write(1);le(body,crc(data),4);le(body,0,4);body.write(29);body.write(0x30);le(body,name.length,2);le(body,0,4);body.write(name);archive.write(header4(0x74,0x8000,body.toByteArray()));}
            else{vint(body,2);vint(body,2);vint(body,data.length);vint(body,4);vint(body,data.length);vint(body,0);le(body,crc(data),4);vint(body,0);vint(body,1);vint(body,name.length);body.write(name);archive.write(header5(body.toByteArray()));}archive.write(data);
        }
        archive.write(version==4?header4(0x7b,0,new byte[0]):header5(new byte[]{5,0,0}));File file=temp.newFile("duplicate-"+System.nanoTime()+".rar");Files.write(file.toPath(),archive.toByteArray());return file;
    }
    private void matches(boolean alias,boolean folder)throws Exception{
        File archive=fixture(alias,folder);List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(archive,null);assertEquals(folder?2:1,entries.size());ArchiveSupport.EntryInfo member=entries.get(entries.size()-1);File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,member.path,out,null);assertTrue(result.failure+" "+result.detail,result.success);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(out.toPath()));assertEquals(out.length(),member.size);
    }
    @Test public void exactDuplicateDescribesFirstSelectedMember()throws Exception{matches(false,false);}
    @Test public void normalizedDuplicateDescribesFirstSelectedMember()throws Exception{matches(true,false);}
    @Test public void syntheticParentDoesNotChangeDuplicateIdentity()throws Exception{matches(true,true);}
    private static byte[] header4(int type,int flags,byte[] body)throws Exception{ByteArrayOutputStream data=new ByteArrayOutputStream();data.write(type);le(data,flags,2);le(data,body.length+7,2);data.write(body);ByteArrayOutputStream out=new ByteArrayOutputStream();le(out,crc(data.toByteArray()),2);out.write(data.toByteArray());return out.toByteArray();}
    private static byte[] header5(byte[] body)throws Exception{ByteArrayOutputStream data=new ByteArrayOutputStream();vint(data,body.length);data.write(body);ByteArrayOutputStream out=new ByteArrayOutputStream();le(out,crc(data.toByteArray()),4);out.write(data.toByteArray());return out.toByteArray();}
    private static void vint(OutputStream out,long n)throws Exception{do{int b=(int)(n&127);n>>>=7;out.write(b|(n==0?0:128));}while(n!=0);}
    private static void le(OutputStream out,long n,int size)throws Exception{for(int i=0;i<size;i++)out.write((int)(n>>>(8*i))&255);}
    private static long crc(byte[] data){CRC32 crc=new CRC32();crc.update(data);return crc.getValue();}
}
