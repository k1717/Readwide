package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/** Header-only completeness check for the Amiga byte-split profile, not a second decoder. */
final class LhaSplitFraming {
    private LhaSplitFraming() {}

    static void validate(File merged) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(merged, "r")) {
            long size = input.length(), position = 0;
            byte[] fixed = new byte[22];
            while (true) {
                checkpoint();
                if (position >= size) throw new IOException("Missing LHA end marker in split archive");
                input.seek(position);
                int first = input.readUnsignedByte();
                if (first == 0) {
                    if (position != size - 1) throw new IOException("Data after LHA split archive end marker");
                    checkpoint();
                    return;
                }
                requireRange(position, fixed.length, size);
                input.seek(position);
                input.readFully(fixed);
                boolean pm0 = fixed[2] == '-' && fixed[3] == 'p' && fixed[4] == 'm'
                        && fixed[5] == '0' && fixed[6] == '-' && fixed[20] == 0;
                if (!pm0 && (fixed[2] != '-' || fixed[3] != 'l' || fixed[6] != '-')) {
                    throw new IOException("Invalid LHA split archive header");
                }
                int level = fixed[20] & 255;
                int sizeWidth = level == 3 ? 4 : 2;
                long headerSize, packed = unsigned(fixed, 7, 4), extendedStart = -1;
                if (pm0 && packed != unsigned(fixed, 11, 4)) {
                    throw new IOException("Invalid PMarc stored entry size");
                }
                if (level == 0 || level == 1) {
                    headerSize = first + 2;
                    int minimum = (level == 0 ? 24 : 27) + (fixed[21] & 255);
                    // Old level-0 headers may omit the two-byte data CRC entirely.
                    if (headerSize < minimum && !(level == 0 && !pm0 && headerSize == minimum - 2)) {
                        throw new IOException("Invalid LHA base header size");
                    }
                    if (level == 1) extendedStart = position + headerSize - 2;
                } else if (level == 2) {
                    headerSize = unsigned(fixed, 0, 2);
                    if (headerSize < 26) throw new IOException("Invalid LHA level-2 header size");
                    extendedStart = position + 24;
                } else if (level == 3) {
                    if (unsigned(fixed, 0, 2) != 4) throw new IOException("Invalid LHA level-3 size field width");
                    requireRange(position, 28, size);
                    input.seek(position + 24);
                    headerSize = readUnsigned(input, 4);
                    if (headerSize < 32) throw new IOException("Invalid LHA level-3 header size");
                    extendedStart = position + 28;
                } else {
                    throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported LHA split header level");
                }
                requireRange(position, headerSize, size);
                long headerEnd = position + headerSize;
                long extendedEnd = headerEnd;
                if (extendedStart >= 0) {
                    long limit;
                    if (level == 1) {
                        // A later 64-bit size field can replace the 32-bit placeholder.
                        // Only the extended-header scan is bounded by the initial skip size.
                        limit = headerEnd + Math.min(packed, size - headerEnd);
                    } else limit = headerEnd;
                    long cursor = extendedStart;
                    while (true) {
                        checkpoint();
                        requireRange(cursor, sizeWidth, limit);
                        input.seek(cursor);
                        long length = readUnsigned(input, sizeWidth);
                        if (length == 0) { extendedEnd = cursor + sizeWidth; break; }
                        if (length <= sizeWidth) throw new IOException("Invalid LHA extended header size");
                        requireRange(cursor, length + sizeWidth, limit);
                        int type = input.readUnsignedByte();
                        if (type == 0x39) {
                            throw new ArchiveSupport.UnsupportedArchiveFeatureException("LHA multi-disc headers are not raw byte splits");
                        }
                        if (type == 0x42) {
                            if (length != sizeWidth + 17) throw new IOException("Invalid LHA large-size header");
                            byte[] sizes = new byte[16];
                            input.readFully(sizes);
                            packed = unsigned(sizes, 0, 8);
                            if (packed < 0 || unsigned(sizes, 8, 8) < 0) throw new IOException("LHA size exceeds file offset range");
                        }
                        cursor += length;
                    }
                }
                // Level 2 permits header padding; the native level-3 reader starts
                // payload immediately after its four-byte extended-header terminator.
                if (level == 3 && extendedEnd != headerEnd) throw new IOException("LHA level-3 header size mismatch");
                requireRange(headerEnd, packed, size);
                long next = headerEnd + packed;
                if (next < extendedEnd) throw new IOException("LHA packed size excludes its extended headers");
                position = next;
                // Header checksum/CRC, entry CRC, method validity and paths remain native checks.
            }
        }
    }

    private static long unsigned(byte[] bytes, int offset, int count) {
        long result = 0;
        for (int i = 0; i < count; i++) result |= (long) (bytes[offset + i] & 255) << (8 * i);
        return result;
    }

    private static long readUnsigned(RandomAccessFile input, int width) throws IOException {
        long result = 0;
        for (int i = 0; i < width; i++) result |= (long) input.readUnsignedByte() << (8 * i);
        return result;
    }

    private static void requireRange(long offset, long count, long limit) throws IOException {
        if (offset < 0 || count < 0 || offset > limit || count > limit - offset) {
            throw new IOException("Truncated LHA split archive");
        }
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("LHA split framing check cancelled");
    }
}
