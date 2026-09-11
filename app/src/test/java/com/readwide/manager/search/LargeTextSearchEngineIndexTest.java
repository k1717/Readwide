package com.readwide.manager.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.util.SearchOptions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class LargeTextSearchEngineIndexTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void completedCountReusesPositionsForNearestAndOccurrenceSearches() throws Exception {
        File file = textFile("alpha beta\nbeta\n\nomega beta");
        AtomicInteger opens = new AtomicInteger();
        LargeTextSearchEngine engine = engine(opens, new AtomicReference<>("rules-v1"));

        assertEquals(3, engine.countMatches(
                file, "beta", SearchOptions.literal(), false, null));
        assertEquals(1, opens.get());

        LargeTextSearchResult forward = engine.searchNearest(
                file, "beta", 12, true, SearchOptions.literal(), false, null);
        assertEquals(23, forward.charPosition);
        assertEquals(4, forward.lineNumber);
        assertEquals(3, forward.ordinal);
        assertEquals(3, forward.total);

        LargeTextSearchResult backward = engine.searchNearest(
                file, "beta", 22, false, SearchOptions.literal(), false, null);
        assertEquals(11, backward.charPosition);
        assertEquals(2, backward.ordinal);

        LargeTextSearchResult wrapped = engine.searchNearest(
                file, "beta", 24, true, SearchOptions.literal(), false, null);
        assertEquals(6, wrapped.charPosition);
        assertEquals(1, wrapped.ordinal);

        LargeTextSearchResult second = engine.search(
                file, "beta", 0, true, 2, SearchOptions.literal(), false, null);
        assertEquals(11, second.charPosition);
        assertEquals(2, second.ordinal);
        assertEquals(1, opens.get());
    }

    @Test
    public void cacheIdentityIncludesFileStateAndDisplayTransform() throws Exception {
        File file = textFile("one hit\ntwo hit");
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<String> transformSignature = new AtomicReference<>("rules-v1");
        LargeTextSearchEngine engine = engine(opens, transformSignature);
        assertEquals(2, engine.countMatches(
                file, "hit", SearchOptions.literal(), false, null));

        transformSignature.set("rules-v2");
        LargeTextSearchResult afterRuleChange = engine.searchNearest(
                file, "hit", 0, true, SearchOptions.literal(), false, null);
        assertTrue(afterRuleChange.found());
        assertEquals(2, opens.get());

        assertEquals(2, engine.countMatches(
                file, "hit", SearchOptions.literal(), false, null));
        Files.write(file.toPath(), "\nthree hit".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        LargeTextSearchResult afterFileChange = engine.searchNearest(
                file, "hit", 0, true, SearchOptions.literal(), false, null);
        assertTrue(afterFileChange.found());
        assertEquals(4, opens.get());
    }

    @Test
    public void cancelledCountDoesNotPublishPartialIndex() throws Exception {
        File file = textFile("hit\nhit\nhit");
        AtomicInteger opens = new AtomicInteger();
        LargeTextSearchEngine engine = engine(opens, new AtomicReference<>("rules-v1"));

        assertEquals(-1, engine.countMatches(
                file, "hit", SearchOptions.literal(), false, () -> true));
        assertEquals(1, opens.get());

        LargeTextSearchResult result = engine.searchNearest(
                file, "hit", 0, true, SearchOptions.literal(), false, null);
        assertTrue(result.found());
        assertEquals(2, opens.get());
    }

    @Test
    public void boundedBuilderDeclinesPartialIndexAfterOverflow() throws Exception {
        File file = textFile("irrelevant");
        LargeTextMatchIndex.Builder builder = new LargeTextMatchIndex.Builder(2);
        builder.add(1, 1);
        builder.add(2, 1);
        builder.add(3, 1);

        assertTrue(builder.overflowed());
        try {
            builder.build(file, "x", "Cwrn", false, "rules");
            org.junit.Assert.fail("Partial indexes must not be published");
        } catch (java.io.IOException expected) { }
    }

    @Test public void spilledIndexPreservesOrdinalsWrapAndDeletesCacheOnClose() throws Exception {
        File file = textFile("source");
        File cache = temporaryFolder.newFolder();
        try (LargeTextMatchIndex.Builder builder = new LargeTextMatchIndex.Builder(2, cache)) {
            for (int i = 0; i < 9; i++) builder.add(i * 3, i + 1);
            assertFalse(builder.overflowed());
            try (LargeTextMatchIndex index = builder.build(file, "x", "options", false, "rules")) {
                assertEquals(9, index.size());
                assertEquals(15, index.occurrence(6).charPosition);
                assertEquals(6, index.occurrence(6).lineNumber);
                assertEquals(0, index.nearest(25, true).charPosition);
                assertEquals(3, index.nearest(4, false).charPosition);
                assertEquals(1, cache.list().length);
            }
        }
        assertEquals(0, cache.list().length);
    }

    @Test public void abandonedSpillRemovesPartialFile() throws Exception {
        File cache = temporaryFolder.newFolder();
        try (LargeTextMatchIndex.Builder builder = new LargeTextMatchIndex.Builder(1, cache)) {
            builder.add(1, 1); builder.add(2, 1);
            assertEquals(1, cache.list().length);
        }
        assertEquals(0, cache.list().length);
    }

    @Test public void unavailableDiskCacheDeclinesIndexWithoutPublishingPrefix() throws Exception {
        File notDirectory = temporaryFolder.newFile();
        try (LargeTextMatchIndex.Builder builder = new LargeTextMatchIndex.Builder(1, notDirectory)) {
            builder.add(1, 1); builder.add(2, 1);
            assertTrue(builder.overflowed());
        }
    }

    @Test public void moreThanTwoHundredThousandMatchesStillUseExactIndex() throws Exception {
        char[] data = new char[200_005];
        java.util.Arrays.fill(data, 'x');
        File file = textFile(new String(data));
        AtomicInteger opens = new AtomicInteger();
        LargeTextSearchEngine engine = engine(opens, new AtomicReference<>("rules"));
        try {
            assertEquals(data.length, engine.countMatches(file, "x", SearchOptions.literal(), false, null));
            assertEquals(200_004, engine.search(file, "x", 0, true, 200_005,
                    SearchOptions.literal(), false, null).charPosition);
            assertEquals(1, opens.get());
        } finally { engine.close(); }
    }

    private File textFile(String text) throws Exception {
        File file = temporaryFolder.newFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static LargeTextSearchEngine engine(AtomicInteger opens,
                                                AtomicReference<String> transformSignature) {
        return new LargeTextSearchEngine(
                file -> {
                    opens.incrementAndGet();
                    return new BufferedReader(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8));
                },
                file -> new LargeTextSearchEngine.LineTransform() {
                    @Override
                    public String apply(String line) {
                        return line;
                    }

                    @Override
                    public String signature() {
                        return transformSignature.get();
                    }
                });
    }
}
