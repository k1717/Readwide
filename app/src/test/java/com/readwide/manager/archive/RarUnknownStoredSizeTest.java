package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

/** Tail/accounting regressions, not real-producer interoperability evidence. */
public class RarUnknownStoredSizeTest {
    @Test public void everyAesTailLengthIsChunkIndependentAndKeepsRealZeros() throws Exception {
        for (int length : new int[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,31,32,33,65537}) {
            byte[] plain = RarBlake2spTest.sequence(length);
            if (length > 1) plain[length - 1] = 0;
            byte[] padded = padded(plain);
            for (int step : new int[]{1,7,16,65536}) {
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                FileOperationProgress progress = new FileOperationProgress("RAR", null);
                try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                        actual, entry(crc(plain), null, 0, padded.length), padded.length, null, progress)) {
                    for (int at = 0; at < padded.length; at += step) {
                        output.write(padded, at, Math.min(step, padded.length - at));
                    }
                    assertEquals(Math.max(0, padded.length - 16), actual.size());
                    output.finish();
                    assertThrows(IOException.class, () -> output.write(1));
                }
                assertArrayEquals(plain, actual.toByteArray());
                assertEquals(plain.length, progress.snapshot().doneBytes);
            }
        }
    }

    @Test public void blakeOnlyCombinedChecksAndHashMacSelectTheSameLength() throws Exception {
        byte[] plain = RarBlake2spTest.sequence(65), padded = padded(plain);
        byte[] hash = RarBlake2spTest.hex("fff24d3cc729d395daf978b0157306cb495797e6c8dca1731d2f6f81b849baae");
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets("pw".toCharArray(), 1, new byte[16]);
        for (int flags : new int[]{0,2}) {
            for (boolean includeCrc : new boolean[]{false,true}) {
                long checksum = includeCrc ? (flags == 0 ? crc(plain) : Rar5Crypto.tweakCrc32(crc(plain), secrets)) : -1;
                byte[] expectedHash = flags == 0 ? hash : Rar5Crypto.tweakBlake2sp(hash, secrets);
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                        actual, entry(checksum, expectedHash, flags, padded.length), padded.length, secrets, null)) {
                    output.write(padded); output.finish();
                }
                assertArrayEquals(plain, actual.toByteArray());
            }
        }
    }

    @Test public void matchingCrcCannotHideBadBlakeHash() throws Exception {
        byte[] plain = RarBlake2spTest.sequence(65), padded = padded(plain);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                actual, entry(crc(plain), new byte[32], 0, padded.length), padded.length, null, null)) {
            output.write(padded);
            assertThrows(ArchiveSupport.PasswordRequiredException.class, output::finish);
            assertEquals(64, actual.size()); // Last block never published; caller rolls back prefix.
        }
    }

    @Test public void multipleChecksumMatchesAreRejectedRatherThanChoosingFirst() throws Exception {
        byte[] padded = new byte[16]; Arrays.fill(padded, 0, 4, (byte) 0xff);
        // CRC32(FF FF FF FF) == FFFFFFFF, unchanged by appending zero bytes.
        assertEquals(0xffffffffL, crc(Arrays.copyOf(padded, 4)));
        assertEquals(0xffffffffL, crc(Arrays.copyOf(padded, 5)));
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                actual, entry(0xffffffffL, null, 0, 16), 16, null, null)) {
            output.write(padded);
            IOException failure = assertThrows(IOException.class, output::finish);
            assertTrue(failure.getMessage().contains("Ambiguous")); assertEquals(0, actual.size());
            assertSame(failure, assertThrows(IOException.class, output::finish));
        }
    }

    @Test public void passwordCheckAloneDoesNotResolveSizeAndAlignmentStillMatters() throws Exception {
        for (int flags : new int[]{0,1,3}) {
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> new RarStoredPayloadIO.UnknownSizeOutput(
                    actual, entry(-1, null, flags, 16), 16, null, null));
            assertEquals(0, actual.size());
        }
        assertThrows(IOException.class, () -> new RarStoredPayloadIO.UnknownSizeOutput(
                new ByteArrayOutputStream(), entry(0, null, 0, 15), 15, null, null));
    }

    @Test public void blakeHashCanDisambiguateMatchingCrcPrefixes() throws Exception {
        byte[] padded = new byte[16]; Arrays.fill(padded, 0, 4, (byte) 0xff);
        byte[] plain = Arrays.copyOf(padded, 4);
        RarBlake2sp hash = new RarBlake2sp(); hash.update(plain, 0, plain.length);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                actual, entry(0xffffffffL, hash.digest(), 0, 16), 16, null, null)) {
            output.write(padded); output.finish();
        }
        assertArrayEquals(plain, actual.toByteArray());
    }

    @Test public void incompleteOrExcessDecryptedBytesFailSticky() throws Exception {
        for (int length : new int[]{15,17}) {
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                    actual, entry(0, null, 0, 16), 16, null, null)) {
                IOException failure;
                if (length == 15) { output.write(new byte[length]); failure = assertThrows(IOException.class, output::finish); }
                else failure = assertThrows(IOException.class, () -> output.write(new byte[length]));
                assertSame(failure, assertThrows(IOException.class, () -> output.write(1)));
                assertEquals(0, actual.size());
            }
        }
    }

    @Test public void cancellationAndDestinationErrorsCannotBecomeChecksumMismatch() throws Exception {
        try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                new ByteArrayOutputStream(), entry(0, null, 0, 16), 16, null, null)) {
            Thread.currentThread().interrupt();
            IOException failure;
            try { failure = assertThrows(java.io.InterruptedIOException.class, () -> output.write(1)); }
            finally { Thread.interrupted(); }
            assertSame(failure, assertThrows(IOException.class, output::finish));
        }
        IOException sentinel = new IOException("destination failed");
        java.io.OutputStream target = new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw sentinel; }
        };
        try (RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                target, entry(0, null, 0, 32), 32, null, null)) {
            assertSame(sentinel, assertThrows(IOException.class, () -> output.write(new byte[32])));
            assertSame(sentinel, assertThrows(IOException.class, output::finish));
        }
    }

    @Test public void closeAbortsWithoutFinalizingOrClosingCallerDestination() throws Exception {
        boolean[] closed = {false};
        ByteArrayOutputStream target = new ByteArrayOutputStream() {
            @Override public void close() { closed[0] = true; }
        };
        RarStoredPayloadIO.UnknownSizeOutput output = new RarStoredPayloadIO.UnknownSizeOutput(
                target, entry(crc(new byte[]{42}), null, 0, 16), 16, null, null);
        output.write(padded(new byte[]{42})); output.close();
        assertEquals(0, target.size()); assertFalse(closed[0]);
        assertThrows(IOException.class, output::finish);
    }

    private static RarArchiveReader.RarEntry entry(long crc, byte[] hash, int flags, int packed) {
        RarArchiveReader.EncryptionInfo encryption = new RarArchiveReader.EncryptionInfo(
                0, flags, 1, new byte[16], new byte[16], (flags & 1) == 0 ? new byte[0] : new byte[12]);
        return new RarArchiveReader.RarEntry("stored.bin", false, -1, packed, 0, 5, 0,
                false, false, false, encryption, crc, 0, 0, hash);
    }

    private static byte[] padded(byte[] plain) {
        byte[] padded = Arrays.copyOf(plain, (plain.length + 15) & ~15);
        Arrays.fill(padded, plain.length, padded.length, (byte) 0xa5);
        return padded;
    }

    private static long crc(byte[] plain) { CRC32 crc = new CRC32(); crc.update(plain); return crc.getValue(); }
}
