package com.readwide.manager.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, metadata-only handoff guard for RAR and standard split 7z sources.
 * This is not a content hash or permission to bypass image/integrity validation.
 * Other families retain their caller's existing single-file snapshot checks.
 */
public final class ArchiveSourceSnapshot {
    private final String selectedPath;
    private final String chainIdentity;
    private final List<Stamp> volumes;

    private ArchiveSourceSnapshot(String selectedPath, String chainIdentity, List<Stamp> volumes) {
        this.selectedPath = selectedPath;
        this.chainIdentity = chainIdentity;
        this.volumes = Collections.unmodifiableList(new ArrayList<>(volumes));
    }

    /** Null means outside this guard's scope; an unresolved in-scope set never matches. */
    @Nullable
    public static ArchiveSourceSnapshot capture(@NonNull File selected) {
        boolean rar = ArchiveTypeDetector.fromFileName(selected.getName()) == ArchiveSupport.Type.RAR
                && !ArchiveSupport.isNumericSplitArchive(selected);
        boolean sevenZ = SevenZSplitVolumeResolver.isSevenZSplitPart(selected);
        if (!rar && !sevenZ) return null;
        try {
            // The name resolvers can treat an unavailable directory as an empty catalog.
            // Do not turn that into a valid one-volume handoff snapshot.
            File parent = selected.getAbsoluteFile().getParentFile();
            if (parent == null || parent.listFiles() == null) {
                throw new IOException("Archive volume directory is unavailable");
            }
            String path = new Stamp(selected).path;
            List<File> files;
            String identity;
            if (rar) {
                RarVolumeChainResolution chain = RarArchiveLocator.resolveVolumeChain(selected);
                files = chain.requireReadableChain();
                // Includes discovered gaps/ordinals beyond the contiguous readable prefix.
                identity = chain.diagnostic();
            } else {
                SevenZSplitVolumeResolver.VolumeSet set = SevenZSplitVolumeResolver.resolve(selected);
                if (set == null) throw new IOException("Split 7z source is unresolved");
                files = set.parts;
                identity = "7z";
            }
            if (files.isEmpty()) throw new IOException("Archive volume set is empty");
            List<Stamp> stamps = new ArrayList<>();
            for (File file : files) stamps.add(new Stamp(file));
            return new ArchiveSourceSnapshot(path, identity, stamps);
        } catch (IOException | SecurityException unavailable) {
            // Deliberately distinct from null: failed capture must fail closed even
            // if the files become available again before the handoff is consumed.
            return new ArchiveSourceSnapshot(null, null, Collections.emptyList());
        }
    }

    /** Re-resolve at handoff boundaries, never on the bitmap/paging hot path. */
    public boolean matches(@NonNull File selected) {
        if (selectedPath == null) return false;
        ArchiveSourceSnapshot current = capture(selected);
        if (current == null || !selectedPath.equals(current.selectedPath)
                || !chainIdentity.equals(current.chainIdentity)
                || volumes.size() != current.volumes.size()) return false;
        for (int i = 0; i < volumes.size(); i++) {
            if (!volumes.get(i).matches(current.volumes.get(i))) return false;
        }
        return true;
    }

    private static final class Stamp {
        final String path;
        final long length;
        final long modified;

        Stamp(File file) throws IOException {
            if (!file.isFile() || !file.canRead()) throw new IOException("Archive volume is unavailable");
            path = file.getCanonicalPath();
            length = file.length();
            modified = file.lastModified();
        }

        boolean matches(Stamp other) {
            return path.equals(other.path) && length == other.length && modified == other.modified;
        }
    }
}
