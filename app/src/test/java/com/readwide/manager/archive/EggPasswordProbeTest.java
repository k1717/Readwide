package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.zip.CRC32;
import org.junit.Test;

public class EggPasswordProbeTest {
    @Test public void sourceFailuresRemainTerminalForEveryCodec() throws Exception {
        for (int method = 0; method <= 4; method++) {
            final int codec = method;
            for (Throwable failure : new Throwable[]{new IOException("source"), new InterruptedIOException("cancelled"),
                    new IllegalStateException("source runtime"), new AssertionError("source fatal")}) {
                int[] closes = {0};
                InputStream source = new InputStream() {
                    @Override public int read() throws IOException {
                        if (failure instanceof IOException) throw (IOException) failure;
                        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                        throw (Error) failure;
                    }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException { return read(); }
                    @Override public void close() { closes[0]++; }
                };
                assertSame(failure, assertThrows(failure.getClass(), () -> probe(source, codec, 32, 16, 1, null)));
                assertEquals(1, closes[0]);
            }
        }
    }

    @Test public void closeFailureOverridesMatchingOrRejectedContent() throws Exception {
        byte[] malformed = {6,0,0,0,0,0,0,0,0};
        for (int method = 0; method <= 4; method++) {
            final int codec = method;
            for (long expected : new long[]{crc(malformed), 1}) {
                IOException failure = new IOException("close"); int[] closes = {0};
                InputStream source = new ByteArrayInputStream(ciphertext(malformed)) {
                    @Override public void close() throws IOException { closes[0]++; throw failure; }
                };
                assertSame(failure, assertThrows(IOException.class, () -> probe(source, codec, malformed.length,
                        malformed.length, expected, null)));
                assertEquals(1, closes[0]);
            }
        }
    }

    @Test public void cancellationBeforeProbeClosesWithoutReading() throws Exception {
        for (boolean interrupted : new boolean[]{false, true}) {
            int[] reads = {0}, closes = {0};
            InputStream source = new InputStream() {
                @Override public int read() { reads[0]++; return -1; }
                @Override public void close() { closes[0]++; }
            };
            FileOperationProgress progress = new FileOperationProgress("probe", null);
            if (interrupted) Thread.currentThread().interrupt(); else progress.cancel();
            try {
                assertThrows(InterruptedIOException.class, () -> probe(source, 0, 0, 0, 1, progress));
                if (interrupted) assertTrue(Thread.currentThread().isInterrupted());
            } finally { if (interrupted) Thread.interrupted(); }
            assertEquals(0, reads[0]); assertEquals(1, closes[0]);
        }
    }

    @Test public void malformedPayloadRejectsOnlyThatCandidate() throws Exception {
        byte[] malformed = {6,0,7,0,(byte)255,0,0,0,0};
        for (int codec : new int[]{1,2,3,4}) {
            assertFalse(probe(new ByteArrayInputStream(ciphertext(malformed)), codec, malformed.length, 16, 1, null));
        }
    }

    @Test public void probesRequireExactLengthAndCrcWithoutPublishingProgress() throws Exception {
        byte[] plain = {1,2,3};
        FileOperationProgress progress = new FileOperationProgress("probe", null);
        assertTrue(probe(new ByteArrayInputStream(ciphertext(plain)), 0, 3, 3, crc(plain), progress));
        assertEquals(0, progress.snapshot().doneBytes);
        for (long size : new long[]{2,4}) {
            assertFalse(probe(new ByteArrayInputStream(ciphertext(plain)), 0, 3, size, crc(plain), progress));
        }
        assertFalse(probe(new ByteArrayInputStream(ciphertext(plain)), 0, 3, 3, crc(plain) ^ 1, progress));
    }

    @Test public void packedTailIsConsumedBeforeTheNextCipherBlock() throws Exception {
        byte[] plain = {1,2,3}, next = {4,5,6,7};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(6, true);
        try (java.util.zip.DeflaterOutputStream output = new java.util.zip.DeflaterOutputStream(bytes, deflater)) {
            output.write(plain);
        } finally { deflater.end(); }
        byte[] packed = java.util.Arrays.copyOf(bytes.toByteArray(), bytes.size() + 70001);
        AlzipZipCryptoTest.Encryptor encryptor = new AlzipZipCryptoTest.Encryptor("probe".toCharArray());
        byte[] firstCipher = encryptor.encrypt(packed), nextCipher = encryptor.encrypt(next);
        AlzipZipCrypto crypto = new AlzipZipCrypto("probe".toCharArray());
        try {
            assertTrue(EggArchiveReader.verifyPasswordBlock(new ByteArrayInputStream(firstCipher), 1, packed.length,
                    plain.length, crc(plain), crypto, null));
            assertTrue(EggArchiveReader.verifyPasswordBlock(new ByteArrayInputStream(nextCipher), 0, next.length,
                    next.length, crc(next), crypto, null));
        } finally { crypto.destroy(); }
    }

    @Test public void invalidSourceReadCountsCannotBecomeCandidateRejection() {
        for (int kind : new int[]{0,1,2}) {
            InputStream source = new InputStream() {
                @Override public int read() { return -1; }
                @Override public int read(byte[] bytes, int offset, int length) {
                    return kind == 0 ? 0 : kind == 1 ? length + 1 : -2;
                }
            };
            assertThrows(IOException.class, () -> probe(source, 0, 3, 3, 1, null));
        }
    }

    @Test public void unsupportedMethodAndPhysicalSizeErrorsStayTerminal() {
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> probe(new ByteArrayInputStream(new byte[0]), 99, 0, 0, 1, null));
        assertThrows(IOException.class, () -> probe(new ByteArrayInputStream(new byte[0]), 4, 8, 0, 1, null));
        assertThrows(IOException.class, () -> probe(new ByteArrayInputStream(new byte[0]), 0, -1, 0, 1, null));
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> probe(new ByteArrayInputStream(new byte[0]), 3, Integer.MAX_VALUE, 0, 1, null));
    }

    private static boolean probe(InputStream input, int method, long packed, long unpacked, long crc,
                                  FileOperationProgress progress) throws IOException {
        AlzipZipCrypto crypto = new AlzipZipCrypto("probe".toCharArray());
        try { return EggArchiveReader.verifyPasswordBlock(input, method, packed, unpacked, crc, crypto, progress); }
        finally { crypto.destroy(); }
    }

    @Test public void azoPackedReadCancellationIsTerminalAndClosesOnce() throws Exception {
        byte[] plain = new byte[65537];
        FileOperationProgress progress = new FileOperationProgress("probe", null);
        int[] reads = {0}, closes = {0}, requested = {0};
        InputStream source = new ByteArrayInputStream(ciphertext(plain)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads[0]++; requested[0] = length;
                int count = super.read(bytes, offset, length);
                progress.cancel();
                return count;
            }
            @Override public void close() { closes[0]++; }
        };
        assertThrows(InterruptedIOException.class,
                () -> probe(source, 3, plain.length, plain.length, crc(plain), progress));
        assertEquals(1, reads[0]); assertEquals(65536, requested[0]); assertEquals(1, closes[0]);
        assertEquals(0, progress.snapshot().doneBytes);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void azoChunkedReadsPreserveCipherStateForNextBlock() throws Exception {
        byte[] plain = new byte[65537], next = {4, 5, 6, 7};
        for (int i = 0; i < plain.length; i++) plain[i] = (byte) i;
        AlzipZipCryptoTest.Encryptor encryptor = new AlzipZipCryptoTest.Encryptor("probe".toCharArray());
        byte[] firstCipher = encryptor.encrypt(plain), nextCipher = encryptor.encrypt(next);
        AlzipZipCrypto crypto = new AlzipZipCrypto("probe".toCharArray());
        FileOperationProgress progress = new FileOperationProgress("probe", null);
        int[] reads = {0};
        InputStream source = new ByteArrayInputStream(firstCipher) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                assertTrue(length <= 65536); reads[0]++;
                return super.read(bytes, offset, length);
            }
        };
        try {
            assertTrue(EggArchiveReader.verifyPasswordBlock(source, 3, plain.length,
                    plain.length, crc(plain), crypto, progress));
            assertEquals(3, reads[0]); // Two packed chunks and the existing EOF drain check.
            assertTrue(EggArchiveReader.verifyPasswordBlock(new ByteArrayInputStream(nextCipher), 0,
                    next.length, next.length, crc(next), crypto, progress));
            assertEquals(0, progress.snapshot().doneBytes);
        } finally { crypto.destroy(); }
    }
    private static byte[] ciphertext(byte[] bytes) {
        return new AlzipZipCryptoTest.Encryptor("probe".toCharArray()).encrypt(bytes);
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
}
