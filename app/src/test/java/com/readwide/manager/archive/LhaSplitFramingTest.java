package com.readwide.manager.archive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Framing only; these synthetic headers do not claim native checksum/codec validation. */
public class LhaSplitFramingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void walksAllDocumentedHeaderLevelsAndPayloadZeros() throws Exception {
        LhaSplitFraming.validate(file(join(entry(0, 4), entry(1, 7), entry(2, 3), entry(3, 5), new byte[]{0})));
        byte[] oldHeader = entry(0, 0);
        oldHeader[0] -= 2;
        LhaSplitFraming.validate(file(join(Arrays.copyOf(oldHeader, oldHeader.length - 2), new byte[]{0})));
    }

    @Test public void rejectsMissingEndMarkerEvenAtExactEntryBoundary() throws Exception {
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(entry(0, 4))));
    }

    @Test public void rejectsIndependentArchivesAndOtherTrailingBytes() throws Exception {
        byte[] complete = join(entry(0, 4), new byte[]{0});
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(join(complete, complete))));
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(join(complete, new byte[]{0}))));
    }

    @Test public void rejectsTruncationInvalidSizesAndUnknownHeaderLevels() throws Exception {
        byte[] value = entry(0, 4);
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(Arrays.copyOf(value, value.length - 1))));
        byte[] shortHeader = value.clone(); shortHeader[0] = 10;
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(shortHeader)));
        byte[] unknown = value.clone(); unknown[20] = 4;
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> LhaSplitFraming.validate(file(unknown)));
    }

    @Test public void walksExtendedHeadersAndHonorsLargeSizeOverride() throws Exception {
        for (int level : new int[]{1, 2}) {
            byte[] base = entry(level, 0);
            int extension = base.length - 2;
            byte[] value = Arrays.copyOf(base, base.length + 19 + 4 + 1);
            value[extension] = 19; value[extension + 2] = 0x42;
            put(value, extension + 3, level == 1 ? 23 : 4, 8);
            put(value, extension + 11, 4, 8);
            if (level == 1) put(value, 7, 0xffffffffL, 4); // Replaced after reading the extension.
            else { put(value, 0, base.length + 19, 2); put(value, 7, 0xffffffffL, 4); }
            LhaSplitFraming.validate(file(value));
            byte[] wrongWidth = value.clone(); wrongWidth[extension] = 20;
            assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(wrongWidth)));
            value[extension + 10] = (byte) 0x80;
            assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(value)));
        }
    }

    @Test public void rejectsSeparateMultidiscSemanticsAndBrokenExtensionLengths() throws Exception {
        byte[] value = entry(2, 0);
        value = Arrays.copyOf(value, 30);
        put(value, 0, 29, 2);
        value[24] = 3; value[26] = 0x39;
        File multidisc = file(value);
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class, () -> LhaSplitFraming.validate(multidisc));
        value[26] = 1; value[24] = 2;
        File invalid = file(value);
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(invalid));
    }

    @Test public void preservesCancellationAtEmptyArchiveEnd() throws Exception {
        File empty = file(new byte[]{0});
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, () -> LhaSplitFraming.validate(empty)); }
        finally { Thread.interrupted(); }
    }

    @Test public void level3WalksFourByteLengthsAndLargeSizeOverrides() throws Exception {
        byte[] base = entry(3, 0);
        byte[] value = Arrays.copyOf(base, base.length + 21 + 4 + 1);
        put(value, 24, base.length + 21, 4);
        put(value, 28, 21, 4); value[32] = 0x42;
        put(value, 33, 4, 8); put(value, 41, 4, 8);
        put(value, 7, 0xffffffffL, 4);
        LhaSplitFraming.validate(file(value));
        byte[] wrongWidth = value.clone(); put(wrongWidth, 28, 19, 4);
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(wrongWidth)));
        byte[] negativeSize = value.clone(); negativeSize[40] = (byte) 0x80;
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(negativeSize)));
    }

    @Test public void level3RejectsWrongWordWidthHeaderRangeAndTrailingHeaderPadding() throws Exception {
        byte[] valid = join(entry(3, 4), new byte[]{0});
        byte[] wordWidth = valid.clone(); wordWidth[0] = 2;
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(wordWidth)));
        byte[] tooSmall = valid.clone(); put(tooSmall, 24, 31, 4);
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(tooSmall)));
        byte[] unsignedLarge = valid.clone(); put(unsignedLarge, 24, 0x80000000L, 4);
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(unsignedLarge)));
        byte[] padding = Arrays.copyOf(valid, valid.length + 1); put(padding, 24, 33, 4);
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(padding)));
    }

    @Test public void level3RejectsTruncatedAndOverflowSizedExtensionsAndSeparateMultidisc() throws Exception {
        for (long length : new long[]{1, 4, 0x80000000L, 0xffffffffL}) {
            byte[] invalid = join(entry(3, 4), new byte[]{0}); put(invalid, 28, length, 4);
            assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(invalid)));
        }
        byte[] multidisc = Arrays.copyOf(entry(3, 0), 38);
        put(multidisc, 24, 37, 4); put(multidisc, 28, 5, 4); multidisc[32] = 0x39;
        assertThrows(ArchiveSupport.UnsupportedArchiveFeatureException.class,
                () -> LhaSplitFraming.validate(file(multidisc)));
        byte[] truncated = Arrays.copyOf(entry(3, 0), 27);
        assertThrows(IOException.class, () -> LhaSplitFraming.validate(file(truncated)));
    }

    private File file(byte[] data) throws IOException {
        File file = temporary.newFile(); Files.write(file.toPath(), data); return file;
    }

    private static byte[] entry(int level, int packed) {
        int header = level == 0 ? 25 : level == 1 ? 28 : level == 2 ? 26 : 32;
        byte[] bytes = new byte[header + packed];
        put(bytes, 0, level == 2 ? header : header - 2, level == 2 ? 2 : 1);
        if (level == 3) { put(bytes, 0, 4, 2); put(bytes, 24, header, 4); }
        bytes[2] = '-'; bytes[3] = 'l'; bytes[4] = 'h'; bytes[5] = '0'; bytes[6] = '-';
        put(bytes, 7, packed, 4); put(bytes, 11, packed, 4);
        bytes[19] = 0x20; bytes[20] = (byte) level;
        if (level < 2) { bytes[21] = 1; bytes[22] = 'x'; }
        return bytes;
    }

    private static void put(byte[] bytes, int offset, long value, int count) {
        for (int i = 0; i < count; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }

    private static byte[] join(byte[]... pieces) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] piece : pieces) output.write(piece);
        return output.toByteArray();
    }
}
