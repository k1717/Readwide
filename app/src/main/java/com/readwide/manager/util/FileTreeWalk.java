package com.readwide.manager.util;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Iterative, no-follow tree walk shared by inventory, copy and deletion. */
final class FileTreeWalk {
    enum Kind { FILE, DIRECTORY, LINK, OTHER, MISSING }

    static final class Entry {
        final File file;
        final String relative;
        final Kind kind;
        final long bytes;
        final Object identity;
        final File expectedParent;

        Entry(File file, String relative, Info info, File expectedParent) {
            this.file = file;
            this.relative = relative;
            this.kind = info.kind;
            this.bytes = info.kind == Kind.FILE ? Math.max(0L, info.bytes) : 0L;
            this.identity = info.identity;
            this.expectedParent = expectedParent;
        }

        void recheck() throws IOException {
            checkParent(file, expectedParent);
            Info now = inspect(file);
            if (now.kind != kind || (identity != null && !identity.equals(now.identity))) {
                throw new IOException("File changed during operation: " + file);
            }
        }
    }

    interface Visitor {
        boolean enter(Entry entry) throws IOException;
        default boolean leave(Entry directory) throws IOException { return true; }
    }

    private static final class Frame {
        final Entry entry;
        final File[] children;
        final File canonicalDirectory;
        int next;

        Frame(Entry entry, File[] children) throws IOException {
            this.entry = entry;
            this.children = children;
            canonicalDirectory = entry.file.getCanonicalFile();
        }
    }

    static boolean walk(File root, BooleanSupplier checkpoint, Visitor visitor) throws IOException {
        if (!checkpoint.getAsBoolean()) return false;
        File parent = root.getAbsoluteFile().getParentFile();
        File expectedParent = parent == null ? null : parent.getCanonicalFile();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        Set<Object> directories = new HashSet<>();
        Entry next = entry(root, "", expectedParent);
        while (true) {
            if (!checkpoint.getAsBoolean()) return false;
            if (next != null) {
                Entry current = next;
                next = null;
                if (!visitor.enter(current)) return false;
                if (current.kind == Kind.DIRECTORY) {
                    current.recheck();
                    Object key = current.identity != null ? current.identity : current.file.getCanonicalPath();
                    if (!directories.add(key)) throw new IOException("Directory cycle: " + current.file);
                    File[] children = current.file.listFiles();
                    if (children == null) throw new IOException("Cannot list directory: " + current.file);
                    current.recheck();
                    stack.push(new Frame(current, children));
                }
            }
            if (stack.isEmpty()) return true;
            Frame frame = stack.peek();
            if (frame.next < frame.children.length) {
                // Detect a parent being replaced by a link before visiting a child.
                // These path checks are not a descriptor-relative atomic transaction.
                frame.entry.recheck();
                File child = frame.children[frame.next++];
                if (child == null) continue;
                String relative = frame.entry.relative.isEmpty() ? child.getName()
                        : frame.entry.relative + File.separator + child.getName();
                next = entry(child, relative, frame.canonicalDirectory);
            } else {
                frame.entry.recheck();
                if (!visitor.leave(frame.entry)) return false;
                stack.pop();
            }
        }
    }

    private static Entry entry(File file, String relative, File expectedParent) throws IOException {
        checkParent(file, expectedParent);
        return new Entry(file, relative, inspect(file), expectedParent);
    }

    private static void checkParent(File file, File expected) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        File actual = parent == null ? null : parent.getCanonicalFile();
        if (!Objects.equals(expected, actual)) throw new IOException("Parent changed: " + file);
    }

    static Kind kind(File file) throws IOException { return inspect(file).kind; }

    static boolean existsNoFollow(File file) throws IOException { return kind(file) != Kind.MISSING; }

    private static Info inspect(File file) throws IOException {
        // SDK 0 is the local JVM Android stub. Keep Java NIO in a separate class
        // so Android 7.x (API 24/25) only uses its supported lstat implementation.
        if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= 26) return NioAccess.inspect(file);
        try {
            StructStat stat = Os.lstat(file.getPath());
            Kind kind = OsConstants.S_ISLNK(stat.st_mode) ? Kind.LINK
                    : OsConstants.S_ISDIR(stat.st_mode) ? Kind.DIRECTORY
                    : OsConstants.S_ISREG(stat.st_mode) ? Kind.FILE : Kind.OTHER;
            Object identity = stat.st_ino == 0 ? null : stat.st_dev + ":" + stat.st_ino;
            return new Info(kind, stat.st_size, identity);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return new Info(Kind.MISSING, 0L, null);
            throw new IOException("Cannot inspect: " + file, e);
        }
    }

    private static final class Info {
        final Kind kind;
        final long bytes;
        final Object identity;
        Info(Kind kind, long bytes, Object identity) {
            this.kind = kind; this.bytes = bytes; this.identity = identity;
        }
    }

    @android.annotation.TargetApi(26)
    private static final class NioAccess {
        static Info inspect(File file) throws IOException {
            try {
                java.nio.file.attribute.BasicFileAttributes attr = java.nio.file.Files.readAttributes(
                        file.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                Kind kind = attr.isSymbolicLink() ? Kind.LINK : attr.isDirectory() ? Kind.DIRECTORY
                        : attr.isRegularFile() ? Kind.FILE : Kind.OTHER;
                return new Info(kind, attr.size(), attr.fileKey());
            } catch (java.nio.file.NoSuchFileException missing) {
                return new Info(Kind.MISSING, 0L, null);
            }
        }
    }
}
