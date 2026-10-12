package com.readwide.manager.archive;

import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.codecs.CabNativeDecoder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Public app routes with the real bundled decoder; source only until authorized to run. */
@RunWith(AndroidJUnit4.class)
public class CabArchiveRoutingInstrumentedTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Before public void requireNative() { assertTrue("Bundled CAB decoder is required",CabNativeDecoder.isAvailable()); }

    @Test public void everySelectedVolumeListsAndExtractsTheSameEntries() throws Exception {
        File[] parts=parts();
        for (File selected:parts) {
            assertEquals(parts[0].getCanonicalFile(),ArchiveSupport.normalizeExtractionQueueArchive(selected));
            List<ArchiveSupport.EntryInfo> entries=ArchiveSupport.listEntries(selected,null);
            assertEquals(3,entries.size());
            for (int i=0;i<entries.size();i++) {
                assertEquals("test"+(i+1)+".txt",entries.get(i).path);
                assertEquals(expected(i).length,entries.get(i).size);
                File output=temporary.newFile();
                assertTrue(ArchiveSupport.extractSingleEntry(selected,entries.get(i).path,output,null));
                assertArrayEquals(expected(i),read(output));
            }
        }
    }

    @Test public void publicBulkRouteReplacesTargetOnlyAfterCompletingTheSet() throws Exception {
        File[] parts=parts();File target=temporary.newFolder();
        write(new File(target,"previous"),new byte[]{9});
        assertTrue(ArchiveSupport.extractArchive(parts[2],target,true,null));
        assertFalse(new File(target,"previous").exists());
        for (int i=0;i<3;i++) assertArrayEquals(expected(i),read(new File(target,"test"+(i+1)+".txt")));
    }

    @Test public void missingVolumeDoesNotReplaceSelectedOrBulkOutput() throws Exception {
        File[] parts=parts();assertTrue(parts[4].delete());
        File output=temporary.newFile();write(output,new byte[]{9});
        File target=temporary.newFolder();File keep=new File(target,"previous");write(keep,new byte[]{8});
        assertFalse(ArchiveSupport.extractSingleEntry(parts[0],"test1.txt",output,null));
        assertFalse(ArchiveSupport.extractArchive(parts[0],target,true,null));
        assertArrayEquals(new byte[]{9},read(output));assertArrayEquals(new byte[]{8},read(keep));
    }

    @Test public void publicSelectionCannotRenameOrOverwriteAnotherSourceVolume() throws Exception {
        File[] parts=parts();byte[] before=read(parts[4]);
        assertFalse(ArchiveSupport.extractSingleEntry(parts[0],"test1.txt",parts[4],null));
        assertArrayEquals(before,read(parts[4]));
        assertEquals(3,ArchiveSupport.listEntries(parts[0],null).size());
    }

    @Test public void viewerSkipsHeadersThenReadsCheckedSpoolInSmallChunks() throws Exception {
        File[] parts=parts();File spools=temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader=ArchiveSupport.openForwardReader(parts[3],null,spools)) {
            assertNotNull(reader);assertTrue(reader.skipsUnreadEntryOnAdvance());
            assertEquals("test1.txt",reader.nextEntry().path);
            assertEquals("test2.txt",reader.nextEntry().path);
            assertEquals(0,spools.list().length);
            assertArrayEquals(expected(1),readEntry(reader));
            assertEquals("test3.txt",reader.nextEntry().path);
            assertArrayEquals(expected(2),readEntry(reader));
            assertNull(reader.nextEntry());assertEquals(0,spools.list().length);
        }
        assertEquals(0,spools.list().length);
    }

    @Test public void changedSiblingRetiresViewerBeforePublishingBytes() throws Exception {
        File[] parts=parts();File spools=temporary.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader=ArchiveSupport.openForwardReader(parts[0],null,spools)) {
            assertNotNull(reader);reader.nextEntry();write(parts[4],new byte[0]);
            byte[] buffer={9,9};
            assertThrows(IOException.class,()->reader.read(buffer));
            assertArrayEquals(new byte[]{9,9},buffer);assertEquals(0,spools.list().length);
            assertThrows(IOException.class,reader::nextEntry);
        }
    }

    @Test public void explicitSetWithNumericNameIsNotConcatenated() throws Exception {
        File[] parts=parts();String old=parts[0].getName();
        String name=old.substring(0,old.length()-8)+".cab.001";
        byte[] second=read(parts[1]),oldBytes=old.getBytes(StandardCharsets.US_ASCII),newBytes=name.getBytes(StandardCharsets.US_ASCII);
        assertEquals(oldBytes.length,newBytes.length);boolean replaced=false;
        for (int i=0;i<=second.length-oldBytes.length;i++) {
            if (Arrays.equals(oldBytes,Arrays.copyOfRange(second,i,i+oldBytes.length))) {
                System.arraycopy(newBytes,0,second,i,newBytes.length);replaced=true;break;
            }
        }
        assertTrue(replaced);write(parts[1],second);
        File renamed=new File(parts[0].getParentFile(),name);assertTrue(parts[0].renameTo(renamed));
        assertTrue(ArchiveSupport.isNumericSplitArchive(renamed));
        assertEquals(3,ArchiveSupport.listEntries(renamed,null).size());
        File output=temporary.newFile();assertTrue(ArchiveSupport.extractSingleEntry(renamed,"test3.txt",output,null));
        assertArrayEquals(expected(2),read(output));
    }

    private File[] parts() throws IOException {
        File directory=temporary.newFolder();File[] files=new File[5];
        for (int i=0;i<files.length;i++) {
            String name="multi_basic_pt"+(i+1)+".cab";
            try (InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name+".base64")) {
                files[i]=new File(directory,"cabd_"+name);write(files[i],Base64.decode(read(input),Base64.DEFAULT));
            }
        }
        return files;
    }
    private static byte[] expected(int entry) {
        String text=entry==0 ? line(1)+line(2) : entry==1 ? line(3) : line(4)+line(5);
        return text.getBytes(StandardCharsets.US_ASCII);
    }
    private static String line(int part) { return "This is the data from cabinet part "+part+".\n"; }
    private static byte[] read(File file) throws IOException {
        try (InputStream input=new FileInputStream(file)) { return read(input); }
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
        while ((count=input.read(buffer))!=-1) output.write(buffer,0,count);
        return output.toByteArray();
    }
    private static byte[] readEntry(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[7];int count;
        while ((count=reader.read(buffer))!=-1) output.write(buffer,0,count);
        return output.toByteArray();
    }
    private static void write(File file,byte[] bytes) throws IOException {
        try (FileOutputStream output=new FileOutputStream(file)) { output.write(bytes); }
    }
}
