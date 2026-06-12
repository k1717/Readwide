package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal order-0 PPMd frequency model primitive used by the legacy diagnostic/probe path.
 *
 * <p>This is not the real RAR3/RAR4 PPMd statistical model. It is a tested arithmetic-model
 * primitive that can map {@link RarPpmdRangeDecoder} counts to literal symbols and rescale
 * frequencies. Full PPMd contexts, suffix links, SEE escape estimation, and the real RAR model
 * update rules still remain first-party gaps.</p>
 */
final class Rar3PpmdOrder0Model {
    static final int SYMBOL_COUNT = 256;
    private static final int MAX_SCALE = 1 << 14;

    @NonNull private final int[] frequencies;
    private int scale;

    Rar3PpmdOrder0Model() {
        frequencies = new int[SYMBOL_COUNT];
        Arrays.fill(frequencies, 1);
        scale = SYMBOL_COUNT;
    }

    Rar3PpmdOrder0Model(@NonNull int[] initialFrequencies) throws IOException {
        if (initialFrequencies.length != SYMBOL_COUNT) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd order-0 model requires 256 frequencies, got "
                            + initialFrequencies.length);
        }
        frequencies = initialFrequencies.clone();
        scale = 0;
        for (int frequency : frequencies) {
            if (frequency < 0) {
                throw new RarArchiveReader.UnsupportedRarFeatureException(
                        "RAR3/RAR4 PPMd order-0 model received a negative frequency");
            }
            scale += frequency;
        }
        if (scale <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd order-0 model has an empty alphabet");
        }
    }

    int decodeSymbol(@NonNull RarPpmdRangeDecoder rangeDecoder) throws IOException {
        return decodeSymbol(rangeDecoder, null);
    }

    int decodeSymbol(@NonNull RarPpmdRangeDecoder rangeDecoder,
                     @Nullable RarPpmdEscapeMask mask) throws IOException {
        int activeScale = scale(mask);
        int count = rangeDecoder.currentCount(activeScale);
        int low = 0;
        for (int symbol = 0; symbol < frequencies.length; symbol++) {
            if (mask != null && mask.isMasked(symbol)) continue;
            int frequency = frequencies[symbol];
            if (frequency == 0) continue;
            int high = low + frequency;
            if (count < high) {
                rangeDecoder.removeSubrange(low, high, activeScale);
                increment(symbol);
                return symbol;
            }
            low = high;
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 PPMd order-0 model count did not map to a symbol: " + count
                        + " / " + activeScale + "; masked=" + (mask == null ? 0 : mask.maskedCount()));
    }

    int frequency(int symbol) {
        return frequencies[symbol & 0xff];
    }

    int scale() {
        return scale;
    }

    int scale(@Nullable RarPpmdEscapeMask mask) throws IOException {
        if (mask == null || mask.maskedCount() == 0) return scale;
        int activeScale = 0;
        for (int symbol = 0; symbol < frequencies.length; symbol++) {
            if (!mask.isMasked(symbol)) activeScale += frequencies[symbol];
        }
        if (activeScale <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd order-0 model has no unmasked symbols left");
        }
        return activeScale;
    }

    private void increment(int symbol) {
        frequencies[symbol]++;
        scale++;
        if (scale >= MAX_SCALE) rescale();
    }

    private void rescale() {
        int newScale = 0;
        for (int i = 0; i < frequencies.length; i++) {
            int frequency = frequencies[i];
            if (frequency > 0) {
                frequency = (frequency + 1) >>> 1;
                if (frequency == 0) frequency = 1;
            }
            frequencies[i] = frequency;
            newScale += frequency;
        }
        if (newScale == 0) {
            Arrays.fill(frequencies, 1);
            newScale = SYMBOL_COUNT;
        }
        scale = newScale;
    }
}
