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

@RunWith(Parameterized.class)
public final class ZipSpannedDirectoryTest {
    @Parameterized.Parameters(name="method={0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{0},{8},{95}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final int method;
    public ZipSpannedDirectoryTest(int method){this.method=method;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    private File fixture()throws Exception{
        File file=new File(temp.newFolder(),"book.zip");
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(file,65536L)){
            zip.setEncoding("US-ASCII");zip.setUseLanguageEncodingFlag(false);zip.setUseZip64(Zip64Mode.Never);
            for(int i=0;i<4;i++){
                String raw="raw-"+i+".bin";byte[] data=("payload-"+i).getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
                if(method==0)packed.write(data);else if(method==8){java.util.zip.Deflater deflater=new java.util.zip.Deflater(6,true);try(java.util.zip.DeflaterOutputStream stream=new java.util.zip.DeflaterOutputStream(packed,deflater)){stream.write(data);}finally{deflater.end();}}else try(XZOutputStream xz=new XZOutputStream(packed,new LZMA2Options(0))){xz.write(data);}
                ZipArchiveEntry entry=new ZipArchiveEntry(raw);entry.setComment("c".repeat(40000));entry.setMethod(method);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());
                entry.addExtraField(new UnicodePathExtraField(name(i),raw.getBytes(StandardCharsets.US_ASCII)));zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
            }
        }
        assertTrue(new File(file.getParentFile(),"book.z02").isFile());return file;
    }
    @Test public void listingSpansTheEntireCentralDirectory()throws Exception{
        File file=fixture();List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(file,null);Set<String> actual=new HashSet<>();for(ArchiveSupport.EntryInfo e:entries)actual.add(e.path);
        assertEquals(new HashSet<>(Arrays.asList(name(0),name(1),name(2),name(3))),actual);
    }
    @Test public void selectedReadUsesNamesFromEarlierDirectoryDisks()throws Exception{
        File file=fixture();for(int i:new int[]{0,3,0}){File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(file,name(i),out,null);assertTrue(result.failure+" "+result.detail,result.success);assertEquals("payload-"+i,ArchiveTestTextFiles.readString(out.toPath()));}
    }
    @Test public void bulkReadUsesNamesFromEarlierDirectoryDisks()throws Exception{
        File file=fixture(),out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(file,out,false,null,null);assertTrue(result.failure+" "+result.detail,result.success);
        for(int i=0;i<4;i++)assertEquals("payload-"+i,ArchiveTestTextFiles.readString(new File(out,name(i)).toPath()));
    }
    private static String name(int i){return "page-"+i+"-\uD398\uC774\uC9C0.bin";}
}
