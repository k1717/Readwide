package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ZipContinuationPublicRouteTest {
    private final ZipxAesArchiveReaderTest fixtures=new ZipxAesArchiveReaderTest();
    @Rule public final TemporaryFolder temp=fixtures.temp;
    private static final byte[] DATA="A split ZIP payload long enough to cross several volumes".getBytes(StandardCharsets.UTF_8);
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    private File[] split(String suffix)throws Exception {
        File source=temp.newFile("source-"+System.nanoTime()+".zip");
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(source)){
            ZipArchiveEntry entry=new ZipArchiveEntry("page.bin");entry.setMethod(0);entry.setSize(DATA.length);
            java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(DATA);entry.setCrc(crc.getValue());
            zip.putArchiveEntry(entry);zip.write(DATA);zip.closeArchiveEntry();
        }
        Method method=ZipxAesArchiveReaderTest.class.getDeclaredMethod("splitZipx",File.class,int[].class);method.setAccessible(true);
        File[] parts=(File[])method.invoke(fixtures,source,new int[]{40,60});
        File last=new File(parts[2].getParentFile(),"split"+suffix);Files.move(parts[2].toPath(),last.toPath());parts[2]=last;return parts;
    }
    @Test public void firstAndMiddleVolumeOpenSelectedFile()throws Exception {
        for(String suffix:new String[]{".zip",".zipx",".cbz"})for(File part:split(suffix)){
            File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(part,"page.bin",out,null);
            assertTrue(part+" "+result.failure+" "+result.detail,result.success);assertArrayEquals(DATA,Files.readAllBytes(out.toPath()));
        }
    }
    @Test public void continuationListsAndBulkExtracts()throws Exception {
        File part=split(".zip")[1];assertEquals(1,ArchiveSupport.listEntries(part,null).size());
        File out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(part,out,false,null,null);
        assertTrue(result.detail,result.success);assertArrayEquals(DATA,Files.readAllBytes(new File(out,"page.bin").toPath()));
    }
    @Test public void queueHasOneOwnerForAllZipVolumes()throws Exception {
        File[] parts=split(".cbz");for(File part:parts)assertEquals(parts[2],ArchiveSupport.normalizeExtractionQueueArchive(part));
    }
    @Test public void missingPartPreservesOutput()throws Exception {
        File[] parts=split(".zip");Files.delete(parts[0].toPath());File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(parts[1],"page.bin",out,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void ambiguousFinalVolumePreservesOutput()throws Exception {
        File[] parts=split(".zip");Files.copy(parts[2].toPath(),new File(parts[2].getParentFile(),"split.cbz").toPath());
        File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");assertFalse(ArchiveSupport.extractSingleEntryDetailed(parts[0],"page.bin",out,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void rarRolloverSuffixKeepsItsTypeWithoutZipCompanion()throws Exception {
        File part=temp.newFile("book.z01");assertEquals(ArchiveSupport.Type.RAR,ArchiveSupport.getSupportedArchiveType(part));
    }
}
