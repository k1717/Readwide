package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * First-party decoder for the 7z AES-256 coder (id {@code 06 F1 07 01}).
 *
 * <p>7z AES is AES-256-CBC with no padding over whole 16-byte blocks. The
 * coder properties encode the numbers of key-derivation cycles, and the salt
 * and IV sizes, followed by the salt and IV. The key is SHA-256 applied to
 * {@code (salt + UTF-16LE(password) + counter)} repeated {@code 2^power}
 * times (or, for power {@code 0x3f}, salt+password truncated to 32 bytes).</p>
 *
 * <p>This mirrors the documented 7z scheme with JCE primitives only; no
 * third-party cryptography code is used. It exists so this reader can decrypt
 * AES-wrapped folders whose inner coder (e.g. BCJ2) Commons Compress cannot
 * decode - the AES layer decrypts here and the inner coder runs afterwards.</p>
 */
final class SevenZAesDecoder {
    private SevenZAesDecoder() {
    }

    @NonNull
    static byte[] decode(@NonNull byte[] cipherText,
                         @androidx.annotation.Nullable byte[] properties,
                         @NonNull char[] password,
                         long unpackSize) throws IOException {
        Cipher cipher = createDecryptCipher(properties, password);
        try {
            byte[] plain = cipher.doFinal(cipherText);
            if (unpackSize >= 0 && unpackSize < plain.length) {
                return Arrays.copyOf(plain, (int) unpackSize);
            }
            return plain;
        } catch (GeneralSecurityException e) {
            throw new IOException("7z AES decryption failed", e);
        }
    }

    /** Decrypts sequentially and validates final CBC padding length without buffering the file. */
    static java.io.InputStream decodeStream(java.io.InputStream input,
                                            byte[] properties, char[] password,
                                            long unpackSize) throws IOException {
        if (unpackSize < 0) throw new IOException("Invalid 7z AES unpacked size");
        javax.crypto.CipherInputStream decrypted = new javax.crypto.CipherInputStream(
                input, createDecryptCipher(properties, password));
        return new java.io.InputStream() {
            long remaining = unpackSize;
            boolean finished;
            final byte[] single = new byte[1];
            @Override public int read() throws IOException {
                return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (bytes == null) throw new NullPointerException("bytes");
                if ((offset | length) < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException();
                if (length == 0) return 0;
                if (remaining == 0) { finish(); return -1; }
                int count = decrypted.read(bytes, offset, (int) Math.min(remaining, length));
                if (count < 0) throw new java.io.EOFException("Truncated 7z AES output");
                remaining -= count;
                if (remaining == 0) finish();
                return count;
            }
            private void finish() throws IOException {
                if (finished) return;
                int padding = 0;
                while (decrypted.read() != -1) {
                    if (++padding > 15) throw new IOException("7z AES output exceeds its declared size");
                }
                finished = true;
            }
            @Override public void close() throws IOException { decrypted.close(); }
        };
    }

    private static Cipher createDecryptCipher(byte[] properties, char[] password) throws IOException {
        if (properties == null || properties.length < 2) {
            throw new IOException("7z AES properties missing");
        }
        int byte0 = properties[0] & 0xff;
        int byte1 = properties[1] & 0xff;
        int numCyclesPower = byte0 & 0x3f;
        int ivSize = ((byte0 >> 6) & 1) + (byte1 & 0x0f);
        int saltSize = ((byte0 >> 7) & 1) + (byte1 >> 4);
        if (2 + saltSize + ivSize > properties.length) {
            throw new IOException("7z AES salt/IV sizes invalid");
        }
        byte[] salt = Arrays.copyOfRange(properties, 2, 2 + saltSize);
        byte[] iv = new byte[16];
        System.arraycopy(properties, 2 + saltSize, iv, 0, ivSize);

        byte[] passwordBytes = utf16LeBytes(password);
        byte[] keyBytes = null;
        try {
            keyBytes = deriveKey(passwordBytes, salt, numCyclesPower);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("7z AES decryption failed: " + e.getMessage());
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    @NonNull
    private static byte[] deriveKey(@NonNull byte[] password, @NonNull byte[] salt, int numCyclesPower) throws IOException {
        if (numCyclesPower == 0x3f) {
            byte[] key = new byte[32];
            System.arraycopy(salt, 0, key, 0, Math.min(salt.length, 32));
            int offset = salt.length;
            System.arraycopy(password, 0, key, offset, Math.min(password.length, 32 - offset));
            return key;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable");
        }
        byte[] counter = new byte[8];
        long cycles = 1L << numCyclesPower;
        for (long j = 0; j < cycles; j++) {
            digest.update(salt);
            digest.update(password);
            digest.update(counter);
            for (int k = 0; k < counter.length; k++) {
                if (++counter[k] != 0) break;
            }
        }
        return digest.digest();
    }

    @NonNull
    private static byte[] utf16LeBytes(@NonNull char[] password) {
        return new String(password).getBytes(StandardCharsets.UTF_16LE);
    }
}
