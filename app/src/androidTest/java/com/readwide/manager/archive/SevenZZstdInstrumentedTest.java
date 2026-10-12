package com.readwide.manager.archive;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Device-only native decoding; fixtures are generated from first-party bytes. */
@RunWith(AndroidJUnit4.class)
public class SevenZZstdInstrumentedTest {
    private static final byte[] PAYLOAD = {1,2,3,4,5,6,7,8};
    private static final char[] PASSWORD = {'p'};

    @Test public void compressedFrameUsesBundledNativeDecoder() throws Exception {
        // Existing first-party tar fixture from ZstdLz4ArchiveSupportTest (zstd -19).
        String packed = "KLUv/WAAJ00FALIHHR+g7YSqWlfHXqodswpk3Uxyk5qBRO51q1WkSQ3m9xjKmqac13if9hfYIIiJerY18EhTQAg/U+zK50YS"
                + "9M5PkEBhmv/Nr55F0N9NGnS3tt86wnTeC62V8tyti9anGNtqb8X4Esa11OJK7z0q7VkXdKYkLVcBESDwLk1cDyRgMDwo5m7K"
                + "/8XCGHUUwTkAUzAA7DCsatjBi+elJHAp9yKxlVsGDbCaCh0=";
        byte[] tar = decode(android.util.Base64.decode(packed, android.util.Base64.DEFAULT));
        assertEquals(10240, tar.length);
        assertEquals("payload.txt", new String(tar, 0, 11, StandardCharsets.UTF_8));
        byte[] payload = "Readwide zstd/lz4 fixture payload. UTF-8: 한글 내용.\n".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(payload, Arrays.copyOfRange(tar, 512, 512 + payload.length));
    }

    @Test public void rawFramesStreamAcrossBlocksAndSmallReads() throws Exception {
        byte[] payload = new byte[200003];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte)(i * 31);
        assertArrayEquals(payload, decode(rawFrame(payload)));
        assertArrayEquals(new byte[0], decode(rawFrame(new byte[0])));
    }

    @Test public void nestedZstdPayloadIsNotDecodedTwiceAndFramesCanConcatenate() throws Exception {
        byte[] inner = rawFrame(PAYLOAD);
        assertArrayEquals(inner, decode(rawFrame(inner)));
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(rawFrame(new byte[0])); joined.write(inner); joined.write(inner);
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8,1,2,3,4,5,6,7,8}, decode(joined.toByteArray()));
    }

    @Test public void standaloneRawZstdAlsoDecodesExactlyOneLayer() throws Exception {
        File archive = temporary(".zst");
        byte[] inner = rawFrame(PAYLOAD);
        try {
            assertThrows(IOException.class, () -> LibarchiveNativeBridge.openRawForwardStream(archive.getPath()));
            try (FileOutputStream output = new FileOutputStream(archive)) { output.write(rawFrame(inner)); }
            LibarchiveNativeBridge.ForwardStream reader = LibarchiveNativeBridge.openRawForwardStream(archive.getPath());
            try {
                assertNotNull(reader.nextEntry());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[3]; int count;
                while ((count = reader.read(buffer)) != -1) bytes.write(buffer, 0, count);
                assertArrayEquals(inner, bytes.toByteArray());
                assertNull(reader.nextEntry());
            } finally { reader.close(); }
        } finally { archive.delete(); }
    }

    @Test public void invalidMissingTruncatedAndBadChecksumFramesFailAndCloseInput() throws Exception {
        byte[] frame = rawFrame(PAYLOAD);
        byte[] checksum = Arrays.copyOf(frame, frame.length + 4);
        checksum[4] |= 4; // Require a content checksum; deliberately supply an incorrect value.
        for (byte[] invalid : new byte[][]{new byte[0], {1,2,3,4},
                Arrays.copyOf(frame, frame.length - 1), checksum}) {
            TrackedInput input = new TrackedInput(invalid);
            assertThrows(IOException.class, () -> {
                try (InputStream decoded = LibarchiveZstdInputStream.open(input)) { readAll(decoded); }
            });
            assertEquals(1, input.closes);
        }
    }

    @Test public void zeroProgressInputAndCancellationFailClosed() throws Exception {
        TrackedInput stalled = new TrackedInput(rawFrame(PAYLOAD)) {
            @Override public synchronized int read(byte[] b, int off, int len) { return 0; }
        };
        assertThrows(IOException.class, () -> LibarchiveZstdInputStream.open(stalled));
        assertEquals(1, stalled.closes);
        TrackedInput interrupted = new TrackedInput(rawFrame(PAYLOAD));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> LibarchiveZstdInputStream.open(interrupted));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, interrupted.closes);
        } finally { Thread.interrupted(); }

        TrackedInput source = new TrackedInput(rawFrame(PAYLOAD));
        InputStream decoded = LibarchiveZstdInputStream.open(source);
        try {
            assertEquals(0, decoded.read(new byte[0]));
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, decoded::read);
            Thread.interrupted();
            assertThrows(IOException.class, decoded::read); // A failed stream cannot revive.
        } finally { Thread.interrupted(); decoded.close(); decoded.close(); }
        assertEquals(1, source.closes);
    }

    @Test public void sevenZPlainAndAesZstdExtractAndOpenForwardReader() throws Exception {
        for (boolean encrypted : new boolean[]{false, true}) {
            File archive = fixture(encrypted, false, 0);
            File out = temporary(".out");
            File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
            try {
                assertTrue(SevenZBcj2ArchiveReader.extractSingleEntry(archive, "a.bin", out, PASSWORD));
                try (InputStream input = new FileInputStream(out)) { assertArrayEquals(PAYLOAD, readAll(input)); }
                try (ArchiveSupport.ForwardArchiveReader reader =
                             SevenZBcj2ArchiveReader.openSpecialForwardReader(archive, PASSWORD, cache)) {
                    assertNotNull(reader);
                    assertNotNull(reader.nextEntry());
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[3]; int count;
                    while ((count = reader.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    assertArrayEquals(PAYLOAD, bytes.toByteArray());
                    assertNull(reader.nextEntry());
                }
                if (encrypted) {
                    assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader
                            .extractSingleEntry(archive, "a.bin", out, new char[]{'x'}));
                }
            } finally { archive.delete(); out.delete(); }
        }
    }

    @Test public void sevenZCrcAndDeclaredOutputSizeStillRejectBadStreams() throws Exception {
        for (int change : new int[]{-1,0,1}) {
            File archive = fixture(true, change == 0, change);
            File out = temporary(".out");
            try {
                assertThrows(IOException.class, () -> SevenZBcj2ArchiveReader
                        .extractSingleEntry(archive, "a.bin", out, PASSWORD));
            } finally { archive.delete(); out.delete(); }
        }
    }

    private static byte[] decode(byte[] frame) throws IOException {
        try (InputStream decoded = LibarchiveZstdInputStream.open(new ByteArrayInputStream(frame))) {
            return readAll(decoded);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[997]; int count;
        while ((count = input.read(buffer, 2, buffer.length - 4)) != -1) bytes.write(buffer, 2, count);
        return bytes.toByteArray();
    }

    private static byte[] rawFrame(byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(new byte[]{0x28,(byte)0xb5,0x2f,(byte)0xfd,(byte)0xa0});
        little(frame, payload.length, 4); // Single segment, four-byte content size.
        int offset = 0;
        do {
            int count = Math.min(65536, payload.length - offset);
            little(frame, ((long)count << 3) | (offset + count == payload.length ? 1 : 0), 3);
            frame.write(payload, offset, count); offset += count;
        } while (offset < payload.length);
        return frame.toByteArray();
    }

    private static File fixture(boolean encrypted, boolean badCrc, int sizeChange) throws Exception {
        byte[] frame = rawFrame(PAYLOAD), packed = frame;
        if (encrypted) {
            byte[] key = new byte[32]; key[0] = 'p'; // 7z direct-key mode, UTF-16LE password.
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            packed = cipher.doFinal(Arrays.copyOf(frame, (frame.length + 15) / 16 * 16));
        }
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(new byte[]{1,4,6,0,1,9,(byte)packed.length,10,1});
        little(header, crc(packed), 4);
        header.write(new byte[]{0,7,11,1,0,(byte)(encrypted ? 2 : 1)});
        if (encrypted) header.write(new byte[]{0x24,6,(byte)0xf1,7,1,1,0x3f});
        header.write(new byte[]{0x24,4,(byte)0xf7,0x11,1,1,0});
        if (encrypted) header.write(new byte[]{1,0}); // Zstd input bound to AES output.
        header.write(12);
        if (encrypted) header.write(frame.length);
        header.write(PAYLOAD.length + sizeChange);
        header.write(new byte[]{10,1}); little(header, crc(PAYLOAD) ^ (badCrc ? 1 : 0), 4);
        header.write(new byte[]{0,0,5,1,17,13,0});
        header.write("a.bin\0".getBytes(StandardCharsets.UTF_16LE)); header.write(new byte[]{0,0});
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        little(start, packed.length, 8); little(start, header.size(), 8); little(start, crc(header.toByteArray()), 4);
        File archive = temporary(".7z");
        try (FileOutputStream output = new FileOutputStream(archive)) {
            output.write(new byte[]{'7','z',(byte)0xbc,(byte)0xaf,0x27,0x1c,0,4});
            ByteArrayOutputStream checksum = new ByteArrayOutputStream(); little(checksum, crc(start.toByteArray()), 4);
            output.write(checksum.toByteArray()); output.write(start.toByteArray()); output.write(packed); output.write(header.toByteArray());
        }
        return archive;
    }

    private static File temporary(String suffix) throws IOException {
        return File.createTempFile("seven-zstd-", suffix,
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    }
    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }
    private static void little(ByteArrayOutputStream out, long value, int count) {
        for (int i = 0; i < count; i++) out.write((int)(value >>> (8 * i)) & 255);
    }
    private static class TrackedInput extends ByteArrayInputStream {
        int closes;
        TrackedInput(byte[] bytes) { super(bytes); }
        @Override public void close() { closes++; }
    }
}
