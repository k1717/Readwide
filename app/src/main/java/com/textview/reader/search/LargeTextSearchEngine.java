package com.textview.reader.search;

import android.content.Context;

import androidx.annotation.NonNull;

import com.textview.reader.util.FileUtils;
import com.textview.reader.util.TextDisplayRule;
import com.textview.reader.util.TextDisplayRuleManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Full-file search helper for large TXT mode.
 *
 * <p>The activity owns UI state and partition loading; this helper owns the
 * repeated line-scan bookkeeping used by Find/Previous/Next/Go-to-match so
 * ReaderActivity does not need to duplicate match counting and wrap-around
 * search logic.</p>
 */
public final class LargeTextSearchEngine {
    public interface ReaderOpener {
        BufferedReader open(@NonNull File file) throws IOException;
    }

    private final Context appContext;
    private final ReaderOpener readerOpener;

    public LargeTextSearchEngine(@NonNull Context context,
                                 @NonNull ReaderOpener readerOpener) {
        this.appContext = context.getApplicationContext();
        this.readerOpener = readerOpener;
    }

    public LargeTextSearchResult search(@NonNull File file,
                                        @NonNull String query,
                                        int startPosition,
                                        boolean forward) throws IOException {
        return search(file, query, startPosition, forward, -1);
    }

    public LargeTextSearchResult searchNearest(@NonNull File file,
                                               @NonNull String query,
                                               int startPosition,
                                               boolean forward) throws IOException {
        if (query.isEmpty()) return new LargeTextSearchResult(-1, 1, 0, 0);

        int start = Math.max(0, startPosition);
        int step = Math.max(1, query.length());
        int ordinal = 0;

        int firstChar = -1;
        int firstLine = 1;
        int firstOrdinal = 0;

        int selectedChar = -1;
        int selectedLine = 1;
        int selectedOrdinal = 0;

        int lastChar = -1;
        int lastLine = 1;
        int lastOrdinal = 0;

        long charCount = 0L;
        int line = 1;
        List<TextDisplayRule> activeRules = getActiveRules(file);

        try (BufferedReader reader = readerOpener.open(file)) {
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                String normalized = normalizeLine(lineText, activeRules);

                int idx = normalized.indexOf(query);
                while (idx >= 0) {
                    int absolute = clampToInt(charCount + idx);
                    ordinal++;

                    if (firstChar < 0) {
                        firstChar = absolute;
                        firstLine = line;
                        firstOrdinal = ordinal;
                    }

                    lastChar = absolute;
                    lastLine = line;
                    lastOrdinal = ordinal;

                    if (forward) {
                        if (absolute >= start) {
                            return new LargeTextSearchResult(absolute, line, ordinal, -1);
                        }
                    } else if (absolute <= start) {
                        selectedChar = absolute;
                        selectedLine = line;
                        selectedOrdinal = ordinal;
                    }

                    idx = normalized.indexOf(query, idx + step);
                }

                charCount += normalized.length() + 1L;
                if (!forward && selectedChar >= 0 && charCount > start) {
                    return new LargeTextSearchResult(selectedChar, selectedLine, selectedOrdinal, -1);
                }
                line++;
            }
        }

        if (ordinal <= 0) {
            return new LargeTextSearchResult(-1, 1, 0, 0);
        }

        // Wrap-around cases require a full-file scan anyway, so total is known here.
        if (forward) {
            return new LargeTextSearchResult(firstChar, firstLine, firstOrdinal, ordinal);
        }
        return new LargeTextSearchResult(lastChar, lastLine, lastOrdinal, ordinal);
    }

    public int countMatches(@NonNull File file,
                            @NonNull String query) throws IOException {
        if (query.isEmpty()) return 0;
        return search(file, query, 0, true, Integer.MAX_VALUE).total;
    }

    public LargeTextSearchResult search(@NonNull File file,
                                        @NonNull String query,
                                        int startPosition,
                                        boolean forward,
                                        int targetOccurrence) throws IOException {
        if (query.isEmpty()) return new LargeTextSearchResult(-1, 1, 0, 0);

        int start = Math.max(0, startPosition);
        int step = Math.max(1, query.length());
        int total = 0;

        int firstChar = -1;
        int firstLine = 1;
        int firstOrdinal = 0;

        int selectedChar = -1;
        int selectedLine = 1;
        int selectedOrdinal = 0;

        int lastChar = -1;
        int lastLine = 1;
        int lastOrdinal = 0;

        long charCount = 0L;
        int line = 1;
        List<TextDisplayRule> activeRules = getActiveRules(file);

        try (BufferedReader reader = readerOpener.open(file)) {
            String lineText;
            while ((lineText = reader.readLine()) != null) {
                String normalized = normalizeLine(lineText, activeRules);

                int idx = normalized.indexOf(query);
                while (idx >= 0) {
                    int absolute = clampToInt(charCount + idx);
                    total++;

                    if (firstChar < 0) {
                        firstChar = absolute;
                        firstLine = line;
                        firstOrdinal = total;
                    }

                    lastChar = absolute;
                    lastLine = line;
                    lastOrdinal = total;

                    if (targetOccurrence > 0) {
                        if (selectedChar < 0 && total == targetOccurrence) {
                            selectedChar = absolute;
                            selectedLine = line;
                            selectedOrdinal = total;
                        }
                    } else if (forward) {
                        if (selectedChar < 0 && absolute >= start) {
                            selectedChar = absolute;
                            selectedLine = line;
                            selectedOrdinal = total;
                        }
                    } else if (absolute <= start) {
                        selectedChar = absolute;
                        selectedLine = line;
                        selectedOrdinal = total;
                    }

                    idx = normalized.indexOf(query, idx + step);
                }

                charCount += normalized.length() + 1L;
                line++;
            }
        }

        if (total <= 0) {
            return new LargeTextSearchResult(-1, 1, 0, 0);
        }

        if (targetOccurrence > 0) {
            return selectedChar >= 0
                    ? new LargeTextSearchResult(selectedChar, selectedLine, selectedOrdinal, total)
                    : new LargeTextSearchResult(-1, 1, 0, total);
        }

        if (selectedChar < 0) {
            if (forward) {
                selectedChar = firstChar;
                selectedLine = firstLine;
                selectedOrdinal = firstOrdinal;
            } else {
                selectedChar = lastChar;
                selectedLine = lastLine;
                selectedOrdinal = lastOrdinal;
            }
        }

        return new LargeTextSearchResult(selectedChar, selectedLine, selectedOrdinal, total);
    }

    private List<TextDisplayRule> getActiveRules(@NonNull File file) {
        return TextDisplayRuleManager.getActiveRules(appContext, file.getAbsolutePath());
    }

    private static String normalizeLine(@NonNull String lineText,
                                        @NonNull List<TextDisplayRule> activeRules) {
        String normalized = FileUtils.enforceTextPresentationSelectors(lineText);
        return TextDisplayRuleManager.apply(normalized, activeRules);
    }

    private static int clampToInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
}
