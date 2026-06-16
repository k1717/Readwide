package com.readwide.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.archive.ArchiveSupport;

import org.junit.Test;

import java.util.ArrayList;

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

}
