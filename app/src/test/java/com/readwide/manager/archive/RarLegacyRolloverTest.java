package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** In-process stored RAR4 containers; no restricted encoder or decoder is used. */
public final class RarLegacyRolloverTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private static final byte[] PAYLOAD="legacy rollover page".getBytes(StandardCharsets.UTF_8);
    @Test public void publicSelectedFromBaseFindsEntryAfterR99()throws Exception{publicSelected(false);}
    @Test public void publicSelectedFromS00ResolvesFullChain()throws Exception{publicSelected(true);}
    private void publicSelected(boolean later)throws Exception{
        File directory=fixture();File selected=new File(directory,later?"book.s00":"book.rar");
        File output=temporary.newFile();
        ArchiveSupport.ExtractionResult result=ArchiveSupport.extractSingleEntryDetailed(selected,"page.bin",output,null);
        assertTrue(result.failure+": "+result.detail,result.success);assertArrayEquals(PAYLOAD,Files.readAllBytes(output.toPath()));
        assertEquals(102,RarArchiveLocator.collectReadableVolumes(selected).size());
    }
    @Test public void missingR99IsDetectedBeforeDecodingS00()throws Exception{
        File directory=fixture();assertTrue(new File(directory,"book.r99").delete());
        RarVolumeChainResolution resolution=RarVolumeChainResolution.resolve(new File(directory,"book.s00"));
        assertTrue(resolution.knownGapBeforeSelected());assertEquals(99,resolution.nextMissingPartIndex());
        assertThrows(IOException.class,resolution::requireReadableChain);
    }
    @Test public void decimalAndLetterAliasesAreRejectedAsAmbiguous()throws Exception{
        File directory=fixture();Files.write(new File(directory,"book.r100").toPath(),new byte[0]);
        assertThrows(IOException.class,()->RarArchiveLocator.collectReadableVolumes(new File(directory,"book.rar")));
    }
    @Test public void upperCaseRolloverNameAndOutputBaseAreRecognized(){
        assertEquals(ArchiveSupport.Type.RAR,ArchiveTypeDetector.fromFileName("BOOK.S00"));
        assertTrue(ArchiveTypeDetector.isRarSplitPart(new File("BOOK.S00")));
        assertEquals("BOOK",ArchiveTypeDetector.outputBaseName(new File("BOOK.S00"),"archive"));
    }
    @Test public void resolverOrdersAllLetterRangesThroughZ99(){
        File[] children=new File[901];children[0]=new File("virtual","book.rar");
        for(int ordinal=0;ordinal<900;ordinal++)children[ordinal+1]=new File("virtual",String.format(Locale.ROOT,"book.%c%02d",'r'+ordinal/100,ordinal%100));
        File parent=new File("virtual"){@Override public File[] listFiles(){return children;}};
        File selected=new File(parent,"book.z99"){@Override public File getParentFile(){return parent;}};
        RarVolumeNameResolver.Result result=RarVolumeNameResolver.resolve(selected);
        assertEquals(901,result.volumes().size());assertEquals(899,result.selectedPartIndex());assertFalse(result.hasKnownGap());
        assertEquals(children[101],result.volumes().get(101));assertEquals(children[900],result.volumes().get(900));
    }
    @Test public void unrelatedAndNoncanonicalNamesStayUnrecognized(){
        for(String name:new String[]{"book.q99","book.s001","book.s0","book.za0"})assertNull(name,ArchiveTypeDetector.fromFileName(name));
    }
    @Test public void existingDecimalR100NamingRemainsSupported()throws Exception{
        File directory=fixture();Files.move(new File(directory,"book.s00").toPath(),new File(directory,"book.r100").toPath());
        assertEquals(102,RarArchiveLocator.collectReadableVolumes(new File(directory,"book.r100")).size());
    }
    private File fixture()throws Exception{
        File directory=temporary.newFolder();
        for(int i=-1;i<=100;i++){
            String suffix=i<0?"rar":i<100?String.format(Locale.ROOT,"r%02d",i):"s00";
            ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{0x52,0x61,0x72,0x21,0x1a,7,0});
            out.write(header(0x73,1|(i<0?0x100:0),new byte[6]));
            if(i==100){
                byte[] name="page.bin".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream body=new ByteArrayOutputStream();
                le(body,PAYLOAD.length,4);le(body,PAYLOAD.length,4);body.write(1);CRC32 crc=new CRC32();crc.update(PAYLOAD);le(body,crc.getValue(),4);
                le(body,0,4);body.write(29);body.write(0x30);le(body,name.length,2);le(body,0,4);body.write(name);
                out.write(header(0x74,0x8000,body.toByteArray()));out.write(PAYLOAD);
            }
            out.write(header(0x7b,i<100?1:0,new byte[0]));Files.write(new File(directory,"book."+suffix).toPath(),out.toByteArray());
        }
        return directory;
    }
    private static byte[] header(int type,int flags,byte[] body)throws Exception{
        ByteArrayOutputStream data=new ByteArrayOutputStream();data.write(type);le(data,flags,2);le(data,body.length+7,2);data.write(body);
        CRC32 crc=new CRC32();crc.update(data.toByteArray());ByteArrayOutputStream result=new ByteArrayOutputStream();le(result,crc.getValue(),2);result.write(data.toByteArray());return result.toByteArray();
    }
    private static void le(OutputStream out,long value,int bytes)throws IOException{for(int i=0;i<bytes;i++)out.write((int)(value>>>(8*i))&255);}
}
