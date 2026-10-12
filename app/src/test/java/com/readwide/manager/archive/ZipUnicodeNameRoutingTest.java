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

/** ZIP central-directory Unicode Path names must identify the same public payload. */
@RunWith(Parameterized.class)
public final class ZipUnicodeNameRoutingTest {
    @Parameterized.Parameters(name="method={0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{0},{95}});}
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final int method;
    public ZipUnicodeNameRoutingTest(int method){this.method=method;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void validUnicodeNameMatchesListingSelectedAndBulk()throws Exception{routes("legacy.bin","\uD398\uC774\uC9C0.bin",false,0,"\uD398\uC774\uC9C0.bin");}
    @Test public void badNameCrcKeepsOriginalName()throws Exception{routes("legacy.bin","wrong.bin",false,1,"legacy.bin");}
    @Test public void unsupportedExtraVersionKeepsOriginalName()throws Exception{routes("legacy.bin","wrong.bin",false,2,"legacy.bin");}
    @Test public void malformedUtf8ExtraKeepsOriginalName()throws Exception{routes("legacy.bin","wrong.bin",false,3,"legacy.bin");}
    @Test public void utf8FlagKeepsItsRawNamePrecedence()throws Exception{routes("flagged.bin","wrong.bin",true,0,"flagged.bin");}
    @Test public void unicodeNameUsesExistingNfcNormalization()throws Exception{routes("legacy.bin","e\u0301.bin",false,0,"\u00e9.bin");}
    @Test public void displayedNamesBeatOtherEntriesRawAliases()throws Exception{
        File archive=file();try(ZipArchiveOutputStream zip=open(archive,false)){
            member(zip,"page.bin","first.bin",0,"first");member(zip,"other.bin","page.bin",0,"second");
        }
        selected(archive,"page.bin","second");selected(archive,"first.bin","first");
        Set<String> names=new HashSet<>();for(ArchiveSupport.EntryInfo e:ArchiveSupport.listEntries(archive,null))names.add(e.path);
        assertEquals(new HashSet<>(Arrays.asList("first.bin","page.bin")),names);
    }
    private void routes(String raw,String unicode,boolean flag,int mode,String expected)throws Exception{
        File archive=file();try(ZipArchiveOutputStream zip=open(archive,flag)){member(zip,raw,unicode,mode,"payload");}
        assertEquals(expected,ArchiveSupport.listEntries(archive,null).get(0).path);
        selected(archive,expected,"payload");selected(archive,expected,"payload");
        File output=new File(temporary.newFolder(),"bulk");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(archive,output,false,null,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertEquals("payload",ArchiveTestTextFiles.readString(new File(output,expected).toPath()));assertArrayEquals(new String[]{expected},output.list());
    }
    private void selected(File archive,String name,String expected)throws Exception{
        File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"existing");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,name,output,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertEquals(expected,ArchiveTestTextFiles.readString(output.toPath()));
    }
    private File file()throws Exception{return temporary.newFile("unicode-"+System.nanoTime()+".zip");}
    private ZipArchiveOutputStream open(File archive,boolean flag)throws Exception{ZipArchiveOutputStream zip=new ZipArchiveOutputStream(archive);zip.setEncoding("UTF-8");zip.setUseLanguageEncodingFlag(flag);return zip;}
    private void member(ZipArchiveOutputStream zip,String raw,String unicode,int mode,String text)throws Exception{
        byte[] name=raw.getBytes(StandardCharsets.UTF_8),data=text.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
        if(method==0)packed.write(data);else try(XZOutputStream stream=new XZOutputStream(packed,new LZMA2Options())){stream.write(data);}
        ZipArchiveEntry entry=new ZipArchiveEntry(raw);entry.setMethod(method);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());
        CRC32 nameCrc=new CRC32();nameCrc.update(name);ByteArrayOutputStream extra=new ByteArrayOutputStream();extra.write(mode==2?2:1);long checksum=nameCrc.getValue()^(mode==1?1:0);for(int i=0;i<4;i++)extra.write((int)(checksum>>>(8*i))&255);extra.write(mode==3?new byte[]{(byte)0xff}:unicode.getBytes(StandardCharsets.UTF_8));
        UnrecognizedExtraField field=new UnrecognizedExtraField();field.setHeaderId(new ZipShort(0x7075));field.setLocalFileDataData(extra.toByteArray());field.setCentralDirectoryData(extra.toByteArray());entry.addExtraField(field);
        zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
    }
}
