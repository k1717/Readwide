package com.readwide.manager.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Snapshot tests use the public pre-existing API, so the same tests run on baseline. */
public class ZipSnapshotRound6Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private List<File> set(int count)throws Exception{return Round6Fixtures.zipSet(temp.newFolder(),count,".zip",false);}
    private File last(List<File> files){return files.get(files.size()-1);}
    @Test public void modifiedEarlierPartInvalidatesFinalZipSnapshot()throws Exception{
        List<File> f=set(2);File archive=last(f);ArchiveSourceSnapshot before=ArchiveSourceSnapshot.capture(archive);
        Files.write(f.get(0).toPath(),new byte[]{9,8});assertFalse(before.matches(archive));
        assertNotEquals(before.cacheFingerprint(),ArchiveSourceSnapshot.capture(archive).cacheFingerprint());
    }
    @Test public void sameSetHasSameIdentityFromEveryPart()throws Exception{
        List<File>f=set(4);String key=ArchiveSourceSnapshot.capture(last(f)).cacheFingerprint();
        for(File part:f)assertEquals(key,ArchiveSourceSnapshot.capture(part).cacheFingerprint());
    }
    @Test public void missingMiddlePartFailsCapture()throws Exception{
        List<File>f=set(3);Files.delete(f.get(1).toPath());ArchiveSourceSnapshot s=ArchiveSourceSnapshot.capture(last(f));assertFalse(s.matches(last(f)));
    }
    @Test public void missingAllEarlierPartsFailsCapture()throws Exception{
        List<File>f=set(2);Files.delete(f.get(0).toPath());Files.delete(f.get(1).toPath());assertFalse(ArchiveSourceSnapshot.capture(last(f)).matches(last(f)));
    }
    @Test public void standaloneZipIgnoresUnrelatedSplitSiblings()throws Exception{
        List<File>f=set(0);File archive=last(f);ArchiveSourceSnapshot s=ArchiveSourceSnapshot.capture(archive);
        File orphan=Round4Fixtures.write(archive.getParentFile(),"book.z01",new byte[]{1});assertTrue(s.matches(archive));
        assertNotEquals(s.cacheFingerprint(),ArchiveSourceSnapshot.capture(orphan).cacheFingerprint());
    }
    @Test public void undeclaredExtraPartDoesNotInvalidateArchive()throws Exception{
        List<File>f=set(2);ArchiveSourceSnapshot s=ArchiveSourceSnapshot.capture(last(f));
        Round4Fixtures.write(last(f).getParentFile(),"book.z03",new byte[]{1});assertTrue(s.matches(last(f)));
    }
    @Test public void zip64LocatorTracksEarlierParts()throws Exception{
        List<File>f=Round6Fixtures.zipSet(temp.newFolder(),2,".zip",true);ArchiveSourceSnapshot s=ArchiveSourceSnapshot.capture(last(f));assertTrue(s.matches(last(f)));
        Files.write(f.get(1).toPath(),new byte[]{2,3});assertFalse(s.matches(last(f)));
    }
    @Test public void zipxAndCbzMultipartIdentity()throws Exception{
        for(String ext:new String[]{".zipx",".cbz"}){
            List<File>f=Round6Fixtures.zipSet(temp.newFolder(),2,ext,false);ArchiveSourceSnapshot s=ArchiveSourceSnapshot.capture(last(f));
            assertEquals(s.cacheFingerprint(),ArchiveSourceSnapshot.capture(f.get(0)).cacheFingerprint());Files.write(f.get(0).toPath(),new byte[]{3,4});assertFalse(s.matches(last(f)));
        }
    }
    @Test public void directoryDisguisedAsPartFailsCapture()throws Exception{
        List<File>f=set(2);Files.delete(f.get(0).toPath());assertTrue(f.get(0).mkdir());assertFalse(ArchiveSourceSnapshot.capture(last(f)).matches(last(f)));
    }
    @Test public void noncanonicalAliasFailsRatherThanUsingWrongBackendName()throws Exception{
        List<File>f=set(2);Round4Fixtures.write(last(f).getParentFile(),"book.z001",new byte[]{1});assertFalse(ArchiveSourceSnapshot.capture(last(f)).matches(last(f)));
    }
    @Test public void impossibleZip64CountFailsBeforeOrdinalAllocation()throws Exception{
        File dir=temp.newFolder();ByteArrayOutputStream b=new ByteArrayOutputStream();Round6Fixtures.le(b,0x07064b50,4);Round6Fixtures.le(b,0xfffffffeL,4);Round6Fixtures.le(b,0,8);Round6Fixtures.le(b,0xffffffffL,4);
        b.write(Round6Fixtures.endRecord(65535,65535,new byte[0]));File f=Round4Fixtures.write(dir,"book.zip",b.toByteArray());
        assertFalse(ArchiveSourceSnapshot.capture(f).matches(f));
    }
    @Test public void missingZip64LocatorFailsCapture()throws Exception{
        File f=Round4Fixtures.write(temp.newFolder(),"book.zip",Round6Fixtures.endRecord(65535,65535,new byte[0]));assertFalse(ArchiveSourceSnapshot.capture(f).matches(f));
    }
    @Test public void contradictoryZip64CountsFailCapture()throws Exception{
        List<File>f=Round6Fixtures.zipSet(temp.newFolder(),2,".zip",true);byte[]data=Files.readAllBytes(last(f).toPath());data[16]=0;Files.write(last(f).toPath(),data);
        assertFalse(ArchiveSourceSnapshot.capture(last(f)).matches(last(f)));
    }
    @Test public void maximumCommentDoesNotHideSplitIdentity()throws Exception{
        List<File>f=set(1);Files.write(last(f).toPath(),Round6Fixtures.endRecord(1,1,new byte[65535]));ArchiveSourceSnapshot s=ArchiveSourceSnapshot.capture(last(f));assertTrue(s.matches(last(f)));
        Files.write(f.get(0).toPath(),new byte[]{3,4});assertFalse(s.matches(last(f)));
    }
    @Test public void ambiguousEndRecordInsideCommentDisablesReuse()throws Exception{
        File f=Round4Fixtures.write(temp.newFolder(),"book.zip",Round6Fixtures.endRecord(0,0,Round6Fixtures.endRecord(0,0,new byte[0])));assertFalse(ArchiveSourceSnapshot.capture(f).matches(f));
    }
    @Test public void cancelledCaptureCannotMatchOldKey()throws Exception{
        List<File>f=set(2);ArchiveSourceSnapshot old=ArchiveSourceSnapshot.capture(last(f));
        try{Thread.currentThread().interrupt();assertNotEquals(old.cacheFingerprint(),ArchiveSourceSnapshot.capture(last(f)).cacheFingerprint());}
        finally{Thread.interrupted();}
    }
    @Test public void largerThan64PartSetIsCaptured()throws Exception{
        List<File>f=set(131);ArchiveSourceSnapshot s=ArchiveSourceSnapshot.capture(last(f));assertTrue(s.matches(last(f)));
        Files.write(f.get(100).toPath(),new byte[]{7,8});assertFalse(s.matches(last(f)));
    }
    @Test public void unchangedOpaqueSingleZipRemainsCacheable()throws Exception{
        File f=Round4Fixtures.write(temp.newFolder(),"opaque.zip",new byte[]{1,2,3});assertTrue(ArchiveSourceSnapshot.capture(f).matches(f));
    }
    @Test public void damagedFinalZipWithPartSiblingsCannotReuseCache()throws Exception{
        List<File>f=set(2);Files.write(last(f).toPath(),new byte[]{1,2,3});assertFalse(ArchiveSourceSnapshot.capture(last(f)).matches(last(f)));
    }
    @Test public void trailingBytesAfterEndRecordCannotHidePartsFromCache()throws Exception{
        List<File>f=set(2);Files.write(last(f).toPath(),new byte[]{42},java.nio.file.StandardOpenOption.APPEND);assertFalse(ArchiveSourceSnapshot.capture(last(f)).matches(last(f)));
    }

}
