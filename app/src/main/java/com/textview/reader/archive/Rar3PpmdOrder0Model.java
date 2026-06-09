package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal order-0 PPMd frequency model primitive used by the non-live diagnostic path.
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
        int count = rangeDecoder.currentCount(scale);
        int low = 0;
        for (int symbol = 0; symbol < frequencies.length; symbol++) {
            int frequency = frequencies[symbol];
            if (frequency == 0) continue;
            int high = low + frequency;
            if (count < high) {
                rangeDecoder.removeSubrange(low, high, scale);
                increment(symbol);
                return symbol;
            }
            low = high;
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 PPMd order-0 model count did not map to a symbol: " + count
                        + " / " + scale);
    }

    int frequency(int symbol) {
        return frequencies[symbol & 0xff];
    }

    int scale() {
        return scale;
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
