package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves standard .7z/.cb7 numeric volumes, including .1000 and beyond. */
final class SevenZSplitVolumeResolver {
    private SevenZSplitVolumeResolver() {}

    // Three is the minimum width, not a limit on the number of volumes.
    private static final Pattern NUMERIC_PART = Pattern.compile("^(.+)\\.([0-9]{3,10})$");

    static final class VolumeSet {
        @NonNull final File firstPart;
        @NonNull final List<File> parts;
        @NonNull final String displayName;

        VolumeSet(@NonNull File firstPart, @NonNull List<File> parts, @NonNull String displayName) {
            this.firstPart = firstPart;
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
            this.displayName = displayName;
        }
    }

    static boolean isSevenZSplitPartName(@NonNull String name) {
        return parsePartName(name) != null;
    }

    static boolean isSevenZSplitPart(@NonNull File file) {
        return isSevenZSplitPartName(file.getName());
    }

    @Nullable
    static String archiveStem(@NonNull String name) {
        PartName parsed = parsePartName(name);
        return parsed == null ? null : parsed.stem;
    }

    /** Canonical numeric suffix, independent of the archive family. */
    @Nullable
    static String numericArchiveStem(@NonNull String name) {
        PartName parsed = parseNumericPartName(name);
        return parsed == null ? null : parsed.stem;
    }

    @NonNull
    static File resolveFirstPart(@NonNull File selectedPart) throws IOException {
        VolumeSet set = resolve(selectedPart);
        return set == null ? selectedPart : set.firstPart;
    }

    @Nullable
    static VolumeSet resolve(@NonNull File selectedPart) throws IOException {
        if (parsePartName(selectedPart.getName()) == null) return null;
        return resolveNumeric(selectedPart);
    }

    /** Shared catalog validation for the generic .zip.001 / .tar.001 path. */
    @NonNull
    static VolumeSet resolveNumeric(@NonNull File selectedPart) throws IOException {
        PartName selected = parseNumericPartName(selectedPart.getName());
        if (selected == null) throw new IOException("Invalid numeric split archive name");
        checkCancelled();
        if (!selectedPart.isFile() || !selectedPart.canRead()) {
            throw new IOException("Selected numeric split volume is unavailable: " + selectedPart.getName());
        }
        File parent = selectedPart.getAbsoluteFile().getParentFile();
        File[] children = parent == null ? null : parent.listFiles();
        if (children == null) throw new IOException("Numeric split volume directory is unavailable");
        // A single catalog scan also detects gaps after volume 999. Sorting real
        // entries avoids looping over an attacker-controlled maximum ordinal.
        TreeMap<Integer, File> found = new TreeMap<>();
        for (File child : children) {
            checkCancelled();
            PartName candidate = parseNumericPartName(child.getName());
            if (candidate == null || !selected.stem.equalsIgnoreCase(candidate.stem)) continue;
            File previous = found.put(candidate.number, child);
            if (previous != null) {
                // Never guess which of Book.7z.001 / book.7z.001 belongs to a chain.
                throw new IOException("Ambiguous numeric split volume: " + previous.getName()
                        + " / " + child.getName());
            }
        }
        List<File> parts = new ArrayList<>(found.size());
        long expected = 1;
        for (Map.Entry<Integer, File> entry : found.entrySet()) {
            checkCancelled();
            if (entry.getKey().longValue() != expected) {
                throw new IOException((parsePartName(selectedPart.getName()) != null
                        ? "Missing 7z split volume: " : "Missing numeric split archive part: ") + selected.stem
                        + String.format(Locale.ROOT, ".%03d", expected));
            }
            File part = entry.getValue();
            if (!part.isFile() || !part.canRead()) {
                throw new IOException("Numeric split volume is unavailable: " + part.getName());
            }
            parts.add(part);
            expected++;
        }
        if (parts.isEmpty()) throw new IOException("First numeric split archive part is missing");
        PartName first = parseNumericPartName(parts.get(0).getName());
        return new VolumeSet(parts.get(0), parts, first.stem);
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Numeric split volume resolution cancelled");
        }
    }

    @Nullable
    private static PartName parsePartName(@NonNull String name) {
        PartName parsed = parseNumericPartName(name);
        if (parsed == null) return null;
        String lower = parsed.stem.toLowerCase(Locale.ROOT);
        return lower.endsWith(".7z") || lower.endsWith(".cb7") ? parsed : null;
    }

    @Nullable
    private static PartName parseNumericPartName(@NonNull String name) {
        Matcher matcher = NUMERIC_PART.matcher(name);
        if (!matcher.matches()) return null;
        String digits = matcher.group(2);
        // Reject alternate zero-padded aliases (.0001), zero, and overflow.
        if (digits.length() > 3 && digits.charAt(0) == '0') return null;
        try {
            int number = Integer.parseInt(digits);
            return number <= 0 ? null : new PartName(matcher.group(1), number);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class PartName {
        final String stem;
        final int number;
        PartName(String stem, int number) { this.stem = stem; this.number = number; }
    }
}
