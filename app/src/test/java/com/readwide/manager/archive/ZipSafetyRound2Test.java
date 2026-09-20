package com.readwide.manager.archive;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class ZipSafetyRound2Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File zip(String...names)throws Exception {
        File archive=temp.newFile();try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(archive))){for(String name:names){ZipEntry e=new ZipEntry(name);byte[] b=("payload:"+name).getBytes(StandardCharsets.UTF_8);e.setMethod(ZipEntry.STORED);e.setSize(b.length);CRC32 crc=new CRC32();crc.update(b);e.setCrc(crc.getValue());out.putNextEntry(e);out.write(b);out.closeEntry();}}return archive;
    }
    private void io(Action a)throws Exception{try{a.run();fail("Expected IOException");}catch(IOException expected){}}
    interface Action{void run()throws Exception;}
    private void corrupt(File zip)throws Exception{byte[] b=Files.readAllBytes(zip.toPath());int name=(b[26]&255)|((b[27]&255)<<8),extra=(b[28]&255)|((b[29]&255)<<8);b[30+name+extra]^=1;Files.write(zip.toPath(),b);}
    @Test public void dotPrefixRawLookupExtractsExpectedPayload()throws Exception {
        File archive=zip("./chapter.txt"),out=new File(temp.getRoot(),"out.txt");
        assertTrue(LightweightZipArchiveReader.extractSingleEntry(archive,"chapter.txt",out));
        assertEquals("payload:./chapter.txt",Files.readString(out.toPath()));
    }
    @Test public void slashNormalizedRawLookupExtractsExpectedPayload()throws Exception {
        File archive=zip("a//b.txt"),out=new File(temp.getRoot(),"out.txt");assertTrue(LightweightZipArchiveReader.extractSingleEntry(archive,"a/b.txt",out));
        assertEquals("payload:a//b.txt",Files.readString(out.toPath()));
    }
    @Test public void crcMismatchRestoresExistingOutput()throws Exception {
        File archive=zip("page.txt");corrupt(archive);File out=temp.newFile("out.txt");Files.writeString(out.toPath(),"KEEP");
        io(()->LightweightZipArchiveReader.extractSingleEntry(archive,"page.txt",out));assertEquals("KEEP",Files.readString(out.toPath()));
    }
    @Test public void crcMismatchDeletesNewPartialOutput()throws Exception {
        File archive=zip("page.txt");corrupt(archive);File out=new File(temp.getRoot(),"out.txt");io(()->LightweightZipArchiveReader.extractSingleEntry(archive,"page.txt",out));assertFalse(out.exists());
    }
    @Test public void normalizedDuplicateTargetsRejectedBeforeWriting()throws Exception {
        File archive=zip("a.txt","./a.txt");File target=temp.newFolder("out");io(()->LightweightZipArchiveReader.extractArchiveIntoDirectory(archive,target));assertEquals(0,target.list().length);
    }
    @Test public void canonicalAliasTargetsRejectedBeforeWriting()throws Exception {
        File archive=zip("dir/./a.txt","dir/a.txt");File target=temp.newFolder("out");io(()->LightweightZipArchiveReader.extractArchiveIntoDirectory(archive,target));assertEquals(0,target.list().length);
    }
    @Test public void fileAndDirectoryAncestorConflictsRejectedBeforeWriting()throws Exception {
        File archive=zip("dir","dir/page.txt");File target=temp.newFolder("out");io(()->LightweightZipArchiveReader.extractArchiveIntoDirectory(archive,target));assertEquals(0,target.list().length);
    }
    @Test public void unsafePathsRejectArchiveInsteadOfSilentlySkipping()throws Exception {
        File archive=zip("../escape.txt","safe.txt");File target=temp.newFolder("out");io(()->LightweightZipArchiveReader.extractArchiveIntoDirectory(archive,target));assertEquals(0,target.list().length);
    }
    @Test public void preInterruptedExtractionDoesNotTouchExistingTarget()throws Exception {
        File archive=zip("page.txt");File out=temp.newFile("out.txt");Files.writeString(out.toPath(),"KEEP");Thread.currentThread().interrupt();
        try{io(()->LightweightZipArchiveReader.extractSingleEntry(archive,"page.txt",out));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}assertEquals("KEEP",Files.readString(out.toPath()));
    }
    @Test public void boundedWorkersExtractManyMembersWithExactBytes()throws Exception {
        String[] names=new String[120];for(int i=0;i<names.length;i++)names[i]="images/p"+i+".txt";
        File archive=zip(names),target=temp.newFolder("out");assertTrue(LightweightZipArchiveReader.extractArchiveIntoDirectory(archive,target));
        for(String name:names)assertEquals("payload:"+name,Files.readString(new File(target,name).toPath()));
    }
    @Test public void parallelFailureLeavesNoActiveWritersOrBackupFiles()throws Exception {
        File archive=zip("bad.txt","b.txt","c.txt","d.txt","e.txt");corrupt(archive);File target=temp.newFolder("out");File old=new File(target,"bad.txt");Files.writeString(old.toPath(),"KEEP");
        io(()->LightweightZipArchiveReader.extractArchiveIntoDirectory(archive,target));assertEquals("KEEP",Files.readString(old.toPath()));
        Map<String,Long> snapshot=new TreeMap<>();for(File f:target.listFiles()){assertFalse(f.getName().contains("rar-extract-backup"));snapshot.put(f.getName(),f.length());}
        Thread.sleep(25);Map<String,Long> after=new TreeMap<>();for(File f:target.listFiles())after.put(f.getName(),f.length());assertEquals(snapshot,after);
    }
    @Test public void repeatedRollbackCloseDoesNotDeleteRestoredOriginal()throws Exception {
        File out=temp.newFile("old.txt");Files.writeString(out.toPath(),"ORIGINAL");RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);Files.writeString(out.toPath(),"PARTIAL");guard.close();guard.close();assertEquals("ORIGINAL",Files.readString(out.toPath()));
    }
    @Test public void repeatedCommittedClosePreservesNewFile()throws Exception {
        File out=temp.newFile("old.txt");Files.writeString(out.toPath(),"ORIGINAL");RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);Files.writeString(out.toPath(),"NEW");guard.commit();guard.close();guard.close();assertEquals("NEW",Files.readString(out.toPath()));
    }
    @Test public void lateCommitCannotResurrectClosedGuard()throws Exception {
        File out=temp.newFile("old.txt");RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);guard.close();try{guard.commit();fail();}catch(IllegalStateException expected){}
    }
    @Test public void concurrentParentCreationIsAcceptedAndFileParentIsRejected()throws Exception {
        File realParent=new File(temp.getRoot(),"race-parent");
        File racingParent=new File(realParent.getPath()) {
            @Override public boolean mkdirs(){super.mkdirs();return false;}
        };
        File output=new File(realParent,"out.txt") {
            @Override public File getParentFile(){return racingParent;}
        };
        RarOutputFileGuard.ensureParentDirectory(output);
        assertTrue(realParent.isDirectory());
        File fileParent=temp.newFile("not-a-directory");
        io(()->RarOutputFileGuard.ensureParentDirectory(new File(fileParent,"out.txt")));
    }

}
