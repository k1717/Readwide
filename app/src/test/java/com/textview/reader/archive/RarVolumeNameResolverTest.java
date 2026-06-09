package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class RarVolumeNameResolverTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void resolve_newStyleLaterVolumeRoutesBackToPartOne() throws Exception {
        File part1 = touch("book.part01.rar");
        File part2 = touch("book.part02.rar");
        File part3 = touch("book.part03.rar");

        RarVolumeNameResolver.Result result = RarVolumeNameResolver.resolve(part2);

        assertEquals(RarVolumeNameResolver.Style.NEW_STYLE_PART, result.style());
        assertEquals(part1, result.firstVolume());
        assertEquals(2, result.selectedPartIndex());
        assertTrue(result.selectedLaterVolume());
        assertEquals(3, result.volumes().size());
        assertEquals(part3, result.volumes().get(2));
        assertEquals(4, result.nextMissingPartIndex());
        assertEquals(3, result.maxSeenPartIndex());
        assertFalse(result.hasKnownGap());
    }

    @Test
    public void resolve_newStyleAcceptsUnpaddedPartNumbers() throws Exception {
        File part1 = touch("comic.part1.rar");
        File part2 = touch("comic.part2.rar");

        RarVolumeNameResolver.Result result = RarVolumeNameResolver.resolve(part1);

        assertEquals(RarVolumeNameResolver.Style.NEW_STYLE_PART, result.style());
        assertFalse(result.selectedLaterVolume());
        assertEquals(2, result.volumes().size());
        assertEquals(part2, result.volumes().get(1));
    }

    @Test
    public void resolve_newStyleStopsAtMissingVolume() throws Exception {
        File part1 = touch("gap.part001.rar");
        touch("gap.part003.rar");

        RarVolumeNameResolver.Result result = RarVolumeNameResolver.resolve(part1);

        assertEquals(1, result.volumes().size());
        assertEquals(2, result.nextMissingPartIndex());
        assertEquals(3, result.maxSeenPartIndex());
        assertTrue(result.hasKnownGap());
    }

    @Test
    public void resolve_oldStyleLaterVolumeRoutesBackToBaseRar() throws Exception {
        File first = touch("legacy.rar");
        File r00 = touch("legacy.r00");
        File r01 = touch("legacy.r01");

        RarVolumeNameResolver.Result result = RarVolumeNameResolver.resolve(r01);

        assertEquals(RarVolumeNameResolver.Style.OLD_STYLE_RAR_PLUS_RNN, result.style());
        assertEquals(first, result.firstVolume());
        assertTrue(result.selectedLaterVolume());
        assertEquals(1, result.selectedPartIndex());
        assertEquals(3, result.volumes().size());
        assertEquals(r00, result.volumes().get(1));
        assertEquals(r01, result.volumes().get(2));
    }

    @Test
    public void resolve_baseRarFindsOldStyleCompanions() throws Exception {
        File first = touch("movie.rar");
        touch("movie.r00");
        touch("movie.r01");

        RarVolumeNameResolver.Result result = RarVolumeNameResolver.resolve(first);

        assertEquals(RarVolumeNameResolver.Style.BASE_RAR_WITH_OLD_STYLE_COMPANIONS, result.style());
        assertEquals(3, result.volumes().size());
        assertFalse(result.selectedLaterVolume());
    }

    @Test
    public void locatorCollectVolumesUsesResolverForLaterNewStyleVolume() throws Exception {
        File part1 = touch("novel.part1.rar");
        File part2 = touch("novel.part2.rar");

        List<File> volumes = RarArchiveLocator.collectVolumes(part2);

        assertEquals(2, volumes.size());
        assertEquals(part1, volumes.get(0));
        assertEquals(part2, volumes.get(1));
    }

    private File touch(String name) throws Exception {
        return tempFolder.newFile(name);
    }
}
