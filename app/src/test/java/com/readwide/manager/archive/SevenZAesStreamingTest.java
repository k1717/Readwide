package com.readwide.manager.archive;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

/** Synthetic AES/CBC fixtures exercise stream finalization, not archive authentication. */
public class SevenZAesStreamingTest {
    private static final byte[] PROPERTIES = {0x3f, 0};
    private static final char[] PASSWORD = "stream-test".toCharArray();

    @Test public void streamsDeclaredBytesAndConsumesOnlyFinalBlockPadding() throws Exception {
        byte[] plain = new byte[19];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte) (i + 1);
        assertArrayEquals(plain, decrypt(encrypt(plain), plain.length));
        assertArrayEquals(new byte[0], decrypt(encrypt(new byte[0]), 0));
    }

    @Test public void rejectsTruncatedCipherBlockAndUnpackedLengthMismatch() throws Exception {
        byte[] cipher = encrypt(new byte[19]);
        expectFailure(Arrays.copyOf(cipher, cipher.length - 1), 19);
        expectFailure(cipher, 40);
        expectFailure(cipher, 1);
    }

    private static void expectFailure(byte[] encrypted, long size) throws Exception {
        try { decrypt(encrypted, size); fail("Expected malformed AES stream"); }
        catch (IOException expected) { }
    }

    private static byte[] decrypt(byte[] encrypted, long size) throws Exception {
        try (InputStream decoded = SevenZAesDecoder.decodeStream(new ByteArrayInputStream(encrypted),
                PROPERTIES, PASSWORD, size)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[3];
            int count;
            while ((count = decoded.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static byte[] encrypt(byte[] plain) throws Exception {
        byte[] key = Arrays.copyOf(new String(PASSWORD).getBytes(StandardCharsets.UTF_16LE), 32);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return cipher.doFinal(Arrays.copyOf(plain, ((plain.length + 15) / 16) * 16));
    }
}
