package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Primary-context arithmetic decode primitive for the RAR3/RAR4 PPMd diagnostic path.
 *
 * <p>RAR PPMd does not use a SEE estimate for the current {@code MinContext}. Multi-state primary
 * contexts decode against the context {@code SummFreq}; suffix contexts use the masked SEE path.
 * This helper models that separation for the first-party implementation without enabling full
 * production extraction yet. It is especially useful for reset blocks, whose root context starts as
 * a full 256-symbol alphabet with {@code SummFreq = 257}.</p>
 */
final class RarPpmdPrimaryContextDecoder {
    private RarPpmdPrimaryContextDecoder() {}

    @NonNull
    static Result decode(@NonNull RarPpmdContext context,
                         @NonNull RarPpmdRangeDecoder rangeDecoder,
                         @NonNull RarPpmdEscapeMask mask) throws IOException {
        return decode(context, rangeDecoder, mask, RarPpmdPrimaryUpdatePolicy.unrarShaped());
    }

    @NonNull
    static Result decode(@NonNull RarPpmdContext context,
                         @NonNull RarPpmdRangeDecoder rangeDecoder,
                         @NonNull RarPpmdEscapeMask mask,
                         @NonNull RarPpmdPrimaryUpdatePolicy updatePolicy) throws IOException {
        if (mask.maskedCount() != 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd primary-context decode cannot run with masked suffix symbols: "
                            + mask.maskedCount());
        }
        int symbolScale = context.scale();
        int escapeScale = context.primaryEscapeScale();
        int totalScale = context.primaryScale();
        if (symbolScale <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd primary-context decoder has no states");
        }
        int count = rangeDecoder.currentCount(totalScale);
        if (count >= symbolScale) {
            rangeDecoder.removeSubrange(symbolScale, totalScale, totalScale);
            int maskedBefore = mask.maskedCount();
            mask.markContext(context);
            return Result.escape(count, symbolScale, totalScale, escapeScale,
                    maskedBefore, mask.maskedCount());
        }

        int low = 0;
        for (int i = 0; i < context.stateCount(); i++) {
            RarPpmdStateRecord state = context.stateAt(i);
            int high = low + state.frequency();
            if (count < high) {
                rangeDecoder.removeSubrange(low, high, totalScale);
                if (updatePolicy.frequencyDelta() > 0) {
                    state.incrementFrequency(updatePolicy.frequencyDelta());
                }
                if (updatePolicy.promoteOneStepIfMoreFrequent()) {
                    context.promoteStateOneStepIfMoreFrequent(state.symbol());
                }
                if (updatePolicy.rescaleIfNeeded()) {
                    context.rescaleIfNeeded(RarPpmdRescalePolicy.diagnosticDefault());
                }
                return Result.symbol(state.symbol(), count, low, high, symbolScale,
                        totalScale, escapeScale, updatePolicy);
            }
            low = high;
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 PPMd primary-context count did not map to a state: "
                        + count + " / " + totalScale + "; symbolScale=" + symbolScale
                        + "; escapeScale=" + escapeScale);
    }

    static final class Result {
        final boolean escape;
        final int symbol;
        final int count;
        final int lowCount;
        final int highCount;
        final int symbolScale;
        final int totalScale;
        final int escapeScale;
        final int maskedCountBefore;
        final int maskedCountAfter;
        @NonNull final String updatePolicyDiagnostic;

        private Result(boolean escape,
                       int symbol,
                       int count,
                       int lowCount,
                       int highCount,
                       int symbolScale,
                       int totalScale,
                       int escapeScale,
                       int maskedCountBefore,
                       int maskedCountAfter,
                       @NonNull String updatePolicyDiagnostic) {
            this.escape = escape;
            this.symbol = symbol;
            this.count = count;
            this.lowCount = lowCount;
            this.highCount = highCount;
            this.symbolScale = symbolScale;
            this.totalScale = totalScale;
            this.escapeScale = escapeScale;
            this.maskedCountBefore = maskedCountBefore;
            this.maskedCountAfter = maskedCountAfter;
            this.updatePolicyDiagnostic = updatePolicyDiagnostic;
        }

        static Result symbol(int symbol,
                             int count,
                             int lowCount,
                             int highCount,
                             int symbolScale,
                             int totalScale,
                             int escapeScale,
                             @NonNull RarPpmdPrimaryUpdatePolicy updatePolicy) {
            return new Result(false, symbol & 0xff, count, lowCount, highCount,
                    symbolScale, totalScale, escapeScale, 0, 0, updatePolicy.diagnostic());
        }

        static Result escape(int count,
                             int symbolScale,
                             int totalScale,
                             int escapeScale,
                             int maskedCountBefore,
                             int maskedCountAfter) {
            return new Result(true, RarPpmdModel.DIAGNOSTIC_ESCAPE, count, symbolScale,
                    totalScale, symbolScale, totalScale, escapeScale,
                    maskedCountBefore, maskedCountAfter, "escape-no-primary-update");
        }

        int symbolOrEscape() {
            return escape ? RarPpmdModel.DIAGNOSTIC_ESCAPE : symbol;
        }

        @NonNull
        String diagnostic() {
            return "escape=" + escape
                    + "; symbol=" + symbol
                    + "; count=" + count
                    + "; low=" + lowCount
                    + "; high=" + highCount
                    + "; symbolScale=" + symbolScale
                    + "; escapeScale=" + escapeScale
                    + "; totalScale=" + totalScale
                    + "; maskedBefore=" + maskedCountBefore
                    + "; maskedAfter=" + maskedCountAfter
                    + "; updatePolicy={" + updatePolicyDiagnostic + "}";
        }
    }
}
