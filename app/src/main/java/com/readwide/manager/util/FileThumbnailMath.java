package com.readwide.manager.util;

import androidx.annotation.Nullable;

import java.util.Locale;

/** Pure filename selection for lightweight file-browser cover thumbnails. */
public final class FileThumbnailMath {
    private FileThumbnailMath() {}

    public static int firstImageIndex(@Nullable String[] names) {
        if (names == null || names.length == 0) return -1;
        int best = -1;
        String bestName = null;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (!isThumbnailImageName(name)) continue;
            if (bestName == null || NaturalSort.compare(name, bestName) < 0) {
                best = i;
                bestName = name;
            }
        }
        return best;
    }

    public static boolean isThumbnailImageName(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".jfif")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp")
                || lower.endsWith(".wbmp")
                || lower.endsWith(".gif")
                || lower.endsWith(".dng")
                || lower.endsWith(".heic")
                || lower.endsWith(".heif")
                || lower.endsWith(".avif");
    }

    public static boolean isThumbnailArchiveName(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(zip|zipx|7z|cb7)\\.001$")) return true;
        return lower.endsWith(".zip")
                || lower.endsWith(".zipx")
                || lower.endsWith(".cbz")
                || lower.endsWith(".rar")
                || lower.endsWith(".cbr")
                || lower.endsWith(".cab")
                || lower.endsWith(".lha")
                || lower.endsWith(".lzh")
                || lower.endsWith(".7z")
                || lower.endsWith(".cb7")
                || lower.endsWith(".alz")
                || lower.endsWith(".egg")
                || lower.endsWith(".tar")
                || lower.endsWith(".cbt")
                || lower.endsWith(".tar.gz")
                || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2")
                || lower.endsWith(".tbz2")
                || lower.endsWith(".tbz")
                || lower.endsWith(".tar.xz")
                || lower.endsWith(".txz")
                || lower.endsWith(".tar.lzma")
                || lower.endsWith(".tlz")
                || lower.endsWith(".tar.z")
                || lower.endsWith(".taz")
                || lower.endsWith(".tar.zst")
                || lower.endsWith(".tzst")
                || lower.endsWith(".tar.lz4");
    }

    public static boolean isThumbnailCandidateName(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return isThumbnailImageName(lower)
                || isThumbnailArchiveName(lower)
                || lower.endsWith(".pdf")
                || lower.endsWith(".epub");
    }

    public static int firstCandidateIndex(@Nullable String[] names) {
        if (names == null || names.length == 0) return -1;
        int best = -1;
        String bestName = null;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (!isThumbnailCandidateName(name)) continue;
            if (bestName == null || NaturalSort.compare(name, bestName) < 0) {
                best = i;
                bestName = name;
            }
        }
        return best;
    }

    public static String fileName(@Nullable String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
