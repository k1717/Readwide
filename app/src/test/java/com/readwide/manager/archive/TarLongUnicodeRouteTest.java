package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.tukaani.xz.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Genuine multibyte/supplementary names, not ASCII placeholders, through public TAR routes. */
@RunWith(Parameterized.class)
public final class TarLongUnicodeRouteTest {
    @Parameterized.Parameters(name="{0}/longMode={1}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{".tar",2},{".tar",3},{".tar.gz",2},{".tar.gz",3},{".tar.xz",2},{".tar.xz",3}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();private final String suffix;private final int mode;
    private static final String NAME="folder/"+"\uD55C\uAE00\uD83D\uDCD6".repeat(12)+".bin";
    private static final byte[] DATA="unicode payload".getBytes(StandardCharsets.UTF_8);
    public TarLongUnicodeRouteTest(String suffix,int mode){this.suffix=suffix;this.mode=mode;}
    private File fixture()throws Exception{
        assertTrue(NAME.getBytes(StandardCharsets.UTF_8).length>100);File file=temp.newFile("names"+suffix);OutputStream raw=new FileOutputStream(file),wrapped=raw;
        if(suffix.endsWith(".gz"))wrapped=new GZIPOutputStream(raw);else if(suffix.endsWith(".xz"))wrapped=new XZOutputStream(raw,new LZMA2Options(0));
        try(TarArchiveOutputStream tar=new TarArchiveOutputStream(wrapped,"UTF-8")){tar.setLongFileMode(mode);TarArchiveEntry e=new TarArchiveEntry(NAME);e.setSize(DATA.length);tar.putArchiveEntry(e);tar.write(DATA);tar.closeArchiveEntry();}return file;
    }
    @Test public void listedNameAndSelectedPayloadMatchUnicodeIdentity()throws Exception{
        File file=fixture();List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(file,null);assertEquals(2,entries.size());assertEquals(NAME,entries.get(1).path);assertEquals(DATA.length,entries.get(1).size);File out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(file,NAME,out,null).success);assertArrayEquals(DATA,Files.readAllBytes(out.toPath()));
    }
    @Test public void bulkCreatesTheListedUnicodeName()throws Exception{
        File out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(fixture(),out,false,null,null);assertTrue(result.detail,result.success);assertArrayEquals(DATA,Files.readAllBytes(new File(out,NAME).toPath()));
    }
    @Test public void forwardReaderPreservesSupplementaryCharacters()throws Exception{
        try(ArchiveSupport.ForwardArchiveReader reader=ArchiveSupport.openForwardReader(fixture(),null)){assertNotNull(reader);ArchiveSupport.ForwardEntry entry=reader.nextEntry();assertEquals(NAME,entry.path);ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[3];int n;while((n=reader.read(buffer))!=-1)bytes.write(buffer,0,n);assertArrayEquals(DATA,bytes.toByteArray());assertNull(reader.nextEntry());}
    }
}
