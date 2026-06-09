package com.textview.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validation and naming policy for plain-ZIP archive creation.
 *
 * Keeps source filtering and destination-name generation out of the UI/controller
 * code so queue handling remains focused on user flow and progress handling.
 */
final class MainArchiveCreationPlanner {
    private MainArchiveCreationPlanner() {
    }

    @NonNull
    static ArrayList<File> collectReadySources(@NonNull List<File> sources) {
        ArrayList<File> ready = new ArrayList<>();
        for (File source : sources) {
            if (source == null || !source.exists() || !source.canRead()) continue;
            if (!source.isFile() && !source.isDirectory()) continue;
            ready.add(source);
        }
        return ready;
    }

    static boolean isWritableParent(@Nullable File parent) {
        return parent != null && parent.exists() && parent.isDirectory() && parent.canWrite();
    }

    @Nullable
    static File freshDestinationFor(@NonNull File parent,
                                    @NonNull List<File> sources,
                                    @NonNull File preferred,
                                    @NonNull String defaultName) {
        if (!preferred.exists()) return preferred;
        return buildArchiveDestination(parent, sources, defaultName);
    }

    @Nullable
    static File buildArchiveDestination(@NonNull File parent,
                                        @NonNull List<File> sources,
                                        @NonNull String defaultName) {
        String base = sources.size() == 1 ? stripExtension(sources.get(0).getName()) : defaultName;
        base = sanitizeBaseName(base);
        if (base.length() == 0) base = defaultName;
        for (int i = 0; i < 10000; i++) {
            String name = i == 0 ? base + ".zip" : base + " (" + i + ").zip";
            File candidate = new File(parent, name);
            if (!candidate.exists()) return candidate;
        }
        return null;
    }

    @NonNull
    private static String stripExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    @NonNull
    private static String sanitizeBaseName(@NonNull String raw) {
        String value = raw.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals(".") || lower.equals("..")) return "";
        return value;
    }
}
