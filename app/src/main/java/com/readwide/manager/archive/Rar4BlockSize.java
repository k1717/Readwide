package com.readwide.manager.archive;

import java.io.IOException;

/** Packed-size fields shared by RAR4 file and service headers. */
final class Rar4BlockSize {
    private Rar4BlockSize() {}

    static long packedSize(int flags, byte[] header, int bodyOffset) throws IOException {
        boolean large = (flags & 0x0100) != 0;
        int minimumSize = large ? 33 : 25;
        if (bodyOffset < 0 || bodyOffset > header.length
                || minimumSize > header.length - bodyOffset) {
            throw new IOException("Truncated RAR4 file or service header");
        }
        long packed = uint32(header, bodyOffset);
        if (large) packed |= uint32(header, bodyOffset + 25) << 32;
        if (packed < 0L) throw new IOException("RAR4 packed size exceeds the supported numeric range");
        return packed;
    }

    private static long uint32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xff)
                | (((long) bytes[offset + 1] & 0xff) << 8)
                | (((long) bytes[offset + 2] & 0xff) << 16)
                | (((long) bytes[offset + 3] & 0xff) << 24);
    }
}
