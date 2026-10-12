package com.readwide.manager.util;

/** Lexical rebinding: the old directory no longer exists after a successful move. */
public final class NavigationPathRewriter {
    private NavigationPathRewriter() {}

    public static String rebase(String path, String oldRoot, String newRoot) {
        if (path == null || oldRoot == null || newRoot == null) return path;
        oldRoot = withoutTrailingSlash(oldRoot);
        newRoot = withoutTrailingSlash(newRoot);
        if (oldRoot.isEmpty() || newRoot.isEmpty() || oldRoot.equals("/")
                || newRoot.equals("/") || oldRoot.equals(newRoot)) return path;
        if (path.equals(oldRoot)) return newRoot;
        // Case-sensitive, just like Android paths; /Books must not match /Bookshelf.
        if (path.startsWith(oldRoot + "/")) return newRoot + path.substring(oldRoot.length());
        return path;
    }

    private static String withoutTrailingSlash(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') --end;
        return path.substring(0, end);
    }
}
