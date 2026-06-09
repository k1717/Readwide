package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class RarVolumeChainResolutionTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void requireReadableChain_routesLaterNewStyleVolumeToFirstVolume() throws Exception {
        File part1 = touch("book.part01.rar");
        File part2 = touch("book.part02.rar");

        RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(part2);
        List<File> volumes = resolution.requireReadableChain();

        assertTrue(resolution.selectedLaterVolume());
        assertTrue(resolution.selectedInContiguousChain());
        assertEquals(part1, resolution.firstVolume());
        assertEquals(2, volumes.size());
        assertEquals(part1, volumes.get(0));
        assertEquals(part2, volumes.get(1));
    }

    @Test
    public void requireReadableChain_rejectsLaterNewStyleVolumeWhenGapPrecedesIt() throws Exception {
        touch("book.part01.rar");
        File part3 = touch("book.part03.rar");

        RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(part3);

        assertTrue(resolution.selectedLaterVolume());
        assertTrue(resolution.knownGapBeforeSelected());
        assertTrue(resolution.knownGapInDiscoveredChain());
        try {
            resolution.requireReadableChain();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
            assertTrue(expected.getMessage().contains("nextMissingPartIndex=2"));
            return;
        }
        throw new AssertionError("later volume with missing earlier part must fail cleanly");
    }

    @Test
    public void requireReadableChain_rejectsOldStyleLaterVolumeWhenR00IsMissing() throws Exception {
        touch("legacy.rar");
        File r01 = touch("legacy.r01");

        RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(r01);

        assertEquals(RarVolumeNameResolver.Style.OLD_STYLE_RAR_PLUS_RNN, resolution.style());
        assertTrue(resolution.knownGapBeforeSelected());
        try {
            resolution.requireReadableChain();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("nextMissingPartIndex=0"));
            return;
        }
        throw new AssertionError("old-style later volume with missing r00 must fail cleanly");
    }

    @Test
    public void requireReadableChain_rejectsOrphanNewStyleLaterVolume() throws Exception {
        File part4 = touch("orphan.part004.rar");

        RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(part4);

        assertEquals(RarVolumeNameResolver.Style.NEW_STYLE_PART, resolution.style());
        assertEquals("orphan.part001.rar", resolution.firstVolume().getName());
        assertTrue(resolution.knownGapBeforeSelected());
        try {
            resolution.requireReadableChain();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("selectedInContiguousChain=false"));
            return;
        }
        throw new AssertionError("orphan later part must fail cleanly");
    }

    @Test
    public void requireReadableChain_rejectsOrphanOldStyleLaterVolume() throws Exception {
        File r01 = touch("orphan.r01");

        RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(r01);

        assertEquals(RarVolumeNameResolver.Style.OLD_STYLE_RAR_PLUS_RNN, resolution.style());
        assertEquals("orphan.rar", resolution.firstVolume().getName());
        assertTrue(resolution.knownGapBeforeSelected());
        try {
            resolution.requireReadableChain();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("nextMissingPartIndex=0"));
            return;
        }
        throw new AssertionError("orphan old-style later part must fail cleanly");
    }

    @Test
    public void collectReadableVolumes_rejectsKnownGapInsteadOfSilentlyReturningPartOneOnly() throws Exception {
        File part1 = touch("gap.part001.rar");
        touch("gap.part003.rar");

        try {
            RarArchiveLocator.collectReadableVolumes(part1);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("knownGapInDiscoveredChain=true"));
            return;
        }
        throw new AssertionError("known split gap must not be silently treated as a single-volume archive");
    }

    @Test
    public void collectVolumes_remainsLenientForSignatureDetection() throws Exception {
        File part1 = touch("gap.part001.rar");
        touch("gap.part003.rar");

        List<File> volumes = RarArchiveLocator.collectVolumes(part1);

        assertEquals(1, volumes.size());
        assertEquals(part1, volumes.get(0));
    }

    @Test
    public void resolution_doesNotFlagCompleteChainAsGapJustBecauseNextPartIsAbsent() throws Exception {
        File part1 = touch("ok.part1.rar");
        File part2 = touch("ok.part2.rar");

        RarVolumeChainResolution resolution = RarArchiveLocator.resolveVolumeChain(part2);

        assertFalse(resolution.knownGapInDiscoveredChain());
        assertEquals(3, resolution.nextMissingPartIndex());
        assertEquals(2, resolution.maxSeenPartIndex());
    }

    private File touch(String name) throws Exception {
        return tempFolder.newFile(name);
    }
}
