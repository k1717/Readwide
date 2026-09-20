package com.readwide.manager.util;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class IndexedBackupMergeTest {
    private static final class Row {
        final String id;
        final List<Object> keys;
        Row(String id, Object... keys) { this.id = id; this.keys = Arrays.asList(keys); }
    }
    private static List<Row> merge(List<Row> existing, List<Row> incoming) {
        return IndexedBackupMerge.merge(existing, incoming, row -> row.id, row -> row.keys);
    }

    @Test public void replacesSameIdInPlaceBeforeCheckingLocation() {
        Row a = new Row("a", "one"), b = new Row("b", "two"), edit = new Row("a", "two");
        assertEquals(Arrays.asList(edit, b), merge(Arrays.asList(a, b), Arrays.asList(edit)));
    }

    @Test public void appendsDistinctRowsInInputOrderAndSkipsEitherLocationKey() {
        Row a = new Row("a", "path", "portable"), b = new Row("b", "new"), c = new Row("c", "last");
        assertEquals(Arrays.asList(a, b, c), merge(Arrays.asList(a), Arrays.asList(
                new Row("skip1", "path"), b, new Row("skip2", "other", "portable"), c)));
    }

    @Test public void replacementReleasesOldLocationWithoutRemovingAnotherOwnersKey() {
        Row a = new Row("a", "shared"), b = new Row("b", "shared"), edit = new Row("a", "new");
        Row skipped = new Row("c", "shared"), freed = new Row("d", "shared");
        Row editB = new Row("b", "last");
        assertEquals(Arrays.asList(edit, editB, freed), merge(Arrays.asList(a, b),
                Arrays.asList(edit, skipped, editB, freed)));
    }

    @Test public void duplicateIdsReplaceOnlyFirstExistingRow() {
        Row a = new Row("same", "a"), b = new Row("same", "b"), edit = new Row("same", "c");
        assertEquals(Arrays.asList(edit, b), merge(Arrays.asList(a, b), Arrays.asList(edit)));
    }

    @Test public void duplicateIncomingIdsKeepTheirFirstSlotAndLastValue() {
        Row a = new Row("a", "a"), b = new Row("b", "b"), edit = new Row("a", "c");
        assertEquals(Arrays.asList(edit, b), merge(Collections.emptyList(), Arrays.asList(a, b, edit)));
    }

    @Test public void idOnlyMergeDoesNotDeduplicateLocationsOrMutateInputs() {
        Row a = new Row("a", "same"), b = new Row("b", "same"), edit = new Row("a", "new");
        List<Row> original = Collections.singletonList(a);
        assertEquals(Arrays.asList(edit, b), IndexedBackupMerge.merge(original,
                Arrays.asList(b, edit), row -> row.id, null));
        assertSame(a, original.get(0));
    }

    @Test public void indexedMergeMatchesSequentialPolicyWithCollisionsAndLinkedLists() {
        Random random = new Random(43117);
        for (int run = 0; run < 80; run++) {
            List<Row> existing = new LinkedList<>(), incoming = new LinkedList<>();
            for (int i = 0; i < 35; i++) existing.add(new Row("id" + random.nextInt(45), random.nextInt(60)));
            for (int i = 0; i < 110; i++) incoming.add(new Row("id" + random.nextInt(120),
                    random.nextInt(160), random.nextInt(160)));
            List<Row> expected = new ArrayList<>(existing);
            for (Row row : incoming) {
                int index = -1;
                for (int i = 0; i < expected.size(); i++) if (Objects.equals(expected.get(i).id, row.id)) { index = i; break; }
                if (index >= 0) expected.set(index, row);
                else {
                    boolean duplicate = false;
                    for (Row old : expected) if (!Collections.disjoint(old.keys, row.keys)) { duplicate = true; break; }
                    if (!duplicate) expected.add(row);
                }
            }
            assertEquals(expected, merge(existing, incoming));
        }
    }

    @Test public void identityAndLocationKeysAreComputedOncePerRow() {
        List<Row> existing = new LinkedList<>(), incoming = new LinkedList<>();
        for (int i = 0; i < 1000; i++) { existing.add(new Row("old" + i, i)); incoming.add(new Row("new" + i, i + 1000)); }
        AtomicInteger ids = new AtomicInteger(), keys = new AtomicInteger();
        List<Row> merged = IndexedBackupMerge.merge(existing, incoming,
                row -> { ids.incrementAndGet(); return row.id; },
                row -> { keys.incrementAndGet(); return row.keys; });
        assertEquals(2000, merged.size());
        assertEquals(2000, ids.get());
        assertEquals(2000, keys.get());
    }
}
