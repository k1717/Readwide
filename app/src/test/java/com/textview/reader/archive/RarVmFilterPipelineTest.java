package com.textview.reader.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class RarVmFilterPipelineTest {
    @Test
    public void queuesStandaloneFilterIntoDelayedOutputBuffer() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RarFilteredOutputBuffer output = new RarFilteredOutputBuffer(out);
        RarVmFilterPipeline pipeline = new RarVmFilterPipeline(output);

        pipeline.queueStandaloneFilter(filterAt(0, RarVmFilter.StandardFilter.E8), 5, 0);
        output.write(new byte[] {(byte) 0xe8, 0x20, 0x00, 0x00, 0x00});
        output.finish();

        assertArrayEquals(new byte[] {(byte) 0xe8, 0x1b, 0x00, 0x00, 0x00}, out.toByteArray());
    }

    @Test
    public void nonStandaloneFilterRemainsPreciseFirstPartyGap() throws Exception {
        RarFilteredOutputBuffer output = new RarFilteredOutputBuffer(new ByteArrayOutputStream());
        RarVmFilterPipeline pipeline = new RarVmFilterPipeline(output);

        try {
            pipeline.queueStandaloneFilter(filterAt(0, RarVmFilter.StandardFilter.DELTA), 5, 0);
        } catch (RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("VM filters"));
            assertTrue(expected.getMessage().contains("Delta"));
            return;
        }
        throw new AssertionError("Delta VM filter must remain a precise first-party gap");
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
