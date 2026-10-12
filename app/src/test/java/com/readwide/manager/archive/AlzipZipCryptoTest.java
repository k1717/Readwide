package com.readwide.manager.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class AlzipZipCryptoTest {
    @Test public void ambiguousEncodingsUsePayloadCrcAndReturnFreshKeyState() throws Exception {
        byte[] plain = "payload CRC resolves a header collision".getBytes(StandardCharsets.UTF_8);
        for (String encoding : new String[]{"UTF-8", "MS949"}) {
            byte[][] fixture = collidingPasswordFixture(plain, encoding, 0x55);
            char[] password = "비밀번호".toCharArray();
            int[] attempts = {0};
            AlzipZipCrypto crypto = AlzipZipCrypto.checked(password, fixture[0], 0x55, true, candidate -> {
                attempts[0]++;
                byte[] decoded = fixture[1].clone();
                candidate.decryptInPlace(decoded, 0, decoded.length);
                return crc(decoded) == crc(plain);
            });
            assertTrue(attempts[0] >= 2);
            byte[] decoded = fixture[1].clone();
            crypto.decryptInPlace(decoded, 0, decoded.length);
            assertArrayEquals(plain, decoded);
            assertArrayEquals("비밀번호".toCharArray(), password);
        }
    }

    @Test public void singleEncodingNeedsNoExtraPayloadPass() throws Exception {
        Encryptor encoder = new Encryptor("secret".toCharArray());
        byte[] header = encoder.encrypt(new byte[]{1,2,3,4,5,6,7,8,9,10,11,0x55});
        byte[] payload = encoder.encrypt(new byte[]{42});
        AlzipZipCrypto.checked("secret".toCharArray(), header, 0x55, true, candidate -> {
            throw new AssertionError("Unambiguous passwords must not be probed");
        }).decryptInPlace(payload, 0, payload.length);
        assertArrayEquals(new byte[]{42}, payload);
    }

    @Test public void absentVerifierOrCrcKeepsLegacyHeaderPriority() throws Exception {
        byte[] plain = {1,2,3,4};
        byte[][] fixture = collidingPasswordFixture(plain, "UTF-8", 0x55);
        char[] password = "비밀번호".toCharArray();
        for (boolean crcPresent : new boolean[]{true, false}) {
            byte[] decoded = fixture[1].clone();
            AlzipZipCrypto crypto = crcPresent
                    ? AlzipZipCrypto.checked(password, fixture[0], 0x55, true)
                    : AlzipZipCrypto.checked(password, fixture[0], 0x55, false, candidate -> {
                        throw new AssertionError("Cannot probe without a payload CRC");
                    });
            crypto.decryptInPlace(decoded, 0, decoded.length);
            assertTrue(crc(decoded) != crc(plain));
        }
    }

    @Test public void probeFailuresAbortWithoutTryingAnotherEncoding() throws Exception {
        byte[][] fixture = collidingPasswordFixture(new byte[]{1,2,3,4}, "UTF-8", 0x55);
        for (Throwable failure : new Throwable[]{new java.io.IOException("source"),
                new java.io.InterruptedIOException("cancelled"),
                new IllegalStateException("unchecked"), new AssertionError("fatal")}) {
            int[] attempts = {0};
            Throwable actual = assertThrows(failure.getClass(), () -> AlzipZipCrypto.checked(
                    "비밀번호".toCharArray(), fixture[0], 0x55, true, candidate -> {
                        attempts[0]++;
                        if (failure instanceof java.io.IOException) throw (java.io.IOException) failure;
                        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                        throw (Error) failure;
                    }));
            assertSame(failure, actual);
            assertEquals(1, attempts[0]);
        }
    }

    @Test public void failedProbeCloseIsNotAnEncodingMismatch() throws Exception {
        byte[][] fixture = collidingPasswordFixture(new byte[]{1,2,3,4}, "UTF-8", 0x55);
        java.io.IOException failure = new java.io.IOException("source close");
        int[] attempts = {0};
        assertSame(failure, assertThrows(java.io.IOException.class, () -> AlzipZipCrypto.checked(
                "비밀번호".toCharArray(), fixture[0], 0x55, true, candidate -> {
                    attempts[0]++;
                    try (java.io.InputStream input = new java.io.ByteArrayInputStream(new byte[0]) {
                        @Override public void close() throws java.io.IOException { throw failure; }
                    }) {
                        return false;
                    }
                })));
        assertEquals(1, attempts[0]);
    }

    @Test public void allCrcMismatchesRejectThePassword() throws Exception {
        byte[][] fixture = collidingPasswordFixture(new byte[]{1,2,3,4}, "MS949", 0x55);
        int[] attempts = {0};
        assertThrows(java.io.IOException.class, () -> AlzipZipCrypto.checked(
                "비밀번호".toCharArray(), fixture[0], 0x55, true, candidate -> {
                    attempts[0]++;
                    return false;
                }));
        assertTrue(attempts[0] >= 2);
    }

    // Deterministically create a producer-style header that also passes the
    // incorrect legacy byte interpretation. Encryption is independent test code.
    static byte[][] collidingPasswordFixture(byte[] payload, String encoding, int checkByte) throws Exception {
        for (int nonce = 0; nonce < 65536; nonce++) {
            Encryptor encoder = new Encryptor("비밀번호".getBytes(encoding));
            byte[] header = encoder.encrypt(new byte[]{(byte)nonce, (byte)(nonce >>> 8),
                    3,4,5,6,7,8,9,10,11,(byte)checkByte});
            AlzipZipCrypto legacy = new AlzipZipCrypto("비밀번호".toCharArray());
            try {
                if (!legacy.checkHeader(header, checkByte)) continue;
                byte[] packed = encoder.encrypt(payload), wrong = packed.clone();
                legacy.decryptInPlace(wrong, 0, wrong.length);
                if (crc(wrong) != crc(payload)) return new byte[][]{header, packed};
            } finally { legacy.destroy(); }
        }
        throw new AssertionError("No ambiguous password fixture found");
    }

    private static long crc(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    @Test public void utf8AndKoreanPasswordBytesRetainPayloadKeyState() throws Exception {
        for (String encoding : new String[]{"UTF-8", "MS949"}) {
            char[] password = "비밀번호".toCharArray();
            byte[] payload = "checked payload".getBytes(StandardCharsets.UTF_8);
            byte[] encryptedHeader = null, encryptedPayload = null;
            // Select a deterministic non-collision fixture; the weak one-byte header
            // is not authentication, and payload CRC enforcement belongs to callers.
            for (int nonce = 0; nonce < 256; nonce++) {
                byte[] plain = {1,2,3,4,5,6,7,8,9,10,(byte)nonce,0x55};
                Encryptor encoder = new Encryptor("비밀번호".getBytes(encoding));
                byte[] header = encoder.encrypt(plain);
                if (new AlzipZipCrypto(password).checkHeader(header, 0x55)) continue;
                if (encoding.equals("MS949") && new AlzipZipCrypto("비밀번호".getBytes(StandardCharsets.UTF_8)).checkHeader(header, 0x55)) continue;
                encryptedHeader = header; encryptedPayload = encoder.encrypt(payload); break;
            }
            assertTrue(encryptedHeader != null);
            byte[] header = encryptedHeader;
            assertThrows(java.io.IOException.class, () -> AlzipZipCrypto.checked(password, header, 0x55, false));
            AlzipZipCrypto crypto = AlzipZipCrypto.checked(password, header, 0x55, true);
            crypto.decryptInPlace(encryptedPayload, 0, encryptedPayload.length);
            assertArrayEquals(payload, encryptedPayload);
            assertArrayEquals("비밀번호".toCharArray(), password);
        }
    }

    @Test public void legacyNonAsciiEncodingStillWorksWithoutCrcFallback() throws Exception {
        char[] password = "비밀".toCharArray();
        Encryptor encoder = new Encryptor(password);
        byte[] header = encoder.encrypt(new byte[]{1,2,3,4,5,6,7,8,9,10,11,0x55});
        byte[] payload = encoder.encrypt(new byte[]{12,13,14});
        AlzipZipCrypto.checked(password, header, 0x55, false).decryptInPlace(payload, 0, payload.length);
        assertArrayEquals(new byte[]{12,13,14}, payload);
    }

    @Test public void malformedHeaderIsRejectedWithAndWithoutFallback() {
        for (boolean crc : new boolean[]{false,true}) {
            assertThrows(java.io.IOException.class,
                    () -> AlzipZipCrypto.checked("비밀".toCharArray(), new byte[11], 0, crc));
        }
    }

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

    static final class Encryptor {
        private static final int[] CRC_TABLE = buildCrcTable();
        private final int[] keys = new int[3];

        Encryptor(char[] password) {
            keys[0] = 305419896;
            keys[1] = 591751049;
            keys[2] = 878082192;
            for (char ch : password) updateKeys((byte) ch);
        }

        Encryptor(byte[] password) {
            this(new char[0]);
            for (byte value : password) updateKeys(value);
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
