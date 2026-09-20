package com.readwide.manager.model;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** A presentation snapshot of the EXISTING folder_shortcuts preference, not a second store. */
public final class HomeShortcut {
    public enum Status { AVAILABLE, MISSING, NOT_DIRECTORY, UNREADABLE }
    public interface Probe { Status inspect(String path); }
    public final String path;
    public final String title;
    public final Status status;

    private HomeShortcut(String path, Status status) {
        this.path = path;
        String name = new File(path).getName();
        this.title = name.isEmpty() ? path : name;
        this.status = status;
    }

    /** No traversal, contents scan or canonicalization: retain the saved locator verbatim. */
    public static Status inspectFileSystem(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return Status.MISSING;
            if (!file.isDirectory()) return Status.NOT_DIRECTORY;
            return file.canRead() ? Status.AVAILABLE : Status.UNREADABLE;
        } catch (SecurityException denied) {
            return Status.UNREADABLE;
        }
    }

    /** Call on a worker. Unavailable pins remain visible and removable. */
    public static List<HomeShortcut> load(List<String> savedPaths, Probe probe)
            throws InterruptedIOException {
        if (probe == null) throw new NullPointerException("probe");
        List<HomeShortcut> rows = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (savedPaths == null) return Collections.emptyList();
        for (String path : savedPaths) {
            checkInterrupted();
            // PrefsManager already trims legacy newline-delimited values. Do not further
            // rewrite case, spaces, symlinks or paths, or removal may hit a different pin.
            if (path == null || path.trim().isEmpty() || !seen.add(path)) continue;
            Status status;
            try { status = probe.inspect(path); }
            catch (SecurityException denied) { status = Status.UNREADABLE; }
            if (status == null) status = Status.UNREADABLE;
            rows.add(new HomeShortcut(path, status));
        }
        checkInterrupted();
        return Collections.unmodifiableList(rows);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Home shortcut refresh cancelled");
        }
    }
}
