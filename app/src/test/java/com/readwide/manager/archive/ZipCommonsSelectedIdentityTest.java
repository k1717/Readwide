package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.tukaani.xz.*;
import static org.junit.Assert.*;

/** An earlier parser alias must not impersonate a later listed entry in the Commons fallback. */
@RunWith(Parameterized.class)
public final class ZipCommonsSelectedIdentityTest {
    @Parameterized.Parameters(name="method={0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{12},{95}});}
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final int method;
    public ZipCommonsSelectedIdentityTest(int method){this.method=method;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    @Test public void displayedTargetWinsOverEarlierLocalUnicodeAlias()throws Exception{
        File archive=fixture(false,false,false,false);
        Set<String> names=new HashSet<>();for(ArchiveSupport.EntryInfo entry:ArchiveSupport.listEntries(archive,null))names.add(entry.path);
        assertEquals(new HashSet<>(Arrays.asList("legacy.bin","page.bin")),names);selected(archive,"second");selected(archive,"second");
    }
    @Test public void reversingEntryOrderKeepsTheSameSelectedIdentity()throws Exception{selected(fixture(true,false,false,false),"second");}
    @Test public void unambiguousLocalOnlyAliasRemainsUsable()throws Exception{selected(fixture(false,true,false,false),"first");}
    @Test public void unsupportedCanonicalTargetCannotReturnAnEarlierAliasPayload()throws Exception{preservedFailure(fixture(false,false,true,false));}
    @Test public void canonicalCrcFailureCannotReturnAnEarlierAliasPayload()throws Exception{preservedFailure(fixture(false,false,false,true));}
    @Test public void splitArchiveKeepsCanonicalIdentityAcrossDiskOffsets()throws Exception{
        File archive=fixture(false,false,false,false,true);assertTrue(ZipVolumeResolver.forSnapshot(archive).size()>1);selected(archive,"second");selected(archive,"second");
    }
    private void selected(File archive,String expected)throws Exception{
        File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",output,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertEquals(expected.length(),output.length());assertEquals(expected,ArchiveTestTextFiles.readString(output.toPath()));
    }
    private void preservedFailure(File archive)throws Exception{
        File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",output,null).success);assertEquals("original",ArchiveTestTextFiles.readString(output.toPath()));
    }
    private File fixture(boolean reverse,boolean aliasOnly,boolean unsupported,boolean corrupt)throws Exception{
        return fixture(reverse,aliasOnly,unsupported,corrupt,false);
    }
    private File fixture(boolean reverse,boolean aliasOnly,boolean unsupported,boolean corrupt,boolean split)throws Exception{
        File archive=temporary.newFile("identity-"+System.nanoTime()+".zip");
        String first="first";
        if(split){byte[] data=new byte[100000];new Random(93).nextBytes(data);first=Base64.getEncoder().encodeToString(data);}
        try(ZipArchiveOutputStream zip=split?new ZipArchiveOutputStream(archive,65536):new ZipArchiveOutputStream(archive)){
            zip.setEncoding("UTF-8");zip.setUseLanguageEncodingFlag(false);
            if(reverse)member(zip,"page.bin",false,unsupported?98:method,"second",corrupt);
            member(zip,"legacy.bin",true,method,first,false);
            if(!reverse&&!aliasOnly)member(zip,"page.bin",false,unsupported?98:method,"second",corrupt);
        }
        // Keep the first member's Unicode alias in its local header only. Its displayed central identity is legacy.bin.
        byte[] bytes=Files.readAllBytes(archive.toPath());boolean changed=false;
        for(int at=0;at+46<bytes.length;at++){
            if(u32(bytes,at)!=0x02014b50L)continue;
            int n=u16(bytes,at+28);if(!"legacy.bin".equals(new String(bytes,at+46,n,StandardCharsets.UTF_8)))continue;
            int extra=at+46+n,end=extra+u16(bytes,at+30);
            while(extra+4<=end){if(u16(bytes,extra)==0x7075){bytes[extra]=0x77;bytes[extra+1]=0x77;changed=true;break;}extra+=4+u16(bytes,extra+2);}
            break;
        }
        assertTrue(changed);Files.write(archive.toPath(),bytes);return archive;
    }
    private static void member(ZipArchiveOutputStream zip,String name,boolean alias,int method,String text,boolean corrupt)throws Exception{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream packed=new ByteArrayOutputStream();
        if(method==12){try(BZip2CompressorOutputStream bz=new BZip2CompressorOutputStream(packed)){bz.write(data);}}
        else if(method==95){try(XZOutputStream xz=new XZOutputStream(packed,new LZMA2Options(0))){xz.write(data);}}
        else packed.write(data); // Unsupported-method routing fixture; never claimed to be decodable PPMd.
        ZipArchiveEntry entry=new ZipArchiveEntry(name);entry.setMethod(method);entry.setSize(data.length);entry.setCompressedSize(packed.size());CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue()^(corrupt?1:0));
        if(alias)entry.addExtraField(new UnicodePathExtraField("page.bin",name.getBytes(StandardCharsets.UTF_8)));
        zip.addRawArchiveEntry(entry,new ByteArrayInputStream(packed.toByteArray()));
    }
    private static int u16(byte[] bytes,int at){return (bytes[at]&255)|((bytes[at+1]&255)<<8);}
    private static long u32(byte[] bytes,int at){return (bytes[at]&255L)|((bytes[at+1]&255L)<<8)|((bytes[at+2]&255L)<<16)|((bytes[at+3]&255L)<<24);}
}
