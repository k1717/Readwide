package com.readwide.manager.archive;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import static org.junit.Assert.*;

/** Streaming checks reuse the independently generated plain fixture and its pinned digest. */
public class SevenZPpmdStreamingTest {
    private static final byte[] PROPERTIES = {6, 0, 0, 1, 0}; // Order 6, 64 KiB model.
    private static final int OUTPUT_SIZE = 31600;

    @Test public void chunkedAndSingleByteReadsMatchPinnedFixture() throws Exception {
        for (int chunk : new int[] {1, 17, 8192}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(
                    new ByteArrayInputStream(payload()), PROPERTIES, OUTPUT_SIZE)) {
                byte[] buffer = new byte[chunk];
                if (chunk == 1) {
                    int value;
                    while ((value = decoded.read()) != -1) out.write(value);
                } else {
                    int count;
                    while ((count = decoded.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                assertEquals(-1, decoded.read());
                assertEquals(0, decoded.read(buffer, 0, 0));
            }
            assertEquals(OUTPUT_SIZE, out.size());
            assertEquals("d8cb9b211ea4130bdace77b73a1cd345ee0fc3062356599c07534746851b1d73",
                    hash(out.toByteArray()));
        }
    }

    @Test public void arrayConvenienceRetainsPinnedOutput() throws Exception {
        assertEquals("d8cb9b211ea4130bdace77b73a1cd345ee0fc3062356599c07534746851b1d73",
                hash(SevenZPpmd7Decoder.decode(payload(), PROPERTIES, OUTPUT_SIZE)));
    }

    @Test public void creationIsLazyAndFirstReadDoesNotDrainPackedStream() throws Exception {
        TrackingInput packed = new TrackingInput(payload());
        try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(packed, PROPERTIES, OUTPUT_SIZE)) {
            assertEquals(0, packed.reads);
            assertEquals(0, decoded.read(new byte[2], 1, 0));
            assertEquals(0, packed.reads);
            assertTrue(decoded.read() >= 0);
            assertTrue(packed.reads < payload().length);
        }
        assertEquals(1, packed.closes);
    }

    @Test public void longDeclaredOutputDoesNotAllocateWholeResult() throws Exception {
        IOException sentinel = new IOException("first packed read");
        TrackingInput packed = new TrackingInput(payload());
        packed.failAfter = 0;
        packed.sentinel = sentinel;
        try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(
                packed, PROPERTIES, (long) Integer.MAX_VALUE + 1)) {
            assertEquals(0, packed.reads);
            try { decoded.read(); fail(); } catch (IOException expected) { assertSame(sentinel, expected); }
        }
        assertEquals(1, packed.closes);
    }

    @Test public void truncatedInitializationNeverSynthesizesZeroBytes() throws Exception {
        for (int size = 0; size < 5; size++) {
            try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(
                    new ByteArrayInputStream(new byte[size]), PROPERTIES, 1)) {
                try { decoded.read(); fail(); } catch (java.io.EOFException expected) { }
            }
        }
    }

    @Test public void invalidMaximumRangeCodeIsRejected() throws Exception {
        try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(
                new ByteArrayInputStream(new byte[] {0, -1, -1, -1, -1}), PROPERTIES, 1)) {
            try { decoded.read(); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("state invalid")); }
        }
    }

    @Test public void truncatedPayloadRetiresStream() throws Exception {
        byte[] complete = payload();
        TrackingInput packed = new TrackingInput(Arrays.copyOf(complete, complete.length / 2));
        try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(packed, PROPERTIES, OUTPUT_SIZE)) {
            IOException failure = null;
            try {
                byte[] buffer = new byte[4096];
                while (decoded.read(buffer) != -1) { }
                fail("Truncated stream must not fabricate its suffix");
            } catch (IOException expected) { failure = expected; }
            assertNotNull(failure);
            try { decoded.read(); fail(); } catch (IOException expected) { assertSame(failure, expected); }
        }
        assertEquals(1, packed.closes);
    }

    @Test public void ioFailureStaysStickyAndClosesInput() throws Exception {
        TrackingInput packed = new TrackingInput(payload());
        packed.failAfter = 12;
        packed.sentinel = new IOException("injected packed failure");
        try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(packed, PROPERTIES, OUTPUT_SIZE)) {
            try { decoded.read(new byte[OUTPUT_SIZE]); fail(); }
            catch (IOException expected) { assertSame(packed.sentinel, expected); }
            try { decoded.read(); fail(); }
            catch (IOException expected) { assertSame(packed.sentinel, expected); }
        }
        assertEquals(1, packed.closes);
    }

    @Test public void cancellationBeforeFirstReadDoesNotConsumeInput() throws Exception {
        TrackingInput packed = new TrackingInput(payload());
        try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(packed, PROPERTIES, OUTPUT_SIZE)) {
            Thread.currentThread().interrupt();
            try {
                try { decoded.read(); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
            } finally { Thread.interrupted(); }
            assertEquals(0, packed.reads);
        }
        assertEquals(1, packed.closes);
    }

    @Test public void closeAndInvalidReadArgumentsDoNotDecode() throws Exception {
        TrackingInput packed = new TrackingInput(payload());
        InputStream decoded = SevenZPpmd7Decoder.decodeStream(packed, PROPERTIES, OUTPUT_SIZE);
        try { decoded.read(new byte[2], 1, 2); fail(); } catch (IndexOutOfBoundsException expected) { }
        assertEquals(0, packed.reads);
        decoded.close(); decoded.close();
        try { decoded.read(); fail(); } catch (IOException expected) { }
        assertEquals(1, packed.closes);
        assertEquals(0, packed.reads);
    }

    @Test public void zeroOutputDoesNotCreateModelOrReadInput() throws Exception {
        TrackingInput packed = new TrackingInput(new byte[0]);
        try (InputStream decoded = SevenZPpmd7Decoder.decodeStream(packed, PROPERTIES, 0)) {
            assertEquals(-1, decoded.read());
            assertEquals(0, packed.reads);
        }
        assertEquals(1, packed.closes);
    }

    @Test public void invalidPropertiesLeaveInputOwnershipWithCaller() throws Exception {
        TrackingInput packed = new TrackingInput(payload());
        try { SevenZPpmd7Decoder.decodeStream(packed, new byte[] {1, 0, 0, 1, 0}, 1); fail(); }
        catch (IOException expected) { }
        assertEquals(0, packed.closes);
        assertEquals(0, packed.reads);
        packed.close();
    }

    private static byte[] payload() {
        byte[] archive = Base64.getDecoder().decode(Sevenz7PpmdFixtures.PLAIN_B64);
        long offset = 0;
        for (int i = 7; i >= 0; i--) offset = (offset << 8) | (archive[12 + i] & 255L);
        // This fixture has one pack stream at packPos=0 and an unencoded next header.
        assertTrue(offset >= 5 && offset < archive.length - 32);
        return Arrays.copyOfRange(archive, 32, 32 + (int) offset);
    }

    private static String hash(byte[] data) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
            text.append(String.format("%02x", value & 255));
        }
        return text.toString();
    }

    private static final class TrackingInput extends InputStream {
        final byte[] data;
        int reads, closes;
        int failAfter = Integer.MAX_VALUE;
        IOException sentinel;
        TrackingInput(byte[] data) { this.data = data; }
        @Override public int read() throws IOException {
            if (reads >= failAfter) throw sentinel;
            return reads < data.length ? data[reads++] & 255 : -1;
        }
        @Override public void close() { closes++; }
    }
}
