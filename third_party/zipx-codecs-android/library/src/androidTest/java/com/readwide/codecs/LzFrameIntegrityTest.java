package com.readwide.codecs;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Test;

/** Synthetic frame regressions; Android/JNI execution and producer validation are pending. */
public class LzFrameIntegrityTest {
    @Test public void checksumBuilderMatchesEmptyXxHashVector() {
        assertEquals(0x02cc5d05, hash(new byte[0]));
    }

    @Test public void checkedStoredBlocksCrossInputAndOutputBoundaries() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            for (int size : new int[]{1, 15, 16, 17, 65536}) {
                byte[] data = pattern(size);
                byte[] packed = storedFrame(lizard, 0x74, -1, data);
                assertArrayEquals(data, decode(packed, lizard, size, size < 100 ? 1 : 257));
            }
        }
    }

    @Test public void checkedLinkedLz5KeepsHistoryAcrossStoredAndCompressedBlocks() throws Exception {
        byte[] history = pattern(90000);
        int distance = 70000;
        byte[] match = {0x45, (byte)distance, (byte)(distance >>> 8),
                (byte)(distance >>> 16), 0x30, 9, 8, 7, 6, 5, 4};
        byte[] expected = Arrays.copyOf(history, history.length + 14);
        System.arraycopy(history, history.length - distance, expected, history.length, 8);
        System.arraycopy(new byte[]{9, 8, 7, 6, 5, 4}, 0, expected, history.length + 8, 6);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, false, 0x5c, expected.length);
        block(out, true, true, Arrays.copyOfRange(history, 0, 65536));
        block(out, true, true, new byte[0]); // Must not terminate or reset linked history.
        block(out, true, true, Arrays.copyOfRange(history, 65536, history.length));
        block(out, false, true, match);
        little(out, 0); little(out, hash(expected));
        assertArrayEquals(expected, decode(out.toByteArray(), false, expected.length, 31));
    }

    @Test public void checkedLizardCompressedFrameCanCarryAnInnerStoredBlock() throws Exception {
        byte[] data = pattern(32);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(10); payload.write(128); // Lizard level 10, inner stored block.
        payload.write(data.length); payload.write(0); payload.write(0); payload.write(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, true, 0x7c, data.length);
        block(out, false, true, payload.toByteArray());
        little(out, 0); little(out, hash(data));
        assertArrayEquals(data, decode(out.toByteArray(), true, data.length, 1));
    }

    @Test public void corruptBlockIsRejectedBeforeItsFirstByteAndFailureStaysTerminal() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            for (boolean stored : new boolean[]{false, true}) {
                for (int corruptAt : new int[]{11, 27}) { // Payload or its checksum.
                    byte[] packed = storedFrame(lizard, 0x70, -1, pattern(16));
                    // Invalid raw-compressed bytes must fail their checksum before decode.
                    if (!stored) packed[10] = 0;
                    packed[corruptAt] ^= 1;
                    try (InputStream in = LzFamilyInputStream.open(
                            new ByteArrayInputStream(packed), lizard, 16)) {
                        IOException error = assertThrows(IOException.class, in::read);
                        assertTrue(error.getMessage().contains("blockChecksum_invalid"));
                        assertSame(error, assertThrows(IOException.class, in::read));
                    }
                }
            }
        }
    }

    @Test public void truncatedBlockChecksumCannotPublishPayload() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            byte[] packed = storedFrame(lizard, 0x70, -1, pattern(16));
            for (int count = 0; count < 4; count++) {
                byte[] truncated = Arrays.copyOf(packed, 27 + count);
                try (InputStream in = LzFamilyInputStream.open(
                        new ByteArrayInputStream(truncated), lizard, 16)) {
                    assertThrows(IOException.class, in::read);
                }
            }
        }
    }

    @Test public void emptyStoredBlocksAreNotEndMarkersWithOrWithoutChecksums() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            for (int flags : new int[]{0x40, 0x50, 0x60, 0x70}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                header(out, lizard, flags, -1);
                boolean checked = (flags & 0x10) != 0;
                block(out, true, checked, new byte[0]);
                block(out, true, checked, new byte[]{7});
                block(out, true, checked, new byte[0]);
                block(out, true, checked, new byte[]{8});
                little(out, 0);
                assertArrayEquals(new byte[]{7, 8}, decode(out.toByteArray(), lizard, 2, 1));
            }
        }
    }

    @Test public void explicitZeroSizeIsEnforcedAndEmptyCheckedFramesAreValid() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            for (int flags : new int[]{0x68, 0x78, 0x7c}) {
                byte[] empty = storedFrame(lizard, flags, 0, new byte[0]);
                assertArrayEquals(new byte[0], decode(empty, lizard, 0, 1));
                byte[] nonempty = storedFrame(lizard, flags, 0, new byte[]{1});
                // Outer 7z size agrees with the payload, so only the inner frame rejects.
                assertThrows(IOException.class, () -> decode(nonempty, lizard, 1, 1));
            }
        }
    }

    @Test public void innerFrameSizeOverrunAndUnderrunAreRejectedIndependentlyOf7zSize() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            for (long declared : new long[]{1, 3, -1L}) {
                for (int flags : new int[]{0x68, 0x78}) {
                    byte[] packed = storedFrame(lizard, flags, declared, new byte[]{1, 2});
                    assertThrows(IOException.class, () -> decode(packed, lizard, 2, 1));
                }
            }
        }
    }

    @Test public void contentChecksumStillAppliesAfterAllBlockChecksumsPass() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            byte[] packed = storedFrame(lizard, 0x74, -1, pattern(32));
            packed[packed.length - 1] ^= 1;
            assertThrows(IOException.class, () -> decode(packed, lizard, 32, 1));
        }
    }

    @Test public void checkedFramesComposeWithMtAndUncheckedFrames() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            byte[] first = storedFrame(lizard, 0x7c, 2, new byte[]{1, 2});
            byte[] second = storedFrame(lizard, 0x60, -1, new byte[]{3});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            little(out, 0x184d2a50); little(out, 4); little(out, first.length);
            out.write(first); out.write(second);
            assertArrayEquals(new byte[]{1, 2, 3}, decode(out.toByteArray(), lizard, 3, 1));
            byte[] shortMt = out.toByteArray(); shortMt[8]--;
            assertThrows(IOException.class, () -> decode(shortMt, lizard, 3, 1));
        }
    }

    @Test public void checksumsDoNotPermitBlocksLargerThanTheirFormatDescriptor() throws Exception {
        for (boolean lizard : new boolean[]{false, true}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            header(out, lizard, 0x70, -1);
            little(out, 0x80000000 | (lizard ? 131073 : 65537));
            byte[] packed = out.toByteArray();
            assertThrows(IOException.class, () -> decode(packed, lizard, 1, 1));
        }
    }

    private static byte[] decode(byte[] packed, boolean lizard, long size, int fragment) throws Exception {
        InputStream source = new ByteArrayInputStream(packed) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, fragment));
            }
        };
        try (InputStream in = LzFamilyInputStream.open(source, lizard, size)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[257]; int count;
            while ((count = in.read(bytes)) != -1) {
                assertTrue(count > 0); out.write(bytes, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static byte[] storedFrame(boolean lizard, int flags, long size, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, lizard, flags, size);
        block(out, true, (flags & 0x10) != 0, data);
        little(out, 0);
        if ((flags & 4) != 0) little(out, hash(data));
        return out.toByteArray();
    }

    private static void header(ByteArrayOutputStream out, boolean lizard, int flags, long size)
            throws IOException {
        little(out, lizard ? 0x184d2206 : 0x184d2205);
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.write(flags); descriptor.write(0x10);
        if ((flags & 8) != 0) {
            little(descriptor, (int)size); little(descriptor, (int)(size >>> 32));
        }
        byte[] bytes = descriptor.toByteArray();
        out.write(bytes); out.write((hash(bytes) >>> 8) & 255);
    }

    private static void block(ByteArrayOutputStream out, boolean stored, boolean checked, byte[] data)
            throws IOException {
        little(out, data.length | (stored ? 0x80000000 : 0));
        out.write(data);
        if (checked) little(out, hash(data));
    }

    private static byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte)(i * 37 + (i >>> 8) * 13);
        return data;
    }

    private static void little(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < 4; i++) out.write((value >>> (i * 8)) & 255);
    }

    /** Seed-zero XXH32 for independently assembled frame fields, not production code. */
    private static int hash(byte[] bytes) {
        final int p1 = 0x9e3779b1, p2 = 0x85ebca77, p3 = 0xc2b2ae3d;
        final int p4 = 0x27d4eb2f, p5 = 0x165667b1;
        int offset = 0, value;
        if (bytes.length >= 16) {
            int a = p1 + p2, b = p2, c = 0, d = -p1;
            do {
                a = Integer.rotateLeft(a + word(bytes, offset) * p2, 13) * p1;
                b = Integer.rotateLeft(b + word(bytes, offset + 4) * p2, 13) * p1;
                c = Integer.rotateLeft(c + word(bytes, offset + 8) * p2, 13) * p1;
                d = Integer.rotateLeft(d + word(bytes, offset + 12) * p2, 13) * p1;
                offset += 16;
            } while (offset <= bytes.length - 16);
            value = Integer.rotateLeft(a, 1) + Integer.rotateLeft(b, 7)
                    + Integer.rotateLeft(c, 12) + Integer.rotateLeft(d, 18);
        } else value = p5;
        value += bytes.length;
        while (offset <= bytes.length - 4) {
            value = Integer.rotateLeft(value + word(bytes, offset) * p3, 17) * p4;
            offset += 4;
        }
        while (offset < bytes.length)
            value = Integer.rotateLeft(value + (bytes[offset++] & 255) * p5, 11) * p1;
        value ^= value >>> 15; value *= p2;
        value ^= value >>> 13; value *= p3; value ^= value >>> 16;
        return value;
    }

    private static int word(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                | ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24);
    }
}
