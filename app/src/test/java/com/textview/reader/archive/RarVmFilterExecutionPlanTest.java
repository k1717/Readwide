package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class RarVmFilterExecutionPlanTest {
    @Test
    public void buildsImmutablePlanFromParsedStandaloneFilter() throws Exception {
        RarVmFilterExecutionPlan plan = RarVmFilterExecutionPlan
                .fromParsedFilter(filterAt(10, RarVmFilter.StandardFilter.E8))
                .outputLength(5)
                .fileOffset(123)
                .build();

        assertEquals(10, plan.outputOffset);
        assertEquals(5, plan.outputLength);
        assertEquals(15, plan.endOffsetExclusive());
        assertEquals(123, plan.fileOffset);
        assertTrue(plan.hasStandalonePrimitive());
        assertTrue(plan.diagnosticSummary().contains("standardFilter=E8"));
    }

    @Test(expected = IOException.class)
    public void rejectsMissingOutputLength() throws Exception {
        RarVmFilterExecutionPlan.fromParsedFilter(filterAt(0, RarVmFilter.StandardFilter.E8))
                .fileOffset(0)
                .build();
    }

    @Test(expected = IOException.class)
    public void rejectsMissingFileOffset() throws Exception {
        RarVmFilterExecutionPlan.fromParsedFilter(filterAt(0, RarVmFilter.StandardFilter.E8))
                .outputLength(5)
                .build();
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void rejectsOversizedRangeBeforeQueueing() throws Exception {
        RarVmFilterExecutionPlan.fromParsedFilter(filterAt(0, RarVmFilter.StandardFilter.E8))
                .outputLength(6)
                .fileOffset(0)
                .maxRangeBytes(5)
                .build();
    }

    @Test(expected = RarArchiveReader.UnsupportedRarFeatureException.class)
    public void rejectsUnsupportedStandardFilter() throws Exception {
        RarVmFilterExecutionPlan.fromParsedFilter(filterAt(0, RarVmFilter.StandardFilter.RGB))
                .outputLength(5)
                .fileOffset(0)
                .build();
    }

    @Test(expected = IOException.class)
    public void planSequenceRejectsOverlappingFilters() throws Exception {
        RarVmFilterExecutionPlan first = RarVmFilterExecutionPlan
                .fromParsedFilter(filterAt(10, RarVmFilter.StandardFilter.E8))
                .outputLength(6)
                .fileOffset(0)
                .build();
        RarVmFilterExecutionPlan second = RarVmFilterExecutionPlan
                .fromParsedFilter(filterAt(15, RarVmFilter.StandardFilter.E8E9))
                .outputLength(5)
                .fileOffset(6)
                .build();

        RarVmFilterExecutionPlan.toQueue(Arrays.asList(first, second));
    }

    @Test(expected = IOException.class)
    public void rejectsPlanTargetingAlreadyEmittedOutput() throws Exception {
        RarVmFilterExecutionPlan plan = RarVmFilterExecutionPlan
                .fromParsedFilter(filterAt(1, RarVmFilter.StandardFilter.E8))
                .outputLength(5)
                .fileOffset(0)
                .build();

        plan.validateTargetNotEmitted(2);
    }

    @Test
    public void pipelineQueuesExecutionPlanWithoutLiveDecoderWiring() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredOutputBuffer output = new RarFilteredOutputBuffer(out);
        RarVmFilterPipeline pipeline = new RarVmFilterPipeline(output);
        RarVmFilterExecutionPlan plan = RarVmFilterExecutionPlan
                .fromParsedFilter(filterAt(0, RarVmFilter.StandardFilter.E8))
                .outputLength(5)
                .fileOffset(0)
                .build();

        pipeline.queueExecutionPlan(plan);
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
