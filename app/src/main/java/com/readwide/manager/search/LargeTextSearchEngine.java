package com.readwide.manager.search;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.readwide.manager.util.FileUtils;
import com.readwide.manager.util.PrefsManager;
import com.readwide.manager.util.SearchMatcher;
import com.readwide.manager.util.SearchOptions;
import com.readwide.manager.util.TextDisplayRule;
import com.readwide.manager.util.TextDisplayRuleManager;
import com.readwide.manager.util.TxtBlankLineCollapser;

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
    private static final int MAX_INDEXED_MATCHES = 200_000;

    public interface ReaderOpener {
        BufferedReader open(@NonNull File file) throws IOException;
    }

    interface LineTransform {
        @NonNull String apply(@NonNull String line);
        @NonNull String signature();
    }

    interface LineTransformFactory {
        @NonNull LineTransform create(@NonNull File file);
    }

    /**
     * Cooperative cancellation hook polled during full-file scans so a search or
     * count that has been superseded (newer query/options) or whose activity is
     * gone stops scanning instead of running the whole file to completion.
     */
    public interface CancelSignal {
        boolean isCancelled();
    }

    @Nullable private final Context appContext;
    private final ReaderOpener readerOpener;
    private final LineTransformFactory lineTransformFactory;
    @Nullable private volatile LargeTextMatchIndex cachedMatchIndex;

    public LargeTextSearchEngine(@NonNull Context context,
                                 @NonNull ReaderOpener readerOpener) {
        this.appContext = context.getApplicationContext();
        this.readerOpener = readerOpener;
        this.lineTransformFactory = file -> {
            List<TextDisplayRule> activeRules = TextDisplayRuleManager.getActiveRules(
                    appContext, file.getAbsolutePath());
            String signature = ruleSignature(activeRules);
            return new LineTransform() {
                @NonNull
                @Override
                public String apply(@NonNull String line) {
                    String normalized = FileUtils.enforceTextPresentationSelectors(line);
                    return TextDisplayRuleManager.apply(normalized, activeRules);
                }

                @NonNull
                @Override
                public String signature() {
                    return signature;
                }
            };
        };
    }

    LargeTextSearchEngine(@NonNull ReaderOpener readerOpener,
                          @NonNull LineTransformFactory lineTransformFactory) {
        this.appContext = null;
        this.readerOpener = readerOpener;
        this.lineTransformFactory = lineTransformFactory;
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
        return searchNearest(file, query, startPosition, forward, SearchOptions.literal());
    }

    public LargeTextSearchResult searchNearest(@NonNull File file,
                                               @NonNull String query,
                                               int startPosition,
                                               boolean forward,
                                               @NonNull SearchOptions options) throws IOException {
        return searchNearest(file, query, startPosition, forward, options,
                PrefsManager.getInstance(appContext).isCollapseBlankLinesEnabled(), null);
    }

    public LargeTextSearchResult searchNearest(@NonNull File file,
                                               @NonNull String query,
                                               int startPosition,
                                               boolean forward,
                                               @NonNull SearchOptions options,
                                               boolean collapseBlankLines,
                                               @Nullable CancelSignal cancel) throws IOException {
        SearchMatcher matcher = SearchMatcher.compile(query, options);
        if (matcher == null) return new LargeTextSearchResult(-1, 1, 0, 0);

        LineTransform lineTransform = lineTransformFactory.create(file);
        LargeTextMatchIndex cached = getCachedIndex(
                file, query, options, collapseBlankLines, lineTransform.signature());
        if (cached != null) return cached.nearest(startPosition, forward);

        int start = Math.max(0, startPosition);
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
        TxtBlankLineCollapser.Filter collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines);

        try (BufferedReader reader = readerOpener.open(file)) {
            String lineText;
            long scanned = 0L;
            while ((lineText = reader.readLine()) != null) {
                if (cancel != null && (++scanned & 0x3FFL) == 0L && cancel.isCancelled()) {
                    return new LargeTextSearchResult(-1, 1, 0, 0);
                }
                String normalized = lineTransform.apply(lineText);
                String emitted = collapseFilter.accept(normalized);
                if (emitted == null) continue;
                normalized = emitted;

                final long lineBaseChar = charCount;
                final int curLine = line;
                // Collect matches for this line with a single normalization pass.
                // Returning a non-null result from the consumer signals an early,
                // method-level return (forward hit at/after start).
                LargeTextSearchResult[] earlyReturn = new LargeTextSearchResult[1];
                int[] state = new int[]{ordinal, firstChar, firstLine, firstOrdinal,
                        lastChar, lastLine, lastOrdinal, selectedChar, selectedLine, selectedOrdinal};
                matcher.forEachMatch(normalized, (s, e) -> {
                    int absolute = clampToInt(lineBaseChar + s);
                    state[0]++; // ordinal
                    if (state[1] < 0) { state[1] = absolute; state[2] = curLine; state[3] = state[0]; }
                    state[4] = absolute; state[5] = curLine; state[6] = state[0];
                    if (forward) {
                        if (absolute >= start) {
                            earlyReturn[0] = new LargeTextSearchResult(absolute, curLine, state[0], -1);
                            return false;
                        }
                    } else if (absolute <= start) {
                        state[7] = absolute; state[8] = curLine; state[9] = state[0];
                    }
                    return true;
                });
                ordinal = state[0];
                firstChar = state[1]; firstLine = state[2]; firstOrdinal = state[3];
                lastChar = state[4]; lastLine = state[5]; lastOrdinal = state[6];
                selectedChar = state[7]; selectedLine = state[8]; selectedOrdinal = state[9];
                if (earlyReturn[0] != null) return earlyReturn[0];

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
        return countMatches(file, query, SearchOptions.literal());
    }

    public int countMatches(@NonNull File file,
                            @NonNull String query,
                            @NonNull SearchOptions options) throws IOException {
        return countMatches(file, query, options,
                PrefsManager.getInstance(appContext).isCollapseBlankLinesEnabled(), null);
    }

    public int countMatches(@NonNull File file,
                            @NonNull String query,
                            @NonNull SearchOptions options,
                            boolean collapseBlankLines,
                            @Nullable CancelSignal cancel) throws IOException {
        if (query.isEmpty()) return 0;
        SearchMatcher matcher = SearchMatcher.compile(query, options);
        if (matcher == null) return 0;

        LineTransform lineTransform = lineTransformFactory.create(file);
        long initialLength = file.length();
        long initialLastModified = file.lastModified();
        LargeTextMatchIndex.Builder indexBuilder = new LargeTextMatchIndex.Builder(MAX_INDEXED_MATCHES);
        int[] total = new int[]{0};
        long charCount = 0L;
        int line = 1;
        TxtBlankLineCollapser.Filter collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines);

        try (BufferedReader reader = readerOpener.open(file)) {
            String lineText;
            long scanned = 0L;
            while ((lineText = reader.readLine()) != null) {
                if (cancel != null && (++scanned & 0x3FFL) == 0L && cancel.isCancelled()) {
                    return -1;
                }
                String normalized = lineTransform.apply(lineText);
                String emitted = collapseFilter.accept(normalized);
                if (emitted == null) continue;
                normalized = emitted;

                final long lineBaseChar = charCount;
                final int currentLine = line;
                matcher.forEachMatch(normalized, (start, end) -> {
                    if (total[0] < Integer.MAX_VALUE) total[0]++;
                    indexBuilder.add(clampToInt(lineBaseChar + start), currentLine);
                    return true;
                });
                charCount += normalized.length() + 1L;
                line++;
            }
        }

        if (cancel != null && cancel.isCancelled()) return -1;
        if (!indexBuilder.overflowed()
                && initialLength == file.length()
                && initialLastModified == file.lastModified()) {
            cachedMatchIndex = indexBuilder.build(
                    file,
                    query,
                    options.signature(),
                    collapseBlankLines,
                    lineTransform.signature());
        }
        return total[0];
    }

    public LargeTextSearchResult search(@NonNull File file,
                                        @NonNull String query,
                                        int startPosition,
                                        boolean forward,
                                        int targetOccurrence) throws IOException {
        return search(file, query, startPosition, forward, targetOccurrence, SearchOptions.literal());
    }

    public LargeTextSearchResult search(@NonNull File file,
                                        @NonNull String query,
                                        int startPosition,
                                        boolean forward,
                                        int targetOccurrence,
                                        @NonNull SearchOptions options) throws IOException {
        return search(file, query, startPosition, forward, targetOccurrence, options,
                PrefsManager.getInstance(appContext).isCollapseBlankLinesEnabled(), null);
    }

    public LargeTextSearchResult search(@NonNull File file,
                                        @NonNull String query,
                                        int startPosition,
                                        boolean forward,
                                        int targetOccurrence,
                                        @NonNull SearchOptions options,
                                        boolean collapseBlankLines,
                                        @Nullable CancelSignal cancel) throws IOException {
        SearchMatcher matcher = SearchMatcher.compile(query, options);
        if (matcher == null) return new LargeTextSearchResult(-1, 1, 0, 0);

        LineTransform lineTransform = lineTransformFactory.create(file);
        LargeTextMatchIndex cached = getCachedIndex(
                file, query, options, collapseBlankLines, lineTransform.signature());
        if (cached != null) {
            return targetOccurrence > 0
                    ? cached.occurrence(targetOccurrence)
                    : cached.nearest(startPosition, forward);
        }

        int start = Math.max(0, startPosition);
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
        TxtBlankLineCollapser.Filter collapseFilter = new TxtBlankLineCollapser.Filter(collapseBlankLines);

        try (BufferedReader reader = readerOpener.open(file)) {
            String lineText;
            long scanned = 0L;
            while ((lineText = reader.readLine()) != null) {
                if (cancel != null && (++scanned & 0x3FFL) == 0L && cancel.isCancelled()) {
                    return new LargeTextSearchResult(-1, 1, 0, 0);
                }
                String normalized = lineTransform.apply(lineText);
                String emitted = collapseFilter.accept(normalized);
                if (emitted == null) continue;
                normalized = emitted;

                final long lineBaseChar = charCount;
                final int curLine = line;
                int[] state = new int[]{total, firstChar, firstLine, firstOrdinal,
                        lastChar, lastLine, lastOrdinal, selectedChar, selectedLine, selectedOrdinal};
                matcher.forEachMatch(normalized, (s, e) -> {
                    int absolute = clampToInt(lineBaseChar + s);
                    state[0]++; // total
                    if (state[1] < 0) { state[1] = absolute; state[2] = curLine; state[3] = state[0]; }
                    state[4] = absolute; state[5] = curLine; state[6] = state[0];
                    if (targetOccurrence > 0) {
                        if (state[7] < 0 && state[0] == targetOccurrence) {
                            state[7] = absolute; state[8] = curLine; state[9] = state[0];
                        }
                    } else if (forward) {
                        if (state[7] < 0 && absolute >= start) {
                            state[7] = absolute; state[8] = curLine; state[9] = state[0];
                        }
                    } else if (absolute <= start) {
                        state[7] = absolute; state[8] = curLine; state[9] = state[0];
                    }
                    return true;
                });
                total = state[0];
                firstChar = state[1]; firstLine = state[2]; firstOrdinal = state[3];
                lastChar = state[4]; lastLine = state[5]; lastOrdinal = state[6];
                selectedChar = state[7]; selectedLine = state[8]; selectedOrdinal = state[9];

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

    @Nullable
    private LargeTextMatchIndex getCachedIndex(@NonNull File file,
                                                @NonNull String query,
                                                @NonNull SearchOptions options,
                                                boolean collapseBlankLines,
                                                @NonNull String transformSignature) {
        LargeTextMatchIndex cached = cachedMatchIndex;
        if (cached == null || !cached.matches(
                file,
                query,
                options.signature(),
                collapseBlankLines,
                transformSignature)) {
            return null;
        }
        return cached;
    }

    @NonNull
    private static String ruleSignature(@NonNull List<TextDisplayRule> rules) {
        StringBuilder out = new StringBuilder(rules.size() * 48);
        for (TextDisplayRule rule : rules) {
            if (rule == null) continue;
            appendSignatureField(out, rule.id);
            appendSignatureField(out, rule.findText);
            appendSignatureField(out, rule.replacementText);
            appendSignatureField(out, rule.scope);
            appendSignatureField(out, rule.filePath);
            appendSignatureField(out, rule.sourceFilePath);
            out.append(rule.enabled ? '1' : '0')
                    .append(rule.caseSensitive ? '1' : '0')
                    .append(rule.useRegex ? '1' : '0');
        }
        return out.toString();
    }

    private static void appendSignatureField(@NonNull StringBuilder out, @Nullable String value) {
        String safe = value == null ? "" : value;
        out.append(safe.length()).append(':').append(safe).append(';');
    }

    private static int clampToInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
}
