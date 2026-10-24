package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import org.junit.Test;

/** Synthetic format fixtures, not ALZip/Bandizip-produced compatibility evidence. */
public class AlzObfuscatedDeflateInputStreamTest {
    @Test public void dynamicAlphabetCoversEverySizeResidueAndByteValue() throws Exception {
        for (int residue = 0; residue < 16; residue++) {
            byte[] plain = new byte[256 + residue];
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) i;
            assertArrayEquals(plain, decode(dynamic(plain, plain.length), plain.length, 7));
        }
    }

    @Test public void orderIsSizeModuloSixteenWithoutIntOverflow() throws Exception {
        assertArrayEquals(new int[]{18,3,6,13,12,15,14,9,0,11,4,5,16,7,8,17,2,1,10},
                AlzObfuscatedDeflateInputStream.codeLengthOrder(0));
        for (int residue = 0; residue < 16; residue++) {
            int[] order = AlzObfuscatedDeflateInputStream.codeLengthOrder(residue);
            assertArrayEquals(order, AlzObfuscatedDeflateInputStream.codeLengthOrder((1L << 31) + residue));
            assertArrayEquals(order, AlzObfuscatedDeflateInputStream.codeLengthOrder((1L << 40) + residue));
            int[] sorted = order.clone(); Arrays.sort(sorted);
            for (int i = 0; i < 19; i++) assertEquals(i, sorted[i]);
        }
        assertThrows(IllegalArgumentException.class, () -> AlzObfuscatedDeflateInputStream.codeLengthOrder(-1));
    }

    @Test public void storedAndFixedBlocksKeepStandardDeflateRules() throws Exception {
        byte[] plain = {0, 1, (byte) 255};
        Bits stored = new Bits(); stored.stored(plain, true);
        assertArrayEquals(plain, decode(stored.finish(), plain.length, 1));
        Bits fixed = new Bits(); fixed.bits(3, 3);
        for (byte value : plain) fixed.code(value & 255, fixedLengths());
        fixed.code(256, fixedLengths());
        assertArrayEquals(plain, decode(fixed.finish(), plain.length, 1));
    }

    @Test public void overlappingMatchSpansReadCallsAndBlocks() throws Exception {
        Bits stream = new Bits(); stream.stored(new byte[]{'a', 'b', 'c'}, false);
        stream.bits(3, 3); // Final fixed block: repeat the previous three bytes.
        stream.code(285, fixedLengths()); // 258 bytes, maximum ordinary Deflate match.
        stream.bits(8, 5); // Distance symbol 2, Huffman bits 00010, LSB packed.
        stream.code(256, fixedLengths());
        byte[] expected = new byte[261];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) ('a' + i % 3);
        assertArrayEquals(expected, decode(stream.finish(), expected.length, 2));
    }

    @Test public void maximumDistanceSurvivesWindowWrapAndStoredBoundary() throws Exception {
        byte[] prefix = new byte[32768];
        for (int i = 0; i < prefix.length; i++) prefix[i] = (byte) (i * 7);
        Bits stream = new Bits(); stream.stored(prefix, false); stream.bits(3, 3);
        stream.code(257, fixedLengths()); // length 3
        stream.bits(23, 5); // reversed canonical distance code 29 (11101).
        stream.bits(8191, 13); // distance 24577 + 8191 = 32768
        stream.code(256, fixedLengths());
        byte[] expected = Arrays.copyOf(prefix, prefix.length + 3);
        System.arraycopy(prefix, 0, expected, prefix.length, 3);
        assertArrayEquals(expected, decode(stream.finish(), expected.length, 4093));
    }

    @Test public void multipleDynamicBlocksUseEntrySizeNotBlockSize() throws Exception {
        byte[] first = {1,2,3}, second = {4,5,6,7};
        Bits stream = new Bits(); dynamicBlock(stream, first, 7, false); dynamicBlock(stream, second, 7, true);
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7}, decode(stream.finish(), 7, 1));
    }

    @Test public void storedFixedDynamicTransitionsKeepMaximumDistanceHistory() throws Exception {
        byte[] prefix = new byte[32768], suffix = {9, 8, 7};
        for (int i = 0; i < prefix.length; i++) prefix[i] = (byte) (i * 7);
        byte[] plain = Arrays.copyOf(prefix, prefix.length + 258 + suffix.length);
        System.arraycopy(prefix, 0, plain, prefix.length, 258);
        System.arraycopy(suffix, 0, plain, prefix.length + 258, suffix.length);
        byte[] packed = storedFixedDynamic(prefix, suffix, plain.length);
        for (int chunk : new int[]{1, 4093, 65536}) assertArrayEquals(plain, decode(packed, plain.length, chunk));
    }

    @Test public void literalOnlyBlockAllowsEmptyDistanceTreeAndSingleEndCode() throws Exception {
        int[] meta = new int[19]; meta[0] = meta[1] = 1;
        Bits stream = dynamicHeader(0, 257, 1, meta, true);
        for (int i = 0; i < 256; i++) stream.code(0, meta);
        stream.code(1, meta); stream.code(0, meta); stream.bits(0, 1);
        byte[] packed = stream.finish();
        assertEquals(14, (((packed[1] & 255) >>> 5) | ((packed[2] & 255) << 3)) & 15);
        assertArrayEquals(new byte[0], decode(packed, 0, 1)); // HCLEN+4=18, final meta length implicitly zero.
    }

    @Test public void repeatLengthsMayCrossLiteralDistanceBoundary() throws Exception {
        // Only A and EOB have one-bit literal codes; four one-bit distance
        // codes would be oversubscribed, so crossing zeros is tested instead.
        int[] meta = new int[19]; meta[0] = meta[1] = meta[17] = meta[18] = 2;
        Bits stream = dynamicHeader(1, 260, 1, meta, true);
        stream.code(18, meta); stream.bits(54, 7); // 65 zeros
        stream.code(1, meta); // A
        stream.code(18, meta); stream.bits(127, 7); // 138 zeros
        stream.code(18, meta); stream.bits(41, 7); // 52 zeros to EOB
        stream.code(1, meta); // EOB at index 256
        stream.code(17, meta); stream.bits(1, 3); // 4 zeros, crosses to distance
        stream.bits(0, 1); stream.bits(1, 1); // A, EOB
        assertArrayEquals(new byte[]{65}, decode(stream.finish(), 1, 1));
    }

    @Test public void repeatPreviousLengthAndDynamicMatchAreDecoded() throws Exception {
        // Four literal codes of length two: A, B, EOB and length-3.
        int[] meta = new int[19];
        meta[0] = meta[2] = 2; meta[1] = meta[16] = meta[17] = meta[18] = 3;
        Bits stream = dynamicHeader(5, 258, 4, meta, true);
        stream.code(18, meta); stream.bits(54, 7); // 65 zeros
        stream.code(2, meta); stream.code(2, meta);
        stream.code(18, meta); stream.bits(127, 7);
        stream.code(18, meta); stream.bits(40, 7); // 189 zeros total
        stream.code(2, meta); stream.code(2, meta);
        stream.code(2, meta); stream.code(16, meta); stream.bits(0, 2); // distances: four length-2 codes
        stream.bits(0, 2); stream.bits(2, 2); stream.bits(3, 2); // A=00 B=01 length3=11
        stream.bits(2, 2); // distance code 1=01, distance 2
        stream.bits(1, 2); // EOB=10
        assertArrayEquals(new byte[]{65,66,65,66,65}, decode(stream.finish(), 5, 1));
    }

    @Test public void truncatedHeadersAndPayloadsNeverBecomeSuccessfulEof() throws Exception {
        byte[] packed = dynamic(new byte[]{1,2,3,4,5}, 5);
        for (int cut = 0; cut < packed.length; cut++) {
            byte[] truncated = Arrays.copyOf(packed, cut);
            assertThrows(IOException.class, () -> decode(truncated, 5, 13));
        }
    }

    @Test public void reservedBlocksAndInvalidStoredLengthsFail() {
        assertThrows(IOException.class, () -> decode(new byte[]{7}, 0, 1));
        assertThrows(IOException.class, () -> decode(new byte[]{1,1,0,0,0,42}, 1, 1));
    }

    @Test public void invalidBackReferencesAndReservedSymbolsFail() {
        for (int symbol : new int[]{257, 286, 287}) {
            Bits stream = new Bits(); stream.bits(3, 3); stream.code(symbol, fixedLengths());
            stream.bits(0, 5); stream.code(256, fixedLengths());
            byte[] packed = stream.finish();
            assertThrows(IOException.class, () -> decode(packed, 3, 1));
        }
        for (int distance : new int[]{30, 31}) {
            Bits stream = new Bits(); stream.stored(new byte[]{42}, false); stream.bits(3, 3);
            stream.code(257, fixedLengths()); stream.bits(Integer.reverse(distance) >>> 27, 5);
            assertThrows(IOException.class, () -> decode(stream.finish(), 4, 1));
        }
    }

    @Test public void malformedMetaTreesRepeatsAndMissingEndCodeFail() {
        for (int kind = 0; kind < 5; kind++) {
            int[] meta = new int[19];
            if (kind == 0) Arrays.fill(meta, 1); // oversubscribed
            if (kind == 1) meta[0] = 2; // incomplete
            if (kind >= 2) { meta[0] = meta[16] = meta[18] = meta[1] = 2; }
            Bits stream = dynamicHeader(0, 257, 1, meta, true);
            if (kind == 2) { stream.code(16, meta); stream.bits(0, 2); } // no preceding length
            if (kind == 3) { // 276 zero lengths exceed 258 requested codes.
                stream.code(18, meta); stream.bits(127, 7); stream.code(18, meta); stream.bits(127, 7);
            }
            if (kind == 4) for (int i = 0; i < 258; i++) stream.code(0, meta);
            assertThrows(IOException.class, () -> decode(stream.finish(), 0, 1));
        }
    }

    @Test public void invalidLiteralCountFailsBeforeTrees() {
        Bits stream = new Bits(); stream.bits(5, 3); stream.bits(30, 5); stream.bits(0, 5); stream.bits(0, 4);
        assertThrows(IOException.class, () -> decode(stream.finish(), 0, 1));
    }

    @Test public void failedReadIsTerminalAndCloseOwnsSourceOnce() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("read"), new IllegalStateException("read"), new AssertionError("read")}) {
            int[] reads = {0}, closes = {0};
            InputStream source = new InputStream() {
                @Override public int read() throws IOException {
                    reads[0]++;
                    if (failure instanceof IOException) throw (IOException) failure;
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    throw (Error) failure;
                }
                @Override public int read(byte[] b, int o, int n) throws IOException { return read(); }
                @Override public void close() { closes[0]++; }
            };
            AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(source, 1);
            assertSame(failure, assertThrows(failure.getClass(), decoded::read));
            assertSame(failure, assertThrows(failure.getClass(), () -> decoded.read(new byte[4])));
            assertSame(failure, assertThrows(failure.getClass(), decoded::available));
            assertEquals(1, reads[0]); decoded.close(); decoded.close(); assertEquals(1, closes[0]);
            assertThrows(IOException.class, decoded::read);
        }
    }

    @Test public void invalidReadCountsAndInterruptedReadAreTerminal() throws Exception {
        for (int result : new int[]{0, -2, 20000}) {
            InputStream source = new InputStream() {
                @Override public int read() { return -1; }
                @Override public int read(byte[] b, int o, int n) { return result; }
            };
            try (AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(source, 0)) {
                IOException failure = assertThrows(IOException.class, decoded::read);
                assertSame(failure, assertThrows(IOException.class, decoded::read));
            }
        }
        try (AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(new ByteArrayInputStream(new byte[]{3,0}), 0)) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedIOException.class, decoded::read); }
            finally { Thread.interrupted(); }
            assertThrows(InterruptedIOException.class, decoded::read);
        }
    }

    @Test public void oneByteSourceAndZeroLengthReadsPreserveEof() throws Exception {
        byte[] plain = {12,34,56};
        InputStream source = new ByteArrayInputStream(dynamic(plain, plain.length)) {
            @Override public synchronized int read(byte[] b, int o, int n) { return super.read(b, o, Math.min(1, n)); }
        };
        try (AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(source, plain.length)) {
            assertEquals(0, decoded.read(new byte[0]));
            for (byte value : plain) assertEquals(value & 255, decoded.read());
            assertEquals(-1, decoded.read()); assertEquals(-1, decoded.read(new byte[5]));
            assertEquals(0, decoded.read(new byte[0]));
        }
    }

    @Test public void tokenCancellationDuringEmptyBlocksDoesNotWaitForOutput() throws Exception {
        com.readwide.manager.util.FileOperationProgress progress =
                new com.readwide.manager.util.FileOperationProgress("test", null);
        Bits stream = new Bits();
        for (int i = 0; i < 100; i++) stream.stored(new byte[0], false);
        stream.stored(new byte[]{42}, true);
        int[] reads = {0};
        InputStream source = new ByteArrayInputStream(stream.finish()) {
            @Override public synchronized int read(byte[] b, int o, int n) {
                reads[0]++;
                int count = super.read(b, o, n);
                progress.cancel(); // cancel after refill, before buffered empty blocks are traversed
                return count;
            }
        };
        try (AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(source, 1, progress)) {
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, decoded::read);
            assertSame(failure, assertThrows(InterruptedIOException.class, decoded::read));
            assertEquals(1, reads[0]);
        }
    }

    @Test public void closeFailureStillRetiresDecoder() throws Exception {
        IOException failure = new IOException("close"); int[] closes = {0};
        InputStream source = new ByteArrayInputStream(new byte[]{3,0}) {
            @Override public void close() throws IOException { closes[0]++; throw failure; }
        };
        AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(source, 0);
        assertSame(failure, assertThrows(IOException.class, decoded::close));
        decoded.close(); assertEquals(1, closes[0]); assertThrows(IOException.class, decoded::read);
    }

    @Test public void failureAfterReturnedPrefixCannotResumeBufferedState() throws Exception {
        Bits stream = new Bits(); stream.stored(new byte[]{42}, false);
        byte[] firstBlock = stream.finish(); IOException failure = new IOException("after prefix");
        InputStream source = new InputStream() {
            boolean supplied;
            @Override public int read() throws IOException { throw failure; }
            @Override public int read(byte[] b, int o, int n) throws IOException {
                if (supplied) throw failure;
                supplied = true; System.arraycopy(firstBlock, 0, b, o, firstBlock.length); return firstBlock.length;
            }
        };
        try (AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(source, 2)) {
            assertEquals(42, decoded.read());
            assertSame(failure, assertThrows(IOException.class, decoded::read));
            assertSame(failure, assertThrows(IOException.class, () -> decoded.read(new byte[5])));
        }
    }

    @Test public void fixedTreesAreLazyReusedAcrossOtherBlocksAndReleasedOnClose() throws Exception {
        Bits stream = new Bits();
        fixedLiteralBlock(stream, 65, false);
        dynamicBlock(stream, new byte[]{66}, 4, false);
        stream.stored(new byte[]{67}, false);
        fixedLiteralBlock(stream, 68, true);
        AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(
                new ByteArrayInputStream(stream.finish()), 4);
        try {
            assertNull(decoderField(decoded, "fixedLiterals"));
            assertNull(decoderField(decoded, "fixedDistances"));
            assertEquals(65, decoded.read());
            Object literals = decoderField(decoded, "fixedLiterals");
            Object distances = decoderField(decoded, "fixedDistances");
            assertNotNull(literals); assertNotNull(distances);
            assertEquals(66, decoded.read());
            assertNotSame(literals, decoderField(decoded, "literals"));
            assertEquals(67, decoded.read());
            assertNull(decoderField(decoded, "literals"));
            assertEquals(68, decoded.read());
            assertSame(literals, decoderField(decoded, "literals"));
            assertSame(distances, decoderField(decoded, "distances"));
            assertEquals(-1, decoded.read());
        } finally { decoded.close(); }
        assertNull(decoderField(decoded, "fixedLiterals"));
        assertNull(decoderField(decoded, "fixedDistances"));
    }

    @Test public void fixedTreesAreNotSharedBetweenDecoderSessions() throws Exception {
        Bits stream = new Bits(); fixedLiteralBlock(stream, 42, true);
        byte[] packed = stream.finish();
        try (AlzObfuscatedDeflateInputStream first = new AlzObfuscatedDeflateInputStream(new ByteArrayInputStream(packed), 1);
             AlzObfuscatedDeflateInputStream second = new AlzObfuscatedDeflateInputStream(new ByteArrayInputStream(packed), 1)) {
            assertEquals(42, first.read()); assertEquals(42, second.read());
            assertNotSame(decoderField(first, "fixedLiterals"), decoderField(second, "fixedLiterals"));
            assertNotSame(decoderField(first, "fixedDistances"), decoderField(second, "fixedDistances"));
        }
    }

    @Test public void huffmanRangeKeepsLocalSymbolsWithoutRetainingLengths() throws Exception {
        Class<?> type = Class.forName(AlzObfuscatedDeflateInputStream.class.getName() + "$Huffman");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(
                int[].class, int.class, int.class, int.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        int[] lengths = {15, 1, 0, 1, 15};
        Object tree = constructor.newInstance(lengths, 1, 3, 15, false, false);
        Arrays.fill(lengths, 0);
        java.lang.reflect.Field symbols = type.getDeclaredField("symbols"); symbols.setAccessible(true);
        assertArrayEquals(new int[]{0, 2}, (int[]) symbols.get(tree));
        for (int[] range : new int[][]{{-1, 1}, {0, -1}, {4, 2}, {6, 0}, {Integer.MAX_VALUE, 1}}) {
            java.lang.reflect.InvocationTargetException failure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> constructor.newInstance(lengths, range[0], range[1], 15, false, false));
            assertTrue(failure.getCause() instanceof IOException);
        }
    }

    @Test public void bulkStoredAndMatchesPreserveOverlapWindowWrapAndOutputSlices() throws Exception {
        byte[] prefix = new byte[65535];
        for (int i = 0; i < prefix.length; i++) prefix[i] = (byte) (i * 7 + i / 251);
        for (int distance : new int[]{1, 2, 3, 32768}) {
            Bits stream = new Bits(); stream.stored(prefix, false); stream.bits(3, 3);
            for (int i = 0; i < 3; i++) fixedMatch258(stream, distance);
            stream.code(256, fixedLengths());
            byte[] packed = stream.finish();
            byte[] expected = Arrays.copyOf(prefix, prefix.length + 3 * 258);
            for (int i = prefix.length; i < expected.length; i++) expected[i] = expected[i - distance];
            for (int chunk : new int[]{1, 2, 257, 4093, 4096, 65536}) {
                try (InputStream decoded = new AlzObfuscatedDeflateInputStream(new ByteArrayInputStream(packed), expected.length)) {
                    assertArrayEquals(expected, readSlices(decoded, chunk));
                }
            }
        }
    }

    @Test public void alternatingScalarAndBulkReadsKeepPendingMatchAndStoredState() throws Exception {
        byte[] prefix = new byte[32768];
        for (int i = 0; i < prefix.length; i++) prefix[i] = (byte) i;
        byte[] suffix = {11, 12, 13};
        byte[] expected = Arrays.copyOf(prefix, prefix.length + 258 + suffix.length);
        System.arraycopy(prefix, 0, expected, prefix.length, 258);
        System.arraycopy(suffix, 0, expected, prefix.length + 258, suffix.length);
        byte[] packed = storedFixedDynamic(prefix, suffix, expected.length);
        try (InputStream decoded = new AlzObfuscatedDeflateInputStream(new ByteArrayInputStream(packed), expected.length)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[97];
            for (int value; (value = decoded.read()) != -1;) {
                out.write(value);
                int count = decoded.read(buffer);
                if (count >= 0) out.write(buffer, 0, count);
            }
            assertArrayEquals(expected, out.toByteArray());
            assertEquals(-1, decoded.read(buffer));
        }
    }

    @Test public void shortPackedRefillsKeepStoredBulkReadsAligned() throws Exception {
        byte[] plain = new byte[33001];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte) i;
        Bits stream = new Bits(); stream.stored(plain, true); byte[] packed = stream.finish();
        for (int fragment : new int[]{1, 7, 16384}) {
            InputStream source = new ByteArrayInputStream(packed) {
                @Override public synchronized int read(byte[] b, int o, int n) {
                    return super.read(b, o, Math.min(fragment, n));
                }
            };
            try (InputStream decoded = new AlzObfuscatedDeflateInputStream(source, plain.length)) {
                assertArrayEquals(plain, readSlices(decoded, 8191));
            }
        }
    }

    @Test public void storedBulkCancellationKeepsOriginalOutputCheckpoint() throws Exception {
        byte[] plain = new byte[9000]; Arrays.fill(plain, (byte) 42);
        Bits stream = new Bits(); stream.stored(plain, true);
        assertBulkCancellation(stream.finish(), plain.length, (byte) 42);
    }

    @Test public void matchBulkCancellationKeepsOriginalOutputCheckpoint() throws Exception {
        Bits stream = new Bits(); stream.bits(3, 3); stream.code(65, fixedLengths());
        for (int i = 0; i < 40; i++) fixedMatch258(stream, 1);
        stream.code(256, fixedLengths());
        assertBulkCancellation(stream.finish(), 1 + 40 * 258, (byte) 65);
    }

    @Test public void truncatedStoredBulkFailureRetainsPrefixAndCannotResume() throws Exception {
        byte[] plain = new byte[5000]; Arrays.fill(plain, (byte) 42);
        Bits stream = new Bits(); stream.stored(plain, true);
        byte[] truncated = Arrays.copyOf(stream.finish(), 5 + 3500);
        try (AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(new ByteArrayInputStream(truncated), plain.length)) {
            byte[] output = new byte[6000]; Arrays.fill(output, (byte) -1);
            IOException failure = assertThrows(IOException.class, () -> decoded.read(output));
            for (int i = 0; i < output.length; i++) assertEquals(i < 3500 ? (byte) 42 : (byte) -1, output[i]);
            assertSame(failure, assertThrows(IOException.class, decoded::read));
            assertSame(failure, assertThrows(IOException.class, () -> decoded.read(output)));
        }
    }

    private static void assertBulkCancellation(byte[] packed, int size, byte expected) throws Exception {
        com.readwide.manager.util.FileOperationProgress progress =
                new com.readwide.manager.util.FileOperationProgress("test", null);
        InputStream source = new ByteArrayInputStream(packed) {
            @Override public synchronized int read(byte[] b, int o, int n) {
                int count = super.read(b, o, n); progress.cancel(); return count;
            }
        };
        try (AlzObfuscatedDeflateInputStream decoded = new AlzObfuscatedDeflateInputStream(source, size, progress)) {
            byte[] output = new byte[size]; Arrays.fill(output, (byte) -1);
            InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> decoded.read(output));
            for (int i = 0; i < size; i++) assertEquals(i < 4096 ? expected : (byte) -1, output[i]);
            assertSame(failure, assertThrows(InterruptedIOException.class, decoded::read));
        }
    }

    private static Object decoderField(AlzObfuscatedDeflateInputStream decoder, String name) throws Exception {
        java.lang.reflect.Field field = AlzObfuscatedDeflateInputStream.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(decoder);
    }

    private static byte[] readSlices(InputStream decoded, int chunk) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[chunk + 10];
        while (true) {
            Arrays.fill(buffer, (byte) -1);
            int count = decoded.read(buffer, 5, chunk);
            int written = Math.max(0, count);
            for (int i = 0; i < 5; i++) assertEquals((byte) -1, buffer[i]);
            for (int i = 5 + written; i < buffer.length; i++) assertEquals((byte) -1, buffer[i]);
            if (count == -1) return out.toByteArray();
            assertTrue(count > 0); out.write(buffer, 5, count);
        }
    }

    private static void fixedLiteralBlock(Bits stream, int value, boolean last) {
        stream.bits(last ? 3 : 2, 3); stream.code(value, fixedLengths()); stream.code(256, fixedLengths());
    }

    private static void fixedMatch258(Bits stream, int distance) {
        stream.code(285, fixedLengths());
        int symbol = distance == 32768 ? 29 : distance - 1;
        if (symbol < 0 || (symbol > 2 && symbol != 29)) throw new AssertionError("Fixture distance");
        stream.bits(Integer.reverse(symbol) >>> 27, 5);
        if (symbol == 29) stream.bits(8191, 13);
    }

    static byte[] dynamic(byte[] plain, long entrySize) {
        Bits stream = new Bits(); dynamicBlock(stream, plain, entrySize, true); return stream.finish();
    }

    static byte[] storedWithRepeatedMatches(byte[] prefix, int distance, int repeats) {
        if (prefix.length > 65535 || prefix.length < distance) throw new AssertionError("Fixture prefix");
        Bits stream = new Bits(); stream.stored(prefix, false); stream.bits(3, 3);
        for (int i = 0; i < repeats; i++) fixedMatch258(stream, distance);
        stream.code(256, fixedLengths()); return stream.finish();
    }

    static byte[] storedFixedDynamic(byte[] prefix, byte[] suffix, long entrySize) {
        if (prefix.length != 32768) throw new AssertionError("Fixture needs a full history window");
        Bits stream = new Bits(); stream.stored(prefix, false);
        stream.bits(2, 3); // Non-final fixed block.
        stream.code(285, fixedLengths()); // Length 258, copied from the preceding block.
        stream.bits(23, 5); stream.bits(8191, 13); // Distance 32768.
        stream.code(256, fixedLengths());
        dynamicBlock(stream, suffix, entrySize, true); // No alignment between compressed blocks.
        return stream.finish();
    }

    // This encoder uses the public format rule, never the production order method.
    private static Bits dynamicHeader(long size, int literals, int distances, int[] meta, boolean last) {
        Bits stream = new Bits(); header(stream, size, literals, distances, meta, last); return stream;
    }
    private static void header(Bits stream, long size, int literals, int distances, int[] meta, boolean last) {
        int[] order = new int[19]; for (int i = 0; i < 19; i++) order[i] = i;
        for (int i = 0; i < 19; i++) {
            int j = 3 * (i % 6) + (int) (size % 16);
            if (j > 18) j %= 18;
            int saved = order[i]; order[i] = order[j]; order[j] = saved;
        }
        int count = 19;
        while (count > 4 && meta[order[count - 1]] == 0) count--;
        stream.bits(last ? 5 : 4, 3); stream.bits(literals - 257, 5); stream.bits(distances - 1, 5); stream.bits(count - 4, 4);
        for (int i = 0; i < count; i++) stream.bits(meta[order[i]], 3);
    }
    private static void dynamicBlock(Bits stream, byte[] plain, long size, boolean last) {
        int[] meta = new int[19]; meta[0] = meta[1] = meta[8] = meta[9] = 2;
        header(stream, size, 257, 1, meta, last);
        int[] literals = new int[257]; Arrays.fill(literals, 0, 255, 8); literals[255] = literals[256] = 9;
        for (int length : literals) stream.code(length, meta);
        stream.code(1, meta); // one-bit distance zero, unused
        for (byte value : plain) stream.code(value & 255, literals);
        stream.code(256, literals);
    }
    private static int[] fixedLengths() {
        int[] lengths = new int[288];
        Arrays.fill(lengths, 0, 144, 8); Arrays.fill(lengths, 144, 256, 9);
        Arrays.fill(lengths, 256, 280, 7); Arrays.fill(lengths, 280, 288, 8); return lengths;
    }
    private static byte[] decode(byte[] packed, long size, int chunk) throws IOException {
        try (InputStream decoded = new AlzObfuscatedDeflateInputStream(new ByteArrayInputStream(packed), size)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[chunk];
            for (int n; (n = decoded.read(buffer)) != -1;) { assertTrue(n > 0); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int pending, count;
        void bits(int value, int length) {
            for (int bit = 0; bit < length; bit++) {
                pending |= ((value >>> bit) & 1) << count++;
                if (count == 8) { out.write(pending); pending = count = 0; }
            }
        }
        void code(int symbol, int[] lengths) {
            int length = lengths[symbol], code = 0;
            if (length == 0) throw new AssertionError("Absent fixture symbol");
            for (int bitLength = 1; bitLength <= length; bitLength++) {
                code <<= 1;
                if (bitLength < length) for (int other : lengths) if (other == bitLength) code++;
            }
            for (int i = 0; i < symbol; i++) if (lengths[i] == length) code++;
            for (int bit = length - 1; bit >= 0; bit--) bits((code >>> bit) & 1, 1);
        }
        void stored(byte[] bytes, boolean last) {
            bits(last ? 1 : 0, 3); align(); bits(bytes.length, 16); bits(bytes.length ^ 65535, 16);
            for (byte value : bytes) bits(value & 255, 8);
        }
        private void align() { if (count != 0) bits(0, 8 - count); }
        byte[] finish() { align(); return out.toByteArray(); }
    }
}
