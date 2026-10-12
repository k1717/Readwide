package com.readwide.manager.archive;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;

/** Synthetic 7z/LZ4 composition; no real-encoder interoperability claim. */
public class SevenZLz4ArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = data();

    @Test public void plainLz4ArchiveExtractsWithoutNativeCodec() throws Exception { success(false, false, false, false); }
    @Test public void aesLz4ArchiveExtracts() throws Exception { success(true, false, false, false); }
    @Test public void aesLz4EncodedHeaderExtracts() throws Exception { success(true, true, false, false); }
    @Test public void solidLz4FolderExtractsSelectedSecondFile() throws Exception { success(false, false, true, false); }
    @Test public void concatenatedLz4FramesAndWorkerHintsDecodeCompletely() throws Exception { success(false, false, false, true); }
    @Test public void lz4FolderCrcFailurePreservesExistingTarget() throws Exception {
        File archive = archive(false, false, false, false, true, false);
        preserveOnFailure(archive, null);
    }
    @Test public void malformedLz4FrameWithValidPackedCrcPreservesTarget() throws Exception {
        File archive = archive(false, false, false, false, false, true);
        preserveOnFailure(archive, null);
    }
    @Test public void aesLz4NeedsPasswordBeforeOutput() throws Exception {
        File archive = archive(true, false, false, false, false, false);
        preserveOnFailure(archive, null);
    }

    private void preserveOnFailure(File archive, char[] password) throws Exception {
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, password));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    private void success(boolean aes, boolean encoded, boolean solid, boolean multiFrame) throws Exception {
        File archive = archive(aes, encoded, solid, multiFrame, false, false);
        char[] password = aes ? Round4Fixtures.PASSWORD : null;
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password));
        assertEquals(solid ? 2 : 1, SevenZBcj2ArchiveReader.listEntries(archive, password).size());
        String name = solid ? "2.bin" : "data.bin";
        byte[] expected = solid ? Arrays.copyOfRange(DATA, DATA.length / 2, DATA.length) : DATA;
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, name, output, password));
        assertArrayEquals(expected, Files.readAllBytes(output.toPath()));
        File target = temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, target, password, null, null));
        assertArrayEquals(expected, Files.readAllBytes(new File(target, name).toPath()));
    }
    private File archive(boolean aes, boolean encoded, boolean solid, boolean multiFrame,
            boolean badCrc, boolean badFrame) throws Exception {
        Round4Fixtures.Graph data = graph(DATA, aes, multiFrame);
        if (badFrame) data.packed[0] ^= 1;
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); Round4Fixtures.streams(header, data, 0, solid, badCrc);
        header.write(5); Round4Fixtures.number(header, solid ? 2 : 1);
        byte[] names = (solid ? "1.bin\0" + "2.bin\0" : "data.bin\0").getBytes(StandardCharsets.UTF_16LE);
        header.write(17); Round4Fixtures.number(header, names.length + 1); header.write(0); header.write(names);
        header.write(0); header.write(0);
        byte[] next = header.toByteArray(); ByteArrayOutputStream packed = new ByteArrayOutputStream(); packed.write(data.packed);
        if (encoded) {
            Round4Fixtures.Graph metadata = graph(next, aes, multiFrame);
            ByteArrayOutputStream descriptor = new ByteArrayOutputStream(); descriptor.write(23);
            Round4Fixtures.streams(descriptor, metadata, packed.size(), false, false);
            packed.write(metadata.packed); next = descriptor.toByteArray();
        }
        return Round5Fixtures.wrap(temp.newFolder(), packed.toByteArray(), next);
    }
    private static Round4Fixtures.Graph graph(byte[] raw, boolean aes, boolean multiFrame) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int count = multiFrame ? 2 : 1;
        for (int i = 0; i < count; i++) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (OutputStream frame = new FramedLZ4CompressorOutputStream(compressed)) {
                int from = raw.length * i / count, to = raw.length * (i + 1) / count;
                frame.write(raw, from, to - from);
            }
            byte[] frame = compressed.toByteArray();
            if (multiFrame) {
                Round4Fixtures.le(output, 0x184d2a50, 4); Round4Fixtures.le(output, 4, 4);
                Round4Fixtures.le(output, frame.length, 4);
            }
            output.write(frame);
        }
        byte[] packed = output.toByteArray();
        Round4Fixtures.Graph encrypted = Round4Fixtures.graph(packed, 0, aes);
        Round4Fixtures.Graph graph = new Round4Fixtures.Graph(raw, encrypted.packed);
        if (aes) graph.add(encrypted.ids.get(0), encrypted.props.get(0), packed.length);
        graph.add(new byte[]{4,(byte)0xf7,0x11,4}, new byte[]{1,9,1,0,0}, raw.length);
        return graph;
    }
    private static byte[] data() {
        byte[] data = new byte[8192];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i % 47);
        return data;
    }
}
