package com.readwide.manager.archive;

import java.io.IOException;

/** EGG's nine-byte LZMA preamble. This sizes independent blocks, never solid-folder history. */
final class EggLzmaProperties {
    final byte properties;
    final int dictionarySize;
    private EggLzmaProperties(byte properties, int dictionarySize) {
        this.properties = properties; this.dictionarySize = dictionarySize;
    }
    static EggLzmaProperties parse(byte[] header, long outputSize, int implementationMaximum) throws IOException {
        if (header == null || header.length != 9 || outputSize < 0) throw new IOException("Invalid EGG LZMA properties");
        int propertySize = (header[2] & 255) | ((header[3] & 255) << 8);
        // Legacy fixtures use zero for the reserved preamble; real ALZip files declare five.
        if (propertySize != 0 && propertySize != 5) throw new IOException("Invalid EGG LZMA property length");
        if ((header[4] & 255) >= 9 * 5 * 5) throw new IOException("Invalid EGG LZMA lc/lp/pb properties");
        long declared = (header[5] & 255L) | ((header[6] & 255L) << 8)
                | ((header[7] & 255L) << 16) | ((header[8] & 255L) << 24);
        long useful = Math.max(4096L, Math.min(declared, outputSize));
        if (useful > implementationMaximum) throw new ArchiveSupport.UnsupportedArchiveFeatureException(
                "EGG LZMA dictionary exceeds decoder implementation range");
        return new EggLzmaProperties(header[4], (int) useful);
    }
}
