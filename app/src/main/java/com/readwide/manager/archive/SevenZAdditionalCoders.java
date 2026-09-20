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
        // Additional coders don't need a declared size; the archive planner supplies real sizes.
        return entry.prepare(properties, 0).open(new InputStream[]{input}, null);
    }
}
