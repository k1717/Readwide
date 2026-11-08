package com.readwide.manager.archive;

import java.io.IOException;
import java.util.zip.CRC32;

/** CRC coverage for RAR4 headers used to admit entries and rewrite payloads. */
final class Rar4HeaderChecksum {
    private Rar4HeaderChecksum() {}

    static void verify(int expected, int type, int flags, byte[] body) throws IOException {
        // Historical auxiliary blocks can include payload bytes in their CRC. They remain skipped.
        if (type != 0x73 && type != 0x74 && type != 0x7a && type != 0x7b) return;
        if (calculate(type, flags, body) != expected) throw new IOException("RAR4 header CRC mismatch");
    }

    static int calculate(int type, int flags, byte[] body) throws IOException {
        if (body.length > 0xffff - 7) throw new IOException("Invalid RAR4 header size");
        int covered = coveredBodyLength(type, flags, body);
        int size = 7 + body.length;
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(flags);
        crc.update(flags >>> 8);
        crc.update(size);
        crc.update(size >>> 8);
        crc.update(body, 0, covered);
        return (int) crc.getValue() & 0xffff;
    }

    private static int coveredBodyLength(int type, int flags, byte[] body) throws IOException {
        if (type == 0x73) {
            // Inline archive comments follow the fixed fields and optional encryption version.
            return advance(0, (flags & 0x0200) == 0 ? 6 : 7, body.length);
        }
        if (type != 0x74 || (flags & 0x0008) == 0) return body.length;

        // A legacy file comment follows the name, salt and extended timestamps.
        int end = advance(0, (flags & 0x0100) == 0 ? 25 : 33, body.length);
        int nameSize = (body[19] & 0xff) | ((body[20] & 0xff) << 8);
        end = advance(end, nameSize, body.length);
        if ((flags & 0x0400) != 0) end = advance(end, 8, body.length);
        if ((flags & 0x1000) != 0 && end != body.length) {
            int timeFlagsOffset = end;
            end = advance(end, 2, body.length);
            int timeFlags = (body[timeFlagsOffset] & 0xff)
                    | ((body[timeFlagsOffset + 1] & 0xff) << 8);
            for (int index = 0; index < 4; index++) {
                int time = (timeFlags >>> ((3 - index) * 4)) & 0xf;
                if ((time & 8) == 0) continue;
                // The modification time's DOS value is already in the fixed file header.
                end = advance(end, (index == 0 ? 0 : 4) + (time & 3), body.length);
            }
        }
        return end;
    }

    private static int advance(int position, int count, int limit) throws IOException {
        if (count > limit - position) throw new IOException("Truncated RAR4 CRC-covered fields");
        return position + count;
    }
}
