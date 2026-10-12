package org.apache.commons.compress.archivers.zip;

import java.io.IOException;
import java.io.InputStream;

/**
 * First-party Readwide access adapter for Commons Compress 1.28.0's package-private
 * ZIP decoders. No codec implementation is copied here; dependency upgrades must
 * retain/review these constructor contracts. See THIRD_PARTY_NOTICES.md.
 */
public final class ReadwideZipLegacyStreams {
    private ReadwideZipLegacyStreams() {}

    /** Takes ownership of input for the supported Shrink and Implode methods. */
    public static InputStream open(int method, int rawFlags, InputStream input) throws IOException {
        if (input == null) throw new NullPointerException("input");
        try {
            if (method == 1) return new UnshrinkingInputStream(input);
            if (method == 6) {
                int dictionary = (rawFlags & 2) == 0 ? 4096 : 8192;
                int trees = (rawFlags & 4) == 0 ? 2 : 3;
                return new ExplodingInputStream(dictionary, trees, input);
            }
            throw new IOException("Unsupported legacy ZIP compression method " + method);
        } catch (IOException | RuntimeException | Error failure) {
            try { input.close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}
