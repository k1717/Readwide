package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdRescalePolicyTest {
    @Test
    public void policyRescalesContextAndReportsThroughUpdater() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64);
        RarPpmdContext context = new RarPpmdContext();
        context.attachContextPointer(allocator.allocUnits(1));
        context.allocateStateArray(allocator);
        context.insertOrUpdateState('A', 3, RarPpmdStateRecord.NO_SUCCESSOR);
        context.insertOrUpdateState('B', 3, RarPpmdStateRecord.NO_SUCCESSOR);

        assertTrue(context.rescaleIfNeeded(RarPpmdRescalePolicy.forTest(6)));
        assertEquals(2, context.findState('A').frequency());
        assertEquals(2, context.findState('B').frequency());
    }

    @Test
    public void updaterDiagnosticIncludesRescaleCount() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64);
        RarPpmdContextChain chain = new RarPpmdContextChain(allocator);
        RarPpmdModelUpdater updater = new RarPpmdModelUpdater(1, allocator, chain);
        RarPpmdModelUpdater.UpdateResult result = null;
        for (int i = 0; i < RarPpmdRescalePolicy.diagnosticDefault().maxScaleBeforeRescaleForTest(); i++) {
            result = updater.learnAfterRootOnlyDecode('R');
        }

        assertTrue(result.rescaledContextCount > 0);
        assertTrue(result.diagnostic().contains("rescaledContextCount="));
    }
}
