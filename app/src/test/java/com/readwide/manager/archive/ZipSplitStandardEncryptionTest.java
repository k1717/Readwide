package com.readwide.manager.archive;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Guard the stored/deflated encrypted methods newly routed through split sources. */
@RunWith(Parameterized.class)
public final class ZipSplitStandardEncryptionTest {
    @Parameterized.Parameters(name="{0}/{1}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{
        {CompressionMethod.STORE,EncryptionMethod.AES},{CompressionMethod.DEFLATE,EncryptionMethod.AES},
        {CompressionMethod.STORE,EncryptionMethod.ZIP_STANDARD},{CompressionMethod.DEFLATE,EncryptionMethod.ZIP_STANDARD}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final CompressionMethod method;private final EncryptionMethod encryption;
    private static final char[] PASSWORD="secret-split".toCharArray();
    private final byte[] payload=new byte[140000];
    public ZipSplitStandardEncryptionTest(CompressionMethod method,EncryptionMethod encryption){this.method=method;this.encryption=encryption;new Random(4321).nextBytes(payload);}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    private File fixture()throws Exception{
        File input=temp.newFile("page.bin");Files.write(input.toPath(),payload);File output=new File(temp.newFolder(),"book.zip");ZipParameters parameters=new ZipParameters();parameters.setCompressionMethod(method);parameters.setEncryptFiles(true);parameters.setEncryptionMethod(encryption);
        new ZipFile(output,PASSWORD).createSplitZipFile(Collections.singletonList(input),parameters,true,65536);return output;
    }
    @Test public void firstAndLastVolumeReadWithPassword()throws Exception{
        File last=fixture();for(File part:new File[]{new File(last.getParentFile(),"book.z01"),last,last}){
            File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(part,"page.bin",out,PASSWORD.clone());assertTrue(result.failure+" "+result.detail,result.success);assertArrayEquals(payload,Files.readAllBytes(out.toPath()));
        }
    }
    @Test public void bulkReadWithPassword()throws Exception{
        File last=fixture(),out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(last,out,false,PASSWORD.clone(),null);assertTrue(result.failure+" "+result.detail,result.success);assertArrayEquals(payload,Files.readAllBytes(new File(out,"page.bin").toPath()));
    }
    @Test public void cacheDoesNotRememberPassword()throws Exception{
        File last=fixture(),out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(last,"page.bin",out,PASSWORD.clone()).success);
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,ArchiveSupport.extractSingleEntryDetailed(last,"page.bin",out,null).failure);assertArrayEquals(payload,Files.readAllBytes(out.toPath()));
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(last,"page.bin",out,"wrong".toCharArray()).success);assertArrayEquals(payload,Files.readAllBytes(out.toPath()));
    }
    @Test public void listingRequiresPasswordWithoutLeakingOutput()throws Exception{
        File last=fixture();assertThrows(ArchiveSupport.PasswordRequiredException.class,()->ArchiveSupport.listEntries(last,null));assertEquals(1,ArchiveSupport.listEntries(last,PASSWORD.clone()).size());
    }
}
