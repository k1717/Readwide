package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * First-party RAR5 AES helper.
 *
 * <p>Keep RAR5 key derivation and password checking out of {@link RarArchiveReader}. The reader
 * should decide which backend/extractor to use; this helper owns only crypto primitives and the
 * RAR5 password-check rule. It intentionally does not implement compressed or solid unpacking.</p>
 */
final class Rar5Crypto {
    private static final int MAX_KDF_COUNT = 24;

    private Rar5Crypto() {}

    @NonNull
    static Secrets deriveSecrets(@NonNull char[] password,
                                 int kdfCount,
                                 @NonNull byte[] salt) throws IOException {
        if (kdfCount < 0 || kdfCount > MAX_KDF_COUNT) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR KDF count is too high");
        }
        byte[] pwdBytes = utf8Bytes(password);
        long iterations = 1L << kdfCount;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pwdBytes, "HmacSHA256"));

            // U1 = PRF(pwd, salt || INT32BE(1)). RAR5 then continues a single U-chain:
            // key at 1<<kdfCount, hash key after +16, password check after another +16.
            mac.update(salt);
            mac.update(new byte[] {0, 0, 0, 1});
            byte[] u = mac.doFinal();

            byte[] accumulator = u.clone();
            byte[] key = null;
            byte[] hashKey = null;
            byte[] pswCheck = null;

            long keyAt = iterations;
            long hashAt = iterations + 16;
            long pswAt = iterations + 32;

            for (long i = 1; i <= pswAt; i++) {
                if ((i == 1 || (i & 1023) == 0) && Thread.currentThread().isInterrupted()) {
                    throw new IOException("RAR password derivation cancelled");
                }
                if (i != 1) {
                    u = mac.doFinal(u);
                    xorInto(accumulator, u);
                }
                if (i == keyAt) key = accumulator.clone();
                if (i == hashAt) hashKey = accumulator.clone();
                if (i == pswAt) pswCheck = accumulator.clone();
            }
            if (key == null || pswCheck == null) {
                throw new IOException("RAR key derivation produced no key");
            }
            return new Secrets(key, hashKey, foldPswCheck(pswCheck));
        } catch (GeneralSecurityException e) {
            throw new IOException("RAR key derivation failed", e);
        } finally {
            java.util.Arrays.fill(pwdBytes, (byte) 0);
        }
    }

    /**
     * Verifies the derived password-check value. When an archive has no check value, return true
     * and let extraction + CRC detect a wrong password.
     */
    static boolean passwordMatches(@NonNull Secrets secrets, @Nullable byte[] storedCheck) {
        if (storedCheck == null || storedCheck.length < 8) return true;
        for (int i = 0; i < 8; i++) {
            if (secrets.pswCheck[i] != storedCheck[i]) return false;
        }
        return true;
    }

    @NonNull
    static Cipher createAesCbcDecryptCipher(@NonNull Secrets secrets,
                                             @NonNull byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(secrets.key, "AES"),
                    new IvParameterSpec(iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("RAR AES decrypt failed", e);
        }
    }

    /**
     * RAR5 HashMAC CRC: HMAC-SHA256 over the little-endian plaintext CRC,
     * XOR-folded to four bytes. Format behavior cross-checked with BSD-licensed
     * nwaples/rardecode (archive50.go and reader.go); no UnRAR code is used.
     */
    static long tweakCrc32(long crc, @NonNull Secrets secrets) throws IOException {
        if (secrets.hashKey == null || secrets.hashKey.length != 32) {
            throw new IOException("RAR5 checksum key is missing");
        }
        byte[] encoded = new byte[4];
        for (int i = 0; i < 4; i++) encoded[i] = (byte) (crc >>> (8 * i));
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secrets.hashKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(encoded);
            long folded = 0;
            for (int i = 0; i < digest.length; i++) {
                folded ^= (digest[i] & 255L) << (8 * (i % 4));
            }
            java.util.Arrays.fill(digest, (byte) 0);
            return folded;
        } catch (GeneralSecurityException failure) {
            throw new IOException("RAR5 checksum calculation failed", failure);
        }
    }

    /** RAR5 HashMAC: HMAC-SHA256 of the 32-byte BLAKE2sp digest, without folding. */
    static byte[] tweakBlake2sp(@NonNull byte[] digest, @NonNull Secrets secrets) throws IOException {
        if (digest.length != 32 || secrets.hashKey == null || secrets.hashKey.length != 32) {
            throw new IOException("RAR5 BLAKE2sp checksum or key has invalid size");
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secrets.hashKey, "HmacSHA256"));
            return mac.doFinal(digest);
        } catch (GeneralSecurityException failure) {
            throw new IOException("RAR5 checksum calculation failed", failure);
        }
    }

    @NonNull
    private static byte[] foldPswCheck(@NonNull byte[] pswCheckDigest) {
        byte[] out = new byte[8];
        for (int i = 0; i < pswCheckDigest.length; i++) {
            out[i % 8] ^= pswCheckDigest[i];
        }
        return out;
    }

    private static void xorInto(@NonNull byte[] target, @NonNull byte[] src) {
        for (int i = 0; i < target.length; i++) target[i] ^= src[i];
    }

    @NonNull
    private static byte[] utf8Bytes(@NonNull char[] password) {
        java.nio.ByteBuffer buf = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    static final class Secrets {
        final byte[] key;
        @Nullable final byte[] hashKey;
        final byte[] pswCheck;

        Secrets(@NonNull byte[] key, @Nullable byte[] hashKey, @NonNull byte[] pswCheck) {
            this.key = key;
            this.hashKey = hashKey;
            this.pswCheck = pswCheck;
        }
    }
}
