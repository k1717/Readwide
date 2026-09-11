package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ArchiveSourceSnapshotTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void unchangedRarChainMatches() throws Exception {
        File first = part("comic.part1.rar");
        part("comic.part2.rar");
        assertTrue(snapshot(first).matches(first));
    }

    @Test
    public void laterRarLengthChangeRejectsHandoff() throws Exception {
        File first = part("comic.part1.rar");
        File second = part("comic.part2.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        long modified = second.lastModified();
        Files.write(second.toPath(), new byte[] {1, 2, 3, 4});
        assertTrue(second.setLastModified(modified));
        assertFalse(saved.matches(first));
    }

    @Test
    public void laterRarTimestampChangeRejectsHandoff() throws Exception {
        File first = part("comic.part1.rar");
        File second = part("comic.part2.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        assertTrue(second.setLastModified(1700000010000L));
        assertFalse(saved.matches(first));
    }

    @Test
    public void appendedRarVolumeRejectsHandoff() throws Exception {
        File first = part("comic.part1.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        part("comic.part2.rar");
        assertFalse(saved.matches(first));
    }

    @Test
    public void removedRarVolumeRejectsHandoff() throws Exception {
        File first = part("comic.part1.rar");
        File second = part("comic.part2.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        Files.delete(second.toPath());
        assertFalse(saved.matches(first));
    }

    @Test
    public void oldStyleRarTracksContinuationVolumes() throws Exception {
        File first = part("comic.rar");
        File second = part("comic.r00");
        ArchiveSourceSnapshot saved = snapshot(first);
        assertTrue(saved.matches(first));
        Files.write(second.toPath(), new byte[] {9});
        assertFalse(saved.matches(first));
    }

    @Test
    public void laterSelectedSevenZPartStillTracksFirstVolume() throws Exception {
        File first = part("comic.7z.001");
        File second = part("comic.7z.002");
        ArchiveSourceSnapshot saved = snapshot(second);
        assertTrue(saved.matches(second));
        Files.write(first.toPath(), new byte[] {9});
        assertFalse(saved.matches(second));
    }

    @Test
    public void laterSevenZVolumeChangeRejectsHandoff() throws Exception {
        File first = part("comic.cb7.001");
        File second = part("comic.cb7.002");
        ArchiveSourceSnapshot saved = snapshot(first);
        Files.write(second.toPath(), new byte[] {9});
        assertFalse(saved.matches(first));
    }

    @Test
    public void unresolvedSevenZCaptureNeverBecomesValidAfterRepair() throws Exception {
        File first = part("comic.7z.001");
        part("comic.7z.003");
        ArchiveSourceSnapshot saved = snapshot(first);
        assertFalse(saved.matches(first));
        part("comic.7z.002");
        assertFalse(saved.matches(first));
        assertTrue(snapshot(first).matches(first));
    }

    @Test
    public void missingSelectedFileCaptureFailsClosed() throws Exception {
        File first = new File(temp.getRoot(), "comic.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        part("comic.rar");
        assertFalse(saved.matches(first));
    }

    @Test
    public void unrelatedSiblingDoesNotInvalidateOrAliasAnotherArchive() throws Exception {
        File first = part("comic.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        File other = part("different.rar");
        assertTrue(saved.matches(first));
        assertFalse(saved.matches(other));
    }

    @Test
    public void otherFormatsRetainExistingSingleFileGuard() throws Exception {
        assertNull(ArchiveSourceSnapshot.capture(part("comic.zip")));
        assertNull(ArchiveSourceSnapshot.capture(part("comic.egg")));
        assertNull(ArchiveSourceSnapshot.capture(part("comic.7z")));
        assertNull(ArchiveSourceSnapshot.capture(part("comic.rar.001")));
    }

    @Test
    public void sevenZGapScanIgnoresUnrelatedFilesAndDirectories() throws Exception {
        File first = part("comic.7z.001");
        part("different.7z.003");
        part("comic.7z.0003");
        temp.newFolder("comic.7z.004");
        assertTrue(snapshot(first).matches(first));
    }

    @Test
    public void sevenZGapAtLastSupportedOrdinalIsDetected() throws Exception {
        File first = part("comic.7z.001");
        ArchiveSourceSnapshot saved = snapshot(first);
        part("comic.7z.999");
        assertFalse(saved.matches(first));
        assertFalse(snapshot(first).matches(first));
    }

    private File part(String name) throws Exception {
        File file = temp.newFile(name);
        Files.write(file.toPath(), new byte[] {1, 2, 3});
        assertTrue(file.setLastModified(1700000000000L));
        return file;
    }

    private ArchiveSourceSnapshot snapshot(File file) {
        ArchiveSourceSnapshot saved = ArchiveSourceSnapshot.capture(file);
        assertNotNull(saved);
        return saved;
    }
}
