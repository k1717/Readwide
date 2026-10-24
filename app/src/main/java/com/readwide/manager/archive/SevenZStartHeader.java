package com.readwide.manager.archive;

import com.readwide.manager.util.FileOperationProgress;

import java.io.EOFException;
import java.io.IOException;
import java.util.zip.CRC32;

/** Checked archive origin in the original logical input; no executable stub is run. */
final class SevenZStartHeader {
    private static final byte[] SIGNATURE = {'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};
    private static final int HEADER_SIZE = 32;
    private static final int SCAN_CHUNK = 64 * 1024;

    final long origin, dataStart, nextHeaderOffset, nextHeaderSize, nextHeaderCrc;

    private SevenZStartHeader(long origin, long dataStart, long nextHeaderOffset,
                             long nextHeaderSize, long nextHeaderCrc) {
        this.origin = origin;
        this.dataStart = dataStart;
        this.nextHeaderOffset = nextHeaderOffset;
        this.nextHeaderSize = nextHeaderSize;
        this.nextHeaderCrc = nextHeaderCrc;
    }

    static SevenZStartHeader locate(SplitVolumeInput source) throws IOException {
        return locate(source, null);
    }

    static SevenZStartHeader locate(SplitVolumeInput source, FileOperationProgress progress) throws IOException {
        ArchiveCancellation.checkpoint(progress);
        long length = source.length();
        if (length < HEADER_SIZE) throw new EOFException("Truncated 7z start header");
        byte[] first = new byte[HEADER_SIZE];
        readFullyAt(source, 0, first, HEADER_SIZE, progress);
        if (hasSignature(first, 0)) {
            // A damaged ordinary archive must not become a different embedded one.
            if (!validStartCrc(first, 0)) throw invalid("7z start header CRC mismatch");
            return checked(first, 0, 0, length);
        }

        // The actual logical input bounds this search. Keep 31 bytes between
        // chunks so a complete candidate can cross buffer or volume boundaries.
        byte[] buffer = new byte[SCAN_CHUNK + HEADER_SIZE - 1];
        long base = 1;
        while (base <= length - HEADER_SIZE) {
            ArchiveCancellation.checkpoint(progress);
            int count = (int) Math.min(buffer.length, length - base);
            readFullyAt(source, base, buffer, count, progress);
            for (int offset = 0; offset <= count - HEADER_SIZE; offset++) {
                if ((offset & 4095) == 0) ArchiveCancellation.checkpoint(progress);
                if (hasSignature(buffer, offset) && validStartCrc(buffer, offset)) {
                    // Select once by the checked start header, like the reference
                    // reader. Bounds or next-header integrity failure is terminal;
                    // do not repeatedly read large candidates or substitute a later one.
                    return checked(buffer, offset, base + offset, length);
                }
            }
            base += count - (HEADER_SIZE - 1);
        }
        throw new IOException("7z start header not found");
    }

    private static SevenZStartHeader checked(byte[] bytes, int offset, long origin, long length)
            throws IOException {
        if ((bytes[offset + 6] & 255) != 0) {
            throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported 7z major version");
        }
        long relative = littleEndian(bytes, offset + 12, 8);
        long size = littleEndian(bytes, offset + 20, 8);
        long crc = littleEndian(bytes, offset + 28, 4);
        // The candidate is entirely within the source, so origin + 32 is safe.
        long dataStart = origin + HEADER_SIZE;
        if (relative < 0 || size < 0 || relative > length - dataStart) {
            throw invalid("7z next header offset exceeds archive bounds");
        }
        long next = dataStart + relative;
        if (size > length - next) throw invalid("7z next header exceeds archive bounds");
        if (size == 0 && (relative != 0 || crc != 0)) {
            throw invalid("Invalid empty 7z next header");
        }
        return new SevenZStartHeader(origin, dataStart, next, size, crc);
    }

    private static boolean hasSignature(byte[] bytes, int offset) {
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (bytes[offset + i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    private static boolean validStartCrc(byte[] bytes, int offset) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset + 12, 20);
        return crc.getValue() == littleEndian(bytes, offset + 8, 4);
    }

    private static long littleEndian(byte[] bytes, int offset, int width) {
        long value = 0;
        for (int i = 0; i < width; i++) value |= (bytes[offset + i] & 255L) << (8 * i);
        return value;
    }

    private static void readFullyAt(SplitVolumeInput source, long position, byte[] bytes, int length,
                                   FileOperationProgress progress)
            throws IOException {
        int read = 0;
        while (read < length) {
            ArchiveCancellation.checkpoint(progress);
            int count = source.readAt(position + read, bytes, read, length - read);
            if (count < 0) throw new EOFException("Truncated 7z start-header search");
            if (count == 0 || count > length - read) throw new IOException("Invalid 7z source read");
            read += count;
        }
    }

    private static SevenZBcj2ArchiveReader.IntegrityException invalid(String message) {
        return new SevenZBcj2ArchiveReader.IntegrityException(message);
    }
}
