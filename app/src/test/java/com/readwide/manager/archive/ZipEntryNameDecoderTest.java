package com.readwide.manager.archive;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.Test;
import static org.junit.Assert.*;

/** Preserve Unicode Path semantics while skipping unnecessary raw-name CRC work. */
public class ZipEntryNameDecoderTest {
    private static final byte[] RAW = "legacy.txt".getBytes(StandardCharsets.US_ASCII);

    @Test public void absentUnrelatedAndFlaggedFieldsDoNotOverrideNames() throws Exception {
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, null));
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, new byte[0]));
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, new byte[]{1, 0, 2, 0, 7, 8}));
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, true, field("different.txt")));
    }

    @Test public void validFieldAfterOtherExtrasHonorsSliceAndNormalization() throws Exception {
        byte[] valid = field("cafe\u0301\\page.txt");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(99);
        bytes.write(new byte[]{1, 0, 2, 0, 7, 8});
        bytes.write(valid);
        bytes.write(99);
        byte[] extras = bytes.toByteArray();
        assertEquals("café/page.txt", ZipEntryNameDecoder.unicodePath(RAW, false, extras, 1, extras.length - 2));
    }

    @Test public void badChecksumVersionOrUtf8IsNotTrusted() throws Exception {
        byte[] valid = field("page.txt");
        byte[] badCrc = valid.clone(); badCrc[5] ^= 1;
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, badCrc));
        byte[] badVersion = valid.clone(); badVersion[4] = 2;
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, badVersion));
        byte[] badUtf8 = valid.clone(); badUtf8[9] = (byte) 0xff;
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, badUtf8));
    }

    @Test public void repeatedFieldsMustAgreeWithoutChangingFirstMatchRules() throws Exception {
        assertEquals("page.txt", ZipEntryNameDecoder.unicodePath(RAW, false, join(field("page.txt"), field("page.txt"))));
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, join(field("page.txt"), field("other.txt"))));
        byte[] stale = field("stale.txt"); stale[5] ^= 1;
        assertEquals("page.txt", ZipEntryNameDecoder.unicodePath(RAW, false, join(stale, field("page.txt"))));
    }

    @Test public void invalidRangesAndTruncatedFieldsStayRejected() throws Exception {
        byte[] valid = field("page.txt");
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, valid, -1, 1));
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, valid, 1, Integer.MAX_VALUE));
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, Arrays.copyOf(valid, valid.length - 1)));
        assertNull(ZipEntryNameDecoder.unicodePath(RAW, false, join(valid, new byte[]{1, 0, 5, 0})));
    }

    private static byte[] field(String name) throws Exception {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int length = utf8.length + 5;
        result.write(0x75); result.write(0x70);
        result.write(length); result.write(length >>> 8); result.write(1);
        CRC32 crc = new CRC32(); crc.update(RAW);
        for (int i = 0; i < 4; i++) result.write((int) (crc.getValue() >>> (8 * i)));
        result.write(utf8);
        return result.toByteArray();
    }

    private static byte[] join(byte[] first, byte[] second) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(first); bytes.write(second);
        return bytes.toByteArray();
    }
}
