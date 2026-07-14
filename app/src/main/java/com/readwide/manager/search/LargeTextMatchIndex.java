package com.readwide.manager.search;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.Arrays;

/**
 * Immutable, bounded position index produced as a side effect of a large-TXT
 * full-file match count. Keeping primitive arrays avoids retaining document
 * text while making later next/previous/nth lookups logarithmic.
 */
final class LargeTextMatchIndex {
    private final String filePath;
    private final long fileLength;
    private final long fileLastModified;
    private final String query;
    private final String optionsSignature;
    private final boolean collapseBlankLines;
    private final String transformSignature;
    private final int[] positions;
    private final int[] lines;

    LargeTextMatchIndex(@NonNull File file,
                        @NonNull String query,
                        @NonNull String optionsSignature,
                        boolean collapseBlankLines,
                        @NonNull String transformSignature,
                        @NonNull int[] positions,
                        @NonNull int[] lines) {
        this.filePath = file.getAbsolutePath();
        this.fileLength = file.length();
        this.fileLastModified = file.lastModified();
        this.query = query;
        this.optionsSignature = optionsSignature;
        this.collapseBlankLines = collapseBlankLines;
        this.transformSignature = transformSignature;
        this.positions = positions;
        this.lines = lines;
    }

    boolean matches(@NonNull File file,
                    @NonNull String requestedQuery,
                    @NonNull String requestedOptionsSignature,
                    boolean requestedCollapseBlankLines,
                    @NonNull String requestedTransformSignature) {
        return filePath.equals(file.getAbsolutePath())
                && fileLength == file.length()
                && fileLastModified == file.lastModified()
                && query.equals(requestedQuery)
                && optionsSignature.equals(requestedOptionsSignature)
                && collapseBlankLines == requestedCollapseBlankLines
                && transformSignature.equals(requestedTransformSignature);
    }

    boolean fileStateUnchanged(@NonNull File file) {
        return filePath.equals(file.getAbsolutePath())
                && fileLength == file.length()
                && fileLastModified == file.lastModified();
    }

    int size() {
        return positions.length;
    }

    @NonNull
    LargeTextSearchResult nearest(int startPosition, boolean forward) {
        int total = positions.length;
        if (total == 0) return new LargeTextSearchResult(-1, 1, 0, 0);

        int index;
        if (forward) {
            index = lowerBound(positions, Math.max(0, startPosition));
            if (index >= total) index = 0;
        } else {
            index = upperBound(positions, Math.max(0, startPosition)) - 1;
            if (index < 0) index = total - 1;
        }
        return resultAt(index);
    }

    @NonNull
    LargeTextSearchResult occurrence(int targetOccurrence) {
        if (targetOccurrence <= 0 || targetOccurrence > positions.length) {
            return new LargeTextSearchResult(-1, 1, 0, positions.length);
        }
        return resultAt(targetOccurrence - 1);
    }

    @NonNull
    private LargeTextSearchResult resultAt(int index) {
        return new LargeTextSearchResult(
                positions[index],
                lines[index],
                index + 1,
                positions.length);
    }

    private static int lowerBound(@NonNull int[] values, int target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (values[mid] < target) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static int upperBound(@NonNull int[] values, int target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (values[mid] <= target) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    static final class Builder {
        private final int limit;
        private int[] positions;
        private int[] lines;
        private int size;
        private boolean overflow;

        Builder(int limit) {
            this.limit = Math.max(0, limit);
            int initial = Math.min(this.limit, 256);
            positions = new int[initial];
            lines = new int[initial];
        }

        void add(int position, int line) {
            if (overflow) return;
            if (size >= limit) {
                overflow = true;
                positions = new int[0];
                lines = new int[0];
                size = 0;
                return;
            }
            ensureCapacity(size + 1);
            positions[size] = position;
            lines[size] = Math.max(1, line);
            size++;
        }

        boolean overflowed() {
            return overflow;
        }

        @NonNull
        LargeTextMatchIndex build(@NonNull File file,
                                  @NonNull String query,
                                  @NonNull String optionsSignature,
                                  boolean collapseBlankLines,
                                  @NonNull String transformSignature) {
            return new LargeTextMatchIndex(
                    file,
                    query,
                    optionsSignature,
                    collapseBlankLines,
                    transformSignature,
                    Arrays.copyOf(positions, size),
                    Arrays.copyOf(lines, size));
        }

        private void ensureCapacity(int required) {
            if (positions.length >= required) return;
            int next = Math.min(limit, Math.max(required, Math.max(16, positions.length * 2)));
            positions = Arrays.copyOf(positions, next);
            lines = Arrays.copyOf(lines, next);
        }
    }
}
