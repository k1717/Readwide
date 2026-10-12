package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.tukaani.xz.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class ZipDuplicateSelectedMetadataTest {
    @Parameterized.Parameters(name="method={0},split={1}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{0,false},{12,false},{95,false},{0,true},{12,true},{95,true}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();private final int method;private final boolean split;
    public ZipDuplicateSelectedMetadataTest(int method,boolean split){this.method=method;this.split=split;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    private File fixture(boolean unicode)throws Exception{
        File file=new File(temp.newFolder(),"duplicate.zip");try(ZipArchiveOutputStream zip=split?new ZipArchiveOutputStream(file,65536L):new ZipArchiveOutputStream(file)){
            zip.setEncoding("US-ASCII");zip.setUseLanguageEncodingFlag(false);
            for(int i=0;i<2;i++){
                byte[] data=(i==0?"one":"a longer second payload").getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
                if(method==0)packed.write(data);else if(method==12){try(BZip2CompressorOutputStream out=new BZip2CompressorOutputStream(packed)){out.write(data);}}else try(XZOutputStream out=new XZOutputStream(packed,new LZMA2Options(0))){out.write(data);}
                String raw=unicode?"raw-"+i+".bin":"page.bin";ZipArchiveEntry entry=new ZipArchiveEntry(raw);entry.setComment("c".repeat(40000));entry.setMethod(method);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());
                if(unicode)entry.addExtraField(new UnicodePathExtraField("page.bin",raw.getBytes(StandardCharsets.UTF_8)));zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
            }
        }return file;
    }
    private void matches(File file)throws Exception{
        for(int attempt=0;attempt<2;attempt++){List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(file,null);assertEquals(1,entries.size());assertEquals("page.bin",entries.get(0).path);File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(file,"page.bin",out,null);assertTrue(result.failure+" "+result.detail,result.success);assertEquals("one",ArchiveTestTextFiles.readString(out.toPath()));assertEquals(out.length(),entries.get(0).size);}
    }
    @Test public void duplicateLiteralNamesDescribeTheSelectedFirstMember()throws Exception{matches(fixture(false));}
    @Test public void duplicateUnicodeIdentitiesDescribeTheSelectedFirstMember()throws Exception{matches(fixture(true));}
}
