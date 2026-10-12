package com.readwide.manager.archive;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/** Metadata-only regressions; no decoder or native coverage claim. */
public class ArchiveListingStructureTest {
    @Test public void firstSelectedIdentityIncludesExplicitDirectories() throws Exception {
        ArchiveSupport.EntryInfo file = entry("a/b/page", false, 10);
        ArchiveSupport.EntryInfo directory = entry("a/b/", true, 20);
        List<ArchiveSupport.EntryInfo> result = ArchiveSupport.withFirstSelectedEntryIdentities(
                Arrays.asList(file, entry(file.path, false, 11), directory, entry(directory.path, true, 21)));
        assertEquals(Arrays.asList("a/", "a/b/", "a/b/page"), paths(result));
        assertSame(directory, result.get(1));
        assertSame(file, result.get(2));
    }

    @Test public void firstFilePolicyRetainsLatestExplicitDirectoryMetadata() throws Exception {
        ArchiveSupport.EntryInfo file = entry("a/page", false, 10);
        ArchiveSupport.EntryInfo directory = entry("a/", true, 21);
        List<ArchiveSupport.EntryInfo> result = ArchiveSupport.withFirstFileEntryIdentities(
                Arrays.asList(file, entry(file.path, false, 11), entry("a/", true, 20), directory));
        assertSame(directory, result.get(0));
        assertSame(file, result.get(1));
    }

    @Test public void lastEntryPolicyPreservesFirstInsertionOrder() throws Exception {
        ArchiveSupport.EntryInfo replacement = entry("dir/page", false, 30);
        List<ArchiveSupport.EntryInfo> result = ArchiveSupport.withSyntheticDirectories(Arrays.asList(
                entry("dir/page", false, 10), entry("other", false, 20), replacement));
        assertEquals(Arrays.asList("dir/", "dir/page", "other"), paths(result));
        assertSame(replacement, result.get(1));
    }

    @Test public void siblingFastPathStillCreatesEveryMissingAncestor() throws Exception {
        List<ArchiveSupport.EntryInfo> input = Arrays.asList(entry("a/b/c/1", false, 1),
                entry("a/b/c/2", false, 2), entry("a/b/d/3", false, 3), entry("a/", true, 4));
        List<ArchiveSupport.EntryInfo> result = ArchiveSupport.withSyntheticDirectories(input);
        assertEquals(Arrays.asList("a/", "a/b/", "a/b/c/", "a/b/c/1", "a/b/c/2", "a/b/d/", "a/b/d/3"), paths(result));
        assertSame(input.get(3), result.get(0));
        assertEquals(-1, result.get(1).size);
    }

    @Test public void consolidatedPoliciesMatchPreviousAlgorithmsForMixedMetadata() throws Exception {
        Random random = new Random(0xCAB7);
        String[] names = {"root", "a/page", "a/b/page", "a/b/", "a/", "A/page", "a/./page", "a//page", "tail/"};
        for (int pass = 0; pass < 32; pass++) {
            List<ArchiveSupport.EntryInfo> input = new ArrayList<>();
            for (int i = 0; i < 100; i++) input.add(entry(names[random.nextInt(names.length)], random.nextBoolean(), i));
            assertEquivalent(legacy(input, false, false), ArchiveSupport.withSyntheticDirectories(input));
            assertEquivalent(legacy(input, true, false), ArchiveSupport.withFirstFileEntryIdentities(input));
            assertEquivalent(legacy(input, false, true), ArchiveSupport.withFirstSelectedEntryIdentities(input));
        }
    }

    @Test public void emptyInputHasNoSyntheticRoot() throws Exception {
        assertTrue(ArchiveSupport.withSyntheticDirectories(Collections.emptyList()).isEmpty());
        assertTrue(ArchiveSupport.withFirstFileEntryIdentities(Collections.emptyList()).isEmpty());
        assertTrue(ArchiveSupport.withFirstSelectedEntryIdentities(Collections.emptyList()).isEmpty());
    }

    @Test public void allListingPoliciesHonorInterruption() throws Exception {
        List<ArchiveSupport.EntryInfo> input = Collections.singletonList(entry("a/b/page", false, 1));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ArchiveSupport.withSyntheticDirectories(input));
            assertThrows(InterruptedIOException.class, () -> ArchiveSupport.withFirstFileEntryIdentities(input));
            assertThrows(InterruptedIOException.class, () -> ArchiveSupport.withFirstSelectedEntryIdentities(input));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static ArchiveSupport.EntryInfo entry(String path, boolean directory, long value) {
        return new ArchiveSupport.EntryInfo(path, directory, value, value + 100);
    }

    private static List<String> paths(List<ArchiveSupport.EntryInfo> entries) {
        List<String> result = new ArrayList<>();
        for (ArchiveSupport.EntryInfo entry : entries) result.add(entry.path);
        return result;
    }

    private static void assertEquivalent(List<ArchiveSupport.EntryInfo> expected, List<ArchiveSupport.EntryInfo> actual) {
        assertEquals(paths(expected), paths(actual));
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).directory, actual.get(i).directory);
            assertEquals(expected.get(i).size, actual.get(i).size);
            assertEquals(expected.get(i).timeMillis, actual.get(i).timeMillis);
        }
    }

    // Independent copy of the pre-refactor algorithms, retained only as a regression oracle.
    private static List<ArchiveSupport.EntryInfo> legacy(List<ArchiveSupport.EntryInfo> input,
                                                        boolean firstFile, boolean firstAll) {
        if (firstAll) {
            Map<String, ArchiveSupport.EntryInfo> first = new LinkedHashMap<>();
            for (ArchiveSupport.EntryInfo entry : input) first.putIfAbsent(entry.path, entry);
            input = new ArrayList<>(first.values());
        }
        Map<String, ArchiveSupport.EntryInfo> output = new LinkedHashMap<>();
        for (ArchiveSupport.EntryInfo entry : input) {
            for (int slash = entry.path.indexOf('/'); slash >= 0; slash = entry.path.indexOf('/', slash + 1)) {
                String parent = entry.path.substring(0, slash + 1);
                if (!output.containsKey(parent)) output.put(parent, new ArchiveSupport.EntryInfo(parent, true, -1, 0));
            }
            if (firstFile && !entry.directory) output.putIfAbsent(entry.path, entry);
            else output.put(entry.path, entry);
        }
        return new ArrayList<>(output.values());
    }
}
