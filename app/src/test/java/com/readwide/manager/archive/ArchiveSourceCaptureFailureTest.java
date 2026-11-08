package com.readwide.manager.archive;

import static org.junit.Assert.*;

import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveSourceCaptureFailureTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] OLD = {7, 8, 9};

    @Test public void failedRarCaptureRetainsTypedCauseAndFreshWrappers() throws Exception {
        File archive = temp.newFile("wrong password.part2.rar");
        ArchiveSourceSnapshot snapshot = ArchiveSourceSnapshot.capture(archive);
        IOException first = assertThrows(IOException.class,
                () -> snapshot.requireOutsideReplacementTree(temp.getRoot()));
        IOException second = assertThrows(IOException.class,
                () -> snapshot.requireOutsideReplacementTree(temp.getRoot()));
        assertNotSame(first, second);
        assertTrue(first.getCause() instanceof RarArchiveReader.IntegrityException);
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(first));
        assertTrue(first.getMessage(), first.getMessage().contains("incomplete"));
        assertFalse(snapshot.matchesKnownFiles(archive));
    }

    @Test public void repairedChainDoesNotRetroactivelyApproveFailedSnapshot() throws Exception {
        File first = temp.newFile("book.7z.001");
        temp.newFile("book.7z.003");
        ArchiveSourceSnapshot failed = ArchiveSourceSnapshot.capture(first);
        temp.newFile("book.7z.002");
        IOException cause = assertThrows(IOException.class,
                () -> failed.requireOutsideReplacementTree(new File(temp.getRoot(), "outside")));
        assertNotNull(cause.getCause());
        assertFalse(failed.matches(first));
        assertTrue(ArchiveSourceSnapshot.capture(first).matches(first));
    }

    @Test public void captureCancellationSurvivesClearedThreadFlag() throws Exception {
        File archive = temp.newFile("source.rar");
        ArchiveSourceSnapshot failed;
        try {
            failed = ArchiveSourceSnapshot.capture(cancelDuringCapture(archive));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        IOException failure = assertThrows(IOException.class,
                () -> failed.requireOutsideReplacementTree(temp.getRoot()));
        try {
            assertTrue(ArchiveCancellation.restoreIfCancelled(failure, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void missingFirstRarVolumeKeepsDiagnosticAndDestination() throws Exception {
        File archive = temp.newFile("wrong password unsupported.part2.rar");
        for (boolean overwrite : new boolean[]{false, true}) {
            reject(archive, overwrite, ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                    "incomplete", null);
        }
    }

    @Test public void missingMiddleRarVolumeFailsFromBothEntrypointsBeforeOutput() throws Exception {
        File first = temp.newFile("book.part1.rar"), last = temp.newFile("book.part3.rar");
        for (File archive : new File[]{first, last}) {
            for (boolean overwrite : new boolean[]{false, true}) {
                reject(archive, overwrite, ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                        "incomplete", null);
            }
        }
    }

    @Test public void missingSevenZVolumeIsNotADecryptionFailure() throws Exception {
        File archive = temp.newFile("book.7z.001");
        temp.newFile("book.7z.003");
        for (boolean overwrite : new boolean[]{false, true}) {
            reject(archive, overwrite, ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                    "Missing", null);
        }
    }

    @Test public void deniedCaptureRemainsFailureAndRetainsItsReason() throws Exception {
        File archive = temp.newFile("denied.rar");
        File denied = new File(archive.getPath()) {
            @Override public String getCanonicalPath() { throw new SecurityException("fixture access denied"); }
        };
        for (boolean overwrite : new boolean[]{false, true}) {
            reject(denied, overwrite, ArchiveSupport.ExtractionFailure.FAILED, "access denied", null);
        }
    }

    @Test public void cancelledCaptureCancelsTokenWithoutStartingExtraction() throws Exception {
        File archive = temp.newFile("cancelled.rar");
        for (boolean overwrite : new boolean[]{false, true}) {
            FileOperationProgress progress = new FileOperationProgress("archive", ignored -> {});
            try {
                reject(cancelDuringCapture(archive), overwrite, ArchiveSupport.ExtractionFailure.FAILED,
                        "cancel", progress);
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(progress.isCancelled());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void destinationAccessFailureDoesNotBecomeAPasswordRequest() throws Exception {
        File archive = temp.newFile("single.rar"), directory = temp.newFolder();
        Files.write(new File(directory, "keep").toPath(), OLD);
        File denied = new File(directory.getPath()) {
            @Override public File getCanonicalFile() throws IOException {
                throw new IOException("wrong password in destination name");
            }
        };
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, denied, true, "supplied".toCharArray(), null);
        assertFalse(result.success);
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, result.failure);
        assertEquals("wrong password in destination name", result.detail);
        assertArrayEquals(OLD, Files.readAllBytes(new File(directory, "keep").toPath()));
        assertArrayEquals(new String[]{"keep"}, directory.list());
    }

    private void reject(File archive, boolean overwrite, ArchiveSupport.ExtractionFailure expected,
                        String detail, FileOperationProgress progress) throws Exception {
        File parent = temp.newFolder(), output = new File(parent, "output");
        if (overwrite) {
            assertTrue(output.mkdir());
            Files.write(new File(output, "keep").toPath(), OLD);
        }
        byte[] source = Files.readAllBytes(archive.toPath());
        ArchiveSupport.ExtractionResult result = ArchiveSupport.extractArchiveDetailed(
                archive, output, overwrite, "supplied".toCharArray(), progress);
        assertFalse(result.success);
        // Check output preservation even when a failure category is wrong.
        assertArrayEquals(source, Files.readAllBytes(archive.toPath()));
        assertArrayEquals(overwrite ? new String[]{"output"} : new String[0], parent.list());
        if (overwrite) {
            assertArrayEquals(new String[]{"keep"}, output.list());
            assertArrayEquals(OLD, Files.readAllBytes(new File(output, "keep").toPath()));
        }
        assertEquals(result.detail, expected, result.failure);
        assertNotNull(result.detail);
        assertTrue(result.detail, result.detail.toLowerCase(java.util.Locale.ROOT)
                .contains(detail.toLowerCase(java.util.Locale.ROOT)));
    }

    private static File cancelDuringCapture(File archive) {
        return new File(archive.getPath()) {
            @Override public String getCanonicalPath() throws IOException {
                throw new InterruptedIOException("fixture capture cancelled");
            }
        };
    }
}
