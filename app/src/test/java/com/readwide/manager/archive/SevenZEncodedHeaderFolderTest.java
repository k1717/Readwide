package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Synthetic container regressions; no producer/device validation claim. */
public class SevenZEncodedHeaderFolderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void metadataTokenCancellationStopsBeforeReadingEvenEmptyOutput() throws Exception {
        com.readwide.manager.util.FileOperationProgress progress =
                new com.readwide.manager.util.FileOperationProgress("metadata", null);
        progress.cancel();
        java.io.InputStream input = new java.io.InputStream() {
            @Override public int read() { throw new AssertionError("Cancelled metadata was read"); }
        };
        for (long size : new long[]{0, 5}) {
            assertThrows(java.io.InterruptedIOException.class, () -> readMetadata(input, size, progress));
        }
        org.junit.Assert.assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void metadataTokenCancellationAfterAChunkDoesNotReturnPartialBytes() throws Exception {
        com.readwide.manager.util.FileOperationProgress progress =
                new com.readwide.manager.util.FileOperationProgress("metadata", null);
        int[] reads = {0};
        java.io.InputStream input = new java.io.InputStream() {
            @Override public int read() { throw new AssertionError("Unexpected single-byte read"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                reads[0]++;
                assertTrue(length > 0 && length <= 65536);
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 42);
                progress.cancel();
                return length;
            }
        };
        assertThrows(java.io.InterruptedIOException.class, () -> readMetadata(input, 131073, progress));
        assertEquals(1, reads[0]);
        org.junit.Assert.assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void activeAndAbsentMetadataTokensKeepExactBytesAndInputOwnership() throws Exception {
        byte[] expected = new byte[131073];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 17);
        for (com.readwide.manager.util.FileOperationProgress progress :
                new com.readwide.manager.util.FileOperationProgress[]{null,
                        new com.readwide.manager.util.FileOperationProgress("metadata", null)}) {
            int[] reads = {0};
            java.io.InputStream input = new java.io.ByteArrayInputStream(expected) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    assertTrue(length > 0 && length <= 65536); reads[0]++;
                    return super.read(bytes, offset, length);
                }
                @Override public void close() { throw new AssertionError("Caller owns metadata input"); }
            };
            assertArrayEquals(expected, readMetadata(input, expected.length, progress));
            assertEquals(3, reads[0]);
            assertEquals(-1, input.read());
        }
    }

    @Test public void metadataReadsPreserveInputErrorsAndRejectNoProgress() throws Exception {
        IOException sentinel = new IOException("source failure");
        java.io.InputStream input = new java.io.InputStream() {
            @Override public int read() throws IOException { throw sentinel; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException { throw sentinel; }
        };
        org.junit.Assert.assertSame(sentinel,
                assertThrows(IOException.class, () -> readMetadata(input, 3, null)));
        java.io.InputStream stalled = new java.io.InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] bytes, int offset, int length) { return 0; }
        };
        assertTrue(assertThrows(IOException.class, () -> readMetadata(stalled, 3, null))
                .getMessage().contains("no progress"));
    }

    private static byte[] readMetadata(java.io.InputStream input, long size,
            com.readwide.manager.util.FileOperationProgress progress) throws Exception {
        java.lang.reflect.Method method = SevenZBcj2ArchiveReader.class.getDeclaredMethod(
                "readExact", java.io.InputStream.class, long.class,
                com.readwide.manager.util.FileOperationProgress.class);
        method.setAccessible(true);
        try { return (byte[]) method.invoke(null, input, size, progress); }
        catch (java.lang.reflect.InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw (Error) cause;
        }
    }

    @Test public void shortCleanCopyAndLzma2HeadersAreTerminalDespiteValidPackedCrcs() throws Exception {
        byte[] raw = emptyFileHeader();
        byte[] shortRaw = java.util.Arrays.copyOf(raw, raw.length - 1);
        assertTerminalXzHeader(singleCoderHeader(shortRaw, new byte[]{0}, new byte[0], raw));
        assertTerminalXzHeader(singleCoderHeader(storedLzma2(shortRaw, true), new byte[]{0x21}, new byte[]{0}, raw));
    }

    @Test public void overlongCopyHeaderKeepsExistingBackendProbePolicy() throws Exception {
        byte[] raw = emptyFileHeader();
        byte[] withPadding = java.util.Arrays.copyOf(raw, raw.length + 1);
        withPadding[raw.length] = 42;
        File archive = singleCoderHeader(withPadding, new byte[]{0}, new byte[0], raw);
        // Do not equate short decoded output with the separate legacy padding policy.
        org.junit.Assert.assertFalse(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        org.junit.Assert.assertNull(SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
    }

    @Test public void xzCorruptMetadataIsTerminalWithoutChangingDestinations() throws Exception {
        byte[] raw = emptyFileHeader();
        byte[] stored = storedLzma2(raw, false);
        for (byte[] packed : new byte[][]{new byte[0], {3}, stored}) {
            assertTerminalXzHeader(singleCoderHeader(packed, new byte[]{0x21}, new byte[]{0}, raw));
        }
        for (byte[] packed : new byte[][]{new byte[0], {1,0,0,0,0}}) {
            assertTerminalXzHeader(singleCoderHeader(packed, new byte[]{3,1,1}, new byte[]{0x5d,0,0,16,0}, raw));
        }
    }

    @Test public void unusedLzma2BranchInEncodedMetadataCannotHideCorruption() throws Exception {
        for (byte[] packed : new byte[][]{new byte[0], {3}}) {
            assertTerminalXzHeader(bcj2HeaderWithUnusedLzma2(packed));
        }
        File valid = bcj2HeaderWithUnusedLzma2(new byte[]{0,0x55});
        assertTrue(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(valid, null));
        assertEquals("data.bin", SevenZBcj2ArchiveReader.tryListSpecialEntries(valid, null).get(0).path);
    }

    @Test public void validLzma2HeaderStillListsAndExtracts() throws Exception {
        byte[] raw = emptyFileHeader();
        File archive = singleCoderHeader(storedLzma2(raw, true), new byte[]{0x21}, new byte[]{0}, raw);
        assertEquals("data.bin", SevenZBcj2ArchiveReader.listEntries(archive, null).get(0).path);
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertEquals(0, output.length());
    }

    @Test public void unknownCoderAndInvalidPreparationStillAllowOtherHeaderBackends() throws Exception {
        for (File archive : new File[]{
                singleCoderHeader(new byte[0], new byte[]{(byte)0xff}, new byte[0], emptyFileHeader()),
                singleCoderHeader(new byte[0], new byte[]{3,1,1}, new byte[]{(byte)255,0,0,16,0}, emptyFileHeader())}) {
            org.junit.Assert.assertFalse(SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
            org.junit.Assert.assertNull(SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
        }
    }

    private void assertTerminalXzHeader(File archive) throws Exception {
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> ArchiveSupport.listEntries(archive, null));
        File spool = temp.newFolder();
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool));
        assertEquals(0, spool.list().length);
        File output = temp.newFile(); byte[] original = {7,8,9};
        Files.write(output.toPath(), original);
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
    }

    private static byte[] storedLzma2(byte[] raw, boolean endMarker) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(1); packed.write((raw.length - 1) >>> 8); packed.write(raw.length - 1);
        packed.write(raw);
        if (endMarker) packed.write(0);
        return packed.toByteArray();
    }

    private File singleCoderHeader(byte[] packed, byte[] id, byte[] properties, byte[] decoded) throws IOException {
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(new byte[]{23,6,0,1,9});
        Round4Fixtures.number(descriptor, packed.length);
        descriptor.write(new byte[]{10,1}); Round4Fixtures.le(descriptor, Round4Fixtures.crc(packed), 4);
        descriptor.write(new byte[]{0,7,11,1,0,1}); // One inline folder/coder.
        descriptor.write(0x20 | id.length); descriptor.write(id);
        Round4Fixtures.number(descriptor, properties.length); descriptor.write(properties);
        descriptor.write(12); Round4Fixtures.number(descriptor, decoded.length);
        descriptor.write(new byte[]{10,1}); Round4Fixtures.le(descriptor, Round4Fixtures.crc(decoded), 4);
        descriptor.write(new byte[]{0,0});
        return Round5Fixtures.wrap(temp.newFolder(), packed, descriptor.toByteArray());
    }

    @Test public void decodedLzma2TailInBcj2HeaderIsTerminalDespiteMatchingCrcs() throws Exception {
        assertTerminalXzHeader(bcj2HeaderWithUnusedLzma2(storedLzma2(new byte[4], true), 4));
    }

    private File bcj2HeaderWithUnusedLzma2(byte[] branch) throws IOException {
        return bcj2HeaderWithUnusedLzma2(branch, 0);
    }

    private File bcj2HeaderWithUnusedLzma2(byte[] branch, int decodedBranchSize) throws IOException {
        byte[] raw = emptyFileHeader(), control = new byte[5];
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(branch); packed.write(raw); packed.write(control);
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(new byte[]{23,6,0,4,9});
        for (int length : new int[]{branch.length,raw.length,0,control.length}) Round4Fixtures.number(descriptor, length);
        descriptor.write(new byte[]{10,1});
        for (byte[] bytes : new byte[][]{branch,raw,new byte[0],control})
            Round4Fixtures.le(descriptor, Round4Fixtures.crc(bytes), 4);
        descriptor.write(new byte[]{0,7,11,1,0,2,0x21,0x21,1,0,0x14,3,3,1,0x1b,4,1});
        descriptor.write(new byte[]{2,0,0,1,3,4}); // CALL <- LZMA2; four external packed inputs.
        descriptor.write(12); Round4Fixtures.number(descriptor, decodedBranchSize);
        Round4Fixtures.number(descriptor, raw.length);
        descriptor.write(new byte[]{10,1}); Round4Fixtures.le(descriptor, Round4Fixtures.crc(raw), 4);
        descriptor.write(new byte[]{0,0});
        return Round5Fixtures.wrap(temp.newFolder(), packed.toByteArray(), descriptor.toByteArray());
    }

    @Test public void invalidPpmdRangeHeaderCannotFallThroughProbes() throws Exception {
        for (byte[] packed : new byte[][]{{1,0,0,0,0}, {0,-1,-1,-1,-1}, {0,0,0,0,1}}) {
            File archive = ppmdEncodedHeader(packed);
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
            File spool = temp.newFolder();
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool));
            assertEquals(0, spool.list().length);
        }
    }

    @Test public void invalidPpmdHeaderCannotReplaceSelectedOutput() throws Exception {
        File archive = ppmdEncodedHeader(new byte[]{0,0,0,0,1});
        File output = temp.newFile(); byte[] original = {7,8,9};
        Files.write(output.toPath(), original);
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> ArchiveSupport.listEntries(archive, null));
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
    }

    @Test public void truncatedPpmdInitializerIsTerminalForAllHeaderProbes() throws Exception {
        for (int length = 0; length < 5; length++) {
            File archive = ppmdEncodedHeader(new byte[length]);
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
            File spool = temp.newFolder();
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool));
            assertEquals(0, spool.list().length);
            File output = temp.newFile(); Files.write(output.toPath(), new byte[]{42});
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                    () -> SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
        }
    }

    private File ppmdEncodedHeader(byte[] packed) throws IOException {
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(new byte[]{23,6,0,1,9}); // EncodedHeader, PackInfo, position 0, count 1, Size.
        Round4Fixtures.number(descriptor, packed.length);
        descriptor.write(new byte[]{0,7,11,1,0,1,0x23,3,4,1,5,6,0,0,1,0});
        // End PackInfo; UnpackInfo, Folder, count 1 inline; one PPMd coder with five property bytes.
        descriptor.write(new byte[]{12,0,0,0}); // Unpacked size 0; end UnpackInfo/StreamsInfo.
        return Round5Fixtures.wrap(temp.newFolder(), packed, descriptor.toByteArray());
    }

    @Test public void encodedHeaderWithoutFolderIsRejected() throws Exception {
        File archive = Round5Fixtures.wrap(temp.newFolder(), new byte[0], new byte[]{23, 0});
        IOException failure = assertThrows(IOException.class,
                () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
        assertEquals("7z encoded header must have exactly one folder", failure.getMessage());
    }

    @Test public void secondEncodedHeaderFolderCannotBeIgnored() throws Exception {
        File archive = copyEncodedHeader(emptyFileHeader(), new byte[]{42});
        IOException failure = assertThrows(IOException.class,
                () -> SevenZBcj2ArchiveReader.listEntries(archive, null));
        assertEquals("7z encoded header must have exactly one folder", failure.getMessage());
    }

    @Test public void malformedEncodedHeaderDoesNotFallThroughCapabilityProbe() throws Exception {
        File archive = copyEncodedHeader(emptyFileHeader(), new byte[]{42});
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.archiveUsesSpecialCoder(archive, null));
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.tryListSpecialEntries(archive, null));
    }

    @Test public void malformedEncodedHeaderDoesNotFallThroughForwardProbe() throws Exception {
        File archive = copyEncodedHeader(emptyFileHeader(), new byte[]{42});
        File spool = temp.newFolder();
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, null, spool));
        assertEquals(0, spool.list().length);
    }

    @Test public void malformedEncodedHeaderIsRejectedByPublicListing() throws Exception {
        File archive = copyEncodedHeader(emptyFileHeader(), new byte[]{42});
        assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                () -> ArchiveSupport.listEntries(archive, null));
    }

    @Test public void extraHeaderFolderCannotReplaceExistingOutput() throws Exception {
        File archive = copyEncodedHeader(emptyFileHeader(), new byte[]{42});
        File output = temp.newFile();
        byte[] original = {7, 8, 9};
        Files.write(output.toPath(), original);
        assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader.extractSingleEntry(
                archive, "data.bin", output, null));
        assertArrayEquals(original, Files.readAllBytes(output.toPath()));
    }

    @Test public void singleCopyEncodedHeaderStillListsAndExtracts() throws Exception {
        File archive = copyEncodedHeader(emptyFileHeader());
        assertEquals("data.bin", SevenZBcj2ArchiveReader.listEntries(archive, null).get(0).path);
        File output = new File(temp.getRoot(), "empty-output.bin");
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "data.bin", output, null));
        assertTrue(output.isFile());
        assertEquals(0L, output.length());
    }

    @Test public void singleEncryptedFilteredHeaderStillExtracts() throws Exception {
        byte[] content = {1, 2, 3, 4, 5, 6};
        File archive = Round4Fixtures.sevenZ(temp.newFolder(), content, 2,
                true, true, false, false);
        File output = temp.newFile();
        assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(
                archive, "data.bin", output, Round4Fixtures.PASSWORD));
        assertArrayEquals(content, Files.readAllBytes(output.toPath()));
    }

    private byte[] emptyFileHeader() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(1); // Header
        header.write(5); // FilesInfo
        Round4Fixtures.number(header, 1);
        header.write(14); // EmptyStream
        Round4Fixtures.number(header, 1);
        header.write(128);
        header.write(15); // EmptyFile
        Round4Fixtures.number(header, 1);
        header.write(128);
        byte[] name = "data.bin\0".getBytes(StandardCharsets.UTF_16LE);
        header.write(17); // Name
        Round4Fixtures.number(header, name.length + 1);
        header.write(0); // Inline names
        header.write(name);
        header.write(0); // FilesInfo end
        header.write(0); // Header end
        return header.toByteArray();
    }

    private File copyEncodedHeader(byte[]... decodedFolders) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(23); // EncodedHeader
        descriptor.write(6); // PackInfo
        Round4Fixtures.number(descriptor, 0);
        Round4Fixtures.number(descriptor, decodedFolders.length);
        descriptor.write(9); // Size
        for (byte[] folder : decodedFolders) {
            Round4Fixtures.number(descriptor, folder.length);
            packed.write(folder);
        }
        descriptor.write(10); // CRC
        descriptor.write(1); // All defined
        for (byte[] folder : decodedFolders) Round4Fixtures.le(descriptor, Round4Fixtures.crc(folder), 4);
        descriptor.write(0); // PackInfo end
        descriptor.write(7); // UnpackInfo
        descriptor.write(11); // Folder
        Round4Fixtures.number(descriptor, decodedFolders.length);
        descriptor.write(0); // Inline folders
        for (byte[] ignored : decodedFolders) {
            Round4Fixtures.number(descriptor, 1); // One coder
            descriptor.write(1); // One-byte method ID, one input/output
            descriptor.write(0); // Copy
        }
        descriptor.write(12); // CodersUnpackSize
        for (byte[] folder : decodedFolders) Round4Fixtures.number(descriptor, folder.length);
        descriptor.write(10); // Folder CRCs
        descriptor.write(1); // All defined
        for (byte[] folder : decodedFolders) Round4Fixtures.le(descriptor, Round4Fixtures.crc(folder), 4);
        descriptor.write(0); // UnpackInfo end
        descriptor.write(0); // StreamsInfo end
        return Round5Fixtures.wrap(temp.newFolder(), packed.toByteArray(), descriptor.toByteArray());
    }
}
