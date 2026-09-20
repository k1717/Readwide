package com.readwide.manager.archive;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata-only catalogue for PKZIP .z01, .z02, ..., final .zip/.zipx/.cbz sets.
 * It does not concatenate these volumes (their ZIP offsets are disk-relative),
 * decode entries, or replace the backend's central-directory validation.
 */
final class ZipVolumeResolver {
    private static final Pattern PART = Pattern.compile("^(.*)\\.z([0-9]{2,})$", Pattern.CASE_INSENSITIVE);
    private static final int END_SIZE = 22, MAX_COMMENT = 65535;

    private ZipVolumeResolver() { }

    static boolean isCandidate(String name) {
        return isFinal(name) || PART.matcher(name).matches();
    }

    private static boolean isFinal(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".zipx") || lower.endsWith(".cbz");
    }

    static List<File> forSnapshot(File selected) throws IOException {
        checkpoint();
        if (!selected.isFile() || !selected.canRead()) throw new IOException("ZIP source is unavailable");
        Matcher part = PART.matcher(selected.getName());
        boolean continuation = part.matches();
        File parent = selected.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("ZIP source has no parent");
        File[] siblings = null;
        File endFile = selected;
        if (continuation) {
            siblings = parent.listFiles();
            if (siblings == null) throw new IOException("Cannot enumerate ZIP parts");
            endFile = null;
            for (File file : siblings) {
                checkpoint();
                String name = file.getName();
                if (isFinal(name) && stem(name).equals(part.group(1))) {
                    if (endFile != null) throw new IOException("Ambiguous final ZIP volume");
                    endFile = file;
                }
            }
            if (endFile == null) throw new IOException("Missing final ZIP volume");
        }
        if (!endFile.isFile() || !endFile.canRead()) throw new IOException("Final ZIP volume is unavailable");
        Long disk = readLastDisk(endFile);
        // This helper supplies cache identity, not format validation. Preserve
        // single-file identities for existing callers using opaque/damaged ZIPs.
        // A declared split set or continuation is always validated fail-closed.
        if (disk == null) {
            if (continuation) throw new IOException("Missing ZIP end record");
            if (siblings == null) siblings = parent.listFiles();
            if (siblings == null) throw new IOException("Cannot enumerate ZIP parts");
            for (File file : siblings) {
                checkpoint();
                Matcher candidate = PART.matcher(file.getName());
                if (candidate.matches() && stem(selected.getName()).equals(candidate.group(1))) {
                    throw new IOException("Split ZIP has no unambiguous end record");
                }
            }
            return Collections.singletonList(selected);
        }
        if (disk == 0) {
            if (continuation) throw new IOException("Selected ZIP part is not in a split set");
            return Collections.singletonList(endFile);
        }
        if (siblings == null) siblings = parent.listFiles();
        if (siblings == null) throw new IOException("Cannot enumerate ZIP parts");
        // Reject impossible counts BEFORE constructing an ordinal-sized array or
        // probing every missing name. Memory scales with actual directory data.
        if (disk > siblings.length) throw new IOException("Missing split ZIP volumes");
        String base = stem(endFile.getName());
        Map<Long, File> found = new TreeMap<>();
        for (File file : siblings) {
            checkpoint();
            Matcher match = PART.matcher(file.getName());
            if (!match.matches() || !base.equals(match.group(1))) continue;
            long ordinal;
            try { ordinal = Long.parseLong(match.group(2)); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid split ZIP ordinal", invalid); }
            if (ordinal <= 0) throw new IOException("Invalid split ZIP ordinal");
            if (ordinal > disk) continue; // Not a member of the declared set.
            // Zip4j's actual lookup uses exact stem + lowercase .z + >=2 digits.
            // Do not cache a permissive alias the decoder cannot open on a
            // case-sensitive filesystem, or choose between duplicate aliases.
            String expected = base + ".z" + (ordinal < 10 ? "0" : "") + ordinal;
            if (!file.getName().equals(expected)) throw new IOException("Noncanonical split ZIP volume name");
            if (!file.isFile() || !file.canRead()) throw new IOException("ZIP volume is unavailable");
            if (found.put(ordinal, file) != null) throw new IOException("Duplicate split ZIP ordinal");
        }
        if (found.size() != disk) throw new IOException("Missing split ZIP volume");
        List<File> ordered = new ArrayList<>(found.size() + 1);
        long expected = 1;
        boolean selectedFound = !continuation;
        String selectedPath = selected.getCanonicalPath();
        for (Map.Entry<Long, File> entry : found.entrySet()) {
            checkpoint();
            if (entry.getKey() != expected++) throw new IOException("Missing split ZIP volume");
            ordered.add(entry.getValue());
            selectedFound |= selectedPath.equals(entry.getValue().getCanonicalPath());
        }
        if (!selectedFound) throw new IOException("Selected ZIP part is outside the declared set");
        ordered.add(endFile);
        return Collections.unmodifiableList(ordered);
    }

    private static String stem(String name) { return name.substring(0, name.lastIndexOf('.')); }

    /** Only a bounded tail is read; payloads and the central directory are not loaded. */
    private static Long readLastDisk(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            int size = (int) Math.min(length, END_SIZE + MAX_COMMENT + 20L);
            if (size < END_SIZE) return null;
            byte[] tail = new byte[size];
            input.seek(length - size);
            input.readFully(tail);
            int end = -1;
            for (int i = size - END_SIZE; i >= 0; i--) {
                if ((i & 4095) == 0) checkpoint();
                if (u32(tail, i) == 0x06054b50L && u16(tail, i + 20) == size - i - END_SIZE) {
                    if (end >= 0) throw new IOException("Ambiguous ZIP end records");
                    end = i;
                }
            }
            if (end < 0) return null;
            long disk = u16(tail, end + 4), centralDisk = u16(tail, end + 6);
            int locator = end - 20;
            if (locator >= 0 && u32(tail, locator) == 0x07064b50L) {
                long zip64Disk = u32(tail, locator + 4), totalDisks = u32(tail, locator + 16);
                if (totalDisks == 0 || zip64Disk >= totalDisks
                        || (disk != 65535 && disk != totalDisks - 1)
                        || (centralDisk != 65535 && centralDisk >= totalDisks)) {
                    throw new IOException("Inconsistent ZIP64 volume metadata");
                }
                return totalDisks - 1;
            }
            if (disk == 65535 || centralDisk == 65535) throw new IOException("Missing ZIP64 end locator");
            if (centralDisk > disk) throw new IOException("Invalid ZIP central directory disk");
            return disk;
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }
    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP volume discovery cancelled");
    }
}
