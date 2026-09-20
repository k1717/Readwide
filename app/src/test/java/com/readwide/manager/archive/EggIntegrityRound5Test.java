package com.readwide.manager.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class EggIntegrityRound5Test {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] OLD = Round5Fixtures.bytes("keep the existing document");
    private static final String[] NAMES = {"first.bin", "middle.bin", "last.bin"};
    private static final byte[][] DATA = {new byte[]{1,2,3},new byte[]{4,5,6,7,8},new byte[]{9,10,11,12}};
    private File output(boolean existing) throws Exception {
        File dir=temp.newFolder();
        if(existing)for(String n:NAMES)Files.write(new File(dir,n).toPath(),OLD);
        return dir;
    }
    private void preserved(File dir,boolean existing) throws Exception {
        assertEquals(existing?3:0,Objects.requireNonNull(dir.list()).length);
        for(String n:NAMES)if(existing)assertArrayEquals(OLD,Files.readAllBytes(new File(dir,n).toPath()));
    }
    private File solid(int method,int[] ends,int corrupt) throws Exception {
        return Round5Fixtures.egg(temp.newFolder(),NAMES,DATA,true,ends,corrupt,method);
    }
    @Test public void independentWholeExtractionRestoresExistingFileOnBadCrc() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"data.bin"},new byte[][]{{1,2,3}},false,null,0,0);
        File dir=temp.newFolder(),old=new File(dir,"data.bin");Files.write(old.toPath(),OLD);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));
        assertArrayEquals(OLD,Files.readAllBytes(old.toPath()));assertEquals(1,dir.list().length);
    }
    @Test public void solidSingleExtractionRestoresExistingFileOnBadCrc() throws Exception {
        File file=solid(0,new int[]{12},0),out=temp.newFile();Files.write(out.toPath(),OLD);
        assertThrows(IOException.class,()->EggArchiveReader.extractSingleEntry(file,"first.bin",out,null));
        assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }
    @Test public void solidStoredBadBlockRestoresAllThreeDestinations() throws Exception {
        File file=solid(0,new int[]{12},0),dir=output(true);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));preserved(dir,true);
    }
    @Test public void solidDeflateBadBlockRestoresAllThreeDestinations() throws Exception {
        File file=solid(1,new int[]{12},0),dir=output(true);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));preserved(dir,true);
    }
    @Test public void solidBadBlockRemovesAllNewOutputs() throws Exception {
        File file=solid(0,new int[]{12},0),dir=output(false);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));preserved(dir,false);
    }
    @Test public void verifiedEarlierBlockMayRemainWhenLaterBlockFails() throws Exception {
        File file=solid(0,new int[]{3,12},1),dir=output(true);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));
        assertArrayEquals(DATA[0],Files.readAllBytes(new File(dir,NAMES[0]).toPath()));
        for(int i=1;i<3;i++)assertArrayEquals(OLD,Files.readAllBytes(new File(dir,NAMES[i]).toPath()));
        assertEquals(3,dir.list().length);
    }
    @Test public void entrySpanningFailedBlockRestoresItsOriginal() throws Exception {
        File file=solid(1,new int[]{1,12},1),dir=output(true);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));preserved(dir,true);
    }
    @Test public void successfulSolidMultiBlockExtractionCommitsEveryOutput() throws Exception {
        File file=solid(1,new int[]{1,4,12},-1),dir=output(true);
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));
        for(int i=0;i<3;i++)assertArrayEquals(DATA[i],Files.readAllBytes(new File(dir,NAMES[i]).toPath()));
        assertEquals(3,dir.list().length);
    }
    @Test public void duplicatePathsRollbackInReverseOrder() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"same","same","same"},DATA,true,new int[]{12},0,0);
        File dir=temp.newFolder(),old=new File(dir,"same");Files.write(old.toPath(),OLD);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));
        assertArrayEquals(OLD,Files.readAllBytes(old.toPath()));assertEquals(1,dir.list().length);
    }
    @Test public void duplicatePathsSuccessKeepsLastEntry() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"same","same","same"},DATA,true,new int[]{12},-1,0);
        File dir=temp.newFolder();assertTrue(EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));
        assertArrayEquals(DATA[2],Files.readAllBytes(new File(dir,"same").toPath()));assertEquals(1,dir.list().length);
    }
    @Test public void directoryOnlyIndependentArchiveIsExtracted() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"empty/nested/"},new byte[][]{new byte[0]},false,null,-1,0);
        File dir=temp.newFolder();assertTrue(EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));
        assertTrue(new File(dir,"empty/nested").isDirectory());
    }
    @Test public void directoryOnlySolidArchiveIsExtracted() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"empty/"},new byte[][]{new byte[0]},true,new int[0],-1,0);
        File dir=temp.newFolder();assertTrue(EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));assertTrue(new File(dir,"empty").isDirectory());
    }
    @Test public void trailingEmptySolidFilesAreCreated() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"one","empty"},new byte[][]{{1},new byte[0]},true,new int[]{1},-1,0);
        File dir=temp.newFolder();assertTrue(EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));assertEquals(0,new File(dir,"empty").length());assertTrue(new File(dir,"empty").isFile());assertEquals(2,dir.list().length);
    }
    @Test public void failedDirectoryCreationDoesNotReportSuccess() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"blocked/child/"},new byte[][]{new byte[0]},false,null,-1,0);
        File dir=temp.newFolder();Files.write(new File(dir,"blocked").toPath(),OLD);
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));
        assertArrayEquals(OLD,Files.readAllBytes(new File(dir,"blocked").toPath()));
    }
    @Test public void unsafeSolidPathDoesNotShiftFollowingEntry() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"../outside","ok"},new byte[][]{{1,2},{3,4}},true,new int[]{4},-1,0);
        File dir=temp.newFolder();assertTrue(EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));assertArrayEquals(new byte[]{3,4},Files.readAllBytes(new File(dir,"ok").toPath()));assertEquals(1,dir.list().length);
    }
    @Test public void manyFilesInOneFailedBlockAreAllRolledBack() throws Exception {
        int count=129;String[] names=new String[count];byte[][] bytes=new byte[count][];
        for(int i=0;i<count;i++){names[i]="entry"+i;bytes[i]=new byte[]{(byte)i};}
        File file=Round5Fixtures.egg(temp.newFolder(),names,bytes,true,new int[]{count},0,0),dir=temp.newFolder();
        assertThrows(IOException.class,()->EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));assertEquals(0,dir.list().length);
    }
    @Test public void cancelledIndependentExtractionKeepsExistingFile() throws Exception {
        File file=Round5Fixtures.egg(temp.newFolder(),new String[]{"data.bin"},new byte[][]{{1}},false,null,-1,0);
        File out=temp.newFile();Files.write(out.toPath(),OLD);
        try{Thread.currentThread().interrupt();assertThrows(IOException.class,()->EggArchiveReader.extractSingleEntry(file,"data.bin",out,null));}
        finally{Thread.interrupted();}
        assertArrayEquals(OLD,Files.readAllBytes(out.toPath()));
    }

    @Test public void successfulSolidArchiveExtracts129Files()throws Exception{
        int count=129;String[] names=new String[count];byte[][] bytes=new byte[count][];
        for(int i=0;i<count;i++){names[i]="entry"+i;bytes[i]=new byte[]{(byte)i};}
        File file=Round5Fixtures.egg(temp.newFolder(),names,bytes,true,new int[]{count},-1,1),dir=temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(file,dir,null));assertEquals(count,dir.list().length);
        for(int i=0;i<count;i++)assertArrayEquals(bytes[i],Files.readAllBytes(new File(dir,names[i]).toPath()));
    }
}
