package com.readwide.manager.archive;

import java.io.*;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** RFC 7932 uncompressed meta-blocks and published Brotli-MT framing, generated in memory. */
public class SevenZBrotliInputStreamTest {
    @Test public void rawAndFramedStreamsSupportMixedReadSizes() throws Exception {
        byte[] raw = new byte[8193]; for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        assertArrayEquals(raw, decode(raw(raw), raw.length));
        assertArrayEquals(raw, decode(frames(Arrays.copyOfRange(raw, 0, 4096), Arrays.copyOfRange(raw, 4096, raw.length)), raw.length));
        assertArrayEquals(new byte[0], decode(raw(new byte[0]), 0));
        assertArrayEquals(new byte[0], decode(frames(new byte[0]), 0));
    }
    @Test public void malformedFramingTruncationAndSizeMismatchFail() throws Exception {
        byte[] framed = frames(new byte[]{1,2,3});
        for (int at : new int[]{4,8,12,14}) {
            byte[] bad = framed.clone(); bad[at] = 0;
            assertThrows(IOException.class, () -> decode(bad, 3));
        }
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(framed, 15), 3));
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(framed, framed.length - 1), 3));
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(framed, framed.length + 1), 3));
        assertThrows(IOException.class, () -> decode(framed, 2));
        assertThrows(IOException.class, () -> decode(framed, 4));
        byte[] packed = raw(new byte[]{1,2,3});
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(packed, packed.length + 1), 3));
    }
    @Test public void framePackedSizeCannotConsumeNextFrame() throws Exception {
        byte[] packed = frames(new byte[]{1}, new byte[]{2});
        packed[8]--; // The first Brotli end marker must not be read from the next frame.
        assertThrows(IOException.class, () -> decode(packed, 2));
    }
    @Test public void largeWindowRawAndFramesUseTheSameStreamingPath() throws Exception {
        byte[] content = new byte[8193];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
        // Small uncompressed payloads exercise the extension header, not long-distance history.
        for (int bits = 10; bits <= 30; bits++) {
            assertArrayEquals(content, decode(largeRaw(content, bits), content.length));
            assertArrayEquals(new byte[0], decode(largeRaw(new byte[0], bits), 0));
            assertArrayEquals(content, decode(framesWithWindow(bits,
                    Arrays.copyOfRange(content, 0, 4096),
                    Arrays.copyOfRange(content, 4096, content.length)), content.length));
        }
    }
    @Test public void mixedStandardAndLargeWindowFramesResetDecoderState() throws Exception {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(frames(new byte[]{1, 2}));
        packed.write(framesWithWindow(25, new byte[0], new byte[]{3, 4}));
        packed.write(framesWithWindow(30, new byte[]{5}));
        packed.write(frames(new byte[]{6}));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, decode(packed.toByteArray(), 6));
    }
    @Test public void malformedLargeWindowStreamsRemainTerminal() throws Exception {
        byte[] content = {1, 2, 3};
        byte[] packed = largeRaw(content, 25);
        for (int bits : new int[]{9, 31, 63}) {
            byte[] bad = packed.clone(); bad[1] = (byte) bits;
            assertThrows(IOException.class, () -> decode(bad, content.length));
        }
        byte[] reserved = packed.clone(); reserved[0] |= (byte) 0x80;
        assertThrows(IOException.class, () -> decode(reserved, content.length));
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(packed, packed.length - 1), 3));
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(packed, packed.length + 1), 3));
        assertThrows(IOException.class, () -> decode(packed, 2));
        assertThrows(IOException.class, () -> decode(packed, 4));
        byte[] framed = framesWithWindow(25, content, content); framed[8]--;
        try (InputStream in = new SevenZBrotliInputStream(new ByteArrayInputStream(framed), 6)) {
            IOException first = assertThrows(IOException.class, () -> { while (in.read() != -1) { } });
            assertSame(first, assertThrows(IOException.class, in::read));
        }
    }
    @Test public void upstreamCompressedDictionaryVectorStillDecodes() throws Exception {
        // Google Brotli v1.2.0 SynthTest.testIntactDistanceRingBuffer1 (MIT).
        // Copyright 2016 Google Inc. All Rights Reserved.
        // License/provenance: third_party/brotli-java/LICENSE and README.md.
        byte[] packed = {
                0x1b, 0x09, 0, 0, 0, 0, (byte) 0x80,
                (byte) 0xe3, (byte) 0xb4, 0x0d, 0, 0, 7, 0x5b,
                0x26, 0x31, 0x40, 2, 0, (byte) 0xe0, 0x4e,
                0x1b, 0x21, (byte) 0xa0, 0x20, 0
        };
        assertArrayEquals("scrollroll".getBytes(java.nio.charset.StandardCharsets.US_ASCII), decode(packed, 10));
    }
    @Test public void compatibilityFacadeRequiresAndUsesRealOutputSize() throws Exception {
        byte[] id = {4,(byte)0xf7,0x11,2}, props = {1,0,5};
        assertThrows(IOException.class, () -> SevenZAdditionalCoders.open(id, props, new ByteArrayInputStream(raw(new byte[]{42}))));
        try (InputStream in = SevenZAdditionalCoders.open(id, props, new ByteArrayInputStream(raw(new byte[]{42})), 1)) {
            assertEquals(42, in.read()); assertEquals(-1, in.read());
        }
    }
    @Test public void cancellationIsStickyAndCloseOwnsSource() throws Exception {
        boolean[] closed = {false};
        InputStream source = new ByteArrayInputStream(raw(new byte[]{1})) {
            @Override public void close() { closed[0] = true; }
        };
        try (InputStream in = new SevenZBrotliInputStream(source, Long.MAX_VALUE)) {
            Thread.currentThread().interrupt(); IOException first;
            try { first = assertThrows(InterruptedIOException.class, in::read); }
            finally { Thread.interrupted(); }
            assertSame(first, assertThrows(IOException.class, in::read));
        }
        assertTrue(closed[0]);
    }
    @Test public void uncheckedPrefixAndPayloadFailuresAreSticky() throws Exception {
        for(boolean error:new boolean[]{false,true})for(boolean prefix:new boolean[]{false,true}) {
            Throwable sentinel=error?new AssertionError("source"):new IllegalStateException("source");
            int[] failures={0};
            InputStream source=new ByteArrayInputStream(raw(new byte[]{1,2,3})) {
                private void reject() {
                    failures[0]++;
                    if(sentinel instanceof Error)throw (Error)sentinel;
                    throw (RuntimeException)sentinel;
                }
                @Override public synchronized int read() {
                    if(prefix)reject();return super.read();
                }
                @Override public synchronized int read(byte[] bytes,int offset,int length) {
                    reject();return -1;
                }
            };
            try(InputStream in=new SevenZBrotliInputStream(source,3)) {
                assertSame(sentinel,assertThrows(Throwable.class,in::read));
                assertSame(sentinel,assertThrows(Throwable.class,in::read));
                assertEquals(1,failures[0]);
            }
        }
    }

    @Test public void failedPhysicalCloseCanBeRetriedButReadsRemainClosed() throws Exception {
        for(boolean error:new boolean[]{false,true}) {
            Throwable sentinel=error?new AssertionError("close"):new IOException("close");
            int[] closes={0};
            InputStream source=new ByteArrayInputStream(raw(new byte[]{42})) {
                @Override public void close() throws IOException {
                    if(++closes[0]!=1)return;
                    if(sentinel instanceof Error)throw (Error)sentinel;
                    throw (IOException)sentinel;
                }
            };
            InputStream in=new SevenZBrotliInputStream(source,1);
            assertEquals(42,in.read());
            assertSame(sentinel,assertThrows(Throwable.class,in::close));
            assertThrows(IOException.class,in::read);
            in.close();in.close();assertEquals(2,closes[0]);
        }
    }

    @Test public void physicalScalarCancellationStopsPrefixAndFrameMetadataImmediately() throws Exception {
        for (boolean framed : new boolean[]{false,true}) for (boolean eof : new boolean[]{false,true}) {
            byte[] packed = framed ? frames(new byte[]{1,2,3,4}) : raw(new byte[]{1,2,3,4});
            int stopAt = framed ? 5 : 1; // Fifth physical byte is MT metadata after pushback.
            int[] reads = {0};
            InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read() {
                    int value = super.read();
                    if (++reads[0] == stopAt) {
                        Thread.currentThread().interrupt();
                        if (eof) return -1;
                    }
                    return value;
                }
            };
            try (InputStream in = new SevenZBrotliInputStream(source,4)) {
                IOException first;
                try { first = assertThrows(InterruptedIOException.class,in::read); }
                finally { Thread.interrupted(); }
                assertEquals(stopAt,reads[0]);
                assertSame(first,assertThrows(IOException.class,in::read));
                assertEquals(stopAt,reads[0]);
            }
        }
    }

    @Test public void physicalBulkCancellationIsNotHiddenByTheDecoder() throws Exception {
        for (boolean framed : new boolean[]{false,true}) for (boolean eof : new boolean[]{false,true}) {
            byte[] packed = framed ? frames(new byte[]{1,2,3,4}) : raw(new byte[]{1,2,3,4});
            int[] reads = {0};
            InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read(byte[] bytes,int offset,int length) {
                    reads[0]++;
                    int count = super.read(bytes,offset,length);
                    Thread.currentThread().interrupt();
                    return eof ? -1 : count;
                }
            };
            try (InputStream in = new SevenZBrotliInputStream(source,4)) {
                IOException first;
                try { first = assertThrows(InterruptedIOException.class,in::read); }
                finally { Thread.interrupted(); }
                assertEquals(1,reads[0]);
                assertSame(first,assertThrows(IOException.class,in::read));
                assertEquals(1,reads[0]);
            }
        }
    }

    @Test public void invalidScalarReadsCannotBecomePrefixOrFrameBytes() throws Exception {
        for (boolean framed : new boolean[]{false,true}) for (int invalid : new int[]{-2,256,Integer.MAX_VALUE}) {
            byte[] packed = framed ? frames(new byte[]{1,2,3,4}) : raw(new byte[]{1,2,3,4});
            int stopAt = framed ? 5 : 1;
            int[] reads = {0};
            InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read() {
                    return ++reads[0] == stopAt ? invalid : super.read();
                }
            };
            try (InputStream in = new SevenZBrotliInputStream(source,4)) {
                IOException first = assertThrows(IOException.class,in::read);
                assertEquals(stopAt,reads[0]);
                assertSame(first,assertThrows(IOException.class,in::read));
                assertEquals(stopAt,reads[0]);
            }
        }
    }

    @Test public void invalidBulkCountsAreCheckedBeforePushbackCombinesThem() throws Exception {
        for (boolean framed : new boolean[]{false,true}) for (int invalid : new int[]{-2,0,1}) {
            byte[] packed = framed ? frames(new byte[]{1,2,3,4}) : raw(new byte[]{1,2,3,4});
            int[] reads = {0};
            InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read(byte[] bytes,int offset,int length) {
                    reads[0]++;
                    return invalid == 1 ? length + 1 : invalid;
                }
            };
            try (InputStream in = new SevenZBrotliInputStream(source,4)) {
                IOException first = assertThrows(IOException.class,in::read);
                assertEquals("Invalid Brotli source read",first.getMessage());
                assertSame(first,assertThrows(IOException.class,in::read));
                assertEquals(1,reads[0]);
            }
        }
    }

    @Test public void checkedPayloadFailureKeepsItsOriginalIdentity() throws Exception {
        for (boolean framed : new boolean[]{false,true}) {
            byte[] packed = framed ? frames(new byte[]{1,2,3,4}) : raw(new byte[]{1,2,3,4});
            IOException sentinel = new IOException("physical source failed");
            int[] reads = {0};
            InputStream source = new FilterInputStream(new ByteArrayInputStream(packed)) {
                @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                    reads[0]++; throw sentinel;
                }
            };
            try (InputStream in = new SevenZBrotliInputStream(source,4)) {
                assertSame(sentinel,assertThrows(IOException.class,in::read));
                assertSame(sentinel,assertThrows(IOException.class,in::read));
                assertEquals(1,reads[0]);
            }
        }
    }

    @Test public void inputCallbacksCannotReenterReadOrClose() throws Exception {
        for (boolean close : new boolean[]{false,true}) for (boolean prefix : new boolean[]{false,true}) {
            SevenZBrotliInputStream[] owner = new SevenZBrotliInputStream[1];
            int[] callbacks = {0}, closes = {0};
            InputStream source = new FilterInputStream(new ByteArrayInputStream(raw(new byte[]{1,2,3,4}))) {
                private void callback() throws IOException {
                    callbacks[0]++;
                    if (close) owner[0].close(); else owner[0].read();
                }
                @Override public int read() throws IOException {
                    if (prefix) callback();
                    return in.read();
                }
                @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                    if (!prefix) callback();
                    return in.read(bytes,offset,length);
                }
                @Override public void close() throws IOException { closes[0]++; in.close(); }
            };
            try (SevenZBrotliInputStream in = new SevenZBrotliInputStream(source,4)) {
                owner[0] = in;
                IOException first = assertThrows(IOException.class,in::read);
                assertTrue(first.getMessage().startsWith("Reentrant 7z Brotli"));
                assertSame(first,assertThrows(IOException.class,in::read));
                assertEquals(1,callbacks[0]);
                assertEquals(0,closes[0]); // Reentrant close did not take ownership.
            }
            assertEquals(1,closes[0]);
        }
    }

    @Test public void cleanupCallbackCannotRecursivelyCloseThePhysicalSource() throws Exception {
        SevenZBrotliInputStream[] owner = new SevenZBrotliInputStream[1];
        int[] closes = {0};
        InputStream source = new ByteArrayInputStream(raw(new byte[]{1})) {
            @Override public void close() throws IOException {
                if (++closes[0] == 1) owner[0].close();
            }
        };
        owner[0] = new SevenZBrotliInputStream(source,1);
        assertThrows(IOException.class,owner[0]::close);
        assertEquals(1,closes[0]);
        assertThrows(IOException.class,owner[0]::read);
        owner[0].close(); owner[0].close();
        assertEquals(2,closes[0]);
    }

    static byte[] raw(byte[] content) throws IOException {
        if (content.length == 0) return new byte[]{6}; // WBITS=16, ISLAST=1, ISLASTEMPTY=1.
        if (content.length > 65536) throw new IllegalArgumentException("Fixture block too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // WBITS=16; non-last, 4-nibble length; uncompressed; byte alignment.
        int header = ((content.length - 1) << 4) | (1 << 20);
        for (int i = 0; i < 3; i++) out.write(header >>> (8 * i));
        out.write(content); out.write(3); // Last empty block, no second stream header.
        return out.toByteArray();
    }
    static byte[] frames(byte[]... chunks) throws IOException {
        return framesWithWindow(0, chunks);
    }
    static byte[] largeRaw(byte[] content, int windowBits) throws IOException {
        if (windowBits < 10 || windowBits > 30 || content.length > 65536) {
            throw new IllegalArgumentException("Invalid large-window fixture parameters");
        }
        // Extended WBITS: 0x11 followed by six little-endian bits of window size.
        if (content.length == 0) return new byte[]{0x11, (byte) (windowBits | 0xc0)};
        long header = 0x11L | ((long) windowBits << 8)
                | ((long) (content.length - 1) << 17) | (1L << 33);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 5; i++) out.write((int) (header >>> (8 * i)));
        out.write(content); out.write(3);
        return out.toByteArray();
    }
    static byte[] framesWithWindow(int windowBits, byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            byte[] packed = windowBits == 0 ? raw(chunk) : largeRaw(chunk, windowBits);
            little(out, 0x184d2a50, 4); little(out, 8, 4); little(out, packed.length, 4);
            little(out, 0x5242, 2); little(out, (chunk.length >>> 16) + 1, 2); out.write(packed);
        }
        return out.toByteArray();
    }
    private static void little(ByteArrayOutputStream out, int value, int size) {
        for (int i = 0; i < size; i++) out.write(value >>> (8 * i));
    }
    private static byte[] decode(byte[] packed, long size) throws IOException {
        try (InputStream in = new SevenZBrotliInputStream(new ByteArrayInputStream(packed), size)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[317];
            int first = in.read(); if (first != -1) out.write(first);
            int n; while ((n = in.read(block)) != -1) out.write(block, 0, n);
            assertEquals(-1, in.read()); return out.toByteArray();
        }
    }
}
