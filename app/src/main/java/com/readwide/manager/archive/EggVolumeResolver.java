package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Header-linked EGG volume discovery shared by routing, decoding and preview caching. */
final class EggVolumeResolver {
    private static final int EGG = 0x41474745, FILE = 0x0a8590e3;
    private static final int SPLIT = 0x24f5a262, END = 0x08e28222;
    private static final Pattern NAME = Pattern.compile("^(.+\\.vol)([0-9]+)(\\.egg)$", Pattern.CASE_INSENSITIVE);
    private EggVolumeResolver() { }

    static final class VolumeSet {
        final List<File> files;
        final List<SplitVolumeInput.Segment> segments;
        VolumeSet(List<File> files, List<SplitVolumeInput.Segment> segments) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }
    }
    static File resolveFirstVolume(File selected) throws IOException { return resolve(selected).files.get(0); }

    static VolumeSet resolve(File selected) throws IOException {
        checkpoint();
        requireFile(selected);
        Matcher selectedName = NAME.matcher(selected.getName());
        Catalogue catalogue = selectedName.matches() ? new Catalogue(selected, selectedName.group(1)) : null;
        if (catalogue != null) catalogue.require(number(selectedName.group(2)));
        File first = catalogue == null ? selected : catalogue.require(1);
        Prefix prefix = prefix(first);
        if (prefix.split && prefix.prev != 0) throw new IOException("EGG first volume links to a missing predecessor");
        ArrayList<File> files = new ArrayList<>();
        ArrayList<SplitVolumeInput.Segment> segments = new ArrayList<>();
        files.add(first);
        segments.add(new SplitVolumeInput.Segment(first, 0, first.length()));
        long ordinal = 1;
        while (prefix.split && prefix.next != 0) {
            checkpoint();
            if (catalogue == null) throw new IOException("EGG split filename must contain .volN.egg");
            // The finite directory catalogue, not a three-digit constant, bounds this walk.
            if (ordinal >= catalogue.files.size()) throw new IOException("Missing EGG continuation volume");
            File next = catalogue.require(++ordinal);
            Prefix following = prefix(next);
            if (!following.split || following.prev != prefix.id || following.id != prefix.next) {
                throw new IOException("EGG split volume chain mismatch at " + next.getName());
            }
            files.add(next);
            segments.add(new SplitVolumeInput.Segment(next, following.offset, next.length() - following.offset));
            prefix = following;
        }
        String selectedPath = selected.getCanonicalPath();
        boolean included = false;
        for (File file : files) included |= file.getCanonicalPath().equals(selectedPath);
        if (!included) throw new IOException("Selected EGG volume is outside the declared chain");
        return new VolumeSet(files, segments);
    }

    private static final class Catalogue {
        final Map<Long, File> files = new LinkedHashMap<>();
        final Set<Long> aliases = new HashSet<>();
        Catalogue(File selected, String stem) throws IOException {
            File parent = selected.getAbsoluteFile().getParentFile();
            File[] siblings = parent == null ? null : parent.listFiles();
            if (siblings == null) throw new IOException("Cannot read EGG split volume directory");
            for (File sibling : siblings) {
                checkpoint();
                Matcher name = NAME.matcher(sibling.getName());
                if (!name.matches() || !name.group(1).equalsIgnoreCase(stem)) continue;
                long n = number(name.group(2));
                // Keep directories in the catalogue so they cannot hide a missing/ambiguous part.
                if (files.putIfAbsent(n, sibling) != null) aliases.add(n);
            }
        }
        File require(long n) throws IOException {
            checkpoint();
            if (aliases.contains(n)) throw new IOException("Ambiguous EGG split volume number: " + n);
            File file = files.get(n);
            if (file == null) throw new IOException("Missing EGG split volume number: " + n);
            requireFile(file);
            return file;
        }
    }
    private static final class Prefix { long id, prev, next, offset; boolean split; }

    private static Prefix prefix(File file) throws IOException {
        checkpoint();
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            if (size < 14 || readInt(input) != EGG) throw new IOException("Invalid EGG volume signature: " + file.getName());
            input.skipBytes(2); // version; existing versions share the same header layout
            Prefix result = new Prefix();
            result.id = uint(input);
            input.skipBytes(4); // reserved
            while (input.getFilePointer() <= size - 4) {
                checkpoint();
                long start = input.getFilePointer();
                int signature = readInt(input);
                if (signature == END || signature == FILE) {
                    result.offset = signature == END ? input.getFilePointer() : start;
                    return result; // retain legacy prefix without END
                }
                int flags = input.readUnsignedByte();
                long length = (flags & 1) == 0 ? (Short.reverseBytes(input.readShort()) & 0xffff) : uint(input);
                long payload = input.getFilePointer();
                if (length > size - payload) throw new IOException("EGG prefix field exceeds physical volume");
                if (signature == SPLIT) {
                    if (result.split || length < 8) throw new IOException("Invalid or duplicate EGG split field");
                    result.split = true;
                    result.prev = uint(input);
                    result.next = uint(input);
                }
                input.seek(payload + length);
            }
            throw new IOException("Truncated EGG volume prefix");
        }
    }
    private static long number(String text) throws IOException {
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9 || value > (Long.MAX_VALUE - digit) / 10) throw new IOException("Invalid EGG volume number");
            value = value * 10 + digit;
        }
        if (value == 0) throw new IOException("Invalid EGG volume number: zero");
        return value;
    }
    private static int readInt(RandomAccessFile input) throws IOException { return Integer.reverseBytes(input.readInt()); }
    private static long uint(RandomAccessFile input) throws IOException { return (readInt(input) & 0xffffffffL); }
    private static void requireFile(File file) throws IOException {
        if (!file.isFile() || !file.canRead()) throw new IOException("EGG volume unavailable: " + file.getName());
    }
    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("EGG operation cancelled");
    }
}
