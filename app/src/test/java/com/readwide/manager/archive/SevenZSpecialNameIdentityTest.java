package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class SevenZSpecialNameIdentityTest {
    @Parameterized.Parameters(name="AES={0},encodedHeader={1}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{false,false},{true,false},{false,true},{true,true}});}
    @Rule public final TemporaryFolder temp=new TemporaryFolder();private final boolean aes,encoded;
    private static final byte[] DATA="first-party 7z names".getBytes(StandardCharsets.UTF_8);
    public SevenZSpecialNameIdentityTest(boolean aes,boolean encoded){this.aes=aes;this.encoded=encoded;}
    private char[] password(){return aes?Round4Fixtures.PASSWORD:null;}
    private File fixture(String name)throws Exception{
        Round4Fixtures.Graph data=Round4Fixtures.graph(DATA,4,aes);ByteArrayOutputStream header=new ByteArrayOutputStream();header.write(1);header.write(4);Round4Fixtures.streams(header,data,0,false,false);
        header.write(5);Round4Fixtures.number(header,1);byte[] names=(name+"\0").getBytes(StandardCharsets.UTF_16LE);header.write(17);Round4Fixtures.number(header,names.length+1);header.write(0);header.write(names);header.write(0);header.write(0);
        byte[] next=header.toByteArray();ByteArrayOutputStream packed=new ByteArrayOutputStream();packed.write(data.packed);
        if(encoded){Round4Fixtures.Graph graph=Round4Fixtures.graph(next,4,aes);ByteArrayOutputStream descriptor=new ByteArrayOutputStream();descriptor.write(23);Round4Fixtures.streams(descriptor,graph,packed.size(),false,false);packed.write(graph.packed);next=descriptor.toByteArray();}
        ByteArrayOutputStream start=new ByteArrayOutputStream();Round4Fixtures.le(start,packed.size(),8);Round4Fixtures.le(start,next.length,8);Round4Fixtures.le(start,Round4Fixtures.crc(next),4);
        ByteArrayOutputStream archive=new ByteArrayOutputStream();archive.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});Round4Fixtures.le(archive,Round4Fixtures.crc(start.toByteArray()),4);archive.write(start.toByteArray());archive.write(packed.toByteArray());archive.write(next);return Round4Fixtures.write(temp.newFolder(),"names.7z",archive.toByteArray());
    }
    @Test public void listingAndSelectionShareNormalizedNames()throws Exception{
        for(String raw:new String[]{"./page.bin","folder\\page.bin","folder//page.bin"," page.bin "}){
            File file=fixture(raw);String expected=ArchiveSupport.sanitizeEntryPathForList(raw);List<ArchiveSupport.EntryInfo> listed=ArchiveSupport.listEntries(file,password());ArchiveSupport.EntryInfo member=listed.get(listed.size()-1);assertEquals(expected,member.path);File out=temp.newFile();ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(file,member.path,out,password());assertTrue(raw+" "+result.failure+" "+result.detail,result.success);assertArrayEquals(DATA,Files.readAllBytes(out.toPath()));
        }
    }
    @Test public void supplementalListingUsesSameIdentityAsPublicListing()throws Exception{
        for(String raw:new String[]{"./page.bin","folder\\page.bin","folder//page.bin"," page.bin "}){List<ArchiveSupport.EntryInfo> listed=SevenZBcj2ArchiveReader.listEntries(fixture(raw),password());assertEquals(1,listed.size());assertEquals(ArchiveSupport.sanitizeEntryPathForList(raw),listed.get(0).path);}
    }
    @Test public void unsafeNamesAreNotListedOrSelectable()throws Exception{
        File file=fixture("../escape.bin");assertTrue(SevenZBcj2ArchiveReader.listEntries(file,password()).isEmpty());File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");assertFalse(ArchiveSupport.extractSingleEntryDetailed(file,"../escape.bin",out,password()).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void bulkUsesNormalizedMemberPath()throws Exception{
        File out=new File(temp.newFolder(),"out");ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(fixture("./folder//page.bin"),out,false,password(),null);assertTrue(result.failure+" "+result.detail,result.success);assertArrayEquals(DATA,Files.readAllBytes(new File(out,"folder/page.bin").toPath()));
    }
}
