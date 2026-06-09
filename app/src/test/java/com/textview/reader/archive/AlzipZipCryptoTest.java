package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class AlzipZipCryptoTest {
    @Test
    public void checkHeader_acceptsMatchingPasswordAndRejectsWrongPassword() {
        byte[] plainHeader = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, (byte) 0xab};
        byte[] encryptedHeader = encrypt("secret".toCharArray(), plainHeader);

        assertTrue(new AlzipZipCrypto("secret".toCharArray()).checkHeader(encryptedHeader, 0xab));
        assertFalse(new AlzipZipCrypto("wrong".toCharArray()).checkHeader(encryptedHeader, 0xab));
    }

    @Test
    public void decryptInPlace_restoresEncryptedPayloadAfterHeaderCheck() {
        char[] password = "secret".toCharArray();
        byte[] plainHeader = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0x55};
        byte[] plainPayload = "alz encrypted payload".getBytes(StandardCharsets.UTF_8);
        Encryptor encryptor = new Encryptor(password);
        byte[] encryptedHeader = encryptor.encrypt(plainHeader);
        byte[] encryptedPayload = encryptor.encrypt(plainPayload);

        AlzipZipCrypto crypto = new AlzipZipCrypto(password);
        assertTrue(crypto.checkHeader(encryptedHeader, 0x55));
        crypto.decryptInPlace(encryptedPayload, 0, encryptedPayload.length);

        assertArrayEquals(plainPayload, encryptedPayload);
    }

    private byte[] encrypt(char[] password, byte[] plain) {
        return new Encryptor(password).encrypt(plain);
    }

    private static final class Encryptor {
        private static final int[] CRC_TABLE = buildCrcTable();
        private final int[] keys = new int[3];

        Encryptor(char[] password) {
            keys[0] = 305419896;
            keys[1] = 591751049;
            keys[2] = 878082192;
            for (char ch : password) updateKeys((byte) ch);
        }

        byte[] encrypt(byte[] plain) {
            byte[] out = Arrays.copyOf(plain, plain.length);
            for (int i = 0; i < out.length; i++) {
                byte value = out[i];
                out[i] = (byte) (value ^ decryptKeyByte());
                updateKeys(value);
            }
            return out;
        }

        private int decryptKeyByte() {
            int temp = (keys[2] & 0xffff) | 2;
            return ((temp * (temp ^ 1)) >>> 8) & 0xff;
        }

        private void updateKeys(byte plain) {
            keys[0] = CRC_TABLE[(keys[0] ^ plain) & 0xff] ^ (keys[0] >>> 8);
            keys[1] = keys[1] + (keys[0] & 0xff);
            keys[1] = keys[1] * 134775813 + 1;
            keys[2] = CRC_TABLE[(keys[2] ^ (byte) (keys[1] >>> 24)) & 0xff] ^ (keys[2] >>> 8);
        }

        private static int[] buildCrcTable() {
            int[] table = new int[256];
            for (int i = 0; i < table.length; i++) {
                int value = i;
                for (int bit = 0; bit < 8; bit++) {
                    value = (value & 1) != 0 ? (value >>> 1) ^ 0xedb88320 : value >>> 1;
                }
                table[i] = value;
            }
            return table;
        }
    }
}
