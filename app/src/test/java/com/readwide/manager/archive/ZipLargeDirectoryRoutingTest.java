package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.tukaani.xz.*;
import static org.junit.Assert.*;

/** A fast metadata-buffer limit must not silently remove early archive members. */
@RunWith(Parameterized.class)
public final class ZipLargeDirectoryRoutingTest {
    private static final int COUNT=72;
    @Parameterized.Parameters(name="method={0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{0},{95}});}
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final int method;
    public ZipLargeDirectoryRoutingTest(int method){this.method=method;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void listingIncludesEveryCanonicalNameBeyondFastBufferLimit()throws Exception{listing(fixture(false),false);}
    @Test public void listingRetainsCompleteLegacyNameCorpus()throws Exception{listing(fixture(true),true);}
    @Test public void coldAndCachedSelectionUseFirstAndLastCanonicalIdentities()throws Exception{
        File archive=fixture(false);for(int index:new int[]{0,COUNT-1,0}){
            File output=temporary.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,name(index),output,null);
            assertTrue(result.failure+": "+result.detail,result.success);assertEquals("payload-"+index,ArchiveTestTextFiles.readString(output.toPath()));
        }
    }
    @Test public void bulkOutputUsesTheSameCanonicalNames()throws Exception{
        File archive=fixture(false),output=new File(temporary.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,output,false,null,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertEquals(COUNT,output.list().length);
        for(int i=0;i<COUNT;i++)assertEquals("payload-"+i,ArchiveTestTextFiles.readString(new File(output,name(i)).toPath()));
    }
    private void listing(File archive,boolean legacy)throws Exception{
        List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(archive,null);assertEquals(COUNT,entries.size());
        Set<String> actual=new HashSet<>(),expected=new HashSet<>();
        for(ArchiveSupport.EntryInfo entry:entries){actual.add(entry.path);assertFalse(entry.directory);}
        for(int i=0;i<COUNT;i++)expected.add(legacy?legacyName(i):name(i));assertEquals(expected,actual);
    }
    private File fixture(boolean legacy)throws Exception{
        File archive=temporary.newFile("large-directory-"+System.nanoTime()+".zip");
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive)){
            zip.setEncoding("MS949");zip.setUseLanguageEncodingFlag(false);zip.setUseZip64(Zip64Mode.Never);
            for(int i=0;i<COUNT;i++){
                String raw=legacy?legacyName(i):"legacy-"+i+".bin";byte[] data=("payload-"+i).getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
                if(method==0)packed.write(data);else try(XZOutputStream xz=new XZOutputStream(packed,new LZMA2Options(0))){xz.write(data);}
                ZipArchiveEntry entry=new ZipArchiveEntry(raw);entry.setComment("C".repeat(60000));entry.setMethod(method);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());
                if(!legacy)entry.addExtraField(new UnicodePathExtraField(name(i),raw.getBytes("MS949")));
                zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
            }
        }
        assertTrue(archive.length()>4L*1024*1024);return archive;
    }
    private static String name(int i){return "page-"+i+"-\uD398\uC774\uC9C0.bin";}
    private static String legacyName(int i){return "\uD55C\uAE00\uBB38\uC11C-"+i+".bin";}
}
