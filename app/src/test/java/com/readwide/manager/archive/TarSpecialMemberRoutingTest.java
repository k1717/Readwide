package com.readwide.manager.archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Public routes share one TAR member policy for raw and compressed containers. */
@RunWith(Parameterized.class)
public final class TarSpecialMemberRoutingTest {
    @Parameterized.Parameters(name="gzip={0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{false},{true}});}
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final boolean gzip;
    public TarSpecialMemberRoutingTest(boolean gzip){this.gzip=gzip;}
    @Before @After public void clear(){TarEntryIndex.clear();}
    @Test public void listingIncludesOnlyDirectoriesRegularAndContiguousFiles()throws Exception{
        Set<String> names=new HashSet<>();for(ArchiveSupport.EntryInfo entry:ArchiveSupport.listEntries(fixture(false),null))names.add(entry.path);
        assertEquals(new HashSet<>(Arrays.asList("dir/","normal.bin","old.bin","contiguous.bin")),names);
    }
    @Test public void bulkDoesNotMaterializeSpecialMembers()throws Exception{
        File output=new File(temporary.getRoot(),"bulk");assertTrue(ArchiveSupport.extractArchive(fixture(false),output,false,null));
        assertEquals(new HashSet<>(Arrays.asList("dir","normal.bin","old.bin","contiguous.bin")),new HashSet<>(Arrays.asList(output.list())));
        assertEquals("normal",ArchiveTestTextFiles.readString(new File(output,"normal.bin").toPath()));
    }
    @Test public void selectedSpecialEntryIsNotPublished()throws Exception{
        File archive=fixture(false);
        for(String name:new String[]{"fifo","device","character","symbolic","hard","unknown"}){
            File output=new File(temporary.getRoot(),"selected-"+name);
            assertFalse(name,ArchiveSupport.extractSingleEntryDetailed(archive,name,output,null).success);assertFalse(output.exists());
        }
    }
    @Test public void specialEntryDoesNotShadowLaterRegularMember()throws Exception{
        File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"existing");
        assertTrue(ArchiveSupport.extractSingleEntryDetailed(fixture(true),"normal.bin",output,null).success);
        assertEquals("normal",ArchiveTestTextFiles.readString(output.toPath()));
    }
    @Test public void forwardReaderSkipsSpecialEntries()throws Exception{
        Set<String> names=new HashSet<>();
        try(ArchiveSupport.ForwardArchiveReader reader=ArchiveSupport.openForwardReader(fixture(false),null)){
            assertNotNull(reader);ArchiveSupport.ForwardEntry entry;
            while((entry=reader.nextEntry())!=null)if(entry.path!=null)names.add(entry.path);
        }
        assertEquals(new HashSet<>(Arrays.asList("dir/","normal.bin","old.bin","contiguous.bin")),names);
    }
    private File fixture(boolean shadow)throws Exception{
        File archive=temporary.newFile("members-"+System.nanoTime()+(gzip?".tar.gz":".tar"));
        try(OutputStream file=new FileOutputStream(archive);OutputStream payload=gzip?new GZIPOutputStream(file):file;TarArchiveOutputStream tar=new TarArchiveOutputStream(payload)){
            String[] names={shadow?"normal.bin":"fifo","device","character","symbolic","hard","unknown","fifo-dir/"};
            byte[] types={TarConstants.LF_FIFO,TarConstants.LF_BLK,TarConstants.LF_CHR,TarConstants.LF_SYMLINK,TarConstants.LF_LINK,(byte)'?',TarConstants.LF_FIFO};
            for(int i=0;i<names.length;i++){TarArchiveEntry entry=new TarArchiveEntry(names[i],types[i]);if(types[i]==TarConstants.LF_LINK||types[i]==TarConstants.LF_SYMLINK)entry.setLinkName("normal.bin");tar.putArchiveEntry(entry);tar.closeArchiveEntry();}
            tar.putArchiveEntry(new TarArchiveEntry("dir/",TarConstants.LF_DIR));tar.closeArchiveEntry();
            member(tar,"normal.bin",TarConstants.LF_NORMAL,"normal");member(tar,"old.bin",TarConstants.LF_OLDNORM,"old");member(tar,"contiguous.bin",TarConstants.LF_CONTIG,"contiguous");
        }
        return archive;
    }
    private static void member(TarArchiveOutputStream tar,String name,byte type,String text)throws Exception{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);TarArchiveEntry entry=new TarArchiveEntry(name,type);entry.setSize(data.length);tar.putArchiveEntry(entry);tar.write(data);tar.closeArchiveEntry();
    }
}
