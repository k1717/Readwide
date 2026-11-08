package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

public class ArchiveFailureClassifierTest {
    @Test public void zipCryptoHeaderRejectionIsBadPassword() {
        IOException failure = org.junit.Assert.assertThrows(AlzipZipCrypto.PasswordException.class,
                () -> AlzipZipCrypto.checked("wrong".toCharArray(), new byte[12], 255, true));
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, ArchiveFailureClassifier.classify(failure));
    }

    @Test public void wrappedZipCryptoRejectionDoesNotDependOnMessage() {
        IOException failure = new IOException("extract",
                new AlzipZipCrypto.PasswordException("No candidate accepted"));
        assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD, ArchiveFailureClassifier.classify(failure));
    }

    @Test public void zipCryptoRejectionStillYieldsToCancellation() {
        IOException failure = new AlzipZipCrypto.PasswordException("No candidate accepted");
        failure.addSuppressed(new java.io.InterruptedIOException("cancelled"));
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, ArchiveFailureClassifier.classify(failure));
    }

    @Test public void zipCryptoSourceIoFailureIsNotAPasswordRejection() {
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED,
                ArchiveFailureClassifier.classify(new IOException("Cannot read ALZ/EGG ZipCrypto payload")));
    }

    @Test public void cancellationWinsOverTypedPasswordWrapper() {
        ArchiveSupport.PasswordRequiredException failure = new ArchiveSupport.PasswordRequiredException();
        failure.initCause(new java.io.InterruptedIOException("cancelled"));
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, ArchiveFailureClassifier.classify(failure));
    }

    @Test public void suppressedCancellationWinsOverTypedUnsupportedWrapper() {
        ArchiveSupport.UnsupportedArchiveFeatureException failure =
                new ArchiveSupport.UnsupportedArchiveFeatureException("unsupported encryption");
        failure.addSuppressed(new IOException("cleanup", new java.io.InterruptedIOException("cancelled")));
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, ArchiveFailureClassifier.classify(failure));
    }

    @Test public void activeInterruptionDoesNotBecomeBadPassword() {
        Thread.currentThread().interrupt();
        try {
            assertEquals(ArchiveSupport.ExtractionFailure.FAILED,
                    ArchiveFailureClassifier.classify(new IOException("Wrong password")));
            org.junit.Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void cyclicSuppressedErrorsStillFindCancellation() {
        IOException failure = new IOException("Wrong password");
        IOException cleanup = new IOException("cleanup");
        failure.addSuppressed(cleanup);
        cleanup.addSuppressed(failure);
        cleanup.addSuppressed(new java.io.InterruptedIOException("cancelled"));
        assertEquals(ArchiveSupport.ExtractionFailure.FAILED, ArchiveFailureClassifier.classify(failure));
    }

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
