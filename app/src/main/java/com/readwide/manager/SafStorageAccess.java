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
 * Persisted Storage Access Framework tree state shared by Settings and the
 * URI-backed fallback browser.
 *
 * <p>No filesystem path is derived from a document URI. Providers are addressed
 * only through {@link DocumentsContract}, which keeps this path usable across
 * Android versions and OEM storage implementations.</p>
 */
final class SafStorageAccess {
    private static final String PREFS = "saf_storage_access";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_LABEL = "tree_label";
    private static final String KEY_REGISTERED_TREES = "registered_trees";
    private static final String LABEL_PREFIX = "registered_label:";
    private static final java.util.Map<Uri, Integer> pinnedTrees = new java.util.HashMap<>();
    private static long treeGeneration;

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
    private static boolean takePersistableTreeGrant(
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

    static boolean selectTree(@NonNull Context context, @NonNull Uri treeUri) {
        String label;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            label = queryDisplayName(context, rootDocument);
        } catch (Exception ignored) {
            label = queryDisplayName(context, treeUri);
        }
        return registerTreeGrant(context, treeUri, label);
    }

    private static synchronized boolean registerTreeGrant(Context context, Uri treeUri, String label) {
        if (!isTree(treeUri)) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.Set<String> trees = registeredUris(prefs);
        // Registration, removal and the last pin release share the same lock.
        if (!takePersistableTreeGrant(context, treeUri)) return false;
        trees.add(treeUri.toString());
        SharedPreferences.Editor editor = prefs.edit();
        retainLegacyLabel(prefs, editor);
        editor.putStringSet(KEY_REGISTERED_TREES, trees)
                .putString(LABEL_PREFIX + treeUri, label)
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_TREE_LABEL, label)
                .apply();
        treeGeneration++;
        return true;
    }

    static final class RegisteredTree {
        final Uri uri;
        final String label;

        RegisteredTree(Uri uri, String label) { this.uri = uri; this.label = label; }
    }

    enum Removal { REMOVED, BUSY, FAILED, NOT_REGISTERED }

    // Old installations store only the last tree. Read it as a one-item registry
    // until the first edit writes the collection and its labels together.
    private static java.util.Set<String> registeredUris(SharedPreferences prefs) {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (prefs.contains(KEY_REGISTERED_TREES)) {
            java.util.Set<String> stored = prefs.getStringSet(KEY_REGISTERED_TREES, null);
            if (stored == null) throw new IllegalStateException("Missing folder registry");
            for (String raw : stored) {
                if (raw == null || !isTree(Uri.parse(raw))) {
                    throw new IllegalStateException("Invalid registered folder");
                }
                result.add(raw);
            }
        } else {
            String legacy = prefs.getString(KEY_TREE_URI, null);
            if (legacy != null && isTree(Uri.parse(legacy))) result.add(legacy);
        }
        return result;
    }

    private static boolean isTree(Uri uri) {
        return uri != null && "content".equals(uri.getScheme())
                && uri.getAuthority() != null && !uri.getAuthority().isEmpty()
                && DocumentsContract.isTreeUri(uri);
    }

    private static void retainLegacyLabel(SharedPreferences prefs, SharedPreferences.Editor editor) {
        if (prefs.contains(KEY_REGISTERED_TREES)) return;
        String legacy = prefs.getString(KEY_TREE_URI, null);
        if (legacy != null) editor.putString(LABEL_PREFIX + legacy,
                prefs.getString(KEY_TREE_LABEL, null));
    }

    @NonNull
    static synchronized java.util.List<RegisteredTree> getRegisteredTrees(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.List<RegisteredTree> result = new java.util.ArrayList<>();
        for (String raw : registeredUris(prefs)) {
            String label = prefs.getString(LABEL_PREFIX + raw, null);
            if (label == null && raw.equals(prefs.getString(KEY_TREE_URI, null))) {
                label = prefs.getString(KEY_TREE_LABEL, null);
            }
            Uri uri = Uri.parse(raw);
            if (label == null || label.trim().isEmpty()) label = DocumentsContract.getTreeDocumentId(uri);
            result.add(new RegisteredTree(uri, label));
        }
        result.sort((a, b) -> {
            int name = a.label.compareToIgnoreCase(b.label);
            return name != 0 ? name : a.uri.toString().compareTo(b.uri.toString());
        });
        return result;
    }

    static synchronized boolean useRegisteredTree(Context context, Uri tree) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (tree == null || !registeredUris(prefs).contains(tree.toString())
                || !hasPersistedReadPermission(context, tree)) return false;
        for (RegisteredTree entry : getRegisteredTrees(context)) {
            if (!entry.uri.equals(tree)) continue;
            prefs.edit().putString(KEY_TREE_URI, tree.toString())
                    .putString(KEY_TREE_LABEL, entry.label).apply();
            treeGeneration++;
            return true;
        }
        return false;
    }

    static synchronized Removal removeRegisteredTree(Context context, Uri tree) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.Set<String> trees = registeredUris(prefs);
        if (tree == null || !trees.contains(tree.toString())) return Removal.NOT_REGISTERED;
        if (isPinned(tree)) return Removal.BUSY;
        if (!releaseGrant(context, tree)) return Removal.FAILED;
        trees.remove(tree.toString());
        SharedPreferences.Editor editor = prefs.edit();
        retainLegacyLabel(prefs, editor);
        editor.putStringSet(KEY_REGISTERED_TREES, trees).remove(LABEL_PREFIX + tree);
        if (tree.equals(getRememberedTree(context))) {
            editor.remove(KEY_TREE_URI).remove(KEY_TREE_LABEL);
        }
        editor.apply();
        treeGeneration++;
        return Removal.REMOVED;
    }

    private static synchronized void forgetTree(@NonNull Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_TREE_URI)
                .remove(KEY_TREE_LABEL)
                .apply();
        treeGeneration++;
    }

    static synchronized void pinTree(Uri tree) {
        if (tree == null) return;
        pinnedTrees.put(tree, pinnedTrees.getOrDefault(tree, 0) + 1);
    }

    private static synchronized boolean isPinned(Uri tree) { return pinnedTrees.containsKey(tree); }

    static synchronized void unpinTree(Context context, Uri tree) {
        if (tree == null) return;
        int count = pinnedTrees.getOrDefault(tree, 0);
        if (count == 0) return;
        if (count > 1) pinnedTrees.put(tree, count - 1);
        else pinnedTrees.remove(tree);
        if (!isPinned(tree)) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!registeredUris(prefs).contains(tree.toString())) releaseGrant(context, tree);
            } catch (RuntimeException unreadableRegistry) {
                // Unknown registration state must not revoke a saved folder.
            }
        }
    }

    private static boolean releaseGrant(@NonNull Context context, @NonNull Uri treeUri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return true;
        } catch (Exception writeWasNotGranted) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return true;
            } catch (Exception ignored) {
                // A missing grant is already removed; an unreadable grant list is not proof.
                try {
                    for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
                        if (grant != null && treeUri.equals(grant.getUri())
                                && (grant.isReadPermission() || grant.isWritePermission())) return false;
                    }
                    return true;
                } catch (RuntimeException unavailable) { return false; }
            }
        }
    }

    @Nullable
    static Uri getUsableTree(@NonNull Context context) {
        Uri treeUri;
        long generation;
        synchronized (SafStorageAccess.class) {
            treeUri = getRememberedTree(context);
            generation = treeGeneration;
        }
        if (treeUri == null) return null;
        boolean readable = hasPersistedReadPermission(context, treeUri);
        synchronized (SafStorageAccess.class) {
            if (generation != treeGeneration || !treeUri.equals(getRememberedTree(context))) return null;
            if (!readable) {
                // Keep the registration so permission can be renewed after disconnect/revocation.
                return null;
            }
            return treeUri;
        }
    }

    @Nullable
    static synchronized Uri getRememberedTree(@NonNull Context context) {
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
        return hasPersistedPermission(context, treeUri, false);
    }

    static boolean hasPersistedWritePermission(@NonNull Context context, @NonNull Uri treeUri) {
        return hasPersistedPermission(context, treeUri, true);
    }

    private static boolean hasPersistedPermission(Context context, Uri treeUri, boolean requireWrite) {
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission != null
                        && permission.isReadPermission()
                        && (!requireWrite || permission.isWritePermission())
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
