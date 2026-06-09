package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

public class Rar5CryptoTest {
    @Test
    public void passwordCheck_matchesDerivedRar5CheckValue() throws Exception {
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(
                "pw".toCharArray(),
                1,
                hex("00112233445566778899aabbccddeeff"));

        assertTrue(Rar5Crypto.passwordMatches(secrets, secrets.pswCheck.clone()));
        byte[] wrong = secrets.pswCheck.clone();
        wrong[0] ^= 0x55;
        assertFalse(Rar5Crypto.passwordMatches(secrets, wrong));
    }

    @Test
    public void createAesCbcDecryptCipher_supportsChunkedStoredPayloadDecrypt() throws Exception {
        byte[] iv = hex("ffeeddccbbaa99887766554433221100");
        byte[] payload = "rar5 streaming crypto split helper check".getBytes(StandardCharsets.UTF_8);
        byte[] padded = java.util.Arrays.copyOf(payload, ((payload.length + 15) / 16) * 16);
        Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(
                "chunked".toCharArray(),
                1,
                hex("102030405060708090a0b0c0d0e0f000"));

        Cipher encrypt = Cipher.getInstance("AES/CBC/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(secrets.key, "AES"),
                new IvParameterSpec(iv));
        byte[] encrypted = encrypt.doFinal(padded);

        Cipher decrypt = Rar5Crypto.createAesCbcDecryptCipher(secrets, iv);
        byte[] first = decrypt.update(encrypted, 0, 16);
        byte[] second = decrypt.update(encrypted, 16, encrypted.length - 16);
        byte[] tail = decrypt.doFinal();
        byte[] combined = concat(first, second, tail);

        assertArrayEquals(payload, java.util.Arrays.copyOf(combined, payload.length));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            if (part != null) length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            if (part == null) continue;
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
