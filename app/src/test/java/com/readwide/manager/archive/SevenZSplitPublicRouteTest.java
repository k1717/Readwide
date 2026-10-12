package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Public-route regressions for the supplemental reader's logical split input. */
public class SevenZSplitPublicRouteTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = Round4Fixtures.data(8192);

    @Test public void publicSelectedPlainSwapAcrossVolumes() throws Exception { selected(false, false); }
    @Test public void publicSelectedAesSwapAcrossVolumes() throws Exception { selected(true, false); }
    @Test public void publicSelectedAesEncodedHeaderAcrossVolumes() throws Exception { selected(true, true); }
    @Test public void publicBulkPlainSwapAcrossVolumes() throws Exception { bulk(false, false); }
    @Test public void publicBulkAesEncodedHeaderAcrossVolumes() throws Exception { bulk(true, true); }

    @Test public void publicListingAesSpecialEncodedHeaderFromLaterPart() throws Exception {
        File[] parts = fixture(true, true, false);
        assertEquals(2, ArchiveSupport.listEntries(parts[parts.length-1], Round4Fixtures.PASSWORD).size());
    }

    @Test public void missingVolumePreservesSelectedDestination() throws Exception {
        File[] parts = fixture(true, true, false);
        assertTrue(parts[2].delete());
        File out = existing();
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(parts[0], "2.bin", out, Round4Fixtures.PASSWORD).success);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
    }

    @Test public void packedCrcFailurePreservesSelectedDestination() throws Exception {
        File[] parts = fixture(false, false, false);
        byte[] bytes = Files.readAllBytes(parts[3].toPath()); bytes[5] ^= 1; Files.write(parts[3].toPath(),bytes);
        File out = existing();
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(parts[0], "2.bin", out, null).success);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
    }

    @Test public void missingPasswordDoesNotPublishTarget() throws Exception {
        File[] parts = fixture(true, true, false); File out = existing();
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(parts[0], "2.bin", out, null).success);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
    }

    @Test public void wrongPasswordDoesNotPublishTarget() throws Exception {
        File[] parts = fixture(true, true, false); File out = existing();
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(parts[0], "2.bin", out, "wrong".toCharArray()).success);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
    }

    @Test public void interruptedSelectionKeepsFlagAndDestination() throws Exception {
        File[] parts = fixture(true, true, false); File out = existing();
        Thread.currentThread().interrupt();
        try {
            assertFalse(ArchiveSupport.extractSingleEntryDetailed(parts[0], "2.bin", out, Round4Fixtures.PASSWORD).success);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(out.toPath()));
    }

    @Test public void publicSelectedAndBulkAesLz4EncodedHeaderAcrossVolumes() throws Exception {
        SevenZLz4ArchiveTest fixtures = new SevenZLz4ArchiveTest(); fixtures.temp = temp;
        Method method = SevenZLz4ArchiveTest.class.getDeclaredMethod("archive", boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, boolean.class);
        method.setAccessible(true);
        File[] parts = split((File)method.invoke(fixtures,true,true,true,false,false,false));
        File out = existing();
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractSingleEntryDetailed(parts[4],"2.bin",out,Round4Fixtures.PASSWORD);
        assertTrue(result.failure+": "+result.detail,result.success);
        byte[] expected = new byte[4096]; for(int i=0;i<expected.length;i++) expected[i]=(byte)((4096+i)%47);
        assertArrayEquals(expected,Files.readAllBytes(out.toPath()));
        File directory = new File(temp.newFolder(),"bulk");
        assertTrue(ArchiveSupport.extractArchive(parts[4],directory,false,Round4Fixtures.PASSWORD));
        assertArrayEquals(expected,Files.readAllBytes(new File(directory,"2.bin").toPath()));
    }

    private void selected(boolean aes, boolean encoded) throws Exception {
        File[] parts = fixture(aes,encoded,false); File out=existing();
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(parts[4],"2.bin",out,aes?Round4Fixtures.PASSWORD:null);
        assertTrue(result.failure+": "+result.detail,result.success);
        assertArrayEquals(Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),Files.readAllBytes(out.toPath()));
        for(File part:parts) assertTrue("Source handles must close",part.delete());
    }
    private void bulk(boolean aes,boolean encoded) throws Exception {
        File[] parts=fixture(aes,encoded,false); File directory=new File(temp.newFolder(),"bulk");
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractArchiveDetailed(parts[4],directory,false,aes?Round4Fixtures.PASSWORD:null,null);
        assertTrue(result.failure+": "+result.detail,result.success);
        assertArrayEquals(Arrays.copyOfRange(DATA,0,DATA.length/2),Files.readAllBytes(new File(directory,"1.bin").toPath()));
        assertArrayEquals(Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),Files.readAllBytes(new File(directory,"2.bin").toPath()));
    }
    private File[] fixture(boolean aes,boolean encoded,boolean badCrc) throws Exception {
        return split(Round4Fixtures.sevenZ(temp.newFolder(),DATA,4,aes,encoded,badCrc,true));
    }
    private File[] split(File archive) throws Exception {
        byte[] bytes=Files.readAllBytes(archive.toPath()); Files.delete(archive.toPath());
        int[] cuts={0,3,31,40,bytes.length-7,bytes.length}; File[] parts=new File[5];
        for(int i=0;i<parts.length;i++) parts[i]=Round4Fixtures.write(archive.getParentFile(),
                String.format(Locale.ROOT,"book.7z.%03d",i+1),Arrays.copyOfRange(bytes,cuts[i],cuts[i+1]));
        return parts;
    }
    private File existing() throws Exception { File f=temp.newFile();Files.write(f.toPath(),new byte[]{42});return f; }
}
