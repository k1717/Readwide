package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

public class SevenZSplitEncryptionBoundaryTest {
    @Test
    public void encryptedSevenZMessagesClassifyAsPasswordRequired() {
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,
                ArchiveFailureClassifier.classify(new IOException(
                        "Cannot read encrypted content from 7z archive without a password")));
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,
                ArchiveFailureClassifier.classify(new IOException(
                        "Encrypted header requires password")));
    }

    @Test
    public void corruptOrMissingSevenZVolumeMessagesDoNotBecomePasswordRequired() {
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(new IOException(
                        "Missing 7z split volume: book.7z.002")));
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(new IOException(
                        "Truncated 7z split chain")));
    }
}
