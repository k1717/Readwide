package com.readwide.manager;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.readwide.manager.model.SafDocumentEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Provider-only operations. New destinations never overwrite an existing document. */
final class SafDocumentOperations {
    enum Result { DONE, SOURCE_REMAINS, DENIED, CHANGED, CONFLICT, FAILED, CLEANUP_REQUIRED }
    private static final int MAX_ENTRIES = 100000;
    private static final int MAX_DEPTH = 128;
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS};

    private SafDocumentOperations() { }

    static boolean validName(String name) {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '/' || ch == '\\' || Character.isISOControl(ch)) return false;
        }
        return true;
    }

    static Result createFolder(Context context, Uri tree, Uri parent, String name) {
        if (!SafStorageAccess.hasPersistedWritePermission(context, tree)) return Result.DENIED;
        try { return createFolder(context.getContentResolver(), tree, parent, name); }
        catch (SecurityException denied) { return Result.DENIED; }
        catch (Exception failed) { return Result.FAILED; }
    }

    static Result createFolder(ContentResolver resolver, Uri tree, Uri parent, String name) throws IOException {
        if (!validName(name)) return Result.FAILED;
        requireCreate(resolver, tree, parent);
        if (hasName(resolver, tree, parent, name, null)) return Result.CONFLICT;
        checkpoint();
        try {
            Uri created = DocumentsContract.createDocument(resolver, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (created == null) return Result.CLEANUP_REQUIRED;
            SafDocumentEntry result = read(resolver, tree, created);
            return result.isDirectory() && name.equals(result.getName())
                    && containsDocument(resolver, tree, parent, created) ? Result.DONE : Result.CLEANUP_REQUIRED;
        } catch (Exception | OutOfMemoryError | StackOverflowError unavailable) {
            return Result.CLEANUP_REQUIRED;
        }
    }

    static Result rename(Context context, Uri tree, Uri parent, SafDocumentEntry selected, String name) {
        if (!SafStorageAccess.hasPersistedWritePermission(context, tree)) return Result.DENIED;
        try { return rename(context.getContentResolver(), tree, parent, selected, name); }
        catch (SecurityException denied) { return Result.DENIED; }
        catch (Exception failed) { return Result.FAILED; }
    }

    static Result rename(ContentResolver resolver, Uri tree, Uri parent,
                         SafDocumentEntry selected, String name) throws IOException {
        if (!validName(name) || !SafDocumentDeletion.isChildUri(tree, selected)) return Result.FAILED;
        SafDocumentEntry fresh = read(resolver, tree, selected.getUri());
        if (!sameSnapshot(selected, fresh)) return Result.CHANGED;
        if ((fresh.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) == 0) return Result.DENIED;
        if (hasName(resolver, tree, parent, name, selected.getDocumentId())) return Result.CONFLICT;
        if (!containsDocument(resolver, tree, parent, selected.getUri())
                || !sameSnapshot(selected, read(resolver, tree, selected.getUri()))) return Result.CHANGED;
        checkpoint();
        try {
            Uri renamed = DocumentsContract.renameDocument(resolver, selected.getUri(), name);
            if (renamed == null) return Result.CLEANUP_REQUIRED;
            SafDocumentEntry result = read(resolver, tree, renamed);
            // Renaming an extension can change the provider's MIME type and document ID.
            return name.equals(result.getName()) && selected.isDirectory() == result.isDirectory()
                    && (selected.isDirectory() || !selected.isSizeKnown()
                        || result.isSizeKnown() && selected.getSize() == result.getSize())
                    && containsDocument(resolver, tree, parent, renamed)
                    ? Result.DONE : Result.CLEANUP_REQUIRED;
        } catch (Exception | OutOfMemoryError | StackOverflowError unavailable) {
            return Result.CLEANUP_REQUIRED;
        }
    }

    static Result transfer(Context context, Uri sourceTree, Uri sourceParent, SafDocumentEntry source,
                           Uri targetTree, Uri targetParent, boolean move) {
        if (!SafStorageAccess.hasPersistedReadPermission(context, sourceTree)
                || !SafStorageAccess.hasPersistedWritePermission(context, targetTree)
                || (move && !SafStorageAccess.hasPersistedWritePermission(context, sourceTree))) return Result.DENIED;
        return transfer(context.getContentResolver(), sourceTree, sourceParent, source, targetTree, targetParent, move);
    }

    static Result transfer(ContentResolver resolver, Uri sourceTree, Uri sourceParent, SafDocumentEntry source,
                           Uri targetTree, Uri targetParent, boolean move) {
        boolean verified = false;
        List<Node> inventory = new ArrayList<>();
        try {
            if (!SafDocumentDeletion.isChildUri(sourceTree, source)) return Result.FAILED;
            if (move && sameDocument(sourceParent, targetParent)) return Result.CONFLICT;
            requireCreate(resolver, targetTree, targetParent);
            SafDocumentEntry fresh = selectedSource(resolver, sourceTree, sourceParent, source);
            if (fresh == null) return Result.CHANGED;
            inventory = inventory(resolver, sourceTree, fresh);
            for (Node node : inventory) {
                if (sameDocument(node.entry.getUri(), targetParent)) return Result.CONFLICT;
            }
            if (hasName(resolver, targetTree, targetParent, source.getName(), null)) return Result.CONFLICT;
            SafDocumentEntry ready = selectedSource(resolver, sourceTree, sourceParent, source);
            if (ready == null || hasIncompleteByteFlags(ready)) return Result.CHANGED;
            if (move
                    && (ready.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_MOVE) != 0
                    && java.util.Objects.equals(source.getUri().getAuthority(), targetParent.getAuthority())) {
                if (!matchesInventory(resolver, sourceTree, inventory)) return Result.CHANGED;
                ready = selectedSource(resolver, sourceTree, sourceParent, source);
                if (ready == null || hasIncompleteByteFlags(ready)
                        || (ready.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_MOVE) == 0) {
                    return Result.CHANGED;
                }
                return nativeMove(resolver, source, sourceParent, targetTree, targetParent);
            }
            if (move && !ready.isDirectory()
                    && (ready.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) return Result.DENIED;
            byte[] buffer = new byte[65536];
            byte[] verification = new byte[65536];
            for (Node node : inventory) {
                checkpoint();
                SafDocumentEntry current = read(resolver, sourceTree, node.entry.getUri());
                if (!validName(node.entry.getName()) || !sameSnapshot(node.entry, current)
                        || hasIncompleteByteFlags(current)) throw new IOException("Source changed");
                Uri parent = node.parent == null ? targetParent : node.parent.created;
                node.createdParent = parent;
                node.creationAttempted = true;
                node.created = DocumentsContract.createDocument(resolver, parent,
                        node.entry.getMimeType(), node.entry.getName());
                if (node.created == null) throw new IOException("Cannot create destination");
                SafDocumentEntry output = read(resolver, targetTree, node.created);
                node.createdEntry = output;
                if (!node.entry.getName().equals(output.getName()) || !node.entry.getMimeType().equals(output.getMimeType())) {
                    throw new IOException("Provider changed destination name or type");
                }
                if ((output.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) {
                    throw new IOException("Destination cannot be cleaned up");
                }
                if (node.entry.isDirectory()) {
                    if (!output.isDirectory()) throw new IOException("Destination type changed");
                } else {
                    if ((output.getFlags() & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0) {
                        throw new IOException("Destination is read only");
                    }
                    long copied = copyFile(resolver, node.entry, node.created, buffer);
                    SafDocumentEntry written = read(resolver, targetTree, node.created);
                    if (!sameIdentity(output, written)
                            || (written.isSizeKnown() && written.getSize() != copied)) {
                        throw new IOException("Destination changed during writing");
                    }
                    compareFile(resolver, node.entry.getUri(), node.created, buffer, verification);
                    if (!sameSnapshot(written, read(resolver, targetTree, node.created))) {
                        throw new IOException("Destination changed during verification");
                    }
                    node.createdEntry = written;
                }
            }
            if (!matchesInventory(resolver, sourceTree, inventory)) throw new IOException("Source changed");
            SafDocumentEntry removable = null;
            if (move) {
                try { removable = selectedSource(resolver, sourceTree, sourceParent, source); }
                catch (Exception unavailable) { /* Keep the source when its selected parent is unavailable. */ }
            }
            if (!matchesCreated(resolver, targetTree, targetParent, inventory)) {
                throw new IOException("Destination changed before completion");
            }
            checkpoint();
            verified = true;
            if (!move) return Result.DONE;
            // A recursive provider delete cannot protect children added after the inventory.
            if (source.isDirectory() || removable == null || hasIncompleteByteFlags(removable)
                    || ((source.getFlags() | fresh.getFlags() | ready.getFlags() | removable.getFlags())
                        & DocumentsContract.Document.FLAG_SUPPORTS_REMOVE) != 0) {
                return Result.SOURCE_REMAINS;
            }
            // Retain the complete destination if deleting the source fails or is interrupted.
            SafDocumentDeletion.Result deleted = SafDocumentDeletion.deleteCopiedSource(
                    resolver, sourceTree, sourceParent, removable);
            return deleted == SafDocumentDeletion.Result.DELETED ? Result.DONE : Result.SOURCE_REMAINS;
        } catch (SecurityException denied) {
            return verified ? Result.SOURCE_REMAINS : cleanupResult(resolver, targetTree, inventory, Result.DENIED);
        } catch (Exception | OutOfMemoryError | StackOverflowError failure) {
            return verified ? Result.SOURCE_REMAINS : cleanupResult(resolver, targetTree, inventory, Result.FAILED);
        }
    }

    private static SafDocumentEntry selectedSource(ContentResolver resolver, Uri tree, Uri parent,
                                                    SafDocumentEntry expected) throws IOException {
        if (!containsDocument(resolver, tree, parent, expected.getUri())) return null;
        SafDocumentEntry current = read(resolver, tree, expected.getUri());
        return sameSnapshot(expected, current) ? current : null;
    }

    private static boolean hasIncompleteByteFlags(SafDocumentEntry entry) {
        return (entry.getFlags() & (DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
                | DocumentsContract.Document.FLAG_PARTIAL)) != 0;
    }

    private static Result nativeMove(ContentResolver resolver, SafDocumentEntry source, Uri parent,
                                     Uri targetTree, Uri targetParent) {
        try {
            checkpoint();
            Uri moved = DocumentsContract.moveDocument(resolver, source.getUri(), parent, targetParent);
            if (moved == null || !java.util.Objects.equals(moved.getAuthority(), targetTree.getAuthority())) return Result.CLEANUP_REQUIRED;
            Uri scoped = DocumentsContract.buildDocumentUriUsingTree(targetTree, DocumentsContract.getDocumentId(moved));
            SafDocumentEntry result = read(resolver, targetTree, scoped);
            if (!source.getName().equals(result.getName()) || !source.getMimeType().equals(result.getMimeType())) return Result.CLEANUP_REQUIRED;
            for (SafDocumentEntry child : children(resolver, targetTree, targetParent)) {
                if (sameDocument(child.getUri(), scoped)) {
                    Uri sourceTree = DocumentsContract.buildTreeDocumentUri(source.getUri().getAuthority(),
                            DocumentsContract.getTreeDocumentId(source.getUri()));
                    for (SafDocumentEntry original : children(resolver, sourceTree, parent)) {
                        if (sameDocument(original.getUri(), source.getUri())) return Result.SOURCE_REMAINS;
                    }
                    return Result.DONE;
                }
            }
        } catch (Exception | OutOfMemoryError | StackOverflowError failure) { return Result.CLEANUP_REQUIRED; }
        return Result.CLEANUP_REQUIRED;
    }

    private static Result cleanupResult(ContentResolver resolver, Uri tree, List<Node> inventory, Result failure) {
        boolean interrupted = Thread.interrupted();
        boolean clean = true;
        try {
            for (int i = inventory.size() - 1; i >= 0; i--) {
                Node node = inventory.get(i);
                if (node.created == null) {
                    if (node.creationAttempted) clean = false;
                    continue;
                }
                try {
                    SafDocumentEntry current = read(resolver, tree, node.created);
                    if (node.createdEntry == null) { clean = false; continue; }
                    if (!containsDocument(resolver, tree, node.createdParent, node.created)) {
                        clean = false;
                        continue;
                    }
                    if (current.isDirectory()) {
                        if (!node.createdEntry.isDirectory()
                                || !node.createdEntry.getMimeType().equals(current.getMimeType())
                                || !node.createdEntry.getDocumentId().equals(current.getDocumentId())
                                || !node.createdEntry.getName().equals(current.getName())
                                || !children(resolver, tree, node.created).isEmpty()) { clean = false; continue; }
                    } else if (!sameSnapshot(node.createdEntry, current)) { clean = false; continue; }
                    if (SafDocumentDeletion.delete(resolver, true, tree, current) != SafDocumentDeletion.Result.DELETED) clean = false;
                } catch (Exception | OutOfMemoryError | StackOverflowError unavailable) { clean = false; }
            }
            return clean ? failure : Result.CLEANUP_REQUIRED;
        } catch (Exception | OutOfMemoryError | StackOverflowError unavailable) { return Result.CLEANUP_REQUIRED; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static long copyFile(ContentResolver resolver, SafDocumentEntry source, Uri target,
                                 byte[] buffer) throws IOException {
        long copied = 0;
        try (InputStream input = resolver.openInputStream(source.getUri());
             OutputStream output = resolver.openOutputStream(target, "w")) {
            if (input == null || output == null) throw new IOException("Cannot open document stream");
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkpoint();
                if (count == 0) continue;
                if (source.isSizeKnown() && count > source.getSize() - copied) throw new IOException("Source grew");
                if (copied > Long.MAX_VALUE - count) throw new IOException("Document too large");
                output.write(buffer, 0, count);
                copied += count;
            }
            if (source.isSizeKnown() && copied != source.getSize()) throw new IOException("Source truncated");
            output.flush();
        }
        return copied;
    }

    private static void compareFile(ContentResolver resolver, Uri source, Uri target,
                                    byte[] first, byte[] second) throws IOException {
        try (InputStream input = resolver.openInputStream(source);
             InputStream output = resolver.openInputStream(target)) {
            if (input == null || output == null) throw new IOException("Cannot verify destination");
            int count;
            while ((count = input.read(first)) != -1) {
                checkpoint();
                if (count == 0) continue;
                int done = 0;
                while (done < count) {
                    checkpoint();
                    int read = output.read(second, done, count - done);
                    if (read < 0) throw new IOException("Destination truncated");
                    if (read == 0) continue;
                    done += read;
                }
                for (int i = 0; i < count; i++) if (first[i] != second[i]) throw new IOException("Destination differs");
            }
            if (output.read() != -1) throw new IOException("Destination grew");
        }
    }

    private static List<Node> inventory(ContentResolver resolver, Uri tree, SafDocumentEntry root) throws IOException {
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(root, null, 0));
        Set<String> ids = new HashSet<>();
        ids.add(root.getDocumentId());
        for (int i = 0; i < nodes.size(); i++) {
            checkpoint();
            Node node = nodes.get(i);
            if ((node.entry.getFlags() & (DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
                    | DocumentsContract.Document.FLAG_PARTIAL)) != 0) {
                throw new IOException("Virtual document cannot be copied as bytes");
            }
            if (!node.entry.isDirectory()) continue;
            node.children = children(resolver, tree, node.entry.getUri());
            for (SafDocumentEntry child : node.children) {
                if (nodes.size() >= MAX_ENTRIES || node.depth >= MAX_DEPTH || !ids.add(child.getDocumentId())) {
                    throw new IOException("Document tree is too large or cyclic");
                }
                nodes.add(new Node(child, node, node.depth + 1));
            }
        }
        return nodes;
    }

    private static boolean matchesInventory(ContentResolver resolver, Uri tree, List<Node> nodes) throws IOException {
        for (Node node : nodes) {
            checkpoint();
            if (!sameSnapshot(node.entry, read(resolver, tree, node.entry.getUri()))) return false;
            if (node.children != null) {
                List<SafDocumentEntry> now = children(resolver, tree, node.entry.getUri());
                Set<String> expected = new HashSet<>();
                for (SafDocumentEntry child : node.children) expected.add(child.getDocumentId());
                if (now.size() != expected.size()) return false;
                for (SafDocumentEntry child : now) if (!expected.remove(child.getDocumentId())) return false;
            }
        }
        return true;
    }

    private static boolean matchesCreated(ContentResolver resolver, Uri tree, Uri parent,
                                          List<Node> nodes) throws IOException {
        if (nodes.isEmpty() || !containsDocument(resolver, tree, parent, nodes.get(0).created)) return false;
        java.util.Map<String, Set<String>> expectedChildren = new java.util.HashMap<>();
        for (Node node : nodes) {
            if (node.parent != null) {
                expectedChildren.computeIfAbsent(node.parent.createdEntry.getDocumentId(),
                        ignored -> new HashSet<>()).add(node.createdEntry.getDocumentId());
            }
        }
        for (Node node : nodes) {
            checkpoint();
            SafDocumentEntry current = read(resolver, tree, node.created);
            if (!node.createdEntry.isDirectory()) {
                if (!sameSnapshot(node.createdEntry, current)) return false;
                continue;
            }
            // Our own child writes can update a directory's size or modification time.
            if (!sameIdentity(node.createdEntry, current)) return false;
            Set<String> expected = expectedChildren.get(node.createdEntry.getDocumentId());
            if (expected == null) expected = new HashSet<>();
            List<SafDocumentEntry> actual = children(resolver, tree, node.created);
            if (actual.size() != expected.size()) return false;
            for (SafDocumentEntry child : actual) if (!expected.remove(child.getDocumentId())) return false;
        }
        return true;
    }

    static boolean containsDocument(ContentResolver resolver, Uri tree, Uri parent,
                                    Uri document) throws IOException {
        for (SafDocumentEntry child : children(resolver, tree, parent)) {
            if (sameDocument(child.getUri(), document)) return true;
        }
        return false;
    }

    static SafDocumentEntry read(ContentResolver resolver, Uri tree, Uri document) throws IOException {
        requireScoped(tree, document);
        try (Cursor cursor = resolver.query(document, COLUMNS, null, null, null)) {
            if (!SafDocumentDeletion.isCompleteResult(cursor) || !cursor.moveToFirst()) {
                throw new IOException("Document unavailable or incomplete");
            }
            SafDocumentEntry entry = row(cursor, tree);
            if (!sameDocument(entry.getUri(), document) || cursor.moveToNext()
                    || !SafDocumentDeletion.isCompleteResult(cursor)) throw new IOException("Ambiguous document");
            return entry;
        }
    }

    private static List<SafDocumentEntry> children(ContentResolver resolver, Uri tree, Uri parent) throws IOException {
        requireScoped(tree, parent);
        List<SafDocumentEntry> entries = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
            if (!SafDocumentDeletion.isCompleteResult(cursor)) throw new IOException("Directory is incomplete");
            while (cursor.moveToNext()) {
                checkpoint();
                if (entries.size() >= MAX_ENTRIES) throw new IOException("Too many documents");
                entries.add(row(cursor, tree));
            }
            if (!SafDocumentDeletion.isCompleteResult(cursor)) throw new IOException("Directory is incomplete");
        }
        return entries;
    }

    private static SafDocumentEntry row(Cursor cursor, Uri tree) throws IOException {
        String id = string(cursor, COLUMNS[0]);
        String name = string(cursor, COLUMNS[1]);
        String mime = string(cursor, COLUMNS[2]);
        if (id == null || id.isEmpty() || name == null || mime == null) throw new IOException("Missing metadata");
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        return new SafDocumentEntry(uri, id, name, mime, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                number(cursor, COLUMNS[3], -1), number(cursor, COLUMNS[4], 0), (int) number(cursor, COLUMNS[5], 0));
    }

    private static String string(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static long number(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }

    static boolean sameSnapshot(SafDocumentEntry expected, SafDocumentEntry actual) {
        return sameIdentity(expected, actual)
                && (!expected.isSizeKnown() || actual.isSizeKnown() && expected.getSize() == actual.getSize())
                && (expected.getLastModified() <= 0 || expected.getLastModified() == actual.getLastModified());
    }

    private static boolean sameIdentity(SafDocumentEntry expected, SafDocumentEntry actual) {
        return expected.getDocumentId().equals(actual.getDocumentId())
                && expected.getName().equals(actual.getName()) && expected.getMimeType().equals(actual.getMimeType());
    }

    private static void requireCreate(ContentResolver resolver, Uri tree, Uri parent) throws IOException {
        SafDocumentEntry entry = read(resolver, tree, parent);
        if (!entry.isDirectory() || (entry.getFlags() & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
            throw new IOException("Destination does not support creation");
        }
    }

    private static boolean hasName(ContentResolver resolver, Uri tree, Uri parent, String name, String except) throws IOException {
        for (SafDocumentEntry entry : children(resolver, tree, parent)) {
            if (!entry.getDocumentId().equals(except) && entry.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    static boolean sameDocument(Uri first, Uri second) {
        return first != null && second != null && java.util.Objects.equals(first.getAuthority(), second.getAuthority())
                && DocumentsContract.getDocumentId(first).equals(DocumentsContract.getDocumentId(second));
    }

    private static void requireScoped(Uri tree, Uri document) throws IOException {
        if (tree == null || document == null || !"content".equals(tree.getScheme())
                || !DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(document)).equals(document)) {
            throw new IOException("Document is outside selected tree URI");
        }
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Document operation cancelled");
    }

    private static final class Node {
        final SafDocumentEntry entry;
        final Node parent;
        final int depth;
        Uri created;
        boolean creationAttempted;
        Uri createdParent;
        SafDocumentEntry createdEntry;
        List<SafDocumentEntry> children;
        Node(SafDocumentEntry entry, Node parent, int depth) { this.entry = entry; this.parent = parent; this.depth = depth; }
    }
}
