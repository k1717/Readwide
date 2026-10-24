package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

/** Injected failures only; no intentional heap/stack exhaustion or live preference changes. */
@RunWith(AndroidJUnit4.class)
public class BookmarkPersistenceResourceInstrumentedTest {
    private static final String PATH = "/persistence-resource-test.txt";

    @Test public void lifecycleSnapshotFailurePreservesFileAndLaterCheckpointWorks() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            fixture.manager.saveReadingState(state(10));
            String before = fixture.read("reading_states.json");
            fixture.manager.saveReadingState(new ReaderState(PATH) {
                @Override public ReaderState copy() { throw failure; }
            });
            assertEquals(before, fixture.read("reading_states.json"));
            fixture.manager.saveReadingState(state(20));
            assertPosition(fixture, 20);
        }
    }

    @Test public void lifecycleEncodingFailurePreservesFileAndLaterCheckpointWorks() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            fixture.manager.saveReadingState(state(10));
            String before = fixture.read("reading_states.json");
            fixture.manager.saveReadingState(new ReaderState(PATH) {
                @Override public ReaderState copy() { return this; }
                @Override public JSONObject toJson() throws JSONException { throw failure; }
            });
            assertEquals(before, fixture.read("reading_states.json"));
            fixture.manager.saveReadingState(state(30));
            assertPosition(fixture, 30);
        }
    }

    @Test public void lifecycleSinkSetupFailureKeepsOldCheckpointAndAllowsRetry() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            fixture.manager.saveReadingState(state(10));
            String before = fixture.read("reading_states.json");
            fixture.filesDirFailure = failure;
            fixture.manager.saveReadingState(state(40));
            assertEquals(before, fixture.read("reading_states.json"));
            fixture.filesDirFailure = null;
            fixture.manager.saveReadingState(state(40));
            assertPosition(fixture, 40);
        }
    }

    @Test public void bookmarkEncodingFailureKeepsOldFileAndLaterSaveWorks() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            fixture.saveBookmarks(new Bookmark(PATH, "sample.txt", 10, 1, "before"));
            String before = fixture.read("bookmarks.json");
            fixture.saveBookmarks(new Bookmark(PATH, "sample.txt", 20, 2, "failed") {
                @Override public JSONObject toJson() throws JSONException { throw failure; }
            });
            assertEquals(before, fixture.read("bookmarks.json"));
            fixture.saveBookmarks(new Bookmark(PATH, "sample.txt", 30, 3, "after"));
            assertEquals(30, new JSONObject(fixture.read("bookmarks.json"))
                    .getJSONArray("bookmarks").getJSONObject(0).getInt("charPosition"));
        }
    }

    @Test public void bookmarkSinkSetupFailureKeepsOldFileAndAllowsLaterSave() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            Bookmark bookmark = new Bookmark(PATH, "sample.txt", 10, 1, "before");
            fixture.saveBookmarks(bookmark);
            String before = fixture.read("bookmarks.json");
            fixture.filesDirFailure = failure;
            fixture.saveBookmarks(new Bookmark(PATH, "sample.txt", 20, 2, "failed"));
            assertEquals(before, fixture.read("bookmarks.json"));
            fixture.filesDirFailure = null;
            fixture.saveBookmarks(new Bookmark(PATH, "sample.txt", 40, 4, "after"));
            assertEquals(40, new JSONObject(fixture.read("bookmarks.json"))
                    .getJSONArray("bookmarks").getJSONObject(0).getInt("charPosition"));
        }
    }

    @Test public void unrelatedFatalErrorStillPropagatesFromLifecycleSnapshot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AssertionError failure = new AssertionError("injected non-resource failure");
            try {
                fixture.manager.saveReadingState(new ReaderState(PATH) {
                    @Override public ReaderState copy() { throw failure; }
                });
                fail("Only recoverable resource errors should be contained");
            } catch (AssertionError expected) { assertSame(failure, expected); }
            fixture.manager.saveReadingState(state(50));
            assertPosition(fixture, 50);
        }
    }

    @Test public void deferredCopyFailureKeepsExistingModelTimestampRevisionAndFile() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            Error[] copyFailure = {null};
            ReaderState current = new ReaderState(PATH) {
                @Override public ReaderState copy() {
                    if (copyFailure[0] != null) throw copyFailure[0];
                    return super.copy();
                }
            };
            current.setCharPosition(10);
            fixture.manager.saveReadingState(current);
            String before = fixture.read("reading_states.json");
            long revision = fixture.revision();
            current.setLastReadAt(17L); // Distinguish even if calls share a clock tick.
            copyFailure[0] = failure;
            fixture.manager.saveReadingStateDeferred(current);
            assertSame(current, fixture.manager.getReadingState(PATH));
            assertEquals(10, current.getCharPosition());
            assertEquals(17L, current.getLastReadAt());
            assertEquals(revision, fixture.revision());
            assertEquals(before, fixture.read("reading_states.json"));
            fixture.manager.saveReadingState(state(20));
            assertPosition(fixture, 20);
        }
    }

    @Test public void deferredPublicationResourceFailureAllowsLaterSynchronousCheckpoint() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            ReaderState current = state(10);
            fixture.manager.saveReadingState(current);
            String before = fixture.read("reading_states.json");
            long revision = fixture.revision();
            FaultingStates states = fixture.failPublications(failure);
            fixture.manager.saveReadingStateDeferred(state(20));
            assertSame(current, fixture.manager.getReadingState(PATH));
            assertEquals(revision, fixture.revision());
            assertEquals(before, fixture.read("reading_states.json"));
            states.failure = null;
            fixture.manager.saveReadingState(state(30));
            assertPosition(fixture, 30);
        }
    }

    @Test public void synchronousPublicationResourceFailureKeepsOldStateAndCanRetry() throws Exception {
        for (Error failure : resourceFailures()) try (Fixture fixture = new Fixture()) {
            ReaderState current = state(10);
            fixture.manager.saveReadingState(current);
            String before = fixture.read("reading_states.json");
            FaultingStates states = fixture.failPublications(failure);
            fixture.manager.saveReadingState(state(20));
            assertSame(current, fixture.manager.getReadingState(PATH));
            assertEquals(before, fixture.read("reading_states.json"));
            states.failure = null;
            fixture.manager.saveReadingState(state(40));
            assertPosition(fixture, 40);
        }
    }

    @Test public void unrelatedFatalErrorStillPropagatesFromDeferredCopy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.manager.saveReadingState(state(10));
            String before = fixture.read("reading_states.json");
            AssertionError failure = new AssertionError("injected non-resource failure");
            try {
                fixture.manager.saveReadingStateDeferred(new ReaderState(PATH) {
                    @Override public ReaderState copy() { throw failure; }
                });
                fail("Unrelated error must propagate");
            } catch (AssertionError expected) { assertSame(failure, expected); }
            assertEquals(10, fixture.manager.getReadingState(PATH).getCharPosition());
            assertEquals(before, fixture.read("reading_states.json"));
        }
    }

    private static ReaderState state(int position) {
        ReaderState state = new ReaderState(PATH);
        state.setCharPosition(position);
        return state;
    }

    private static void assertPosition(Fixture fixture, int expected) throws Exception {
        assertEquals(expected, new JSONObject(fixture.read("reading_states.json"))
                .getJSONObject("states").getJSONObject(PATH).getInt("charPosition"));
    }

    private static List<Error> resourceFailures() {
        return Arrays.asList(new OutOfMemoryError("injected heap failure"),
                new StackOverflowError("injected stack failure"));
    }

    private static final class FaultingStates extends HashMap<String, ReaderState> {
        Error failure;
        FaultingStates(Map<String, ReaderState> existing) { super(existing); }
        @Override public ReaderState put(String key, ReaderState value) {
            if (failure != null) throw failure;
            return super.put(key, value);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File directory;
        final BookmarkManager manager;
        Error filesDirFailure;

        Fixture() throws Exception {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            directory = new File(target.getCacheDir(), "bookmark-resource-" + UUID.randomUUID());
            assertTrue(directory.mkdir());
            Context isolated = new ContextWrapper(target) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() {
                    if (filesDirFailure != null) throw filesDirFailure;
                    return directory;
                }
            };
            Constructor<BookmarkManager> constructor = BookmarkManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            manager = constructor.newInstance(isolated);
        }

        String read(String name) throws Exception {
            return AtomicUtf8File.readIfPresent(new File(directory, name));
        }

        long revision() throws Exception {
            Field field = BookmarkManager.class.getDeclaredField("readingStateRevision");
            field.setAccessible(true);
            return field.getLong(manager);
        }

        @SuppressWarnings("unchecked")
        FaultingStates failPublications(Error failure) throws Exception {
            Field field = BookmarkManager.class.getDeclaredField("readingStates");
            field.setAccessible(true);
            FaultingStates states = new FaultingStates((Map<String, ReaderState>) field.get(manager));
            states.failure = failure;
            field.set(manager, states);
            return states;
        }

        void saveBookmarks(Bookmark value) throws Exception {
            Field field = BookmarkManager.class.getDeclaredField("bookmarks");
            field.setAccessible(true);
            field.set(manager, new ArrayList<>(Arrays.asList(value)));
            Method save = BookmarkManager.class.getDeclaredMethod("saveBookmarks");
            save.setAccessible(true);
            save.invoke(manager);
        }

        @Override public void close() {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) assertTrue(file.delete());
            assertTrue(directory.delete());
        }
    }
}
