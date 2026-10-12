package com.readwide.manager.archive;

import org.junit.Test;

/** Same decoder checks executed by scripts/check-alz-selector-validation.py. */
public final class AlzSelectorBoundaryTest {
    @Test public void existingSingleBlockStillDecodes() throws Exception {
        AlzSelectorBoundaryChecks.existingSingleBlockStillDecodes();
    }
    @Test public void existingThreeBlocksStillDecode() throws Exception {
        AlzSelectorBoundaryChecks.existingThreeBlocksStillDecode();
    }
    @Test public void everyActiveSelectorIsAccepted() throws Exception {
        AlzSelectorBoundaryChecks.everyActiveSelectorIsAccepted();
    }
    @Test public void validUpperSelectorSurvivesShortReads() throws Exception {
        AlzSelectorBoundaryChecks.validUpperSelectorSurvivesShortReads();
    }
    @Test public void wrappedSelectorsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.wrappedSelectorsAreRejected();
    }
    @Test public void realFixtureWrapMutationsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.realFixtureWrapMutationsAreRejected();
    }
    @Test public void inactiveSelectorsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.inactiveSelectorsAreRejected();
    }
    @Test public void zeroSelectorsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.zeroSelectorsAreRejected();
    }
    @Test public void zeroGroupsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.zeroGroupsAreRejected();
    }
    @Test public void oneGroupIsRejected() throws Exception {
        AlzSelectorBoundaryChecks.oneGroupIsRejected();
    }
    @Test public void sevenGroupsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.sevenGroupsAreRejected();
    }
    @Test public void validExcessSelectorsRemainCompatible() throws Exception {
        AlzSelectorBoundaryChecks.validExcessSelectorsRemainCompatible();
    }
    @Test public void invalidDiscardedSelectorsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.invalidDiscardedSelectorsAreRejected();
    }
    @Test public void unterminatedUnaryFailsAtActiveBound() throws Exception {
        AlzSelectorBoundaryChecks.unterminatedUnaryFailsAtActiveBound();
    }
    @Test public void multipleBlocksResetSelectorTables() throws Exception {
        AlzSelectorBoundaryChecks.multipleBlocksResetSelectorTables();
    }
    @Test public void laterBlockFailureRemainsSticky() throws Exception {
        AlzSelectorBoundaryChecks.laterBlockFailureRemainsSticky();
    }
    @Test public void laterBlockZeroSelectorsAreRejected() throws Exception {
        AlzSelectorBoundaryChecks.laterBlockZeroSelectorsAreRejected();
    }
    @Test public void truncatedSelectorListIsRejected() throws Exception {
        AlzSelectorBoundaryChecks.truncatedSelectorListIsRejected();
    }
    @Test public void emptyStreamRemainsValid() throws Exception {
        AlzSelectorBoundaryChecks.emptyStreamRemainsValid();
    }
}
