package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Metadata and payload changes on an early ZIP disk must invalidate a warm final-volume index. */
@RunWith(Parameterized.class)
public final class ZipSpannedDirectoryCacheTest {
    @Parameterized.Parameters(name="method={0}") public static Collection<Object[]> variants(){return Arrays.asList(new Object[][]{{0},{95}});}
    private final ZipSpannedDirectoryTest fixture;@Rule public final TemporaryFolder temp;
    public ZipSpannedDirectoryCacheTest(int method){fixture=new ZipSpannedDirectoryTest(method);temp=fixture.temp;}
    @Before @After public void clear(){ZipxAesArchiveReader.clearCachedIndexes();}
    private File fixture()throws Exception{Method build=ZipSpannedDirectoryTest.class.getDeclaredMethod("fixture");build.setAccessible(true);return (File)build.invoke(fixture);}
    private static String original(){return "page-0-\uD398\uC774\uC9C0.bin";}
    private void warm(File file)throws Exception{File out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(file,original(),out,null).success);assertEquals("payload-0",ArchiveTestTextFiles.readString(out.toPath()));assertTrue(ZipxAesArchiveReader.hasCachedIndex(file));}
    @Test public void earlierCentralNameChangeRefreshesListingAndSelectedIndex()throws Exception{
        File file=fixture();warm(file);List<File> parts=ZipVolumeResolver.forSnapshot(file);File first=parts.get(0);long lastStamp=file.lastModified(),lastLength=file.length(),stamp=first.lastModified();byte[] bytes=Files.readAllBytes(first.toPath());int central=find(bytes,new byte[]{'P','K',1,2},0);assertTrue(central>=0);int marker=find(bytes,"page-0-".getBytes(StandardCharsets.UTF_8),central);assertTrue(marker>=0);System.arraycopy("leaf-0-".getBytes(StandardCharsets.UTF_8),0,bytes,marker,7);Files.write(first.toPath(),bytes);assertTrue(first.setLastModified(stamp+5000));assertEquals(lastStamp,file.lastModified());assertEquals(lastLength,file.length());
        String renamed="leaf-0-\uD398\uC774\uC9C0.bin";List<String> names=new ArrayList<>();for(ArchiveSupport.EntryInfo e:ArchiveSupport.listEntries(file,null))names.add(e.path);assertTrue(names.contains(renamed));assertFalse(names.contains(original()));File out=temp.newFile();assertTrue(ArchiveSupport.extractSingleEntryDetailed(file,renamed,out,null).success);assertEquals("payload-0",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void earlierPayloadDamageCannotReuseWarmSuccess()throws Exception{
        File file=fixture();warm(file);File first=ZipVolumeResolver.forSnapshot(file).get(0);long stamp=first.lastModified();byte[] bytes=Files.readAllBytes(first.toPath());int local=find(bytes,new byte[]{'P','K',3,4},0);assertTrue(local>=0);int data=local+30+u16(bytes,local+26)+u16(bytes,local+28);bytes[data]^=1;Files.write(first.toPath(),bytes);assertTrue(first.setLastModified(stamp+5000));File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");assertFalse(ArchiveSupport.extractSingleEntryDetailed(file,original(),out,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void removedMiddleDirectoryVolumeInvalidatesWarmIndex()throws Exception{
        File file=fixture();warm(file);List<File> parts=ZipVolumeResolver.forSnapshot(file);assertTrue(parts.size()>=3);Files.delete(parts.get(1).toPath());File out=temp.newFile();ArchiveTestTextFiles.writeString(out.toPath(),"keep");assertFalse(ArchiveSupport.extractSingleEntryDetailed(file,original(),out,null).success);assertEquals("keep",ArchiveTestTextFiles.readString(out.toPath()));
    }
    @Test public void releaseFromMiddleVolumeRetiresFinalVolumeIndex()throws Exception{
        File file=fixture();warm(file);List<File> parts=ZipVolumeResolver.forSnapshot(file);ArchiveSupport.releaseViewerArchiveIndex(parts.get(1));assertFalse(ZipxAesArchiveReader.hasCachedIndex(file));for(File part:parts)assertTrue(part.delete());
    }
    private static int u16(byte[] bytes,int at){return (bytes[at]&255)|((bytes[at+1]&255)<<8);}
    private static int find(byte[] bytes,byte[] marker,int start){outer:for(int i=start;i<=bytes.length-marker.length;i++){for(int j=0;j<marker.length;j++)if(bytes[i+j]!=marker[j])continue outer;return i;}return -1;}
}
