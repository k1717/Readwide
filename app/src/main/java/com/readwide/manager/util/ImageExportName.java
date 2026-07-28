package com.readwide.manager.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Pure filename normalization for exporting an archive image page. */
public final class ImageExportName {
    private ImageExportName() {}

    @NonNull
    public static String safeDisplayName(@Nullable String rawName, @Nullable String mimeType) {
        String value = rawName == null ? "" : rawName.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        value = value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").trim();
        while (value.endsWith(".") || value.endsWith(" ")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) value = "image";
        if (extension(value).isEmpty()) value += extensionForMime(mimeType);
        return value;
    }

    @NonNull
    public static String stem(@NonNull String displayName) {
        int dot = displayName.lastIndexOf('.');
        return dot > 0 ? displayName.substring(0, dot) : displayName;
    }

    @NonNull
    public static String extension(@NonNull String displayName) {
        int dot = displayName.lastIndexOf('.');
        return dot > 0 && dot < displayName.length() - 1
                ? displayName.substring(dot)
                : "";
    }

    @NonNull
    private static String extensionForMime(@Nullable String mimeType) {
        if (mimeType == null) return ".png";
        switch (mimeType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/jpeg": return ".jpg";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/bmp": return ".bmp";
            case "image/avif": return ".avif";
            default: return ".png";
        }
    }
}
