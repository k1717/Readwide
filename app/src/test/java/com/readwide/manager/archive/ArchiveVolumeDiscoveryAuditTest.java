package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Controlled directory snapshots; no decoder execution or producer-coverage claim. */
public class ArchiveVolumeDiscoveryAuditTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void numericSelectionMissingFromSnapshotCannotOpenAShorterChain() throws Exception {
        for (String extension : new String[]{"7z", "zip"}) {
            File directory = temp.newFolder();
            File first = file(directory, "book." + extension + ".001");
            File second = file(directory, "book." + extension + ".002");
            File selected = snapshotSelection(second, first);
            IOException failure = assertThrows(IOException.class,
                    () -> SevenZSplitVolumeResolver.resolveNumeric(selected));
            assertTrue(failure.getMessage().contains("outside the discovered set"));
        }
    }

    @Test public void numericOrdinalAloneCannotSubstituteAnotherPhysicalFile() throws Exception {
        File first = file(temp.getRoot(), "book.7z.001");
        File other = file(temp.getRoot(), "other.bin");
        File selected = snapshotSelection(first, named(other, first.getName()));
        assertThrows(IOException.class, () -> SevenZSplitVolumeResolver.resolveNumeric(selected));
    }

    @Test public void unorderedNumericCatalogueStillReturnsTheSelectedChain() throws Exception {
        File first = file(temp.getRoot(), "book.cb7.001");
        File second = file(temp.getRoot(), "book.cb7.002");
        File selected = snapshotSelection(second, second, first);
        assertEquals(Arrays.asList(first, second), SevenZSplitVolumeResolver.resolve(selected).parts);
    }

    @Test public void firstNumberedRarSelectionMustBelongToItsResolvedChain() throws Exception {
        File first = file(temp.getRoot(), "book.part1.rar");
        File other = file(temp.getRoot(), "other.bin");
        File selected = snapshotSelection(first, named(other, first.getName()));
        RarVolumeChainResolution resolution = RarVolumeChainResolution.resolve(selected);
        assertFalse(resolution.selectedLaterVolume());
        assertFalse(resolution.selectedInContiguousChain());
        assertThrows(IOException.class, resolution::requireReadableChain);
    }

    @Test public void baseRarSelectionMustBelongEvenWhenContinuationsExist() throws Exception {
        File first = file(temp.getRoot(), "book.rar");
        File tail = file(temp.getRoot(), "book.r00");
        File other = file(temp.getRoot(), "other.bin");
        File selected = snapshotSelection(first, tail, named(other, first.getName()));
        RarVolumeChainResolution resolution = RarVolumeChainResolution.resolve(selected);
        assertFalse(resolution.selectedLaterVolume());
        assertFalse(resolution.selectedInContiguousChain());
        assertThrows(IOException.class, resolution::requireReadableChain);
    }

    @Test public void validFirstRarSelectionKeepsItsOrdinaryChain() throws Exception {
        File first = file(temp.getRoot(), "book.part1.rar");
        File second = file(temp.getRoot(), "book.part2.rar");
        assertEquals(Arrays.asList(first, second), RarArchiveLocator.collectReadableVolumes(
                snapshotSelection(first, second, first)));
    }

    @Test public void alzSelectedCanonicalPathIsCapturedOnlyOnce() throws Exception {
        File first = file(temp.getRoot(), "book.alz");
        File second = file(temp.getRoot(), "book.a00");
        File third = file(temp.getRoot(), "book.a01");
        int[] canonicalCalls = {0};
        File selected = new SnapshotSelection(third, new File[]{third, first, second}) {
            @Override public String getCanonicalPath() throws IOException {
                canonicalCalls[0]++;
                return super.getCanonicalPath();
            }
        };
        assertEquals(first, AlzVolumeResolver.resolveFirstVolume(selected));
        assertEquals(1, canonicalCalls[0]);
    }

    @Test public void alzCancellationDuringMembershipChecksCannotReturnFirstVolume() throws Exception {
        File first = file(temp.getRoot(), "book.alz");
        File second = file(temp.getRoot(), "book.a00");
        File interruptedFirst = new File(first.getPath()) {
            @Override public String getCanonicalPath() throws IOException {
                Thread.currentThread().interrupt();
                return super.getCanonicalPath();
            }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> AlzVolumeResolver.resolveFirstVolume(
                    snapshotSelection(second, interruptedFirst, second)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void eggCancellationDuringFinalMembershipCheckCannotReturnASet() throws Exception {
        File file = file(temp.getRoot(), "book.egg");
        Files.write(file.toPath(), Round4Fixtures.prefix(1, 0, 0, false));
        File selected = new File(file.getPath()) {
            @Override public String getCanonicalPath() throws IOException {
                Thread.currentThread().interrupt();
                return super.getCanonicalPath();
            }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> EggVolumeResolver.resolve(selected));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void eggHeaderOrderDoesNotDependOnCatalogueInsertionOrder() throws Exception {
        List<File> parts = Round4Fixtures.splitEgg(temp.newFolder(), "book",
                Round4Fixtures.egg(Round4Fixtures.data(6023), 0, false), 3);
        File selected = snapshotSelection(parts.get(1), parts.get(2), parts.get(0), parts.get(1));
        assertEquals(parts, EggVolumeResolver.resolve(selected).files);
    }

    private static File file(File directory, String name) throws IOException {
        File file = new File(directory, name);
        Files.write(file.toPath(), new byte[]{1});
        return file;
    }

    private static File named(File physical, String name) {
        return new File(physical.getPath()) { @Override public String getName() { return name; } };
    }

    private static File snapshotSelection(File actual, File... siblings) {
        return new SnapshotSelection(actual, siblings);
    }

    private static class SnapshotSelection extends File {
        final File directory;
        SnapshotSelection(File actual, File[] siblings) {
            super(actual.getPath());
            directory = new File(actual.getParent()) {
                @Override public File[] listFiles() { return siblings.clone(); }
            };
        }
        @Override public File getAbsoluteFile() { return this; }
        @Override public File getParentFile() { return directory; }
    }
}
