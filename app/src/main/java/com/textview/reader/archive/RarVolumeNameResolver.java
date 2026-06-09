package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves RAR multi-volume file-name chains without touching payload bytes.
 *
 * <p>The Java RAR split path is intentionally narrow: stored split payloads and a few
 * encrypted stored variants. This resolver does not broaden compressed split support. It only
 * makes the volume discovery boundary deterministic and testable, especially when the user opens
 * a later volume such as {@code .part02.rar} or {@code .r01}.</p>
 */
final class RarVolumeNameResolver {
    private static final Pattern NEW_STYLE_PART = Pattern.compile(
            "^(.*)\\.part(\\d{1,6})\\.rar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_STYLE_PART = Pattern.compile(
            "^(.*)\\.r(\\d{2,3})$", Pattern.CASE_INSENSITIVE);

    private RarVolumeNameResolver() {}

    enum Style {
        SINGLE,
        NEW_STYLE_PART,
        OLD_STYLE_RAR_PLUS_RNN,
        BASE_RAR_WITH_NEW_STYLE_COMPANIONS,
        BASE_RAR_WITH_OLD_STYLE_COMPANIONS
    }

    static final class Result {
        private final Style style;
        private final File selected;
        private final File firstVolume;
        private final List<File> volumes;
        private final int selectedPartIndex;
        private final int nextMissingPartIndex;
        private final int maxSeenPartIndex;
        private final boolean selectedLaterVolume;
        private final String prefix;

        private Result(@NonNull Style style,
                       @NonNull File selected,
                       @NonNull File firstVolume,
                       @NonNull List<File> volumes,
                       int selectedPartIndex,
                       int nextMissingPartIndex,
                       int maxSeenPartIndex,
                       boolean selectedLaterVolume,
                       @NonNull String prefix) {
            this.style = style;
            this.selected = selected;
            this.firstVolume = firstVolume;
            this.volumes = Collections.unmodifiableList(new ArrayList<>(volumes));
            this.selectedPartIndex = selectedPartIndex;
            this.nextMissingPartIndex = nextMissingPartIndex;
            this.maxSeenPartIndex = maxSeenPartIndex;
            this.selectedLaterVolume = selectedLaterVolume;
            this.prefix = prefix;
        }

        @NonNull
        Style style() {
            return style;
        }

        @NonNull
        File selected() {
            return selected;
        }

        @NonNull
        File firstVolume() {
            return firstVolume;
        }

        @NonNull
        List<File> volumes() {
            return volumes;
        }

        int selectedPartIndex() {
            return selectedPartIndex;
        }

        int nextMissingPartIndex() {
            return nextMissingPartIndex;
        }

        int maxSeenPartIndex() {
            return maxSeenPartIndex;
        }

        boolean hasKnownGap() {
            return nextMissingPartIndex >= 0 && maxSeenPartIndex >= nextMissingPartIndex;
        }

        boolean selectedLaterVolume() {
            return selectedLaterVolume;
        }

        @NonNull
        String prefix() {
            return prefix;
        }

        boolean hasSplitCompanions() {
            return volumes.size() > 1;
        }
    }

    @NonNull
    static Result resolve(@NonNull File selected) {
        File parent = selected.getParentFile();
        if (parent == null) return single(selected);

        String name = selected.getName();
        Matcher newStyle = NEW_STYLE_PART.matcher(name);
        if (newStyle.matches()) {
            String prefix = newStyle.group(1);
            int selectedIndex = parseNonNegativeInt(newStyle.group(2));
            Chain chain = collectNewStyle(parent, prefix);
            if (!chain.volumes.isEmpty()) {
                return new Result(
                        Style.NEW_STYLE_PART,
                        selected,
                        chain.volumes.get(0),
                        chain.volumes,
                        selectedIndex,
                        chain.nextMissingIndex,
                        chain.maxSeenIndex,
                        selectedIndex > 1,
                        prefix);
            }
            if (selectedIndex > 1 && chain.maxSeenIndex >= selectedIndex) {
                return new Result(
                        Style.NEW_STYLE_PART,
                        selected,
                        expectedNewStyleFirstVolume(parent, prefix, newStyle.group(2)),
                        chain.volumes,
                        selectedIndex,
                        chain.nextMissingIndex,
                        chain.maxSeenIndex,
                        true,
                        prefix);
            }
            return single(selected);
        }

        Matcher oldStyle = OLD_STYLE_PART.matcher(name);
        if (oldStyle.matches()) {
            String prefix = oldStyle.group(1);
            int selectedIndex = parseNonNegativeInt(oldStyle.group(2));
            Chain chain = collectOldStyle(parent, prefix);
            if (!chain.volumes.isEmpty()) {
                return new Result(
                        Style.OLD_STYLE_RAR_PLUS_RNN,
                        selected,
                        chain.volumes.get(0),
                        chain.volumes,
                        selectedIndex,
                        chain.nextMissingIndex,
                        chain.maxSeenIndex,
                        true,
                        prefix);
            }
            if (selectedIndex >= 0) {
                return new Result(
                        Style.OLD_STYLE_RAR_PLUS_RNN,
                        selected,
                        new File(parent, prefix + ".rar"),
                        chain.volumes,
                        selectedIndex,
                        chain.nextMissingIndex >= 0 ? chain.nextMissingIndex : 0,
                        Math.max(chain.maxSeenIndex, selectedIndex),
                        true,
                        prefix);
            }
            return single(selected);
        }

        if (name.toLowerCase(Locale.ROOT).endsWith(".rar")) {
            String prefix = name.substring(0, name.length() - 4);
            Chain newStyleChain = collectNewStyle(parent, prefix);
            if (newStyleChain.volumes.size() > 1) {
                return new Result(
                        Style.BASE_RAR_WITH_NEW_STYLE_COMPANIONS,
                        selected,
                        newStyleChain.volumes.get(0),
                        newStyleChain.volumes,
                        0,
                        newStyleChain.nextMissingIndex,
                        newStyleChain.maxSeenIndex,
                        false,
                        prefix);
            }
            Chain oldStyleChain = collectOldStyle(parent, prefix);
            if (oldStyleChain.volumes.size() > 1) {
                return new Result(
                        Style.BASE_RAR_WITH_OLD_STYLE_COMPANIONS,
                        selected,
                        oldStyleChain.volumes.get(0),
                        oldStyleChain.volumes,
                        0,
                        oldStyleChain.nextMissingIndex,
                        oldStyleChain.maxSeenIndex,
                        false,
                        prefix);
            }
        }
        return single(selected);
    }

    @NonNull
    private static File expectedNewStyleFirstVolume(@NonNull File parent,
                                                   @NonNull String prefix,
                                                   @NonNull String selectedDigits) {
        int width = Math.max(1, selectedDigits.length());
        String number = String.format(Locale.ROOT, "%0" + width + "d", 1);
        return new File(parent, prefix + ".part" + number + ".rar");
    }

    @NonNull
    private static Result single(@NonNull File selected) {
        List<File> volumes = new ArrayList<>();
        volumes.add(selected);
        return new Result(Style.SINGLE, selected, selected, volumes, 0, -1, 0, false, selected.getName());
    }

    @NonNull
    private static Chain collectNewStyle(@NonNull File parent, @NonNull String prefix) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(prefix) + "\\.part(\\d{1,6})\\.rar$",
                Pattern.CASE_INSENSITIVE);
        Map<Integer, File> byIndex = new TreeMap<>();
        File[] files = sortedFiles(parent);
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            Matcher matcher = pattern.matcher(file.getName());
            if (!matcher.matches()) continue;
            int index = parseNonNegativeInt(matcher.group(1));
            if (index <= 0) continue;
            putDeterministic(byIndex, index, file);
        }
        return contiguous(byIndex, 1);
    }

    @NonNull
    private static Chain collectOldStyle(@NonNull File parent, @NonNull String prefix) {
        File first = new File(parent, prefix + ".rar");
        if (!first.isFile()) return new Chain(new ArrayList<File>(), -1, -1);

        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(prefix) + "\\.r(\\d{2,3})$",
                Pattern.CASE_INSENSITIVE);
        Map<Integer, File> byIndex = new TreeMap<>();
        File[] files = sortedFiles(parent);
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            Matcher matcher = pattern.matcher(file.getName());
            if (!matcher.matches()) continue;
            int index = parseNonNegativeInt(matcher.group(1));
            if (index < 0) continue;
            putDeterministic(byIndex, index, file);
        }

        Chain continuations = contiguous(byIndex, 0);
        List<File> result = new ArrayList<>();
        result.add(first);
        result.addAll(continuations.volumes);
        return new Chain(result, continuations.nextMissingIndex, continuations.maxSeenIndex);
    }

    @NonNull
    private static Chain contiguous(@NonNull Map<Integer, File> byIndex, int firstIndex) {
        List<File> result = new ArrayList<>();
        int maxSeen = -1;
        for (Integer seen : byIndex.keySet()) {
            if (seen != null && seen > maxSeen) maxSeen = seen;
        }
        int index = firstIndex;
        for (; index <= 9999; index++) {
            File file = byIndex.get(index);
            if (file == null) break;
            result.add(file);
        }
        int nextMissing = byIndex.isEmpty() ? -1 : index;
        return new Chain(result, nextMissing, maxSeen);
    }

    private static void putDeterministic(@NonNull Map<Integer, File> byIndex,
                                         int index,
                                         @NonNull File file) {
        File existing = byIndex.get(index);
        if (existing == null || file.getName().compareToIgnoreCase(existing.getName()) < 0) {
            byIndex.put(index, file);
        }
    }

    @NonNull
    private static File[] sortedFiles(@NonNull File parent) {
        File[] files = parent.listFiles();
        if (files == null) return new File[0];
        List<File> list = new ArrayList<>();
        Collections.addAll(list, files);
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a == b) return 0;
                if (a == null) return -1;
                if (b == null) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return list.toArray(new File[0]);
    }

    private static int parseNonNegativeInt(@Nullable String value) {
        if (value == null || value.length() == 0) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static final class Chain {
        final List<File> volumes;
        final int nextMissingIndex;
        final int maxSeenIndex;

        Chain(@NonNull List<File> volumes, int nextMissingIndex, int maxSeenIndex) {
            this.volumes = volumes;
            this.nextMissingIndex = nextMissingIndex;
            this.maxSeenIndex = maxSeenIndex;
        }
    }
}
