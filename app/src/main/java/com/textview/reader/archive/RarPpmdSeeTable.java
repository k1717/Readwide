package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * SEE-context table for the evolving first-party RAR3/RAR4 PPMd model.
 *
 * <p>The model uses a stable 25 x 16 PPMd-I-shaped selector table. The live decoder still remains
 * guarded, but suffix fallback now exercises the same row/column lifetime shape that production
 * masked-symbol decoding will need: model order/state density selects the row, and active
 * mask/history pressure selects the column.</p>
 */
final class RarPpmdSeeTable {
    static final int ROOT_ORDER = 0;
    static final int ORDER1 = 1;
    static final int ORDER2 = 2;

    @NonNull private final RarPpmdSeeContext[][] contexts =
            new RarPpmdSeeContext[RarPpmdSeeSelector.ROWS][RarPpmdSeeSelector.COLUMNS];
    private int lastRow;
    private int lastColumn;
    @NonNull private String lastSelectionDiagnostic = "row=0; column=0; orderBand=0; stateBand=0; maskBand=0; historyBand=0";
    private int selectionCount;

    RarPpmdSeeTable() throws IOException {
        for (int row = 0; row < RarPpmdSeeSelector.ROWS; row++) {
            for (int column = 0; column < RarPpmdSeeSelector.COLUMNS; column++) {
                contexts[row][column] = RarPpmdSeeSelector.initialContext(row, column);
            }
        }
    }

    @NonNull
    RarPpmdSeeContext select(int orderDepth,
                             @NonNull RarPpmdContext context,
                             @NonNull RarPpmdEscapeMask mask) throws IOException {
        return select(orderDepth, context, mask, orderDepth, 0);
    }

    @NonNull
    RarPpmdSeeContext select(int orderDepth,
                             @NonNull RarPpmdContext context,
                             @NonNull RarPpmdEscapeMask mask,
                             int maxOrder,
                             int previousSymbolCount) throws IOException {
        RarPpmdSeeSelector.Selection selection = RarPpmdSeeSelector.select(
                orderDepth, maxOrder, context.stateCount(), mask.maskedCount(), previousSymbolCount);
        lastRow = selection.row;
        lastColumn = selection.column;
        lastSelectionDiagnostic = selection.diagnostic();
        selectionCount++;
        return contexts[lastRow][lastColumn];
    }

    @NonNull
    RarPpmdSeeContext contextForTest(int orderDepth, int stateCount, int maskedCount) {
        try {
            RarPpmdSeeSelector.Selection selection = RarPpmdSeeSelector.select(
                    orderDepth, orderDepth, stateCount, maskedCount, 0);
            return contexts[selection.row][selection.column];
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid RAR3/RAR4 PPMd SEE test selector", e);
        }
    }

    @NonNull
    RarPpmdSeeContext contextAtForTest(int row, int column) throws IOException {
        RarPpmdSeeSelector.initialContext(row, column); // validates row/column without duplicating checks.
        return contexts[row][column];
    }

    int lastOrderBucketForTest() {
        if (lastRow <= 4) return ROOT_ORDER;
        if (lastRow <= 9) return ORDER1;
        return ORDER2;
    }

    int lastStateBucketForTest() {
        return lastRow % 5;
    }

    int lastMaskBucketForTest() {
        return lastColumn / 4;
    }

    int lastRowForTest() {
        return lastRow;
    }

    int lastColumnForTest() {
        return lastColumn;
    }

    int selectionCountForTest() {
        return selectionCount;
    }

    @NonNull
    String diagnostic() {
        return lastSelectionDiagnostic + "; selections=" + selectionCount;
    }
}
