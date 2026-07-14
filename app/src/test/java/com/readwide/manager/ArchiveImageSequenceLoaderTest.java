package com.readwide.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.archive.ArchiveSupport;

import org.junit.Test;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class ArchiveImageSequenceLoaderTest {
    @Test
    public void sequenceHandoffDefensivelyCopiesArchivePassword() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/tmp/page.jpg");
        char[] password = "secret".toCharArray();

        ImageSequenceHandoffStore.Sequence sequence = new ImageSequenceHandoffStore.Sequence(
                paths,
                null,
                null,
                password);
        password[0] = 'X';

        assertTrue(sequence.archivePassword != null);
        assertTrue(sequence.archivePassword.length == 6);
        assertTrue(sequence.archivePassword[0] == 's');
    }

    @Test
    public void sequenceHandoffDefensivelyCopiesExactVerifiedSensitivePaths() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/tmp/page.jpg");
        Set<String> verified = new HashSet<>();
        verified.add("/tmp/verified-page.jpg");

        ImageSequenceHandoffStore.Sequence sequence = new ImageSequenceHandoffStore.Sequence(
                paths, null, null, null, null, verified);
        verified.clear();

        assertTrue(sequence.verifiedSensitivePaths.contains("/tmp/verified-page.jpg"));
        sequence.clearSensitiveData();
        assertTrue(sequence.verifiedSensitivePaths.isEmpty());
    }

    @Test
    public void archiveSequenceHandoffRejectsSourceChangedAfterLoaderSnapshot() throws Exception {
        File archive = File.createTempFile("readwide-sequence-handoff", ".rar");
        try {
            java.nio.file.Files.write(archive.toPath(), new byte[] {1, 2, 3});
            archive.setLastModified(1700000000000L);
            ArrayList<String> paths = new ArrayList<>();
            paths.add("/tmp/page.jpg");
            ImageSequenceHandoffStore.Sequence sequence = new ImageSequenceHandoffStore.Sequence(
                    paths,
                    null,
                    null,
                    null,
                    null,
                    null,
                    archive.getAbsolutePath(),
                    archive.length(),
                    archive.lastModified());

            assertTrue(sequence.matchesSourceArchiveSnapshot(archive.getAbsolutePath()));
            java.nio.file.Files.write(archive.toPath(), new byte[] {1, 2, 3, 4});
            archive.setLastModified(1700000001000L);
            assertFalse(sequence.matchesSourceArchiveSnapshot(archive.getAbsolutePath()));
            sequence.clearSensitiveData();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
    }

    @Test
    public void alternateImageEntryPolicyRetriesNonPasswordFailuresOnly() {
        assertTrue(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, null)));
        assertTrue(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, "unsupported")));

        assertFalse(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(null));
        assertFalse(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                ArchiveSupport.ExtractionResult.success()));
        assertFalse(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED, "password")));
    }

    @Test
    public void alternateImageEntryPolicyDoesNotScanEncryptedSequenceAfterFailedPasswordExtraction() {
        assertFalse(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, "bad password"),
                true));
        assertTrue(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, "unsupported"),
                true));
    }
    @Test
    public void badPasswordNeverFallsBack() {
        assertFalse(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, "bad password"),
                true));
    }

    @Test
    public void sequenceHandoffClosesUnclaimedPreparedReader() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/tmp/page.jpg");
        RecordingCloseable resource = new RecordingCloseable();
        ImageSequenceHandoffStore.Sequence sequence = new ImageSequenceHandoffStore.Sequence(
                paths, null, null, null, resource);

        sequence.clearSensitiveData();
        sequence.clearSensitiveData();

        assertTrue(resource.closed);
        assertTrue(resource.closeCount == 1);
    }

    @Test
    public void sequenceHandoffTransfersPreparedReaderOwnershipOnce() throws IOException {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/tmp/page.jpg");
        RecordingCloseable resource = new RecordingCloseable();
        ImageSequenceHandoffStore.Sequence sequence = new ImageSequenceHandoffStore.Sequence(
                paths, null, null, null, resource);

        Closeable claimed = sequence.takePreparedResource();
        assertTrue(claimed == resource);
        assertTrue(sequence.takePreparedResource() == null);
        sequence.clearSensitiveData();
        assertFalse(resource.closed);

        claimed.close();
        assertTrue(resource.closed);
    }

    @Test
    public void preparedReaderHandoffRejectsArchiveChangedAfterReaderCreation() throws Exception {
        File archive = File.createTempFile("readwide-handoff", ".rar");
        try {
            java.nio.file.Files.write(archive.toPath(), new byte[] {1, 2, 3});
            archive.setLastModified(1700000000000L);
            String pathSnapshot = archive.getAbsolutePath();
            long lengthSnapshot = archive.length();
            long modifiedSnapshot = archive.lastModified();

            assertTrue(SequentialArchiveImageReader.matchesArchiveSnapshot(
                    archive, pathSnapshot, lengthSnapshot, modifiedSnapshot));

            java.nio.file.Files.write(archive.toPath(), new byte[] {1, 2, 3, 4});
            archive.setLastModified(1700000001000L);
            assertFalse(SequentialArchiveImageReader.matchesArchiveSnapshot(
                    archive, pathSnapshot, lengthSnapshot, modifiedSnapshot));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
    }

    @Test
    public void skippedSolidEntryUsesBackendDecodeDrain() throws Exception {
        RecordingForwardReader reader = new RecordingForwardReader(false, true);

        SequentialArchiveImageReader.drainSkippedEntry(reader, new byte[8], 1024L);

        assertTrue(reader.drainCalls == 1);
        assertTrue(reader.readCalls == 0);
    }

    @Test
    public void skippedEntryFallsBackToBufferedDrainWhenBackendHasNoDrain() throws Exception {
        RecordingForwardReader reader = new RecordingForwardReader(false, false);

        SequentialArchiveImageReader.drainSkippedEntry(reader, new byte[8], 1024L);

        assertTrue(reader.drainCalls == 1);
        assertTrue(reader.readCalls == 2);
    }

    private static final class RecordingCloseable implements Closeable {
        boolean closed;
        int closeCount;

        @Override
        public void close() {
            closed = true;
            closeCount++;
        }
    }

    private static final class RecordingForwardReader
            implements ArchiveSupport.ForwardArchiveReader {
        final boolean skipWithoutDecode;
        final boolean backendDrain;
        int drainCalls;
        int readCalls;

        RecordingForwardReader(boolean skipWithoutDecode, boolean backendDrain) {
            this.skipWithoutDecode = skipWithoutDecode;
            this.backendDrain = backendDrain;
        }

        @Override
        public ArchiveSupport.ForwardEntry nextEntry() {
            return null;
        }

        @Override
        public int read(byte[] buffer) {
            readCalls++;
            return readCalls == 1 ? 4 : -1;
        }

        @Override
        public boolean drainCurrentEntry(long maxDecodedBytes) {
            drainCalls++;
            return backendDrain;
        }

        @Override
        public boolean skipsUnreadEntryOnAdvance() {
            return skipWithoutDecode;
        }

        @Override
        public void close() {
        }
    }

}
