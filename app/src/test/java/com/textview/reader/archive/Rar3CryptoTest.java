package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

public class Rar3CryptoTest {
    @Test
    public void deriveParameters_matchesKnownRar3AesVector() throws Exception {
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(
                "hello".toCharArray(),
                hex("379475b06e303955"));

        assertArrayEquals(hex("a002f7af8fc3b153436abb226f298747"), parameters.key);
        assertArrayEquals(hex("e3dfe7498ad0faf3325f9ee9283a396c"), parameters.iv);
    }


    @Test
    public void createAesCbcDecryptCipher_supportsChunkedStoredPayloadDecrypt() throws Exception {
        char[] password = "chunked".toCharArray();
        byte[] salt = hex("379475b06e303955");
        byte[] payload = "streaming encrypted stored payload check".getBytes(StandardCharsets.UTF_8);
        byte[] padded = java.util.Arrays.copyOf(payload, ((payload.length + 15) / 16) * 16);
        Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);
        Cipher encrypt = Cipher.getInstance("AES/CBC/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(parameters.key, "AES"),
                new IvParameterSpec(parameters.iv));
        byte[] encrypted = encrypt.doFinal(padded);

        Cipher decrypt = Rar3Crypto.createAesCbcDecryptCipher(password, salt);
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
