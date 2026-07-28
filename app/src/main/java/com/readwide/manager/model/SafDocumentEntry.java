package com.readwide.manager.model;

import android.net.Uri;

import androidx.annotation.NonNull;

/** Immutable metadata row returned by a DocumentsProvider child query. */
public final class SafDocumentEntry {
    @NonNull private final Uri uri;
    @NonNull private final String documentId;
    @NonNull private final String name;
    @NonNull private final String mimeType;
    private final boolean directory;
    private final long size;
    private final long lastModified;

    public SafDocumentEntry(@NonNull Uri uri,
                            @NonNull String documentId,
                            @NonNull String name,
                            @NonNull String mimeType,
                            boolean directory,
                            long size,
                            long lastModified) {
        this.uri = uri;
        this.documentId = documentId;
        this.name = name;
        this.mimeType = mimeType;
        this.directory = directory;
        this.size = Math.max(0L, size);
        this.lastModified = Math.max(0L, lastModified);
    }

    @NonNull public Uri getUri() { return uri; }
    @NonNull public String getDocumentId() { return documentId; }
    @NonNull public String getName() { return name; }
    @NonNull public String getMimeType() { return mimeType; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }
    public long getLastModified() { return lastModified; }
}
