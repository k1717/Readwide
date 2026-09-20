package com.readwide.manager.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class EggPackedAuthenticationRound6Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private void succeeds(int bits,int blocks,int size)throws Exception{
        File archive=Round6Fixtures.aesEgg(temp.newFolder(),bits,blocks,size,false,false),out=temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive,"data.bin",out,Round6Fixtures.PASSWORD));
        ByteArrayOutputStream expected=new ByteArrayOutputStream();for(int i=0;i<blocks;i++)expected.write(Round6Fixtures.PLAIN);
        assertArrayEquals(expected.toByteArray(),Files.readAllBytes(out.toPath()));
    }
    private void rejects(int bits,boolean prefix,boolean tamper,boolean whole,boolean existing)throws Exception{
        File archive=Round6Fixtures.aesEgg(temp.newFolder(),bits,1,140000,prefix,tamper),dir=temp.newFolder(),out=new File(dir,"data.bin");
        byte[] original={9,8,7};if(existing)Files.write(out.toPath(),original);
        IOException error=assertThrows(IOException.class,()->{
            if(whole)EggArchiveReader.extractArchiveIntoDirectory(archive,dir,Round6Fixtures.PASSWORD);
            else EggArchiveReader.extractSingleEntry(archive,"data.bin",out,Round6Fixtures.PASSWORD);
        });
        assertTrue(error.getMessage().contains("authentication"));
        if(existing)assertArrayEquals(original,Files.readAllBytes(out.toPath()));else assertFalse(out.exists());
        assertEquals(existing?1:0,dir.list().length);
    }
    @Test public void aes128AuthenticatesEntireDeclaredPackedBlock()throws Exception{succeeds(128,1,140000);}
    @Test public void aes256AuthenticatesEntireDeclaredPackedBlock()throws Exception{succeeds(256,1,140000);}
    @Test public void aesCounterRemainsAlignedAcrossBlocks()throws Exception{succeeds(256,3,140000);}
    @Test public void ordinaryShortEncryptedDeflateStillWorks()throws Exception{succeeds(128,2,0);}
    @Test public void prefixOnlyMacRejectedSingleExisting()throws Exception{rejects(128,true,false,false,true);}
    @Test public void prefixOnlyMacRejectedWholeExisting()throws Exception{rejects(256,true,false,true,true);}
    @Test public void prefixOnlyMacRejectedWholeNew()throws Exception{rejects(128,true,false,true,false);}
    @Test public void tamperedUnconsumedTailRejected()throws Exception{rejects(256,true,true,false,true);}
    @Test public void fullMacWithTamperedTailRejected()throws Exception{rejects(128,false,true,true,true);}
    @Test public void bufferBoundaryCasesRemainByteExact()throws Exception{
        for(int size:new int[]{65535,65536,65537,131071,131072,131073})succeeds(128,2,size);
    }
    @Test public void wrongPasswordPreservesOutput()throws Exception{
        File archive=Round6Fixtures.aesEgg(temp.newFolder(),256,1,140000,false,false),out=temp.newFile();
        Files.write(out.toPath(),new byte[]{42});
        assertThrows(IOException.class,()->EggArchiveReader.extractSingleEntry(archive,"data.bin",out,"wrong".toCharArray()));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(out.toPath()));
    }
}
