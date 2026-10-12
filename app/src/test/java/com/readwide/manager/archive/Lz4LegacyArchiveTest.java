package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Container compositions use generated blocks, not legacy-producer fixtures. */
public class Lz4LegacyArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = Round4Fixtures.data(8192);

    @Test public void standaloneLegacyUsesPublicListAndExtraction() throws Exception {
        File archive = Round4Fixtures.write(temp.newFolder(), "data.bin.lz4", Lz4ArchiveInputStreamTest.legacy(DATA));
        assertEquals(1,ArchiveSupport.listEntries(archive,null).size());
        success(archive,"data.bin",DATA,null);
    }

    @Test public void tarStreamCanCrossModernAndLegacyFrames() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("data.bin"); entry.setSize(DATA.length);
            tar.putArchiveEntry(entry); tar.write(DATA); tar.closeArchiveEntry();
        }
        byte[] raw = bytes.toByteArray();
        byte[] packed = Lz4ArchiveInputStreamTest.join(
                Lz4ArchiveInputStreamTest.frame(Arrays.copyOfRange(raw,0,257)),
                Lz4ArchiveInputStreamTest.legacy(Arrays.copyOfRange(raw,257,raw.length)));
        File archive = Round4Fixtures.write(temp.newFolder(),"data.tar.lz4",packed);
        assertEquals(1,ArchiveSupport.listEntries(archive,null).size());
        success(archive,"data.bin",DATA,null);
    }

    @Test public void standaloneMalformedTailRestoresExistingOutput() throws Exception {
        byte[] bytes = Lz4ArchiveInputStreamTest.join(Lz4ArchiveInputStreamTest.legacy(DATA),new byte[]{1});
        File archive = Round4Fixtures.write(temp.newFolder(),"data.bin.lz4",bytes);
        File output = temp.newFile(); Files.write(output.toPath(),new byte[]{42});
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"data.bin",output,null).success);
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }

    @Test public void sevenZLegacyUsesSupplementalExtraction() throws Exception {
        success(sevenZ(false,false,false,null),"data.bin",DATA,null);
    }

    @Test public void sevenZAesSolidAndEncodedHeaderUseTheSameFrameReader() throws Exception {
        success(sevenZ(true,true,true,null),"2.bin",Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),Round4Fixtures.PASSWORD);
    }

    @Test public void sevenZSplitFromLaterVolumeUsesDedicatedForwardReader() throws Exception {
        byte[] bytes = Files.readAllBytes(sevenZ(false,true,true,null).toPath());
        File directory = temp.newFolder(), later = null;
        for (int i=0;i<3;i++) {
            later = new File(directory,"data.7z.00"+(i+1));
            Files.write(later.toPath(),Arrays.copyOfRange(bytes,bytes.length*i/3,bytes.length*(i+1)/3));
        }
        try (ArchiveSupport.ForwardArchiveReader in = SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(later,null,temp.newFolder())) {
            assertNotNull(in); assertEquals("1.bin",in.nextEntry().path);
            assertTrue(in.drainCurrentEntry(-1)); assertEquals("2.bin",in.nextEntry().path);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[257]; int n;
            while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            assertArrayEquals(Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),out.toByteArray());
            assertNull(in.nextEntry());
        }
    }

    @Test public void sevenZValidOutputPrefixCannotHideTruncatedLaterFrame() throws Exception {
        byte[] packed = Lz4ArchiveInputStreamTest.join(Lz4ArchiveInputStreamTest.frame(DATA),
                Lz4ArchiveInputStreamTest.word(0x184c2102L),new byte[]{1});
        File archive = sevenZ(false,false,false,packed), output = temp.newFile();
        Files.write(output.toPath(),new byte[]{42});
        assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"data.bin",output,null).success);
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder(), existing = new File(directory,"data.bin");
        Files.write(existing.toPath(),new byte[]{43});
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive,directory,true,null,null).success);
        assertArrayEquals(new byte[]{43},Files.readAllBytes(existing.toPath()));
    }

    @Test public void sevenZAesWrongPasswordDoesNotReplaceDestination() throws Exception {
        File archive = sevenZ(true,false,false,null), output = temp.newFile();
        Files.write(output.toPath(),new byte[]{42});
        assertThrows(IOException.class,()->SevenZBcj2ArchiveReader.extractSingleEntry(archive,"data.bin",output,"wrong".toCharArray()));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }

    @Test public void modernEmptyStoredBlocksWorkThroughStandaloneExtraction() throws Exception {
        File archive = Round4Fixtures.write(temp.newFolder(),"data.bin.lz4",modernWithEmptyBlocks(DATA));
        assertEquals(1,ArchiveSupport.listEntries(archive,null).size());
        success(archive,"data.bin",DATA,null);
    }

    @Test public void tarHeadersAndPayloadCanCrossEmptyStoredBlocks() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("data.bin"); entry.setSize(DATA.length);
            tar.putArchiveEntry(entry); tar.write(DATA); tar.closeArchiveEntry();
        }
        File archive = Round4Fixtures.write(temp.newFolder(),"data.tar.lz4",modernWithEmptyBlocks(bytes.toByteArray()));
        assertEquals(1,ArchiveSupport.listEntries(archive,null).size());
        success(archive,"data.bin",DATA,null);
    }

    @Test public void sevenZAesSolidAndSplitRoutesReadModernEmptyBlocks() throws Exception {
        File archive = sevenZ(true,true,true,modernWithEmptyBlocks(DATA));
        success(archive,"2.bin",Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),Round4Fixtures.PASSWORD);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File directory = temp.newFolder(), later = null;
        for (int i=0;i<3;i++) {
            later = new File(directory,"data.7z.00"+(i+1));
            Files.write(later.toPath(),Arrays.copyOfRange(bytes,bytes.length*i/3,bytes.length*(i+1)/3));
        }
        try (ArchiveSupport.ForwardArchiveReader in = SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(
                later,Round4Fixtures.PASSWORD,temp.newFolder())) {
            assertNotNull(in); assertEquals("1.bin",in.nextEntry().path);
            assertTrue(in.drainCurrentEntry(-1)); assertEquals("2.bin",in.nextEntry().path);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[257]; int n;
            while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            assertArrayEquals(Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),out.toByteArray());
            assertNull(in.nextEntry());
        }
    }

    @Test public void badEmptyBlockAfterValidOutputCannotPublishStandaloneOrSevenZ() throws Exception {
        byte[] empty = Lz4ArchiveInputStreamTest.modernBlock(new byte[0],true,true);
        empty[empty.length-1]^=1;
        byte[] packed = Lz4ArchiveInputStreamTest.modern(0x74,0x40,(long)DATA.length,DATA,
                Lz4ArchiveInputStreamTest.modernBlock(DATA,true,true),empty);
        File standalone = Round4Fixtures.write(temp.newFolder(),"data.bin.lz4",packed);
        for (File archive : new File[]{standalone,sevenZ(false,false,false,packed)}) {
            File output = temp.newFile(); Files.write(output.toPath(),new byte[]{42});
            assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"data.bin",output,null).success);
            assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
            File directory = temp.newFolder(), existing = new File(directory,"data.bin");
            Files.write(existing.toPath(),new byte[]{43});
            assertFalse(ArchiveSupport.extractArchiveDetailed(archive,directory,true,null,null).success);
            assertArrayEquals(new byte[]{43},Files.readAllBytes(existing.toPath()));
        }
    }

    @Test public void unusedDictionaryIdsWorkThroughStandaloneAndTarExtraction() throws Exception {
        File standalone = Round4Fixtures.write(temp.newFolder(),"data.bin.lz4",withUnusedDictionary(DATA));
        success(standalone,"data.bin",DATA,null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("data.bin"); entry.setSize(DATA.length);
            tar.putArchiveEntry(entry); tar.write(DATA); tar.closeArchiveEntry();
        }
        File archive = Round4Fixtures.write(temp.newFolder(),"data.tar.lz4",withUnusedDictionary(bytes.toByteArray()));
        assertEquals(1,ArchiveSupport.listEntries(archive,null).size());
        success(archive,"data.bin",DATA,null);
    }

    @Test public void unusedDictionaryIdWorksInSevenZAesSolidSplitAndForwardRoutes() throws Exception {
        File archive = sevenZ(true,true,true,withUnusedDictionary(DATA));
        success(archive,"2.bin",Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),Round4Fixtures.PASSWORD);
        byte[] bytes = Files.readAllBytes(archive.toPath());
        File directory = temp.newFolder(), later = null;
        for (int i=0;i<3;i++) {
            later = new File(directory,"data.7z.00"+(i+1));
            Files.write(later.toPath(),Arrays.copyOfRange(bytes,bytes.length*i/3,bytes.length*(i+1)/3));
        }
        try (ArchiveSupport.ForwardArchiveReader in = SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(
                later,Round4Fixtures.PASSWORD,temp.newFolder())) {
            assertNotNull(in); assertEquals("1.bin",in.nextEntry().path);
            assertTrue(in.drainCurrentEntry(-1)); assertEquals("2.bin",in.nextEntry().path);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[257]; int count;
            while ((count=in.read(buffer))!=-1) output.write(buffer,0,count);
            assertArrayEquals(Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),output.toByteArray());
            assertNull(in.nextEntry());
        }
    }

    @Test public void aLaterMissingDictionaryCannotPublishAnOtherwiseValidPrefix() throws Exception {
        byte[] dependent = Lz4ArchiveInputStreamTest.modernWithDictionary(0x70,0x40,null,123L,new byte[0],
                Lz4ArchiveInputStreamTest.modernBlock(new byte[]{4,4,0,0x50,1,2,3,4,5},false,true));
        byte[] packed = Lz4ArchiveInputStreamTest.join(withUnusedDictionary(DATA),dependent);
        File standalone = Round4Fixtures.write(temp.newFolder(),"data.bin.lz4",packed);
        for (File archive : new File[]{standalone,sevenZ(false,false,false,packed)}) {
            File output = temp.newFile(); Files.write(output.toPath(),new byte[]{42});
            assertFalse(ArchiveSupport.extractSingleEntryDetailed(archive,"data.bin",output,null).success);
            assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
            File directory = temp.newFolder(), existing = new File(directory,"data.bin");
            Files.write(existing.toPath(),new byte[]{43});
            assertFalse(ArchiveSupport.extractArchiveDetailed(archive,directory,true,null,null).success);
            assertArrayEquals(new byte[]{43},Files.readAllBytes(existing.toPath()));
        }
    }

    private static byte[] withUnusedDictionary(byte[] data) throws IOException {
        return Lz4ArchiveInputStreamTest.modernWithDictionary(0x74,0x40,(long)data.length,123L,data,
                Lz4ArchiveInputStreamTest.modernBlock(data,true,true));
    }

    private static byte[] modernWithEmptyBlocks(byte[] data) throws IOException {
        byte[] empty = Lz4ArchiveInputStreamTest.modernBlock(new byte[0],true,true);
        int boundary = Math.min(257,data.length);
        return Lz4ArchiveInputStreamTest.modern(0x74,0x40,(long)data.length,data,empty,
                Lz4ArchiveInputStreamTest.modernBlock(Arrays.copyOfRange(data,0,boundary),true,true),empty,
                Lz4ArchiveInputStreamTest.modernBlock(Arrays.copyOfRange(data,boundary,data.length),true,true),empty);
    }

    private void success(File archive,String name,byte[] expected,char[] password) throws Exception {
        File output = temp.newFile();
        ArchiveSupport.ExtractionResult one = ArchiveSupport.extractSingleEntryDetailed(archive,name,output,password);
        assertTrue(one.failure+": "+one.detail,one.success);
        assertArrayEquals(expected,Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder();
        ArchiveSupport.ExtractionResult all = ArchiveSupport.extractArchiveDetailed(archive,directory,true,password,null);
        assertTrue(all.failure+": "+all.detail,all.success);
        assertArrayEquals(expected,Files.readAllBytes(new File(directory,name).toPath()));
    }

    private File sevenZ(boolean aes,boolean encoded,boolean solid,byte[] override) throws Exception {
        Round4Fixtures.Graph data = graph(DATA,aes,override);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); Round4Fixtures.streams(header,data,0,solid,false);
        header.write(5); Round4Fixtures.number(header,solid?2:1);
        byte[] names = (solid?"1.bin\0"+"2.bin\0":"data.bin\0").getBytes(StandardCharsets.UTF_16LE);
        header.write(17); Round4Fixtures.number(header,names.length+1); header.write(0); header.write(names);
        header.write(0); header.write(0);
        byte[] next = header.toByteArray(); ByteArrayOutputStream packed = new ByteArrayOutputStream(); packed.write(data.packed);
        if (encoded) {
            Round4Fixtures.Graph metadata = graph(next,aes,null);
            ByteArrayOutputStream descriptor = new ByteArrayOutputStream(); descriptor.write(23);
            Round4Fixtures.streams(descriptor,metadata,packed.size(),false,false);
            packed.write(metadata.packed); next = descriptor.toByteArray();
        }
        return Round5Fixtures.wrap(temp.newFolder(),packed.toByteArray(),next);
    }
    private static Round4Fixtures.Graph graph(byte[] raw,boolean aes,byte[] override) throws Exception {
        byte[] packed = override==null?Lz4ArchiveInputStreamTest.legacy(raw):override;
        Round4Fixtures.Graph encrypted = Round4Fixtures.graph(packed,0,aes);
        Round4Fixtures.Graph graph = new Round4Fixtures.Graph(raw,encrypted.packed);
        if (aes) graph.add(encrypted.ids.get(0),encrypted.props.get(0),packed.length);
        graph.add(new byte[]{4,(byte)0xf7,0x11,4},new byte[]{1,9,1,0,0},raw.length);
        return graph;
    }
}
