package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decryptor for AES-128/256 encrypted EGG entries.
 *
 * <p>EGG's AES scheme is the WinZip AE-2 construction (the vendor's unegg
 * decoder links Gladman's {@code fileenc}, and archives built to this scheme
 * decrypt byte-identically through it; see docs/EGG_FORMAT_NOTES.md):
 * PBKDF2-HMAC-SHA1 with 1000 iterations derives, from the per-file salt
 * (8 bytes for AES-128, 16 for AES-256), the AES key, an HMAC-SHA1 key of the
 * same length, and a 2-byte password verifier. Block data is AES-CTR
 * ciphertext with a 16-byte little-endian counter that starts at 1, and the
 * Encrypt field's 10-byte footer is the truncated HMAC-SHA1 of the whole
 * file's ciphertext. One context spans the file: the keystream, and the MAC,
 * run continuously across the file's blocks in block order.</p>
 *
 * <p>Implemented from the published WinZip AES specification with JCE
 * primitives only; no third-party cryptography code is used.</p>
 */
final class EggWinZipAesCrypto {
    private static final int PBKDF2_ITERATIONS = 1000;
    private static final int VERIFIER_LENGTH = 2;
    private static final int FOOTER_LENGTH = 10;

    @NonNull private final Cipher aes;
    @NonNull private final Mac hmac;
    private final byte[] counter = new byte[16];
    private final byte[] keystream = new byte[16];
    private int keystreamUsed = 16;
    private final boolean passwordVerified;

    /**
     * Derives keys for the given password bytes. The password verifier is
     * checked here; {@link #isPasswordVerified()} reports the result so the
     * caller can retry with a different byte encoding of the same password.
     */
    EggWinZipAesCrypto(@NonNull byte[] passwordBytes,
                       @NonNull byte[] salt,
                       @NonNull byte[] storedVerifier,
                       int keyBits) throws IOException {
        int keyLen = keyBits / 8;
        byte[] derived;
        try {
            // Manual PBKDF2 keeps byte-exact control over the password: JCE's
            // PBEKeySpec takes chars and re-encodes them, which breaks
            // non-UTF-8 password byte sequences.
            derived = pbkdf2HmacSha1(passwordBytes, salt, PBKDF2_ITERATIONS, keyLen * 2 + VERIFIER_LENGTH);
            aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(derived, 0, keyLen), "AES"));
            hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(Arrays.copyOfRange(derived, keyLen, keyLen * 2), "HmacSHA1"));
        } catch (GeneralSecurityException e) {
            throw new IOException("EGG AES initialisation failed: " + e.getMessage());
        }
        byte[] verifier = Arrays.copyOfRange(derived, keyLen * 2, keyLen * 2 + VERIFIER_LENGTH);
        passwordVerified = MessageDigest.isEqual(verifier, storedVerifier);
        Arrays.fill(derived, (byte) 0);
        counter[0] = 1; // little-endian counter, first value 1
    }

    boolean isPasswordVerified() {
        return passwordVerified;
    }

    /**
     * Decrypts ciphertext in place, feeding the MAC with the ciphertext bytes
     * first (encrypt-then-MAC, verified against the footer at file end).
     */
    void decryptInPlace(@NonNull byte[] buffer, int offset, int length) throws IOException {
        hmac.update(buffer, offset, length);
        for (int i = 0; i < length; i++) {
            if (keystreamUsed == 16) {
                try {
                    aes.doFinal(counter, 0, 16, keystream, 0);
                } catch (GeneralSecurityException e) {
                    throw new IOException("EGG AES keystream failure: " + e.getMessage());
                }
                incrementCounter();
                keystreamUsed = 0;
            }
            buffer[offset + i] ^= keystream[keystreamUsed++];
        }
    }

    /** Verifies the 10-byte truncated HMAC-SHA1 footer over the ciphertext. */
    boolean verifyFooter(@NonNull byte[] footer) {
        byte[] mac = hmac.doFinal();
        return footer.length >= FOOTER_LENGTH
                && MessageDigest.isEqual(Arrays.copyOf(mac, FOOTER_LENGTH), Arrays.copyOf(footer, FOOTER_LENGTH));
    }

    private void incrementCounter() {
        for (int i = 0; i < counter.length; i++) {
            if (++counter[i] != 0) break;
        }
    }

    /**
     * Byte-exact password handling: an EGG password is whatever byte sequence
     * the archiver hashed. Callers try UTF-8 first and, for non-ASCII
     * passwords, fall back to the Windows-949 bytes legacy ALZip would use.
     */
    @NonNull
    static byte[] passwordBytes(@NonNull char[] password, @NonNull Charset charset) {
        return new String(password).getBytes(charset);
    }

    @NonNull
    static byte[] passwordBytesUtf8(@NonNull char[] password) {
        return passwordBytes(password, StandardCharsets.UTF_8);
    }

    /** RFC 2898 PBKDF2 with HMAC-SHA1 over raw password bytes. */
    @NonNull
    private static byte[] pbkdf2HmacSha1(@NonNull byte[] password,
                                         @NonNull byte[] salt,
                                         int iterations,
                                         int dkLen) throws GeneralSecurityException {
        Mac prf = Mac.getInstance("HmacSHA1");
        // A single zero byte is HMAC-equivalent to an empty key (both zero-pad
        // to the block size); JCE rejects zero-length key material.
        prf.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA1"));
        int hLen = prf.getMacLength();
        int blocks = (dkLen + hLen - 1) / hLen;
        byte[] out = new byte[blocks * hLen];
        byte[] intBuf = new byte[4];
        for (int block = 1; block <= blocks; block++) {
            intBuf[0] = (byte) (block >>> 24);
            intBuf[1] = (byte) (block >>> 16);
            intBuf[2] = (byte) (block >>> 8);
            intBuf[3] = (byte) block;
            prf.update(salt);
            byte[] u = prf.doFinal(intBuf);
            byte[] t = u.clone();
            for (int iter = 1; iter < iterations; iter++) {
                u = prf.doFinal(u);
                for (int i = 0; i < hLen; i++) t[i] ^= u[i];
            }
            System.arraycopy(t, 0, out, (block - 1) * hLen, hLen);
        }
        return Arrays.copyOf(out, dkLen);
    }
}
