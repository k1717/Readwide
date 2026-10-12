package com.readwide.manager.archive;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ZipEmptyArchiveTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private File empty(String comment)throws Exception{
        File file=temp.newFile("empty-"+System.nanoTime()+".zip");try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(file)){zip.setComment(comment);}return file;
    }
    private void successfulEmpty(File archive)throws Exception{
        assertTrue(ArchiveSupport.listEntries(archive,null).isEmpty());File out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,out,false,null,null);assertTrue(result.failure+" "+result.detail,result.success);assertTrue(out.isDirectory());assertEquals(0,out.list().length);
    }
    @Test public void emptyZipCreatesAnEmptyDirectory()throws Exception{successfulEmpty(empty(""));}
    @Test public void emptyZipWithCommentCreatesAnEmptyDirectory()throws Exception{successfulEmpty(empty("No files in this archive"));}
    @Test public void longCommentDoesNotHideValidEmptyZip()throws Exception{successfulEmpty(empty("c".repeat(65535)));}
    @Test public void emptySplitZipCreatesAnEmptyDirectory()throws Exception{
        File folder=temp.newFolder(),last=new File(folder,"split.zip");byte[] end=Files.readAllBytes(empty("").toPath());end[4]=1;end[6]=1;Files.write(last.toPath(),end);Files.write(new File(folder,"split.z01").toPath(),new byte[]{'P','K',7,8});successfulEmpty(last);
    }
    @Test public void emptyZipCanReplaceAnExistingDirectoryWhenRequested()throws Exception{
        File archive=empty(""),out=temp.newFolder();ArchiveTestTextFiles.writeString(new File(out,"old").toPath(),"old");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,out,true,null,null);assertTrue(result.detail,result.success);assertEquals(0,out.list().length);
    }
    @Test public void absentSelectedEntryPreservesExistingOutput()throws Exception{
        File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");assertFalse(ArchiveSupport.extractSingleEntryDetailed(empty(""),"missing",out,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void truncatedEmptyZipPreservesExistingDirectory()throws Exception{
        File archive=empty("");byte[] bytes=Files.readAllBytes(archive.toPath());Files.write(archive.toPath(),Arrays.copyOf(bytes,bytes.length-1));File out=temp.newFolder();ArchiveTestTextFiles.writeString(new File(out,"keep").toPath(),"keep");assertFalse(ArchiveSupport.extractArchiveDetailed(archive,out,true,null,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(new File(out,"keep").toPath()));
    }
    @Test public void directoryOnlyZipStillExtracts()throws Exception{
        File file=temp.newFile("directories.zip");try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(file)){zip.putArchiveEntry(new ZipArchiveEntry("folder/"));zip.closeArchiveEntry();}File out=new File(temp.newFolder(),"out");assertTrue(ArchiveSupport.extractArchiveDetailed(file,out,false,null,null).success);assertTrue(new File(out,"folder").isDirectory());
    }
}
