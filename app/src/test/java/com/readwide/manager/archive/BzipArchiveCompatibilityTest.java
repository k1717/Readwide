package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic bounded-payload compositions; producer/device validation is separate. */
public class BzipArchiveCompatibilityTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final byte[] DATA=Round4Fixtures.data(4096);

    @Test public void alzStandardAndLeadingBzStrippedStreamsDecodeCompletely() throws Exception {
        byte[] packed=concatenated(DATA);
        for(byte[] payload:new byte[][]{packed,Arrays.copyOfRange(packed,2,packed.length)}) {
            File archive=Round4Fixtures.write(temp.newFolder(),"data.alz",Round4Fixtures.alzStored(DATA,1,payload,false));
            success(archive,"data.bin",DATA,null);
        }
    }
    @Test public void alzEncryptedSplitStreamsKeepCipherContinuity() throws Exception {
        byte[] archive=Round4Fixtures.alzStored(DATA,1,concatenated(DATA),true);
        List<File> parts=Round4Fixtures.splitAlz(temp.newFolder(),"data",archive,3);
        success(parts.get(2),"data.bin",DATA,Round4Fixtures.PASSWORD);
    }
    @Test public void eggPlainStreamsDecodeCompletely() throws Exception {
        File archive=Round4Fixtures.write(temp.newFolder(),"data.egg",Round4Fixtures.eggStored(DATA,2,concatenated(DATA),false));
        success(archive,"data.bin",DATA,null);
    }
    @Test public void eggEncryptedSplitStreamsKeepCipherContinuity() throws Exception {
        byte[] archive=Round4Fixtures.eggStored(DATA,2,concatenated(DATA),true);
        List<File> parts=Round4Fixtures.splitEgg(temp.newFolder(),"data",archive,3);
        success(parts.get(2),"data.bin",DATA,Round4Fixtures.PASSWORD);
    }
    @Test public void alzAndEggRejectMalformedLaterStreamsWithoutReplacingTargets() throws Exception {
        byte[] valid=concatenated(DATA);
        byte[] truncated=Arrays.copyOf(valid,valid.length-5);
        byte[] junk=Arrays.copyOf(valid,valid.length+1);junk[junk.length-1]=1;
        for(boolean egg:new boolean[]{false,true})for(byte[] packed:new byte[][]{truncated,junk}) {
            byte[] bytes=egg?Round4Fixtures.eggStored(DATA,2,packed,true):Round4Fixtures.alzStored(DATA,1,packed,true);
            File archive=Round4Fixtures.write(temp.newFolder(),egg?"bad.egg":"bad.alz",bytes);
            File output=temp.newFile();Files.write(output.toPath(),new byte[]{42});
            assertThrows(IOException.class,()->{
                if(egg)EggArchiveReader.extractSingleEntry(archive,"data.bin",output,Round4Fixtures.PASSWORD);
                else AlzipArchiveReader.extractSingleEntry(archive,"data.bin",output,Round4Fixtures.PASSWORD);
            });
            assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void sevenZPlainBzipUsesSupplementalExtraction() throws Exception {
        File archive=sevenZ(false,false,false,false,null);
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive,null));
        success(archive,"data.bin",DATA,null);
    }
    @Test public void sevenZAesSolidAndEncodedHeaderDecodeAllStreams() throws Exception {
        File archive=sevenZ(true,true,true,false,null);
        success(archive,"2.bin",Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),Round4Fixtures.PASSWORD);
    }
    @Test public void sevenZBzipOnlyInEncodedHeaderStillSelectsDedicatedForward() throws Exception {
        File archive=sevenZ(false,true,false,true,null);
        forward(archive,false);
    }
    @Test public void sevenZSolidSplitUsesDedicatedForwardFromLaterVolume() throws Exception {
        File archive=sevenZ(false,true,true,false,null);
        byte[] bytes=Files.readAllBytes(archive.toPath());
        File directory=temp.newFolder(),later=null;
        for(int i=0;i<3;i++) {
            later=new File(directory,"data.7z.00"+(i+1));
            Files.write(later.toPath(),Arrays.copyOfRange(bytes,bytes.length*i/3,bytes.length*(i+1)/3));
        }
        forward(later,true);
    }
    @Test public void sevenZMalformedSecondStreamWithValidPackedCrcRestoresTarget() throws Exception {
        byte[] bad=concatenated(DATA);bad[bad.length-2]^=0x40;
        File archive=sevenZ(false,false,false,false,bad),output=temp.newFile();
        Files.write(output.toPath(),new byte[]{42});
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.extractSingleEntry(archive,"data.bin",output,null));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }
    @Test public void sevenZAesWrongPasswordPreservesTarget() throws Exception {
        File archive=sevenZ(true,false,false,false,null),output=temp.newFile();
        Files.write(output.toPath(),new byte[]{42});
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.extractSingleEntry(archive,"data.bin",output,"wrong".toCharArray()));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }
    @Test public void publicSevenZDoesNotAcceptValidPrefixFollowedByBrokenEmptyStream() throws Exception {
        ByteArrayOutputStream packed=new ByteArrayOutputStream();
        try(OutputStream bz=new BZip2CompressorOutputStream(packed)) { bz.write(DATA); }
        // The first complete stream has the full output size and correct CRC.
        // A single-stream backend would accept it and overlook this truncated tail.
        packed.write(new byte[]{'B','Z','h','9'});
        File archive=sevenZ(false,false,false,false,packed.toByteArray()),output=temp.newFile();
        Files.write(output.toPath(),new byte[]{42});
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"data.bin",output,null).success);
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
        File directory=temp.newFolder(),existing=new File(directory,"data.bin");
        Files.write(existing.toPath(),new byte[]{43});
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive,directory,true,null,null).success);
        assertArrayEquals(new byte[]{43},Files.readAllBytes(existing.toPath()));
    }

    private void success(File archive,String name,byte[] expected,char[] password) throws Exception {
        File output=temp.newFile();
        ArchiveSupport.ExtractionResult one=ArchiveSupport.extractSingleEntryDetailed(archive,name,output,password);
        assertTrue(one.failure+": "+one.detail,one.success);
        assertArrayEquals(expected,Files.readAllBytes(output.toPath()));
        File directory=temp.newFolder();
        ArchiveSupport.ExtractionResult all=ArchiveSupport.extractArchiveDetailed(archive,directory,true,password,null);
        assertTrue(all.failure+": "+all.detail,all.success);
        assertArrayEquals(expected,Files.readAllBytes(new File(directory,name).toPath()));
    }
    private void forward(File archive,boolean solid) throws Exception {
        File directory=temp.newFolder();
        // Exercise the choice made when native libarchive is available, without loading JNI.
        try(ArchiveSupport.ForwardArchiveReader in=SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive,null,directory)) {
            assertNotNull(in);
            assertEquals(solid?"1.bin":"data.bin",in.nextEntry().path);
            if(solid) { assertTrue(in.drainCurrentEntry(-1));assertEquals("2.bin",in.nextEntry().path); }
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[257];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            assertArrayEquals(solid?Arrays.copyOfRange(DATA,DATA.length/2,DATA.length):DATA,out.toByteArray());
            assertNull(in.nextEntry());
        }
    }
    private File sevenZ(boolean aes,boolean encoded,boolean solid,boolean headerOnly,byte[] badPacked) throws Exception {
        Round4Fixtures.Graph data=headerOnly?Round4Fixtures.graph(DATA,0,aes):graph(DATA,aes,badPacked);
        ByteArrayOutputStream header=new ByteArrayOutputStream();
        header.write(1);header.write(4);Round4Fixtures.streams(header,data,0,solid,false);
        header.write(5);Round4Fixtures.number(header,solid?2:1);
        byte[] names=(solid?"1.bin\0"+"2.bin\0":"data.bin\0").getBytes(StandardCharsets.UTF_16LE);
        header.write(17);Round4Fixtures.number(header,names.length+1);header.write(0);header.write(names);
        header.write(0);header.write(0);
        byte[] next=header.toByteArray();ByteArrayOutputStream packed=new ByteArrayOutputStream();packed.write(data.packed);
        if(encoded) {
            Round4Fixtures.Graph metadata=graph(next,aes,null);
            ByteArrayOutputStream descriptor=new ByteArrayOutputStream();descriptor.write(23);
            Round4Fixtures.streams(descriptor,metadata,packed.size(),false,false);
            packed.write(metadata.packed);next=descriptor.toByteArray();
        }
        return Round5Fixtures.wrap(temp.newFolder(),packed.toByteArray(),next);
    }
    private static Round4Fixtures.Graph graph(byte[] raw,boolean aes,byte[] override) throws Exception {
        byte[] packed=override==null?concatenated(raw):override;
        Round4Fixtures.Graph encrypted=Round4Fixtures.graph(packed,0,aes);
        Round4Fixtures.Graph graph=new Round4Fixtures.Graph(raw,encrypted.packed);
        if(aes)graph.add(encrypted.ids.get(0),encrypted.props.get(0),packed.length);
        graph.add(new byte[]{4,2,2},new byte[0],raw.length);return graph;
    }
    static byte[] concatenated(byte[] raw) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<3;i++) {
            // An empty middle stream and different block-size headers are intentional.
            try(OutputStream bz=new BZip2CompressorOutputStream(out,i==0?1:9)) {
                if(i==0)bz.write(raw,0,raw.length/2);
                if(i==2)bz.write(raw,raw.length/2,raw.length-raw.length/2);
            }
        }
        return out.toByteArray();
    }
}
