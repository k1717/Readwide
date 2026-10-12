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

/** Small ZIP64 containers exercise metadata without multi-gigabyte payloads. */
@RunWith(Parameterized.class)
public final class Zip64MetadataRoutingTest {
    @Parameterized.Parameters(name="method={0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{0},{95}});}
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final int method;
    public Zip64MetadataRoutingTest(int method){this.method=method;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void zip64SizeIsResolvedForPublicListing()throws Exception{routes("page.bin",null,false,"payload",Zip64Mode.Always);}
    @Test public void zip64EmptyEntryKeepsZeroSize()throws Exception{routes("empty.bin",null,false,"",Zip64Mode.Always);}
    @Test public void compatibilityZip64KeepsSizeAndIdentity()throws Exception{routes("page.bin",null,false,"payload",Zip64Mode.AlwaysWithCompatibility);}
    @Test public void saturatedEndRecordKeepsCanonicalUnicodeIdentity()throws Exception{routes("legacy.bin","\uD398\uC774\uC9C0.bin",true,"payload",Zip64Mode.Always);}
    @Test public void saturatedEndRecordKeepsLegacyNameDecoding()throws Exception{routes("\uD55C\uAE00\uBB38\uC11C.bin",null,true,"payload",Zip64Mode.Always);}
    private void routes(String raw,String unicode,boolean saturated,String text,Zip64Mode mode)throws Exception{
        File archive=temporary.newFile("zip64-"+System.nanoTime()+".zip");
        byte[] data=text.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
        if(method==0)packed.write(data);else try(XZOutputStream stream=new XZOutputStream(packed,new LZMA2Options())){stream.write(data);}
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive)){
            zip.setUseZip64(mode);zip.setEncoding("MS949");zip.setUseLanguageEncodingFlag(false);
            ZipArchiveEntry entry=new ZipArchiveEntry(raw);entry.setMethod(method);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());
            if(unicode!=null)entry.addExtraField(new UnicodePathExtraField(unicode,raw.getBytes("MS949")));
            zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
        }
        if(saturated){byte[] bytes=Files.readAllBytes(archive.toPath());int end=bytes.length-22;assertEquals(0x50,bytes[end]&255);Arrays.fill(bytes,end+8,end+20,(byte)255);Files.write(archive.toPath(),bytes);}
        String expected=unicode==null?raw:unicode;
        List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(archive,null);
        assertEquals(1,entries.size());assertEquals(expected,entries.get(0).path);assertEquals(data.length,entries.get(0).size);
        for(int i=0;i<2;i++){
            File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"existing");
            ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,expected,output,null);
            assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(data,Files.readAllBytes(output.toPath()));
        }
        File directory=new File(temporary.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,directory,false,null,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(data,Files.readAllBytes(new File(directory,expected).toPath()));assertArrayEquals(new String[]{expected},directory.list());
    }
}
