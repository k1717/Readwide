package com.textview.reader;

import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.search.LargeTextSearchResult;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class ReaderSearchController {
    private final ReaderActivity activity;

    ReaderSearchController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void resetActiveSearchState() {
        activity.activeSearchQuery = "";
        activity.activeSearchIndex = -1;
        activity.activeSearchOrdinal = 0;
        activity.largeTextSearchCountGeneration.incrementAndGet();
        activity.largeTextSearchTotalCache.clear();
        activity.applySearchHighlight();
    }

    int getCachedLargeTextSearchTotal(@NonNull String query) {
        if (!activity.largeTextEstimateActive || activity.filePath == null || query == null || query.isEmpty()) return -1;
        return activity.largeTextSearchTotalCache.get(activity.filePath, activity.loadGeneration.get(), query);
    }

    void rememberLargeTextSearchTotal(@NonNull String query, int total) {
        if (!activity.largeTextEstimateActive || activity.filePath == null || query == null || query.isEmpty() || total < 0) return;
        activity.largeTextSearchTotalCache.remember(activity.filePath, activity.loadGeneration.get(), query, total);
    }

    void clearLargeTextSearchTotalCache() {
        activity.largeTextSearchTotalCache.clear();
    }

    void applySearchHighlight() {
        if (activity.readerView == null) return;
        int highlightIndex = activity.activeSearchIndex;
        if (activity.largeTextEstimateActive && activity.activeSearchIndex >= 0) {
            highlightIndex = activity.activeSearchIndex - activity.largeTextPreviewBaseCharOffset;
            int bodyLength = activity.fileContent != null ? activity.fileContent.length() : 0;
            if (highlightIndex < 0 || highlightIndex >= bodyLength) {
                highlightIndex = -1;
            }
        }
        activity.readerView.setSearchHighlight(activity.activeSearchQuery, highlightIndex);
    }

    void updateLargeTextSearchStatus(@Nullable TextView matchStatus,
                                             int ordinal,
                                             int knownTotal) {
        if (matchStatus == null) return;
        int safeOrdinal = Math.max(0, ordinal);
        if (knownTotal >= 0) {
            matchStatus.setText(String.format(Locale.getDefault(), "%d / %d", safeOrdinal, knownTotal));
        } else {
            matchStatus.setText(String.format(Locale.getDefault(), "%d / …", safeOrdinal));
        }
    }

    void startLargeTextSearchTotalCountInBackground(@NonNull String query,
                                                            @Nullable TextView matchStatus) {
        if (!activity.largeTextEstimateActive || activity.filePath == null || query.isEmpty()) return;
        if (getCachedLargeTextSearchTotal(query) >= 0) return;

        final String expectedPath = activity.filePath;
        final int generation = activity.loadGeneration.get();
        if (activity.largeTextSearchTotalCache.isInFlight(expectedPath, query, generation)) {
            return;
        }

        final int countGeneration = activity.largeTextSearchCountGeneration.incrementAndGet();
        activity.largeTextSearchTotalCache.markInFlight(expectedPath, query, generation);

        activity.largeTextSearchCountExecutor.execute(() -> {
            int total = -1;
            String error = null;
            try {
                total = activity.largeTextSearchEngine.countMatches(new File(expectedPath), query);
            } catch (IOException | RuntimeException t) {
                error = t.getClass().getSimpleName();
            }

            final int finalTotal = total;
            final String finalError = error;
            activity.handler.post(() -> {
                activity.largeTextSearchTotalCache.clearInFlightIf(expectedPath, query, generation);

                if (activity.activityDestroyed
                        || generation != activity.loadGeneration.get()
                        || countGeneration != activity.largeTextSearchCountGeneration.get()
                        || !expectedPath.equals(activity.filePath)
                        || !query.equals(activity.activeSearchQuery)) {
                    return;
                }

                if (finalError != null || finalTotal < 0) {
                    return;
                }

                rememberLargeTextSearchTotal(query, finalTotal);
                updateLargeTextSearchStatus(matchStatus, activity.activeSearchOrdinal, finalTotal);
            });
        });
    }

    LargeTextSearchResult searchCurrentLargeTextPartitionInstant(@NonNull String query,
                                                                         int startPosition,
                                                                         boolean forward) {
        if (!activity.largeTextEstimateActive || query.isEmpty() || activity.fileContent == null || activity.fileContent.isEmpty()) {
            return null;
        }

        int bodyEnd = activity.largeTextPartitionBodyCharCount > 0
                ? Math.min(activity.fileContent.length(), activity.largeTextPartitionBodyCharCount)
                : activity.fileContent.length();
        if (bodyEnd <= 0) return null;

        int localStart = startPosition - Math.max(0, activity.largeTextPreviewBaseCharOffset);
        int step = Math.max(1, query.length());
        int idx = -1;

        if (forward) {
            int from = Math.max(0, Math.min(bodyEnd, localStart));
            int candidate = activity.fileContent.indexOf(query, from);
            if (candidate >= 0 && candidate + query.length() <= bodyEnd) {
                idx = candidate;
            }
        } else {
            int from = Math.min(bodyEnd - 1, Math.max(0, localStart));
            int candidate = activity.fileContent.lastIndexOf(query, from);
            while (candidate >= 0 && candidate + query.length() > bodyEnd) {
                candidate = activity.fileContent.lastIndexOf(query, Math.max(0, candidate - step));
            }
            if (candidate >= 0) {
                idx = candidate;
            }
        }

        if (idx < 0) return null;

        int absolute = Math.max(0, activity.largeTextPreviewBaseCharOffset + idx);
        int line = activity.countLinesUntilChar(absolute);
        int ordinal = activity.activeSearchOrdinal;
        if (ordinal > 0) {
            ordinal = forward ? ordinal + 1 : Math.max(1, ordinal - 1);
        }
        return new LargeTextSearchResult(absolute, line, ordinal, -1);
    }

    boolean applyInstantLargeTextSearchResult(@NonNull String query,
                                                      @NonNull LargeTextSearchResult result,
                                                      @Nullable TextView matchStatus) {
        if (!result.found()) return false;

        activity.activeSearchIndex = result.charPosition;
        activity.activeSearchOrdinal = Math.max(0, result.ordinal);
        activity.applySearchHighlight();

        int totalPages = Math.max(1, activity.getDisplayedTotalPageCount());
        int displayPage = activity.isLargeTextExactPageIndexReady()
                ? Math.max(1, Math.min(totalPages, activity.findExactLargeTextPageForChar(result.charPosition)))
                : activity.estimateDisplayedPageForLargeTextLine(result.lineNumber, totalPages);

        activity.largeTextEstimatedTotalPages = Math.max(activity.largeTextEstimatedTotalPages, totalPages);
        activity.scrollToSearchResultPosition(result.charPosition);
        int localPage = Math.max(1, activity.readerView != null ? activity.readerView.getCurrentPageNumber() : 1);
        activity.largeTextEstimatedBasePageOffset = Math.max(0, displayPage - localPage);
        activity.updatePositionLabel();

        int knownTotal = getCachedLargeTextSearchTotal(query);
        updateLargeTextSearchStatus(matchStatus, activity.activeSearchOrdinal, knownTotal);
        if (knownTotal < 0) {
            startLargeTextSearchTotalCountInBackground(query, matchStatus);
        }
        return true;
    }

    void performLargeTextSearchMove(@NonNull String query, boolean forward, TextView matchStatus) {
        performLargeTextSearchMove(query, forward, matchStatus, -1);
    }

    void performLargeTextSearchMove(@NonNull String query, boolean forward, TextView matchStatus, int targetOccurrence) {
        if (activity.filePath == null || query.isEmpty()) return;

        boolean queryChanged = !query.equals(activity.activeSearchQuery);
        if (queryChanged) {
            activity.activeSearchQuery = query;
            activity.activeSearchIndex = -1;
            activity.activeSearchOrdinal = 0;
            activity.largeTextSearchCountGeneration.incrementAndGet();
            activity.applySearchHighlight();
            if (matchStatus != null) {
                updateLargeTextSearchStatus(matchStatus, 0, getCachedLargeTextSearchTotal(query));
            }
        }
        if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);

        final String expectedPath = activity.filePath;
        final int generation = activity.loadGeneration.get();
        final int searchGeneration = activity.largeTextSearchGeneration.incrementAndGet();
        final int queryLength = Math.max(1, query.length());
        final int startPosition;
        if (activity.activeSearchIndex >= 0) {
            startPosition = forward
                    ? activity.activeSearchIndex + queryLength
                    : activity.activeSearchIndex - 1;
        } else {
            startPosition = activity.getCurrentCharPosition();
        }

        if (targetOccurrence <= 0 && activity.activeSearchOrdinal > 0) {
            LargeTextSearchResult instantResult = searchCurrentLargeTextPartitionInstant(query, startPosition, forward);
            if (instantResult != null && instantResult.found()) {
                applyInstantLargeTextSearchResult(query, instantResult, matchStatus);
                return;
            }
        }

        // Keep the previous match counter visible while the background search runs.
        // Replacing it with an ellipsis made the header visibly blink on every next/previous search.
        activity.largeTextSearchExecutor.execute(() -> {
            LargeTextSearchResult result;
            String error = null;
            try {
                File searchFile = new File(expectedPath);
                result = targetOccurrence > 0
                        ? activity.largeTextSearchEngine.search(searchFile, query, startPosition, forward, targetOccurrence)
                        : activity.largeTextSearchEngine.searchNearest(searchFile, query, startPosition, forward);
            } catch (Throwable t) {
                result = new LargeTextSearchResult(-1, 1, 0, 0);
                error = t.getClass().getSimpleName();
            }

            final LargeTextSearchResult finalResult = result;
            final String finalError = error;
            activity.handler.post(() -> {
                if (activity.activityDestroyed
                        || generation != activity.loadGeneration.get()
                        || searchGeneration != activity.largeTextSearchGeneration.get()
                        || !expectedPath.equals(activity.filePath)
                        || !query.equals(activity.activeSearchQuery)) {
                    return;
                }

                if (finalError != null) {
                    if (matchStatus != null) matchStatus.setText("0 / 0");
                    ShortToast.show(activity, activity.getString(R.string.error_prefix) + finalError);
                    return;
                }

                if (finalResult.totalKnown()) {
                    rememberLargeTextSearchTotal(query, finalResult.total);
                }

                if (!finalResult.found()) {
                    int knownTotal = finalResult.totalKnown()
                            ? finalResult.total
                            : getCachedLargeTextSearchTotal(query);
                    if (targetOccurrence > 0 && knownTotal > 0) {
                        updateLargeTextSearchStatus(matchStatus, 0, knownTotal);
                        ShortToast.show(activity, activity.getString(R.string.search_occurrence_out_of_range, knownTotal));
                        return;
                    }
                    activity.activeSearchIndex = -1;
                    activity.activeSearchOrdinal = 0;
                    activity.applySearchHighlight();
                    if (knownTotal >= 0) {
                        updateLargeTextSearchStatus(matchStatus, 0, knownTotal);
                    } else if (matchStatus != null) {
                        matchStatus.setText("0 / 0");
                    }
                    ShortToast.show(activity, activity.getString(R.string.not_found));
                    return;
                }

                activity.activeSearchIndex = finalResult.charPosition;
                activity.activeSearchOrdinal = Math.max(1, finalResult.ordinal);
                activity.applySearchHighlight();

                int totalPages = Math.max(1, activity.getDisplayedTotalPageCount());
                int displayPage = activity.isLargeTextExactPageIndexReady()
                        ? Math.max(1, Math.min(totalPages, activity.findExactLargeTextPageForChar(finalResult.charPosition)))
                        : activity.estimateDisplayedPageForLargeTextLine(finalResult.lineNumber, totalPages);
                int partitionStart = activity.getLargeTextPartitionStartLineForLine(finalResult.lineNumber);

                if (activity.isAbsoluteCharPositionInCurrentLargeTextBody(finalResult.charPosition)) {
                    activity.largeTextEstimatedTotalPages = Math.max(activity.largeTextEstimatedTotalPages, totalPages);
                    activity.scrollToSearchResultPosition(finalResult.charPosition);
                    int localPage = Math.max(1, activity.readerView != null ? activity.readerView.getCurrentPageNumber() : 1);
                    activity.largeTextEstimatedBasePageOffset = Math.max(0, displayPage - localPage);
                    activity.updatePositionLabel();
                } else {
                    activity.reloadLargeTextPreviewAround(
                            finalResult.charPosition,
                            displayPage,
                            totalPages,
                            null,
                            null,
                            partitionStart,
                            true);
                }

                int knownTotal = finalResult.totalKnown()
                        ? finalResult.total
                        : getCachedLargeTextSearchTotal(query);
                updateLargeTextSearchStatus(matchStatus, finalResult.ordinal, knownTotal);
                if (knownTotal < 0) {
                    startLargeTextSearchTotalCountInBackground(query, matchStatus);
                }
            });
        });
    }

    void performTextSearchMove(String rawQuery, boolean forward, TextView matchStatus) {
        performTextSearchMove(rawQuery, forward, matchStatus, -1);
    }

    void performTextSearchMove(String rawQuery, boolean forward, TextView matchStatus, int targetOccurrence) {
        if (targetOccurrence == 0) return;
        String query = rawQuery == null ? "" : rawQuery.trim();

        if (query.isEmpty()) {
            if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery("");
            resetActiveSearchState();
            if (matchStatus != null) matchStatus.setText("0 / 0");
            ShortToast.show(activity, activity.getString(R.string.enter_search_text));
            return;
        }

        if (activity.largeTextEstimateActive) {
            performLargeTextSearchMove(query, forward, matchStatus, targetOccurrence);
            return;
        }

        if (activity.fileContent == null || activity.fileContent.isEmpty()) return;

        int total = activity.countTextMatches(query);
        if (total <= 0) {
            if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);
            activity.activeSearchQuery = query;
            activity.activeSearchIndex = -1;
            activity.activeSearchOrdinal = 0;
            activity.applySearchHighlight();
            if (matchStatus != null) matchStatus.setText("0 / 0");
            ShortToast.show(activity, activity.getString(R.string.not_found));
            return;
        }

        if (!query.equals(activity.activeSearchQuery)) {
            activity.activeSearchQuery = query;
            activity.activeSearchIndex = -1;
            activity.activeSearchOrdinal = 0;
            activity.applySearchHighlight();
        }
        if (activity.prefs != null) activity.prefs.setLastReaderSearchQuery(query);

        int idx;
        if (targetOccurrence > 0) {
            if (targetOccurrence > total) {
                if (matchStatus != null) {
                    matchStatus.setText(String.format(Locale.getDefault(), "0 / %d", total));
                }
                ShortToast.show(activity, activity.getString(R.string.search_occurrence_out_of_range, total));
                return;
            }
            idx = activity.findNthText(query, targetOccurrence);
        } else if (activity.activeSearchIndex >= 0) {
            idx = forward
                    ? activity.findText(query, activity.activeSearchIndex + Math.max(1, query.length()))
                    : activity.findTextBackward(query, activity.activeSearchIndex - 1);
        } else {
            int currentPos = activity.getCurrentCharPosition();
            idx = forward
                    ? activity.findText(query, currentPos)
                    : activity.findTextBackward(query, currentPos);
        }

        if (targetOccurrence <= 0 && idx < 0) {
            idx = forward ? activity.findText(query, 0) : activity.findTextBackward(query, activity.fileContent.length() - 1);
        }

        if (idx >= 0) {
            activity.activeSearchIndex = idx;
            activity.activeSearchOrdinal = targetOccurrence > 0 ? targetOccurrence : activity.matchIndexForPosition(query, idx);
            activity.applySearchHighlight();

            // Search movement should use the same reveal-safe placement as large-TXT
            // search, including nth-result jumps.  Keep bookmark and saved-position
            // restore on exact top alignment; only search gets the popup-safe offset.
            activity.scrollToSearchResultPosition(idx);
            activity.updatePositionLabel();

            int ordinal = activity.activeSearchOrdinal;
            if (matchStatus != null) {
                matchStatus.setText(String.format(Locale.getDefault(), "%d / %d", ordinal, total));
            }

        } else {
            activity.activeSearchIndex = -1;
            activity.activeSearchOrdinal = 0;
            activity.applySearchHighlight();
            if (matchStatus != null) matchStatus.setText(String.format(Locale.getDefault(), "0 / %d", total));
            ShortToast.show(activity, activity.getString(R.string.not_found));
        }
    }

}
