package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class TarDuplicateSelectedMetadataTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{".tar"},{".tar.gz"},{".tar.bz2"},{".tar.xz"},{".tar.lzma"},{".tar.lz4"}});}
    private final TarEmptyPublicRouteTest wrapper;@Rule public final TemporaryFolder temp;
    public TarDuplicateSelectedMetadataTest(String suffix){wrapper=new TarEmptyPublicRouteTest(suffix);temp=wrapper.temp;}
    private File fixture(String duplicate,boolean link)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(TarArchiveOutputStream tar=new TarArchiveOutputStream(bytes)){
            if(link){TarArchiveEntry e=new TarArchiveEntry("page.bin",TarConstants.LF_SYMLINK);e.setLinkName("elsewhere");tar.putArchiveEntry(e);tar.closeArchiveEntry();}
            member(tar,"page.bin",new byte[]{1,2,3});member(tar,duplicate,new byte[]{4,5,6,7,8});
        }
        Method method=TarEmptyPublicRouteTest.class.getDeclaredMethod("file",byte[].class);method.setAccessible(true);return (File)method.invoke(wrapper,(Object)bytes.toByteArray());
    }
    private static void member(TarArchiveOutputStream tar,String name,byte[] data)throws Exception{TarArchiveEntry e=new TarArchiveEntry(name);e.setSize(data.length);tar.putArchiveEntry(e);tar.write(data);tar.closeArchiveEntry();}
    private void matches(File file)throws Exception{
        List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(file,null);assertEquals(1,entries.size());File out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(file,"page.bin",out,null).success);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(out.toPath()));assertEquals(out.length(),entries.get(0).size);
    }
    @Test public void duplicateLiteralNamesDescribeTheSelectedFirstMember()throws Exception{matches(fixture("page.bin",false));}
    @Test public void normalizedAliasNamesDescribeTheSelectedFirstMember()throws Exception{matches(fixture("./page.bin",false));}
    @Test public void skippedLinkDoesNotReserveTheRegularFileIdentity()throws Exception{matches(fixture("./page.bin",true));}
}
