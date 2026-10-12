package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ZipContinuationSnapshotTest {
    private final ZipContinuationPublicRouteTest fixture=new ZipContinuationPublicRouteTest();
    @Rule public final TemporaryFolder temp=fixture.temp;
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    private File[] parts()throws Exception{
        Method method=ZipContinuationPublicRouteTest.class.getDeclaredMethod("split",String.class);method.setAccessible(true);return (File[])method.invoke(fixture,".zip");
    }
    @Test public void everyVolumeHasTheSameStableFingerprint()throws Exception{
        File[] parts=parts();String expected=ArchiveSourceSnapshot.capture(parts[2]).cacheFingerprint();
        for(File part:parts){ArchiveSourceSnapshot snapshot=ArchiveSourceSnapshot.capture(part);assertTrue(snapshot.matches(part));assertEquals(expected,snapshot.cacheFingerprint());}
    }
    @Test public void firstVolumeSnapshotContainsTheWholeChain()throws Exception{
        File[] parts=parts();ArchiveSourceSnapshot snapshot=ArchiveSourceSnapshot.capture(parts[0]);for(File part:parts)assertTrue(snapshot.containsFile(part));
    }
    @Test public void changedMiddleVolumeInvalidatesFirstAndLastSnapshots()throws Exception{
        File[] parts=parts();ArchiveSourceSnapshot first=ArchiveSourceSnapshot.capture(parts[0]),last=ArchiveSourceSnapshot.capture(parts[2]);assertTrue(first.matches(parts[0]));assertTrue(last.matches(parts[2]));
        assertTrue(parts[1].setLastModified(parts[1].lastModified()+5000));assertFalse(first.matches(parts[0]));assertFalse(last.matches(parts[2]));
    }
    @Test public void missingMiddleVolumeNeverReusesAnIdentity()throws Exception{
        File[] parts=parts();ArchiveSourceSnapshot before=ArchiveSourceSnapshot.capture(parts[0]);Files.delete(parts[1].toPath());assertFalse(before.matches(parts[0]));
        ArchiveSourceSnapshot broken=ArchiveSourceSnapshot.capture(parts[0]);assertFalse(broken.matches(parts[0]));assertNotEquals(broken.cacheFingerprint(),ArchiveSourceSnapshot.capture(parts[0]).cacheFingerprint());
    }
    @Test public void unrelatedZipFinalDoesNotRedirectRarRollover()throws Exception{
        File selected=temp.newFile("book.z01");temp.newFile("other.zip");assertEquals(ArchiveSupport.Type.RAR,ArchiveSupport.getSupportedArchiveType(selected));assertFalse(ArchiveSourceSnapshot.capture(selected).matches(selected));
    }
    @Test public void ambiguousFinalNeverYieldsReusableSnapshot()throws Exception{
        File[] parts=parts();Files.copy(parts[2].toPath(),new File(parts[2].getParentFile(),"split.cbz").toPath());assertFalse(ArchiveSourceSnapshot.capture(parts[0]).matches(parts[0]));
    }
}
