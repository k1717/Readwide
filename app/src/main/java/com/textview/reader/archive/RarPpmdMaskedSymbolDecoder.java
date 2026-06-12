package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Production-facing masked-symbol arithmetic primitive for the RAR3/RAR4 PPMd work.
 *
 * <p>This class does not enable the live PPMd stream yet. It isolates the exact operation the live
 * decoder will need after SEE selection: combine the unmasked context scale with the current SEE
 * escape estimate, read a range-decoder count, map that count either to an unmasked state subrange
 * or to the escape subrange, and update the local model helpers consistently. Keeping this logic in
 * one place prevents the diagnostic suffix path and the future production path from drifting.</p>
 */
final class RarPpmdMaskedSymbolDecoder {
    private RarPpmdMaskedSymbolDecoder() {
    }

    @NonNull
    static Result decode(@NonNull RarPpmdContext context,
                         @NonNull RarPpmdRangeDecoder rangeDecoder,
                         @NonNull RarPpmdEscapeMask mask,
                         @NonNull RarPpmdSeeContext seeContext) throws IOException {
        int symbolScale = context.unmaskedScale(mask);
        if (symbolScale <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd masked-symbol decoder has no unmasked states for suffix fallback");
        }
        int escapeScale = seeContext.mean();
        int totalScale = safeTotalScale(symbolScale, escapeScale);
        int count = rangeDecoder.currentCount(totalScale);
        if (count >= symbolScale) {
            rangeDecoder.removeSubrange(symbolScale, totalScale, totalScale);
            int maskedBefore = mask.maskedCount();
            mask.markContext(context);
            seeContext.updateAfterEscape();
            return Result.escape(count, symbolScale, totalScale, escapeScale,
                    maskedBefore, mask.maskedCount());
        }

        int low = 0;
        for (int i = 0; i < context.stateCount(); i++) {
            RarPpmdStateRecord state = context.stateAt(i);
            if (mask.isMasked(state.symbol())) continue;
            int high = low + state.frequency();
            if (count < high) {
                rangeDecoder.removeSubrange(low, high, totalScale);
                state.incrementFrequency(1);
                context.promoteState(state.symbol());
                context.rescaleIfNeeded(RarPpmdRescalePolicy.diagnosticDefault());
                seeContext.updateAfterSymbol();
                return Result.symbol(state.symbol(), count, low, high, symbolScale,
                        totalScale, escapeScale, mask.maskedCount());
            }
            low = high;
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 PPMd masked-symbol decoder count did not map to an unmasked state: "
                        + count + " / " + totalScale + "; unmaskedScale=" + symbolScale
                        + "; masked=" + mask.maskedCount());
    }

    private static int safeTotalScale(int symbolScale, int escapeScale) throws IOException {
        if (symbolScale <= 0 || escapeScale <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd masked-symbol decoder received invalid scale: symbolScale="
                            + symbolScale + "; escapeScale=" + escapeScale);
        }
        long total = (long) symbolScale + (long) escapeScale;
        if (total > Integer.MAX_VALUE) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd masked-symbol decoder scale overflow: symbolScale="
                            + symbolScale + "; escapeScale=" + escapeScale);
        }
        return (int) total;
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

        private Result(boolean escape,
                       int symbol,
                       int count,
                       int lowCount,
                       int highCount,
                       int symbolScale,
                       int totalScale,
                       int escapeScale,
                       int maskedCountBefore,
                       int maskedCountAfter) {
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
        }

        static Result symbol(int symbol,
                             int count,
                             int lowCount,
                             int highCount,
                             int symbolScale,
                             int totalScale,
                             int escapeScale,
                             int maskedCount) {
            return new Result(false, symbol & 0xff, count, lowCount, highCount, symbolScale,
                    totalScale, escapeScale, maskedCount, maskedCount);
        }

        static Result escape(int count,
                             int symbolScale,
                             int totalScale,
                             int escapeScale,
                             int maskedCountBefore,
                             int maskedCountAfter) {
            return new Result(true, RarPpmdModel.DIAGNOSTIC_ESCAPE, count, symbolScale,
                    totalScale, symbolScale, totalScale, escapeScale,
                    maskedCountBefore, maskedCountAfter);
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
                    + "; maskedAfter=" + maskedCountAfter;
        }
    }
}
