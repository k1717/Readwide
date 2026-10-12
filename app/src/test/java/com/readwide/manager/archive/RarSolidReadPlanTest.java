package com.readwide.manager.archive;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class RarSolidReadPlanTest {
    @Test public void selectedRunSkipsStoredDirectoriesNullsAndUnrelatedDamage() throws Exception {
        for (int version : new int[]{4, 5}) {
            RarArchiveReader.RarEntry old = entry("old-missing", version, false, false, true);
            RarArchiveReader.RarEntry first = entry("primer", version, false, false, true);
            RarArchiveReader.RarEntry firstTail = entry("primer", version, false, true, false);
            RarArchiveReader.RarEntry target = entry("target", version, true, false, true);
            RarArchiveReader.RarEntry targetTail = entry("target", version, true, true, false);
            RarArchiveReader.RarEntry stored = new RarArchiveReader.RarEntry("stored", false,
                    0, 0, 0, version, version == 4 ? 0x30 : 0, false, false, false, null, 0, 0);
            RarArchiveReader.RarEntry directory = new RarArchiveReader.RarEntry("folder", true,
                    0, 0, 0, version, 0, false, false, false, null, 0, 0);
            List<RarArchiveReader.RarEntry> entries = new LinkedList<>(Arrays.asList(old,
                    first, entry("orphan", version, true, true, false), firstTail,
                    stored, null, directory, target, targetTail,
                    entry("later-missing", version, false, false, true)));
            RarSolidReadPlan plan = RarSolidReadPlan.prepare(target, entries, version);
            assertNotNull(plan);
            assertEquals(2, plan.steps.size());
            assertSame(first, plan.steps.get(0).entry);
            assertSame(target, plan.steps.get(1).entry);
            assertEquals(Arrays.asList(first, firstTail), plan.steps.get(0).parts);
            assertEquals(Arrays.asList(target, targetTail), plan.steps.get(1).parts);
            entries.clear();
            assertEquals(Arrays.asList(target, targetTail), plan.steps.get(1).parts);
            assertThrows(UnsupportedOperationException.class, () -> plan.steps.clear());
            assertThrows(UnsupportedOperationException.class, () -> plan.steps.get(0).parts.clear());
        }
    }

    @Test public void missingPrimerForeignTargetAndContinuationTargetDoNotProduceAPlan() throws Exception {
        for (int version : new int[]{4, 5}) {
            RarArchiveReader.RarEntry target = entry("image", version, true, false, false);
            assertNull(RarSolidReadPlan.prepare(target, Collections.singletonList(target), version));
            RarArchiveReader.RarEntry first = entry("image", version, false, false, false);
            assertNull(RarSolidReadPlan.prepare(entry("image", version, false, false, false),
                    Collections.singletonList(first), version));
            RarArchiveReader.RarEntry continuation = entry("image", version, false, true, false);
            assertNull(RarSolidReadPlan.prepare(continuation, Arrays.asList(first, continuation), version));
            assertNull(RarSolidReadPlan.prepare(first, Collections.singletonList(first), version == 4 ? 5 : 4));
        }
    }

    @Test public void requiredMissingOrAmbiguousPartsFailDuringPlanning() {
        RarArchiveReader.RarEntry first = entry("primer", 5, false, false, true);
        RarArchiveReader.RarEntry target = entry("image", 5, true, false, false);
        assertThrows(IOException.class, () -> RarSolidReadPlan.prepare(target, Arrays.asList(first, target), 5));
        RarArchiveReader.RarEntry conflicting = entry("primer", 5, true, false, false);
        RarArchiveReader.RarEntry tail = entry("primer", 5, false, true, false);
        assertThrows(IOException.class, () -> RarSolidReadPlan.prepare(target,
                Arrays.asList(first, conflicting, tail, target), 5));
    }

    @Test public void completedDuplicatePathsRemainDifferentSolidSteps() throws Exception {
        RarArchiveReader.RarEntry first = entry("image", 4, false, false, true);
        RarArchiveReader.RarEntry firstTail = entry("image", 4, false, true, false);
        RarArchiveReader.RarEntry target = entry("image", 4, true, false, true);
        RarArchiveReader.RarEntry targetTail = entry("image", 4, true, true, false);
        RarSolidReadPlan plan = RarSolidReadPlan.prepare(target,
                Arrays.asList(first, firstTail, target, targetTail), 4);
        assertEquals(2, plan.steps.size());
        assertEquals(Arrays.asList(first, firstTail), plan.steps.get(0).parts);
        assertEquals(Arrays.asList(target, targetTail), plan.steps.get(1).parts);
    }

    @Test public void linkedSourceUsesTwoIteratorPassesNotPerPrimerScans() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new LinkedList<>();
        List<RarArchiveReader.RarEntry> heads = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            RarArchiveReader.RarEntry head = entry("page" + i, 5, i != 0, false, true);
            heads.add(head); entries.add(head);
            entries.add(entry(head.path, 5, i != 0, true, false));
        }
        int[] passes = {0}, visits = {0};
        List<RarArchiveReader.RarEntry> iteratorOnly = new AbstractList<RarArchiveReader.RarEntry>() {
            @Override public int size() { return entries.size(); }
            @Override public RarArchiveReader.RarEntry get(int index) { throw new AssertionError("Indexed source access"); }
            @Override public Iterator<RarArchiveReader.RarEntry> iterator() {
                passes[0]++;
                Iterator<RarArchiveReader.RarEntry> source = entries.iterator();
                return new Iterator<RarArchiveReader.RarEntry>() {
                    @Override public boolean hasNext() { return source.hasNext(); }
                    @Override public RarArchiveReader.RarEntry next() { visits[0]++; return source.next(); }
                };
            }
        };
        RarSolidReadPlan plan = RarSolidReadPlan.prepare(heads.get(heads.size() - 1), iteratorOnly, 5);
        assertEquals(heads.size(), plan.steps.size());
        assertEquals(2, passes[0]);
        assertEquals(entries.size() * 2 - 1, visits[0]); // First pass ends at the target head.
        for (int i = 0; i < heads.size(); i++) assertSame(heads.get(i), plan.steps.get(i).entry);
        assertEquals(2, passes[0]);
    }

    @Test public void cancellationIsPreservedBeforeSourceTraversal() {
        RarArchiveReader.RarEntry target = entry("image", 4, false, false, false);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,
                    () -> RarSolidReadPlan.prepare(target, Collections.singletonList(target), 4));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static RarArchiveReader.RarEntry entry(String path, int version, boolean solid,
            boolean before, boolean after) {
        return new RarArchiveReader.RarEntry(path, false, 16, 8, 0, version,
                version == 4 ? 0x33 : 1, solid, before, after, null, 0, 0, 0);
    }
}
