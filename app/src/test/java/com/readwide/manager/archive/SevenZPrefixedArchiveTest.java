package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Synthetic prefixed containers; not a real executable-stub interoperability claim. */
public class SevenZPrefixedArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = {1, 2, 3, 4, 5, 6, 7};

    @Test public void prefixedCopyListsAndExtractsThroughAllDedicatedRoutes() throws Exception {
        verifyAllRoutes(prefixed(archiveBytes(0, false, false), 97), null);
    }

    @Test public void ordinaryCopyKeepsItsExistingCapabilityRouting() throws Exception {
        File archive = write(archiveBytes(0, false, false));
        assertFalse(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        assertNull(SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
        try (ArchiveSupport.ForwardArchiveReader ignored =
                     SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive, null, temp.newFolder())) {
            assertNull(ignored);
        }
    }

    @Test public void prefixedCopyUsesDedicatedNativePresentForwardRoute() throws Exception {
        File archive = prefixed(archiveBytes(0, false, false), 53);
        try (ArchiveSupport.ForwardArchiveReader reader =
                     SevenZBcj2ArchiveReader.openNativeUnsupportedForwardReader(archive, null, temp.newFolder())) {
            assertNotNull(reader);
            verifyForward(reader);
        }
    }

    @Test public void prefixedAesSwapWithEncodedHeaderExtracts() throws Exception {
        verifyAllRoutes(prefixed(archiveBytes(2, true, true), 81), Round4Fixtures.PASSWORD);
    }

    @Test public void prefixedAesHeaderStillRequiresPassword() throws Exception {
        File archive = prefixed(archiveBytes(2, true, true), 81);
        assertThrows(ArchiveSupport.PasswordRequiredException.class,
                () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
    }

    @Test public void prefixedEncodedAdditionalAndMainStreamsShareOrigin() throws Exception {
        verifyAllRoutes(prefixed(externalNamesWithEncodedHeader(), 113), Round4Fixtures.PASSWORD);
    }

    @Test public void signatureAcrossScanChunkBoundaryIsFound() throws Exception {
        verifyAllRoutes(prefixed(archiveBytes(2, false, true), 64 * 1024 - 3), null);
    }

    @Test public void startHeaderAcrossScanChunkBoundaryIsFound() throws Exception {
        verifyAllRoutes(prefixed(archiveBytes(0, false, false), 64 * 1024 + 17), null);
    }

    @Test public void splitSignatureAndHeaderCanCrossPhysicalVolumes() throws Exception {
        byte[] bytes = prefixBytes(archiveBytes(2, true, true), 31);
        File directory = temp.newFolder();
        Round4Fixtures.write(directory, "book.cb7.001", Arrays.copyOfRange(bytes, 0, 34));
        File selected = Round4Fixtures.write(directory, "book.cb7.002",
                Arrays.copyOfRange(bytes, 34, bytes.length - 9));
        Round4Fixtures.write(directory, "book.cb7.003", Arrays.copyOfRange(bytes, bytes.length - 9, bytes.length));
        verifyAllRoutes(selected, Round4Fixtures.PASSWORD);
    }

    @Test public void invalidStartCrcInPrefixIsNotAnArchiveOrigin() throws Exception {
        byte[] prefix = new byte[64];
        byte[] badStart = Arrays.copyOf(archiveBytes(0, false, false), 32);
        badStart[8] ^= 1;
        System.arraycopy(badStart, 0, prefix, 7, badStart.length);
        verifyAllRoutes(write(concat(prefix, archiveBytes(0, false, false))), null);
    }

    @Test public void damagedSignatureAtZeroCannotSelectLaterArchive() throws Exception {
        byte[] damaged = archiveBytes(0, false, false);
        damaged[8] ^= 1;
        assertTerminal(write(concat(damaged, archiveBytes(0, false, false))));
    }

    @Test public void damagedSelectedNextHeaderCannotSelectLaterArchive() throws Exception {
        byte[] damaged = archiveBytes(0, false, false);
        damaged[damaged.length - 1] ^= 1;
        assertTerminal(prefixed(concat(damaged, archiveBytes(0, false, false)), 41));
    }

    @Test public void selectedOutOfBoundsOffsetIsTerminal() throws Exception {
        byte[] damaged = archiveBytes(0, false, false);
        changeStartField(damaged, 12, Long.MAX_VALUE);
        assertTerminal(prefixed(concat(damaged, archiveBytes(0, false, false)), 41));
    }

    @Test public void unsignedOffsetBeyondJavaRangeIsRejected() throws Exception {
        byte[] damaged = archiveBytes(0, false, false);
        changeStartField(damaged, 12, -1L);
        assertTerminal(prefixed(damaged, 41));
    }

    @Test public void selectedOutOfBoundsSizeIsTerminal() throws Exception {
        byte[] damaged = archiveBytes(0, false, false);
        changeStartField(damaged, 20, Long.MAX_VALUE);
        assertTerminal(prefixed(damaged, 41));
    }

    @Test public void noSignatureFailsWithoutOutput() throws Exception {
        File archive = write(new byte[70 * 1024]);
        File output = new File(temp.getRoot(), "not-created.bin");
        assertThrows(IOException.class,
                () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertFalse(output.exists());
    }

    @Test public void prefixSearchHonorsCancellation() throws Exception {
        File archive = prefixed(archiveBytes(0, false, false), 70 * 1024);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void longPrefixLocatorHonorsTokenCancellationWithoutThreadInterrupt() throws Exception {
        File archive = prefixed(archiveBytes(0, false, false), 3 * 64 * 1024 + 17);
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        progress.cancel();
        assertFalse(Thread.currentThread().isInterrupted());
        try (SplitVolumeInput source = new SplitVolumeInput(Collections.singletonList(
                new SplitVolumeInput.Segment(archive, 0, archive.length())))) {
            assertThrows(InterruptedIOException.class, () -> SevenZStartHeader.locate(source, progress));
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void longPrefixCapabilityProbeCannotFallbackAfterTokenCancellation() throws Exception {
        File archive = prefixed(archiveBytes(0, false, false), 3 * 64 * 1024 + 17);
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        progress.cancel();
        assertFalse(Thread.currentThread().isInterrupted());
        assertThrows(InterruptedIOException.class,
                () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null, progress));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void changedPrefixedSourceCannotPublishForwardPayload() throws Exception {
        File archive = prefixed(archiveBytes(2, false, true), 41);
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                     SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool)) {
            assertNotNull(reader);
            Files.write(archive.toPath(), concat(Files.readAllBytes(archive.toPath()), new byte[]{0}));
            assertNotNull(reader.nextEntry());
            assertThrows(IOException.class, () -> reader.read(new byte[8]));
        }
        assertEquals(0, spool.list().length);
    }

    @Test public void prefixSupportDoesNotAdvertiseExecutableExtensions() throws Exception {
        File executable = Round4Fixtures.write(temp.newFolder(), "book.exe",
                prefixBytes(archiveBytes(0, false, false), 41));
        assertNull(ArchiveSupport.getSupportedArchiveType(executable));
    }

    @Test public void prefixedCanonicalEmptyArchiveRemainsEmpty() throws Exception {
        File plain = Round5Fixtures.wrap(temp.newFolder(), new byte[0], new byte[0]);
        File archive = prefixed(Files.readAllBytes(plain.toPath()), 41);
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        assertTrue(SevenZBcj2ArchiveReader.listEntries(archive, null).isEmpty());
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                archive, temp.newFolder(), null, null, null));
        File absent = new File(temp.getRoot(), "missing.bin");
        assertFalse(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "missing.bin", absent, null));
        assertFalse(absent.exists());
        try (ArchiveSupport.ForwardArchiveReader reader =
                     SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, temp.newFolder())) {
            assertNotNull(reader);
            assertNull(reader.nextEntry());
        }
    }

    @Test public void cancelledEmptyArchiveCannotReportSuccessfulBulkExtraction() throws Exception {
        File plain = Round5Fixtures.wrap(temp.newFolder(), new byte[0], new byte[0]);
        File archive = prefixed(Files.readAllBytes(plain.toPath()), 41);
        File directory = temp.newFolder();
        FileOperationProgress progress = new FileOperationProgress("7z", null);
        progress.cancel();
        assertFalse(Thread.currentThread().isInterrupted());
        assertFalse(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(
                archive, directory, null, progress, null));
        assertEquals(0, directory.list().length);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void packedStreamCannotBorrowBytesFromNextHeader() throws Exception {
        Round4Fixtures.Graph data = Round4Fixtures.graph(DATA, 0, false);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1);
        header.write(4);
        // Real packed bytes start at zero; shifting by one makes the claimed
        // range overlap the next header while still fitting the physical file.
        Round4Fixtures.streams(header, data, 1, false, false);
        header.write(5);
        Round4Fixtures.number(header, 1);
        header.write(0);
        header.write(0);
        File raw = Round5Fixtures.wrap(temp.newFolder(), data.packed, header.toByteArray());
        assertTerminal(prefixed(Files.readAllBytes(raw.toPath()), 41));
    }

    @Test public void emptyReadableViewChecksItsZeroSubstreamFolder() throws Exception {
        File archive = prefixed(emptyWithUnreferencedFolder(false), 41);
        File directory = temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.listEntries(archive, null).isEmpty());
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, directory, null, null, null));
        assertEquals(0, directory.list().length);
    }

    @Test public void emptyReadableViewCannotHideBadZeroSubstreamFolderCrc() throws Exception {
        File archive = prefixed(emptyWithUnreferencedFolder(true), 41);
        File directory = temp.newFolder();
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, directory, null, null, null));
        assertEquals(0, directory.list().length);
    }

    @Test public void visibleDirectoryDoesNotSkipZeroSubstreamFolderVerification() throws Exception {
        verifyVisibleEmptyItem(false);
    }

    @Test public void visibleEmptyFileDoesNotSkipZeroSubstreamFolderVerification() throws Exception {
        verifyVisibleEmptyItem(true);
    }

    private void verifyVisibleEmptyItem(boolean emptyFile) throws Exception {
        for (boolean badCrc : new boolean[]{false,true}) {
            File archive = prefixed(emptyWithUnreferencedFolder(badCrc, emptyFile ? 2 : 1),41);
            assertEquals(1,SevenZBcj2ArchiveReader.listEntries(archive,null).size());
            File directory = temp.newFolder();
            if (badCrc) {
                assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                        () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive,directory,null,null,null));
                assertEquals(0,directory.list().length);
            } else {
                assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive,directory,null,null,null));
                File item = new File(directory,"entry0");
                assertEquals(emptyFile,item.isFile()); assertEquals(!emptyFile,item.isDirectory());
                if (emptyFile) assertEquals(0,item.length());
            }
        }
    }

    @Test public void streamedFileDoesNotSkipUnreferencedFirstOrLastFolder() throws Exception {
        for (int unused = 0; unused < 2; unused++) {
            File archive = prefixed(copyWithUnreferencedFolder(unused,false),41);
            File directory = temp.newFolder();
            assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive,directory,null,null,null));
            assertArrayEquals(DATA,Files.readAllBytes(new File(directory,"entry0").toPath()));
        }
    }

    @Test public void badUnreferencedFirstOrLastFolderCannotReportBulkSuccess() throws Exception {
        for (int unused = 0; unused < 2; unused++) {
            File archive = prefixed(copyWithUnreferencedFolder(unused,true),41);
            File directory = temp.newFolder();
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive,directory,null,null,null));
            assertEquals(0,directory.list().length);
        }
    }

    private byte[] copyWithUnreferencedFolder(int unused, boolean badCrc) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); header.write(4); // Header, MainStreamsInfo.
        header.write(6); Round4Fixtures.number(header,0); Round4Fixtures.number(header,2);
        header.write(9); Round4Fixtures.number(header,DATA.length); Round4Fixtures.number(header,DATA.length);
        header.write(0); // PackInfo end.
        header.write(7); header.write(11); Round4Fixtures.number(header,2); header.write(0);
        for (int i = 0; i < 2; i++) { header.write(1); header.write(1); header.write(0); } // Copy coders.
        header.write(12); Round4Fixtures.number(header,DATA.length); Round4Fixtures.number(header,DATA.length);
        header.write(10); header.write(1);
        for (int i = 0; i < 2; i++) Round4Fixtures.le(header,
                Round4Fixtures.crc(DATA) ^ (badCrc && i == unused ? 1L : 0L),4);
        header.write(0); // UnpackInfo end.
        header.write(8); header.write(13);
        for (int i = 0; i < 2; i++) Round4Fixtures.number(header,i == unused ? 0 : 1);
        header.write(0); header.write(0); // SubStreamsInfo, StreamsInfo ends.
        header.write(5); Round4Fixtures.number(header,1); header.write(0); header.write(0);
        File archive = Round5Fixtures.wrap(temp.newFolder(),concat(DATA,DATA),header.toByteArray());
        return Files.readAllBytes(archive.toPath());
    }

    private void assertTerminal(File archive) throws Exception {
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
        File output = temp.newFile();
        byte[] previous = {42};
        Files.write(output.toPath(), previous);
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(previous, Files.readAllBytes(output.toPath()));
    }

    private void verifyAllRoutes(File archive, char[] password) throws Exception {
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, password));
        assertEquals("data.bin", SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, password).get(0).path);
        assertEquals("data.bin", ArchiveSupport.listEntries(archive, password).get(0).path);
        File selected = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", selected, password));
        assertArrayEquals(DATA, Files.readAllBytes(selected.toPath()));
        File bulk = temp.newFolder();
        assertTrue(SevenZBcj2ArchiveReader.extractArchiveIntoDirectory(archive, bulk, password, null, null));
        assertArrayEquals(DATA, Files.readAllBytes(new File(bulk, "data.bin").toPath()));
        File spool = temp.newFolder();
        try (ArchiveSupport.ForwardArchiveReader reader =
                     SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, password, spool)) {
            assertNotNull(reader);
            verifyForward(reader);
        }
        assertEquals(0, spool.list().length);
    }

    private void verifyForward(ArchiveSupport.ForwardArchiveReader reader) throws Exception {
        assertNotNull(reader.nextEntry());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[3];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            assertTrue(count > 0);
            output.write(buffer, 0, count);
        }
        assertArrayEquals(DATA, output.toByteArray());
        assertNull(reader.nextEntry());
    }

    private byte[] archiveBytes(int width, boolean aes, boolean encodedHeader) throws Exception {
        File archive = Round4Fixtures.sevenZ(temp.newFolder(), DATA, width, aes, encodedHeader, false, false);
        return Files.readAllBytes(archive.toPath());
    }

    private byte[] externalNamesWithEncodedHeader() throws Exception {
        Round4Fixtures.Graph names = Round4Fixtures.graph("data.bin\0".getBytes(StandardCharsets.UTF_16LE), 2, true);
        Round4Fixtures.Graph data = Round4Fixtures.graph(DATA, 2, true);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1);
        header.write(3); // AdditionalStreamsInfo
        Round4Fixtures.streams(header, names, 0, false, false);
        header.write(4); // MainStreamsInfo
        Round4Fixtures.streams(header, data, names.packed.length, false, false);
        header.write(5);
        Round4Fixtures.number(header, 1);
        header.write(17); // External name in additional folder 0
        Round4Fixtures.number(header, 2);
        header.write(1);
        header.write(0);
        header.write(0);
        header.write(0);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(names.packed);
        packed.write(data.packed);
        Round4Fixtures.Graph encoded = Round4Fixtures.graph(header.toByteArray(), 2, true);
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(23);
        Round4Fixtures.streams(descriptor, encoded, packed.size(), false, false);
        packed.write(encoded.packed);
        File archive = Round5Fixtures.wrap(temp.newFolder(), packed.toByteArray(), descriptor.toByteArray());
        return Files.readAllBytes(archive.toPath());
    }

    private byte[] emptyWithUnreferencedFolder(boolean badCrc) throws Exception {
        return emptyWithUnreferencedFolder(badCrc,0);
    }

    private byte[] emptyWithUnreferencedFolder(boolean badCrc, int visibleKind) throws Exception {
        Round4Fixtures.Graph data = Round4Fixtures.graph(DATA, 0, false);
        ByteArrayOutputStream streams = new ByteArrayOutputStream();
        Round4Fixtures.streams(streams, data, 0, false, badCrc);
        byte[] info = streams.toByteArray();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1);
        header.write(4);
        header.write(info, 0, info.length - 1); // Replace the StreamsInfo terminator.
        header.write(8); // SubStreamsInfo
        header.write(13); // NumUnpackStream
        Round4Fixtures.number(header, 0);
        header.write(0); // SubStreamsInfo end
        header.write(0); // StreamsInfo end
        header.write(5); // FilesInfo
        Round4Fixtures.number(header, visibleKind == 0 ? 0 : 1);
        if (visibleKind != 0) {
            header.write(14); Round4Fixtures.number(header,1); header.write(0x80); // EmptyStream.
            if (visibleKind == 2) {
                header.write(15); Round4Fixtures.number(header,1); header.write(0x80); // EmptyFile.
            }
        }
        header.write(0);
        header.write(0);
        File archive = Round5Fixtures.wrap(temp.newFolder(), data.packed, header.toByteArray());
        return Files.readAllBytes(archive.toPath());
    }

    private File prefixed(byte[] archive, int size) throws IOException { return write(prefixBytes(archive, size)); }

    private byte[] prefixBytes(byte[] archive, int size) throws IOException {
        byte[] prefix = new byte[size];
        Arrays.fill(prefix, (byte) 0x55);
        if (size > 1) { prefix[0] = 'M'; prefix[1] = 'Z'; }
        return concat(prefix, archive);
    }

    private File write(byte[] bytes) throws IOException { return Round4Fixtures.write(temp.newFolder(), "book.7z", bytes); }

    private static byte[] concat(byte[] first, byte[] second) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(first);
        output.write(second);
        return output.toByteArray();
    }

    private static void changeStartField(byte[] archive, int offset, long value) {
        for (int i = 0; i < 8; i++) archive[offset + i] = (byte) (value >>> (8 * i));
        long crc = Round4Fixtures.crc(Arrays.copyOfRange(archive, 12, 32));
        for (int i = 0; i < 4; i++) archive[8 + i] = (byte) (crc >>> (8 * i));
    }
}
