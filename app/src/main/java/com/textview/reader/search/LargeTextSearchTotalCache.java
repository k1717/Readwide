package com.textview.reader.search;

import androidx.annotation.NonNull;

/**
 * Tracks the cached total-match count and the in-flight count request for
 * large-TXT search.  This is intentionally path + load-generation scoped so
 * old async count results cannot be applied after a file reload.
 */
public final class LargeTextSearchTotalCache {
    private String totalFilePath = "";
    private String totalQuery = "";
    private int totalCount = -1;
    private int totalLoadGeneration = -1;

    private String inFlightFilePath = "";
    private String inFlightQuery = "";
    private int inFlightLoadGeneration = -1;

    public int get(@NonNull String filePath, int loadGeneration, @NonNull String query) {
        if (query.isEmpty()) return -1;
        if (totalCount < 0) return -1;
        if (totalLoadGeneration != loadGeneration) return -1;
        if (!filePath.equals(totalFilePath)) return -1;
        if (!query.equals(totalQuery)) return -1;
        return totalCount;
    }

    public void remember(@NonNull String filePath,
                         int loadGeneration,
                         @NonNull String query,
                         int total) {
        if (query.isEmpty() || total < 0) return;
        totalFilePath = filePath;
        totalQuery = query;
        totalCount = Math.max(0, total);
        totalLoadGeneration = loadGeneration;
    }

    public boolean isInFlight(@NonNull String filePath,
                              @NonNull String query,
                              int loadGeneration) {
        return filePath.equals(inFlightFilePath)
                && query.equals(inFlightQuery)
                && loadGeneration == inFlightLoadGeneration;
    }

    public void markInFlight(@NonNull String filePath,
                             @NonNull String query,
                             int loadGeneration) {
        inFlightFilePath = filePath;
        inFlightQuery = query;
        inFlightLoadGeneration = loadGeneration;
    }

    public void clearInFlightIf(@NonNull String filePath,
                                @NonNull String query,
                                int loadGeneration) {
        if (isInFlight(filePath, query, loadGeneration)) {
            inFlightFilePath = "";
            inFlightQuery = "";
            inFlightLoadGeneration = -1;
        }
    }

    public void clear() {
        totalFilePath = "";
        totalQuery = "";
        totalCount = -1;
        totalLoadGeneration = -1;
        inFlightFilePath = "";
        inFlightQuery = "";
        inFlightLoadGeneration = -1;
    }
}
