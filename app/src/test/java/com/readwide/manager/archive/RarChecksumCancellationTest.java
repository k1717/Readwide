package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.zip.CRC32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RarChecksumCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cancelledVerificationDoesNotDeleteMismatchingInput() throws Exception {
        File output = file(new byte[]{42});
        FileOperationProgress progress = cancelled();
        assertThrows(InterruptedIOException.class,
                () -> RarStoredPayloadIO.verifyCrc(entry(0), output, null, progress));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void noChecksumShortcutStillHonorsCancellation() throws Exception {
        File output = file(new byte[]{42});
        assertThrows(InterruptedIOException.class,
                () -> RarStoredPayloadIO.verifyCrc(entry(-1), output, null, cancelled()));
        assertTrue(output.exists());
    }

    @Test public void emptyFileVerificationHonorsThreadInterruptionWithoutToken() throws Exception {
        File output = file(new byte[0]);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> RarStoredPayloadIO.verifyCrc(entry(0), output));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertTrue(output.exists());
    }

    @Test public void cancellationDuringInputOpeningStopsBeforeChecksumMismatch() throws Exception {
        File output = file(new byte[]{42});
        FileOperationProgress progress = new FileOperationProgress("verify", null);
        File cancelOnOpen = new File(output.getPath()) {
            @Override public String getPath() {
                progress.cancel();
                return super.getPath();
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> RarStoredPayloadIO.verifyCrc(entry(0), cancelOnOpen, null, progress));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void verificationCancellationAllowsOutputGuardToRestoreOriginal() throws Exception {
        File output = file(new byte[]{42});
        assertThrows(InterruptedIOException.class, () -> {
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(output)) {
                Files.write(output.toPath(), new byte[]{7});
                RarStoredPayloadIO.verifyCrc(entry(0), output, null, cancelled());
                guard.commit();
            }
        });
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void validAndMismatchingChecksumsRetainExistingBehavior() throws Exception {
        byte[] bytes = {1, 2, 3};
        CRC32 crc = new CRC32();
        crc.update(bytes);
        File output = file(bytes);
        RarStoredPayloadIO.verifyCrc(entry(crc.getValue()), output, null,
                new FileOperationProgress("verify", null));
        assertArrayEquals(bytes, Files.readAllBytes(output.toPath()));
        assertThrows(IOException.class, () -> RarStoredPayloadIO.verifyCrc(entry(crc.getValue() ^ 1), output));
        assertFalse(output.exists());
    }

    private File file(byte[] bytes) throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static FileOperationProgress cancelled() {
        FileOperationProgress progress = new FileOperationProgress("verify", null);
        progress.cancel();
        return progress;
    }

    private static RarArchiveReader.RarEntry entry(long crc) {
        return new RarArchiveReader.RarEntry("entry.bin", false, 0, 0, 0,
                5, 0, false, false, false, null, crc, 0, 0);
    }
}
