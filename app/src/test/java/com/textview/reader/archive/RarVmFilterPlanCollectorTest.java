package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class RarVmFilterPlanCollectorTest {
    @Test
    public void collectorBuildsPlanWhenVmExecutionStateIsProvided() throws Exception {
        RarVmFilterPlanCollector collector = new RarVmFilterPlanCollector();
        RarVmFilterExecutionState state = RarVmFilterExecutionState
                .knownStandardFilterRange(5, 123);

        RarVmFilterExecutionPlan plan = collector.addParsedFilter(
                filterAt(10, RarVmFilter.StandardFilter.E8), state);

        assertEquals(1, collector.size());
        assertFalse(collector.isEmpty());
        assertEquals(10, plan.outputOffset);
        assertEquals(5, plan.outputLength);
        assertEquals(123, plan.fileOffset);
        assertTrue(plan.hasStandalonePrimitive());
        assertEquals(1, collector.plans().size());
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void collectorFailsCleanlyWhenVmExecutionStateIsMissing() throws Exception {
        RarVmFilterPlanCollector collector = new RarVmFilterPlanCollector();
        collector.addParsedFilterWithoutDecodedVmState(
                filterAt(0, RarVmFilter.StandardFilter.E8));
    }

    @Test(expected = IOException.class)
    public void collectorRejectsOverlappingPlansBeforeQueueing() throws Exception {
        RarVmFilterPlanCollector collector = new RarVmFilterPlanCollector();
        collector.addParsedFilter(
                filterAt(10, RarVmFilter.StandardFilter.E8),
                RarVmFilterExecutionState.knownStandardFilterRange(6, 0));
        collector.addParsedFilter(
                filterAt(15, RarVmFilter.StandardFilter.E8E9),
                RarVmFilterExecutionState.knownStandardFilterRange(5, 6));
    }

    @Test(expected = IOException.class)
    public void collectorRejectsTargetAlreadyEmitted() throws Exception {
        RarVmFilterPlanCollector collector = new RarVmFilterPlanCollector(8);
        collector.addParsedFilter(
                filterAt(7, RarVmFilter.StandardFilter.E8),
                RarVmFilterExecutionState.knownStandardFilterRange(5, 0));
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void executionStateRangeLimitIsAppliedBeforePlanCollection() throws Exception {
        RarVmFilterPlanCollector collector = new RarVmFilterPlanCollector();
        collector.addParsedFilter(
                filterAt(0, RarVmFilter.StandardFilter.E8),
                RarVmFilterExecutionState.knownStandardFilterRange(6, 0, 5));
    }

    @Test(expected = IOException.class)
    public void executionStateRejectsInvalidLength() throws Exception {
        RarVmFilterExecutionState.knownStandardFilterRange(0, 0);
    }

    @Test
    public void collectorQueuesPlansIntoPipelineWithoutLiveDecoderWiring() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredOutputBuffer output = new RarFilteredOutputBuffer(out);
        RarVmFilterPipeline pipeline = new RarVmFilterPipeline(output);
        RarVmFilterPlanCollector collector = new RarVmFilterPlanCollector();

        collector.addParsedFilter(
                filterAt(0, RarVmFilter.StandardFilter.E8),
                RarVmFilterExecutionState.knownStandardFilterRange(5, 0));
        collector.queueInto(pipeline);

        output.write(new byte[] {(byte) 0xe8, 0x20, 0x00, 0x00, 0x00});
        output.finish();

        assertEquals(5, out.size());
        assertEquals(0x1b, out.toByteArray()[1] & 0xff);
    }

    private static RarVmFilter filterAt(long outputOffset,
                                        RarVmFilter.StandardFilter standardFilter) {
        return new RarVmFilter(
                outputOffset,
                0,
                0,
                RarVmFilter.LengthEncoding.INLINE,
                new byte[] {1},
                0,
                standardFilter);
    }
}