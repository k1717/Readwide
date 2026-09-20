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
    @Test public void capabilityTableIsCompleteAndReadOnly() {
        assertEquals(20, SevenZCoderRegistry.entries().size());
        for (SevenZCoderRegistry.Entry entry : SevenZCoderRegistry.entries()) {
            assertSame(entry, SevenZCoderRegistry.find(id(entry.id)));
            assertEquals(entry.id.equals("0303011B") ? 4 : 1, entry.inputCount);
        }
        assertThrows(UnsupportedOperationException.class, () -> SevenZCoderRegistry.entries().clear());
    }

    @Test public void discoveryUsesExactIdsAndSharedSupplementalFlags() {
        for (String method : new String[]{"0303011B", "030401", "0A", "0B", "020302", "020304"}) {
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
