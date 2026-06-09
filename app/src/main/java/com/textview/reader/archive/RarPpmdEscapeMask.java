package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Versioned symbol mask used by the future RAR3/RAR4 PPMd escape path.
 *
 * <p>Masked symbols are excluded while falling through suffix contexts. The real model still has
 * to decide when and how to apply the mask; this helper centralizes the byte-range validation and
 * cheap reset semantics.</p>
 */
final class RarPpmdEscapeMask {
    private final int[] versions = new int[256];
    private int currentVersion = 1;
    private int maskedCount;

    void clear() {
        currentVersion++;
        maskedCount = 0;
        if (currentVersion == 0) {
            Arrays.fill(versions, 0);
            currentVersion = 1;
        }
    }

    void mark(int symbol) throws IOException {
        validateSymbol(symbol);
        int normalized = symbol & 0xff;
        if (versions[normalized] != currentVersion) {
            versions[normalized] = currentVersion;
            maskedCount++;
        }
    }

    void markContext(@NonNull RarPpmdContext context) throws IOException {
        for (int i = 0; i < context.stateCount(); i++) {
            mark(context.stateAt(i).symbol());
        }
    }

    boolean isMasked(int symbol) throws IOException {
        validateSymbol(symbol);
        return versions[symbol & 0xff] == currentVersion;
    }

    int maskedCount() {
        return maskedCount;
    }

    private static void validateSymbol(int symbol) throws IOException {
        if (symbol < 0 || symbol > 255) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd escape mask symbol is out of byte range: " + symbol);
        }
    }
}
