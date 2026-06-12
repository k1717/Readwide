package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

public class SevenZEncryptionFixtureQaTest {
    @Test
    public void passwordContextPromotesSevenZAesCorruptToBadPassword() throws Exception {
        File archive = writeSyntheticSevenZBytes("aes-corrupt.7z",
                new byte[] { 0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c,
                        0x06, (byte) 0xf1, 0x07, 0x01 });
        try {
            assertEquals(ArchiveSupport.ExtractionFailure.BAD_PASSWORD,
                    classifyWithContext(archive, "bad".toCharArray(),
                            new IOException("Compressed data is corrupt")));
        } finally {
            archive.delete();
        }
    }

    @Test
    public void passwordContextDoesNotPromotePlainCorruptSevenZ() throws Exception {
        File archive = writeSyntheticSevenZBytes("plain-corrupt.7z",
                new byte[] { 0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c,
                        0x00, 0x00, 0x00, 0x00 });
        try {
            assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                    classifyWithContext(archive, "irrelevant".toCharArray(),
                            new IOException("Compressed data is corrupt")));
        } finally {
            archive.delete();
        }
    }

    @Test
    public void rawSevenZAesCoderScanIsBoundedAndSpecific() throws Exception {
        File encrypted = writeSyntheticSevenZBytes("scan-aes.7z",
                new byte[] { 0x00, 0x01, 0x06, (byte) 0xf1, 0x07, 0x01, 0x02 });
        File plain = writeSyntheticSevenZBytes("scan-plain.7z",
                new byte[] { 0x00, 0x01, 0x06, (byte) 0xf1, 0x07, 0x02, 0x02 });
        try {
            Method method = ArchiveSupport.class.getDeclaredMethod(
                    "rawSevenZHeaderContainsAesCoder", File.class);
            method.setAccessible(true);
            assertTrue((Boolean) method.invoke(null, encrypted));
            assertFalse((Boolean) method.invoke(null, plain));
        } finally {
            encrypted.delete();
            plain.delete();
        }
    }

    @Test
    public void existingClassifierStillKeepsPasswordAndCorruptBoundaries() {
        assertEquals(ArchiveSupport.ExtractionFailure.PASSWORD_REQUIRED,
                ArchiveFailureClassifier.classify(new IOException(
                        "Cannot read encrypted content from 7z archive without a password")));
        assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                ArchiveFailureClassifier.classify(new IOException(
                        "Missing 7z split volume: book.7z.002")));
    }

    private static ArchiveSupport.ExtractionFailure classifyWithContext(File archive,
                                                                        char[] password,
                                                                        Exception error)
            throws Exception {
        Method method = ArchiveSupport.class.getDeclaredMethod(
                "classifyExtractionFailure",
                ArchiveSupport.Type.class,
                File.class,
                char[].class,
                Exception.class);
        method.setAccessible(true);
        return (ArchiveSupport.ExtractionFailure) method.invoke(null,
                ArchiveSupport.Type.SEVEN_Z,
                archive,
                password,
                error);
    }

    private static File writeSyntheticSevenZBytes(String name, byte[] bytes) throws IOException {
        File file = File.createTempFile(name, "");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }
}
