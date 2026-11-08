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
    private final boolean sizeKnown;
    private final long lastModified;
    private final int flags;

    public SafDocumentEntry(@NonNull Uri uri,
                            @NonNull String documentId,
                            @NonNull String name,
                            @NonNull String mimeType,
                            boolean directory,
                            long size,
                            long lastModified,
                            int flags) {
        this.uri = uri;
        this.documentId = documentId;
        this.name = name;
        this.mimeType = mimeType;
        this.directory = directory;
        this.size = Math.max(0L, size);
        this.sizeKnown = size >= 0;
        this.lastModified = Math.max(0L, lastModified);
        this.flags = flags;
    }

    @NonNull public Uri getUri() { return uri; }
    @NonNull public String getDocumentId() { return documentId; }
    @NonNull public String getName() { return name; }
    @NonNull public String getMimeType() { return mimeType; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }
    public boolean isSizeKnown() { return sizeKnown; }
    public long getLastModified() { return lastModified; }
    public int getFlags() { return flags; }
}
