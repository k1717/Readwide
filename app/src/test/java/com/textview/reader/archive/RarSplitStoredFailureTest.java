package com.textview.reader.archive;

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
