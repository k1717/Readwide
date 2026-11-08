package com.readwide.manager;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.readwide.manager.model.SafDocumentEntry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileNotFoundException;

import static org.junit.Assert.*;

/** Fake provider only; this does not access or delete files on a storage volume. */
@RunWith(AndroidJUnit4.class)
public class SafDocumentDeletionInstrumentedTest {
    private static final String AUTHORITY = "com.readwide.test.documents";
    private static final Uri TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "card:Books");
    private static final Uri PARENT = DocumentsContract.buildDocumentUriUsingTree(TREE, "card:Books");
    private static final String ID = "opaque-book-id";

    private static SafDocumentEntry entry(Uri tree, String id, String name, String mime) {
        return new SafDocumentEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                id, name, mime, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                12, 100, DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
    }

    private static SafDocumentEntry book() { return entry(TREE, ID, "测试.txt", "text/plain"); }

    @Test public void opaqueIdsRemainProviderOwned() {
        assertTrue(SafDocumentDeletion.isChildUri(TREE, book()));
    }

    @Test public void treeRootCannotBeDeleted() {
        assertFalse(SafDocumentDeletion.isChildUri(TREE,
                entry(TREE, "card:Books", "Books", DocumentsContract.Document.MIME_TYPE_DIR)));
    }

    @Test public void anotherTreeOrAuthorityIsRejected() {
        assertFalse(SafDocumentDeletion.isChildUri(TREE, entry(
                DocumentsContract.buildTreeDocumentUri(AUTHORITY, "other"), ID, "测试.txt", "text/plain")));
        assertFalse(SafDocumentDeletion.isChildUri(TREE, entry(
                DocumentsContract.buildTreeDocumentUri("other.provider", "card:Books"), ID, "测试.txt", "text/plain")));
    }

    @Test public void rawPathsAndUriIdMismatchAreRejected() {
        assertFalse(SafDocumentDeletion.isChildUri(Uri.parse("file:///storage/card"), book()));
        assertFalse(SafDocumentDeletion.isChildUri(TREE, new SafDocumentEntry(
                book().getUri(), "different", "测试.txt", "text/plain", false, 12, 100, 4)));
    }

    // ContentResolver.wrap is a test-only API 29 convenience. Production uses
    // the Activity's resolver and API 19/21 DocumentsContract calls.
    @SdkSuppress(minSdkVersion = 29)
    @Test public void readOnlyGrantNeverQueriesOrDeletes() {
        Provider provider = new Provider();
        assertEquals(SafDocumentDeletion.Result.NEEDS_PERMISSION, run(provider, false));
        assertEquals(0, provider.queries);
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void writeFlagAloneDoesNotPermitDeletion() {
        Provider provider = new Provider();
        provider.flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        assertEquals(SafDocumentDeletion.Result.UNSUPPORTED, run(provider, true));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void copiedSourceRemoveCapabilityInFinalQueryPreventsGlobalDelete() {
        Provider provider = new Provider();
        provider.flags |= DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
        assertEquals(SafDocumentDeletion.Result.UNSUPPORTED,
                SafDocumentDeletion.deleteCopiedSource(ContentResolver.wrap(provider), TREE, PARENT, book()));
        assertEquals(2, provider.queries);
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void copiedSourcePartialFlagInFinalQueryPreventsDelete() {
        Provider provider = new Provider();
        provider.flags |= DocumentsContract.Document.FLAG_PARTIAL;
        assertEquals(SafDocumentDeletion.Result.UNSUPPORTED,
                SafDocumentDeletion.deleteCopiedSource(ContentResolver.wrap(provider), TREE, PARENT, book()));
        assertEquals(2, provider.queries);
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void copiedSourceVirtualFlagInFinalQueryPreventsDelete() {
        Provider provider = new Provider();
        provider.flags |= DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT;
        assertEquals(SafDocumentDeletion.Result.UNSUPPORTED,
                SafDocumentDeletion.deleteCopiedSource(ContentResolver.wrap(provider), TREE, PARENT, book()));
        assertEquals(2, provider.queries);
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void explicitDeleteStillAllowsProviderRemovablePartialOrVirtualDocuments() {
        int[] flags = {DocumentsContract.Document.FLAG_SUPPORTS_REMOVE,
                DocumentsContract.Document.FLAG_PARTIAL, DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT};
        for (int flag : flags) {
            Provider provider = new Provider();
            provider.flags |= flag;
            assertEquals(SafDocumentDeletion.Result.DELETED, run(provider, true));
            assertEquals(1, provider.deletes);
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void ordinaryVerifiedCopySourceCanStillBeDeleted() {
        Provider provider = new Provider();
        assertEquals(SafDocumentDeletion.Result.DELETED,
                SafDocumentDeletion.deleteCopiedSource(ContentResolver.wrap(provider), TREE, PARENT, book()));
        assertEquals(1, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void copiedDirectoryNeverUsesRecursiveProviderDelete() {
        Provider provider = new Provider();
        assertEquals(SafDocumentDeletion.Result.UNSUPPORTED,
                SafDocumentDeletion.deleteCopiedSource(ContentResolver.wrap(provider), TREE, PARENT,
                        entry(TREE, ID, "Folder", DocumentsContract.Document.MIME_TYPE_DIR)));
        assertEquals(0, provider.queries);
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void missingOrChangedEntryIsNotDeleted() {
        Provider provider = new Provider();
        provider.removed = true;
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        assertEquals(0, provider.deletes);
        provider.removed = false;
        provider.name = "renamed.txt";
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void changedSizeOrDateStopsDeletion() {
        Provider provider = new Provider();
        provider.size = 13;
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        provider.size = 12;
        provider.modified = 101;
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void knownSizeBecomingUnavailableStopsDeletion() {
        Provider provider = new Provider();
        provider.nullSize = true;
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        assertEquals(0, provider.deletes);
        provider = new Provider();
        provider.omitSize = true;
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void knownDateBecomingUnavailableStopsDeletion() {
        Provider provider = new Provider();
        provider.nullModified = true;
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        assertEquals(0, provider.deletes);
        provider = new Provider();
        provider.omitModified = true;
        assertEquals(SafDocumentDeletion.Result.CHANGED, run(provider, true));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void originallyUnknownMetadataDoesNotRequireNewValues() {
        Provider provider = new Provider();
        provider.nullSize = true;
        provider.omitModified = true;
        SafDocumentEntry unknown = new SafDocumentEntry(book().getUri(), ID, "测试.txt", "text/plain",
                false, -1, 0, DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
        assertEquals(SafDocumentDeletion.Result.DELETED,
                SafDocumentDeletion.delete(ContentResolver.wrap(provider), true, TREE, unknown));
        assertEquals(1, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void previouslyEmptyFileGrowingStopsDeletion() {
        Provider provider = new Provider();
        SafDocumentEntry empty = new SafDocumentEntry(book().getUri(), ID, "测试.txt", "text/plain",
                false, 0, 100, DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
        assertEquals(SafDocumentDeletion.Result.CHANGED,
                SafDocumentDeletion.delete(ContentResolver.wrap(provider), true, TREE, empty));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void permissionFailureDuringMutationIsUnconfirmed() {
        Provider provider = new Provider();
        provider.denyDelete = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
        assertFalse(provider.removed);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void failedProviderIsNotSuccess() {
        Provider provider = new Provider();
        provider.failDelete = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
        assertFalse(provider.removed);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void deletionCommittedBeforeFailedReplyIsConfirmedByAbsence() {
        Provider provider = new Provider();
        provider.throwAfterDelete = true;
        assertEquals(SafDocumentDeletion.Result.DELETED, run(provider, true));
        assertTrue(provider.removed);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void failedReplyAndUnavailableVerificationRemainUnconfirmed() {
        Provider provider = new Provider();
        provider.throwAfterDelete = true;
        provider.failVerification = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
        assertTrue(provider.removed);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void loadingMetadataCannotAuthorizeDelete() {
        Provider provider = new Provider();
        provider.loadingBefore = true;
        assertEquals(SafDocumentDeletion.Result.FAILED, run(provider, true));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void errorMetadataCannotAuthorizeDelete() {
        Provider provider = new Provider();
        provider.errorBefore = true;
        assertEquals(SafDocumentDeletion.Result.FAILED, run(provider, true));
        assertEquals(0, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void loadingEmptyResultCannotConfirmDelete() {
        Provider provider = new Provider();
        provider.loadingAfter = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
        assertTrue(provider.removed);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void errorEmptyResultCannotConfirmDelete() {
        Provider provider = new Provider();
        provider.errorAfter = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
        assertTrue(provider.removed);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void providerAcknowledgementWithoutRemovalIsUnconfirmed() {
        Provider provider = new Provider();
        provider.retain = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
        assertEquals(1, provider.deletes);
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void nullOrFailedVerificationIsUnconfirmed() {
        Provider provider = new Provider();
        provider.nullAfterDelete = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
        provider = new Provider();
        provider.failVerification = true;
        assertEquals(SafDocumentDeletion.Result.UNCONFIRMED, run(provider, true));
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void emptyResultOrExplicitMissingDocumentConfirmsDeletion() {
        Provider provider = new Provider();
        assertEquals(SafDocumentDeletion.Result.DELETED, run(provider, true));
        assertEquals(1, provider.deletes);
        assertEquals(book().getUri(), provider.deletedUri);
        provider = new Provider();
        provider.missingAfterDelete = true;
        assertEquals(SafDocumentDeletion.Result.DELETED, run(provider, true));
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void cancellationBeforeCommitDoesNotDelete() {
        Provider provider = new Provider();
        Thread.currentThread().interrupt();
        try {
            assertEquals(SafDocumentDeletion.Result.FAILED, run(provider, true));
            assertEquals(0, provider.deletes);
        } finally {
            Thread.interrupted();
        }
    }

    @android.annotation.TargetApi(29)
    private static SafDocumentDeletion.Result run(Provider provider, boolean writable) {
        return SafDocumentDeletion.delete(ContentResolver.wrap(provider), writable, TREE, book());
    }

    private static final class Provider extends ContentProvider {
        int queries, deletes;
        int flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        long size = 12, modified = 100;
        String name = "测试.txt";
        boolean removed, retain, denyDelete, failDelete, nullAfterDelete, failVerification, missingAfterDelete;
        boolean nullSize, nullModified, omitSize, omitModified;
        boolean throwAfterDelete, loadingBefore, loadingAfter, errorBefore, errorAfter;
        Uri deletedUri;

        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String s, String[] a) { throw new AssertionError("Use DocumentsContract"); }

        @Override public Cursor query(Uri uri, String[] projection, String s, String[] a, String order) {
            queries++;
            if (deletes > 0 && nullAfterDelete) return null;
            if (deletes > 0 && failVerification) throw new SecurityException();
            if (deletes > 0 && missingAfterDelete) throw new IllegalStateException(new FileNotFoundException());
            java.util.ArrayList<String> columns = new java.util.ArrayList<>();
            for (String column : projection) {
                if (omitSize && DocumentsContract.Document.COLUMN_SIZE.equals(column)) continue;
                if (omitModified && DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) continue;
                columns.add(column);
            }
            MatrixCursor result = new MatrixCursor(columns.toArray(new String[0]));
            Bundle extras = new Bundle();
            if (deletes == 0 ? loadingBefore : loadingAfter) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
            if (deletes == 0 ? errorBefore : errorAfter) extras.putString(DocumentsContract.EXTRA_ERROR, "Unavailable");
            result.setExtras(extras);
            if (!removed) {
                MatrixCursor.RowBuilder row = result.newRow();
                for (String column : columns) {
                    switch (column) {
                        case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(ID); break;
                        case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(name); break;
                        case DocumentsContract.Document.COLUMN_MIME_TYPE: row.add("text/plain"); break;
                        case DocumentsContract.Document.COLUMN_FLAGS: row.add(flags); break;
                        case DocumentsContract.Document.COLUMN_SIZE: row.add(nullSize ? null : Long.valueOf(size)); break;
                        case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row.add(nullModified ? null : Long.valueOf(modified)); break;
                        default: row.add(null);
                    }
                }
            }
            return result;
        }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            assertEquals("android:deleteDocument", method);
            deletedUri = androidx.core.os.BundleCompat.getParcelable(extras, "uri", Uri.class);
            deletes++;
            if (denyDelete) throw new SecurityException();
            if (failDelete) throw new IllegalStateException();
            removed = !retain;
            if (throwAfterDelete) throw new IllegalStateException("Reply failed after deletion");
            return new Bundle();
        }
    }
}
