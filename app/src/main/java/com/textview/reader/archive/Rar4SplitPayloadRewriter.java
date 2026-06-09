package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Rebuilds a plain RAR3/RAR4 split file entry as a temporary single-volume RAR4 archive.
 *
 * <p>This is not a decompressor. It only removes the split flags from the first visible FILE
 * header, rewrites the packed size to the sum of the split payloads, concatenates those payloads,
 * and lets libarchive perform the normal compressed unpack step. Keeping this as a rewriter gives
 * us one more real split case without embedding a half-finished RAR3 LZ/Huffman unpacker in the
 * main reader.</p>
 */
final class Rar4SplitPayloadRewriter {
    private static final byte[] RAR4_SIGNATURE = new byte[] {
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00
    };

    private static final int MAX_SFX_SCAN = 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static final int RAR4_HEADER_MAIN = 0x73;
    private static final int RAR4_HEADER_FILE = 0x74;
    private static final int RAR4_HEADER_END = 0x7b;
    private static final int RAR4_FILE_SPLIT_BEFORE = 0x0001;
    private static final int RAR4_FILE_SPLIT_AFTER = 0x0002;
    private static final int RAR4_FILE_PASSWORD = 0x0004;
    private static final int RAR4_FILE_SOLID = 0x0010;
    private static final int RAR4_FILE_LARGE = 0x0100;
    private static final int RAR4_METHOD_STORE = 0x30;

    private Rar4SplitPayloadRewriter() {}

    @NonNull
    static File buildSingleEntryCopy(@NonNull File firstArchive,
                                     long firstDataOffset,
                                     @NonNull List<RarCryptoStreams.EncryptedSegment> segments,
                                     @Nullable FileOperationProgress progress) throws IOException {
        if (segments.isEmpty()) throw new IOException("RAR split chain has no payload segments");
        HeaderBlock sourceBlock = findFileHeaderForDataOffset(firstArchive, firstDataOffset);
        RewrittenFileBlock rewritten = prepareSplitFileBlock(sourceBlock.flags, sourceBlock.headerData, totalPayloadSize(segments));

        File temp = File.createTempFile("textview-rar4-split-plain-", ".rar");
        boolean success = false;
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(RAR4_SIGNATURE);
            writeRar4Header(out, RAR4_HEADER_MAIN, 0, new byte[6]);
            writeRar4Header(out, RAR4_HEADER_FILE, rewritten.flags, rewritten.headerData);
            copySegmentsToStream(segments, out, progress);
            writeRar4Header(out, RAR4_HEADER_END, 0, new byte[0]);
            success = true;
            return temp;
        } finally {
            if (!success) safeDelete(temp);
        }
    }

    @NonNull
    private static HeaderBlock findFileHeaderForDataOffset(@NonNull File archive, long dataOffset) throws IOException {
        if (dataOffset < 0L) throw new IOException("Invalid RAR4 data offset");
        try (RandomAccessFile in = new RandomAccessFile(archive, "r")) {
            long signatureOffset = findRar4SignatureOffset(in);
            if (signatureOffset < 0) throw new IOException("RAR4 signature not found");
            in.seek(signatureOffset + RAR4_SIGNATURE.length);
            while (in.getFilePointer() < in.length()) {
                long headerStart = in.getFilePointer();
                if (in.length() - headerStart < 7) break;
                readUInt16LE(in); // source CRC is advisory; rewritten header CRC is recalculated.
                int type = in.readUnsignedByte();
                int flags = readUInt16LE(in);
                int headerSize = readUInt16LE(in);
                if (headerSize < 7 || headerStart + headerSize > in.length()) {
                    throw new IOException("Invalid RAR4 header size");
                }
                byte[] headerData = new byte[headerSize - 7];
                in.readFully(headerData);
                long currentDataOffset = headerStart + headerSize;
                long dataSize = type == RAR4_HEADER_FILE ? parseFileBlock(flags, headerData).packedSize : 0L;
                if (type == RAR4_HEADER_FILE && currentDataOffset == dataOffset) {
                    return new HeaderBlock(flags, headerData);
                }
                long next = currentDataOffset + dataSize;
                if (next < currentDataOffset || next > in.length()) throw new IOException("Invalid RAR4 data size");
                in.seek(next);
                if (type == RAR4_HEADER_END) break;
            }
        }
        throw new IOException("RAR4 split file header not found");
    }

    @NonNull
    private static RewrittenFileBlock prepareSplitFileBlock(int flags,
                                                            @NonNull byte[] headerData,
                                                            long totalPayloadSize) throws IOException {
        FileBlock block = parseFileBlock(flags, headerData);
        if ((flags & RAR4_FILE_PASSWORD) != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Encrypted RAR3/RAR4 split payload must use the encrypted split rewriter");
        }
        if ((flags & RAR4_FILE_SOLID) != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 solid split payload still requires the solid-state decoder");
        }
        if (block.method == RAR4_METHOD_STORE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Stored RAR3/RAR4 split payload should use the direct stored split extractor");
        }
        if (totalPayloadSize < 0L) throw new IOException("Invalid RAR4 split payload size");
        byte[] rewrittenHeaderData = headerData.clone();
        writePackedSize(rewrittenHeaderData, totalPayloadSize, (flags & RAR4_FILE_LARGE) != 0);
        int rewrittenFlags = flags & ~RAR4_FILE_SPLIT_BEFORE & ~RAR4_FILE_SPLIT_AFTER;
        return new RewrittenFileBlock(rewrittenFlags, rewrittenHeaderData);
    }

    private static long totalPayloadSize(@NonNull List<RarCryptoStreams.EncryptedSegment> segments) throws IOException {
        long total = 0L;
        for (RarCryptoStreams.EncryptedSegment segment : segments) {
            if (segment.encryptedSize < 0L) throw new IOException("Invalid RAR split segment size");
            if (Long.MAX_VALUE - total < segment.encryptedSize) throw new IOException("RAR split payload is too large");
            total += segment.encryptedSize;
        }
        return total;
    }

    private static void copySegmentsToStream(@NonNull List<RarCryptoStreams.EncryptedSegment> segments,
                                             @NonNull FileOutputStream out,
                                             @Nullable FileOperationProgress progress) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        for (RarCryptoStreams.EncryptedSegment segment : segments) {
            if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
            try (RandomAccessFile in = new RandomAccessFile(segment.archive, "r")) {
                in.seek(segment.offset);
                long remaining = segment.encryptedSize;
                while (remaining > 0L) {
                    if (progress != null && !progress.checkpoint()) throw new IOException("RAR extraction cancelled");
                    int request = (int) Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, request);
                    if (read < 0) throw new EOFException("Unexpected EOF in RAR split payload");
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        }
    }

    @NonNull
    private static FileBlock parseFileBlock(int flags, @NonNull byte[] headerData) throws IOException {
        if (headerData.length < 25) throw new IOException("Invalid RAR4 file header");
        long packedSize = uint32(headerData, 0);
        int method = headerData[18] & 0xff;
        int nameSize = uint16(headerData, 19);
        int nameOffset = 25;
        if ((flags & RAR4_FILE_LARGE) != 0) {
            if (headerData.length < 33) throw new IOException("Invalid RAR4 large file header");
            packedSize |= uint32(headerData, 25) << 32;
            nameOffset = 33;
        }
        if (nameOffset + nameSize > headerData.length) throw new IOException("Invalid RAR4 file name size");
        return new FileBlock(packedSize, method);
    }

    private static void writePackedSize(@NonNull byte[] headerData, long packedSize, boolean large) {
        putUInt32(headerData, 0, packedSize & 0xffff_ffffL);
        if (large) putUInt32(headerData, 25, (packedSize >>> 32) & 0xffff_ffffL);
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

    private static long findRar4SignatureOffset(@NonNull RandomAccessFile in) throws IOException {
        long original = in.getFilePointer();
        try {
            long length = Math.max(0L, in.length());
            int scanLimit = (int) Math.min(length, MAX_SFX_SCAN + RAR4_SIGNATURE.length);
            byte[] data = new byte[scanLimit];
            in.seek(0L);
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
            outer:
            for (int i = 0; i <= read - RAR4_SIGNATURE.length; i++) {
                for (int j = 0; j < RAR4_SIGNATURE.length; j++) {
                    if (data[i + j] != RAR4_SIGNATURE[j]) continue outer;
                }
                return i;
            }
            return -1L;
        } finally {
            in.seek(original);
        }
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

    private static void putUInt32(@NonNull byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >>> 8) & 0xff);
        data[offset + 2] = (byte) ((value >>> 16) & 0xff);
        data[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static void writeUInt16LE(@NonNull FileOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void safeDelete(@Nullable File file) {
        if (file != null && file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (SecurityException ignored) {
            }
        }
    }

    private static final class HeaderBlock {
        final int flags;
        final byte[] headerData;

        HeaderBlock(int flags, @NonNull byte[] headerData) {
            this.flags = flags;
            this.headerData = headerData;
        }
    }

    private static final class FileBlock {
        final long packedSize;
        final int method;

        FileBlock(long packedSize, int method) {
            this.packedSize = packedSize;
            this.method = method;
        }
    }

    private static final class RewrittenFileBlock {
        final int flags;
        final byte[] headerData;

        RewrittenFileBlock(int flags, @NonNull byte[] headerData) {
            this.flags = flags;
            this.headerData = headerData;
        }
    }
}
