package com.readwide.manager.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import org.junit.After;
import org.junit.Test;

public class UriCopyIntegrityTest {
    @After public void clearInterrupt() { Thread.interrupted(); }

    @Test public void knownLengthAndTimestampAcceptCompleteCopy() throws Exception {
        byte[] input = {1, 2, 3};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UriCopyIntegrity.Metadata metadata = metadata(3, 20);
        long copied = UriCopyIntegrity.copy(new ByteArrayInputStream(input), output, metadata, 8);
        UriCopyIntegrity.verify(metadata, metadata(3, 20), copied);
        assertArrayEquals(input, output.toByteArray());
    }

    @Test public void unknownMetadataAcceptsBoundedStream() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long copied = UriCopyIntegrity.copy(new ByteArrayInputStream(new byte[] {1, 2}), output,
                UriCopyIntegrity.Metadata.UNKNOWN, 2);
        UriCopyIntegrity.verify(UriCopyIntegrity.Metadata.UNKNOWN, UriCopyIntegrity.Metadata.UNKNOWN, copied);
        assertEquals(2L, copied);
    }

    @Test public void truncatedStreamIsRejected() throws Exception {
        expectFailure(() -> UriCopyIntegrity.copy(new ByteArrayInputStream(new byte[] {1, 2}),
                new ByteArrayOutputStream(), metadata(3, 0), 8));
    }

    @Test public void knownGrowthIsNotWrittenPastOriginalLength() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        expectFailure(() -> UriCopyIntegrity.copy(new ByteArrayInputStream(new byte[9000]), output,
                metadata(8192, 0), 10000));
        assertEquals(8192, output.size());
    }

    @Test public void knownEmptyFileCannotSilentlyGrow() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        expectFailure(() -> UriCopyIntegrity.copy(new ByteArrayInputStream(new byte[] {1}), output,
                metadata(0, 0), 8));
        assertEquals(0, output.size());
    }

    @Test public void knownOversizeFailsBeforeReadingOrWriting() throws Exception {
        InputStream input = new InputStream() {
            @Override public int read() { throw new AssertionError("Should not read"); }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        expectFailure(() -> UriCopyIntegrity.copy(input, output, metadata(9, 0), 8));
        assertEquals(0, output.size());
    }

    @Test public void unknownLengthCannotPassHardCap() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        expectFailure(() -> UriCopyIntegrity.copy(new ByteArrayInputStream(new byte[9000]), output,
                UriCopyIntegrity.Metadata.UNKNOWN, 8192));
        assertEquals(8192, output.size());
    }

    @Test public void zeroLengthReadMakesProgressThroughSingleByteRead() throws Exception {
        InputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override public int read(byte[] bytes, int offset, int length) { return 0; }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(3, UriCopyIntegrity.copy(input, output, metadata(3, 0), 8));
        assertArrayEquals(new byte[] {1, 2, 3}, output.toByteArray());
    }

    @Test public void changedSizeAfterCopyRejectsSnapshot() throws Exception {
        expectFailure(() -> UriCopyIntegrity.verify(metadata(3, 10), metadata(4, 10), 3));
        expectFailure(() -> UriCopyIntegrity.verify(metadata(3, 10), metadata(2, 10), 3));
    }

    @Test public void sameSizeChangedTimestampRejectsSnapshot() throws Exception {
        expectFailure(() -> UriCopyIntegrity.verify(metadata(3, 10), metadata(3, 11), 3));
    }

    @Test public void previouslyKnownMetadataMustStillBeAvailable() throws Exception {
        expectFailure(() -> UriCopyIntegrity.verify(metadata(3, 0), UriCopyIntegrity.Metadata.UNKNOWN, 3));
        expectFailure(() -> UriCopyIntegrity.verify(metadata(-1, 10), metadata(-1, 0), 3));
    }

    @Test public void newlyAvailableLengthIsCheckedAgainstCopiedBytes() throws Exception {
        UriCopyIntegrity.verify(UriCopyIntegrity.Metadata.UNKNOWN, metadata(3, 10), 3);
        expectFailure(() -> UriCopyIntegrity.verify(UriCopyIntegrity.Metadata.UNKNOWN, metadata(4, 10), 3));
    }

    @Test public void interruptBeforeCopyDoesNotRead() throws Exception {
        Thread.currentThread().interrupt();
        expectInterrupted(() -> UriCopyIntegrity.copy(new ByteArrayInputStream(new byte[] {1}),
                new ByteArrayOutputStream(), metadata(1, 0), 8));
    }

    @Test public void interruptOnEofRejectsCompletedBytes() throws Exception {
        InputStream input = new ByteArrayInputStream(new byte[0]) {
            @Override public int read(byte[] bytes, int offset, int length) {
                Thread.currentThread().interrupt();
                return -1;
            }
        };
        expectInterrupted(() -> UriCopyIntegrity.copy(input, new ByteArrayOutputStream(), metadata(0, 0), 8));
    }

    @Test public void interruptBeforeCommitVerificationIsPreserved() throws Exception {
        Thread.currentThread().interrupt();
        expectInterrupted(() -> UriCopyIntegrity.verify(metadata(3, 0), metadata(3, 0), 3));
    }

    @Test public void timestampZeroAndNegativeSizesRemainUnknown() throws Exception {
        UriCopyIntegrity.Metadata unknown = metadata(-50, -4);
        assertEquals(-1L, unknown.size);
        assertEquals(0L, unknown.lastModified);
        UriCopyIntegrity.verify(unknown, UriCopyIntegrity.Metadata.UNKNOWN, 3);
    }

    private static UriCopyIntegrity.Metadata metadata(long size, long time) {
        return new UriCopyIntegrity.Metadata(size, time);
    }

    private interface Work { void run() throws IOException; }

    private static void expectFailure(Work work) throws Exception {
        try { work.run(); fail("Expected incomplete or changed source rejection"); }
        catch (IOException expected) { }
    }

    private static void expectInterrupted(Work work) throws Exception {
        try { work.run(); fail("Expected cancellation"); }
        catch (InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
    }
}
