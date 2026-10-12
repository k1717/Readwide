package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import com.readwide.manager.util.FileOperationProgress;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

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

    AlzipZipCrypto(byte[] password) {
        keys[0] = 305419896;
        keys[1] = 591751049;
        keys[2] = 878082192;
        for (byte value : password) updateKeys(value);
    }

    private AlzipZipCrypto(AlzipZipCrypto original) {
        System.arraycopy(original.keys, 0, keys, 0, keys.length);
    }

    interface PayloadVerifier {
        // False means rejected decoded content (format, length or CRC), never
        // source I/O, cancellation, close or unchecked failure. Those must propagate.
        boolean matches(AlzipZipCrypto candidate) throws IOException;
    }

    /** Keep the original byte interpretation first; modern guesses require payload CRCs. */
    static AlzipZipCrypto checked(char[] password, byte[] header, int checkByte,
                                   boolean hasPayloadCrc) throws IOException {
        return checked(password, header, checkByte, hasPayloadCrc, null);
    }

    /** Probe only ambiguous header matches; return an unconsumed payload key state. */
    static AlzipZipCrypto checked(char[] password, byte[] header, int checkByte,
                                   boolean hasPayloadCrc, PayloadVerifier verifier) throws IOException {
        if (!hasPayloadCrc) verifier = null;
        AlzipZipCrypto[] candidates = new AlzipZipCrypto[3];
        AlzipZipCrypto selected = null;
        try {
            int count = retainCandidate(candidates, 0, new AlzipZipCrypto(password), header, checkByte);
            if (count > 0 && verifier == null) return selected = candidates[0];
            boolean nonAscii = false;
            for (char ch : password) nonAscii |= ch > 127;
            if (nonAscii && hasPayloadCrc) {
                for (String encoding : new String[]{"UTF-8", "MS949"}) {
                    if (!Charset.isSupported(encoding)) continue;
                    ByteBuffer encoded = null;
                    byte[] bytes = null;
                    try {
                        // Never replace unmappable characters or create an immutable
                        // password String. A header match alone is not authentication.
                        encoded = Charset.forName(encoding).newEncoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .encode(CharBuffer.wrap(password));
                        bytes = new byte[encoded.remaining()];
                        encoded.get(bytes);
                        count = retainCandidate(candidates, count, new AlzipZipCrypto(bytes), header, checkByte);
                        if (count > 0 && verifier == null) return selected = candidates[0];
                    } catch (CharacterCodingException ignored) {
                        // This byte representation cannot encode the supplied password.
                    } finally {
                        if (bytes != null) Arrays.fill(bytes, (byte) 0);
                        if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
                    }
                }
            }
            if (count == 0) throw new IOException("Invalid ALZ/EGG ZipCrypto password");
            if (count == 1 || verifier == null) return selected = candidates[0];
            for (int i = 0; i < count; i++) {
                AlzipZipCrypto probe = new AlzipZipCrypto(candidates[i]);
                try {
                    if (verifier.matches(probe)) return selected = candidates[i];
                } finally {
                    probe.destroy();
                }
            }
            throw new IOException("ALZ/EGG ZipCrypto password encodings do not match decoded payload");
        } finally {
            for (AlzipZipCrypto candidate : candidates) {
                if (candidate != null && candidate != selected) candidate.destroy();
            }
        }
    }

    private static int retainCandidate(AlzipZipCrypto[] candidates, int count,
                                       AlzipZipCrypto crypto, byte[] header, int checkByte) {
        boolean retained = false;
        try {
            if (!crypto.checkHeader(header, checkByte)) return count;
            for (int i = 0; i < count; i++) {
                if (Arrays.equals(candidates[i].keys, crypto.keys)) return count;
            }
            candidates[count] = crypto;
            retained = true;
            return count + 1;
        } finally {
            if (!retained) crypto.destroy();
        }
    }

    void destroy() {
        Arrays.fill(keys, 0);
    }

    /** Read-only candidate view; source failures must never become password rejection. */
    static final class ProbeInput extends InputStream {
        final InputStream source;
        final AlzipZipCrypto crypto;
        final FileOperationProgress progress;
        final byte[] one = new byte[1];
        Throwable failure;
        boolean closed;

        ProbeInput(InputStream source, AlzipZipCrypto crypto, FileOperationProgress progress) {
            this.source = source; this.crypto = crypto; this.progress = progress;
        }
        @Override public int read() throws IOException {
            int count = read(one, 0, 1);
            return count == -1 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                rethrowFailure();
                if (closed) throw new IOException("Archive password probe source is closed");
                ArchiveCancellation.checkpoint(progress);
                int count = source.read(bytes, offset, length);
                if (count < -1 || count > length || (length > 0 && count == 0)) {
                    throw new IOException("Invalid archive password probe source read");
                }
                if (count > 0) crypto.decryptInPlace(bytes, offset, count);
                return count;
            } catch (IOException | RuntimeException | Error error) {
                if (failure == null) failure = error;
                throw error;
            }
        }
        void rethrowFailure() throws IOException {
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { source.close(); }
            catch (IOException | RuntimeException | Error error) {
                if (failure == null) failure = error;
                throw error;
            }
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
