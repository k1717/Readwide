package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ZipVolumeCaseRoutingTest {
    private final ZipContinuationPublicRouteTest fixture=new ZipContinuationPublicRouteTest();
    @Rule public final TemporaryFolder temp=fixture.temp;
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    private File[] parts(boolean stem)throws Exception{
        Method method=ZipContinuationPublicRouteTest.class.getDeclaredMethod("split",String.class);method.setAccessible(true);File[] files=(File[])method.invoke(fixture,".zip");
        for(int i=0;i<files.length;i++){String name=i==2?"split.ZIP":(stem?"SPLIT":"split")+".Z0"+(i+1);File to=new File(files[i].getParentFile(),name),intermediate=new File(files[i].getParentFile(),"rename-"+i);Files.move(files[i].toPath(),intermediate.toPath());Files.move(intermediate.toPath(),to.toPath());files[i]=to;}return files;
    }
    @Test public void uppercasePartExtensionsListFromFinalVolume()throws Exception{assertEquals(1,ArchiveSupport.listEntries(parts(false)[2],null).size());}
    @Test public void mixedCaseStemsListFromContinuation()throws Exception{assertEquals(1,ArchiveSupport.listEntries(parts(true)[0],null).size());}
    @Test public void uppercaseSelectedVolumesExtractTheSamePayload()throws Exception{
        for(File part:parts(false)){File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(part,"page.bin",out,null);assertTrue(result.failure+" "+result.detail,result.success);assertEquals("A split ZIP payload long enough to cross several volumes",ArchiveTestTextFiles.readString(out.toPath()));}
    }
    @Test public void mixedCaseSetBulkExtractionUsesActualFiles()throws Exception{
        File last=parts(true)[2],out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(last,out,false,null,null);assertTrue(result.failure+" "+result.detail,result.success);assertTrue(new File(out,"page.bin").isFile());
    }
    @Test public void mixedCaseSetHasOneQueueAndSnapshotIdentity()throws Exception{
        File[] files=parts(true);String fingerprint=ArchiveSourceSnapshot.capture(files[2]).cacheFingerprint();for(File file:files){assertEquals(files[2],ArchiveSupport.normalizeExtractionQueueArchive(file));assertEquals(fingerprint,ArchiveSourceSnapshot.capture(file).cacheFingerprint());}
    }
    @Test public void noncanonicalOrdinalWidthStillFailsClosed()throws Exception{
        File[] files=parts(false);File alias=new File(files[0].getParentFile(),"split.Z001");Files.move(files[0].toPath(),alias.toPath());assertThrows(IOException.class,()->ZipVolumeResolver.forSnapshot(files[2]));
    }
}
