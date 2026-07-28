package com.readwide.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Persisted Storage Access Framework tree state shared by the main drawer and
 * the URI-backed fallback browser.
 *
 * <p>No filesystem path is derived from a document URI. Providers are addressed
 * only through {@link DocumentsContract}, which keeps this path usable across
 * Android versions and OEM storage implementations.</p>
 */
final class SafStorageAccess {
    private static final String PREFS = "saf_storage_access";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_LABEL = "tree_label";

    private SafStorageAccess() {}

    /**
     * Best-effort persistence for a single document returned by OpenDocument.
     *
     * <p>Several OEM and third-party providers return a usable one-shot grant
     * but reject persistable grants. Callers must still open that document for
     * the current activity result, so persistence failure is deliberately
     * non-fatal.</p>
     */
    static void tryTakePersistableReadGrant(
            @NonNull Context context, @NonNull Uri documentUri) {
        try {
            context.getContentResolver().takePersistableUriPermission(
                    documentUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Keep the one-shot ActivityResult permission.
        }
    }

    /**
     * Persists a selected document tree, preferring read/write but accepting a
     * read-only provider. Returns false only when no persistent read grant can
     * be retained.
     */
    static boolean takePersistableTreeGrant(
            @NonNull Context context, @NonNull Uri treeUri) {
        try {
            context.getContentResolver().takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return true;
        } catch (RuntimeException writeDenied) {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return true;
            } catch (RuntimeException readDenied) {
                return false;
            }
        }
    }

    static void rememberTree(@NonNull Context context, @NonNull Uri treeUri) {
        String label;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            label = queryDisplayName(context, rootDocument);
        } catch (Exception ignored) {
            label = queryDisplayName(context, treeUri);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_TREE_LABEL, label)
                .apply();
    }

    static void forgetTree(@NonNull Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_TREE_URI)
                .remove(KEY_TREE_LABEL)
                .apply();
    }

    static void releasePreviousGrantIfDifferent(
            @NonNull Context context, @NonNull Uri replacement) {
        Uri previous = getRememberedTree(context);
        if (previous == null || previous.equals(replacement)) return;
        releaseGrant(context, previous);
    }

    private static void releaseGrant(@NonNull Context context, @NonNull Uri treeUri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception writeWasNotGranted) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // Provider may already have revoked or forgotten the old grant.
            }
        }
    }

    @Nullable
    static Uri getUsableTree(@NonNull Context context) {
        Uri treeUri = getRememberedTree(context);
        if (treeUri == null) return null;
        if (!hasPersistedReadPermission(context, treeUri)) {
            forgetTree(context);
            return null;
        }
        return treeUri;
    }

    @Nullable
    static Uri getRememberedTree(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_TREE_URI, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Exception ignored) {
            forgetTree(context);
            return null;
        }
    }

    @Nullable
    static String getRememberedLabel(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TREE_LABEL, null);
    }

    static boolean hasPersistedReadPermission(@NonNull Context context, @NonNull Uri treeUri) {
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission != null
                        && permission.isReadPermission()
                        && treeUri.equals(permission.getUri())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // A broken provider must not make the main screen fail to start.
        }
        return false;
    }

    @NonNull
    static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(
                uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (Exception ignored) {
            // Some providers do not expose metadata for the tree URI itself.
        }
        return context.getString(R.string.internal_storage);
    }
}
