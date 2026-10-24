package com.readwide.manager.archive;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.zip.CRC32;
import org.junit.Test;

public class AlzPasswordProbeTest {
    @Test public void decoderFormatFailuresRejectOnlyThatCandidate() throws Exception {
        // Deflate BTYPE=3 and a non-BZip2 header are malformed decoded content.
        for (int method : new int[]{1, 2, 3}) {
            assertFalse(probe(encrypted(new byte[]{6,0,0,0}), method, 16, 0, null));
        }
    }

    @Test public void sourceFailuresRemainTerminalAndKeepTheirIdentity() throws Exception {
        for (int method : new int[]{0, 1, 2, 3}) {
            for (Throwable failure : new Throwable[]{new IOException("source"),
                    new InterruptedIOException("cancelled"), new IllegalStateException("source runtime"),
                    new AssertionError("source fatal")}) {
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
                assertSame(failure, assertThrows(failure.getClass(), () -> probe(source, method, 16, 0, null)));
                assertEquals(1, closes[0]);
            }
        }
    }

    @Test public void closeFailureOverridesMatchingAndRejectedCandidates() throws Exception {
        for (int method : new int[]{0, 1, 2, 3}) {
            for (long crc : new long[]{crc(new byte[]{6,0,0,0}), 0}) {
                IOException failure = new IOException("source close");
                int[] closes = {0};
                InputStream source = new ByteArrayInputStream(ciphertext(new byte[]{6,0,0,0})) {
                    @Override public void close() throws IOException { closes[0]++; throw failure; }
                };
                assertSame(failure, assertThrows(IOException.class, () -> probe(source, method, 4, crc, null)));
                assertEquals(1, closes[0]);
            }
        }
    }

    @Test public void cancellationBeforeDecodingClosesWithoutReading() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            int[] reads = {0}, closes = {0};
            InputStream source = new InputStream() {
                @Override public int read() { reads[0]++; return -1; }
                @Override public void close() { closes[0]++; }
            };
            FileOperationProgress progress = new FileOperationProgress("test", null);
            if (interrupt) Thread.currentThread().interrupt(); else progress.cancel();
            try {
                assertThrows(InterruptedIOException.class, () -> probe(source, 2, 0, 0, progress));
                if (interrupt) assertTrue(Thread.currentThread().isInterrupted());
            } finally { if (interrupt) Thread.interrupted(); }
            assertEquals(0, reads[0]);
            assertEquals(1, closes[0]);
        }
    }

    @Test public void invalidSourceCountsAreNotPasswordMismatches() {
        for (int kind : new int[]{0, 1, 2}) {
            InputStream source = new InputStream() {
                @Override public int read() { return -1; }
                @Override public int read(byte[] bytes, int offset, int length) {
                    return kind == 0 ? 0 : kind == 1 ? length + 1 : -2;
                }
            };
            assertThrows(IOException.class, () -> probe(source, 0, 16, 0, null));
        }
    }

    @Test public void unsupportedCodecDoesNotBecomeAnEncodingMismatch() {
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> probe(encrypted(new byte[]{1}), 99, 1, 0, null));
    }

    @Test public void verificationRequiresBothExactLengthAndCrc() throws Exception {
        byte[] plain = {1,2,3};
        assertTrue(probe(encrypted(plain), 0, plain.length, crc(plain), null));
        assertFalse(probe(encrypted(plain), 0, plain.length - 1, crc(plain), null));
        assertFalse(probe(encrypted(plain), 0, plain.length + 1, crc(plain), null));
        assertFalse(probe(encrypted(plain), 0, plain.length, crc(plain) ^ 1, null));
    }

    @Test public void obfuscatedProbeUsesDeclaredSizeAndValidatesCrc() throws Exception {
        byte[] plain = {1,2,3,4,5};
        byte[] packed = AlzObfuscatedDeflateInputStreamTest.dynamic(plain, plain.length);
        assertTrue(probe(encrypted(packed), 3, plain.length, crc(plain), null));
        assertFalse(probe(encrypted(packed), 3, plain.length, crc(plain) ^ 1, null));
        // Same order residue isolates decoded-length checking from table selection.
        assertFalse(probe(encrypted(packed), 3, plain.length + 16, crc(plain), null));
    }

    @Test public void trimmedBzipProbeKeepsTokenAndCipherCrcContract() throws Exception {
        byte[] plain = "ALZ bzip2 single block payload. ".repeat(40)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] packed = java.util.Base64.getDecoder().decode(Round4Fixtures.ALZ_BZ_SINGLE_BLOCK_B64);
        FileOperationProgress progress = new FileOperationProgress("test", null);
        assertTrue(probe(encrypted(packed), 1, plain.length, crc(plain), progress));
        assertFalse(probe(encrypted(packed), 1, plain.length, crc(plain) ^ 1, progress));
        assertEquals(0, progress.snapshot().doneBytes);
        assertFalse(progress.isCancelled());
    }

    @Test public void trimmedBzipProbeCancellationClosesOnceWithoutRejectingPassword() {
        byte[] packed = java.util.Base64.getDecoder().decode(Round4Fixtures.ALZ_BZ_SINGLE_BLOCK_B64);
        FileOperationProgress progress = new FileOperationProgress("test", null);
        int[] reads = {0}, closes = {0};
        InputStream source = new ByteArrayInputStream(ciphertext(packed)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                // First call is the three-byte DLZ probe; second feeds the decoder.
                if (++reads[0] == 2) progress.cancel();
                return count;
            }
            @Override public void close() { closes[0]++; }
        };
        long expectedSize = "ALZ bzip2 single block payload. ".length() * 40L;
        assertThrows(InterruptedIOException.class, () -> probe(source, 1, expectedSize, 0, progress));
        assertEquals(2, reads[0]);
        assertEquals(1, closes[0]);
        assertEquals(0, progress.snapshot().doneBytes);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static boolean probe(InputStream source, int method, long size, long crc,
                                  FileOperationProgress progress) throws IOException {
        AlzipZipCrypto crypto = new AlzipZipCrypto("probe".toCharArray());
        try { return AlzipArchiveReader.verifyPasswordPayload(source, method, crypto, size, crc, progress); }
        finally { crypto.destroy(); }
    }

    private static byte[] ciphertext(byte[] bytes) {
        return new AlzipZipCryptoTest.Encryptor("probe".toCharArray()).encrypt(bytes);
    }
    private static InputStream encrypted(byte[] bytes) { return new ByteArrayInputStream(ciphertext(bytes)); }
    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue();
    }
}
