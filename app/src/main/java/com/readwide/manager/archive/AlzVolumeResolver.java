package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One catalogue for ALZ routing, payload windows and preview source identity. */
final class AlzVolumeResolver {
    static final Pattern CONTINUATION = Pattern.compile("^(.+)\\.a([0-9]{2,})$", Pattern.CASE_INSENSITIVE);
    private static final int HEADER = 0x015a4c41;
    private static final int CENTRAL = 0x015a4c43;
    private static final int LAST = 0x025a4c43;
    private static final int MORE = 0x035a4c43;
    private AlzVolumeResolver() { }

    static final class VolumeSet {
        final List<File> files;
        final List<SplitVolumeInput.Segment> segments;
        VolumeSet(List<File> files, List<SplitVolumeInput.Segment> segments) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }
    }

    static File resolveFirstVolume(File selected) throws IOException {
        return catalogue(selected).get(0);
    }

    /** List actual names once; never probe all numbers up to an attacker-supplied ordinal. */
    private static List<File> catalogue(File selected) throws IOException {
        checkpoint();
        requireFile(selected);
        Matcher selectedPart = CONTINUATION.matcher(selected.getName());
        String name = selected.getName();
        boolean continuation = selectedPart.matches();
        String base;
        if (continuation) {
            ordinal(selectedPart.group(2));
            base = selectedPart.group(1);
        } else if (name.length() > 4 && name.regionMatches(true, name.length() - 4, ".alz", 0, 4)) {
            base = name.substring(0, name.length() - 4);
        } else {
            // Signature-routed standalone files have no filename-defined continuation family.
            return Collections.singletonList(selected);
        }
        File parent = selected.getAbsoluteFile().getParentFile();
        File[] siblings = parent == null ? null : parent.listFiles();
        if (siblings == null) throw new IOException("Cannot read ALZ volume directory");
        File first = null;
        TreeMap<Long, File> parts = new TreeMap<>();
        for (File sibling : siblings) {
            checkpoint();
            if (sibling.getName().equalsIgnoreCase(base + ".alz")) {
                if (first != null) throw new IOException("Ambiguous ALZ first volume");
                requireFile(sibling);
                first = sibling;
                continue;
            }
            Matcher part = CONTINUATION.matcher(sibling.getName());
            if (!part.matches() || !part.group(1).equalsIgnoreCase(base)) continue;
            requireFile(sibling);
            long number = ordinal(part.group(2));
            if (parts.put(number, sibling) != null) throw new IOException("Ambiguous ALZ volume number: " + number);
        }
        if (first == null) throw new IOException("Missing ALZ first volume: " + base + ".alz");
        ArrayList<File> result = new ArrayList<>();
        result.add(first);
        long expected = 0;
        boolean selectedFound = first.getCanonicalPath().equals(selected.getCanonicalPath());
        for (Map.Entry<Long, File> part : parts.entrySet()) {
            if (part.getKey() != expected) throw new IOException("Missing ALZ split volume number: " + expected);
            result.add(part.getValue());
            selectedFound |= part.getValue().getCanonicalPath().equals(selected.getCanonicalPath());
            expected++;
        }
        if (!selectedFound) throw new IOException("Selected file is not in the ALZ volume set");
        return result;
    }

    static VolumeSet resolve(File selected) throws IOException {
        List<File> files = catalogue(selected);
        ArrayList<SplitVolumeInput.Segment> segments = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            checkpoint();
            File file = files.get(i);
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long size = input.length();
                boolean header = size >= 4 && readInt(input) == HEADER;
                boolean framed = false;
                int tail = 0;
                if (size >= 16) {
                    input.seek(size - 16);
                    framed = readInt(input) == CENTRAL;
                    input.seek(size - 4);
                    tail = readInt(input);
                    framed &= tail == LAST || tail == MORE;
                }
                if (files.size() == 1) {
                    if (framed && tail == MORE) throw new IOException("Missing ALZ continuation after " + file.getName());
                    segments.add(new SplitVolumeInput.Segment(file, 0, size));
                } else if (i == 0 || header) {
                    if (!header || !framed || size < 24) {
                        throw new IOException("Invalid ALZ split framing: " + file.getName());
                    }
                    boolean last = i == files.size() - 1;
                    if (tail != (last ? LAST : MORE)) throw new IOException("ALZ split end marker disagrees with volume set");
                    long start = i == 0 ? 0 : 8;
                    segments.add(new SplitVolumeInput.Segment(file, start, size - start - 16));
                } else {
                    // Retain the existing unframed continuation compatibility path.
                    segments.add(new SplitVolumeInput.Segment(file, 0, size));
                }
            }
        }
        return new VolumeSet(files, segments);
    }

    private static int readInt(RandomAccessFile input) throws IOException { return Integer.reverseBytes(input.readInt()); }
    private static long ordinal(String digits) throws IOException {
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            if (digit < 0 || digit > 9 || value > (Long.MAX_VALUE - digit) / 10) throw new IOException("Invalid ALZ volume number");
            value = value * 10 + digit;
        }
        return value;
    }
    private static void requireFile(File file) throws IOException {
        if (!file.isFile() || !file.canRead()) throw new IOException("ALZ volume unavailable: " + file.getName());
    }
    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ALZ operation cancelled");
    }
}
