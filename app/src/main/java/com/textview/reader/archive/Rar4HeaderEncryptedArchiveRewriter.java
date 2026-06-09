package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Rewrites RAR3/RAR4 archives created with encrypted headers (-hp) into a temporary RAR4 file
 * with visible headers.
 *
 * <p>RAR3/RAR4 header encryption leaves the marker and main archive header visible. If the main
 * header has the password flag, an 8-byte salt follows it and all subsequent header blocks are
 * AES-CBC encrypted and padded to a 16-byte boundary. This helper decrypts only those header
 * blocks, removes the main-header password flag and header salt, and copies file payload bytes
 * unchanged. File data encryption, compression, split handling, and CRC verification remain owned
 * by the existing visible-header RAR paths.</p>
 */
final class Rar4HeaderEncryptedArchiveRewriter {
    private static final byte[] RAR4_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
    };

    private static final int AES_BLOCK_SIZE = 16;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_HEADER_SIZE = 2 * 1024 * 1024;

    private static final int RAR4_HEADER_MAIN = 0x73;
    private static final int RAR4_HEADER_FILE = 0x74;
    private static final int RAR4_HEADER_END = 0x7b;
    private static final int RAR4_MAIN_PASSWORD = 0x0080;
    private static final int RAR4_LONG_BLOCK = 0x8000;
    private static final int RAR4_FILE_LARGE = 0x0100;

    private Rar4HeaderEncryptedArchiveRewriter() {}

    static boolean isRar4HeaderEncrypted(@NonNull File archive) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(archive, "r")) {
            MainHeader main = readEncryptedMainHeader(in);
            return main != null;
        }
    }

    @NonNull
    static File buildDecryptedHeaderCopy(@NonNull File archive,
                                         @NonNull char[] password,
                                         @Nullable FileOperationProgress progress) throws IOException {
        File temp = File.createTempFile("textview-rar4-hp-headers-", ".rar");
        boolean success = false;
        try {
            writeDecryptedHeaderCopy(archive, temp, password, progress);
            success = true;
            return temp;
        } finally {
            if (!success) safeDelete(temp);
        }
    }

    static void writeDecryptedHeaderCopy(@NonNull File archive,
                                         @NonNull File output,
                                         @NonNull char[] password,
                                         @Nullable FileOperationProgress progress) throws IOException {
        if (password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        try (RandomAccessFile in = new RandomAccessFile(archive, "r");
             FileOutputStream out = new FileOutputStream(output)) {
            MainHeader main = readEncryptedMainHeader(in);
            if (main == null) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "Archive is not a RAR3/RAR4 header-encrypted archive");
            }
            byte[] salt = new byte[8];
            in.seek(main.saltOffset);
            in.readFully(salt);
            Rar3Crypto.Parameters parameters = Rar3Crypto.deriveParameters(password, salt);

            out.write(RAR4_SIGNATURE);
            writeRar4Header(out, RAR4_HEADER_MAIN, main.flags & ~RAR4_MAIN_PASSWORD, main.body);

            long position = main.saltOffset + salt.length;
            boolean sawEnd = false;
            while (position < in.length()) {
                if (progress != null && !progress.checkpoint()) {
                    throw new IOException("RAR extraction cancelled");
                }
                DecryptedHeader header = readEncryptedHeader(in, position, parameters);
                out.write(header.plainHeader);
                position += header.encryptedHeaderSize;
                if (header.dataSize > 0L) {
                    copyRange(in, position, header.dataSize, out, progress);
                    position += header.dataSize;
                }
                if (header.type == RAR4_HEADER_END) {
                    sawEnd = true;
                    break;
                }
            }
            // Some damaged archives omit ENDARC. If at least all headers decrypted cleanly, let the
            // normal parser/libarchive decide whether the rewritten stream is acceptable.
            if (!sawEnd && out.getChannel().position() <= RAR4_SIGNATURE.length) {
                throw new BadRarPasswordException();
            }
        } catch (BadRarPasswordException e) {
            safeDelete(output);
            throw new ArchiveSupport.PasswordRequiredException();
        } catch (IOException | RuntimeException e) {
            safeDelete(output);
            throw e;
        }
    }

    @Nullable
    private static MainHeader readEncryptedMainHeader(@NonNull RandomAccessFile in) throws IOException {
        RarArchiveLocator.Signature signature = RarArchiveLocator.findSignature(in);
        if (signature == null || signature.version != 4) return null;
        long headerStart = signature.offset + RarArchiveLocator.signatureLength(signature.version);
        if (in.length() - headerStart < 7 + 8) return null;
        in.seek(headerStart);
        readUInt16LE(in); // Header CRC. Rewritten output recalculates the main header CRC.
        int type = in.readUnsignedByte();
        int flags = readUInt16LE(in);
        int headerSize = readUInt16LE(in);
        if (type != RAR4_HEADER_MAIN || headerSize < 7 || headerSize > MAX_HEADER_SIZE) return null;
        long bodyStart = in.getFilePointer();
        long bodyEnd = headerStart + headerSize;
        if (bodyEnd < bodyStart || bodyEnd + 8 > in.length()) return null;
        byte[] body = new byte[(int) (bodyEnd - bodyStart)];
        in.readFully(body);
        if ((flags & RAR4_MAIN_PASSWORD) == 0) return null;
        return new MainHeader(flags, body, bodyEnd);
    }

    @NonNull
    private static DecryptedHeader readEncryptedHeader(@NonNull RandomAccessFile in,
                                                       long position,
                                                       @NonNull Rar3Crypto.Parameters parameters) throws IOException,
            BadRarPasswordException {
        if (position < 0L || position + AES_BLOCK_SIZE > in.length()) throw new BadRarPasswordException();
        byte[] firstEncrypted = new byte[AES_BLOCK_SIZE];
        in.seek(position);
        in.readFully(firstEncrypted);
        byte[] firstPlain = decryptBlock(parameters, firstEncrypted);
        int type = firstPlain[2] & 0xff;
        int flags = uint16(firstPlain, 3);
        int headerSize = uint16(firstPlain, 5);
        if (!isPlausibleHeaderType(type) || headerSize < 7 || headerSize > MAX_HEADER_SIZE) {
            throw new BadRarPasswordException();
        }
        long encryptedHeaderSize = align16(headerSize);
        if (encryptedHeaderSize <= 0L || position + encryptedHeaderSize > in.length()) {
            throw new BadRarPasswordException();
        }
        byte[] encrypted = new byte[(int) encryptedHeaderSize];
        in.seek(position);
        in.readFully(encrypted);
        byte[] decrypted = decryptBlock(parameters, encrypted);
        byte[] plainHeader = Arrays.copyOf(decrypted, headerSize);
        if (!hasValidHeaderCrc(plainHeader)) throw new BadRarPasswordException();
        long dataSize = payloadSize(type, flags, plainHeader);
        if (dataSize < 0L || position + encryptedHeaderSize + dataSize > in.length()) {
            throw new BadRarPasswordException();
        }
        return new DecryptedHeader(type, plainHeader, encryptedHeaderSize, dataSize);
    }

    @NonNull
    private static byte[] decryptBlock(@NonNull Rar3Crypto.Parameters parameters,
                                       @NonNull byte[] encrypted) throws IOException {
        if ((encrypted.length % AES_BLOCK_SIZE) != 0) throw new BadRarPasswordException();
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(parameters.key, "AES"),
                    new IvParameterSpec(parameters.iv));
            byte[] out = new byte[encrypted.length];
            int written = cipher.update(encrypted, 0, encrypted.length, out, 0);
            written += cipher.doFinal(out, written);
            return written == out.length ? out : Arrays.copyOf(out, written);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("RAR3/RAR4 header decrypt failed", e);
        }
    }

    private static long payloadSize(int type, int flags, @NonNull byte[] plainHeader) throws IOException {
        if (type == RAR4_HEADER_FILE) {
            if (plainHeader.length < 7 + 25) throw new BadRarPasswordException();
            long packedSize = uint32(plainHeader, 7);
            if ((flags & RAR4_FILE_LARGE) != 0) {
                if (plainHeader.length < 7 + 33) throw new BadRarPasswordException();
                packedSize |= uint32(plainHeader, 7 + 25) << 32;
            }
            return packedSize;
        }
        if ((flags & RAR4_LONG_BLOCK) != 0) {
            if (plainHeader.length < 11) throw new BadRarPasswordException();
            return uint32(plainHeader, 7);
        }
        return 0L;
    }

    private static long align16(long value) {
        long remainder = value % AES_BLOCK_SIZE;
        return remainder == 0L ? value : value + (AES_BLOCK_SIZE - remainder);
    }

    private static boolean isPlausibleHeaderType(int type) {
        return type >= 0x72 && type <= 0x7b;
    }

    private static boolean hasValidHeaderCrc(@NonNull byte[] plainHeader) {
        if (plainHeader.length < 7) return false;
        int expected = uint16(plainHeader, 0);
        CRC32 crc32 = new CRC32();
        crc32.update(plainHeader, 2, plainHeader.length - 2);
        return ((int) crc32.getValue() & 0xffff) == expected;
    }

    private static void copyRange(@NonNull RandomAccessFile in,
                                  long offset,
                                  long size,
                                  @NonNull FileOutputStream out,
                                  @Nullable FileOperationProgress progress) throws IOException {
        if (size < 0L) throw new IOException("Invalid RAR payload size");
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        in.seek(offset);
        long remaining = size;
        while (remaining > 0L) {
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            int request = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, request);
            if (read < 0) throw new EOFException("Unexpected EOF in RAR payload");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void writeRar4Header(@NonNull FileOutputStream out,
                                        int type,
                                        int flags,
                                        @NonNull byte[] headerData) throws IOException {
        int size = 7 + headerData.length;
        CRC32 crc32 = new CRC32();
        crc32.update(type & 0xff);
        crc32.update(flags & 0xff);
        crc32.update((flags >>> 8) & 0xff);
        crc32.update(size & 0xff);
        crc32.update((size >>> 8) & 0xff);
        crc32.update(headerData, 0, headerData.length);
        writeUInt16LE(out, (int) crc32.getValue() & 0xffff);
        out.write(type & 0xff);
        writeUInt16LE(out, flags);
        writeUInt16LE(out, size);
        out.write(headerData);
    }

    private static int readUInt16LE(@NonNull RandomAccessFile in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int uint16(@NonNull byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long uint32(@NonNull byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24);
    }

    private static void writeUInt16LE(@NonNull FileOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void safeDelete(@Nullable File file) {
        if (file == null || !file.exists()) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (SecurityException ignored) {
        }
    }

    private static final class MainHeader {
        final int flags;
        final byte[] body;
        final long saltOffset;

        MainHeader(int flags, @NonNull byte[] body, long saltOffset) {
            this.flags = flags;
            this.body = body;
            this.saltOffset = saltOffset;
        }
    }

    private static final class DecryptedHeader {
        final int type;
        final byte[] plainHeader;
        final long encryptedHeaderSize;
        final long dataSize;

        DecryptedHeader(int type, @NonNull byte[] plainHeader, long encryptedHeaderSize, long dataSize) {
            this.type = type;
            this.plainHeader = plainHeader;
            this.encryptedHeaderSize = encryptedHeaderSize;
            this.dataSize = dataSize;
        }
    }

    private static final class BadRarPasswordException extends IOException {
    }
}
