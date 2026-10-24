package com.readwide.manager;

import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.Bookmark;
import com.readwide.manager.util.BookmarkManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.*;

/** Portable binding must not transfer ownership merely because a copy is opened. */
@RunWith(AndroidJUnit4.class)
public class BookmarkPortableBindingInstrumentedTest {
    @Test public void openingIdenticalCopyKeepsLiveOriginalBinding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File original = fixture.book("original", "same text");
            File duplicate = fixture.book("copy", "same text");
            Bookmark bookmark = fixture.add(original);

            assertTrue(fixture.manager.getBookmarksForFile(duplicate.getAbsolutePath()).isEmpty());
            assertEquals(original.getAbsolutePath(), bookmark.getFilePath());
            assertEquals(1, fixture.reload().getBookmarksForFile(original.getAbsolutePath()).size());
            assertLocation(bookmark);
        }
    }

    @Test public void missingOriginalRebindsAndPreservesLocation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File original = fixture.book("original", "same text");
            File moved = fixture.book("moved", "same text");
            Bookmark bookmark = fixture.add(original);
            assertTrue(original.delete());

            assertEquals(1, fixture.manager.getBookmarksForFile(moved.getAbsolutePath()).size());
            assertEquals(moved.getAbsolutePath(), bookmark.getFilePath());
            assertLocation(bookmark);
            assertLocation(fixture.reload().getBookmarksForFile(moved.getAbsolutePath()).get(0));
        }
    }

    @Test public void canonicalAliasOfLiveOriginalStillFindsBookmarks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File original = fixture.book("original", "same text");
            File alias = new File(original.getParentFile(), "../original/novel.txt");
            Bookmark bookmark = fixture.add(original);
            assertNotEquals(original.getAbsolutePath(), alias.getAbsolutePath());
            assertEquals(original.getCanonicalFile(), alias.getCanonicalFile());

            assertEquals(1, fixture.manager.getBookmarksForFile(alias.getAbsolutePath()).size());
            assertEquals(alias.getAbsolutePath(), bookmark.getFilePath());
            assertLocation(bookmark);
        }
    }

    @Test public void replacementAtOldPathDoesNotBlockOriginalRelocation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File original = fixture.book("original", "same text");
            File moved = fixture.book("moved", "same text");
            Bookmark bookmark = fixture.add(original);
            write(original, "other txt"); // Same name and length, different identity.

            assertEquals(1, fixture.manager.getBookmarksForFile(moved.getAbsolutePath()).size());
            assertEquals(moved.getAbsolutePath(), bookmark.getFilePath());
            assertLocation(bookmark);
        }
    }

    @Test public void legacyMissingSourceStillUsesNameAndSize() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File original = fixture.book("original", "same text");
            File moved = fixture.book("moved", "same text");
            Bookmark bookmark = fixture.add(original);
            bookmark.setQuickFingerprint("");
            assertTrue(original.delete());

            assertEquals(1, fixture.manager.getBookmarksForFile(moved.getAbsolutePath()).size());
            assertEquals(moved.getAbsolutePath(), bookmark.getFilePath());
            assertLocation(bookmark);
        }
    }

    @Test public void savedFingerprintDoesNotDowngradeWhenTargetFingerprintIsUnavailable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Bookmark bookmark = fixture.add(fixture.book("original", "same text"));
            for (String unavailable : new String[]{null, "", "   "}) {
                assertFalse(matches(fixture.manager, bookmark, "novel.txt", 9L, unavailable));
            }
            assertTrue(matches(fixture.manager, bookmark, "novel.txt", 9L,
                    bookmark.getQuickFingerprint()));
            bookmark.setQuickFingerprint("");
            assertTrue(matches(fixture.manager, bookmark, "novel.txt", 9L, ""));
            assertFalse(matches(fixture.manager, bookmark, "novel.txt", 8L, ""));
        }
    }

    @Test public void providerBindingKeepsExistingPortableMatchingBehavior() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File target = fixture.book("local", "same text");
            Bookmark bookmark = fixture.add(target);
            bookmark.setFilePath("content://example.documents/document/novel");

            assertEquals(1, fixture.manager.getBookmarksForFile(target.getAbsolutePath()).size());
            assertEquals(target.getAbsolutePath(), bookmark.getFilePath());
            assertLocation(bookmark);
        }
    }

    private static boolean matches(BookmarkManager manager, Bookmark bookmark,
                                   String name, long size, String fingerprint) throws Exception {
        Class<?> identityType = Class.forName(BookmarkManager.class.getName() + "$PortableFileIdentity");
        Constructor<?> constructor = identityType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object identity = constructor.newInstance();
        set(identity, "displayName", name);
        set(identity, "sizeBytes", size);
        set(identity, "quickFingerprint", fingerprint);
        Method match = BookmarkManager.class.getDeclaredMethod("portableIdentityMatches", Bookmark.class, identityType);
        match.setAccessible(true);
        return (Boolean) match.invoke(manager, bookmark, identity);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void assertLocation(Bookmark bookmark) {
        assertEquals(3, bookmark.getCharPosition());
        assertEquals(7, bookmark.getEndPosition());
        assertEquals(2, bookmark.getLineNumber());
        assertEquals("text", bookmark.getExcerpt());
        assertEquals("before", bookmark.getAnchorTextBefore());
        assertEquals("after", bookmark.getAnchorTextAfter());
        assertEquals("keep", bookmark.getLabel());
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File root;
        final Context context;
        final BookmarkManager manager;

        Fixture() throws Exception {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            root = new File(target.getCacheDir(), "portable-binding-" + UUID.randomUUID());
            assertTrue(root.mkdir());
            context = new ContextWrapper(target) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return root; }
            };
            manager = reload();
        }

        BookmarkManager reload() throws Exception {
            Constructor<BookmarkManager> constructor = BookmarkManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            return constructor.newInstance(context);
        }

        File book(String directory, String contents) throws Exception {
            File folder = new File(root, directory);
            assertTrue(folder.mkdir());
            File file = new File(folder, "novel.txt");
            write(file, contents);
            return file;
        }

        Bookmark add(File file) {
            Bookmark bookmark = new Bookmark(file.getAbsolutePath(), file.getName(), 3, 2, "text");
            bookmark.setAnchorTextBefore("before");
            bookmark.setAnchorTextAfter("after");
            bookmark.setLabel("keep");
            manager.addBookmark(bookmark);
            return bookmark;
        }

        @Override public void close() { delete(root); }

        private static void delete(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
            assertTrue(file.delete());
        }
    }
}
