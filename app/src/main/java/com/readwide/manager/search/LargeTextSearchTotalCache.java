package com.readwide.manager.search;

import androidx.annotation.NonNull;

/**
 * Tracks the cached total-match count and the in-flight count request for
 * large-TXT search.  This is intentionally path + load-generation scoped so
 * old async count results cannot be applied after a file reload.
 */
public final class LargeTextSearchTotalCache {
    private String totalFilePath = "";
    private String totalQuery = "";
    private String totalOptionsSignature = "";
    private int totalCount = -1;
    private int totalLoadGeneration = -1;

    private String inFlightFilePath = "";
    private String inFlightQuery = "";
    private String inFlightOptionsSignature = "";
    private int inFlightLoadGeneration = -1;

    public int get(@NonNull String filePath, int loadGeneration, @NonNull String query,
                   @NonNull String optionsSignature) {
        if (query.isEmpty()) return -1;
        if (totalCount < 0) return -1;
        if (totalLoadGeneration != loadGeneration) return -1;
        if (!filePath.equals(totalFilePath)) return -1;
        if (!query.equals(totalQuery)) return -1;
        if (!optionsSignature.equals(totalOptionsSignature)) return -1;
        return totalCount;
    }

    public void remember(@NonNull String filePath,
                         int loadGeneration,
                         @NonNull String query,
                         @NonNull String optionsSignature,
                         int total) {
        if (query.isEmpty() || total < 0) return;
        totalFilePath = filePath;
        totalQuery = query;
        totalOptionsSignature = optionsSignature;
        totalCount = Math.max(0, total);
        totalLoadGeneration = loadGeneration;
    }

    public boolean isInFlight(@NonNull String filePath,
                              @NonNull String query,
                              int loadGeneration,
                              @NonNull String optionsSignature) {
        return filePath.equals(inFlightFilePath)
                && query.equals(inFlightQuery)
                && loadGeneration == inFlightLoadGeneration
                && optionsSignature.equals(inFlightOptionsSignature);
    }

    public void markInFlight(@NonNull String filePath,
                             @NonNull String query,
                             int loadGeneration,
                             @NonNull String optionsSignature) {
        inFlightFilePath = filePath;
        inFlightQuery = query;
        inFlightLoadGeneration = loadGeneration;
        inFlightOptionsSignature = optionsSignature;
    }

    public void clearInFlightIf(@NonNull String filePath,
                                @NonNull String query,
                                int loadGeneration,
                                @NonNull String optionsSignature) {
        if (isInFlight(filePath, query, loadGeneration, optionsSignature)) {
            inFlightFilePath = "";
            inFlightQuery = "";
            inFlightOptionsSignature = "";
            inFlightLoadGeneration = -1;
        }
    }

    public void clear() {
        totalFilePath = "";
        totalQuery = "";
        totalOptionsSignature = "";
        totalCount = -1;
        totalLoadGeneration = -1;
        inFlightFilePath = "";
        inFlightQuery = "";
        inFlightOptionsSignature = "";
        inFlightLoadGeneration = -1;
    }
}
