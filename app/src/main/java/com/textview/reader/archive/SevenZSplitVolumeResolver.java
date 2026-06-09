package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves standard 7-Zip split volumes such as book.7z.001, book.7z.002, ... .
 *
 * <p>Commons Compress can read multi-volume 7z data when the volume files are
 * exposed as one concatenated SeekableByteChannel. This helper deliberately
 * accepts only the common 7-Zip naming form so that unrelated .001 files are not
 * silently treated as 7z volumes.</p>
 */
final class SevenZSplitVolumeResolver {
    private SevenZSplitVolumeResolver() {}

    private static final Pattern STANDARD_7Z_PART = Pattern.compile("^(.+\\.(?:7z|cb7))\\.(\\d{3})$", Pattern.CASE_INSENSITIVE);

    static final class VolumeSet {
        @NonNull final File firstPart;
        @NonNull final List<File> parts;
        @NonNull final String displayName;

        VolumeSet(@NonNull File firstPart, @NonNull List<File> parts, @NonNull String displayName) {
            this.firstPart = firstPart;
            this.parts = parts;
            this.displayName = displayName;
        }
    }

    static boolean isSevenZSplitPartName(@NonNull String fileName) {
        return parsePartNumber(fileName) > 0;
    }

    static boolean isSevenZSplitPart(@NonNull File file) {
        return isSevenZSplitPartName(file.getName());
    }

    @NonNull
    static File resolveFirstPart(@NonNull File selectedPart) throws IOException {
        PartName partName = parsePartName(selectedPart.getName());
        if (partName == null) return selectedPart;
        File parent = selectedPart.getParentFile();
        if (parent == null) throw new IOException("7z split archive has no parent directory");
        File first = new File(parent, partName.stem + ".001");
        if (!first.exists() || !first.isFile()) {
            throw new IOException("First 7z split volume is missing: " + first.getName());
        }
        return first;
    }

    @Nullable
    static VolumeSet resolve(@NonNull File archive) throws IOException {
        if (!isSevenZSplitPart(archive)) return null;
        File first = resolveFirstPart(archive);
        PartName firstName = parsePartName(first.getName());
        if (firstName == null || firstName.number != 1) {
            throw new IOException("Invalid 7z split first volume");
        }
        List<File> parts = collectContiguousParts(first, firstName.stem);
        return new VolumeSet(first, Collections.unmodifiableList(parts), firstName.stem);
    }

    @NonNull
    private static List<File> collectContiguousParts(@NonNull File firstPart, @NonNull String stem) throws IOException {
        File parent = firstPart.getParentFile();
        if (parent == null) throw new IOException("7z split archive has no parent directory");
        List<File> parts = new ArrayList<>();
        int expectedMissing = -1;
        for (int index = 1; index <= 999; index++) {
            File part = new File(parent, stem + String.format(Locale.ROOT, ".%03d", index));
            if (!part.exists() || !part.isFile()) {
                expectedMissing = index;
                break;
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new IOException("First 7z split volume is missing: " + stem + ".001");
        }
        if (expectedMissing > 0 && hasLaterVolume(parent, stem, expectedMissing + 1)) {
            throw new IOException("Missing 7z split volume: " + stem + String.format(Locale.ROOT, ".%03d", expectedMissing));
        }
        return parts;
    }

    private static boolean hasLaterVolume(@NonNull File parent, @NonNull String stem, int startIndex) {
        for (int index = startIndex; index <= 999; index++) {
            File part = new File(parent, stem + String.format(Locale.ROOT, ".%03d", index));
            if (part.exists() && part.isFile()) return true;
        }
        return false;
    }

    private static int parsePartNumber(@NonNull String fileName) {
        PartName parsed = parsePartName(fileName);
        return parsed == null ? -1 : parsed.number;
    }

    @Nullable
    private static PartName parsePartName(@NonNull String fileName) {
        Matcher matcher = STANDARD_7Z_PART.matcher(fileName);
        if (!matcher.matches()) return null;
        int number;
        try {
            number = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (number <= 0) return null;
        return new PartName(matcher.group(1), number);
    }

    private static final class PartName {
        @NonNull final String stem;
        final int number;

        PartName(@NonNull String stem, int number) {
            this.stem = stem;
            this.number = number;
        }
    }
}
