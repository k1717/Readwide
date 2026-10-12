package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import net.lingala.zip4j.model.enums.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Cross-format public boundaries must preserve a pending interrupt and an existing destination. */
@RunWith(Parameterized.class)
public final class ArchivePublicInterruptionMatrixTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{"zip"},{"zip-aes"},{"rar4"},{"rar5"},{"7z-aes"},{"tar-gz"}});}
    @Rule public final TemporaryFolder temp;private final Object owner;private final String kind,path;private final char[] password;
    public ArchivePublicInterruptionMatrixTest(String kind){
        this.kind=kind;
        if(kind.equals("zip")){ZipContinuationPublicRouteTest f=new ZipContinuationPublicRouteTest();owner=f;temp=f.temp;path="page.bin";password=null;}
        else if(kind.equals("zip-aes")){ZipSplitStandardEncryptionTest f=new ZipSplitStandardEncryptionTest(CompressionMethod.DEFLATE,EncryptionMethod.AES);owner=f;temp=f.temp;path="page.bin";password="secret-split".toCharArray();}
        else if(kind.startsWith("rar")){RarDuplicateSelectedMetadataTest f=new RarDuplicateSelectedMetadataTest(kind.equals("rar4")?4:5);owner=f;temp=f.temp;path="page.bin";password=null;}
        else if(kind.equals("7z-aes")){SevenZSpecialNameIdentityTest f=new SevenZSpecialNameIdentityTest(true,true);owner=f;temp=f.temp;path="page.bin";password=Round4Fixtures.PASSWORD;}
        else{TarLongUnicodeRouteTest f=new TarLongUnicodeRouteTest(".tar.gz",3);owner=f;temp=f.temp;path="folder/"+"\uD55C\uAE00\uD83D\uDCD6".repeat(12)+".bin";password=null;}
    }
    @After public void clear(){Thread.interrupted();ZipxAesArchiveReader.clearCachedIndexes();TarEntryIndex.clear();}
    private File fixture()throws Exception{
        if(kind.equals("zip")){File[] files=(File[])call("split",new Class<?>[]{String.class},".zip");return files[files.length-1];}
        if(kind.startsWith("rar"))return (File)call("fixture",new Class<?>[]{boolean.class,boolean.class},false,false);
        if(kind.equals("7z-aes"))return (File)call("fixture",new Class<?>[]{String.class},"page.bin");
        return (File)call("fixture",new Class<?>[0]);
    }
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}
    @Test public void interruptedListingDoesNotPublishMembers()throws Exception{
        File archive=fixture();Thread.currentThread().interrupt();try{assertThrows(IOException.class,()->ArchiveSupport.listEntries(archive,password));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
    }
    @Test public void interruptedWarmSelectionPreservesPayloadAndFlag()throws Exception{
        File archive=fixture(),out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive,path,out,password).success);byte[] original=Files.readAllBytes(out.toPath());Thread.currentThread().interrupt();try{assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,path,out,password).success);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}assertArrayEquals(original,Files.readAllBytes(out.toPath()));
    }
    @Test public void interruptedBulkReplacementPreservesOldDirectory()throws Exception{
        File archive=fixture(),out=temp.newFolder();ArchiveTestTextFiles.writeString(new File(out,"keep").toPath(),"keep");Thread.currentThread().interrupt();try{assertFalse(ArchiveSupport.extractArchiveDetailed(archive,out,true,password,null).success);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}assertArrayEquals(new String[]{"keep"},out.list());assertEquals("keep",ArchiveTestTextFiles.readString(new File(out,"keep").toPath()));
    }
    @Test public void missingSelectionAfterWarmReadPreservesExistingOutput()throws Exception{
        File archive=fixture(),out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive,path,out,password).success);byte[] original=Files.readAllBytes(out.toPath());assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"missing-entry",out,password).success);assertArrayEquals(original,Files.readAllBytes(out.toPath()));
    }
}
