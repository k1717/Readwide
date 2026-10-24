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

    @Test public void emptyArchiveHonorsPreCancelledToken() throws Exception {
        emptyArchiveCancellation(false);
    }

    @Test public void emptyArchiveHonorsCancellationFromTotalCallback() throws Exception {
        emptyArchiveCancellation(true);
    }

    private void emptyArchiveCancellation(boolean duringTotal) throws Exception {
        for (boolean solid : new boolean[]{false, true}) {
            File archive = archive(solid), directory = temp.newFolder();
            File existing = new File(directory, "keep.bin");
            Files.write(existing.toPath(), new byte[]{42});
            com.readwide.manager.util.FileOperationProgress progress =
                    new com.readwide.manager.util.FileOperationProgress("empty", null);
            if (duringTotal) {
                progress.setListener(s -> { if (!s.cancelled && s.totalBytes == 0) progress.cancel(); });
            } else progress.cancel();
            assertThrows(java.io.InterruptedIOException.class, () ->
                    EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null, progress, null));
            assertTrue(progress.isCancelled());
            assertFalse(Thread.currentThread().isInterrupted());
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
            assertEquals(1, directory.list().length);
        }
    }

    @Test public void emptyArchiveWithActiveTokenStillSucceeds() throws Exception {
        for (boolean solid : new boolean[]{false, true}) {
            File archive = archive(solid), directory = temp.newFolder();
            com.readwide.manager.util.FileOperationProgress progress =
                    new com.readwide.manager.util.FileOperationProgress("empty", null);
            assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive, directory, null, progress, null));
            assertFalse(progress.isCancelled());
            assertEquals(0, directory.list().length);
        }
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

    @Test public void encryptedNameFlagsStayUnsupportedEvenWithAPassword() throws Exception {
        // Synthetic flag admission, not an invented filename-encryption scheme.
        for (int flags : new int[]{4,5,6,7,12,13,20,21,28,29,36,37}) {
            byte[] locale = (flags & 0x28) != 0 ? le(949,2) : new byte[0];
            byte[] parent = (flags & 0x10) != 0 ? le(0,4) : new byte[0];
            File archive = archive(false, header(concat(locale,parent,new byte[]{65}),flags,0,new byte[0]));
            for (char[] password : new char[][]{null, "provided".toCharArray()}) {
                assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                        () -> EggArchiveReader.listEntries(archive,password));
            }
        }
    }

    @Test public void encryptedLaterNamePreventsPartialOutputAndSpoolCreation() throws Exception {
        for (boolean solid : new boolean[]{false,true}) {
            File archive = archive(solid, entry("first",new byte[]{1}),
                    extra(0xffff0000,0,new byte[]{0,0,0}),
                    header(new byte[]{65},4,0,new byte[0]));
            assertUnsupportedNamePreservesOutputs(archive);
            File alias = new File(temp.newFolder(),"names.alz");
            Files.copy(archive.toPath(),alias.toPath());
            // Family detection must not bypass EGG admission for a .alz suffix.
            assertUnsupportedNamePreservesOutputs(alias);
        }
    }

    @Test public void encryptedNameInLaterVolumeCannotPublishEarlierEntry() throws Exception {
        File[] volumes = splitAtRecord(entry("first",new byte[]{1}),
                header(new byte[]{65},5,0,new byte[0]),new byte[0],new byte[0]);
        assertEquals(volumes[0].getCanonicalFile(),
                EggVolumeResolver.resolveFirstVolume(volumes[1]).getCanonicalFile());
        assertUnsupportedNamePreservesOutputs(volumes[0]);
    }

    @Test public void opaqueGlobalExtrasRetainBoundedSkipCompatibility() throws Exception {
        // The specification assigns this signature but not a usable cipher layout.
        // These opaque bytes do not assert any global-encryption implementation.
        byte[] opaque = extra(0x08d144a8,1,new byte[]{4,3,2,1});
        File[] volumes = splitAtRecord(entry("first",new byte[]{1}),
                entry("second",new byte[]{2,3}),opaque,opaque);
        assertEquals(2,EggArchiveReader.listEntries(volumes[0],null).size());
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(volumes[0],"second",output,null));
        assertArrayEquals(new byte[]{2,3},Files.readAllBytes(output.toPath()));

        File[] flagged = splitAtRecord(entry("first",new byte[]{1}),
                header(new byte[]{65},4,0,new byte[0]),opaque,opaque);
        assertUnsupportedNamePreservesOutputs(flagged[0]);
    }

    @Test public void opaqueContinuationExtraCannotExceedPhysicalVolume() throws Exception {
        byte[] truncated = concat(le(0x08d144a8,4),new byte[]{1},le(0xffffffffL,4));
        File[] volumes = splitAtRecord(entry("first",new byte[]{1}),
                entry("second",new byte[]{2}),new byte[0],truncated);
        rejects(volumes[0]);
        File output = temp.newFile();
        Files.write(output.toPath(),new byte[]{42});
        assertThrows(IOException.class,
                () -> EggArchiveReader.extractSingleEntry(volumes[0],"first",output,null));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }

    @Test public void rejectedNameDoesNotCacheAnEarlierPartialIndex() throws Exception {
        File archive = archive(false,entry("first",new byte[]{1}),
                header(new byte[]{65},4,0,new byte[0]));
        byte[] rejected = Files.readAllBytes(archive.toPath());
        long modified = archive.lastModified();
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> EggArchiveReader.listEntries(archive,null));
        // Change only the second NAME flag, preserving length and timestamp.
        // A failed parse must not have published a prefix index under this stamp.
        int secondName = 18 + entry("first",new byte[]{1}).length + 16;
        assertArrayEquals(le(NAME,4),Arrays.copyOfRange(rejected,secondName,secondName+4));
        rejected[secondName+4] = 0;
        Files.write(archive.toPath(),rejected);
        assertTrue(archive.setLastModified(modified));
        assertEquals(2,EggArchiveReader.listEntries(archive,null).size());
        assertEquals("A",EggArchiveReader.listEntries(archive,null).get(1).path);
    }

    private void assertUnsupportedNamePreservesOutputs(File archive) throws Exception {
        char[] password = "provided".toCharArray();
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> AlzipArchiveReader.listEntries(archive,password));
        File directory = temp.newFolder(), existing = new File(directory,"first");
        Files.write(existing.toPath(),new byte[]{42});
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> AlzipArchiveReader.extractSingleEntry(archive,"first",existing,password));
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> AlzipArchiveReader.extractArchiveIntoDirectory(archive,directory,password,null,null));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(existing.toPath()));
        assertEquals(1,directory.list().length);
        File absent = new File(directory,"absent");
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> EggArchiveReader.extractArchiveIntoDirectory(archive,absent,password));
        assertFalse(absent.exists());
        File spool = new File(directory,"spool");
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class, () -> {
            try (ArchiveSupport.ForwardArchiveReader reader =
                    EggArchiveReader.openSolidForwardReader(archive,spool,password)) {
                fail("Encrypted names must be rejected before a forward session is returned");
            }
        });
        assertFalse(spool.exists());
    }

    private File[] splitAtRecord(byte[] before, byte[] after, byte[] firstExtra,
                                 byte[] secondExtra) throws Exception {
        File directory = temp.newFolder();
        File first = new File(directory,"names.vol1.egg"), second = new File(directory,"names.vol2.egg");
        // Entire records stay on one volume, as required by the producer spec.
        Files.write(first.toPath(),concat(volumePrefix(1,0,2,firstExtra),before));
        Files.write(second.toPath(),concat(volumePrefix(2,1,0,secondExtra),after,le(END,4)));
        return new File[]{first,second};
    }

    private static byte[] volumePrefix(int id, int prev, int next, byte[] metadata) throws IOException {
        return concat(le(0x41474745,4),le(0x0100,2),le(id,4),le(0,4),
                extra(0x24f5a262,0,concat(le(prev,4),le(next,4))),metadata,le(END,4));
    }

    @Test public void skipRecordsDoNotPublishTheirContentsOrHideLaterFiles() throws Exception {
        for (int flags : new int[]{0,1}) {
            byte[] skip = extra(0xffff0000,flags,header(new byte[]{65},4,0,new byte[0]));
            File archive = archive(false,skip,entry("first",new byte[]{1}),skip,
                    entry("second",new byte[]{2,3}),skip);
            List<ArchiveSupport.EntryInfo> entries = EggArchiveReader.listEntries(archive,null);
            assertEquals(2,entries.size());
            assertEquals("first",entries.get(0).path);
            assertEquals("second",entries.get(1).path);
            File output = temp.newFile();
            assertTrue(EggArchiveReader.extractSingleEntry(archive,"second",output,null));
            assertArrayEquals(new byte[]{2,3},Files.readAllBytes(output.toPath()));
            File directory = temp.newFolder();
            assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive,directory,null));
            assertArrayEquals(new byte[]{1},Files.readAllBytes(new File(directory,"first").toPath()));
            assertArrayEquals(new byte[]{2,3},Files.readAllBytes(new File(directory,"second").toPath()));
            assertEquals(2,directory.list().length);
        }
    }

    @Test public void skipBeforeSolidBlockPreservesEntryOrderAndSpoolCleanup() throws Exception {
        byte[] data = {1,2,3};
        CRC32 crc = new CRC32(); crc.update(data);
        byte[] block = concat(le(0x02b50c13,4),new byte[]{0,0},le(3,4),le(3,4),
                le(crc.getValue(),4),le(END,4),data);
        File archive = archive(true,header(new byte[]{65},0,1,new byte[0]),
                header(new byte[]{66},0,2,new byte[0]),extra(0xffff0000,0,new byte[0]),block);
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive,"B",output,null));
        assertArrayEquals(new byte[]{2,3},Files.readAllBytes(output.toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = EggArchiveReader.openSolidForwardReader(archive,spool)) {
            assertEquals("A",reader.nextEntry().path);
            assertEquals("B",reader.nextEntry().path);
            byte[] buffer = new byte[2];
            assertEquals(2,reader.read(buffer));
            assertArrayEquals(new byte[]{2,3},buffer);
            assertNull(reader.nextEntry());
        }
        assertEquals(0,spool.list().length);
    }

    @Test public void skipAtPhysicalBoundaryRetainsTheNextVolumeEntry() throws Exception {
        byte[] skip = extra(0xffff0000,0,new byte[8]);
        File[] volumes = splitAtRecord(concat(entry("first",new byte[]{1}),skip),
                concat(skip,entry("second",new byte[]{2,3})),skip,skip);
        assertEquals(2,EggArchiveReader.listEntries(volumes[0],null).size());
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(volumes[0],"second",output,null));
        assertArrayEquals(new byte[]{2,3},Files.readAllBytes(output.toPath()));
    }

    @Test public void truncatedSkipCannotPublishOrReplaceEarlierOutput() throws Exception {
        for (int flags : new int[]{0,1}) {
            byte[] truncated = concat(le(0xffff0000,4),new byte[]{(byte)flags},
                    le(0xffffffffL,flags == 0 ? 2 : 4));
            File archive = archive(false,entry("first",new byte[]{1}),truncated);
            rejects(archive);
            File directory = temp.newFolder(), existing = new File(directory,"first");
            Files.write(existing.toPath(),new byte[]{42});
            assertThrows(IOException.class,
                    () -> EggArchiveReader.extractArchiveIntoDirectory(archive,directory,null));
            assertArrayEquals(new byte[]{42},Files.readAllBytes(existing.toPath()));
            assertEquals(1,directory.list().length);
        }
    }

    @Test public void parentIdCannotReferToTheRegularFileItself() throws Exception {
        rejects(archive(false, header(new byte[]{0,0,0,0,65}, 16, 0, new byte[0])));
    }

    @Test public void framedBlockExtrasKeepPayloadAndLaterEntries() throws Exception {
        byte[] data = new byte[64]; Arrays.fill(data,(byte)90);
        for (int flags : new int[]{0,1}) {
            // Metadata contents include signatures but must remain opaque.
            byte[] metadata = concat(extra(COMMENT,flags,new byte[]{65}),
                    extra(DUMMY,1,new byte[0]), extra(0xffff0000,flags,
                    concat(le(END,4),le(FILE,4),le(NAME,4))));
            File archive = archive(false,header(new byte[]{65},0,data.length,new byte[0]),
                    storedBlock(data,metadata,true),entry("later",new byte[]{8,9}));
            assertEquals(2,EggArchiveReader.listEntries(archive,null).size());
            File output = temp.newFile();
            assertTrue(EggArchiveReader.extractSingleEntry(archive,"A",output,null));
            assertArrayEquals(data,Files.readAllBytes(output.toPath()));
            File directory = temp.newFolder();
            assertTrue(EggArchiveReader.extractArchiveIntoDirectory(archive,directory,null));
            assertArrayEquals(data,Files.readAllBytes(new File(directory,"A").toPath()));
            assertArrayEquals(new byte[]{8,9},Files.readAllBytes(new File(directory,"later").toPath()));
        }
    }

    @Test public void legacyBlockExtraLookalikesRetainEveryAcceptedSuccessor() throws Exception {
        byte[] data = concat(extra(0xffff0000,1,new byte[]{9,8}),le(END,4),new byte[32]);
        byte[] tail = {2,3,4};
        byte[][] successors = {new byte[0],le(END,4),entry("later",tail),
                storedBlock(tail,new byte[0],true),extra(COMMENT,0,new byte[0]),
                extra(DUMMY,1,new byte[0]),extra(0xffff0000,0,new byte[0])};
        for (int i=0; i<successors.length; i++) {
            byte[] expected = i == 3 ? concat(data,tail) : data;
            File archive = archive(false,header(new byte[]{65},0,expected.length,new byte[0]),
                    storedBlock(data,new byte[0],false),successors[i]);
            if (i == 0) { // Historical exact EOF without the archive END.
                byte[] raw = Files.readAllBytes(archive.toPath());
                Files.write(archive.toPath(),Arrays.copyOf(raw,raw.length-4));
            }
            File output = temp.newFile();
            assertTrue(EggArchiveReader.extractSingleEntry(archive,"A",output,null));
            assertArrayEquals(expected,Files.readAllBytes(output.toPath()));
        }
    }

    @Test public void immediateBlockEndKeepsSkipShapedPayloadLiteral() throws Exception {
        byte[] data = concat(extra(0xffff0000,0,new byte[]{1}),le(END,4),new byte[32]);
        File archive = archive(false,entry("A",data));
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(archive,"A",output,null));
        assertArrayEquals(data,Files.readAllBytes(output.toPath()));
    }

    @Test public void framedBlockExtrasKeepSplitSolidOwnershipAndSpoolCleanup() throws Exception {
        byte[] data = new byte[64]; Arrays.fill(data,(byte)90);
        byte[] block = storedBlock(data,extra(0xffff0000,1,new byte[]{9,8,7,6}),true);
        int cut = block.length-27; // Header/extras stay whole; only payload crosses volumes.
        byte[] headers = concat(header(new byte[]{65},0,21,new byte[0]),
                header(new byte[]{66},0,43,new byte[0]));
        File[] volumes = splitAtRecord(concat(headers,Arrays.copyOf(block,cut)),
                Arrays.copyOfRange(block,cut,block.length),extra(0x24e5a060,0,new byte[0]),new byte[0]);
        File output = temp.newFile();
        assertTrue(EggArchiveReader.extractSingleEntry(volumes[1],"B",output,null));
        assertArrayEquals(Arrays.copyOfRange(data,21,64),Files.readAllBytes(output.toPath()));
        File directory = temp.newFolder();
        assertTrue(EggArchiveReader.extractArchiveIntoDirectory(volumes[0],directory,null));
        assertArrayEquals(Arrays.copyOf(data,21),Files.readAllBytes(new File(directory,"A").toPath()));
        assertArrayEquals(Arrays.copyOfRange(data,21,64),Files.readAllBytes(new File(directory,"B").toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader = EggArchiveReader.openSolidForwardReader(volumes[0],spool)) {
            assertEquals("A",reader.nextEntry().path);
            assertEquals("B",reader.nextEntry().path); // Drain the first slice.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[11]; int count;
            while ((count=reader.read(buffer)) != -1) bytes.write(buffer,0,count);
            assertArrayEquals(Arrays.copyOfRange(data,21,64),bytes.toByteArray());
            assertNull(reader.nextEntry());
        }
        assertEquals(0,spool.list().length);
    }

    @Test public void malformedBlockExtrasCannotPublishOrReplaceEarlierFiles() throws Exception {
        byte[] data = new byte[64]; Arrays.fill(data,(byte)90);
        byte[][] malformed = {
                concat(le(0xffff0000,4),new byte[]{0},le(65535,2)),
                concat(le(0xffff0000,4),new byte[]{1},le(0xffffffffL,4)),
                concat(extra(COMMENT,0,new byte[0]),extra(0x12345678,0,new byte[0])),
                extra(0xffff0000,0,new byte[0])};
        for (int i=0; i<malformed.length; i++) {
            File archive = archive(false,entry("first",new byte[]{1}),
                    header(new byte[]{65},0,data.length,new byte[0]),
                    storedBlock(data,malformed[i],i != 3));
            rejects(archive);
            File directory = temp.newFolder(), existing = new File(directory,"first");
            Files.write(existing.toPath(),new byte[]{42});
            assertThrows(IOException.class, () ->
                    EggArchiveReader.extractArchiveIntoDirectory(archive,directory,null));
            assertArrayEquals(new byte[]{42},Files.readAllBytes(existing.toPath()));
            assertEquals(1,directory.list().length);
            File absent = new File(temp.newFolder(),"absent");
            assertThrows(IOException.class, () ->
                    EggArchiveReader.extractSingleEntry(archive,"A",absent,null));
            assertFalse(absent.exists());
        }
    }

    @Test public void framedBlockExtrasDoNotRelaxPayloadCrc() throws Exception {
        byte[] data = new byte[64]; Arrays.fill(data,(byte)90);
        byte[] block = storedBlock(data,extra(DUMMY,0,new byte[]{7}),true);
        block[block.length-1] ^= 1;
        File archive = archive(false,header(new byte[]{65},0,data.length,new byte[0]),block);
        assertEquals(1,EggArchiveReader.listEntries(archive,null).size());
        File output = temp.newFile(); Files.write(output.toPath(),new byte[]{42});
        assertThrows(IOException.class, () -> EggArchiveReader.extractSingleEntry(archive,"A",output,null));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(output.toPath()));
    }

    @Test public void truncatedWideSkipPrefixCannotHideAtEof() throws Exception {
        for (int sizeBytes=0; sizeBytes<4; sizeBytes++) {
            File archive = archive(false,entry("A",new byte[]{1}));
            byte[] raw = Files.readAllBytes(archive.toPath());
            Files.write(archive.toPath(),concat(Arrays.copyOf(raw,raw.length-4),
                    le(0xffff0000,4),new byte[]{1},new byte[sizeBytes]));
            rejects(archive);
        }
    }

    private static byte[] storedBlock(byte[] data, byte[] metadata, boolean end) throws IOException {
        CRC32 crc = new CRC32(); crc.update(data);
        return concat(le(0x02b50c13,4),new byte[]{0,0},le(data.length,4),le(data.length,4),
                le(crc.getValue(),4),metadata,end ? le(END,4) : new byte[0],data);
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
