package com.readwide.manager.archive;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.tukaani.xz.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class TarEmptyPublicRouteTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{".tar"},{".tar.gz"},{".tar.bz2"},{".tar.xz"},{".tar.lzma"},{".tar.lz4"}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();private final String suffix;
    public TarEmptyPublicRouteTest(String suffix){this.suffix=suffix;}
    private File file(byte[] bytes)throws Exception{
        File file=temp.newFile("archive-"+System.nanoTime()+suffix);OutputStream raw=new FileOutputStream(file),out=raw;
        if(suffix.endsWith(".gz"))out=new GZIPOutputStream(raw);else if(suffix.endsWith(".bz2"))out=new BZip2CompressorOutputStream(raw);else if(suffix.endsWith(".xz"))out=new XZOutputStream(raw,new LZMA2Options(0));else if(suffix.endsWith(".lzma"))out=new LZMAOutputStream(raw,new LZMA2Options(0),bytes.length);else if(suffix.endsWith(".lz4"))out=new FramedLZ4CompressorOutputStream(raw);
        try(OutputStream stream=out){stream.write(bytes);}return file;
    }
    @Test public void completeEmptyTarCreatesEmptyDirectory()throws Exception{
        File file=file(new byte[1024]);assertTrue(ArchiveSupport.listEntries(file,null).isEmpty());File out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(file,out,false,null,null);assertTrue(result.failure+" "+result.detail,result.success);assertTrue(out.isDirectory());assertEquals(0,out.list().length);
    }
    @Test public void missingEndRecordsPreserveExistingDirectory()throws Exception{
        for(int length:new int[]{0,511,512}){File out=temp.newFolder();ArchiveTestTextFiles.writeString(new File(out,"keep").toPath(),"keep");assertFalse("length="+length,ArchiveSupport.extractArchiveDetailed(file(new byte[length]),out,true,null,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(new File(out,"keep").toPath()));}
    }
    @Test public void directoryOnlyTarStillMaterializesDirectory()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(TarArchiveOutputStream tar=new TarArchiveOutputStream(bytes)){tar.putArchiveEntry(new TarArchiveEntry("folder/"));tar.closeArchiveEntry();}File out=new File(temp.newFolder(),"out");assertTrue(ArchiveSupport.extractArchiveDetailed(file(bytes.toByteArray()),out,false,null,null).success);assertTrue(new File(out,"folder").isDirectory());
    }
    @Test public void nonexistentMemberPreservesSelectedOutput()throws Exception{
        File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");assertFalse(ArchiveSupport.extractSingleEntryDetailed(file(new byte[1024]),"missing",out,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
}
