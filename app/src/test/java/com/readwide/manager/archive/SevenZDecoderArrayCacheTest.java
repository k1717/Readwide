package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;
import org.junit.Test;
import org.tukaani.xz.*;

public class SevenZDecoderArrayCacheTest {
    @Test public void returnedBuffersAreZeroedAndNeverLoanedTwice() {
        try (SevenZDecoderArrayCache cache = new SevenZDecoderArrayCache()) {
            byte[] original = cache.getByteArray(4096, false);
            Arrays.fill(original, (byte) 99);
            cache.putArray(original);
            assertArrayEquals(new byte[4096], original);
            cache.putArray(original); // A repeated return must not create aliased slots.
            assertSame(original, cache.getByteArray(4096, true));
            assertNotSame(original, cache.getByteArray(4096, false));
        }
    }

    @Test public void retentionIsByteBoundedButDoesNotLimitAllocation() {
        try (SevenZDecoderArrayCache cache = new SevenZDecoderArrayCache()) {
            int budget = SevenZDecoderArrayCache.MAX_RETAINED_BYTES;
            byte[] old = cache.getByteArray(budget / 2 + 1, false);
            byte[] recent = cache.getByteArray(budget / 2, false);
            cache.putArray(old);
            cache.putArray(recent);
            assertSame(recent, cache.getByteArray(recent.length, false));
            assertNotSame(old, cache.getByteArray(old.length, false));
            byte[] large = cache.getByteArray(budget + 1, false);
            assertEquals(budget + 1, large.length);
            cache.putArray(large);
            assertNotSame(large, cache.getByteArray(large.length, false));
        }
    }

    @Test public void closeDiscardsIdleBuffersAndCannotBeRepopulated() {
        SevenZDecoderArrayCache cache = new SevenZDecoderArrayCache();
        byte[] idle = cache.getByteArray(4096, false);
        byte[] active = cache.getByteArray(8192, false);
        cache.putArray(idle);
        cache.close();
        cache.close();
        cache.putArray(active);
        assertNotSame(idle, cache.getByteArray(4096, false));
        assertNotSame(active, cache.getByteArray(8192, false));
    }

    @Test public void failedLzmaConstructionCanReturnCheckedOutDictionary() throws Exception {
        try (SevenZDecoderArrayCache cache = new SevenZDecoderArrayCache()) {
            byte[] dictionary = cache.getByteArray(4096, false);
            cache.putArray(dictionary);
            ResettableArrayCache folder = new ResettableArrayCache(cache);
            SevenZCoderRegistry.Prepared prepared = SevenZCoderRegistry.require(SevenZCoderRegistryTest.id("030101"))
                    .prepare(new byte[]{0x5d, 0, 0, 16, 0}, 4096);
            try {
                assertThrows(IOException.class, () -> prepared.open(
                        new InputStream[]{new ByteArrayInputStream(new byte[0])}, null, folder));
            } finally { folder.reset(); }
            assertSame(dictionary, cache.getByteArray(4096, true));
            cache.putArray(dictionary);
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, () -> prepared.open(
                        new InputStream[]{new ByteArrayInputStream(new byte[5])}, null, folder));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); folder.reset(); }
            assertSame(dictionary, cache.getByteArray(4096, true));
        }
    }

    @Test public void lzmaAndLzma2ReuseDoesNotCarryHistoryAcrossStreams() throws Exception {
        try (SevenZDecoderArrayCache cache = new SevenZDecoderArrayCache()) {
            byte[] dictionary = cache.getByteArray(32768, false);
            cache.putArray(dictionary);
            for (int pass = 0; pass < 6; pass++) {
                byte[] raw = new byte[32768];
                new java.util.Random(901 + pass).nextBytes(raw);
                LZMA2Options options = new LZMA2Options(1);
                options.setDictSize(32768);
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                boolean lzma = pass % 2 == 0;
                try (OutputStream encoder = lzma ? new LZMAOutputStream(packed, options, true)
                        : options.getOutputStream(new FinishableWrapperOutputStream(packed))) {
                    encoder.write(raw);
                }
                SevenZCoderRegistry.Prepared prepared = SevenZCoderRegistry.require(
                        SevenZCoderRegistryTest.id(lzma ? "030101" : "21"))
                        .prepare(lzma ? new byte[]{0x5d, 0, (byte)0x80, 0, 0} : new byte[]{6}, raw.length);
                ResettableArrayCache folder = new ResettableArrayCache(cache);
                try (InputStream decoded = prepared.open(
                        new InputStream[]{new ByteArrayInputStream(packed.toByteArray())}, null, folder)) {
                    ByteArrayOutputStream result = new ByteArrayOutputStream();
                    byte[] buffer = new byte[997];
                    int read;
                    while ((read = decoded.read(buffer)) != -1) result.write(buffer, 0, read);
                    assertArrayEquals(raw, result.toByteArray());
                } finally { folder.reset(); }
                byte[] returned = cache.getByteArray(32768, true);
                assertSame(dictionary, returned);
                assertArrayEquals(new byte[32768], returned);
                cache.putArray(returned);
            }
        }
    }

    @Test public void earlyCloseReturnsBuffersEvenWhenSourceCloseFails() throws Exception {
        for (boolean lzma : new boolean[]{true, false}) {
            try (SevenZDecoderArrayCache cache = new SevenZDecoderArrayCache()) {
                byte[] dictionary = cache.getByteArray(4096, false);
                cache.putArray(dictionary);
                ResettableArrayCache folder = new ResettableArrayCache(cache);
                InputStream source = new ByteArrayInputStream(new byte[5]) {
                    @Override public void close() throws IOException { throw new IOException("close failure"); }
                };
                InputStream decoded = SevenZCoderRegistry.require(SevenZCoderRegistryTest.id(lzma ? "030101" : "21"))
                        .prepare(lzma ? new byte[]{0x5d, 0, 0, 16, 0} : new byte[]{0}, 4096)
                        .open(new InputStream[]{source}, null, folder);
                try { assertThrows(IOException.class, decoded::close); }
                finally { folder.reset(); }
                assertSame(dictionary, cache.getByteArray(4096, true));
            }
        }
    }
}
