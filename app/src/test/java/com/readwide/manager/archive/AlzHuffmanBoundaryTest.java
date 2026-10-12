package com.readwide.manager.archive;

import org.junit.Test;

/** Same focused checks as scripts/check-archive-decoder-boundaries.py. */
public final class AlzHuffmanBoundaryTest {
    @Test public void positiveCodeLengthEdges() throws Exception { AlzHuffmanBoundaryChecks.checks().get("positiveCodeLengthEdges").run(); }
    @Test public void positiveCodeLengthDeltas() throws Exception { AlzHuffmanBoundaryChecks.checks().get("positiveCodeLengthDeltas").run(); }
    @Test public void positiveUnusedIncompleteTable() throws Exception { AlzHuffmanBoundaryChecks.checks().get("positiveUnusedIncompleteTable").run(); }
    @Test public void codeLengthCharWrapPositive() throws Exception { AlzHuffmanBoundaryChecks.checks().get("codeLengthCharWrapPositive").run(); }
    @Test public void codeLengthCharWrapNegative() throws Exception { AlzHuffmanBoundaryChecks.checks().get("codeLengthCharWrapNegative").run(); }
    @Test public void codeLengthZeroInitial() throws Exception { AlzHuffmanBoundaryChecks.checks().get("codeLengthZeroInitial").run(); }
    @Test public void codeLengthTooLargeInitial() throws Exception { AlzHuffmanBoundaryChecks.checks().get("codeLengthTooLargeInitial").run(); }
    @Test public void codeLengthDipsBelowOne() throws Exception { AlzHuffmanBoundaryChecks.checks().get("codeLengthDipsBelowOne").run(); }
    @Test public void codeLengthRisesAboveTwenty() throws Exception { AlzHuffmanBoundaryChecks.checks().get("codeLengthRisesAboveTwenty").run(); }
    @Test public void codeLengthInvalidUnusedTable() throws Exception { AlzHuffmanBoundaryChecks.checks().get("codeLengthInvalidUnusedTable").run(); }
    @Test public void selectorExhaustionLiteralPath() throws Exception { AlzHuffmanBoundaryChecks.checks().get("selectorExhaustionLiteralPath").run(); }
    @Test public void selectorExhaustionRunPath() throws Exception { AlzHuffmanBoundaryChecks.checks().get("selectorExhaustionRunPath").run(); }
    @Test public void selectorExhaustionAfterPriorBlock() throws Exception { AlzHuffmanBoundaryChecks.checks().get("selectorExhaustionAfterPriorBlock").run(); }
    @Test public void positiveSelectorBoundary() throws Exception { AlzHuffmanBoundaryChecks.checks().get("positiveSelectorBoundary").run(); }
    @Test public void rleIntegerWrap() throws Exception { AlzHuffmanBoundaryChecks.checks().get("rleIntegerWrap").run(); }
    @Test public void rleOverBlockSize() throws Exception { AlzHuffmanBoundaryChecks.checks().get("rleOverBlockSize").run(); }
    @Test public void rleOverRemainingBlockSize() throws Exception { AlzHuffmanBoundaryChecks.checks().get("rleOverRemainingBlockSize").run(); }
    @Test public void positiveShortRuns() throws Exception { AlzHuffmanBoundaryChecks.checks().get("positiveShortRuns").run(); }
    @Test public void missingRleCountByte() throws Exception { AlzHuffmanBoundaryChecks.checks().get("missingRleCountByte").run(); }
    @Test public void emptySymbolMap() throws Exception { AlzHuffmanBoundaryChecks.checks().get("emptySymbolMap").run(); }
    @Test public void emptySymbolMapAfterPriorBlock() throws Exception { AlzHuffmanBoundaryChecks.checks().get("emptySymbolMapAfterPriorBlock").run(); }
    @Test public void origPtrUsesCurrentBlock() throws Exception { AlzHuffmanBoundaryChecks.checks().get("origPtrUsesCurrentBlock").run(); }
    @Test public void positiveShrinkingBlocks() throws Exception { AlzHuffmanBoundaryChecks.checks().get("positiveShrinkingBlocks").run(); }
    @Test public void laterHuffmanFailureIsSticky() throws Exception { AlzHuffmanBoundaryChecks.checks().get("laterHuffmanFailureIsSticky").run(); }
    @Test public void laterSelectorFailureIsSticky() throws Exception { AlzHuffmanBoundaryChecks.checks().get("laterSelectorFailureIsSticky").run(); }
    @Test public void laterRleFailureIsSticky() throws Exception { AlzHuffmanBoundaryChecks.checks().get("laterRleFailureIsSticky").run(); }
    @Test public void cancellationIsSticky() throws Exception { AlzHuffmanBoundaryChecks.checks().get("cancellationIsSticky").run(); }
    @Test public void invalidBitSourceCounts() throws Exception { AlzHuffmanBoundaryChecks.checks().get("invalidBitSourceCounts").run(); }
    @Test public void closedStreamAndInvalidArguments() throws Exception { AlzHuffmanBoundaryChecks.checks().get("closedStreamAndInvalidArguments").run(); }
    @Test public void mixedScalarAndBulkExistingFixture() throws Exception { AlzHuffmanBoundaryChecks.checks().get("mixedScalarAndBulkExistingFixture").run(); }
}
