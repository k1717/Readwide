package com.textview.reader.model;

public final class CachedRestoreTarget {
    public final int charPosition;
    public final int displayPage;
    public final int totalPages;

    public CachedRestoreTarget(int charPosition, int displayPage, int totalPages) {
        this.charPosition = Math.max(0, charPosition);
        this.displayPage = Math.max(0, displayPage);
        this.totalPages = Math.max(0, totalPages);
    }
}
