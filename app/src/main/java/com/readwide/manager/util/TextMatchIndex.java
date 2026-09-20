package com.readwide.manager.util;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One worker-confined match snapshot for count, next/previous and nth-result navigation. */
public final class TextMatchIndex {
    private static final int MAX_CACHED_STARTS = 200_000;
    private final String text, query, optionsSignature;
    private final MatchOffsetIndex offsets;
    private final int count;
    private final SearchMatcher.PreparedText overflowScan;

    public static final class Hit {
        public final int position, ordinal;
        private Hit(int position, int ordinal) { this.position = position; this.ordinal = ordinal; }
    }

    private TextMatchIndex(String text, String query, SearchOptions options, MatchOffsetIndex offsets,
                           SearchMatcher.PreparedText overflowScan) {
        this.text = text; this.query = query; optionsSignature = options.signature();
        this.offsets = offsets; this.count = offsets.count(); this.overflowScan = overflowScan;
    }

    public static TextMatchIndex build(String text, String query, SearchOptions options,
                                       BooleanSupplier cancelled) {
        return build(text, query, options, cancelled, MAX_CACHED_STARTS);
    }

    static TextMatchIndex build(String content, String query, SearchOptions options,
                                BooleanSupplier cancelled, int cacheLimit) {
        check(cancelled);
        String text = content == null ? "" : content;
        SearchOptions opt = options == null ? SearchOptions.literal() : options;
        SearchMatcher matcher = SearchMatcher.compile(query, opt);
        MatchOffsetIndex.Builder builder = new MatchOffsetIndex.Builder(text.length(), Math.max(0, cacheLimit));
        SearchMatcher.PreparedText prepared = matcher == null ? null : matcher.prepareText(text);
        if (prepared != null) prepared.forEachInRange(0, text.length(), (start, end) -> {
            check(cancelled);
            builder.add(start);
            return true;
        });
        MatchOffsetIndex offsets = builder.build();
        check(cancelled); // Never publish a partial count after cancellation/interruption.
        return new TextMatchIndex(text, query, opt, offsets, offsets.isComplete() ? null : prepared);
    }

    public boolean matches(String content, String query, SearchOptions options) {
        return text == content && Objects.equals(this.query, query)
                && optionsSignature.equals((options == null ? SearchOptions.literal() : options).signature());
    }

    public int count() { return count; }

    public Hit nearest(int from, boolean forward, BooleanSupplier cancelled) {
        check(cancelled);
        if (count == 0) return new Hit(-1, 0);
        if (overflowScan == null) {
            int rank = offsets.rank(forward ? (long) from : from + 1L);
            int ordinal = forward ? (rank == count ? 1 : rank + 1) : (rank == 0 ? count : rank);
            return new Hit(offsets.occurrence(ordinal), ordinal);
        }
        // If neither a complete sparse index nor the compact ranked bitmap fits,
        // keep full result coverage through the prepared worker scan.
        int[] first = {-1}, last = {-1}, selected = {-1}, selectedOrdinal = {0}, ordinal = {0};
        scan(cancelled, (start, end) -> {
            ++ordinal[0];
            if (first[0] < 0) first[0] = start;
            last[0] = start;
            if (forward ? start >= from : start <= from) {
                selected[0] = start; selectedOrdinal[0] = ordinal[0];
                if (forward) return false;
            }
            return forward || start <= from || selected[0] < 0;
        });
        return selected[0] >= 0 ? new Hit(selected[0], selectedOrdinal[0])
                : forward ? new Hit(first[0], 1) : new Hit(last[0], count);
    }

    public Hit occurrence(int ordinal, BooleanSupplier cancelled) {
        check(cancelled);
        if (ordinal < 1 || ordinal > count) return new Hit(-1, 0);
        int cached = offsets.occurrence(ordinal);
        if (cached >= 0) return new Hit(cached, ordinal);
        Hit[] selected = {new Hit(-1, 0)};
        int[] seen = {0};
        scan(cancelled, (start, end) -> {
            if (++seen[0] != ordinal) return true;
            selected[0] = new Hit(start, ordinal);
            return false;
        });
        return selected[0];
    }

    public int ordinalAt(int position, BooleanSupplier cancelled) {
        if (position < 0) { check(cancelled); return 0; }
        Hit hit = nearest(position, true, cancelled);
        return hit.position == position ? hit.ordinal : 0;
    }

    private void scan(BooleanSupplier cancelled, SearchMatcher.MatchConsumer consumer) {
        overflowScan.forEachInRange(0, text.length(), (start, end) -> {
            check(cancelled);
            return consumer.accept(start, end);
        });
        check(cancelled);
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean()))
            throw new CancellationException();
    }

}
