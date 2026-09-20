package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Metadata-only RAR catalogue; callers must require a readable chain before decoding. */
final class RarVolumeNameResolver {
    private static final Pattern NEW_STYLE_PART = Pattern.compile(
            "^(.*)\\.part([0-9]+)\\.rar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_STYLE_PART = Pattern.compile(
            "^(.*)\\.r([0-9]{2,3})$", Pattern.CASE_INSENSITIVE);

    private RarVolumeNameResolver() {}

    enum Style {
        SINGLE, NEW_STYLE_PART, OLD_STYLE_RAR_PLUS_RNN,
        BASE_RAR_WITH_NEW_STYLE_COMPANIONS, BASE_RAR_WITH_OLD_STYLE_COMPANIONS
    }

    static final class Result {
        private final Style style;
        private final File selected, firstVolume;
        private final List<File> volumes;
        private final int selectedPartIndex, nextMissingPartIndex, maxSeenPartIndex;
        private final boolean selectedLaterVolume;
        private final String prefix;
        @Nullable private final String problem;

        private Result(Style style, File selected, File firstVolume, Chain chain,
                       int selectedPartIndex, boolean selectedLaterVolume, String prefix) {
            this.style = style;
            this.selected = selected;
            this.firstVolume = firstVolume;
            this.volumes = Collections.unmodifiableList(new ArrayList<>(chain.volumes));
            this.selectedPartIndex = selectedPartIndex;
            this.nextMissingPartIndex = chain.nextMissingIndex;
            this.maxSeenPartIndex = chain.maxSeenIndex;
            this.selectedLaterVolume = selectedLaterVolume;
            this.prefix = prefix;
            this.problem = chain.problem;
        }

        @NonNull Style style() { return style; }
        @NonNull File selected() { return selected; }
        @NonNull File firstVolume() { return firstVolume; }
        @NonNull List<File> volumes() { return volumes; }
        int selectedPartIndex() { return selectedPartIndex; }
        int nextMissingPartIndex() { return nextMissingPartIndex; }
        int maxSeenPartIndex() { return maxSeenPartIndex; }
        boolean hasKnownGap() {
            return nextMissingPartIndex >= 0 && maxSeenPartIndex >= nextMissingPartIndex;
        }
        boolean selectedLaterVolume() { return selectedLaterVolume; }
        @NonNull String prefix() { return prefix; }
        boolean hasSplitCompanions() { return volumes.size() > 1; }
        @Nullable String problem() { return problem; }
    }

    @NonNull
    static Result resolve(@NonNull File selected) {
        if (Thread.currentThread().isInterrupted()) return single(selected, "RAR volume discovery interrupted");
        File parent = selected.getParentFile();
        if (parent == null) parent = selected.getAbsoluteFile().getParentFile();
        if (parent == null) return single(selected, "RAR volume directory unavailable");
        // Exactly one directory snapshot. A failed enumeration is not a singleton archive.
        File[] siblings;
        try { siblings = parent.listFiles(); }
        catch (SecurityException denied) { return single(selected, "RAR volume directory unavailable"); }
        if (siblings == null) return single(selected, "RAR volume directory unavailable");

        String name = selected.getName();
        Matcher numbered = NEW_STYLE_PART.matcher(name);
        if (numbered.matches()) {
            String prefix = numbered.group(1);
            int index = parseNonNegativeInt(numbered.group(2));
            Chain chain = collectNewStyle(siblings, prefix);
            if (index < 1) chain.problem = "Invalid RAR volume number: " + name;
            File first = chain.volumes.isEmpty()
                    ? expectedNewStyleFirstVolume(parent, prefix, numbered.group(2)) : chain.volumes.get(0);
            return new Result(Style.NEW_STYLE_PART, selected, first, chain, index, index > 1, prefix);
        }
        Matcher legacy = OLD_STYLE_PART.matcher(name);
        if (legacy.matches()) {
            String prefix = legacy.group(1);
            int index = parseNonNegativeInt(legacy.group(2));
            Chain chain = collectOldStyle(siblings, prefix, selected);
            File first = chain.volumes.isEmpty() ? new File(parent, prefix + ".rar") : chain.volumes.get(0);
            return new Result(Style.OLD_STYLE_RAR_PLUS_RNN, selected, first, chain, index, true, prefix);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".rar")) {
            String prefix = name.substring(0, name.length() - 4);
            Chain chain = collectOldStyle(siblings, prefix, selected);
            if (chain.maxSeenIndex >= 0) {
                File first = chain.volumes.isEmpty() ? selected : chain.volumes.get(0);
                return new Result(Style.BASE_RAR_WITH_OLD_STYLE_COMPANIONS,
                        selected, first, chain, 0, false, prefix);
            }
            // A base .rar is a different identity from a coexisting .partN.rar set.
            // A missing selected file is an error at the strict boundary, not an alias.
        }
        return single(selected, null);
    }

    private static File expectedNewStyleFirstVolume(File parent, String prefix, String digits) {
        // Width affects only diagnostics when part 1 is absent; there is no ordinal probing loop.
        StringBuilder first = new StringBuilder(digits.length());
        for (int i = 1; i < digits.length(); i++) first.append('0');
        first.append('1');
        return new File(parent, prefix + ".part" + first + ".rar");
    }

    private static Result single(File selected, @Nullable String problem) {
        Chain chain = new Chain(Collections.singletonList(selected), -1, 0, problem);
        return new Result(Style.SINGLE, selected, selected, chain, 0, false, selected.getName());
    }

    private static Chain collectNewStyle(File[] files, String prefix) {
        TreeMap<Integer, File> byIndex = new TreeMap<>();
        String problem = null;
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) { problem = "RAR volume discovery interrupted"; break; }
            if (file == null) continue;
            Matcher match = NEW_STYLE_PART.matcher(file.getName());
            if (!match.matches() || !match.group(1).equalsIgnoreCase(prefix)) continue;
            int index = parseNonNegativeInt(match.group(2));
            if (index <= 0) { problem = "Invalid RAR volume number: " + file.getName(); continue; }
            File prior = byIndex.putIfAbsent(index, file);
            if (prior != null) problem = "Ambiguous RAR volume " + index + ": "
                    + prior.getName() + " / " + file.getName();
        }
        return contiguous(byIndex, 1, problem);
    }

    private static Chain collectOldStyle(File[] files, String prefix, File selected) {
        File first = null;
        boolean ambiguousBase = false;
        String problem = null;
        TreeMap<Integer, File> byIndex = new TreeMap<>();
        String baseName = prefix + ".rar";
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) { problem = "RAR volume discovery interrupted"; break; }
            if (file == null) continue;
            if (file.getName().equalsIgnoreCase(baseName)) {
                if (first != null) ambiguousBase = true;
                if (first == null || file.equals(selected)) first = file;
                continue;
            }
            Matcher match = OLD_STYLE_PART.matcher(file.getName());
            if (!match.matches() || !match.group(1).equalsIgnoreCase(prefix)) continue;
            int index = parseNonNegativeInt(match.group(2));
            File prior = byIndex.putIfAbsent(index, file);
            if (prior != null) problem = "Ambiguous RAR continuation " + index + ": "
                    + prior.getName() + " / " + file.getName();
        }
        Chain tail = contiguous(byIndex, 0, problem);
        if (first == null) return new Chain(Collections.emptyList(), 0, tail.maxSeenIndex,
                problem != null ? problem : "Missing first RAR volume");
        if (ambiguousBase && !byIndex.isEmpty()) tail.problem = "Ambiguous first RAR volume";
        ArrayList<File> result = new ArrayList<>();
        result.add(first);
        result.addAll(tail.volumes);
        return new Chain(result, tail.nextMissingIndex, tail.maxSeenIndex, tail.problem);
    }

    /** O(V) walk of an ordered catalogue, not O(largest attacker-chosen ordinal). */
    private static Chain contiguous(TreeMap<Integer, File> byIndex, int firstIndex, @Nullable String problem) {
        ArrayList<File> result = new ArrayList<>();
        long expected = firstIndex;
        for (Map.Entry<Integer, File> entry : byIndex.entrySet()) {
            if (entry.getKey().longValue() != expected) break;
            result.add(entry.getValue());
            expected++;
        }
        int nextMissing = byIndex.isEmpty() ? -1 : expected > Integer.MAX_VALUE ? -1 : (int) expected;
        int maxSeen = byIndex.isEmpty() ? -1 : byIndex.lastKey();
        return new Chain(result, nextMissing, maxSeen, problem);
    }

    private static int parseNonNegativeInt(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static final class Chain {
        final List<File> volumes;
        final int nextMissingIndex, maxSeenIndex;
        @Nullable String problem;
        Chain(List<File> volumes, int nextMissingIndex, int maxSeenIndex, @Nullable String problem) {
            this.volumes = volumes;
            this.nextMissingIndex = nextMissingIndex;
            this.maxSeenIndex = maxSeenIndex;
            this.problem = problem;
        }
    }
}
