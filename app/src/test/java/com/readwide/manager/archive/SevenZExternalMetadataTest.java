package com.readwide.manager.archive;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Synthetic format-composition regressions; no real-encoder interoperability claim. */
public class SevenZExternalMetadataTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = {1,2,3,4};

    @Test public void copyExternalNamesRouteAndExtract() throws Exception { success(0, false, "selected.bin"); }
    @Test public void filteredExternalNamesReuseCheckedCoderGraph() throws Exception { success(2, false, "selected.bin"); }
    @Test public void encryptedExternalNamesReuseAesDecoder() throws Exception { success(2, true, "selected.bin"); }
    @Test public void unicodeExternalNamesArePreserved() throws Exception { success(0, false, "folder/한글.bin"); }

    @Test public void invalidExternalIndexFailsBeforeOutput() throws Exception {
        File archive = names("selected.bin\0".getBytes(StandardCharsets.UTF_16LE), 0, false, false, 1);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }
    @Test public void metadataCrcFailurePreservesExistingTarget() throws Exception {
        File archive = names("selected.bin\0".getBytes(StandardCharsets.UTF_16LE), 2, false, true, 0);
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractSingleEntry(
                archive, "selected.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void unterminatedExternalNameIsRejected() throws Exception {
        File archive = names("selected.bin".getBytes(StandardCharsets.UTF_16LE), 0, false, false, 0);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }
    @Test public void encryptedExternalNamesNeedPassword() throws Exception {
        File archive = names("selected.bin\0".getBytes(StandardCharsets.UTF_16LE), 0, true, false, 0);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }

    @Test public void externalCopyFolderGraphExtracts() throws Exception { folderSuccess(0, false); }
    @Test public void externalSwapFolderGraphExtracts() throws Exception { folderSuccess(2, false); }
    @Test public void encryptedExternalFolderGraphExtracts() throws Exception { folderSuccess(2, true); }

    @Test public void prefixedEncryptedExternalFolderDefinitionsExtract() throws Exception {
        File archive = folderArchive(2, true, false, 0, false, true);
        byte[] original = Files.readAllBytes(archive.toPath());
        ByteArrayOutputStream prefixed = new ByteArrayOutputStream();
        prefixed.write(new byte[]{'M', 'Z', 0, 1, 2, 3, 4});
        prefixed.write(original);
        Files.write(archive.toPath(), prefixed.toByteArray());
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(
                archive, "data.bin", output, Round4Fixtures.PASSWORD));
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
    }

    @Test public void invalidExternalFolderIndexIsRejected() throws Exception {
        File archive = folderArchive(0, false, false, 1, false);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }
    @Test public void externalFolderMetadataCrcIsChecked() throws Exception {
        File archive = folderArchive(2, false, true, 0, false);
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }
    @Test public void externalFolderTrailingBytesAreRejected() throws Exception {
        File archive = folderArchive(0, false, false, 0, true);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }

    @Test public void multipleMetadataSubstreamsCopyNamesExtract() throws Exception { splitNamesSuccess(0, false); }
    @Test public void multipleMetadataSubstreamsFilteredNamesExtract() throws Exception { splitNamesSuccess(2, false); }
    @Test public void multipleMetadataSubstreamsEncryptedNamesExtract() throws Exception { splitNamesSuccess(2, true); }

    @Test public void metadataSubstreamsDoNotCreateExtraExternalIndexes() throws Exception {
        File archive = names("selected.bin\0".getBytes(StandardCharsets.UTF_16LE), 0, false, false, 1, true, false);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
    }
    @Test public void badMetadataSubstreamCrcWithValidFolderCrcPreservesOutput() throws Exception {
        File archive = names("selected.bin\0".getBytes(StandardCharsets.UTF_16LE), 2, false, false, 0, true, true);
        File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "selected.bin", output, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }
    @Test public void externalFolderDefinitionsMaySpanMetadataSubstreams() throws Exception {
        File archive = folderArchive(2, true, false, 0, false, true);
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, Round4Fixtures.PASSWORD));
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
    }
    private void splitNamesSuccess(int width, boolean aes) throws Exception {
        File archive = names("selected.bin\0".getBytes(StandardCharsets.UTF_16LE), width, aes, false, 0, true, false);
        char[] password = aes ? Round4Fixtures.PASSWORD : null;
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password));
        assertEquals("selected.bin", SevenZBcj2ArchiveReader.listEntries(archive, password).get(0).path);
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "selected.bin", output, password));
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
    }

    private void folderSuccess(int width, boolean aes) throws Exception {
        File archive = folderArchive(width, aes, false, 0, false);
        char[] password = aes ? Round4Fixtures.PASSWORD : null;
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password));
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, password));
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
    }

    private File folderArchive(int width, boolean aes, boolean bad, int index, boolean trailing) throws Exception {
        return folderArchive(width, aes, bad, index, trailing, false);
    }

    private File folderArchive(int width, boolean aes, boolean bad, int index, boolean trailing,
            boolean multipleSubstreams) throws Exception {
        Round4Fixtures.Graph data = Round4Fixtures.graph(DATA, width, false);
        ByteArrayOutputStream definitions = new ByteArrayOutputStream();
        Round4Fixtures.number(definitions, data.ids.size());
        for (int i = 0; i < data.ids.size(); i++) {
            byte[] id = data.ids.get(i), props = data.props.get(i);
            definitions.write(id.length | 0x20); definitions.write(id);
            Round4Fixtures.number(definitions, props.length); definitions.write(props);
        }
        for (int i = 0; i + 1 < data.ids.size(); i++) {
            Round4Fixtures.number(definitions, i + 1); Round4Fixtures.number(definitions, i);
        }
        if (trailing) definitions.write(0);
        Round4Fixtures.Graph metadata = Round4Fixtures.graph(definitions.toByteArray(), 0, aes);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(3); Round4Fixtures.streams(header, metadata, 0, multipleSubstreams, bad);
        header.write(4); header.write(6); Round4Fixtures.number(header, metadata.packed.length);
        Round4Fixtures.number(header, 1); header.write(9); Round4Fixtures.number(header, data.packed.length);
        header.write(10); header.write(1); Round4Fixtures.le(header, Round4Fixtures.crc(data.packed), 4); header.write(0);
        header.write(7); header.write(11); Round4Fixtures.number(header, 1); header.write(1); header.write(index);
        header.write(12); for (long size : data.sizes) Round4Fixtures.number(header, size);
        header.write(10); header.write(1); Round4Fixtures.le(header, Round4Fixtures.crc(DATA), 4);
        header.write(0); header.write(0);
        header.write(5); Round4Fixtures.number(header, 1);
        byte[] names = "data.bin\0".getBytes(StandardCharsets.UTF_16LE);
        header.write(17); Round4Fixtures.number(header, names.length + 1); header.write(0); header.write(names);
        header.write(0); header.write(0);
        ByteArrayOutputStream packed = new ByteArrayOutputStream(); packed.write(metadata.packed); packed.write(data.packed);
        return Round5Fixtures.wrap(temp.newFolder(), packed.toByteArray(), header.toByteArray());
    }

    private void success(int width, boolean aes, String name) throws Exception {
        File archive = names((name + "\0").getBytes(StandardCharsets.UTF_16LE), width, aes, false, 0);
        char[] password = aes ? Round4Fixtures.PASSWORD : null;
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password));
        assertEquals(name, SevenZBcj2ArchiveReader.listEntries(archive, password).get(0).path);
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, name, output, password));
        assertArrayEquals(DATA, Files.readAllBytes(output.toPath()));
    }

    private File names(byte[] metadata, int width, boolean aes, boolean badCrc, int index) throws Exception {
        return names(metadata, width, aes, badCrc, index, false, false);
    }

    private File names(byte[] metadata, int width, boolean aes, boolean badCrc, int index,
            boolean multipleSubstreams, boolean badSubstreamCrc) throws Exception {
        Round4Fixtures.Graph names = Round4Fixtures.graph(metadata, width, aes);
        Round4Fixtures.Graph data = Round4Fixtures.graph(DATA, 0, false);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        ByteArrayOutputStream metadataInfo = new ByteArrayOutputStream();
        Round4Fixtures.streams(metadataInfo, names, 0, multipleSubstreams, badCrc);
        byte[] info = metadataInfo.toByteArray();
        // Two CRC32 values followed by the SubStreamsInfo and StreamsInfo terminators.
        if (badSubstreamCrc) { assertTrue(multipleSubstreams); info[info.length - 10] ^= 1; }
        header.write(1); header.write(3); header.write(info);
        header.write(4); Round4Fixtures.streams(header, data, names.packed.length, false, false);
        header.write(5); Round4Fixtures.number(header, 1);
        header.write(17); Round4Fixtures.number(header, 2); header.write(1); header.write(index);
        header.write(0); header.write(0);
        ByteArrayOutputStream packed = new ByteArrayOutputStream(); packed.write(names.packed); packed.write(data.packed);
        return Round5Fixtures.wrap(temp.newFolder(), packed.toByteArray(), header.toByteArray());
    }
}
