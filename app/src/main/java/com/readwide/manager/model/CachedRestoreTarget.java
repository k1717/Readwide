package com.readwide.manager.model;

public final class CachedRestoreTarget {
    public final int charPosition;
    public final int displayPage;
    public final int totalPages;
    public final String anchorTextBefore;
    public final String anchorTextAfter;
    public final boolean preferAnchorPartition;

    public CachedRestoreTarget(int charPosition, int displayPage, int totalPages) {
        this(charPosition, displayPage, totalPages, "", "", false);
    }

    public CachedRestoreTarget(int charPosition, int displayPage, int totalPages,
                               String anchorTextBefore, String anchorTextAfter,
                               boolean preferAnchorPartition) {
        this.charPosition = Math.max(0, charPosition);
        this.displayPage = Math.max(0, displayPage);
        this.totalPages = Math.max(0, totalPages);
        this.anchorTextBefore = anchorTextBefore != null ? anchorTextBefore : "";
        this.anchorTextAfter = anchorTextAfter != null ? anchorTextAfter : "";
        this.preferAnchorPartition = preferAnchorPartition;
    }
}
