package com.readwide.manager.util;

import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.DocumentAnnotation;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.Assert.*;

/** Isolated private-store failures; never replaces the app's annotation singleton. */
@RunWith(AndroidJUnit4.class)
public class DocumentAnnotationPersistenceInstrumentedTest {
    @Test public void failedAddLeavesMemoryAndExistingStoreUnchanged() throws Exception {
        try (Fixture f = new Fixture()) {
            f.manager.add(note("old"));
            f.context.blocked = true;
            expectFailure(() -> f.manager.add(note("new")));
            assertEquals(1, f.manager.getForFile("book.txt").size());
            f.context.blocked = false;
            assertEquals("old", f.reload().getForFile("book.txt").get(0).getNote());
        }
    }

    @Test public void failedUpdateCannotPublishEditedNote() throws Exception {
        try (Fixture f = new Fixture()) {
            f.manager.add(note("old"));
            DocumentAnnotation edited = f.manager.getForFile("book.txt").get(0);
            edited.setNote("changed");
            assertEquals("old", f.manager.getForFile("book.txt").get(0).getNote());
            f.context.blocked = true;
            expectFailure(() -> f.manager.update(edited));
            assertEquals("old", f.manager.getForFile("book.txt").get(0).getNote());
            f.context.blocked = false;
            assertEquals("old", f.reload().getForFile("book.txt").get(0).getNote());
        }
    }

    @Test public void failedDeleteRetainsRowAndDurableStore() throws Exception {
        try (Fixture f = new Fixture()) {
            DocumentAnnotation original = note("old");
            f.manager.add(original);
            f.context.blocked = true;
            expectFailure(() -> f.manager.delete(original.getId()));
            assertEquals(original.getId(), f.manager.getForFile("book.txt").get(0).getId());
            f.context.blocked = false;
            assertEquals(1, f.reload().getForFile("book.txt").size());
        }
    }

    @Test public void addDetachesInputAndReturnedRows() throws Exception {
        try (Fixture f = new Fixture()) {
            DocumentAnnotation original = note("old");
            f.manager.add(original);
            original.setNote("external mutation");
            DocumentAnnotation returned = f.manager.getForFile("book.txt").get(0);
            returned.setNote("uncommitted edit");
            assertEquals("old", f.manager.getForFile("book.txt").get(0).getNote());
            assertEquals("old", f.reload().getForFile("book.txt").get(0).getNote());
        }
    }

    @Test public void successfulUpdateAndDeleteSurviveReload() throws Exception {
        try (Fixture f = new Fixture()) {
            f.manager.add(note("old"));
            DocumentAnnotation edited = f.manager.getForFile("book.txt").get(0);
            edited.setNote("saved");
            f.manager.update(edited);
            edited.setNote("later external mutation");
            assertEquals("saved", f.reload().getForFile("book.txt").get(0).getNote());
            f.manager.delete(edited.getId());
            assertTrue(f.manager.getForFile("book.txt").isEmpty());
            assertTrue(f.reload().getForFile("book.txt").isEmpty());
        }
    }

    @Test public void duplicateHighlightDoesNotWriteOrChangeExistingEntry() throws Exception {
        try (Fixture f = new Fixture()) {
            DocumentAnnotation highlight = note("");
            highlight.setType(DocumentAnnotation.TYPE_HIGHLIGHT);
            assertTrue(f.manager.add(highlight));
            f.context.blocked = true;
            assertFalse(f.manager.add(highlight.copy()));
            assertEquals(1, f.manager.getForFile("book.txt").size());
        }
    }

    @Test public void checkedImportAndRollbackRemainCompatible() throws Exception {
        try (Fixture f = new Fixture()) {
            f.manager.add(note("old"));
            List<DocumentAnnotation> previous = f.manager.snapshotForImport();
            org.json.JSONArray imported = new org.json.JSONArray().put(note("imported").toJson());
            f.manager.importJson(imported, false);
            assertEquals("imported", f.manager.getForFile("book.txt").get(0).getNote());
            f.manager.restoreAfterImportFailure(previous);
            assertEquals("old", f.manager.getForFile("book.txt").get(0).getNote());
            assertEquals("old", f.reload().getForFile("book.txt").get(0).getNote());
        }
    }

    @Test public void detachedCopyPreservesAllStoredFields() throws Exception {
        DocumentAnnotation original = note("메모");
        DocumentAnnotation copy = original.copy();
        assertNotSame(original, copy);
        assertEquals(original.toJson().toString(), copy.toJson().toString());
    }

    @Test public void noteEditCannotRevertRelocatedPathOrSourcePosition() throws Exception {
        try (Fixture f = new Fixture()) {
            f.manager.add(note("old"));
            DocumentAnnotation edited = f.manager.getForFile("book.txt").get(0);
            f.manager.moveFileReferences("book.txt", "renamed.txt");
            edited.setNote("saved after rename");
            edited.setStartPosition(999);
            f.manager.update(edited);
            assertTrue(f.manager.getForFile("book.txt").isEmpty());
            DocumentAnnotation saved = f.reload().getForFile("renamed.txt").get(0);
            assertEquals("saved after rename", saved.getNote());
            assertEquals(4, saved.getStartPosition());
            assertEquals(9, saved.getEndPosition());
        }
    }

    @Test public void missingUpdateIdentityRetainsExistingAddBehavior() throws Exception {
        try (Fixture f = new Fixture()) {
            DocumentAnnotation added = note("new");
            f.manager.update(added);
            assertEquals(added.getId(), f.reload().getForFile("book.txt").get(0).getId());
        }
    }

    private static DocumentAnnotation note(String value) {
        DocumentAnnotation result = new DocumentAnnotation();
        result.setFilePath("book.txt");
        result.setFileName("book.txt");
        result.setDocumentType("TXT");
        result.setStartPosition(4);
        result.setEndPosition(9);
        result.setLineNumber(2);
        result.setSelectedText("selection");
        result.setAnchorTextBefore("before");
        result.setAnchorTextAfter("after");
        result.setNote(value);
        return result;
    }

    private interface Edit { void run() throws DocumentAnnotationManager.PersistenceException; }
    private static void expectFailure(Edit edit) throws Exception {
        try { edit.run(); fail("Expected durable-write failure"); }
        catch (DocumentAnnotationManager.PersistenceException expected) { }
    }

    private static final class Fixture implements AutoCloseable {
        final File root;
        final StoreContext context;
        final DocumentAnnotationManager manager;
        Fixture() throws Exception {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            root = File.createTempFile("annotation-store-", ".test", base.getCacheDir());
            assertTrue(root.delete());
            assertTrue(root.mkdir());
            File store = new File(root, "files");
            assertTrue(store.mkdir());
            File blocker = new File(root, "not-a-directory");
            assertTrue(blocker.createNewFile());
            context = new StoreContext(base, store, blocker);
            manager = reload();
        }
        DocumentAnnotationManager reload() throws Exception {
            Constructor<DocumentAnnotationManager> constructor =
                    DocumentAnnotationManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            return constructor.newInstance(context);
        }
        @Override public void close() { deleteFixture(root); }
    }

    private static final class StoreContext extends ContextWrapper {
        final File store, blocker;
        boolean blocked;
        StoreContext(Context base, File store, File blocker) {
            super(base); this.store = store; this.blocker = blocker;
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return blocked ? blocker : store; }
    }

    private static void deleteFixture(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixture(child);
        assertTrue(file.delete());
    }
}
