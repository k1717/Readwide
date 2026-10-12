package com.readwide.manager.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

public class RarVolumeChainIndexTest {
    @Test public void selectedIndexValidatesRequestedIdentitiesButIgnoresUnrelatedTopology() throws Exception {
        RarArchiveReader.RarEntry first = entry("a", false, true);
        RarArchiveReader.RarEntry tail = entry("a", true, false);
        RarArchiveReader.RarEntry second = entry("b", false, false);
        List<RarArchiveReader.RarEntry> entries = Arrays.asList(
                entry("orphan", true, false), first, tail, second, entry("missing", false, true));
        RarVolumeChain.Index index = RarVolumeChain.indexSelected(Arrays.asList(first, second), entries);
        assertEquals(Arrays.asList(first, tail), index.chain(first));
        assertEquals(Collections.singletonList(second), index.chain(second));
        assertThrows(IOException.class, () -> RarVolumeChain.indexSelected(Arrays.asList(first, first), entries));
        assertThrows(IOException.class, () -> RarVolumeChain.indexSelected(Collections.singletonList(tail), entries));
        assertThrows(IOException.class, () -> RarVolumeChain.indexSelected(
                Collections.singletonList(entry("b", false, false)), entries));
    }

    @Test public void selectedChainCannotCrossANewHeadWithTheSamePath() {
        for (boolean split : new boolean[]{false, true}) {
            RarArchiveReader.RarEntry first = entry("image.jpg", false, true);
            List<RarArchiveReader.RarEntry> entries = Arrays.asList(first,
                    entry("image.jpg", false, split), entry("image.jpg", true, false));
            IOException failure = assertThrows(IOException.class, () -> RarVolumeChain.build(first, entries));
            assertTrue(failure.getMessage().contains("Ambiguous"));
            assertThrows(IOException.class, () -> RarVolumeChain.index(entries));
        }
    }

    @Test public void selectedChainDoesNotRequireUnrelatedChainsToBeComplete() throws Exception {
        RarArchiveReader.RarEntry head = entry("good.jpg", false, true);
        RarArchiveReader.RarEntry tail = entry("good.jpg", true, false);
        List<RarArchiveReader.RarEntry> entries = Arrays.asList(
                head, entry("orphan.jpg", true, false), tail, entry("missing.jpg", false, true));
        assertEquals(Arrays.asList(head, tail), RarVolumeChain.build(head, entries));
        assertThrows(IOException.class, () -> RarVolumeChain.index(entries));
    }

    @Test public void completedRepeatedNamesAndInterleavedPathsHaveSeparateChains() throws Exception {
        RarArchiveReader.RarEntry a = entry("a.jpg", false, true);
        RarArchiveReader.RarEntry b = entry("b.jpg", false, true);
        RarArchiveReader.RarEntry aTail = entry("a.jpg", true, false);
        RarArchiveReader.RarEntry bTail = entry("b.jpg", true, false);
        RarArchiveReader.RarEntry aAgain = entry("a.jpg", false, true);
        RarArchiveReader.RarEntry againTail = entry("a.jpg", true, false);
        RarArchiveReader.RarEntry aSingle = entry("a.jpg", false, false);
        List<RarArchiveReader.RarEntry> entries = Arrays.asList(a, b, aTail, bTail, aAgain, againTail, aSingle);
        RarVolumeChain.Index index = RarVolumeChain.index(entries);
        assertEquals(Arrays.asList(a, aTail), index.chain(a));
        assertEquals(Arrays.asList(b, bTail), index.chain(b));
        assertEquals(Arrays.asList(aAgain, againTail), index.chain(aAgain));
        assertEquals(Collections.singletonList(aSingle), index.chain(aSingle));
        for (RarArchiveReader.RarEntry head : Arrays.asList(a, b, aAgain, aSingle)) {
            assertEquals(RarVolumeChain.build(head, entries), index.chain(head));
        }
    }

    @Test public void pathMatchingPreservesCaseAndWhitespace() throws Exception {
        List<RarArchiveReader.RarEntry> heads = new ArrayList<>();
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        for (String path : new String[]{"a.jpg", "A.jpg", " a.jpg", "a.jpg "}) {
            RarArchiveReader.RarEntry head = entry(path, false, true);
            heads.add(head); entries.add(head);
        }
        for (RarArchiveReader.RarEntry head : heads) entries.add(entry(head.path, true, false));
        RarVolumeChain.Index index = RarVolumeChain.index(entries);
        for (int i = 0; i < heads.size(); i++) {
            assertEquals(Arrays.asList(heads.get(i), entries.get(i + heads.size())), index.chain(heads.get(i)));
        }
    }

    @Test public void orphanIncompleteAndReusedPayloadEntriesAreRejected() {
        assertThrows(IOException.class, () -> RarVolumeChain.index(
                Collections.singletonList(entry("orphan", true, false))));
        assertThrows(IOException.class, () -> RarVolumeChain.index(
                Collections.singletonList(entry("missing", false, true))));
        RarArchiveReader.RarEntry single = entry("single", false, false);
        assertThrows(IOException.class, () -> RarVolumeChain.index(Arrays.asList(single, single)));
        RarArchiveReader.RarEntry head = entry("image", false, true);
        RarArchiveReader.RarEntry middle = entry("image", true, true);
        assertThrows(IOException.class, () -> RarVolumeChain.index(Arrays.asList(
                head, middle, middle, entry("image", true, false))));
    }

    @Test public void membershipIsImmutableAndLookupUsesHeadIdentity() throws Exception {
        RarArchiveReader.RarEntry head = entry("image", false, true);
        RarArchiveReader.RarEntry tail = entry("image", true, false);
        List<RarArchiveReader.RarEntry> entries = new LinkedList<>(Arrays.asList(head, tail));
        RarVolumeChain.Index index = RarVolumeChain.index(entries);
        entries.clear();
        assertEquals(Arrays.asList(head, tail), index.chain(head));
        assertThrows(UnsupportedOperationException.class, () -> index.chain(head).clear());
        assertThrows(IOException.class, () -> index.chain(tail));
        assertThrows(IOException.class, () -> index.chain(entry("image", false, true)));
    }

    @Test public void preparationWalksTheSourceOnceAndLookupsDoNotRescanIt() throws Exception {
        List<RarArchiveReader.RarEntry> entries = new LinkedList<>();
        List<RarArchiveReader.RarEntry> heads = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            RarArchiveReader.RarEntry head = entry("image" + i, false, true);
            heads.add(head); entries.add(head); entries.add(entry(head.path, true, false));
        }
        IteratorOnlyList source = new IteratorOnlyList(entries);
        RarVolumeChain.Index index = RarVolumeChain.index(source);
        assertEquals(1, source.iterations);
        assertEquals(entries.size(), source.visits);
        for (RarArchiveReader.RarEntry head : heads) {
            assertSame(head, index.chain(head).get(0));
            assertEquals(2, index.chain(head).size());
        }
        assertEquals(1, source.iterations);
        assertEquals(entries.size(), source.visits);
        RarArchiveReader.RarEntry last = heads.get(heads.size() - 1);
        assertEquals(index.chain(last), RarVolumeChain.build(last, source));
        assertEquals(2, source.iterations);
        assertEquals(entries.size() * 2, source.visits);
    }

    @Test public void discoveryAndLookupPreserveCancellation() throws Exception {
        RarArchiveReader.RarEntry head = entry("image", false, false);
        List<RarArchiveReader.RarEntry> entries = Collections.singletonList(head);
        RarVolumeChain.Index index = RarVolumeChain.index(entries);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> RarVolumeChain.build(head, entries));
            assertThrows(InterruptedIOException.class, () -> RarVolumeChain.index(entries));
            assertThrows(InterruptedIOException.class, () -> RarVolumeChain.index(Collections.emptyList()));
            assertThrows(InterruptedIOException.class, () -> index.chain(head));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static RarArchiveReader.RarEntry entry(String path, boolean before, boolean after) {
        return new RarArchiveReader.RarEntry(path, false, 128, 64, 0,
                4, 0x30, false, before, after, null, 0, 0);
    }

    private static final class IteratorOnlyList extends AbstractList<RarArchiveReader.RarEntry> {
        private final List<RarArchiveReader.RarEntry> entries;
        int iterations, visits;

        IteratorOnlyList(List<RarArchiveReader.RarEntry> entries) { this.entries = entries; }
        @Override public int size() { return entries.size(); }
        @Override public RarArchiveReader.RarEntry get(int index) { throw new AssertionError("Indexed source access"); }
        @Override public int indexOf(Object value) { throw new AssertionError("Repeated head scan"); }
        @Override public Iterator<RarArchiveReader.RarEntry> iterator() {
            iterations++;
            Iterator<RarArchiveReader.RarEntry> iterator = entries.iterator();
            return new Iterator<RarArchiveReader.RarEntry>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public RarArchiveReader.RarEntry next() { visits++; return iterator.next(); }
            };
        }
    }
}
