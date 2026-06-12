package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Production-facing SEE-table selector for the evolving first-party RAR3/RAR4 PPMd decoder.
 *
 * <p>This class deliberately keeps the selection math explicit and testable instead of hiding it
 * inside the context decoder. The table shape follows the PPMd-I style requirement that escape
 * estimators are selected by model order/state density and by the current suffix mask/history
 * pressure. The exact RAR3/RAR4 constants still have to be verified against real fixtures before
 * the live PPMd path is enabled; this pass moves the selection boundary from ad-hoc diagnostic
 * buckets to a stable 25 x 16 table that production masked-symbol decoding can reuse.</p>
 */
final class RarPpmdSeeSelector {
    static final int ROWS = 25;
    static final int COLUMNS = 16;

    private static final int ORDER_BANDS = 5;
    private static final int STATE_BANDS = 5;
    private static final int MASK_BANDS = 4;
    private static final int HISTORY_BANDS = 4;

    private RarPpmdSeeSelector() {
    }

    @NonNull
    static Selection select(int orderDepth,
                            int maxOrder,
                            int stateCount,
                            int maskedCount,
                            int previousSymbolCount) throws IOException {
        if (stateCount < 0 || maskedCount < 0 || previousSymbolCount < 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd SEE selector received a negative metric: orderDepth="
                            + orderDepth + ", maxOrder=" + maxOrder + ", stateCount=" + stateCount
                            + ", maskedCount=" + maskedCount
                            + ", previousSymbolCount=" + previousSymbolCount);
        }
        int boundedOrder = Math.max(0, Math.min(Math.max(0, maxOrder), Math.max(0, orderDepth)));
        int orderBand = orderBand(boundedOrder);
        int stateBand = stateBand(stateCount);
        int maskBand = maskBand(maskedCount);
        int historyBand = historyBand(previousSymbolCount);
        int row = orderBand * STATE_BANDS + stateBand;
        int column = maskBand * HISTORY_BANDS + historyBand;
        return new Selection(row, column, orderBand, stateBand, maskBand, historyBand);
    }

    @NonNull
    static RarPpmdSeeContext initialContext(int row, int column) throws IOException {
        validateRowColumn(row, column);
        int shift = 3 + Math.min(2, row / 10);
        int base = 4 + row + (column >>> 1);
        int count = 4 + (column & 0x03);
        return new RarPpmdSeeContext(base << shift, shift, count);
    }

    private static int orderBand(int orderDepth) {
        if (orderDepth <= 0) return 0;
        if (orderDepth == 1) return 1;
        if (orderDepth == 2) return 2;
        if (orderDepth <= 4) return 3;
        return 4;
    }

    private static int stateBand(int stateCount) {
        if (stateCount <= 1) return 0;
        if (stateCount <= 2) return 1;
        if (stateCount <= 4) return 2;
        if (stateCount <= 8) return 3;
        return 4;
    }

    private static int maskBand(int maskedCount) {
        if (maskedCount <= 0) return 0;
        if (maskedCount <= 1) return 1;
        if (maskedCount <= 4) return 2;
        return 3;
    }

    private static int historyBand(int previousSymbolCount) {
        if (previousSymbolCount <= 0) return 0;
        if (previousSymbolCount == 1) return 1;
        if (previousSymbolCount == 2) return 2;
        return 3;
    }

    private static void validateRowColumn(int row, int column) throws IOException {
        if (row < 0 || row >= ROWS || column < 0 || column >= COLUMNS) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd SEE selector table index is invalid: row=" + row
                            + ", column=" + column);
        }
    }

    static final class Selection {
        final int row;
        final int column;
        final int orderBand;
        final int stateBand;
        final int maskBand;
        final int historyBand;

        Selection(int row, int column, int orderBand, int stateBand, int maskBand, int historyBand) {
            this.row = row;
            this.column = column;
            this.orderBand = orderBand;
            this.stateBand = stateBand;
            this.maskBand = maskBand;
            this.historyBand = historyBand;
        }

        @NonNull
        String diagnostic() {
            return "row=" + row
                    + "; column=" + column
                    + "; orderBand=" + orderBand
                    + "; stateBand=" + stateBand
                    + "; maskBand=" + maskBand
                    + "; historyBand=" + historyBand;
        }
    }
}
