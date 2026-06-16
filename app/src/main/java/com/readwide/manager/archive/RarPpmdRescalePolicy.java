package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Explicit rescale policy for diagnostic and future live RAR3/RAR4 PPMd context updates.
 *
 * <p>The exact RAR PPMd-I update constants still have to be fixture-verified before live decode is
 * enabled. Keeping the rescale threshold as a named policy prevents hidden magic values from being
 * spread across masked-symbol decode, context teaching, and successor traversal diagnostics.</p>
 */
final class RarPpmdRescalePolicy {
    private static final int DIAGNOSTIC_MAX_SCALE = 1 << 14;

    @NonNull private static final RarPpmdRescalePolicy DIAGNOSTIC_DEFAULT =
            new RarPpmdRescalePolicy(DIAGNOSTIC_MAX_SCALE, "diagnostic-ppmd-i-shaped");

    private final int maxScaleBeforeRescale;
    @NonNull private final String name;

    private RarPpmdRescalePolicy(int maxScaleBeforeRescale, @NonNull String name) {
        if (maxScaleBeforeRescale <= 1) {
            throw new IllegalArgumentException("PPMd rescale max scale must be > 1: "
                    + maxScaleBeforeRescale);
        }
        this.maxScaleBeforeRescale = maxScaleBeforeRescale;
        this.name = name;
    }

    @NonNull
    static RarPpmdRescalePolicy diagnosticDefault() {
        return DIAGNOSTIC_DEFAULT;
    }

    @NonNull
    static RarPpmdRescalePolicy forTest(int maxScaleBeforeRescale) {
        return new RarPpmdRescalePolicy(maxScaleBeforeRescale, "test-policy");
    }

    boolean shouldRescale(int scale) throws IOException {
        if (scale < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd context scale is invalid before rescale: " + scale);
        }
        return scale >= maxScaleBeforeRescale;
    }

    int maxScaleBeforeRescaleForTest() {
        return maxScaleBeforeRescale;
    }

    @NonNull
    String diagnostic() {
        return "name=" + name + "; maxScaleBeforeRescale=" + maxScaleBeforeRescale;
    }
}
