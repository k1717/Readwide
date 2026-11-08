package com.readwide.manager.util;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class UriCopyCacheIntegrityInstrumentedTest {
    private File root;
    private FixtureProvider provider;
    private Context context;
    private final Uri uri = Uri.parse("content://readwide.copy.fixture/book");

    @Before public void setUp() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        root = new File(target.getCacheDir(), "uri-copy-fixture-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        provider = new FixtureProvider(new File(root, "input.txt"));
        ContentResolver resolver = ContentResolver.wrap(provider);
        context = new ContextWrapper(target) {
            @Override public File getCacheDir() { return root; }
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
    }

    @After public void tearDown() {
        Thread.interrupted();
        if (root != null) removeFixture(root);
    }

    @Test public void truncatedProviderReadKeepsPreviousCache() throws Exception {
        File cache = createPreviousCache();
        provider.prepare("no", 3L, 3L, 20L, 20L);
        expectFailedRefresh();
        assertEquals("old", read(cache));
        assertNoStaging(cache);
    }

    @Test public void sameSizeTimestampChangeKeepsPreviousCache() throws Exception {
        File cache = createPreviousCache();
        provider.prepare("new", 3L, 3L, 20L, 21L);
        expectFailedRefresh();
        assertEquals("old", read(cache));
        assertNoStaging(cache);
    }

    @Test public void metadataFailureAfterReadKeepsPreviousCache() throws Exception {
        File cache = createPreviousCache();
        provider.prepare("new", 3L, 3L, 20L, 20L);
        provider.failSecondQuery = true;
        expectFailedRefresh();
        assertEquals("old", read(cache));
        assertNoStaging(cache);
    }

    @Test public void unknownMetadataStillOpensAndRefreshes() throws Exception {
        File cache = createPreviousCache();
        provider.prepare("new data", null, null, null, null);
        assertEquals(cache, FileUtils.copyUriToLocal(context, uri, "book.txt"));
        assertEquals("new data", read(cache));
        assertNoStaging(cache);
    }

    @Test public void unchangedKnownMetadataReplacesCache() throws Exception {
        File cache = createPreviousCache();
        provider.prepare("new", 3L, 3L, 20L, 20L);
        assertEquals(cache, FileUtils.copyUriToLocal(context, uri, "book.txt"));
        assertEquals("new", read(cache));
        assertNoStaging(cache);
    }

    @Test public void cancellationDuringFinalQueryKeepsPreviousCache() throws Exception {
        File cache = createPreviousCache();
        provider.prepare("new", 3L, 3L, 20L, 20L);
        provider.interruptSecondQuery = true;
        try {
            expectFailedRefresh();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("old", read(cache));
        assertNoStaging(cache);
    }

    private File createPreviousCache() throws Exception {
        provider.prepare("old", 3L, 3L, 10L, 10L);
        return FileUtils.copyUriToLocal(context, uri, "book.txt");
    }

    private void expectFailedRefresh() throws Exception {
        try {
            FileUtils.copyUriToLocal(context, uri, "book.txt");
            fail("Expected refresh to reject changed input");
        } catch (IOException expected) { }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void assertNoStaging(File cache) {
        File[] entries = cache.getParentFile().listFiles();
        assertNotNull(entries);
        assertEquals(1, entries.length);
        assertEquals(cache, entries[0]);
    }

    private static void removeFixture(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeFixture(child);
        assertTrue(file.delete());
    }

    private static final class FixtureProvider extends ContentProvider {
        final File file;
        Long beforeSize, afterSize, beforeModified, afterModified;
        int queries;
        boolean failSecondQuery, interruptSecondQuery;

        FixtureProvider(File file) { this.file = file; }

        void prepare(String text, Long beforeSize, Long afterSize, Long beforeModified,
                Long afterModified) throws Exception {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            this.beforeSize = beforeSize;
            this.afterSize = afterSize;
            this.beforeModified = beforeModified;
            this.afterModified = afterModified;
            queries = 0;
            failSecondQuery = false;
            interruptSecondQuery = false;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) {
            boolean after = ++queries > 1;
            if (after && failSecondQuery) throw new SecurityException("Access revoked");
            if (after && interruptSecondQuery) Thread.currentThread().interrupt();
            MatrixCursor result = new MatrixCursor(new String[] {
                    OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED});
            result.addRow(new Object[] {
                    after ? afterSize : beforeSize, after ? afterModified : beforeModified});
            return result;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
