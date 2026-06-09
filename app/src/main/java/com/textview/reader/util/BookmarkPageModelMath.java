package com.textview.reader.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Stateless page-model decisions for TXT bookmark compatibility. */
public final class BookmarkPageModelMath {
    private BookmarkPageModelMath() {}

    public static int normalizePage(int pageNumber) {
        return Math.max(1, pageNumber);
    }

    public static int normalizeTotalPages(int pageNumber, int totalPages) {
        int page = normalizePage(pageNumber);
        return Math.max(page, totalPages);
    }

    public static boolean pageModelFieldsChanged(int oldPage,
                                                 int oldTotalPages,
                                                 @Nullable String oldSignature,
                                                 int newPage,
                                                 int newTotalPages,
                                                 @Nullable String newSignature) {
        int page = normalizePage(newPage);
        int total = normalizeTotalPages(page, newTotalPages);
        String oldSig = oldSignature != null ? oldSignature : "";
        String newSig = newSignature != null ? newSignature : "";
        return oldPage != page || oldTotalPages != total || !oldSig.equals(newSig);
    }

    @NonNull
    public static int[] resolveSavedPageTarget(@Nullable String currentSignature,
                                               @Nullable String savedSignature,
                                               int savedPage,
                                               int savedTotalPages) {
        String current = currentSignature != null ? currentSignature : "";
        String saved = savedSignature != null ? savedSignature : "";
        if (!current.isEmpty()
                && current.equals(saved)
                && savedPage > 0
                && savedTotalPages > 0) {
            int total = Math.max(1, savedTotalPages);
            int page = Math.max(1, Math.min(total, savedPage));
            return new int[] { page, total };
        }

        // Different or missing layout signature: preserve absolute-char/anchor restore,
        // but intentionally discard stale page/denominator metadata.
        return new int[] { 0, 0 };
    }
}
