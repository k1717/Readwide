package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMAOutputStream;

public class SevenZCoderRegistryTest {
    @Test public void unadoptedDecoderCleanupClosesWithoutReadingAndRetainsPrimaryFailure() throws Exception {
        int[] closes = {0};
        InputStream input = new InputStream() {
            @Override public int read() { throw new AssertionError("Cleanup must not decode"); }
            @Override public void close() { closes[0]++; }
        };
        Error primary = new OutOfMemoryError("synthetic wrapper allocation failure");
        closeUnadopted(input, primary);
        assertEquals(1, closes[0]);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test public void unadoptedDecoderCleanupSuppressesCheckedUncheckedAndErrorFailures() throws Exception {
        for (Throwable cleanup : new Throwable[]{new IOException("close"),
                new IllegalStateException("close"), new AssertionError("close")}) {
            Error primary = new OutOfMemoryError("synthetic wrapper allocation failure");
            InputStream input = new InputStream() {
                @Override public int read() { throw new AssertionError("Cleanup must not decode"); }
                @Override public void close() throws IOException {
                    if (cleanup instanceof IOException) throw (IOException) cleanup;
                    if (cleanup instanceof RuntimeException) throw (RuntimeException) cleanup;
                    throw (Error) cleanup;
                }
            };
            closeUnadopted(input, primary);
            assertEquals(1, primary.getSuppressed().length);
            assertSame(cleanup, primary.getSuppressed()[0]);
        }
    }

    @Test public void unadoptedDecoderCleanupDoesNotSelfSuppress() throws Exception {
        Error primary = new OutOfMemoryError("synthetic failure");
        InputStream input = new InputStream() {
            @Override public int read() { throw new AssertionError("Cleanup must not decode"); }
            @Override public void close() { throw primary; }
        };
        closeUnadopted(input, primary);
        assertEquals(0, primary.getSuppressed().length);
    }

    // Exercises failure cleanup without inducing actual allocation failure or opening JNI.
    private static void closeUnadopted(InputStream input, Throwable primary) throws Exception {
        java.lang.reflect.Method method = SevenZCoderRegistry.class.getDeclaredMethod(
                "closeUnadopted", InputStream.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(null, input, primary);
    }

    @Test public void xzPayloadCorruptionAndTruncationAreTerminalAndSticky() throws Exception {
        SevenZCoderRegistry.Prepared lzma2 = SevenZCoderRegistry.require(id("21")).prepare(new byte[]{0}, 1);
        for (byte[] packed : new byte[][]{new byte[0], {3}, {1,0,0,42}}) {
            try (InputStream decoded = lzma2.open(new InputStream[]{new ByteArrayInputStream(packed)}, null)) {
                IOException failure = assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class,
                        () -> readAll(decoded));
                assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                        ArchiveFailureClassifier.classify(failure));
                assertSame(failure, assertThrows(IOException.class, () -> decoded.read()));
                assertSame(failure, assertThrows(IOException.class, () -> decoded.read(new byte[1])));
                assertEquals(0, decoded.read(new byte[0]));
            }
        }
        for (byte[] packed : new byte[][]{new byte[0], {1,0,0,0,0}}) {
            assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class, () -> rawLzma(packed, 0));
        }
    }

    @Test public void xzPayloadWrapperKeepsValidEmptyAndUncompressedStreams() throws Exception {
        for (byte[] raw : new byte[][]{new byte[0], {42}}) {
            byte[] packed = raw.length == 0 ? new byte[]{0} : new byte[]{1,0,0,42,0};
            SevenZCoderRegistry.Prepared plan = SevenZCoderRegistry.require(id("21")).prepare(new byte[]{0}, raw.length);
            try (InputStream decoded = plan.open(new InputStream[]{new ByteArrayInputStream(packed)}, null)) {
                assertArrayEquals(raw, readAll(decoded));
                assertEquals(-1, decoded.read());
            }
        }
    }

    @Test public void xzPayloadWrapperPreservesInputErrorIdentityAndCancellation() throws Exception {
        for (IOException original : new IOException[]{new IOException("source unavailable"),
                new java.io.EOFException("source changed"), new org.tukaani.xz.CorruptedInputException(),
                new org.tukaani.xz.UnsupportedOptionsException("upstream option"),
                new java.io.InterruptedIOException("cancelled")}) {
            for (boolean lzma : new boolean[]{true, false}) {
                InputStream source = new InputStream() {
                    @Override public int read() throws IOException { throw original; }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException { throw original; }
                };
                SevenZCoderRegistry.Prepared plan = lzma
                        ? SevenZCoderRegistry.require(id("030101")).prepare(new byte[]{0x5d,0,0,16,0}, 1)
                        : SevenZCoderRegistry.require(id("21")).prepare(new byte[]{0}, 1);
                if (lzma) {
                    assertSame(original, assertThrows(IOException.class,
                            () -> plan.open(new InputStream[]{source}, null)));
                } else {
                    try (InputStream decoded = plan.open(new InputStream[]{source}, null)) {
                        assertSame(original, assertThrows(IOException.class, () -> decoded.read()));
                        assertSame(original, assertThrows(IOException.class, () -> decoded.read(new byte[1])));
                    }
                }
            }
        }
    }

    @Test public void xzInvalidPropertiesAreStillPreparationErrors() {
        IOException failure = assertThrows(org.tukaani.xz.CorruptedInputException.class,
                () -> SevenZCoderRegistry.require(id("030101")).prepare(new byte[]{(byte)255,0,0,16,0}, 1));
        assertFalse(failure instanceof SevenZBcj2ArchiveReader.IntegrityException);
    }

    @Test public void preparedPpmdOpensIndependentModelsAndDoesNotRetainFailedStreamState() throws Exception {
        byte[] properties = {6,0,0,1,0};
        SevenZCoderRegistry.Prepared plan = SevenZCoderRegistry.require(id("030401")).prepare(properties, 1);
        properties[0] = 1; // Owned properties, not caller state.
        try (InputStream bad = plan.open(new InputStream[]{new ByteArrayInputStream(new byte[]{0,0,0,0,1,0})}, null);
             InputStream good = plan.open(new InputStream[]{new ByteArrayInputStream(new byte[6])}, null)) {
            IOException failure = assertThrows(SevenZBcj2ArchiveReader.IntegrityException.class, () -> bad.read());
            assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE, ArchiveFailureClassifier.classify(failure));
            assertEquals(ArchiveSupport.ExtractionFailure.CORRUPT_ARCHIVE,
                    ArchiveFailureClassifier.classify(new IOException("unsupported password in display name", failure)));
            assertSame(failure, assertThrows(IOException.class, () -> bad.read()));
            assertEquals(0, good.read()); assertEquals(-1, good.read());
        }
        try (InputStream again = plan.open(new InputStream[]{new ByteArrayInputStream(new byte[6])}, null)) {
            assertEquals(0, again.read()); assertEquals(-1, again.read());
        }
    }

    @Test public void capabilityTableIsCompleteAndReadOnly() {
        assertEquals(26, SevenZCoderRegistry.entries().size());
        for (SevenZCoderRegistry.Entry entry : SevenZCoderRegistry.entries()) {
            assertSame(entry, SevenZCoderRegistry.find(id(entry.id)));
            assertEquals(entry.id.equals("0303011B") ? 4 : 1, entry.inputCount);
        }
        assertThrows(UnsupportedOperationException.class, () -> SevenZCoderRegistry.entries().clear());
    }

    @Test public void discoveryUsesExactIdsAndSharedSupplementalFlags() {
        for (String method : new String[]{"0303011B", "030401", "0A", "0B", "020302", "020304", "040202", "04F71001", "04F71101", "04F71102", "04F71104", "04F71105", "04F71106"}) {
            assertTrue(SevenZCoderRegistry.requiresSupplemental(id(method)));
        }
        assertFalse(SevenZCoderRegistry.requiresSupplemental(id("21")));
        assertTrue(SevenZCoderRegistry.find(id("0B")).isModernBcj());
        assertFalse(SevenZCoderRegistry.find(id("03030103")).isModernBcj());
        assertNull(SevenZCoderRegistry.find(id("0A00")));
        assertNull(SevenZCoderRegistry.find(id("0000")));
        assertNull(SevenZCoderRegistry.find(null));
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> SevenZCoderRegistry.require(id("FF")));
    }

    @Test public void forwardRoutingUsesTheSameCapabilityTableWithoutOpeningCodecs() {
        for(String method:new String[]{"020302","020304","040202","04F71001","04F71102","04F71104","04F71105","04F71106"}) {
            assertTrue(method,SevenZCoderRegistry.requiresDedicatedForward(id(method)));
            assertTrue(SevenZCoderRegistry.requiresSupplemental(id(method)));
        }
        for(String method:new String[]{"00","21","030101","0303011B","04F71101","040108","FF"}) {
            assertFalse(method,SevenZCoderRegistry.requiresDedicatedForward(id(method)));
        }
    }

    @Test public void invalidPropertiesFailWithoutPayloadOrPassword() {
        String[] methods = {"00", "030101", "21", "06F10701", "0303011B", "030401", "040108", "03", "0A"};
        byte[][] properties = {{1}, {0x5d}, {0x40}, {(byte)0xff, (byte)0xff}, {1}, {1,0,0,1,0}, {1}, {}, {1}};
        for (int i = 0; i < methods.length; i++) {
            SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.find(id(methods[i]));
            byte[] props = properties[i];
            assertThrows(IOException.class, () -> entry.prepare(props, 32));
        }
        assertThrows(IOException.class, () -> SevenZCoderRegistry.find(id("21")).prepare(new byte[]{0,0}, 32));
        assertThrows(IOException.class, () -> SevenZCoderRegistry.find(id("030101"))
                .prepare(new byte[]{(byte)0xff,0,0,1,0}, 32));
    }

    @Test public void preparedDecoderOwnsItsPropertySnapshot() throws Exception {
        byte[] props = {0}; // Delta distance one.
        SevenZCoderRegistry.Prepared prepared = SevenZCoderRegistry.find(id("03")).prepare(props, 4);
        props[0] = 1;
        try (InputStream decoded = prepared.open(new InputStream[]{new ByteArrayInputStream(new byte[]{1,1,1,1})}, null)) {
            assertArrayEquals(new byte[]{1,2,3,4}, readAll(decoded));
        }
    }

    @Test public void zstdPropertiesAreCheckedWithoutLoadingNativeCode() throws Exception {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.require(id("04F71101"));
        for (byte[] props : new byte[][]{{0}, {1,5,3}, {1,5,3,0,0}}) {
            assertEquals(Long.MAX_VALUE, entry.prepare(props, Long.MAX_VALUE).outputSize);
        }
        for (int length : new int[]{0,2,4,6}) {
            assertThrows(IOException.class, () -> entry.prepare(new byte[length], 32));
        }
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> entry.prepare(new byte[]{1}, 32));
    }

    @Test public void lz4ProducerPropertiesHaveExactFiveByteShape() throws Exception {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.require(id("04F71104"));
        assertEquals(Long.MAX_VALUE, entry.prepare(new byte[]{1,9,1,0,0}, Long.MAX_VALUE).outputSize);
        for (int length : new int[]{0,1,2,3,4,6}) {
            assertThrows(IOException.class, () -> entry.prepare(new byte[length], 32));
        }
    }

    @Test public void brotliPropertiesHaveExactThreeByteShape() throws Exception {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.require(id("04F71102"));
        assertEquals(Long.MAX_VALUE, entry.prepare(new byte[]{1,0,11}, Long.MAX_VALUE).outputSize);
        for (int length : new int[]{0,1,2,4,5}) {
            assertThrows(IOException.class, () -> entry.prepare(new byte[length], 32));
        }
    }

    @Test public void lz5AndLizardPropertiesAreCheckedWithoutLoadingNativeCode() throws Exception {
        for (String method : new String[]{"04F71105", "04F71106"}) {
            int wanted = method.endsWith("05") ? 5 : 3;
            SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.require(id(method));
            assertEquals(Long.MAX_VALUE, entry.prepare(new byte[wanted], Long.MAX_VALUE).outputSize);
            for (int length = 0; length <= 6; length++) {
                if (length == wanted) continue;
                byte[] properties = new byte[length];
                assertThrows(IOException.class, () -> entry.prepare(properties, 32));
            }
        }
    }

    @Test public void lzhamPropertiesDoNotLoadNativeOrImposePayloadCaps() throws Exception {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.require(id("04F71001"));
        for (int version : new int[]{0x10,0x11}) {
            for (int bits : new int[]{0,15,16,26,27,28,29}) {
                assertEquals(Long.MAX_VALUE, entry.prepare(new byte[]{(byte)version,(byte)bits,4,6,0}, Long.MAX_VALUE).outputSize);
            }
            // Descriptive encoder metadata does not select custom table settings.
            assertEquals(7, entry.prepare(new byte[]{(byte)version,15,(byte)255,(byte)255,(byte)255},7).outputSize);
        }
    }

    @Test public void lzhamRejectsUnverifiedVersionsAndMalformedDictionaryProperties() throws Exception {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.require(id("04F71001"));
        for (int length : new int[]{0,1,2,3,4,6}) {
            assertThrows(IOException.class, () -> entry.prepare(new byte[length],0));
        }
        for (int version = 0; version <= 255; version++) {
            if (version == 0x10 || version == 0x11) continue;
            final byte propertyVersion = (byte)version;
            assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                    () -> entry.prepare(new byte[]{propertyVersion,15,0,0,0},0));
        }
        for (int version : new int[]{0x10,0x11}) {
            for (int bits : new int[]{1,14,30,255}) {
                assertThrows(IOException.class, () -> entry.prepare(new byte[]{(byte)version,(byte)bits,0,0,0},0));
            }
        }
    }

    @Test public void hugeDeclaredDictionariesDecodeSmallLzmaAndLzma2Streams() throws Exception {
        byte[] raw = new byte[32768];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte)(i % 37);
        LZMA2Options options = new LZMA2Options(1);
        options.setDictSize(1 << 20);
        ByteArrayOutputStream lzma = new ByteArrayOutputStream();
        try (LZMAOutputStream encoded = new LZMAOutputStream(lzma, options, false)) { encoded.write(raw); }
        int lcLpPb = (options.getPb() * 5 + options.getLp()) * 9 + options.getLc();
        SevenZCoderRegistry.Prepared first = SevenZCoderRegistry.find(id("030101")).prepare(
                new byte[]{(byte)lcLpPb, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff}, raw.length);
        try (InputStream decoded = first.open(new InputStream[]{new ByteArrayInputStream(lzma.toByteArray())}, null)) {
            assertArrayEquals(raw, readAll(decoded));
        }
        ByteArrayOutputStream lzma2 = new ByteArrayOutputStream();
        try (FinishableOutputStream encoded = options.getOutputStream(new FinishableWrapperOutputStream(lzma2))) {
            encoded.write(raw);
        }
        SevenZCoderRegistry.Prepared second = SevenZCoderRegistry.find(id("21")).prepare(new byte[]{40}, raw.length);
        try (InputStream decoded = second.open(new InputStream[]{new ByteArrayInputStream(lzma2.toByteArray())}, null)) {
            assertArrayEquals(raw, readAll(decoded));
        }
    }

    @Test public void dictionarySizingDoesNotImposeAnOutputSizeCap() throws Exception {
        int maximum = LZMA2InputStream.DICT_SIZE_MAX;
        assertEquals(4096, SevenZCoderRegistry.boundedDictionary(0xffffffffL, 0, maximum));
        assertEquals(4096, SevenZCoderRegistry.boundedDictionary(0xffffffffL, 32, maximum));
        assertEquals(32769, SevenZCoderRegistry.boundedDictionary(0xffffffffL, 32769, maximum));
        assertEquals(1 << 20, SevenZCoderRegistry.boundedDictionary(1 << 20, Long.MAX_VALUE, maximum));
        assertEquals(Long.MAX_VALUE, SevenZCoderRegistry.find(id("21")).prepare(new byte[]{0}, Long.MAX_VALUE).outputSize);
    }

    @Test public void lzma2PreparationRetainsTheRawDecoderDictionaryRange() throws Exception {
        // Preparation must not allocate a dictionary or inherit the options
        // encoder's smaller ceiling. Opening these sizes requires real memory.
        for (int dictionary : new int[]{LZMA2Options.DICT_SIZE_MAX,
                LZMA2Options.DICT_SIZE_MAX + 16, LZMA2InputStream.DICT_SIZE_MAX}) {
            assertEquals(dictionary, SevenZCoderRegistry.boundedDictionary(
                    0xffffffffL, dictionary, LZMA2InputStream.DICT_SIZE_MAX));
            assertEquals(dictionary, SevenZCoderRegistry.require(id("21"))
                    .prepare(new byte[]{40}, dictionary).outputSize);
        }
    }

    @Test public void knownSizeLzmaAcceptsBothEndingsIncludingEmptyOutput() throws Exception {
        for (int length : new int[]{0, 1, 32768}) {
            byte[] raw = new byte[length];
            new java.util.Random(51).nextBytes(raw);
            for (boolean marker : new boolean[]{false, true}) {
                try (InputStream decoded = rawLzma(lzmaBytes(raw, marker), raw.length)) {
                    assertArrayEquals(raw, readAll(decoded));
                }
            }
        }
    }

    @Test public void optionalLzmaMarkerDoesNotPermitWrongOutputSize() throws Exception {
        byte[] raw = new byte[32768];
        new java.util.Random(52).nextBytes(raw);
        for (boolean marker : new boolean[]{false, true}) {
            byte[] packed = lzmaBytes(raw, marker);
            for (int size : new int[]{raw.length - 1, raw.length + 1}) {
                assertThrows(IOException.class, () -> {
                    try (InputStream decoded = rawLzma(packed, size)) { readAll(decoded); }
                });
            }
        }
    }

    @Test public void optionalLzmaMarkerStillRejectsTruncatedOrDamagedEnding() throws Exception {
        byte[] raw = new byte[16384];
        new java.util.Random(53).nextBytes(raw);
        byte[] packed = lzmaBytes(raw, true);
        for (int removed : new int[]{1, 3, 8}) {
            byte[] truncated = java.util.Arrays.copyOf(packed, packed.length - removed);
            assertThrows(IOException.class, () -> {
                try (InputStream decoded = rawLzma(truncated, raw.length)) { readAll(decoded); }
            });
        }
        packed[packed.length - 1] ^= 1;
        assertThrows(IOException.class, () -> {
            try (InputStream decoded = rawLzma(packed, raw.length)) { readAll(decoded); }
        });
    }

    private static byte[] lzmaBytes(byte[] raw, boolean marker) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(1);
        options.setDictSize(1 << 20);
        try (LZMAOutputStream encoded = new LZMAOutputStream(packed, options, marker)) { encoded.write(raw); }
        return packed.toByteArray();
    }

    private static InputStream rawLzma(byte[] packed, long size) throws IOException {
        return SevenZCoderRegistry.require(id("030101"))
                .prepare(new byte[]{0x5d, 0, 0, 16, 0}, size)
                .open(new InputStream[]{new ByteArrayInputStream(packed)}, null);
    }

    @Test public void genuinelyUnrepresentableDictionariesRemainExplicitlyUnsupported() {
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> SevenZCoderRegistry.boundedDictionary(0xffffffffL, 0xffffffffL, LZMAInputStream.DICT_SIZE_MAX));
        assertThrows(IOException.class, () -> SevenZCoderRegistry.boundedDictionary(-1, 32, LZMAInputStream.DICT_SIZE_MAX));
        assertThrows(IOException.class, () -> SevenZCoderRegistry.find(id("00")).prepare(null, -1));
    }

    @Test public void openingRequiresCompleteInputsAndChecksCancellation() throws Exception {
        SevenZCoderRegistry.Prepared copy = SevenZCoderRegistry.find(id("00")).prepare(null, 0);
        assertThrows(IOException.class, () -> copy.open(new InputStream[0], null));
        assertThrows(IOException.class, () -> copy.open(new InputStream[]{null}, null));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> copy.open(new InputStream[]{new ByteArrayInputStream(new byte[0])}, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    static byte[] id(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[997];
        int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        return output.toByteArray();
    }
}
