package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InterruptedIOException;
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
    private static final int MAX_CYCLES_POWER = 24;

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
            if (unpackSize >= 0 && (unpackSize > plain.length || plain.length - unpackSize > 15)) {
                Arrays.fill(plain, (byte) 0);
                throw new IOException("7z AES output does not match its declared size");
            }
            if (unpackSize >= 0 && unpackSize < plain.length) {
                byte[] result = Arrays.copyOf(plain, (int) unpackSize);
                Arrays.fill(plain, (byte) 0);
                return result;
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
            boolean closed;
            IOException failure;
            final byte[] single = new byte[1];
            @Override public int read() throws IOException {
                return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (bytes == null) throw new NullPointerException("bytes");
                if ((offset | length) < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException();
                if (closed) throw new IOException("7z AES stream closed");
                if (failure != null) throw failure;
                try {
                    checkCancelled();
                    if (length == 0) return 0;
                    if (remaining == 0) { finish(); return -1; }
                    int count = decrypted.read(bytes, offset, (int) Math.min(remaining, length));
                    if (count < 0) throw new java.io.EOFException("Truncated 7z AES output");
                    remaining -= count;
                    if (remaining == 0) finish();
                    return count;
                } catch (IOException error) {
                    failure = error; // A failed final-size check cannot become a later clean EOF.
                    throw error;
                }
            }
            private void finish() throws IOException {
                if (finished) return;
                int padding = 0;
                checkCancelled();
                while (decrypted.read() != -1) {
                    if (++padding > 15) throw new IOException("7z AES output exceeds its declared size");
                }
                finished = true;
            }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                decrypted.close();
            }
        };
    }

    /** Cheap validation; reject unreasonable KDF work before opening any input. */
    static void validateProperties(byte[] properties) throws IOException {
        parseProperties(properties);
    }

    private static Properties parseProperties(byte[] properties) throws IOException {
        if (properties == null || properties.length == 0) {
            throw new IOException("7z AES properties missing");
        }
        int byte0 = properties[0] & 0xff;
        int power = byte0 & 0x3f;
        // The reference 7-Zip decoder's supported work limit is 2^24 rounds.
        // 0x3f is the distinct direct-key mode, not a work factor of 2^63.
        if (power > MAX_CYCLES_POWER && power != 0x3f) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                    "7z AES key derivation work factor exceeds supported limit");
        }
        if ((byte0 & 0xc0) == 0) {
            // Canonical no-salt/no-IV properties have exactly one byte. Retain
            // the old two-byte {power, 0} form for existing Readwide fixtures.
            if (properties.length != 1
                    && !(properties.length == 2 && properties[1] == 0)) {
                throw new IOException("Invalid 7z AES no-salt/no-IV properties");
            }
            return new Properties(power, 0, 0, properties.length);
        }
        if (properties.length < 2) throw new IOException("7z AES salt/IV sizes missing");
        int byte1 = properties[1] & 0xff;
        int ivSize = ((byte0 >> 6) & 1) + (byte1 & 0x0f);
        int saltSize = ((byte0 >> 7) & 1) + (byte1 >> 4);
        if (2 + saltSize + ivSize != properties.length) {
            throw new IOException("7z AES salt/IV sizes invalid");
        }
        return new Properties(power, saltSize, ivSize, 2);
    }

    private static final class Properties {
        final int power, saltSize, ivSize, offset;
        Properties(int power, int saltSize, int ivSize, int offset) {
            this.power = power; this.saltSize = saltSize;
            this.ivSize = ivSize; this.offset = offset;
        }
    }

    private static Cipher createDecryptCipher(byte[] properties, char[] password) throws IOException {
        checkCancelled();
        Properties parsed = parseProperties(properties);
        if (password == null) throw new IOException("7z AES password missing");
        byte[] salt = Arrays.copyOfRange(properties, parsed.offset, parsed.offset + parsed.saltSize);
        byte[] iv = new byte[16];
        System.arraycopy(properties, parsed.offset + parsed.saltSize, iv, 0, parsed.ivSize);

        byte[] passwordBytes = utf16LeBytes(password);
        byte[] keyBytes = null;
        try {
            keyBytes = deriveKey(passwordBytes, salt, parsed.power);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("7z AES decryption failed", e);
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    @NonNull
    private static byte[] deriveKey(@NonNull byte[] password, @NonNull byte[] salt, int numCyclesPower) throws IOException {
        checkCancelled();
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
        try {
            for (long j = 0; j < cycles; j++) {
                if ((j & 1023) == 0) checkCancelled();
                digest.update(salt);
                digest.update(password);
                digest.update(counter);
                for (int k = 0; k < counter.length; k++) {
                    if (++counter[k] != 0) break;
                }
            }
            checkCancelled();
            return digest.digest();
        } finally {
            Arrays.fill(counter, (byte) 0);
            digest.reset();
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("7z AES decoding cancelled");
        }
    }

    @NonNull
    private static byte[] utf16LeBytes(@NonNull char[] password) {
        // Encode the UTF-16 code units without creating an immutable password String.
        if (password.length > (Integer.MAX_VALUE - 8) / 2) {
            throw new IllegalArgumentException("7z AES password is too long");
        }
        byte[] bytes = new byte[password.length * 2];
        for (int i = 0; i < password.length; i++) {
            bytes[2 * i] = (byte) password[i];
            bytes[2 * i + 1] = (byte) (password[i] >>> 8);
        }
        return bytes;
    }
}
