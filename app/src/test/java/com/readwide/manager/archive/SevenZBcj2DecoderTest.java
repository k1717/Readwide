package com.readwide.manager.archive;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Streaming boundary regressions; no large file allocation is needed. */
public class SevenZBcj2DecoderTest {
    @Test public void longOutputSizeIsLazyAndDoesNotAllocateOutputArray() throws Exception {
        TrackingInput main = new TrackingInput(new byte[] {65, 66, 67});
        TrackingInput control = new TrackingInput(new byte[5]);
        try (InputStream decoded = SevenZBcj2Decoder.decodeStream(main, empty(), empty(),
                control, (long) Integer.MAX_VALUE + 1)) {
            assertEquals(0, main.reads);
            assertEquals(0, control.reads);
            byte[] prefix = new byte[3];
            assertEquals(3, decoded.read(prefix));
            assertArrayEquals(new byte[] {65, 66, 67}, prefix);
            assertEquals(3, main.reads);
        }
        assertTrue(main.closed);
        assertTrue(control.closed);
    }

    @Test public void convertedCallSurvivesSmallReads() throws Exception {
        try (InputStream decoded = SevenZBcj2Decoder.decodeStream(
                bytes(0xe8), bytes(0, 0, 0, 5), empty(), convertedControl(), 5)) {
            assertEquals(0xe8, decoded.read());
            byte[] two = new byte[2];
            assertEquals(2, decoded.read(two));
            assertArrayEquals(new byte[2], two);
            assertEquals(2, decoded.read(two));
            assertArrayEquals(new byte[2], two);
            assertEquals(-1, decoded.read());
        }
    }

    @Test public void convertedJumpUsesJumpStreamAndWrapsRelativeAddress() throws Exception {
        assertArrayEquals(new byte[] {(byte) 0xe9, (byte) 0xfb, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                SevenZBcj2Decoder.decode(new byte[] {(byte) 0xe9}, new byte[0], new byte[4],
                        new byte[] {0, 0x7f, (byte) 0xff, (byte) 0xfc, 0}, 5));
    }

    @Test public void truncatedControlMainAndAddressAreIoFailures() throws Exception {
        expectFailure(bytes(65), empty(), new byte[4], 1);
        expectFailure(empty(), empty(), new byte[5], 1);
        expectFailure(bytes(0xe8), bytes(0, 0, 0),
                new byte[] {0, 0x7f, (byte) 0xff, (byte) 0xfc, 0}, 5);
        expectFailure(bytes(0xe8), bytes(0, 0, 0, 5),
                new byte[] {0, 0x7f, (byte) 0xff, (byte) 0xfc, 0}, 4);
    }

    @Test public void closeReleasesEveryInputEvenIfOneCloseFails() throws Exception {
        TrackingInput main = new TrackingInput(new byte[0]) {
            @Override public void close() throws IOException { super.close(); throw new IOException("close"); }
        };
        TrackingInput call = new TrackingInput(new byte[0]);
        TrackingInput jump = new TrackingInput(new byte[0]);
        TrackingInput control = new TrackingInput(new byte[5]);
        InputStream decoded = SevenZBcj2Decoder.decodeStream(main, call, jump, control, 0);
        try { decoded.close(); fail("Expected close failure"); } catch (IOException expected) { }
        assertTrue(main.closed && call.closed && jump.closed && control.closed);
        decoded.close();
    }

    @Test public void folderOffsetsCrossIntegerBoundaryButRejectLongOverflow() throws Exception {
        assertEquals(6L * 1024 * 1024 * 1024,
                SevenZBcj2ArchiveReader.checkedStreamSum(3L * 1024 * 1024 * 1024, 3L * 1024 * 1024 * 1024));
        try { SevenZBcj2ArchiveReader.checkedStreamSum(Long.MAX_VALUE, 1); fail("overflow"); }
        catch (IOException expected) { }
        try { SevenZBcj2ArchiveReader.checkedStreamSum(0, -1); fail("negative"); }
        catch (IOException expected) { }
    }

    private static void expectFailure(InputStream main, InputStream call, byte[] control,
                                      long size) throws Exception {
        try (InputStream decoded = SevenZBcj2Decoder.decodeStream(main, call, empty(),
                new ByteArrayInputStream(control), size)) {
            while (decoded.read() != -1) { }
            fail("Expected malformed input to fail");
        } catch (IOException expected) { }
    }

    @Test public void reservedInitialRangeCodeIsRejectedEvenForEmptyOutput() throws Exception {
        try (InputStream decoded = SevenZBcj2Decoder.decodeStream(empty(), empty(), empty(), bytes(0,255,255,255,255), 0)) {
            org.junit.Assert.assertThrows(IOException.class, () -> decoded.read());
        }
    }

    @Test public void truncatedAddressFailureIsStickyAfterPartialStateChange() throws Exception {
        try (InputStream decoded = SevenZBcj2Decoder.decodeStream(bytes(0xe8,65), bytes(0,0,0), empty(), convertedControl(), 5)) {
            IOException first = org.junit.Assert.assertThrows(IOException.class, () -> decoded.read());
            org.junit.Assert.assertSame(first, org.junit.Assert.assertThrows(IOException.class, () -> decoded.read()));
        }
    }

    @Test public void interruptionStopsPendingAddressAndRemainsTerminal() throws Exception {
        try (InputStream decoded = SevenZBcj2Decoder.decodeStream(bytes(0xe8), bytes(0,0,0,5), empty(), convertedControl(), 5)) {
            assertEquals(0xe8, decoded.read());
            Thread.currentThread().interrupt();
            try { org.junit.Assert.assertThrows(java.io.InterruptedIOException.class, () -> decoded.read()); }
            finally { Thread.interrupted(); }
            org.junit.Assert.assertThrows(java.io.InterruptedIOException.class, () -> decoded.read());
        }
    }

    @Test public void uncheckedCloseFailureStillClosesAllInputs() throws Exception {
        TrackingInput main = new TrackingInput(new byte[0]) {
            @Override public void close() throws IOException { super.close(); throw new IllegalStateException("close"); }
        };
        TrackingInput call = new TrackingInput(new byte[0]), jump = new TrackingInput(new byte[0]), rc = new TrackingInput(new byte[5]);
        InputStream decoded = SevenZBcj2Decoder.decodeStream(main, call, jump, rc, 0);
        org.junit.Assert.assertThrows(IllegalStateException.class, decoded::close);
        assertTrue(main.closed && call.closed && jump.closed && rc.closed);
        decoded.close();
    }

    @Test public void sharedCloseFailureDoesNotAbortRemainingCloses() throws Exception {
        IOException shared = new IOException("shared");
        TrackingInput throwing = new TrackingInput(new byte[0]) {
            @Override public void close() throws IOException { super.close(); throw shared; }
        };
        TrackingInput rc = new TrackingInput(new byte[5]);
        InputStream decoded = SevenZBcj2Decoder.decodeStream(throwing, throwing, throwing, rc, 0);
        org.junit.Assert.assertSame(shared, org.junit.Assert.assertThrows(IOException.class, decoded::close));
        assertTrue(rc.closed);
    }

    private static InputStream empty() { return bytes(); }
    private static InputStream convertedControl() { return bytes(0, 127, 255, 252, 0); }
    private static InputStream bytes(int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) data[i] = (byte) values[i];
        return new ByteArrayInputStream(data);
    }
    private static class TrackingInput extends ByteArrayInputStream {
        int reads;
        boolean closed;
        TrackingInput(byte[] data) { super(data); }
        @Override public synchronized int read() { reads++; return super.read(); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
