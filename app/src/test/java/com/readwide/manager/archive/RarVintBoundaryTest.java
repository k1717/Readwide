package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class RarVintBoundaryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void numericReadersPreserveAllUnsigned64Bits() throws Exception {
        for (long value : new long[]{0, 1, 127, 128, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            assertNumericValue(unsignedVint(value), value);
        }
    }

    @Test public void paddedEncodingsRemainValidAndKeepTheirOriginalBytes() throws Exception {
        for (int width = 2; width <= 10; width++) {
            assertNumericValue(paddedVint(0, width), 0);
            assertNumericValue(paddedVint(3, width), 3);
        }
    }

    @Test public void tenthByteCannotWrapExtraDataBitsIntoSmallValues() throws Exception {
        for (int last : new int[]{2, 3, 0x7e, 0x7f}) {
            byte[] encoded = paddedVint(3, 10);
            encoded[9] = (byte) last;
            assertNumericFailure(encoded, "RAR vint overflow", 10);
        }
    }

    @Test public void tenthByteContinuationFailsBeforeReadingAnotherField() throws Exception {
        for (int last : new int[]{0x80, 0x81, 0xfe, 0xff}) {
            byte[] encoded = Arrays.copyOf(paddedVint(3, 10), 11);
            encoded[9] = (byte) last;
            encoded[10] = 0;
            assertNumericFailure(encoded, "RAR vint is too long", 10);
        }
    }

    @Test public void truncatedNumericReadersStillReportEndOfInput() throws Exception {
        for (int length : new int[]{1, 2, 9}) {
            byte[] encoded = new byte[length];
            Arrays.fill(encoded, (byte) 0x80);
            try (RandomAccessFile input = open(encoded)) {
                assertThrows(EOFException.class, () -> readPlainVint(input));
            }
            Object cursor = cursor(encoded);
            assertThrows(EOFException.class, () -> readCursorVint(cursor));
        }
    }

    @Test public void plainHeaderSizeAndHeaderFieldsRejectOverflowWithMatchingCrc() throws Exception {
        byte[] overflow = paddedVint(3, 10);
        overflow[9] = 2;
        byte[] fields = new byte[]{1, 0, 0};
        try (RandomAccessFile input = open(header(overflow, fields))) {
            IOException failure = assertThrows(IOException.class, () -> readHeader(input, null));
            assertEquals("RAR vint overflow", failure.getMessage());
        }
        overflow[0] = (byte) 0x80;
        byte[] invalidFields = concat(concat(new byte[]{1}, overflow), new byte[]{0});
        try (RandomAccessFile input = open(header(unsignedVint(invalidFields.length), invalidFields))) {
            IOException failure = assertThrows(IOException.class, () -> readHeader(input, null));
            assertEquals("RAR vint overflow", failure.getMessage());
        }
    }

    @Test public void encryptedHeaderSizeRejectsOverflowAndContinuationBeforeReadingMoreBlocks() throws Exception {
        Rar5Crypto.Secrets secrets = new Rar5Crypto.Secrets(new byte[32], null, new byte[8]);
        for (int last : new int[]{2, 3, 0x7f, 0x80, 0x81, 0xff}) {
            byte[] invalid = paddedVint(3, 10);
            invalid[9] = (byte) last;
            try (RandomAccessFile input = open(encrypt(header(invalid, new byte[]{1, 0, 0}), secrets))) {
                assertThrows(ArchiveSupport.PasswordRequiredException.class,
                        () -> readHeader(input, secrets));
                assertEquals(32L, input.getFilePointer());
            }
        }
    }

    @Test public void plainAndEncryptedHeadersKeepPaddedSizeCrcAndAlignment() throws Exception {
        Rar5Crypto.Secrets secrets = new Rar5Crypto.Secrets(new byte[32], null, new byte[8]);
        byte[] fields = new byte[]{1, 0, 0};
        for (int width : new int[]{1, 3, 10}) {
            byte[] raw = header(paddedVint(fields.length, width), fields);
            for (boolean encrypted : new boolean[]{false, true}) {
                byte[] stored = encrypted ? encrypt(raw, secrets) : raw;
                try (RandomAccessFile input = open(stored)) {
                    Object block = readHeader(input, encrypted ? secrets : null);
                    assertEquals(1, ((Number) field(block, "type")).intValue());
                    assertArrayEquals(fields, (byte[]) field(block, "headerData"));
                    assertEquals(stored.length, ((Number) field(block, "dataOffset")).longValue());
                    assertEquals(stored.length, input.getFilePointer());
                }
            }
        }
    }

    private void assertNumericValue(byte[] encoded, long expected) throws Exception {
        try (RandomAccessFile input = open(concat(encoded, new byte[]{0x55}))) {
            Object decoded = readPlainVint(input);
            assertEquals(expected, ((Number) field(decoded, "value")).longValue());
            assertArrayEquals(encoded, (byte[]) field(decoded, "encoded"));
            assertEquals(encoded.length, input.getFilePointer());
            assertEquals(0x55, input.read());
        }
        Object cursor = cursor(concat(encoded, new byte[]{0x55}));
        assertEquals(expected, readCursorVint(cursor));
        assertEquals(encoded.length, ((Number) field(cursor, "position")).intValue());
    }

    private void assertNumericFailure(byte[] encoded, String message, int consumed) throws Exception {
        try (RandomAccessFile input = open(encoded)) {
            IOException failure = assertThrows(IOException.class, () -> readPlainVint(input));
            assertEquals(message, failure.getMessage());
            assertEquals(consumed, input.getFilePointer());
        }
        Object cursor = cursor(encoded);
        IOException failure = assertThrows(IOException.class, () -> readCursorVint(cursor));
        assertEquals(message, failure.getMessage());
        assertEquals(consumed, ((Number) field(cursor, "position")).intValue());
    }

    private RandomAccessFile open(byte[] bytes) throws Exception {
        File file = temporary.newFile();
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        return new RandomAccessFile(file, "r");
    }

    private static Object readPlainVint(RandomAccessFile input) throws Exception {
        Method method = RarArchiveReader.class.getDeclaredMethod("readVInt", RandomAccessFile.class);
        method.setAccessible(true);
        return invoke(method, null, input);
    }

    private static Object cursor(byte[] encoded) throws Exception {
        Class<?> type = Class.forName(RarArchiveReader.class.getName() + "$ByteCursor");
        Constructor<?> constructor = type.getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance((Object) encoded);
    }

    private static long readCursorVint(Object cursor) throws Exception {
        Method method = cursor.getClass().getDeclaredMethod("readVInt");
        method.setAccessible(true);
        return ((Number) invoke(method, cursor)).longValue();
    }

    private static Object readHeader(RandomAccessFile input, Rar5Crypto.Secrets secrets) throws Exception {
        Method method = secrets == null
                ? RarArchiveReader.class.getDeclaredMethod("readRar5Header", RandomAccessFile.class)
                : RarArchiveReader.class.getDeclaredMethod("readRar5EncryptedHeader", RandomAccessFile.class,
                        Rar5Crypto.Secrets.class);
        method.setAccessible(true);
        return secrets == null ? invoke(method, null, input) : invoke(method, null, input, secrets);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static byte[] unsignedVint(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            output.write(next | (value == 0 ? 0 : 0x80));
        } while (value != 0);
        return output.toByteArray();
    }

    private static byte[] paddedVint(int value, int width) {
        byte[] output = new byte[width];
        Arrays.fill(output, (byte) 0x80);
        output[0] |= value;
        output[width - 1] &= 0x7f;
        return output;
    }

    private static byte[] header(byte[] encodedSize, byte[] fields) {
        byte[] covered = concat(encodedSize, fields);
        CRC32 crc = new CRC32();
        crc.update(covered);
        byte[] output = new byte[4 + covered.length];
        for (int i = 0; i < 4; i++) output[i] = (byte) (crc.getValue() >>> (8 * i));
        System.arraycopy(covered, 0, output, 4, covered.length);
        return output;
    }

    private static byte[] encrypt(byte[] plain, Rar5Crypto.Secrets secrets) throws Exception {
        byte[] iv = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secrets.key, "AES"), new IvParameterSpec(iv));
        return concat(iv, cipher.doFinal(Arrays.copyOf(plain, (plain.length + 15) & ~15)));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
