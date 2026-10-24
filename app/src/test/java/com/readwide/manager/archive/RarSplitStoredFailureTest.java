package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;

public class RarSplitStoredFailureTest {
    @Test
    public void classify_mapsStoredSplitValidationFailuresToStableCodes() {
        RarArchiveReader.RarEntry first = entry(false);

        assertEquals(
                RarSplitStoredFailure.Code.COMPRESSED_SPLIT_UNSUPPORTED,
                RarSplitStoredFailure.classify(first, null,
                        new RarArchiveReader.UnsupportedRarFeatureException(
                                "RAR compressed split payload is not supported by the stored split path")).code);
        assertEquals(
                RarSplitStoredFailure.Code.INCOMPLETE_CHAIN,
                RarSplitStoredFailure.classify(first, null,
                        new RarArchiveReader.UnsupportedRarFeatureException(
                                "Incomplete RAR split payload")).code);
        assertEquals(
                RarSplitStoredFailure.Code.MISSING_SOURCE_VOLUME,
                RarSplitStoredFailure.classify(first, null,
                        new IOException("RAR entry source volume is missing")).code);
    }

    @Test
    public void classify_preservesPasswordRequiredAsUserAction() {
        RarSplitStoredFailure failure = RarSplitStoredFailure.classify(
                entry(true),
                null,
                new ArchiveSupport.PasswordRequiredException());

        assertEquals(RarSplitStoredFailure.Code.PASSWORD_REQUIRED, failure.code);
        assertEquals(RarSplitStoredFailure.Kind.USER_ACTION, failure.kind);
    }

    @Test
    public void wrap_keepsPasswordRequiredTypeButWrapsIoFailures() {
        IOException password = RarSplitStoredFailure.wrap(
                entry(true),
                null,
                new ArchiveSupport.PasswordRequiredException());
        IOException eof = RarSplitStoredFailure.wrap(entry(false), null, new EOFException("truncated"));

        assertTrue(password instanceof ArchiveSupport.PasswordRequiredException);
        assertTrue(eof instanceof RarSplitStoredFailure.ExtractionException);
        RarSplitStoredFailure.ExtractionException wrapped = (RarSplitStoredFailure.ExtractionException) eof;
        assertEquals(RarSplitStoredFailure.Code.IO_FAILURE, wrapped.failure().code);
        assertTrue(wrapped.getMessage().contains("RAR stored split extraction failed"));
    }

    @Test
    public void classify_storedSolidKeepsActualFailureCauseForBothRarVersions() {
        for (int version : new int[]{4, 5}) {
            RarArchiveReader.RarEntry first = solidEntry(version, version == 4 ? 0x30 : 0);
            assertEquals(RarSplitStoredFailure.Code.CRC_MISMATCH,
                    RarSplitStoredFailure.classify(first, null, new IOException("CRC mismatch")).code);
            assertEquals(RarSplitStoredFailure.Code.MISSING_SOURCE_VOLUME,
                    RarSplitStoredFailure.classify(first, null,
                            new IOException("RAR entry source volume is missing")).code);
            assertEquals(RarSplitStoredFailure.Code.INCOMPLETE_CHAIN,
                    RarSplitStoredFailure.classify(first, null,
                            new IOException("Incomplete RAR split payload")).code);
            assertEquals(RarSplitStoredFailure.Code.ENCRYPTION_PARAMETERS_CHANGED,
                    RarSplitStoredFailure.classify(first, null,
                            new IOException("RAR encrypted split parameters changed between volumes")).code);
            assertEquals(RarSplitStoredFailure.Code.MIXED_ENCRYPTION,
                    RarSplitStoredFailure.classify(first, null,
                            new IOException("Mixed encrypted and plain RAR split payload is not supported")).code);
            assertEquals(RarSplitStoredFailure.Code.DECRYPT_FAILED,
                    RarSplitStoredFailure.classify(first, null, new IOException("AES decrypt failed")).code);
            assertEquals(RarSplitStoredFailure.Code.IO_FAILURE,
                    RarSplitStoredFailure.classify(first, null, new EOFException("truncated")).code);
        }
    }

    @Test
    public void wrap_storedSolidPreservesPasswordAndCancellation() {
        for (int version : new int[]{4, 5}) {
            RarArchiveReader.RarEntry first = solidEntry(version, version == 4 ? 0x30 : 0);
            IOException password = new ArchiveSupport.PasswordRequiredException();
            IOException cancelled = new java.io.InterruptedIOException("cancelled");
            org.junit.Assert.assertSame(password, RarSplitStoredFailure.wrap(first, null, password));
            org.junit.Assert.assertSame(cancelled, RarSplitStoredFailure.wrap(first, null, cancelled));
            assertEquals(RarSplitStoredFailure.Code.PASSWORD_REQUIRED,
                    RarSplitStoredFailure.classify(first, null, password).code);
            assertEquals(RarSplitStoredFailure.Code.CANCELLED,
                    RarSplitStoredFailure.classify(first, null, cancelled).code);
        }
    }

    @Test
    public void classify_compressedSolidAndExplicitUnsupportedMessagesRemainDistinct() {
        for (int version : new int[]{4, 5}) {
            RarArchiveReader.RarEntry compressed = solidEntry(version, version == 4 ? 0x33 : 3);
            assertEquals(RarSplitStoredFailure.Code.SOLID_SPLIT_UNSUPPORTED,
                    RarSplitStoredFailure.classify(compressed, null,
                            new RarArchiveReader.UnsupportedRarFeatureException("Unsupported solid payload")).code);
            assertEquals(RarSplitStoredFailure.Code.COMPRESSED_SPLIT_UNSUPPORTED,
                    RarSplitStoredFailure.classify(compressed, null,
                            new RarArchiveReader.UnsupportedRarFeatureException(
                                    "RAR compressed split payload is not supported by the stored split path")).code);
        }
        assertEquals(RarSplitStoredFailure.Code.SOLID_SPLIT_UNSUPPORTED,
                RarSplitStoredFailure.classify(null, null,
                        new IOException("Solid RAR split payload is not supported")).code);
    }

    private static RarArchiveReader.RarEntry solidEntry(int version, int method) {
        return new RarArchiveReader.RarEntry("file.bin", false, 10L, 5L, 0L,
                version, method, true, false, true, null, 0L, 0L);
    }

    private static RarArchiveReader.RarEntry entry(boolean encrypted) {
        RarArchiveReader.EncryptionInfo encryption = encrypted
                ? RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[] {1,2,3,4,5,6,7,8})
                : null;
        return new RarArchiveReader.RarEntry(
                "file.bin",
                false,
                10L,
                5L,
                0L,
                4,
                0x30,
                false,
                false,
                true,
                encryption,
                0L,
                0L);
    }
}
