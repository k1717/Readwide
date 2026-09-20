package com.readwide.manager.util;

/** Archive entry spelling shared by preview cache keys and image sequence keys. */
public final class ArchiveEntryPaths {
    private ArchiveEntryPaths() {}

    public static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        int start = 0;
        while (start + 1 < normalized.length()
                && normalized.charAt(start) == '.' && normalized.charAt(start + 1) == '/') {
            start += 2;
        }
        // Strip prefixes before collapsing slashes: repeating the prefix step
        // afterward would change existing cache identities and image ordering.
        int duplicate = normalized.indexOf("//", start);
        if (duplicate < 0) return normalized.substring(start);
        StringBuilder result = new StringBuilder(normalized.length() - start);
        result.append(normalized, start, duplicate + 1);
        boolean slash = true;
        for (int i = duplicate + 1; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c != '/' || !slash) result.append(c);
            slash = c == '/';
        }
        return result.toString();
    }
}
