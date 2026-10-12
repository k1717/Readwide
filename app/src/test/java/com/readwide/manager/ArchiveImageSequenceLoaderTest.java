package com.readwide.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.archive.ArchiveSupport;
import com.readwide.manager.archive.ArchiveSourceSnapshot;

import org.junit.Test;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class ArchiveImageSequenceLoaderTest {
    @Test
    public void interruptedLoadDoesNotAccessContextOrArchiveOrTryAlternates() {
        ArchiveSupport.ExtractionResult failed = ArchiveSupport.ExtractionResult.failed(
                ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE, "unsupported member");
        Thread.currentThread().interrupt();
        try {
            assertFalse(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(failed));
            ArchiveImageSequenceLoader.Result lazy = ArchiveImageSequenceLoader.loadLazy(
                    null, new File("missing.zip"), new ArrayList<>(), 0, null);
            ArchiveImageSequenceLoader.Result full = ArchiveImageSequenceLoader.loadFully(
                    null, new File("missing.rar"), new ArrayList<>(), 0, null);
            assertFalse(lazy.selectedReady);
            assertFalse(full.selectedReady);
            assertTrue(lazy.imagePaths.isEmpty() && full.imagePaths.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(failed));
    }

    @Test
    public void interruptedCacheRequestLeavesExistingCacheUntouched() throws Exception {
        File archive = volumeFolder.newFile("cancelled.rar");
        File output = volumeFolder.newFile("page.jpg");
        byte[] original = {1, 2, 3};
        java.nio.file.Files.write(output.toPath(), original);
        Thread.currentThread().interrupt();
        try {
            ArchiveSupport.ExtractionResult result = ArchiveImageEntryCache.ensureReady(
                    archive, "page.jpg", output, null, true, new HashSet<>());
            assertFalse(result.success);
            assertTrue(Thread.currentThread().isInterrupted());
            try {
                ArchiveImageEntryCache.checkInterrupted();
                org.junit.Assert.fail("Cancellation checkpoint must reject interrupted work");
            } catch (java.io.InterruptedIOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {
            Thread.interrupted();
        }
        ArchiveImageEntryCache.checkInterrupted();
        org.junit.Assert.assertArrayEquals(original, java.nio.file.Files.readAllBytes(output.toPath()));
    }

    @org.junit.Rule public org.junit.rules.TemporaryFolder volumeFolder =
            new org.junit.rules.TemporaryFolder();

    @Test
    public void sequenceCarriesLoaderVolumeSnapshotWithoutRecapturingIt() throws Exception {
        File first = volumeFolder.newFile("comic.part1.rar");
        File second = volumeFolder.newFile("comic.part2.rar");
        ArchiveSourceSnapshot saved = ArchiveSourceSnapshot.capture(first);
        assertTrue(saved != null && saved.matches(first));
        ArchiveImageSequenceLoader.Result result = new ArchiveImageSequenceLoader.Result(
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0, true,
                null, null, null, first.getAbsolutePath(), first.length(), first.lastModified(), saved);
        java.nio.file.Files.write(second.toPath(), new byte[] {1, 2, 3});
        ImageSequenceHandoffStore.Sequence sequence = new ImageSequenceHandoffStore.Sequence(
                result.imagePaths, result.displayNames, result.entryPaths, null, null, null,
                result.archivePathSnapshot, result.archiveLengthSnapshot,
                result.archiveLastModifiedSnapshot, result.sourceSnapshot);
        assertFalse(sequence.matchesSourceArchiveSnapshot(first.getAbsolutePath()));
    }

    @Test
    public void rejectedVolumeHandoffStillClearsPasswordAndClosesResource() throws Exception {
        File first = volumeFolder.newFile("comic.7z.001");
        File second = volumeFolder.newFile("comic.7z.002");
        RecordingCloseable resource = new RecordingCloseable();
        ImageSequenceHandoffStore.Sequence sequence = new ImageSequenceHandoffStore.Sequence(
                new ArrayList<>(), null, null, new char[] {'x'}, resource, null,
                first.getAbsolutePath(), first.length(), first.lastModified(),
                ArchiveSourceSnapshot.capture(first));
        assertTrue(sequence.matchesSourceArchiveSnapshot(first.getAbsolutePath()));
        java.nio.file.Files.delete(second.toPath());
        assertFalse(sequence.matchesSourceArchiveSnapshot(first.getAbsolutePath()));
        sequence.clearSensitiveData();
        assertTrue(resource.closed && resource.closeCount == 1);
        assertTrue(sequence.archivePassword[0] == '\0');
    }

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

    @Test public void bufferedForwardDrainHasNoFormerTwoGiBCeiling() throws Exception {
        final long[] remaining = {2L * 1024 * 1024 * 1024 + 1};
        ArchiveSupport.ForwardArchiveReader reader = new ArchiveSupport.ForwardArchiveReader() {
            @Override public ArchiveSupport.ForwardEntry nextEntry() { return null; }
            @Override public int read(byte[] buffer) {
                if (remaining[0] == 0) return -1;
                int count = (int) Math.min(buffer.length, remaining[0]);
                remaining[0] -= count;
                return count;
            }
            @Override public void close() { }
        };
        SequentialArchiveImageReader.drainSkippedEntry(reader, new byte[64 * 1024], Long.MAX_VALUE);
        assertTrue(remaining[0] == 0);
    }

    @Test public void forwardChunkCancellationBeforeReadDoesNotTouchDecoder() throws Exception {
        RecordingForwardReader reader = new RecordingForwardReader(false, false);
        Thread.currentThread().interrupt();
        try {
            org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                    () -> SequentialArchiveImageReader.readEntryChunk(reader, new byte[8]));
            org.junit.Assert.assertEquals(0, reader.readCalls);
        } finally { Thread.interrupted(); }
    }

    @Test public void viewerFallbackRetainsWrappedAndSuppressedCancellation() {
        IOException primary = new IOException("decode");
        RuntimeException wrapper = new RuntimeException("callback", new java.io.InterruptedIOException("cancelled"));
        primary.addSuppressed(wrapper);
        wrapper.addSuppressed(primary); // Identity-based traversal must tolerate cycles.
        try {
            assertFalse(Thread.currentThread().isInterrupted());
            ArchiveImageEntryCache.preserveCancellation(primary);
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(ArchiveImageSequenceLoader.shouldTryAlternateImageEntry(
                    ArchiveSupport.ExtractionResult.failed(ArchiveSupport.ExtractionFailure.FAILED, "decode")));
        } finally { Thread.interrupted(); }
        ArchiveImageEntryCache.preserveCancellation(new IOException("ordinary failure"));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void forwardChunkCancellationAfterDataOrEofIsTerminal() throws Exception {
        for (int count : new int[]{1, -1}) {
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> SequentialArchiveImageReader.readEntryChunk(chunkReader(count, true), new byte[8]));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void forwardChunkRejectsInvalidProgressButKeepsValidEof() throws Exception {
        for (int count : new int[]{-2, 0, 9}) {
            org.junit.Assert.assertThrows(IOException.class,
                    () -> SequentialArchiveImageReader.readEntryChunk(chunkReader(count, false), new byte[8]));
        }
        org.junit.Assert.assertEquals(-1,
                SequentialArchiveImageReader.readEntryChunk(chunkReader(-1, false), new byte[8]));
        org.junit.Assert.assertEquals(8,
                SequentialArchiveImageReader.readEntryChunk(chunkReader(8, false), new byte[8]));
    }

    @Test public void forwardDrainChecksCancellationBeforeSkipOrDecode() throws Exception {
        RecordingForwardReader reader = new RecordingForwardReader(true, true);
        Thread.currentThread().interrupt();
        try {
            org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                    () -> SequentialArchiveImageReader.drainSkippedEntry(reader, new byte[8], Long.MAX_VALUE));
            org.junit.Assert.assertEquals(0, reader.drainCalls);
            org.junit.Assert.assertEquals(0, reader.readCalls);
        } finally { Thread.interrupted(); }
    }

    @Test public void backendDrainCancellationCannotBecomeSuccessOrBufferedRetry() throws Exception {
        for (boolean drained : new boolean[]{false, true}) {
            ArchiveSupport.ForwardArchiveReader reader = new ArchiveSupport.ForwardArchiveReader() {
                @Override public ArchiveSupport.ForwardEntry nextEntry() { return null; }
                @Override public int read(byte[] buffer) { throw new AssertionError("No read after cancellation"); }
                @Override public boolean drainCurrentEntry(long maximum) {
                    Thread.currentThread().interrupt(); return drained;
                }
                @Override public void close() { }
            };
            try {
                org.junit.Assert.assertThrows(java.io.InterruptedIOException.class,
                        () -> SequentialArchiveImageReader.drainSkippedEntry(reader, new byte[8], Long.MAX_VALUE));
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void cancelledCachePublicationPreservesExistingImageAndVerification() throws Exception {
        for (boolean duringValidation : new boolean[]{false, true}) {
            File root = volumeFolder.newFolder();
            File output = new File(root, "page.avif");
            File marker = new File(output.getPath() + ".ready");
            java.nio.file.Files.write(output.toPath(), new byte[]{9});
            java.nio.file.Files.write(marker.toPath(), new byte[]{7});
            File temporary = new File(root, "candidate");
            java.nio.file.Files.write(temporary.toPath(), new byte[]{0, 0, 0, 12, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'});
            File checked = duringValidation ? new File(temporary.getPath()) {
                @Override public long length() { Thread.currentThread().interrupt(); return super.length(); }
            } : temporary;
            Set<String> verified = new HashSet<>();
            try {
                if (!duringValidation) Thread.currentThread().interrupt();
                assertFalse(ArchiveImageEntryCache.commitReadyImageFile(
                        "page.avif", checked, output, true, verified));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            org.junit.Assert.assertArrayEquals(new byte[]{9}, java.nio.file.Files.readAllBytes(output.toPath()));
            org.junit.Assert.assertArrayEquals(new byte[]{7}, java.nio.file.Files.readAllBytes(marker.toPath()));
            assertFalse(temporary.exists());
            assertTrue(verified.isEmpty());
        }
    }

    private static ArchiveSupport.ForwardArchiveReader chunkReader(int count, boolean interrupt) {
        return new ArchiveSupport.ForwardArchiveReader() {
            @Override public ArchiveSupport.ForwardEntry nextEntry() { return null; }
            @Override public int read(byte[] buffer) {
                if (interrupt) Thread.currentThread().interrupt();
                return count;
            }
            @Override public void close() { }
        };
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
