package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class RarCbrQueueIdentityTest {
    private final CbrNumberedSplitRouteTest fixture=new CbrNumberedSplitRouteTest();
    @Rule public final TemporaryFolder temp=fixture.temporary;
    private File[] parts(String first,String last)throws Exception{
        Method method=CbrNumberedSplitRouteTest.class.getDeclaredMethod("fixture",String.class,String.class);method.setAccessible(true);return (File[])method.invoke(fixture,first,last);
    }
    @Test public void allCbrVolumesShareOneQueueItem()throws Exception{sameOwner(parts("cbr","CBR"));}
    @Test public void mixedRarCbrVolumesShareOneQueueItem()throws Exception{sameOwner(parts("rar","cbr"));sameOwner(parts("cbr","rar"));}
    @Test public void outputFolderOmitsNumberedCbrSuffix()throws Exception{
        for(File file:parts("CBR","cbr"))assertEquals("book",ArchiveSupport.getArchiveOutputBaseName(file,"fallback"));
    }
    @Test public void incompleteCbrSetKeepsSelectedQueueItem()throws Exception{
        File[] files=parts("cbr","cbr");Files.delete(files[0].toPath());assertEquals(files[1],ArchiveSupport.normalizeExtractionQueueArchive(files[1]));
    }
    @Test public void duplicateCbrOrdinalDoesNotChooseAQueueOwner()throws Exception{
        File[] files=parts("rar","cbr");Files.copy(files[0].toPath(),new File(files[0].getParentFile(),"book.part01.cbr").toPath());assertEquals(files[1],ArchiveSupport.normalizeExtractionQueueArchive(files[1]));
    }
    @Test public void standaloneComicRemainsItsOwnOwner()throws Exception{
        File file=temp.newFile("book.cbr");assertEquals(file,ArchiveSupport.normalizeExtractionQueueArchive(file));assertEquals("book",ArchiveSupport.getArchiveOutputBaseName(file,"fallback"));
    }
    private static void sameOwner(File[] files){for(File file:files)assertEquals(files[0],ArchiveSupport.normalizeExtractionQueueArchive(file));}
}
