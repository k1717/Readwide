package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic container regression sources, not real ALZip interoperability evidence. */
public class EggContainerCoverageTest {
    private static final int END = 0x08e28222, FILE = 0x0a8590e3, NAME = 0x0a8591ac;
    private static final int COMMENT = 0x04c63672, DUMMY = 0x07463307;
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void commentsAndPaddingBetweenFilesDoNotHideLaterFiles() throws Exception {
        File archive = archive(false, entry("first", new byte[]{1}), extra(COMMENT, 0, new byte[]{65}),
                extra(DUMMY, 1, new byte[]{0,0,0}), entry("second", new byte[]{2,3}));
        assertEquals(2, EggArchiveReader.listEntries(archive, null).size());
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "second", output, null));
        assertArrayEquals(new byte[]{2,3}, Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        assertArrayEquals(new byte[]{2,3}, Files.readAllBytes(new File(directory, "second").toPath()));
    }

    @Test public void canonicalEmptyArchiveListsAndExtractsSuccessfully() throws Exception {
        File archive = archive(false);
        assertTrue(EggArchiveReader.listEntries(archive, null).isEmpty());
        File directory = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        assertEquals(0, directory.list().length);
    }

    @Test public void commentBeforeSolidBlocksDoesNotDetachThePayload() throws Exception {
        byte[] data = {1,2,3};
        byte[] firstHeader = header("first".getBytes(StandardCharsets.UTF_8),0,1,new byte[0]);
        byte[] lastHeader = header("second".getBytes(StandardCharsets.UTF_8),0,2,new byte[0]);
        CRC32 crc = new CRC32(); crc.update(data);
        byte[] block = concat(le(0x02b50c13,4),new byte[]{0,0},le(3,4),le(3,4),le(crc.getValue(),4),le(END,4),data);
        File archive = archive(true, firstHeader, lastHeader, extra(COMMENT,0,new byte[]{65}), block);
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive,"second",output,null));
        assertArrayEquals(new byte[]{2,3},Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive,directory,null));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(new File(directory,"first").toPath()));
        assertArrayEquals(new byte[]{2,3},Files.readAllBytes(new File(directory,"second").toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = EggArchiveReader.openSolidForwardReader(archive,spool)) {
            assertEquals("first",reader.nextEntry().path);
            assertEquals("second",reader.nextEntry().path);
            byte[] buffer = new byte[2]; assertEquals(2,reader.read(buffer));
            assertArrayEquals(new byte[]{2,3},buffer);
            assertNull(reader.nextEntry());
        }
        assertEquals(0,spool.list().length);
    }

    @Test public void unknownInterFileStructureDoesNotPublishAPartialListing() throws Exception {
        rejects(archive(false, entry("first", new byte[]{1}), new byte[]{9,8,7,6}));
    }

    @Test public void truncatedInterFileCommentIsRejected() throws Exception {
        rejects(archive(false, entry("first", new byte[]{1}), concat(le(COMMENT,4), new byte[]{0,100,0,1})));
    }

    @Test public void truncatedTrailingSignatureIsRejected() throws Exception {
        File valid = archive(false, entry("first", new byte[]{1}));
        byte[] bytes = Files.readAllBytes(valid.toPath());
        File truncated = write(Arrays.copyOf(bytes, bytes.length - 2));
        rejects(truncated);
    }

    @Test public void missingFilenameIsRejected() throws Exception {
        rejects(archive(false, concat(le(FILE,4), le(0,4), le(0,8), le(END,4))));
    }

    @Test public void unterminatedFileHeaderIsRejected() throws Exception {
        byte[] header = header("entry".getBytes(StandardCharsets.UTF_8), 0, 0, new byte[0]);
        File valid = archive(false, header);
        byte[] bytes = Files.readAllBytes(valid.toPath());
        rejects(write(Arrays.copyOf(bytes, bytes.length - 8))); // Remove file and archive ENDs.
    }

    @Test public void standardLocaleFlagDecodesCp949WithoutCodepagePrefixInName() throws Exception { locale(8); }
    @Test public void legacyLocaleFlagRemainsCompatible() throws Exception { locale(32); }

    @Test public void shortLocalePayloadIsRejected() throws Exception {
        rejects(archive(false, header(new byte[]{(byte)0xb5,3}, 8, 0, new byte[0])));
    }

    @Test public void encryptedNameIsNotTreatedAsPlaintext() throws Exception {
        rejects(archive(false, header(new byte[]{65}, 4, 0, new byte[0])));
    }

    @Test public void parentIdCannotReferToTheRegularFileItself() throws Exception {
        rejects(archive(false, header(new byte[]{0,0,0,0,65}, 16, 0, new byte[0])));
    }

    @Test public void duplicateNameCannotReplaceEntryIdentity() throws Exception {
        rejects(archive(false, header(new byte[]{65}, 0, 0, extra(NAME, 0, new byte[]{66}))));
    }

    @Test public void duplicateEncryptionCannotReuseStaleHeaderMaterial() throws Exception {
        byte[] encrypted = extra(0x08d1470f, 0, new byte[]{1});
        rejects(archive(false, header(new byte[]{65}, 0, 0, concat(encrypted, encrypted))));
    }

    @Test public void windowsDirectoryWithoutSlashIsADirectory() throws Exception {
        directory(extra(0x2c86950b, 0, concat(new byte[8], new byte[]{(byte)128})));
    }

    @Test public void posixDirectoryWithoutSlashIsADirectory() throws Exception {
        directory(extra(0x1ee922e5, 0, concat(le(0040755,4), new byte[16])));
    }

    @Test public void posixSymlinkIsNotExtractedAsARegularFile() throws Exception {
        rejects(archive(false, header(new byte[]{65}, 0, 0,
                extra(0x1ee922e5, 0, concat(le(0120777,4), new byte[16])))));
    }

    @Test public void malformedWindowsMetadataIsRejected() throws Exception {
        rejects(archive(false, header(new byte[]{65}, 0, 0, extra(0x2c86950b,0,new byte[8]))));
    }

    @Test public void duplicateListingDescribesTheFirstSelectedFile() throws Exception {
        File archive = archive(false, entry("same", new byte[]{1}), entry("same", new byte[]{2,3}));
        List<ArchiveSupport.EntryInfo> listed = EggArchiveReader.listEntries(archive, null);
        assertEquals(1, listed.size()); assertEquals(1, listed.get(0).size);
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive, "same", output, null));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(output.toPath()));
    }

    @Test public void unsafeLaterPathFailsBeforeEarlierFileIsReplaced() throws Exception {
        preflight(false, "../outside");
    }

    @Test public void implicitDirectoryCollisionFailsBeforeEarlierFileIsReplaced() throws Exception {
        preflight(false, "first/child");
    }

    @Test public void solidUnsafePathFailsBeforeAnyOutput() throws Exception { preflight(true, "../outside"); }

    private void preflight(boolean solid, String later) throws Exception {
        File archive = archive(solid, entry("first", new byte[]{1}), entry(later, new byte[]{2}));
        File directory = temp.newFolder(), existing = new File(directory, "first");
        Files.write(existing.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, directory.list().length);
    }

    private void directory(byte[] metadata) throws Exception {
        File archive = archive(false, header("folder".getBytes(StandardCharsets.UTF_8), 0, 0, metadata),
                entry("folder/page", new byte[]{1}));
        assertTrue(EggArchiveReader.listEntries(archive, null).get(0).directory);
        File directory = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null));
        assertTrue(new File(directory, "folder").isDirectory());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(directory, "folder/page").toPath()));
    }

    private void locale(int flags) throws Exception {
        String name = "한글.txt";
        byte[] encoded = concat(le(949,2), name.getBytes(Charset.forName("MS949")));
        File archive = archive(false, header(encoded, flags, 0, new byte[0]));
        assertEquals(name, EggArchiveReader.listEntries(archive, null).get(0).path);
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive, name, output, null));
        assertEquals(0, output.length());
    }

    private void rejects(File archive) {
        assertThrows(IOException.class, () -> EggArchiveReader.listEntries(archive, null));
    }

    private File archive(boolean solid, byte[]... records) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(le(0x41474745,4)); bytes.write(le(0x0100,2)); bytes.write(le(1,4)); bytes.write(le(0,4));
        if (solid) bytes.write(extra(0x24e5a060,0,new byte[0]));
        bytes.write(le(END,4));
        for (byte[] record : records) bytes.write(record);
        bytes.write(le(END,4));
        return write(bytes.toByteArray());
    }

    private File write(byte[] bytes) throws Exception {
        File archive = new File(temp.newFolder(), "fixture.egg");
        Files.write(archive.toPath(), bytes); return archive;
    }

    private static byte[] entry(String name, byte[] data) throws IOException {
        CRC32 crc = new CRC32(); crc.update(data);
        return concat(header(name.getBytes(StandardCharsets.UTF_8),0,data.length,new byte[0]),
                le(0x02b50c13,4), new byte[]{0,0}, le(data.length,4), le(data.length,4),
                le(crc.getValue(),4), le(END,4), data);
    }

    private static byte[] header(byte[] name, int flags, long size, byte[] metadata) throws IOException {
        return concat(le(FILE,4), le(0,4), le(size,8), extra(NAME,flags,name), metadata, le(END,4));
    }

    private static byte[] extra(int signature, int flags, byte[] data) throws IOException {
        return concat(le(signature,4), new byte[]{(byte)flags}, le(data.length,(flags & 1) != 0 ? 4 : 2), data);
    }

    private static byte[] le(long value, int count) {
        byte[] bytes = new byte[count];
        for (int i=0; i<count; i++) bytes[i] = (byte)(value >>> (8*i));
        return bytes;
    }

    private static byte[] concat(byte[]... pieces) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] piece : pieces) bytes.write(piece);
        return bytes.toByteArray();
    }
}
