package com.readwide.manager.archive;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.zip.CRC32;

/** ZIP-only Unicode Path extra-field validation; legacy scoring stays in ArchiveFilenameDecoder. */
final class ZipEntryNameDecoder {
    private ZipEntryNameDecoder() {}

    static String unicodePath(byte[] raw, boolean utf8, byte[] extra) {
        return unicodePath(raw, utf8, extra, 0, extra == null ? 0 : extra.length);
    }

    static String unicodePath(byte[] raw, boolean utf8, byte[] extra, int offset, int length) {
        if (utf8 || raw == null || extra == null || (offset | length) < 0
                || offset > extra.length || length > extra.length - offset) return null;
        long rawCrc = -1;
        String found = null;
        int end = offset + length;
        for (int at = offset; end - at >= 4;) {
            int id = u16(extra, at), count = u16(extra, at + 2);
            int data = at + 4;
            if (count > end - data) return null;
            if (id == 0x7075 && count > 5 && extra[data] == 1) {
                // Most entries have no usable Unicode Path field; don't allocate
                // a checksum object or hash their raw names unless one is present.
                if (rawCrc < 0) {
                    CRC32 crc = new CRC32(); crc.update(raw);
                    rawCrc = crc.getValue();
                }
                long expected = (extra[data + 1] & 255L) | ((extra[data + 2] & 255L) << 8)
                        | ((extra[data + 3] & 255L) << 16) | ((extra[data + 4] & 255L) << 24);
                if (expected == rawCrc) {
                    try {
                        String name = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(extra, data + 5, count - 5)).toString();
                        name = Normalizer.normalize(name.replace('\\', '/'), Normalizer.Form.NFC);
                        if (found != null && !found.equals(name)) return null;
                        found = name;
                    } catch (CharacterCodingException invalid) { return null; }
                }
            }
            at = data + count;
        }
        return found;
    }
    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); }
}
