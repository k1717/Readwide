package com.readwide.manager.archive;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import static org.junit.Assert.*;

public class Rar3PpmdStreamingTest {
    @Test public void invalidOrderAndMixedLzTableFailBeforeAllocatingModel() throws Exception {
        for (byte[] header : new byte[][]{{(byte)0xa0,0},{0}}) {
            try { new Rar3PpmdSolidStreamDecoder().decodeEntry(
                    new java.io.ByteArrayInputStream(header),1,new ByteArrayOutputStream()); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("model order")
                    || expected.getMessage().contains("mixed-mode")); }
        }
    }

    @Test public void interruptedPasswordDerivationStopsForBothRarGenerations() throws Exception {
        Thread.currentThread().interrupt();
        try {
            try { Rar3Crypto.deriveParameters("pw".toCharArray(),new byte[8]); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
            try { Rar5Crypto.deriveSecrets("pw".toCharArray(),1,new byte[16]); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
        } finally { Thread.interrupted(); }
    }

    @Test public void interruptedDecodeStopsBeforeReadingOrAllocatingModel() throws Exception {
        Thread.currentThread().interrupt();
        try {
            new Rar3PpmdSolidStreamDecoder().decodeEntry(new InputStream() {
                @Override public int read() { fail("Interrupted decoder must not read payload"); return -1; }
            }, 1, new ByteArrayOutputStream());
            fail("Expected interruption");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        } finally { Thread.interrupted(); }
    }

    @Test public void interruptedPackedReaderStopsWithoutProgressObject() throws Exception {
        RarPackedInputStream input = new RarPackedInputStream(java.util.Collections.emptyList(), null);
        Thread.currentThread().interrupt();
        try {
            try { input.read(); fail("Expected interruption even without progress callback"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
        } finally { Thread.interrupted(); input.close(); }
    }

    @Test public void rollingWindowWrapsAndSupportsOverlappingMatches() throws Exception {
        Rar3PpmdSolidStreamDecoder.HistoryWindow window = new Rar3PpmdSolidStreamDecoder.HistoryWindow(8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 12; i++) window.literal(i, out);
        window.match(8, 10, out);
        byte[] expected = {0,1,2,3,4,5,6,7,8,9,10,11,4,5,6,7,8,9,10,11,4,5};
        assertArrayEquals(expected, out.toByteArray());
        out.reset();
        window.match(1, 4, out);
        assertArrayEquals(new byte[] {5,5,5,5}, out.toByteArray());
    }

    @Test public void historyGrowthPreservesMatchAtGrowthBoundary() throws Exception {
        Rar3PpmdSolidStreamDecoder.HistoryWindow window = new Rar3PpmdSolidStreamDecoder.HistoryWindow(128 * 1024);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 65536; i++) window.literal(i & 255, out);
        out.reset();
        window.match(65536, 3, out);
        assertArrayEquals(new byte[] {0,1,2}, out.toByteArray());
    }

    @Test public void rejectsUnavailableHistoryBeforeWritingMatch() throws Exception {
        Rar3PpmdSolidStreamDecoder.HistoryWindow window = new Rar3PpmdSolidStreamDecoder.HistoryWindow(8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        window.literal(1, out);
        try { window.match(2, 4, out); fail("Invalid history"); } catch (IOException expected) { }
        assertEquals(1, out.size());
    }

    @Test public void largeDeclaredEntryReachesInputWithoutAllocatingOutput() throws Exception {
        IOException sentinel = new IOException("input read reached");
        try {
            new Rar3PpmdSolidStreamDecoder().decodeEntry(new InputStream() {
                @Override public int read() throws IOException { throw sentinel; }
            }, (long) Integer.MAX_VALUE + 1, new ByteArrayOutputStream());
            fail("Expected input failure");
        } catch (IOException expected) { assertSame(sentinel, expected); }
    }

    @Test public void rangeInitializationPropagatesIoFailure() throws Exception {
        IOException sentinel = new IOException("packed read failed");
        try {
            new Rar3PpmdSolidStreamDecoder().decodeEntry(new InputStream() {
                int position;
                @Override public int read() throws IOException {
                    if (position++ == 0) return 0xa1; // Valid order 2 reaches range initialization.
                    if (position == 2) return 0;
                    throw sentinel;
                }
            }, 1, new ByteArrayOutputStream());
            fail("Expected packed I/O failure");
        } catch (IOException expected) { assertSame(sentinel, expected); }
    }

    @Test public void rollingHistoryCanCrossFormer64MiBTotalBoundary() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("readwide.largeArchiveTests"));
        Rar3PpmdSolidStreamDecoder.HistoryWindow window = new Rar3PpmdSolidStreamDecoder.HistoryWindow(32 * 1024 * 1024);
        OutputStream discard = new OutputStream() { @Override public void write(int value) { } };
        for (long i = 0; i < 65L * 1024 * 1024; i++) window.literal((int) i & 255, discard);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        window.match(0x1000001, 3, out);
        assertArrayEquals(new byte[] {(byte) 255, 0, 1}, out.toByteArray());
    }

    @Test public void customEscapePersistsWhenTheNextTableOmitsIt() throws Exception {
        Rar3PpmdSolidStreamDecoder decoder=new Rar3PpmdSolidStreamDecoder();
        java.lang.reflect.Method table=Rar3PpmdSolidStreamDecoder.class.getDeclaredMethod("readPpmdTable",InputStream.class);
        table.setAccessible(true);
        java.lang.reflect.Field escape=Rar3PpmdSolidStreamDecoder.class.getDeclaredField("escapeChar");
        escape.setAccessible(true);
        table.invoke(decoder,new java.io.ByteArrayInputStream(new byte[]{(byte)0xe1,0,7,0,0,0,0}));
        assertEquals(7,escape.getInt(decoder));
        table.invoke(decoder,new java.io.ByteArrayInputStream(new byte[]{(byte)0x80,0,0,0,0}));
        assertEquals(7,escape.getInt(decoder));
        table.invoke(decoder,new java.io.ByteArrayInputStream(new byte[]{(byte)0xc0,9,0,0,0,0}));
        assertEquals(9,escape.getInt(decoder));
    }
}
