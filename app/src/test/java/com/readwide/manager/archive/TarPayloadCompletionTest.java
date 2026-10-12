package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;
import org.tukaani.xz.XZOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic compressed TARs place their codec trailer beyond TAR's end marker. */
public class TarPayloadCompletionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] CONTENT = {1, 2, 3, 4};
    private static final byte[] ORIGINAL = {42};

    @Test public void validPaddedCodecsStillListAndExtract() throws Exception {
        for (String suffix : new String[]{".gz", ".bz2", ".xz", ".lzma", ".lz4"}) {
            File archive = archive(compress(tar(true), suffix), suffix);
            assertEquals(1, ArchiveSupport.listEntries(archive, null).size());
            File destination = new File(temporary.newFolder(), "output");
            ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                    archive, destination, false, null, null);
            assertTrue(suffix + ": " + result.detail, result.success);
            assertArrayEquals(CONTENT, Files.readAllBytes(new File(destination, "page").toPath()));
        }
    }

    @Test public void badGzipCrcFailsListingWithAndWithoutLargePadding() throws Exception {
        for (boolean padding : new boolean[]{false, true}) {
            File archive = archive(badGzip(tar(padding)), ".gz");
            assertThrows(ArchiveSupport.TarIntegrityException.class,
                    () -> ArchiveSupport.listEntries(archive, null));
        }
    }

    @Test public void truncatedGzipTrailerFailsListing() throws Exception {
        byte[] gzip = compress(tar(true), ".gz");
        File archive = archive(Arrays.copyOf(gzip, gzip.length - 3), ".gz");
        assertThrows(ArchiveSupport.TarIntegrityException.class,
                () -> ArchiveSupport.listEntries(archive, null));
    }

    @Test public void badTrailerCannotReplaceExistingDirectory() throws Exception {
        File archive = archive(badGzip(tar(true)), ".gz"), destination = temporary.newFolder();
        File keep = new File(destination, "keep");
        Files.write(keep.toPath(), ORIGINAL);
        assertFalse(ArchiveSupport.extractArchiveDetailed(archive, destination, true, null, null).success);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(keep.toPath()));
        assertArrayEquals(new String[]{"keep"}, destination.list());
    }

    @Test public void bulkTrailerFailureIsTerminalAtFallbackBoundary() throws Exception {
        File archive = archive(badGzip(tar(true)), ".gz"), destination = temporary.newFolder();
        assertThrows(ArchiveSupport.TarIntegrityException.class,
                () -> call("extractTarIntoDirectoryWithFallback", archive, ArchiveSupport.Type.TAR_GZ,
                        destination, null, null));
        // This private writer is not archive-atomic; the public route owns staging/cleanup.
    }

    @Test public void damagedLaterGzipMemberCannotHideBeyondTarEof() throws Exception {
        byte[] first = compress(tar(true), ".gz"), second = badGzip(new byte[128 * 1024]);
        File archive = archive(concat(first, second), ".gz");
        assertThrows(ArchiveSupport.TarIntegrityException.class,
                () -> ArchiveSupport.listEntries(archive, null));
    }

    @Test public void validConcatenatedPaddingMemberRemainsAccepted() throws Exception {
        File archive = archive(concat(compress(tar(true), ".gz"),
                compress(new byte[128 * 1024], ".gz")), ".gz");
        assertEquals(1, ArchiveSupport.listEntries(archive, null).size());
        File destination = new File(temporary.newFolder(), "output");
        assertTrue(ArchiveSupport.extractArchiveDetailed(archive, destination, false, null, null).success);
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(destination, "page").toPath()));
    }

    @Test public void viewerTrailerFailureIsStickyAfterTarEof() throws Exception {
        File archive = archive(badGzip(tar(true)), ".gz");
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            assertEquals("page", reader.nextEntry().path);
            assertArrayEquals(CONTENT, readEntry(reader));
            IOException failure = assertThrows(ArchiveSupport.TarIntegrityException.class, reader::nextEntry);
            assertSame(failure, assertThrows(IOException.class, reader::nextEntry));
            assertSame(failure, assertThrows(IOException.class, () -> reader.read(new byte[1])));
            reader.close(); // Repeated close must not resume decoding or mask the failure.
        }
    }

    @Test public void viewerSuccessfulEofAndCloseRemainStable() throws Exception {
        File archive = archive(compress(tar(true), ".gz"), ".gz");
        ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null);
        try {
            assertEquals("page", reader.nextEntry().path);
            assertArrayEquals(CONTENT, readEntry(reader));
            assertNull(reader.nextEntry());
            assertNull(reader.nextEntry());
            assertEquals(-1, reader.read(new byte[1]));
            assertEquals(0, reader.read(new byte[0]));
        } finally { reader.close(); }
        reader.close();
        assertThrows(IOException.class, reader::nextEntry);
        assertThrows(IOException.class, () -> reader.read(new byte[1]));
    }

    @Test public void earlySelectedAndViewerCloseDoNotScanUnrelatedTail() throws Exception {
        File archive = archive(badGzip(tar(true)), ".gz"), output = temporary.newFile();
        Files.write(output.toPath(), ORIGINAL);
        assertTrue(ArchiveSupport.extractSingleEntryDetailed(archive, "page", output, null).success);
        assertArrayEquals(CONTENT, Files.readAllBytes(output.toPath()));
        try (ArchiveSupport.ForwardArchiveReader reader = ArchiveSupport.openForwardReader(archive, null)) {
            assertEquals("page", reader.nextEntry().path);
            assertArrayEquals(CONTENT, readEntry(reader));
        }
        // Early output is not a claim that the entire compressed archive has been verified.
    }

    @Test public void completionCancellationIsCheckedBeforeInputRead() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        progress.cancel();
        InputStream untouched = new InputStream() {
            @Override public int read() { throw new AssertionError("must not read after cancellation"); }
        };
        assertThrows(InterruptedIOException.class, () -> finish(untouched, progress));
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> finish(untouched, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void completionCancellationAfterLastReadCannotBecomeSuccess() throws Exception {
        FileOperationProgress progress = new FileOperationProgress("extract", null);
        InputStream ending = new InputStream() {
            @Override public int read() { progress.cancel(); return -1; }
        };
        assertThrows(InterruptedIOException.class, () -> finish(ending, progress));
    }

    @Test public void completionRejectsInvalidReadCountsWithoutLooping() throws Exception {
        for (int count : new int[]{-2, 0, 65537}) {
            InputStream invalid = new InputStream() {
                @Override public int read() { return count; }
                @Override public int read(byte[] bytes, int offset, int length) { return count; }
            };
            assertThrows(ArchiveSupport.TarIntegrityException.class, () -> finish(invalid, null));
        }
    }

    @Test public void payloadReadSkipAndCloseErrorsAreTerminalWithTheirCauses() throws Exception {
        IOException original = new IOException("injected codec I/O");
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw original; }
            @Override public long skip(long count) throws IOException { throw original; }
            @Override public void close() throws IOException { throw original; }
        };
        InputStream wrapped = (InputStream) call("checkedTarPayload", failing);
        assertSame(original, assertThrows(ArchiveSupport.TarIntegrityException.class, wrapped::read).getCause());
        assertSame(original, assertThrows(ArchiveSupport.TarIntegrityException.class,
                () -> wrapped.read(new byte[1])).getCause());
        assertSame(original, assertThrows(ArchiveSupport.TarIntegrityException.class,
                () -> wrapped.skip(1)).getCause());
        assertSame(original, assertThrows(ArchiveSupport.TarIntegrityException.class, wrapped::close).getCause());
    }

    @Test public void payloadWrappedCancellationIsNotRelabeledCorruption() throws Exception {
        InterruptedIOException cancelled = new InterruptedIOException("cancelled");
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("callback", cancelled); }
        };
        try (InputStream wrapped = (InputStream) call("checkedTarPayload", failing)) {
            assertSame(cancelled, assertThrows(InterruptedIOException.class, wrapped::read));
        }
    }

    private void finish(InputStream input, FileOperationProgress progress) throws Exception {
        call("finishTarPayload", input, ArchiveSupport.Type.TAR_GZ, progress);
    }
    private Object call(String name, Object... arguments) throws Exception {
        Method method = Arrays.stream(ArchiveSupport.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().get();
        method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }
    private byte[] tar(boolean padded) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("page");
            entry.setSize(CONTENT.length);
            tar.putArchiveEntry(entry); tar.write(CONTENT); tar.closeArchiveEntry();
        }
        byte[] raw = bytes.toByteArray();
        return padded ? Arrays.copyOf(raw, raw.length + 128 * 1024) : raw;
    }
    private byte[] compress(byte[] raw, String suffix) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream compressor;
        switch (suffix) {
            case ".gz": compressor = new GZIPOutputStream(bytes); break;
            case ".bz2": compressor = new BZip2CompressorOutputStream(bytes); break;
            case ".xz": compressor = new XZOutputStream(bytes, new LZMA2Options(0)); break;
            case ".lzma": compressor = new LZMAOutputStream(bytes, new LZMA2Options(0), raw.length); break;
            case ".lz4": compressor = new FramedLZ4CompressorOutputStream(bytes); break;
            default: throw new AssertionError(suffix);
        }
        try (OutputStream output = compressor) { output.write(raw); }
        return bytes.toByteArray();
    }
    private byte[] badGzip(byte[] raw) throws Exception {
        byte[] bytes = compress(raw, ".gz");
        bytes[bytes.length - 8] ^= 1;
        return bytes;
    }
    private File archive(byte[] bytes, String suffix) throws IOException {
        File file = new File(temporary.newFolder(), "book.tar" + suffix);
        Files.write(file.toPath(), bytes);
        return file;
    }
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] bytes = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, bytes, first.length, second.length);
        return bytes;
    }
    private static byte[] readEntry(ArchiveSupport.ForwardArchiveReader reader) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[3];
        for (int count; (count = reader.read(buffer)) != -1;) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }
}
