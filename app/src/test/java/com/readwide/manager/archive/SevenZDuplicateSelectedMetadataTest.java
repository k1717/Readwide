package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.apache.commons.compress.archivers.sevenz.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class SevenZDuplicateSelectedMetadataTest {
    @Parameterized.Parameters(name="special={0},split={1}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{false,false},{true,false},{false,true},{true,true}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();private final boolean special,split;
    private static final byte[] FIRST={1,2,3},SECOND={4,5,6,7,8};
    public SevenZDuplicateSelectedMetadataTest(boolean special,boolean split){this.special=special;this.split=split;}
    private File fixture(boolean alias)throws Exception{
        String second=alias?"./page.bin":"page.bin";File file=new File(temp.newFolder(),"duplicate.7z");
        if(!special){try(SevenZOutputFile out=new SevenZOutputFile(file)){out.setContentCompression(SevenZMethod.COPY);member(out,"page.bin",FIRST);member(out,second,SECOND);}}
        else{
            byte[] data={1,2,3,4,5,6,7,8};Round4Fixtures.Graph graph=Round4Fixtures.graph(data,4,false);ByteArrayOutputStream streams=new ByteArrayOutputStream();Round4Fixtures.streams(streams,graph,0,false,false);byte[] main=streams.toByteArray();
            ByteArrayOutputStream header=new ByteArrayOutputStream();header.write(1);header.write(4);header.write(main,0,main.length-1);header.write(8);header.write(13);Round4Fixtures.number(header,2);header.write(9);Round4Fixtures.number(header,FIRST.length);header.write(10);header.write(1);Round4Fixtures.le(header,Round4Fixtures.crc(FIRST),4);Round4Fixtures.le(header,Round4Fixtures.crc(SECOND),4);header.write(0);header.write(0);
            header.write(5);Round4Fixtures.number(header,2);byte[] names=("page.bin\0"+second+"\0").getBytes(StandardCharsets.UTF_16LE);header.write(17);Round4Fixtures.number(header,names.length+1);header.write(0);header.write(names);header.write(0);header.write(0);byte[] next=header.toByteArray();
            ByteArrayOutputStream start=new ByteArrayOutputStream();Round4Fixtures.le(start,graph.packed.length,8);Round4Fixtures.le(start,next.length,8);Round4Fixtures.le(start,Round4Fixtures.crc(next),4);ByteArrayOutputStream archive=new ByteArrayOutputStream();archive.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});Round4Fixtures.le(archive,Round4Fixtures.crc(start.toByteArray()),4);archive.write(start.toByteArray());archive.write(graph.packed);archive.write(next);Files.write(file.toPath(),archive.toByteArray());
        }
        if(!split)return file;byte[] bytes=Files.readAllBytes(file.toPath());Files.delete(file.toPath());File first=new File(file.getParentFile(),"duplicate.7z.001"),last=new File(file.getParentFile(),"duplicate.7z.002");Files.write(first.toPath(),Arrays.copyOfRange(bytes,0,35));Files.write(last.toPath(),Arrays.copyOfRange(bytes,35,bytes.length));return last;
    }
    private static void member(SevenZOutputFile out,String name,byte[] data)throws Exception{SevenZArchiveEntry e=new SevenZArchiveEntry();e.setName(name);out.putArchiveEntry(e);out.write(data);out.closeArchiveEntry();}
    private void matches(File archive)throws Exception{
        List<ArchiveSupport.EntryInfo> direct=SevenZBcj2ArchiveReader.listEntries(archive,null);assertEquals(1,direct.size());assertEquals(FIRST.length,direct.get(0).size);
        List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(archive,null);assertEquals(1,entries.size());File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(archive,"page.bin",out,null);assertTrue(result.failure+" "+result.detail,result.success);assertArrayEquals(FIRST,Files.readAllBytes(out.toPath()));assertEquals(out.length(),entries.get(0).size);
    }
    @Test public void exactDuplicateDescribesFirstSelectedMember()throws Exception{matches(fixture(false));}
    @Test public void normalizedDuplicateDescribesFirstSelectedMember()throws Exception{matches(fixture(true));}
}
