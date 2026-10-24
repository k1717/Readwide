package com.readwide.manager.util;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Conservative regular-file identity/metadata check around a read or replacement.
 * This is not a lock: an unrelated writer can still race the last check and rename/unlink.
 */
public final class FileMutationGuard {
    private final File file;
    private final File parent;
    private final Stamp original;
    private final long legacyModifiedMillis;

    private FileMutationGuard(File file, File parent, Stamp original) {
        this.file = file;
        this.parent = parent;
        this.original = original;
        legacyModifiedMillis = Build.VERSION.SDK_INT > 0 && Build.VERSION.SDK_INT < 27
                ? file.lastModified() : 0L;
    }

    public static FileMutationGuard capture(File file) throws IOException {
        File parent = canonicalParent(file);
        Stamp stamp = readPath(file);
        if (stamp.identity == null) throw new IOException("Cannot identify source file: " + file);
        FileMutationGuard guard = new FileMutationGuard(file, parent, stamp);
        guard.recheck();
        return guard;
    }

    public void recheck() throws IOException {
        if (!Objects.equals(parent, canonicalParent(file)) || !original.matches(readPath(file))
                || (Build.VERSION.SDK_INT > 0 && Build.VERSION.SDK_INT < 27
                    && legacyModifiedMillis != file.lastModified())) {
            throw new IOException("Source file changed: " + file);
        }
    }

    public void verifyOpened(FileInputStream input) throws IOException {
        if (Build.VERSION.SDK_INT != 0) {
            try {
                if (!original.matches(fromStat(Os.fstat(input.getFD())))) {
                    throw new IOException("Opened source file changed: " + file);
                }
            } catch (ErrnoException failure) {
                throw new IOException("Cannot inspect opened source: " + file, failure);
            }
        } else if (input.getChannel().size() != original.bytes) {
            // Local JVM tests have no public descriptor-relative file-key API.
            throw new IOException("Opened source size changed: " + file);
        }
        recheck();
    }

    private static File canonicalParent(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        return parent == null ? null : parent.getCanonicalFile();
    }

    private static Stamp readPath(File file) throws IOException {
        if (Build.VERSION.SDK_INT == 0) return NioAccess.read(file);
        try {
            return fromStat(Os.lstat(file.getPath()));
        } catch (ErrnoException failure) {
            throw new IOException("Cannot inspect source: " + file, failure);
        }
    }

    private static Stamp fromStat(StructStat stat) throws IOException {
        if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("Source is not a regular file");
        long modifiedNanos = Build.VERSION.SDK_INT >= 27 ? StatNanos.modified(stat) : 0L;
        long changedNanos = Build.VERSION.SDK_INT >= 27 ? StatNanos.changed(stat) : 0L;
        return new Stamp(stat.st_ino == 0 ? null : stat.st_dev + ":" + stat.st_ino,
                stat.st_size, new Timestamp(stat.st_mtime, modifiedNanos),
                new Timestamp(stat.st_ctime, changedNanos));
    }

    private static final class Stamp {
        final Object identity, modified, changed;
        final long bytes;
        Stamp(Object identity, long bytes, Object modified, Object changed) {
            this.identity = identity;
            this.bytes = bytes;
            this.modified = modified;
            this.changed = changed;
        }
        boolean matches(Stamp other) {
            return bytes == other.bytes && Objects.equals(identity, other.identity)
                    && Objects.equals(modified, other.modified) && Objects.equals(changed, other.changed);
        }
    }

    private static final class Timestamp {
        final long seconds, nanos;
        Timestamp(long seconds, long nanos) { this.seconds = seconds; this.nanos = nanos; }
        @Override public boolean equals(Object other) {
            return other instanceof Timestamp && seconds == ((Timestamp) other).seconds
                    && nanos == ((Timestamp) other).nanos;
        }
        @Override public int hashCode() { return Objects.hash(seconds, nanos); }
    }

    @android.annotation.TargetApi(27)
    private static final class StatNanos {
        static long modified(StructStat stat) { return stat.st_mtim.tv_nsec; }
        static long changed(StructStat stat) { return stat.st_ctim.tv_nsec; }
    }

    @android.annotation.TargetApi(26)
    private static final class NioAccess {
        static Stamp read(File file) throws IOException {
            java.nio.file.Path path = file.toPath();
            java.nio.file.LinkOption noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS;
            try {
                // One provider read keeps identity and times from the same stat.
                java.util.Map<String, Object> attributes = java.nio.file.Files.readAttributes(
                        path, "unix:ino,dev,size,lastModifiedTime,ctime,isRegularFile", noFollow);
                if (!Boolean.TRUE.equals(attributes.get("isRegularFile"))) {
                    throw new IOException("Source is not a regular file: " + file);
                }
                Object inode = attributes.get("ino");
                return new Stamp(inode == null || Long.valueOf(0L).equals(inode) ? null
                        : attributes.get("dev") + ":" + inode,
                        ((Number) attributes.get("size")).longValue(),
                        attributes.get("lastModifiedTime"), attributes.get("ctime"));
            } catch (UnsupportedOperationException unsupported) {
                java.nio.file.attribute.BasicFileAttributes attributes = java.nio.file.Files.readAttributes(
                        path, java.nio.file.attribute.BasicFileAttributes.class, noFollow);
                if (!attributes.isRegularFile()) throw new IOException("Source is not a regular file: " + file);
                // Windows/basic-only providers expose identity and high-resolution mtime, not ctime.
                return new Stamp(attributes.fileKey(), attributes.size(),
                        attributes.lastModifiedTime(), attributes.creationTime());
            }
        }
    }
}
