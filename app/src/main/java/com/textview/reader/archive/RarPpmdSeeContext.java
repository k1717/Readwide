package com.textview.reader.archive;

import java.io.IOException;

/**
 * Small SEE-context skeleton for RAR3/RAR4 PPMd escape estimation.
 *
 * <p>The exact RAR PPMd SEE table initialization and context selection are not implemented yet. This
 * class only provides bounded counters and deterministic update rules so future model code has a
 * validated place to store escape-estimator state.</p>
 */
final class RarPpmdSeeContext {
    private static final int MAX_SHIFT = 15;
    private static final int MAX_SUMMARY = 1 << 20;

    private int summary;
    private int shift;
    private int count;

    RarPpmdSeeContext(int summary, int shift, int count) throws IOException {
        validate(summary, shift, count);
        this.summary = summary;
        this.shift = shift;
        this.count = count;
    }

    static RarPpmdSeeContext defaultContext() throws IOException {
        return new RarPpmdSeeContext(8, 3, 4);
    }

    int summary() {
        return summary;
    }

    int shift() {
        return shift;
    }

    int count() {
        return count;
    }

    int mean() {
        int value = summary >>> shift;
        return value <= 0 ? 1 : value;
    }

    void updateAfterEscape() {
        summary += mean();
        if (summary > MAX_SUMMARY) summary = MAX_SUMMARY;
        if (count > 0) count--;
        if (count == 0 && shift < MAX_SHIFT) {
            summary >>>= 1;
            if (summary <= 0) summary = 1;
            shift++;
            count = 3;
        }
    }

    void updateAfterSymbol() {
        int decrease = mean();
        summary -= decrease;
        if (summary <= 0) summary = 1;
        if (count < 0xff) count++;
    }

    private static void validate(int summary, int shift, int count) throws IOException {
        if (summary <= 0 || summary > MAX_SUMMARY || shift < 0 || shift > MAX_SHIFT || count < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd SEE context parameters are invalid: summary=" + summary
                            + ", shift=" + shift + ", count=" + count);
        }
    }
}
