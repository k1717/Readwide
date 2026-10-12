package com.readwide.manager.archive;

import java.io.IOException;
import java.io.InputStream;

/** Compatibility facade for additional-codec callers; definitions live in the shared registry. */
final class SevenZAdditionalCoders {
    private SevenZAdditionalCoders() {}

    static boolean isModernBcj(byte[] id) {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.find(id);
        return entry != null && entry.isModernBcj();
    }

    /** Null means outside this facade; recognised methods with bad properties fail explicitly. */
    static InputStream open(byte[] id, byte[] properties, InputStream input) throws IOException {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.find(id);
        if (entry == null || !entry.isAdditional()) return null;
        // Historical facade callers use size-independent codecs. New Brotli framing
        // requires the real coder size; the archive planner always supplies it.
        if (entry.id.equals("04F71102")) throw new IOException("7z Brotli requires a declared output size");
        return entry.prepare(properties, 0).open(new InputStream[]{input}, null);
    }

    static InputStream open(byte[] id, byte[] properties, InputStream input, long outputSize) throws IOException {
        SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.find(id);
        if (entry == null || !entry.isAdditional()) return null;
        return entry.prepare(properties, outputSize).open(new InputStream[]{input}, null);
    }
}
