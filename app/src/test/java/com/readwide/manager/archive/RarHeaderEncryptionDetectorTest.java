package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.zip.CRC32;

public class RarHeaderEncryptionDetectorTest {
    private static final byte[] RAR4_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
    };
    private static final byte[] RAR5_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00
    };
    private static final int RAR4_HEADER_MAIN = 0x73;
    private static final int RAR4_MAIN_PASSWORD = 0x0080;

    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void rar4MainPasswordFlag_isDetectedAsHeaderEncrypted() throws Exception {
        File rar = File.createTempFile("textview-rar4-hp-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR4_SIGNATURE);
                writeRar4Header(out, RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[6]);
            }
            assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void rar4NormalMainHeader_isNotHeaderEncrypted() throws Exception {
        File rar = File.createTempFile("textview-rar4-normal-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR4_SIGNATURE);
                writeRar4Header(out, RAR4_HEADER_MAIN, 0, new byte[6]);
            }
            assertFalse(RarHeaderEncryptionDetector.hasEncryptedHeaders(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void rar5EncryptionHeader_isDetectedAsHeaderEncrypted() throws Exception {
        File rar = File.createTempFile("textview-rar5-hp-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR5_SIGNATURE);
                out.write(rar5Header(encryptionHeaderData()));
            }
            assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void headerEncryptedRarVersion_reportsRar4() throws Exception {
        File rar = File.createTempFile("textview-rar4-hp-ver-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR4_SIGNATURE);
                writeRar4Header(out, RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[6]);
            }
            // RAR4 -hp has a first-party header rewriter, so the version must be
            // reported as 4 (drives the RAR4 rewriter/libarchive message, not the
            // RAR5 first-party header-parser-gap message).
            assertEquals(4, RarHeaderEncryptionDetector.headerEncryptedRarVersion(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void headerEncryptedRarVersion_reportsRar5() throws Exception {
        File rar = File.createTempFile("textview-rar5-hp-ver-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR5_SIGNATURE);
                out.write(rar5Header(encryptionHeaderData()));
            }
            assertEquals(5, RarHeaderEncryptionDetector.headerEncryptedRarVersion(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void headerEncryptedRarVersion_reportsZeroWhenNotEncrypted() throws Exception {
        File rar = File.createTempFile("textview-rar4-normal-ver-", ".rar");
        try {
            try (FileOutputStream out = new FileOutputStream(rar)) {
                out.write(RAR4_SIGNATURE);
                writeRar4Header(out, RAR4_HEADER_MAIN, 0, new byte[6]);
            }
            assertEquals(0, RarHeaderEncryptionDetector.headerEncryptedRarVersion(rar));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rar.delete();
        }
    }

    @Test
    public void rar4CorruptPasswordFlagDoesNotRequestPassword() throws Exception {
        byte[] main = rar4Header(RAR4_HEADER_MAIN, 0, new byte[6]);
        main[3] |= (byte) RAR4_MAIN_PASSWORD;
        File archive = writeRar4(main);
        assertNotEncrypted(archive);
        RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                archive, null, new IOException("Header CRC mismatch"));
    }

    @Test
    public void rar4DamagedEncryptedMainCrcDoesNotRequestPassword() throws Exception {
        byte[] main = rar4Header(RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[6]);
        main[0] ^= 1;
        File archive = writeRar4(main);
        assertNotEncrypted(archive);
        RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                archive, null, new IOException("Damaged header"));
    }

    @Test
    public void rar4MainCommentBytesAreOutsideMainCrc() throws Exception {
        byte[] body = concat(new byte[6], new byte[] {1, 2, 3, 4});
        byte[] main = rar4Header(RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD | 0x0002, body);
        File archive = writeRar4(main);
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(archive));
        assertEquals(4, RarHeaderEncryptionDetector.headerEncryptedRarVersion(archive));
        main[main.length - 1] ^= 1;
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(writeRar4(main)));
    }

    @Test
    public void rar4EncryptionVersionIsCoveredBeforeTrailingComment() throws Exception {
        byte[] body = concat(new byte[6], new byte[] {29, 1, 2, 3});
        byte[] main = rar4Header(RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD | 0x0202, body);
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(writeRar4(main)));
        main[14] ^= 1;
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(writeRar4(main)));
        main[13] ^= 1;
        assertNotEncrypted(writeRar4(main));
    }

    @Test
    public void rar4EncryptedMainRequiresAllCrcCoveredFields() throws Exception {
        for (int length : new int[] {0, 1, 5}) {
            assertNotEncrypted(writeRar4(rar4Header(
                    RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[length])));
        }
        assertNotEncrypted(writeRar4(rar4Header(
                RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD | 0x0200, new byte[6])));
    }

    @Test
    public void rar4EncryptedMainCannotExceedAvailableHeaderBytes() throws Exception {
        byte[] complete = rar4Header(RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[6]);
        byte[] truncated = new byte[complete.length - 1];
        System.arraycopy(complete, 0, truncated, 0, truncated.length);
        assertNotEncrypted(writeRar4(truncated));
    }

    @Test
    public void rar4HistoricalAuxiliaryCrcRemainsSkippedBeforeEncryptedMain() throws Exception {
        byte[] auxiliary = rar4Header(0x78, 0x8000, new byte[] {3, 0, 0, 0});
        auxiliary[0] ^= 1;
        byte[] main = rar4Header(RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[6]);
        File archive = writeRar4(auxiliary, new byte[] {9, 8, 7}, main);
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(archive));
        assertEquals(4, RarHeaderEncryptionDetector.headerEncryptedRarVersion(archive));
    }

    @Test
    public void rar4NormalMainProbeRetainsItsExistingSkipPolicy() throws Exception {
        byte[] normal = rar4Header(RAR4_HEADER_MAIN, 0, new byte[6]);
        normal[0] ^= 1;
        byte[] encrypted = rar4Header(RAR4_HEADER_MAIN, RAR4_MAIN_PASSWORD, new byte[6]);
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(writeRar4(normal, encrypted)));
    }

    @Test
    public void rar5VintReadersPreserveUnsigned64BitValues() throws Exception {
        for (long value : new long[] {0L, 1L, 127L, 128L, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            byte[] encoded = unsignedVint(value);
            assertEquals(value, readFileVint(encoded));
            assertEquals(value, readCursorVint(encoded));
        }
    }

    @Test
    public void rar5VintReadersPreserveZeroPaddedValues() throws Exception {
        for (int width = 2; width <= 10; width++) {
            for (int value : new int[] {0, 3, 127}) {
                byte[] encoded = paddedVint(value, width);
                assertEquals(value, readFileVint(encoded));
                assertEquals(value, readCursorVint(encoded));
            }
        }
    }

    @Test
    public void rar5VintReadersRejectTenthByteOverflowAndContinuation() throws Exception {
        for (int last : new int[] {2, 3, 0x7e, 0x7f, 0x80, 0x81, 0xfe, 0xff}) {
            byte[] encoded = invalidVint(0, last);
            assertInvalidVint(encoded);
            assertInvalidVint(concat(encoded, new byte[] {0}));
        }
    }

    @Test
    public void rar5VintReadersRejectTruncatedValues() throws Exception {
        assertInvalidVint(new byte[] {(byte) 0x80});
        byte[] missingLast = new byte[9];
        for (int i = 0; i < missingLast.length; i++) missingLast[i] = (byte) 0x80;
        assertInvalidVint(missingLast);
    }

    @Test
    public void rar5HeaderSizeOverflowDoesNotWrapToSmallHeader() throws Exception {
        byte[] data = encryptionHeaderData();
        for (int last : new int[] {2, 3, 0x7e, 0x7f, 0x80, 0xff}) {
            assertInvalidArchive(writeRar5(rar5Header(invalidVint(data.length, last), data)));
        }
    }

    @Test
    public void rar5CommonFieldOverflowCannotClassifyEncryption() throws Exception {
        for (int last : new int[] {2, 3, 0x7e, 0x7f, 0x80, 0xff}) {
            byte[][] data = new byte[][] {
                    concat(invalidVint(4, last), new byte[] {0}, encryptionBody()),
                    concat(new byte[] {4}, invalidVint(0, last), encryptionBody()),
                    concat(new byte[] {4, 1}, invalidVint(0, last), encryptionBody()),
                    concat(new byte[] {4, 2}, invalidVint(0, last), encryptionBody())
            };
            for (byte[] headerData : data) {
                assertInvalidArchive(writeRar5(rar5Header(headerData)));
            }
        }
    }

    @Test
    public void rar5WideTypeDoesNotAliasEncryption() throws Exception {
        byte[] unknown = rar5Header(concat(unsignedVint(0x100000004L), new byte[] {0}));
        File archive = writeRar5(unknown);
        assertNotEncrypted(archive);
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(
                writeRar5(unknown, rar5Header(encryptionHeaderData()))));
    }

    @Test
    public void rar5DamagedEncryptionHeaderCrcDoesNotRequestPassword() throws Exception {
        byte[] block = rar5Header(encryptionHeaderData());
        block[0] ^= 1;
        File archive = writeRar5(block);
        assertNotEncrypted(archive);
        RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                archive, null, new IOException("Damaged header"));
        RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                archive, new char[] {'x'}, new IOException("Damaged header"));
    }

    @Test
    public void rar5CorruptMainTypeDoesNotBecomeEncryption() throws Exception {
        byte[] main = rar5Header(new byte[] {1, 0, 0});
        main[5] = 4;
        File archive = writeRar5(main);
        assertNotEncrypted(archive);
        RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                archive, null, new IOException("Header CRC mismatch"));
    }

    @Test
    public void rar5ValidEncryptionStillRequestsPassword() throws Exception {
        File archive = writeRar5(rar5Header(encryptionHeaderData()));
        try {
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(archive, null, null);
            fail("Expected a password request");
        } catch (ArchiveSupport.PasswordRequiredException expected) {
            // A CRC-valid encryption marker retains the password prompt.
        }
    }

    @Test
    public void rar5DetectionDoesNotClaimSuppliedPasswordWasVerified() throws Exception {
        File archive = writeRar5(rar5Header(encryptionHeaderData()));
        try {
            RarHeaderEncryptionDetector.throwIfHeaderEncryptedNeedsUnsupportedPath(
                    archive, new char[] {'x'}, new IOException("Backend failure"));
            fail("Expected an unsupported-header failure");
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertFalse(expected.getMessage().contains("accepted the password"));
        }
    }

    @Test
    public void rar5HeaderCrcIncludesOriginalPaddedSizeBytes() throws Exception {
        byte[] data = encryptionHeaderData();
        byte[] canonical = rar5Header(data);
        for (int width : new int[] {2, 3, 10}) {
            byte[] padded = rar5Header(paddedVint(data.length, width), data);
            File archive = writeRar5(padded);
            assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(archive));
            assertEquals(5, RarHeaderEncryptionDetector.headerEncryptedRarVersion(archive));
            System.arraycopy(canonical, 0, padded, 0, 4);
            assertNotEncrypted(writeRar5(padded));
        }
    }

    @Test
    public void rar5PaddedTypeAndFlagsRemainAccepted() throws Exception {
        byte[] data = concat(paddedVint(4, 10), paddedVint(0, 10), encryptionBody());
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(writeRar5(rar5Header(data))));
    }

    @Test
    public void rar5EncryptionWithValidCommonExtraAndDataRemainsDetected() throws Exception {
        byte[] data = concat(new byte[] {4, 3, 2, 3}, encryptionBody(), new byte[] {1, 7});
        File archive = writeRar5(rar5Header(data), new byte[] {9, 8, 7});
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(archive));
        assertEquals(5, RarHeaderEncryptionDetector.headerEncryptedRarVersion(archive));
    }

    @Test
    public void rar5EncryptionWithTruncatedCommonFieldsIsRejected() throws Exception {
        for (byte[] data : new byte[][] {
                {4, 1}, {4, 2}, {4, 3, 0}, {4, 1, (byte) 0x80}, {4, 2, (byte) 0x80}
        }) {
            assertInvalidArchive(writeRar5(rar5Header(data)));
        }
    }

    @Test
    public void rar5EncryptionWithInvalidCommonRangesIsNotDetected() throws Exception {
        for (byte[] data : new byte[][] {
                {4, 1, 1}, {4, 1, 127}, {4, 2, 1},
                concat(new byte[] {4, 1}, unsignedVint(Long.MIN_VALUE)),
                concat(new byte[] {4, 2}, unsignedVint(Long.MIN_VALUE))
        }) {
            assertNotEncrypted(writeRar5(rar5Header(data)));
        }
    }

    @Test
    public void rar5UnknownHeaderSkipsItsDataBeforeClassifyingNextHeader() throws Exception {
        byte[] encryption = rar5Header(encryptionHeaderData());
        byte[] unknownData = concat(unsignedVint(0x100000004L), new byte[] {7, 2},
                unsignedVint(encryption.length), new byte[] {1, 7});
        byte[] unknown = rar5Header(unknownData);
        assertNotEncrypted(writeRar5(unknown, encryption, rar5Header(new byte[] {5, 0, 0})));
        assertTrue(RarHeaderEncryptionDetector.hasEncryptedHeaders(
                writeRar5(unknown, encryption, encryption)));
    }

    @Test
    public void rar5FileAndEndHeadersStopEncryptionProbe() throws Exception {
        for (int type : new int[] {2, 5}) {
            assertNotEncrypted(writeRar5(rar5Header(new byte[] {(byte) type, 0, 0}),
                    rar5Header(encryptionHeaderData())));
        }
    }

    private File writeRar5(byte[]... blocks) throws Exception {
        File archive = files.newFile();
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(RAR5_SIGNATURE);
            for (byte[] block : blocks) out.write(block);
        }
        return archive;
    }

    private File writeRar4(byte[]... blocks) throws Exception {
        File archive = files.newFile();
        try (FileOutputStream out = new FileOutputStream(archive)) {
            out.write(RAR4_SIGNATURE);
            for (byte[] block : blocks) out.write(block);
        }
        return archive;
    }

    private static void assertNotEncrypted(File archive) throws Exception {
        assertFalse(RarHeaderEncryptionDetector.hasEncryptedHeaders(archive));
        assertEquals(0, RarHeaderEncryptionDetector.headerEncryptedRarVersion(archive));
    }

    private static void assertInvalidArchive(File archive) throws Exception {
        try {
            RarHeaderEncryptionDetector.hasEncryptedHeaders(archive);
            fail("Expected malformed RAR5 header rejection");
        } catch (IOException expected) {
            assertFalse(expected instanceof ArchiveSupport.PasswordRequiredException);
        }
    }

    private void assertInvalidVint(byte[] encoded) throws Exception {
        try {
            readFileVint(encoded);
            fail("Expected file vint rejection");
        } catch (IOException expected) {
            assertFalse(expected instanceof ArchiveSupport.PasswordRequiredException);
        }
        try {
            readCursorVint(encoded);
            fail("Expected cursor vint rejection");
        } catch (IOException expected) {
            assertFalse(expected instanceof ArchiveSupport.PasswordRequiredException);
        }
    }

    private long readFileVint(byte[] encoded) throws Exception {
        File file = files.newFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(encoded);
        }
        Method read = RarHeaderEncryptionDetector.class.getDeclaredMethod("readVInt", RandomAccessFile.class);
        read.setAccessible(true);
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            Object vint = invoke(read, null, in);
            Field value = vint.getClass().getDeclaredField("value");
            value.setAccessible(true);
            return value.getLong(vint);
        }
    }

    private static long readCursorVint(byte[] encoded) throws Exception {
        Class<?> type = Class.forName(RarHeaderEncryptionDetector.class.getName() + "$ByteCursor");
        Constructor<?> constructor = type.getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);
        Method read = type.getDeclaredMethod("readVInt");
        read.setAccessible(true);
        return (Long) invoke(read, constructor.newInstance((Object) encoded));
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private static byte[] encryptionHeaderData() {
        return concat(new byte[] {4, 0}, encryptionBody());
    }

    private static byte[] encryptionBody() {
        // Version 0, no password-check field, KDF exponent 0, and a 16-byte salt.
        return new byte[19];
    }

    private static byte[] rar5Header(byte[] data) {
        return rar5Header(unsignedVint(data.length), data);
    }

    private static byte[] rar5Header(byte[] size, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(size);
        crc.update(data);
        long value = crc.getValue();
        byte[] checksum = new byte[] {(byte) value, (byte) (value >>> 8),
                (byte) (value >>> 16), (byte) (value >>> 24)};
        return concat(checksum, size, data);
    }

    private static byte[] unsignedVint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int next = (int) (value & 0x7fL);
            value >>>= 7;
            out.write(value == 0L ? next : next | 0x80);
        } while (value != 0L);
        return out.toByteArray();
    }

    private static byte[] paddedVint(int value, int width) {
        byte[] result = new byte[width];
        result[0] = (byte) (value | 0x80);
        for (int i = 1; i < width - 1; i++) result[i] = (byte) 0x80;
        return result;
    }

    private static byte[] invalidVint(int value, int last) {
        byte[] result = paddedVint(value, 10);
        result[9] = (byte) last;
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static void writeRar4Header(FileOutputStream out,
                                        int type,
                                        int flags,
                                        byte[] data) throws Exception {
        out.write(rar4Header(type, flags, data));
    }

    private static byte[] rar4Header(int type, int flags, byte[] body) {
        byte[] header = new byte[7 + body.length];
        header[2] = (byte) type;
        header[3] = (byte) flags;
        header[4] = (byte) (flags >>> 8);
        header[5] = (byte) header.length;
        header[6] = (byte) (header.length >>> 8);
        System.arraycopy(body, 0, header, 7, body.length);
        int coveredBody = type == RAR4_HEADER_MAIN
                ? Math.min(body.length, (flags & 0x0200) == 0 ? 6 : 7) : body.length;
        CRC32 crc = new CRC32();
        crc.update(header, 2, 5 + coveredBody);
        header[0] = (byte) crc.getValue();
        header[1] = (byte) (crc.getValue() >>> 8);
        return header;
    }

}
