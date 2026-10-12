package com.readwide.manager.archive;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Renamed numbered RAR volumes keep their split identity through public routes. */
public final class CbrNumberedSplitRouteTest {
    private final CbrLegacySplitRouteTest fixtures=new CbrLegacySplitRouteTest();
    @Rule public final TemporaryFolder temporary=fixtures.temporary;
    @Test public void allCbrPartsExtractFromEitherSelectedVolume()throws Exception{both(fixture("CBR","cbr"));}
    @Test public void renamedFirstPartKeepsRarContinuation()throws Exception{both(fixture("cbr","rar"));}
    @Test public void renamedContinuationKeepsRarFirstPart()throws Exception{both(fixture("rar","CBR"));}
    @Test public void ordinaryNumberedRarIsUnchanged()throws Exception{both(fixture("rar","rar"));}
    @Test public void snapshotTracksBothRenamedNumberedVolumes()throws Exception{
        File[] files=fixture("cbr","rar");ArchiveSourceSnapshot snapshot=ArchiveSourceSnapshot.capture(files[1]);
        assertTrue(snapshot.containsFile(files[0]));assertTrue(snapshot.containsFile(files[1]));
        assertTrue(files[0].setLastModified(files[0].lastModified()+5000));assertFalse(snapshot.matches(files[1]));
    }
    @Test public void competingSameOrdinalRarAndCbrFailClosed()throws Exception{
        File[] files=fixture("rar","rar");File duplicate=new File(files[0].getParentFile(),"book.part01.cbr");Files.copy(files[0].toPath(),duplicate.toPath());
        for(File selected:new File[]{files[0],files[1],duplicate}){
            assertThrows(IOException.class,()->RarArchiveLocator.collectReadableVolumes(selected));preservedFailure(selected);
        }
    }
    @Test public void missingFirstCbrVolumeUsesNumberedDiagnostic()throws Exception{
        File[] files=fixture("cbr","cbr");assertTrue(files[0].delete());
        assertEquals("book.part01.cbr",RarVolumeNameResolver.resolve(files[1]).firstVolume().getName());
        assertThrows(IOException.class,()->RarArchiveLocator.collectReadableVolumes(files[1]));preservedFailure(files[1]);
    }
    @Test public void numberedCbrGapIsRejectedBeforeExtraction()throws Exception{
        File[] files=fixture("cbr","cbr");File third=new File(files[1].getParentFile(),"book.part03.cbr");Files.move(files[1].toPath(),third.toPath());
        assertTrue(RarVolumeNameResolver.resolve(files[0]).hasKnownGap());
        assertThrows(IOException.class,()->RarArchiveLocator.collectReadableVolumes(files[0]));preservedFailure(files[0]);
    }
    @Test public void invalidCbrOrdinalsAreRejected()throws Exception{
        for(String digits:new String[]{"00","99999999999999999999"}){
            File bad=new File(temporary.newFolder(),"book.part"+digits+".cbr");Files.write(bad.toPath(),new byte[0]);
            assertThrows(IOException.class,()->RarArchiveLocator.collectReadableVolumes(bad));
        }
    }
    private void both(File[] files)throws Exception{
        for(File selected:files){
            File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");
            ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(selected,"page.bin",output,null);
            assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(output.toPath()));
        }
    }
    private void preservedFailure(File selected)throws Exception{
        File output=temporary.newFile();ArchiveTestTextFiles.writeString(output.toPath(),"original");
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(selected,"page.bin",output,null).success);assertEquals("original",ArchiveTestTextFiles.readString(output.toPath()));
    }
    private File[] fixture(String firstExtension,String secondExtension)throws Exception{
        Method build=CbrLegacySplitRouteTest.class.getDeclaredMethod("fixture",String.class);build.setAccessible(true);
        File[] files=(File[])build.invoke(fixtures,"rar");
        String[] extensions={firstExtension,secondExtension};
        for(int i=0;i<files.length;i++){
            byte[] bytes=Files.readAllBytes(files[i].toPath());bytes[10]|=0x10; // MHD_NEWNUMBERING, preserve valid header CRC.
            CRC32 crc=new CRC32();crc.update(bytes,9,11);long sum=crc.getValue();bytes[7]=(byte)sum;bytes[8]=(byte)(sum>>>8);Files.write(files[i].toPath(),bytes);
            File renamed=new File(files[i].getParentFile(),"book.part0"+(i+1)+"."+extensions[i]);Files.move(files[i].toPath(),renamed.toPath());files[i]=renamed;
        }
        return files;
    }
}
