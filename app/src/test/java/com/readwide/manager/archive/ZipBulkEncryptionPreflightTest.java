package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Checks the approved local-versus-central ZIP field-offset correction. */
public final class ZipBulkEncryptionPreflightTest {
    private final ZipUncachedSelectedRouteTest fixtures=new ZipUncachedSelectedRouteTest();
    @Rule public final TemporaryFolder temporary=fixtures.temporary;
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void plainXzBulkNoLongerRequestsPassword()throws Exception{success(95);}
    @Test public void ordinaryStoredDeflateAndBzip2BulkRemainReadable()throws Exception{for(int method:new int[]{0,8,12})success(method);}
    private void success(int method)throws Exception{
        File output=new File(temporary.newFolder(),"out");
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive(method,false),output,false,null,null);
        assertTrue(result.failure+": "+result.detail,result.success);
        assertArrayEquals("Fresh ZIP selected payload\n".repeat(93).getBytes(StandardCharsets.UTF_8),Files.readAllBytes(new File(output,"page.bin").toPath()));
    }
    @Test public void plainXzPublicListingDoesNotRequestPassword()throws Exception{
        assertEquals("page.bin",ArchiveSupport.listEntries(archive(95,false),null).get(0).path);
    }
    @Test public void corruptXzBulkPreservesExistingDirectory()throws Exception{
        File output=temporary.newFolder();File old=new File(output,"keep.txt");ArchiveTestTextFiles.writeString(old.toPath(),"original");
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive(95,true),output,true,null,null);
        assertFalse(result.success);assertNotEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,result.failure);
        assertEquals("original",ArchiveTestTextFiles.readString(old.toPath()));assertArrayEquals(new String[]{"keep.txt"},output.list());
    }
    @Test public void localEncryptionFlagUsesOffsetSix()throws Exception{assertTrue(probe(false,1,8,0));}
    @Test public void localAesMethodUsesOffsetEight()throws Exception{assertTrue(probe(false,0,99,0));}
    @Test public void localOddCompressionMethodsAreNotEncryptionFlags()throws Exception{for(int method:new int[]{9,93,95})assertFalse(probe(false,0,method,0));}
    @Test public void localTimestampIsNotAnAesMethod()throws Exception{assertFalse(probe(false,0,8,99));}
    @Test public void centralHeaderOffsetsRemainUnchanged()throws Exception{
        assertTrue(probe(true,1,8,0));assertTrue(probe(true,0,99,0));assertFalse(probe(true,0,95,99));
    }
    @Test public void aesBulkStillRequiresCorrectPasswordAndPreservesOldOutput()throws Exception{
        File archive=new File(temporary.newFolder(),"aes.zip");char[] password="correct-password".toCharArray();
        net.lingala.zip4j.model.ZipParameters parameters=new net.lingala.zip4j.model.ZipParameters();
        parameters.setFileNameInZip("page.bin");parameters.setEncryptFiles(true);parameters.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
        try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(archive,password)){zip.addStream(new ByteArrayInputStream(new byte[]{1,2,3}),parameters);}
        File output=temporary.newFolder();File old=new File(output,"keep.txt");ArchiveTestTextFiles.writeString(old.toPath(),"original");
        ArchiveSupport.ExtractionResult missing=ArchiveSupport.extractArchiveDetailed(archive,output,true,null,null);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,missing.failure);assertEquals("original",ArchiveTestTextFiles.readString(old.toPath()));
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive,output,true,new char[]{'x'},null).success);assertEquals("original",ArchiveTestTextFiles.readString(old.toPath()));
        assertTrue(ArchiveSupport.extractArchiveDetailed(archive,output,true,password,null).success);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(output,"page.bin").toPath()));
    }
    private File archive(int method,boolean bad)throws Exception{
        Method build=ZipUncachedSelectedRouteTest.class.getDeclaredMethod("archive",int.class,boolean.class,boolean.class);build.setAccessible(true);return (File)build.invoke(fixtures,method,false,bad);
    }
    private boolean probe(boolean central,int flags,int method,int time)throws Exception{
        byte[] bytes=new byte[central?46:30];put(bytes,0,central?0x02014b50:0x04034b50,4);
        put(bytes,central?8:6,flags,2);put(bytes,central?10:8,method,2);put(bytes,central?12:10,time,2);
        File archive=temporary.newFile();Files.write(archive.toPath(),bytes);
        Method probe=ArchiveSupport.class.getDeclaredMethod("hasZipEncryptedHeaderSignature",File.class);probe.setAccessible(true);return (Boolean)probe.invoke(null,archive);
    }
    private static void put(byte[] bytes,int offset,int value,int count){for(int i=0;i<count;i++)bytes[offset+i]=(byte)(value>>>(8*i));}
}
