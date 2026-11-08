package com.readwide.manager;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.readwide.manager.model.SafDocumentEntry;

import java.io.FileNotFoundException;

/** Deletes the selected provider document, never its local viewer cache. */
final class SafDocumentDeletion {
    enum Result { DELETED, NEEDS_PERMISSION, UNSUPPORTED, CHANGED, FAILED, UNCONFIRMED }

    private SafDocumentDeletion() { }

    static Result delete(Context context, Uri tree, SafDocumentEntry entry) {
        return delete(context.getContentResolver(),
                SafStorageAccess.hasPersistedWritePermission(context, tree), tree, entry);
    }

    static Result delete(ContentResolver resolver, boolean writable, Uri tree,
                         SafDocumentEntry entry) {
        return delete(resolver, writable, tree, entry, false, null);
    }

    static Result deleteCopiedSource(ContentResolver resolver, Uri tree, Uri parent, SafDocumentEntry entry) {
        return delete(resolver, true, tree, entry, true, parent);
    }

    private static Result delete(ContentResolver resolver, boolean writable, Uri tree,
                                 SafDocumentEntry entry, boolean copiedSource, Uri sourceParent) {
        if (!writable) return Result.NEEDS_PERMISSION;
        if (!isChildUri(tree, entry)) return Result.FAILED;
        if (copiedSource && (entry.isDirectory() || unsafeCopiedSourceFlags(entry.getFlags()))) {
            return Result.UNSUPPORTED;
        }
        Uri document = entry.getUri();
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try {
            if (copiedSource && !SafDocumentOperations.containsDocument(resolver, tree, sourceParent, document)) {
                return Result.CHANGED;
            }
            try (Cursor row = resolver.query(document, columns, null, null, null)) {
                if (!isCompleteResult(row)) return Result.FAILED;
                if (row == null || !row.moveToFirst()) return Result.CHANGED;
                int id = row.getColumnIndex(columns[0]);
                int name = row.getColumnIndex(columns[1]);
                int mime = row.getColumnIndex(columns[2]);
                int flags = row.getColumnIndex(columns[3]);
                if (id < 0 || name < 0 || mime < 0
                        || !entry.getDocumentId().equals(row.getString(id))
                        || !entry.getName().equals(row.getString(name))
                        || !entry.getMimeType().equals(row.getString(mime))
                        || (entry.isSizeKnown() && metadataChanged(row, columns[4], entry.getSize()))
                        || (entry.getLastModified() > 0
                            && metadataChanged(row, columns[5], entry.getLastModified()))) return Result.CHANGED;
                if (flags < 0 || row.isNull(flags)
                        || (row.getInt(flags) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) {
                    return Result.UNSUPPORTED;
                }
                if (copiedSource && unsafeCopiedSourceFlags(row.getInt(flags))) return Result.UNSUPPORTED;
                if (row.moveToNext()) return Result.CHANGED;
                if (!isCompleteResult(row)) return Result.FAILED;
            }
        } catch (SecurityException denied) {
            return Result.NEEDS_PERMISSION;
        } catch (Exception | OutOfMemoryError | StackOverflowError failed) {
            return Result.FAILED;
        }

        if (Thread.currentThread().isInterrupted()) return Result.FAILED;
        try {
            DocumentsContract.deleteDocument(resolver, document);
        } catch (Exception | OutOfMemoryError | StackOverflowError uncertain) {
            // The provider may have deleted the document before its reply failed.
        }

        // Neither acknowledgement nor a failed reply establishes the final state.
        try (Cursor remaining = resolver.query(document,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return isCompleteResult(remaining) && !remaining.moveToFirst()
                    && isCompleteResult(remaining) ? Result.DELETED : Result.UNCONFIRMED;
        } catch (Exception unavailable) {
            return causedByMissingDocument(unavailable) ? Result.DELETED : Result.UNCONFIRMED;
        } catch (OutOfMemoryError | StackOverflowError unavailable) {
            return Result.UNCONFIRMED;
        }
    }

    private static boolean unsafeCopiedSourceFlags(int flags) {
        return (flags & (DocumentsContract.Document.FLAG_SUPPORTS_REMOVE
                | DocumentsContract.Document.FLAG_PARTIAL
                | DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT)) != 0;
    }

    static boolean isCompleteResult(Cursor cursor) {
        if (cursor == null) return false;
        android.os.Bundle extras = cursor.getExtras();
        return extras == null || (!extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                && !extras.containsKey(DocumentsContract.EXTRA_ERROR));
    }

    static boolean isChildUri(Uri tree, SafDocumentEntry entry) {
        try {
            if (tree == null || entry == null || !"content".equals(tree.getScheme())) return false;
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            String id = entry.getDocumentId();
            return !rootId.equals(id) && !id.isEmpty()
                    && DocumentsContract.buildDocumentUriUsingTree(tree, id).equals(entry.getUri());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean metadataChanged(Cursor row, String column, long expected) {
        int index = row.getColumnIndex(column);
        return index < 0 || row.isNull(index) || row.getLong(index) != expected;
    }

    private static boolean causedByMissingDocument(Exception error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof FileNotFoundException) return true;
        }
        return false;
    }
}
