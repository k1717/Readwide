package com.textview.reader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.textview.reader.archive.ArchiveSupport;

import org.junit.Test;

public class ArchiveImageSequenceLoaderTest {
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
}
