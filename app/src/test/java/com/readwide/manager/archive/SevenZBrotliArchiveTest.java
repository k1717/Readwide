package com.readwide.manager.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic folder/AES/encoded-header compositions; real producer fixtures remain pending. */
public class SevenZBrotliArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = "Brotli archive page one, page two".getBytes(StandardCharsets.UTF_8);
    @Test public void rawPlainArchiveExtracts() throws Exception { success(false, false, false, false); }
    @Test public void framedPlainArchiveExtracts() throws Exception { success(false, false, false, true); }
    @Test public void aesFramedArchiveExtracts() throws Exception { success(true, false, false, true); }
    @Test public void aesFramedEncodedHeaderExtracts() throws Exception { success(true, true, false, true); }
    @Test public void solidFramedFolderExtractsSecondFile() throws Exception { success(false, false, true, true); }
    @Test public void largeWindowRawAndFramedArchivesExtract() throws Exception {
        success(false, false, false, false, 25);
        success(false, false, false, true, 30);
    }
    @Test public void largeWindowAesSolidAndEncodedHeadersExtract() throws Exception {
        success(true, true, true, false, 25);
        success(true, true, true, true, 30);
    }
    @Test public void largeWindowFolderCrcFailurePreservesTarget() throws Exception {
        File archive = archive(true, true, true, true, true, 25), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractSingleEntry(
                archive, "2.bin", output, Round4Fixtures.PASSWORD));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void largeWindowForwardReaderUsesVerifiedSolidSuffix() throws Exception {
        File archive = archive(true, true, true, true, false, 25);
        try (ArchiveSupport.ForwardArchiveReader in = SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(
                archive, Round4Fixtures.PASSWORD, temp.newFolder())) {
            assertNotNull(in); assertEquals("1.bin", in.nextEntry().path);
            assertTrue(in.drainCurrentEntry(Long.MAX_VALUE));
            assertEquals("2.bin", in.nextEntry().path);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[7]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            assertArrayEquals(Arrays.copyOfRange(DATA, DATA.length / 2, DATA.length), out.toByteArray());
            assertNull(in.nextEntry());
        }
    }
    @Test public void nativePresentSelectionRetainsBrotliForwardDecoder() throws Exception {
        File archive=archive(false,true,true,true,false);
        try(ArchiveSupport.ForwardArchiveReader in=SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive,null,temp.newFolder())) {
            assertNotNull(in);assertEquals("1.bin",in.nextEntry().path);
            assertTrue(in.drainCurrentEntry(Long.MAX_VALUE));assertEquals("2.bin",in.nextEntry().path);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[7];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            assertArrayEquals(Arrays.copyOfRange(DATA,DATA.length/2,DATA.length),out.toByteArray());
            assertNull(in.nextEntry());
        }
    }
    @Test public void folderCrcFailurePreservesTarget() throws Exception {
        File archive = archive(false, false, false, true, true), output = temp.newFile();
        Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    private void success(boolean aes, boolean encoded, boolean solid, boolean framed) throws Exception {
        success(aes, encoded, solid, framed, 0);
    }
    private void success(boolean aes, boolean encoded, boolean solid, boolean framed, int windowBits) throws Exception {
        File archive = archive(aes, encoded, solid, framed, false, windowBits);
        char[] password = aes ? Round4Fixtures.PASSWORD : null;
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password));
        String name = solid ? "2.bin" : "data.bin";
        byte[] expected = solid ? Arrays.copyOfRange(DATA, DATA.length / 2, DATA.length) : DATA;
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, name, output, password));
        assertArrayEquals(expected, Files.readAllBytes(output.toPath()));
        File target = temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, target, password, null, null));
        assertArrayEquals(expected, Files.readAllBytes(new File(target, name).toPath()));
    }
    private File archive(boolean aes, boolean encoded, boolean solid, boolean framed, boolean badCrc) throws Exception {
        return archive(aes, encoded, solid, framed, badCrc, 0);
    }
    private File archive(boolean aes, boolean encoded, boolean solid, boolean framed, boolean badCrc, int windowBits) throws Exception {
        Round4Fixtures.Graph data = graph(DATA, aes, framed, windowBits);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); Round4Fixtures.streams(header, data, 0, solid, badCrc);
        header.write(5); Round4Fixtures.number(header, solid ? 2 : 1);
        byte[] names = (solid ? "1.bin\0" + "2.bin\0" : "data.bin\0").getBytes(StandardCharsets.UTF_16LE);
        header.write(17); Round4Fixtures.number(header, names.length + 1); header.write(0); header.write(names);
        header.write(0); header.write(0);
        byte[] next = header.toByteArray(); ByteArrayOutputStream packed = new ByteArrayOutputStream(); packed.write(data.packed);
        if (encoded) {
            Round4Fixtures.Graph metadata = graph(next, aes, framed, windowBits);
            ByteArrayOutputStream descriptor = new ByteArrayOutputStream(); descriptor.write(23);
            Round4Fixtures.streams(descriptor, metadata, packed.size(), false, false);
            packed.write(metadata.packed); next = descriptor.toByteArray();
        }
        return Round5Fixtures.wrap(temp.newFolder(), packed.toByteArray(), next);
    }
    private static Round4Fixtures.Graph graph(byte[] raw, boolean aes, boolean framed, int windowBits) throws Exception {
        byte[] packed = framed ? SevenZBrotliInputStreamTest.framesWithWindow(windowBits,
                Arrays.copyOfRange(raw, 0, raw.length / 2), Arrays.copyOfRange(raw, raw.length / 2, raw.length))
                : windowBits == 0 ? SevenZBrotliInputStreamTest.raw(raw) : SevenZBrotliInputStreamTest.largeRaw(raw, windowBits);
        Round4Fixtures.Graph encrypted = Round4Fixtures.graph(packed, 0, aes);
        Round4Fixtures.Graph graph = new Round4Fixtures.Graph(raw, encrypted.packed);
        if (aes) graph.add(encrypted.ids.get(0), encrypted.props.get(0), packed.length);
        graph.add(new byte[]{4,(byte)0xf7,0x11,2}, new byte[]{1,0,5}, raw.length);
        return graph;
    }
}
