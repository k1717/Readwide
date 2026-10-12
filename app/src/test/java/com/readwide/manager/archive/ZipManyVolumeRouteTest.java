package com.readwide.manager.archive;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.*;
import static org.junit.Assert.*;

public final class ZipManyVolumeRouteTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void selectedAndCachedPayloadCrossManyVolumes()throws Exception{
        File archive=fixture(temporary.newFolder(),16);byte[] expected=content(16);
        assertTrue(ZipVolumeResolver.forSnapshot(archive).size()>16);
        for(int i=0;i<2;i++){
            File output=temporary.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",output,null);
            assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(expected,Files.readAllBytes(output.toPath()));
        }
    }
    @Test public void publicBulkAcrossVolumesRetainsExactPayload()throws Exception{
        File archive=fixture(temporary.newFolder(),4),output=new File(temporary.newFolder(),"out");
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,output,false,null,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(content(4),Files.readAllBytes(new File(output,"page.bin").toPath()));
    }
    @Test public void cachedIndexIsInvalidatedByEarlierVolumeTruncation()throws Exception{
        File archive=fixture(temporary.newFolder(),4),output=temporary.newFile();
        assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",output,null).success);
        File first=ZipVolumeResolver.forSnapshot(archive).get(0);try(RandomAccessFile file=new RandomAccessFile(first,"rw")){file.setLength(16);}
        ArchiveTestTextFiles.writeString(output.toPath(),"original");assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",output,null).success);assertEquals("original",ArchiveTestTextFiles.readString(output.toPath()));
    }
    static byte[] content(int blocks){byte[] data=new byte[65536*blocks];new Random(741).nextBytes(data);return data;}
    static File fixture(File directory,int blocks)throws Exception{
        byte[] data=content(blocks);ByteArrayOutputStream packed=new ByteArrayOutputStream();
        try(XZOutputStream xz=new XZOutputStream(packed,new LZMA2Options(0))){xz.write(data);}
        File archive=new File(directory,"many.zip");try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive,65536)){
            ZipArchiveEntry entry=new ZipArchiveEntry("page.bin");entry.setMethod(95);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
        }
        return archive;
    }
}
