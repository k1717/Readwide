package com.readwide.manager.archive;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import org.apache.commons.compress.archivers.sevenz.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SevenZEmptyPublicRouteTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private File empty()throws Exception{File file=temp.newFile("empty-"+System.nanoTime()+".7z");try(SevenZOutputFile out=new SevenZOutputFile(file)){}return file;}
    private void success(File archive)throws Exception{assertEquals(0,ArchiveSupport.listEntries(archive,null).size());File out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,out,false,null,null);assertTrue(result.failure+" "+result.detail,result.success);assertTrue(out.isDirectory());assertEquals(0,out.list().length);}
    @Test public void commonsGeneratedEmptyArchiveExtracts()throws Exception{success(empty());}
    @Test public void explicitEmptyFilesInfoExtracts()throws Exception{success(Round5Fixtures.emptySevenZ(temp.newFolder(),0,new byte[0]));}
    @Test public void emptySplitArchiveExtractsFromLaterPart()throws Exception{
        byte[] bytes=Files.readAllBytes(empty().toPath());File dir=temp.newFolder(),first=new File(dir,"book.7z.001"),last=new File(dir,"book.7z.002");Files.write(first.toPath(),Arrays.copyOfRange(bytes,0,7));Files.write(last.toPath(),Arrays.copyOfRange(bytes,7,bytes.length));success(last);
    }
    @Test public void requestedEmptyReplacementRemovesOldTree()throws Exception{
        File out=temp.newFolder();ArchiveTestTextFiles.writeString(new File(out,"old").toPath(),"old");assertTrue(ArchiveSupport.extractArchiveDetailed(empty(),out,true,null,null).success);assertEquals(0,out.list().length);
    }
    @Test public void missingSelectedEntryPreservesTarget()throws Exception{
        File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");assertFalse(ArchiveSupport.extractSingleEntryDetailed(empty(),"missing",out,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void damagedStartHeaderPreservesOldTree()throws Exception{
        File file=empty();byte[] bytes=Files.readAllBytes(file.toPath());bytes[8]^=1;Files.write(file.toPath(),bytes);File out=temp.newFolder();ArchiveTestTextFiles.writeString(new File(out,"keep").toPath(),"keep");assertFalse(ArchiveSupport.extractArchiveDetailed(file,out,true,null,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(new File(out,"keep").toPath()));
    }
    @Test public void zeroLengthFileIsStillMaterialized()throws Exception{
        File file=temp.newFile("zero.7z");try(SevenZOutputFile zip=new SevenZOutputFile(file)){SevenZArchiveEntry e=new SevenZArchiveEntry();e.setName("zero.bin");zip.putArchiveEntry(e);zip.closeArchiveEntry();}File out=new File(temp.newFolder(),"out");assertTrue(ArchiveSupport.extractArchiveDetailed(file,out,false,null,null).success);assertTrue(new File(out,"zero.bin").isFile());assertEquals(0,new File(out,"zero.bin").length());
    }
}
