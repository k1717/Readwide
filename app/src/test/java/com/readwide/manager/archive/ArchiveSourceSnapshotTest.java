package com.readwide.manager.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ArchiveSourceSnapshotTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void workerStampCheckDetectsLaterVolumeReplacementAndRemoval() throws Exception {
        File first = part("comic.part1.rar"), second = part("comic.part2.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        assertTrue(saved.matchesKnownFiles(first));
        long timestamp = second.lastModified();
        Files.write(second.toPath(), new byte[]{4, 3, 2, 1});
        assertTrue(second.setLastModified(timestamp));
        assertFalse(saved.matchesKnownFiles(first));
        ArchiveSourceSnapshot replacement = snapshot(first);
        assertTrue(replacement.matchesKnownFiles(first));
        assertTrue(second.delete());
        assertFalse(replacement.matchesKnownFiles(first));
    }

    @Test public void workerStampCheckRejectsAnotherSourceAndFailedCapture() throws Exception {
        File first = part("one.zip"), other = part("two.zip");
        assertFalse(snapshot(first).matchesKnownFiles(other));
        assertFalse(snapshot(new File(temp.getRoot(), "missing.zip")).matchesKnownFiles(first));
    }

    @Test public void workerStampCheckPreservesInterruption() throws Exception {
        File archive = part("one.zip");
        ArchiveSourceSnapshot saved = snapshot(archive);
        Thread.currentThread().interrupt();
        try {
            assertFalse(saved.matchesKnownFiles(archive));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void fullBoundaryCheckDetectsNewChainMembers() throws Exception {
        File first = part("comic.part1.rar");
        ArchiveSourceSnapshot saved = snapshot(first);
        part("comic.part2.rar");
        assertTrue(saved.matchesKnownFiles(first)); // cheap per-page check only knows prior members
        assertFalse(saved.matches(first)); // resume and thumbnail checks re-resolve membership
    }

    @Test public void checkedCancellationDuringStampCaptureRestoresInterrupt() throws Exception {
        File source = temp.newFile("stamp.dat");
        File interrupted = new File(source.getPath()) {
            @Override public String getCanonicalPath() throws java.io.IOException {
                throw new java.io.InterruptedIOException("stamp cancelled");
            }
        };
        ArchiveSourceSnapshot captured;
        try {
            captured = ArchiveSourceSnapshot.capture(interrupted);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertFalse(captured.matches(source));
    }

    @Test public void interruptionInFinalStampCannotPublishReusableIdentity() throws Exception {
        File source = temp.newFile("stamp.dat");
        File interrupted = new File(source.getPath()) {
            private int stamps;
            @Override public long lastModified() {
                long modified = super.lastModified();
                if (++stamps == 2) Thread.currentThread().interrupt();
                return modified;
            }
        };
        ArchiveSourceSnapshot captured;
        try {
            captured = ArchiveSourceSnapshot.capture(interrupted);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertFalse(captured.matches(source));
    }

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
    public void singleFilesAndGenericNumericSplitsNowHaveSourceSnapshots() throws Exception {
        File zip = part("comic.zip");
        assertTrue(snapshot(zip).matches(zip));
        File egg = part("comic.egg");
        // EGG cache identity now validates its volume header rather than stamping arbitrary bytes.
        Files.write(egg.toPath(), new byte[] {0x45,0x47,0x47,0x41,0,1,1,0,0,0,0,0,0,0,0x22,(byte)0x82,(byte)0xe2,8});
        assertTrue(snapshot(egg).matches(egg));
        File sevenZ = part("comic.7z");
        assertTrue(snapshot(sevenZ).matches(sevenZ));
        File numeric = part("comic.rar.001");
        assertTrue(snapshot(numeric).matches(numeric));
    }

    @Test
    public void sevenZGapScanRejectsDirectoryMasqueradingAsVolume() throws Exception {
        File first = part("comic.7z.001");
        part("different.7z.003");
        part("comic.7z.0003");
        temp.newFolder("comic.7z.004");
        assertFalse(snapshot(first).matches(first));
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

    @Test public void replacementProtectionUsesAncestorsNotStringPrefixes() throws Exception {
        File directory = temp.newFolder("book-old");
        File archive = new File(directory, "comic.zip"); Files.write(archive.toPath(), new byte[]{1});
        File sibling = temp.newFolder("book");
        ArchiveSourceSnapshot saved = snapshot(archive);
        saved.requireOutsideReplacementTree(sibling);
        assertThrows(java.io.IOException.class, () -> saved.requireOutsideReplacementTree(directory));
        assertThrows(java.io.IOException.class, () -> saved.requireOutsideReplacementTree(temp.getRoot()));
        assertThrows(java.io.IOException.class, () -> saved.requireOutsideReplacementTree(archive));
    }

    @Test public void replacementProtectionNormalizesDotDotAliases() throws Exception {
        File directory = temp.newFolder("archive-root");
        File child = new File(directory, "child"); assertTrue(child.mkdir());
        File archive = new File(directory, "comic.zip"); Files.write(archive.toPath(), new byte[]{1});
        ArchiveSourceSnapshot saved = snapshot(archive);
        assertThrows(java.io.IOException.class,
                () -> saved.requireOutsideReplacementTree(new File(child, "..")));
    }

    @Test public void unresolvedSnapshotCannotApproveAReplacementAndCancellationIsRetained() throws Exception {
        File missing = new File(temp.getRoot(), "missing.zip");
        ArchiveSourceSnapshot unresolved = snapshot(missing);
        assertThrows(java.io.IOException.class, () -> unresolved.requireOutsideReplacementTree(temp.getRoot()));
        File archive = part("comic.zip"), outside = temp.newFolder("outside");
        ArchiveSourceSnapshot saved = snapshot(archive);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> saved.requireOutsideReplacementTree(outside));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private ArchiveSourceSnapshot snapshot(File file) {
        ArchiveSourceSnapshot saved = ArchiveSourceSnapshot.capture(file);
        assertNotNull(saved);
        return saved;
    }
}
