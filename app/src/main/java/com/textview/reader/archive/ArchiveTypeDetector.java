package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filename and lightweight signature detection for archive families.
 *
 * <p>ArchiveSupport should stay focused on listing/extraction routing. Keeping
 * archive-name heuristics here prevents extension/split/SFX rules from being
 * duplicated across extraction, recent-state, and release-QA helpers.</p>
 */
final class ArchiveTypeDetector {
    private ArchiveTypeDetector() {}

    static final Pattern RAR_NEW_STYLE_PART = Pattern.compile("^(.*)\\.part(\\d+)\\.rar$", Pattern.CASE_INSENSITIVE);
    static final Pattern RAR_OLD_STYLE_PART = Pattern.compile("^(.*)\\.r(\\d{2,3})$", Pattern.CASE_INSENSITIVE);
    static final Pattern EGG_VOLUME_PART = Pattern.compile("^(.*)\\.vol(\\d+)\\.egg$", Pattern.CASE_INSENSITIVE);
    static final Pattern ALZ_VOLUME_PART = Pattern.compile("^(.*)\\.a(\\d{2,3})$", Pattern.CASE_INSENSITIVE);

    private static final String[] OUTPUT_BASE_EXTENSIONS = new String[] {
            ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.lzma", ".tar.z",
            ".tgz", ".tbz2", ".tbz", ".txz", ".tlz", ".taz",
            ".lzma", ".bz2", ".gz", ".xz", ".z",
            ".zip", ".zipx", ".cbz", ".rar", ".cbr",
            ".alz", ".egg", ".cb7", ".7z", ".cbt", ".tar"
    };

    @Nullable
    static ArchiveSupport.Type fromFile(@NonNull File file) {
        if (!file.isFile()) return null;
        ArchiveSupport.Type splitType = getAlzipSplitArchiveType(file);
        if (splitType != null) return splitType;
        ArchiveSupport.Type nameType = fromFileName(file.getName());
        if (nameType != null) return nameType;
        return hasEmbeddedRarSignatureForSfx(file) ? ArchiveSupport.Type.RAR : null;
    }

    @Nullable
    static ArchiveSupport.Type fromFileName(@NonNull String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (SevenZSplitVolumeResolver.isSevenZSplitPartName(fileName)) return ArchiveSupport.Type.SEVEN_Z;
        if (isFirstNumericSplitName(name)) {
            ArchiveSupport.Type splitBaseType = fromFileName(name.substring(0, name.length() - 4));
            if (splitBaseType != null) return splitBaseType;
        }
        if (isFirstRarSplitName(name)) return ArchiveSupport.Type.RAR;
        if (RAR_OLD_STYLE_PART.matcher(name).matches()) return ArchiveSupport.Type.RAR;
        if (EGG_VOLUME_PART.matcher(name).matches()) return ArchiveSupport.Type.EGG;
        if (name.endsWith(".zip") || name.endsWith(".zipx") || name.endsWith(".cbz")) return ArchiveSupport.Type.ZIP;
        if (name.endsWith(".rar") || name.endsWith(".cbr")) return ArchiveSupport.Type.RAR;
        if (name.endsWith(".alz")) return ArchiveSupport.Type.ALZ;
        if (name.endsWith(".egg")) return ArchiveSupport.Type.EGG;
        if (name.endsWith(".7z") || name.endsWith(".cb7")) return ArchiveSupport.Type.SEVEN_Z;
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) return ArchiveSupport.Type.TAR_GZ;
        if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz")) return ArchiveSupport.Type.TAR_BZ2;
        if (name.endsWith(".tar.xz") || name.endsWith(".txz")) return ArchiveSupport.Type.TAR_XZ;
        if (name.endsWith(".tar.lzma") || name.endsWith(".tlz")) return ArchiveSupport.Type.TAR_LZMA;
        if (name.endsWith(".tar.z") || name.endsWith(".taz")) return ArchiveSupport.Type.TAR_Z;
        if (name.endsWith(".tar") || name.endsWith(".cbt")) return ArchiveSupport.Type.TAR;
        if (name.endsWith(".gz")) return ArchiveSupport.Type.SINGLE_GZ;
        if (name.endsWith(".bz2")) return ArchiveSupport.Type.SINGLE_BZ2;
        if (name.endsWith(".xz")) return ArchiveSupport.Type.SINGLE_XZ;
        if (name.endsWith(".lzma")) return ArchiveSupport.Type.SINGLE_LZMA;
        if (name.endsWith(".z")) return ArchiveSupport.Type.SINGLE_Z;
        return null;
    }

    static boolean isFirstNumericSplitName(@NonNull String lowerName) {
        return lowerName.endsWith(".001") && lowerName.length() > 4;
    }

    static boolean isFirstRarSplitName(@NonNull String lowerName) {
        Matcher matcher = RAR_NEW_STYLE_PART.matcher(lowerName);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(2)) == 1;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return lowerName.endsWith(".rar");
    }

    static boolean isRarSplitPart(@NonNull File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.endsWith(".rar")
                || RAR_NEW_STYLE_PART.matcher(lower).matches()
                || RAR_OLD_STYLE_PART.matcher(lower).matches();
    }

    static boolean isAlzipSplitPart(@NonNull File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return EGG_VOLUME_PART.matcher(lower).matches() || ALZ_VOLUME_PART.matcher(lower).matches();
    }

    @Nullable
    static ArchiveSupport.Type getAlzipSplitArchiveType(@NonNull File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (EGG_VOLUME_PART.matcher(lower).matches()) return ArchiveSupport.Type.EGG;
        Matcher alzPart = ALZ_VOLUME_PART.matcher(lower);
        if (!alzPart.matches()) return null;
        File parent = file.getParentFile();
        if (parent == null) return null;
        String prefix = file.getName().substring(0, lower.lastIndexOf(".a"));
        File first = new File(parent, prefix + ".alz");
        return first.exists() && first.isFile() ? ArchiveSupport.Type.ALZ : null;
    }

    @NonNull
    static String outputBaseName(@NonNull File archive, @NonNull String fallback) {
        String name = archive.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (isFirstNumericSplitName(lower) || SevenZSplitVolumeResolver.isSevenZSplitPartName(name)) {
            name = name.substring(0, name.length() - 4);
            lower = lower.substring(0, lower.length() - 4);
        }
        Matcher rarPartMatcher = RAR_NEW_STYLE_PART.matcher(name);
        if (rarPartMatcher.matches()) {
            name = rarPartMatcher.group(1) + ".rar";
            lower = name.toLowerCase(Locale.ROOT);
        }
        for (String ext : OUTPUT_BASE_EXTENSIONS) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name.length() > 0 ? name : fallback;
    }

    private static boolean hasEmbeddedRarSignatureForSfx(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".exe") && !name.endsWith(".sfx") && !name.endsWith(".bin")) {
            return false;
        }
        try {
            return RarArchiveReader.findEmbeddedRarSignatureOffsetForBackend(file) >= 0L;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }
}
