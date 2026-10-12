package com.readwide.manager.archive;

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

/** Amiga LhA byte splits: name.lha/name.lzh, name.l01, name.l02, ... */
final class LhaVolumeResolver {
    private LhaVolumeResolver() {}

    private static final Pattern CONTINUATION = Pattern.compile("^(.+)\\.l([0-9]{2,10})$", Pattern.CASE_INSENSITIVE);

    static boolean isCandidate(String name) { return stem(name) != null; }

    static String continuationStem(String name) {
        Matcher match = CONTINUATION.matcher(name);
        if (!match.matches()) return null;
        String digits = match.group(2);
        if (digits.length() > 2 && digits.charAt(0) == '0') return null;
        try { return Integer.parseInt(digits) > 0 ? match.group(1) : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String stem(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if ((lower.endsWith(".lha") || lower.endsWith(".lzh")) && name.length() > 4) {
            return name.substring(0, name.length() - 4);
        }
        return continuationStem(name);
    }

    /** Filename association, not proof of payload integrity; framing is checked after merging. */
    static List<File> resolve(File selected) throws IOException {
        checkpoint();
        String base = stem(selected.getName());
        if (base == null) throw new IOException("Invalid LHA volume name");
        if (!selected.isFile() || !selected.canRead()) throw new IOException("Selected LHA volume unavailable");
        File parent = selected.getAbsoluteFile().getParentFile();
        File[] children = parent == null ? null : parent.listFiles();
        if (children == null) throw new IOException("LHA volume directory unavailable");
        List<File> first = new ArrayList<>();
        TreeMap<Integer, File> continuations = new TreeMap<>();
        for (File child : children) {
            checkpoint();
            if (!base.equalsIgnoreCase(stem(child.getName()))) continue;
            String continuation = continuationStem(child.getName());
            if (continuation == null) {
                first.add(child);
            } else {
                int ordinal = Integer.parseInt(child.getName().substring(continuation.length() + 2));
                if (continuations.put(ordinal, child) != null) throw new IOException("Ambiguous LHA continuation volume");
            }
        }
        // Two ordinary archives with the same stem are independent unless continuations exist.
        if (continuations.isEmpty() && continuationStem(selected.getName()) == null) {
            return Collections.singletonList(selected);
        }
        if (first.size() != 1) throw new IOException("Missing or ambiguous first LHA volume");
        List<File> parts = new ArrayList<>();
        parts.add(first.get(0));
        long expected = 1;
        for (Map.Entry<Integer, File> entry : continuations.entrySet()) {
            checkpoint();
            if (entry.getKey().longValue() != expected++) throw new IOException("Missing LHA continuation volume");
            parts.add(entry.getValue());
        }
        String selection = selected.getCanonicalPath();
        boolean found = false;
        for (File part : parts) {
            checkpoint();
            if (!part.isFile() || !part.canRead() || part.length() == 0) throw new IOException("LHA volume unavailable or empty");
            if (part.getCanonicalPath().equals(selection)) found = true;
        }
        if (!found) throw new IOException("Selected LHA volume is outside the discovered set");
        return Collections.unmodifiableList(parts);
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("LHA volume resolution cancelled");
    }
}
