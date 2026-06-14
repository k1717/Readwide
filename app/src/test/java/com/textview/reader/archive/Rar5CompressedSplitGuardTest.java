package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

public class Rar5CompressedSplitGuardTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void firstPartyRar5CompressedExtractorDeletesOutputWhenSplitPayloadIsMalformed() throws Exception {
        RarArchiveReader.RarEntry first = rar5CompressedSplit(false, true);
        RarArchiveReader.RarEntry last = rar5CompressedSplit(true, false);
        first.sourceArchive = tempFolder.newFile("sample.part1.rar");
        last.sourceArchive = tempFolder.newFile("sample.part2.rar");

        File out = new File(tempFolder.getRoot(), "out.bin");

        try {
            Rar5CompressedArchiveExtractor.tryExtractEntry(
                    first,
                    Arrays.asList(first, last),
                    out,
                    null,
                    null);
        } catch (java.io.IOException expected) {
            assertFalse("Split compressed fallback must not create partial output", out.exists());
            return;
        }
        throw new AssertionError("Malformed compressed split payload must fail");
    }

    @Test
    public void rar5CompressedFallbackFailureNamesSplitBoundary() {
        RarArchiveReader.RarEntry first = rar5CompressedSplit(false, true);
        RarArchiveReader.RarEntry last = rar5CompressedSplit(true, false);

        RarArchiveReader.UnsupportedRarFeatureException failure =
                RarFeatureClassifier.rar5CompressedFallbackFailure(
                        Arrays.asList(first, last), null);

        assertTrue(failure.getMessage().contains("RAR5 compressed split/multi-volume"));
        assertTrue(failure.getMessage().contains("could not be completed"));
        assertTrue(failure.getMessage().contains("never routed through the stored-entry fallback"));
    }

    private static RarArchiveReader.RarEntry rar5CompressedSplit(boolean splitBefore, boolean splitAfter) {
        return new RarArchiveReader.RarEntry(
                "image.bin",
                false,
                8L,
                4L,
                0L,
                5,
                1,
                false,
                splitBefore,
                splitAfter,
                null,
                0L,
                0L,
                0L);
    }
}
