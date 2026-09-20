package com.readwide.manager.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * NFC comparison text with monotone original-UTF-16 span mappings.
 * Unchanged runs use offset deltas. Composition, expansion and reordered marks
 * map to their complete covering source segment (not an arbitrary interior
 * code unit). No map is allocated for already-normalized text.
 *
 * Normalization delegates to the runtime Unicode implementation. Provenance is
 * paired through canonical decompositions: ordering is stable for occurrences
 * of the same decomposed code point. Crossing/overlapping source spans are
 * coalesced so matches remain ordered in original coordinates.
 */
final class NormalizedSearchText {
    final String value;
    private final Segment[] segments; // null means identity

    private NormalizedSearchText(String value, Segment[] segments) {
        this.value = value;
        this.segments = segments;
    }

    static NormalizedSearchText identity(String text) { return new NormalizedSearchText(text, null); }

    static NormalizedSearchText nfc(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        if (normalized.equals(text) || Thread.currentThread().isInterrupted()) return identity(text);
        Map<Integer, Positions> origins = new HashMap<>();
        for (int offset = 0; offset < text.length();) {
            if ((offset & 1023) == 0 && Thread.currentThread().isInterrupted()) return identity(text);
            int cp = text.codePointAt(offset);
            String decomposition = decompose(cp);
            for (int at = 0; at < decomposition.length();) {
                int token = decomposition.codePointAt(at);
                origins.computeIfAbsent(token, unused -> new Positions()).add(offset);
                at += Character.charCount(token);
            }
            offset += Character.charCount(cp);
        }
        ArrayList<Segment> stack = new ArrayList<>();
        for (int offset = 0; offset < normalized.length();) {
            if ((offset & 1023) == 0 && Thread.currentThread().isInterrupted()) return identity(text);
            int cp = normalized.codePointAt(offset);
            String decomposition = decompose(cp);
            int sourceStart = text.length(), sourceEnd = 0;
            for (int at = 0; at < decomposition.length();) {
                int token = decomposition.codePointAt(at);
                Positions positions = origins.get(token);
                if (positions == null || positions.read == positions.size) {
                    throw new IllegalStateException("Inconsistent canonical decomposition");
                }
                int original = positions.take();
                sourceStart = Math.min(sourceStart, original);
                sourceEnd = Math.max(sourceEnd, original + Character.charCount(text.codePointAt(original)));
                at += Character.charCount(token);
            }
            Segment segment = new Segment(offset, offset + Character.charCount(cp), sourceStart, sourceEnd);
            // A later normalized mark can originate before an earlier one, or
            // several output characters can originate from the same input.
            while (!stack.isEmpty() && stack.get(stack.size() - 1).sourceEnd > segment.sourceStart) {
                Segment previous = stack.remove(stack.size() - 1);
                segment = new Segment(previous.start, segment.end,
                        Math.min(previous.sourceStart, segment.sourceStart),
                        Math.max(previous.sourceEnd, segment.sourceEnd));
            }
            segment.identity = segment.end - segment.start == segment.sourceEnd - segment.sourceStart
                    && text.regionMatches(segment.sourceStart, normalized, segment.start, segment.end - segment.start);
            if (!stack.isEmpty()) {
                Segment previous = stack.get(stack.size() - 1);
                if (previous.identity && segment.identity && previous.sourceEnd == segment.sourceStart) {
                    previous.end = segment.end;
                    previous.sourceEnd = segment.sourceEnd;
                    offset += Character.charCount(cp);
                    continue;
                }
            }
            stack.add(segment);
            offset += Character.charCount(cp);
        }
        return new NormalizedSearchText(normalized, stack.toArray(new Segment[0]));
    }

    private static String decompose(int cp) {
        String scalar = new String(Character.toChars(cp));
        return cp < 0xc0 ? scalar : Normalizer.normalize(scalar, Normalizer.Form.NFD);
    }

    NormalizedSearchText withValue(String folded) {
        if (folded.length() != value.length()) throw new IllegalArgumentException("Case fold moved offsets");
        return new NormalizedSearchText(folded, segments);
    }

    int originalStart(int normalizedOffset) {
        if (segments == null) return normalizedOffset;
        Segment s = segmentAt(normalizedOffset);
        return s.identity ? s.sourceStart + normalizedOffset - s.start : s.sourceStart;
    }

    int originalEnd(int normalizedEnd) {
        if (segments == null) return normalizedEnd;
        Segment s = segmentAt(normalizedEnd - 1);
        return s.identity ? s.sourceStart + normalizedEnd - s.start : s.sourceEnd;
    }

    /** First comparison offset with a mapped start at or after an original offset. */
    int comparisonStart(int originalOffset) {
        if (segments == null) return Math.min(value.length(), Math.max(0, originalOffset));
        int low = 0, high = segments.length;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (segments[mid].sourceEnd <= originalOffset) low = mid + 1; else high = mid;
        }
        if (low == segments.length) return value.length();
        Segment s = segments[low];
        if (originalOffset <= s.sourceStart) return s.start;
        return s.identity ? s.start + originalOffset - s.sourceStart : s.end;
    }

    private Segment segmentAt(int offset) {
        int low = 0, high = segments.length;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (segments[mid].end <= offset) low = mid + 1; else high = mid;
        }
        return segments[low];
    }

    private static final class Segment {
        final int start, sourceStart;
        int end, sourceEnd;
        boolean identity;
        Segment(int start, int end, int sourceStart, int sourceEnd) {
            this.start = start; this.end = end; this.sourceStart = sourceStart; this.sourceEnd = sourceEnd;
        }
    }

    private static final class Positions {
        int[] data = new int[8];
        int size, read;
        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, Math.addExact(size, Math.max(8, size / 2)));
            data[size++] = value;
        }
        int take() { return data[read++]; }
    }
}
