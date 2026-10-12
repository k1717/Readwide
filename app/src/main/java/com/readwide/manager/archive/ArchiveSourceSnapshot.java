package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable metadata snapshot and preview namespace for an archive source.
 * RAR, ALZ, EGG, Amiga LHA, linked CAB, PKZIP and generic numeric splits include their resolved volume set.
 * Other families currently retain a single-file stamp; this is NOT a content hash or an integrity/authentication check.
 * Capture once per loading/session boundary; reuse cacheFingerprint() per page.
 */
public final class ArchiveSourceSnapshot {
    @Nullable private final String selectedPath;
    private final String family;
    private final List<Stamp> volumes;
    private final String fingerprint;

    private ArchiveSourceSnapshot(@Nullable String selectedPath, String family, List<Stamp> volumes) {
        this.selectedPath = selectedPath;
        this.family = family;
        this.volumes = Collections.unmodifiableList(new ArrayList<>(volumes));
        // Failed capture must never alias a previously verified plaintext cache.
        this.fingerprint = selectedPath == null
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                : fingerprint(family, this.volumes);
    }

    /** Failed capture returns a nonmatching snapshot, never a reusable singleton fallback. */
    @NonNull
    public static ArchiveSourceSnapshot capture(@NonNull File selected) {
        try {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            Stamp selectedStamp = new Stamp(selected);
            String numericStem = ArchiveTypeDetector.numericSplitStem(selected.getName());
            boolean rar = numericStem == null
                    && ArchiveTypeDetector.fromFile(selected) == ArchiveSupport.Type.RAR;
            List<File> files;
            String family;
            if (ArchiveTypeDetector.fromFileName(selected.getName()) == ArchiveSupport.Type.LIBARCHIVE
                    && CabVolumeResolver.hasVolumeLinks(selected)) {
                files = CabVolumeResolver.resolve(selected).parts;
                family = "cab";
            } else if (numericStem != null) {
                files = SevenZSplitVolumeResolver.resolveNumeric(selected).parts;
                family = "numeric";
            } else if (LhaVolumeResolver.isCandidate(selected.getName())) {
                files = LhaVolumeResolver.resolve(selected);
                family = files.size() > 1 ? "lha-split" : "single";
            } else if (rar) {
                files = RarArchiveLocator.collectReadableVolumes(selected);
                family = "rar";
            } else if (selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".alz")
                    || AlzVolumeResolver.CONTINUATION.matcher(selected.getName()).matches()) {
                files = AlzVolumeResolver.resolve(selected).files;
                family = "alz";
            } else if (ArchiveTypeDetector.fromFileName(selected.getName()) == ArchiveSupport.Type.EGG) {
                files = EggVolumeResolver.resolve(selected).files;
                family = "egg";
            } else if (ZipVolumeResolver.isCandidate(selected.getName())) {
                files = ZipVolumeResolver.forSnapshot(selected);
                family = files.size() > 1 ? "pkzip" : "single";
            } else {
                files = Collections.singletonList(selected);
                family = "single";
            }
            if (files.isEmpty()) throw new IOException("Archive volume set is empty");
            ArrayList<Stamp> stamps = new ArrayList<>(files.size());
            boolean selectedFound = false;
            for (File file : files) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                Stamp stamp = new Stamp(file);
                stamps.add(stamp);
                if (stamp.path.equals(selectedStamp.path)) {
                    if (!stamp.matches(selectedStamp)) throw new IOException("Selected volume changed during capture");
                    selectedFound = true;
                }
            }
            if (!selectedFound) throw new IOException("Selected volume is not in the resolved chain");
            ArchiveCancellation.checkpoint(null);
            return new ArchiveSourceSnapshot(selectedStamp.path, family, stamps);
        } catch (IOException | SecurityException unavailable) {
            ArchiveCancellation.restoreIfCancelled(unavailable, null);
            return new ArchiveSourceSnapshot(null, "unresolved", Collections.emptyList());
        }
    }

    /** The same validated chain yields the same key regardless of which part was selected. */
    @NonNull
    public String cacheFingerprint() { return fingerprint; }

    /** Worker-only recheck of known volumes without reparsing archive headers. */
    public boolean matchesKnownFiles(@NonNull File selected) {
        if (selectedPath == null || Thread.currentThread().isInterrupted()) return false;
        try {
            if (!selectedPath.equals(selected.getCanonicalPath())) return false;
            for (Stamp stamp : volumes) {
                ArchiveCancellation.checkpoint(null);
                if (!stamp.matches(new Stamp(new File(stamp.path)))) return false;
            }
            ArchiveCancellation.checkpoint(null);
            return true;
        } catch (IOException | SecurityException unavailable) {
            ArchiveCancellation.restoreIfCancelled(unavailable, null);
            return false;
        }
    }

    /** Membership only; this does not certify current bytes or validate a failed snapshot. */
    boolean containsFile(@NonNull File candidate) throws IOException {
        if (selectedPath == null) return false;
        String path = candidate.getCanonicalPath();
        for (Stamp stamp : volumes) if (stamp.path.equals(path)) return true;
        return false;
    }

    /** Replacing a tree must not remove any source volume; unresolved capture is not permission. */
    void requireOutsideReplacementTree(@NonNull File destination) throws IOException {
        if (selectedPath == null) throw new IOException("Cannot resolve archive sources for destination replacement");
        File root = destination.getCanonicalFile();
        for (Stamp stamp : volumes) {
            ArchiveCancellation.checkpoint(null);
            // File equality respects the host filesystem's case rules. Do not use
            // string prefixes, which confuse sibling names such as book / book-old.
            for (File current = new File(stamp.path); current != null; current = current.getParentFile()) {
                if (root.equals(current)) throw new IOException("Replacement destination contains an archive source volume");
            }
        }
    }

    /** Re-resolve at handoff boundaries, not once for every cached bitmap or page. */
    public boolean matches(@NonNull File selected) {
        if (selectedPath == null) return false;
        ArchiveSourceSnapshot current = capture(selected);
        if (!selectedPath.equals(current.selectedPath) || !family.equals(current.family)
                || volumes.size() != current.volumes.size()) return false;
        for (int i = 0; i < volumes.size(); i++) {
            if (!volumes.get(i).matches(current.volumes.get(i))) return false;
        }
        return true;
    }

    private static String fingerprint(String family, List<Stamp> volumes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            putText(digest, "readwide-preview-v4");
            putText(digest, family);
            putLong(digest, volumes.size());
            for (Stamp stamp : volumes) {
                putText(digest, stamp.path);
                putLong(digest, stamp.length);
                putLong(digest, stamp.modified);
            }
            byte[] hash = digest.digest();
            char[] chars = new char[24];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 12; i++) {
                chars[i * 2] = hex[(hash[i] & 255) >>> 4];
                chars[i * 2 + 1] = hex[hash[i] & 15];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static void putText(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // Length framing avoids collisions caused by newline/path delimiter ambiguity.
        putLong(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static final class Stamp {
        final String path;
        final long length, modified;
        Stamp(File file) throws IOException {
            if (!file.isFile() || !file.canRead()) throw new IOException("Archive volume unavailable");
            path = file.getCanonicalPath();
            length = file.length();
            modified = file.lastModified();
        }
        boolean matches(Stamp other) {
            return path.equals(other.path) && length == other.length && modified == other.modified;
        }
    }
}
