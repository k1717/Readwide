package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

public class ArchiveFailureClassifierTest {
    @Test
    public void classify_badPasswordMessagesDoNotBecomePasswordRequired() {
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD,
                ArchiveFailureClassifier.classify(new IOException("Wrong password")));
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD,
                ArchiveFailureClassifier.classify(new IOException("MAC check failed")));
    }

    @Test
    public void classify_unsupportedEncryptionDoesNotLoopPasswordPrompt() {
        assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE,
                ArchiveFailureClassifier.classify(new IOException("Unsupported encryption method")));
        assertEquals(ArchiveSupport.ExtractionFailure.UNSUPPORTED_FEATURE,
                ArchiveFailureClassifier.classify(new IOException("Unsupported password method")));
    }

    @Test
    public void classify_passwordRequiredStaysSeparate() {
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,
                ArchiveFailureClassifier.classify(new IOException("Archive requires password")));
    }

    @Test
    public void classify_corruptArchiveSignalsBoundary() {
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(new IOException("Invalid header in truncated archive")));
    }
}
