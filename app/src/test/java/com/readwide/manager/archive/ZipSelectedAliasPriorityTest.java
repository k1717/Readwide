package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.*;
import static org.junit.Assert.*;

/** A fallback name must not take ownership of another entry's displayed name. */
public final class ZipSelectedAliasPriorityTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void coldSelectedDisplayedNameWinsOverEarlierAlias()throws Exception{check(false,false);}
    @Test public void cachedSelectedDisplayedNameWinsOverEarlierAlias()throws Exception{check(false,true);}
    @Test public void reversingEntryOrderDoesNotChangeDisplayedIdentity()throws Exception{check(true,true);}
    private void check(boolean reverse,boolean warm)throws Exception{
        File archive=fixture(reverse,false,false),output=temporary.newFile();
        if(warm)selected(archive,"legacy.bin","first",output);
        Set<String> names=new HashSet<>();for(ArchiveSupport.EntryInfo info:ArchiveSupport.listEntries(archive,null))names.add(info.path);
        assertEquals(new HashSet<>(Arrays.asList("legacy.bin","page.bin")),names);
        selected(archive,"page.bin","second",output);selected(archive,"legacy.bin","first",output);
    }
    @Test public void unambiguousAliasStillWorks()throws Exception{
        File archive=fixture(false,true,false);selected(archive,"page.bin","first",temporary.newFile());
    }
    @Test public void duplicateDisplayedNamesKeepFirstEntryPolicy()throws Exception{
        File archive=fixture(false,false,true);selected(archive,"page.bin","first",temporary.newFile());
    }
    private void selected(File archive,String name,String expected,File output)throws Exception{
        ArchiveTestTextFiles.writeString(output.toPath(),"old");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,name,output,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertEquals(expected,ArchiveTestTextFiles.readString(output.toPath()));
    }
    private File fixture(boolean reverse,boolean aliasOnly,boolean duplicate)throws Exception{
        File archive=temporary.newFile("aliases-"+System.nanoTime()+".zip");
        try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive)){
            zip.setEncoding("UTF-8");zip.setUseLanguageEncodingFlag(false);
            if(reverse)member(zip,"other.bin","page.bin","second");
            member(zip,"page.bin",duplicate?null:"legacy.bin","first");
            if(!reverse&&!aliasOnly)member(zip,duplicate?"page.bin":"other.bin",duplicate?null:"page.bin","second");
        }
        if(!duplicate){try(org.apache.commons.compress.archivers.zip.ZipFile zip=org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get()){
            boolean found=false;Enumeration<ZipArchiveEntry> entries=zip.getEntries();while(entries.hasMoreElements()){
                ZipArchiveEntry entry=entries.nextElement();if(Arrays.equals("page.bin".getBytes(StandardCharsets.UTF_8),entry.getRawName())){
                    assertEquals(ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD,entry.getNameSource());assertEquals("legacy.bin",entry.getName());found=true;
                }
            }assertTrue(found);
        }}
        return archive;
    }
    private static void member(ZipArchiveOutputStream zip,String rawName,String alias,String text)throws Exception{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
        try(XZOutputStream stream=new XZOutputStream(packed,new LZMA2Options())){stream.write(data);}
        CRC32 crc=new CRC32();crc.update(data);ZipArchiveEntry entry=new ZipArchiveEntry(rawName);entry.setMethod(95);entry.setSize(data.length);entry.setCompressedSize(packed.size());entry.setCrc(crc.getValue());
        if(alias!=null)entry.addExtraField(new UnicodePathExtraField(alias,rawName.getBytes(StandardCharsets.UTF_8)));
        zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
    }
}
