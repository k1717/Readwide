package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public class Rar4EncryptedSizePreflightTest {
    @Test public void storedPlaintextCannotExceedCiphertextInEitherRewriter() throws Exception {
        for (boolean split : new boolean[]{false, true}) {
            IOException failure = assertThrows(IOException.class, () -> prepare(split, 0x30, 17));
            assertEquals("Invalid RAR4 decrypted payload size", failure.getMessage());
        }
    }

    @Test public void signedOverflowCannotBecomeUnlimitedStoredPlaintext() throws Exception {
        for (boolean split : new boolean[]{false, true}) {
            IOException failure = assertThrows(IOException.class, () -> prepare(split, 0x30, Long.MIN_VALUE));
            assertEquals("Invalid RAR4 decrypted payload size", failure.getMessage());
        }
    }

    @Test public void validStoredLengthsIncludingPaddingRemainEligible() throws Exception {
        for (boolean split : new boolean[]{false, true}) {
            for (long size : new long[]{0, 1, 15, 16}) assertNotNull(prepare(split, 0x30, size));
        }
    }

    @Test public void compressedDecodedLengthMayExceedPackedLength() throws Exception {
        for (boolean split : new boolean[]{false, true}) assertNotNull(prepare(split, 0x33, 4096));
    }

    private static Object prepare(boolean split, int method, long unpacked) throws Exception {
        // LARGE FILE body, one name byte, eight salt bytes. Packed data is 16 bytes.
        byte[] header = new byte[42];
        put32(header, 0, 16);
        put32(header, 4, unpacked);
        put32(header, 29, unpacked >>> 32);
        header[17] = 29;
        header[18] = (byte) method;
        header[19] = 1;
        header[33] = 'a';
        Class<?> owner = split ? Rar4EncryptedSplitPayloadRewriter.class : Rar4EncryptedPayloadRewriter.class;
        Method prepare = owner.getDeclaredMethod(split ? "prepareSplitFileBlock" : "prepareEncryptedFileBlock",
                int.class, byte[].class, long.class);
        prepare.setAccessible(true);
        try { return prepare.invoke(null, split ? 0x0506 : 0x0504, header, 16L); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }

    private static void put32(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
}
