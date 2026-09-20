package com.readwide.manager.archive;

import static org.junit.Assert.*;
import static com.readwide.manager.archive.Rar3DecodePlan.Action.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Planner tests do not claim that synthetic probe classifications prove payload support. */
public class Rar3DecodePlanTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void probesOnlyIndependentStartsAndKeepsDirectoryHistory() throws Exception {
        List<RarArchiveReader.RarEntry> entries = Arrays.asList(compressed(false), directory(), compressed(true), compressed(true));
        int[] probes = {0};
        Rar3DecodePlan plan = Rar3DecodePlan.compile(entries, entry -> { probes[0]++; return classic(); });
        assertNotNull(plan);
        assertEquals(1, probes[0]);
        assertEquals(START_CHECKED, plan.step(0).action);
        assertEquals(DIRECTORY, plan.step(1).action);
        assertEquals(CONTINUE_CHECKED, plan.step(2).action);
        assertEquals(CONTINUE_CHECKED, plan.step(3).action);
        assertEquals(0, plan.step(3).runStart);
        assertFalse(plan.independentLzOnly);
    }

    @Test public void standaloneRunsRemainIndependentBesideSolidRuns() throws Exception {
        Rar3DecodePlan plan = Rar3DecodePlan.compile(Arrays.asList(compressed(false), compressed(false),
                compressed(true), compressed(false)), entry -> classic());
        assertEquals(INDEPENDENT_LZ, plan.step(0).action);
        assertEquals(START_CHECKED, plan.step(1).action);
        assertEquals(CONTINUE_CHECKED, plan.step(2).action);
        assertEquals(INDEPENDENT_LZ, plan.step(3).action);
        assertEquals(1, plan.step(2).runStart);
        assertEquals(3, plan.step(3).runStart);
    }

    @Test public void storedFilesBreakPrimerEligibilityButDirectoriesDoNot() throws Exception {
        RarArchiveReader.RarEntry stored = entry(0x30, false, 2, 2, 0, 0, false, false, null);
        assertNull(Rar3DecodePlan.compile(Arrays.asList(compressed(false), stored, directory(), compressed(true)), e -> classic()));
        assertNull(Rar3DecodePlan.compile(Collections.singletonList(compressed(true)), e -> classic()));
        Rar3DecodePlan plan = Rar3DecodePlan.compile(Arrays.asList(compressed(false), stored,
                compressed(false), compressed(true)), e -> classic());
        assertEquals(INDEPENDENT_LZ, plan.step(0).action);
        assertEquals(STORED, plan.step(1).action);
        assertEquals(START_CHECKED, plan.step(2).action);
        assertEquals(2, plan.step(3).runStart);
    }

    @Test public void independentPpmdRequiresResetAndCheckedBoundary() throws Exception {
        Rar3DecodePlan.StartProbe reset = e -> new Rar3PpmdBlockProbe.Result(
                Rar3PpmdBlockProbe.KIND_PPMD, false, 0xa000, 0, "reset");
        Rar3DecodePlan plan = Rar3DecodePlan.compile(Collections.singletonList(compressed(false)), reset);
        assertEquals(START_CHECKED, plan.step(0).action);
        assertFalse(plan.independentLzOnly);
        assertNull(Rar3DecodePlan.compile(Collections.singletonList(compressed(false)), e ->
                new Rar3PpmdBlockProbe.Result(Rar3PpmdBlockProbe.KIND_PPMD, true, 0x8000, 0, "reuse")));
    }

    @Test public void unsupportedMetadataIsRejectedBeforeAnyDecode() throws Exception {
        List<RarArchiveReader.RarEntry> invalid = Arrays.asList(
                entry(0x33, false, 1, 2, 0, -1, false, false, null),
                entry(0x33, false, 1, 2, 0, 0x100000000L, false, false, null),
                entry(0x33, false, 1, 2, Long.MAX_VALUE, 0, false, false, null),
                entry(0x33, false, -1, 2, 0, 0, false, false, null),
                entry(0x33, false, 1, 2, 0, 0, true, false, null),
                entry(0x33, false, 1, 2, 0, 0, false, true, null),
                entry(0x33, false, 1, 2, 0, 0, false, false, RarArchiveReader.EncryptionInfo.rar4Unsupported(new byte[8])),
                entry(0x30, true, 2, 2, 0, 0, false, false, null),
                entry(0x30, false, 3, 2, 0, 0, false, false, null),
                entry(0x30, false, 2, 2, 0, -1, false, false, null));
        for (RarArchiveReader.RarEntry candidate : invalid) {
            assertNull(Rar3DecodePlan.compile(Arrays.asList(compressed(false), candidate), e -> classic()));
        }
    }

    @Test public void planOwnsListAndMutableSourceReference() throws Exception {
        RarArchiveReader.RarEntry entry = compressed(false);
        File original = entry.sourceArchive;
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>(Collections.singletonList(entry));
        Rar3DecodePlan plan = Rar3DecodePlan.compile(entries, e -> classic());
        entries.clear(); entry.sourceArchive = new File("replacement");
        assertEquals(1, plan.size());
        assertNotSame(entry, plan.step(0).entry);
        assertEquals(original, plan.step(0).entry.sourceArchive);
        assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear());
    }

    @Test public void outputAccountingSaturatesWithoutFileSizeCaps() throws Exception {
        RarArchiveReader.RarEntry large = entry(0x33, false, Long.MAX_VALUE, 2, 0, 0, false, false, null);
        Rar3DecodePlan plan = Rar3DecodePlan.compile(Arrays.asList(large, compressed(false)), e -> classic());
        assertNotNull(plan);
        assertEquals(Long.MAX_VALUE, plan.totalUnpackedBytes);
        assertTrue(plan.independentLzOnly);
    }

    @Test public void targetSelectionIgnoresUnrelatedRunsAndUsesIdentity() throws Exception {
        File source = temp.newFile(); Files.write(source.toPath(), new byte[]{0,0});
        RarArchiveReader.RarEntry target = compressed(false); target.sourceArchive = source;
        RarArchiveReader.RarEntry unsupported = entry(0xff, false, 1, 2, 0, 0, false, false, null);
        assertNotNull(Rar3DecodePlan.forTarget(Arrays.asList(unsupported, target, unsupported), target));
        assertNull(Rar3DecodePlan.forTarget(Collections.singletonList(target), compressed(false)));
    }

    @Test public void targetSolidPlanKeepsDirectoriesAndCannotCrossStoredBarrier() throws Exception {
        File source = temp.newFile(); Files.write(source.toPath(), new byte[]{0,0});
        RarArchiveReader.RarEntry first = compressed(false), target = compressed(true);
        first.sourceArchive = target.sourceArchive = source;
        Rar3DecodePlan plan = Rar3DecodePlan.forTarget(Arrays.asList(first, directory(), target), target);
        assertNotNull(plan);
        assertEquals(3, plan.size());
        assertEquals(START_CHECKED, plan.step(0).action);
        assertEquals(CONTINUE_CHECKED, plan.step(2).action);
        RarArchiveReader.RarEntry stored = entry(0x30, false, 2, 2, 0, 0, false, false, null);
        assertNull(Rar3DecodePlan.forTarget(Arrays.asList(first, stored, target), target));
    }

    @Test public void planningPreservesCancellation() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> Rar3DecodePlan.compile(Collections.singletonList(compressed(false)), e -> classic()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void longLinkedSequenceNeedsOnlyOneStartProbe() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new LinkedList<>();
        entries.add(compressed(false));
        for (int i = 1; i < 10000; i++) entries.add(compressed(true));
        int[] probes = {0};
        Rar3DecodePlan plan = Rar3DecodePlan.compile(entries, e -> { probes[0]++; return classic(); });
        assertEquals(10000, plan.size());
        assertEquals(1, probes[0]);
        assertEquals(0, plan.step(9999).runStart);
    }

    private static Rar3PpmdBlockProbe.Result classic() {
        return new Rar3PpmdBlockProbe.Result(Rar3PpmdBlockProbe.KIND_CLASSIC_LZ, false, 0, 0, "classic");
    }
    private static RarArchiveReader.RarEntry compressed(boolean solid) {
        return entry(0x33, solid, 1, 2, 0, 0, false, false, null);
    }
    private static RarArchiveReader.RarEntry directory() {
        return new RarArchiveReader.RarEntry("folder", true, 0, 0, 0, 4, 0x30, false, false, false, null, 0, 0);
    }
    private static RarArchiveReader.RarEntry entry(int method, boolean solid, long unpacked, long packed,
            long offset, long crc, boolean before, boolean after, RarArchiveReader.EncryptionInfo encryption) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("same-name.bin", false, unpacked,
                packed, offset, 4, method, solid, before, after, encryption, crc, 0);
        entry.sourceArchive = new File("metadata-only.payload");
        return entry;
    }
}
