package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.model.Bookmark;
import com.readwide.manager.model.ReaderState;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BookmarkLoadRecoveryInstrumentedTest {
    private static final String PATH = "/load-recovery.txt";
    private static Bookmark bookmark(int position) {
        return new Bookmark(PATH, "sample.txt", position, 1, "saved");
    }
    private static ReaderState state(int position) {
        ReaderState result = new ReaderState(PATH);
        result.setCharPosition(position);
        return result;
    }
    private static String bookmarks() throws Exception {
        return new JSONObject().put("bookmarks", new JSONArray().put(bookmark(123).toJson())).toString();
    }
    private static String states() throws Exception {
        return new JSONObject().put("states", new JSONObject().put(PATH, state(456).toJson())).toString();
    }

    @Test public void badBookmarkRowCannotPublishPrefixOrOverwriteOriginal() throws Exception {
        try (Fixture f = new Fixture()) {
            String broken = new JSONObject().put("bookmarks",
                    new JSONArray().put(bookmark(123).toJson()).put("bad row")).toString();
            f.write("bookmarks.json", broken);
            f.open();
            assertTrue(f.manager.getAllBookmarks().isEmpty());
            f.manager.addBookmark(bookmark(999));
            f.manager.updateBookmark(bookmark(999));
            f.manager.saveBookmarkPageMetadataRefresh(Collections.singletonList(bookmark(999)));
            f.manager.deleteBookmarksForFile(PATH);
            assertEquals(0, f.manager.replacePathPrefix("/", "/other/"));
            assertEquals(broken, f.read("bookmarks.json"));
            assertExportBlocked(f.manager);
            f.write("bookmarks.json", bookmarks());
            f.manager.addBookmark(bookmark(999));
            assertEquals(2, f.manager.getAllBookmarks().size());
            assertEquals(123, f.manager.getAllBookmarks().get(0).getCharPosition());
        }
    }

    @Test public void badStateRowBlocksSyncDeferredDeleteAndCrossStoreReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            String goodBookmarks = bookmarks();
            String broken = new JSONObject().put("states", new JSONObject()
                    .put(PATH, state(456).toJson()).put("/bad.txt", "bad row")).toString();
            f.write("bookmarks.json", goodBookmarks);
            f.write("reading_states.json", broken);
            f.open();
            assertNull(f.manager.getReadingState(PATH));
            f.manager.saveReadingState(state(999));
            f.manager.saveReadingStateDeferred(state(999));
            f.manager.deleteReadingState(PATH);
            f.manager.clearReadingStates();
            assertEquals(0, f.manager.replacePathPrefix("/", "/other/"));
            assertEquals(0L, f.revision());
            assertEquals(broken, f.read("reading_states.json"));
            assertEquals(goodBookmarks, f.read("bookmarks.json"));
            assertExportBlocked(f.manager);
            f.write("reading_states.json", states());
            assertEquals(456, f.manager.getReadingState(PATH).getCharPosition());
            ReaderState added = new ReaderState("/other.txt");
            added.setCharPosition(789);
            f.manager.saveReadingState(added);
            assertEquals(456, new JSONObject(f.read("reading_states.json"))
                    .getJSONObject("states").getJSONObject(PATH).getInt("charPosition"));
        }
    }

    @Test public void missingOrWrongCollectionsAreNotValidEmptyStores() throws Exception {
        for (String broken : new String[] {"{}", "{\"bookmarks\":{}}", "{\"states\":[]}"}) {
            try (Fixture f = new Fixture()) {
                f.write("bookmarks.json", broken);
                f.write("reading_states.json", broken);
                f.open();
                f.manager.addBookmark(bookmark(999));
                f.manager.saveReadingState(state(999));
                assertEquals(broken, f.read("bookmarks.json"));
                assertEquals(broken, f.read("reading_states.json"));
            }
        }
    }

    @Test public void invalidUtf8IsPreservedButMissingStoresCanBeCreated() throws Exception {
        try (Fixture f = new Fixture()) {
            File file = new File(f.directory, "bookmarks.json");
            byte[] invalid = {(byte) 0xc3, 0x28};
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(invalid); }
            f.open();
            f.manager.addBookmark(bookmark(999));
            try (FileInputStream in = new FileInputStream(file)) {
                assertEquals(0xc3, in.read());
                assertEquals(0x28, in.read());
                assertEquals(-1, in.read());
            }
        }
        try (Fixture f = new Fixture()) {
            f.open();
            f.manager.addBookmark(bookmark(123));
            f.manager.saveReadingState(state(456));
            assertEquals(1, new JSONObject(f.read("bookmarks.json")).getJSONArray("bookmarks").length());
            assertEquals(456, f.manager.getReadingState(PATH).getCharPosition());
        }
    }

    @Test public void transientReadFailureRetriesBeforeMutation() throws Exception {
        for (Throwable failure : new Throwable[] {new SecurityException("denied"),
                new OutOfMemoryError("read allocation"), new StackOverflowError("read stack")}) {
            try (Fixture f = new Fixture()) {
                f.write("bookmarks.json", bookmarks());
                f.write("reading_states.json", states());
                f.failure = failure;
                f.open();
                f.manager.addBookmark(bookmark(999));
                assertEquals(1, new JSONObject(f.read("bookmarks.json")).getJSONArray("bookmarks").length());
                f.failure = null;
                f.manager.addBookmark(bookmark(999));
                assertEquals(2, f.manager.getAllBookmarks().size());
                assertEquals(123, f.manager.getAllBookmarks().get(0).getCharPosition());
                assertEquals(456, f.manager.getReadingState(PATH).getCharPosition());
            }
        }
    }

    private static void assertExportBlocked(BookmarkManager manager) throws Exception {
        try { manager.exportAll(); fail("Incomplete stores must not become a backup"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("kept")); }
    }

    private static final class Fixture implements AutoCloseable {
        final File directory;
        final Context context;
        BookmarkManager manager;
        Throwable failure;
        Fixture() throws Exception {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            directory = new File(target.getCacheDir(), "bookmark-load-" + UUID.randomUUID());
            assertTrue(directory.mkdir());
            context = new ContextWrapper(target) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() {
                    if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                    if (failure instanceof Error) throw (Error) failure;
                    return directory;
                }
            };
        }
        void open() throws Exception {
            Constructor<BookmarkManager> ctor = BookmarkManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            manager = ctor.newInstance(context);
        }
        void write(String name, String value) throws Exception { AtomicUtf8File.write(new File(directory, name), value); }
        String read(String name) throws Exception { return AtomicUtf8File.readIfPresent(new File(directory, name)); }
        long revision() throws Exception {
            Field field = BookmarkManager.class.getDeclaredField("readingStateRevision");
            field.setAccessible(true);
            return field.getLong(manager);
        }
        @Override public void close() {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) assertTrue(file.delete());
            assertTrue(directory.delete());
        }
    }
}
