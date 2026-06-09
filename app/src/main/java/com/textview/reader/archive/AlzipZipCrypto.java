package com.textview.reader.archive;

import androidx.annotation.NonNull;

final class AlzipZipCrypto {
    private static final int[] CRC_TABLE = buildCrcTable();
    private final int[] keys = new int[3];

    AlzipZipCrypto(@NonNull char[] password) {
        keys[0] = 305419896;
        keys[1] = 591751049;
        keys[2] = 878082192;
        for (char ch : password) {
            updateKeys((byte) ch);
        }
    }

    boolean checkHeader(@NonNull byte[] encryptedHeader, int expectedCheckByte) {
        if (encryptedHeader.length != 12) return false;
        byte last = 0;
        for (byte b : encryptedHeader) {
            last = decryptByte(b);
        }
        return (last & 0xff) == (expectedCheckByte & 0xff);
    }

    void decryptInPlace(@NonNull byte[] data, int offset, int length) {
        int end = Math.min(data.length, offset + Math.max(0, length));
        for (int i = Math.max(0, offset); i < end; i++) {
            data[i] = decryptByte(data[i]);
        }
    }

    private byte decryptByte(byte encrypted) {
        byte plain = (byte) (encrypted ^ decryptKeyByte());
        updateKeys(plain);
        return plain;
    }

    private int decryptKeyByte() {
        int temp = (keys[2] & 0xffff) | 2;
        return ((temp * (temp ^ 1)) >>> 8) & 0xff;
    }

    private void updateKeys(byte plain) {
        keys[0] = crc32(keys[0], plain);
        keys[1] = keys[1] + (keys[0] & 0xff);
        keys[1] = keys[1] * 134775813 + 1;
        keys[2] = crc32(keys[2], (byte) (keys[1] >>> 24));
    }

    private static int crc32(int value, byte b) {
        return CRC_TABLE[(value ^ b) & 0xff] ^ (value >>> 8);
    }

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            int value = i;
            for (int bit = 0; bit < 8; bit++) {
                if ((value & 1) != 0) {
                    value = (value >>> 1) ^ 0xedb88320;
                } else {
                    value >>>= 1;
                }
            }
            table[i] = value;
        }
        return table;
    }
}
