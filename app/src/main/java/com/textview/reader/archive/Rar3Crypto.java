package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * First-party RAR3/RAR4 AES helper.
 *
 * <p>This class intentionally covers only the crypto layer. It does not implement the RAR3
 * compressed data unpacker, PPMd, VM filters, or solid-state continuation. Those pieces stay
 * outside this helper so the archive reader can support encrypted stored entries without
 * accidentally claiming generic compressed RAR3/RAR4 support.</p>
 */
final class Rar3Crypto {
    private static final int RAR3_KDF_ROUNDS = 1 << 18;
    private static final int RAR3_IV_STEP = RAR3_KDF_ROUNDS / 16;
    private static final int AES_BLOCK_SIZE = 16;

    private Rar3Crypto() {}

    @NonNull
    static Parameters deriveParameters(@NonNull char[] password, @NonNull byte[] salt) throws IOException {
        if (salt.length != 8) {
            throw new RarArchiveReader.UnsupportedRarFeatureException("RAR3/RAR4 encrypted file salt is missing");
        }
        java.nio.ByteBuffer passwordBuffer = StandardCharsets.UTF_16LE.encode(java.nio.CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[passwordBuffer.remaining()];
        passwordBuffer.get(passwordBytes);
        int usedPasswordBytes = passwordBytes.length;
        byte[] packetCounter = new byte[3];
        byte[] iv = new byte[AES_BLOCK_SIZE];
        byte[] digest;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (int round = 0; round < RAR3_KDF_ROUNDS; round++) {
                sha1.update(passwordBytes, 0, usedPasswordBytes);
                sha1.update(salt);
                packetCounter[0] = (byte) round;
                packetCounter[1] = (byte) (round >>> 8);
                packetCounter[2] = (byte) (round >>> 16);
                sha1.update(packetCounter);
                if ((round % RAR3_IV_STEP) == 0) {
                    iv[round / RAR3_IV_STEP] = sha1DigestSnapshot(sha1)[19];
                }
            }
            digest = sha1.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is not available for RAR3/RAR4 password derivation", e);
        }
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i += 4) {
            key[i] = digest[i + 3];
            key[i + 1] = digest[i + 2];
            key[i + 2] = digest[i + 1];
            key[i + 3] = digest[i];
        }
        return new Parameters(key, iv);
    }


    @NonNull
    static Cipher createAesCbcDecryptCipher(@NonNull char[] password,
                                             @NonNull byte[] salt) throws IOException {
        Parameters parameters = deriveParameters(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(parameters.key, "AES"),
                    new IvParameterSpec(parameters.iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("RAR3/RAR4 AES decrypt failed", e);
        }
    }

    @NonNull
    static byte[] decryptAesCbcNoPadding(@NonNull byte[] encrypted,
                                         @NonNull char[] password,
                                         @NonNull byte[] salt) throws IOException {
        if ((encrypted.length % AES_BLOCK_SIZE) != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 encrypted data is not AES block aligned");
        }
        try {
            return createAesCbcDecryptCipher(password, salt).doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IOException("RAR3/RAR4 AES decrypt failed", e);
        }
    }


    @NonNull
    private static byte[] sha1DigestSnapshot(@NonNull MessageDigest sha1) throws IOException {
        try {
            return ((MessageDigest) sha1.clone()).digest();
        } catch (CloneNotSupportedException e) {
            throw new IOException("SHA-1 provider cannot snapshot RAR3/RAR4 KDF state", e);
        }
    }

    static final class Parameters {
        final byte[] key;
        final byte[] iv;

        Parameters(@NonNull byte[] key, @NonNull byte[] iv) {
            this.key = key;
            this.iv = iv;
        }
    }
}
