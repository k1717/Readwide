package com.readwide.manager.archive;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Selected compressed history and its volume chains, not codec or byte validation. */
final class RarSolidReadPlan {
    static final class Step {
        final RarArchiveReader.RarEntry entry;
        final List<RarArchiveReader.RarEntry> parts;

        private Step(RarArchiveReader.RarEntry entry, List<RarArchiveReader.RarEntry> parts) {
            this.entry = entry;
            this.parts = parts;
        }
    }

    final List<Step> steps;

    private RarSolidReadPlan(List<Step> steps) {
        this.steps = Collections.unmodifiableList(steps);
    }

    /**
     * Two iterator passes: select the latest compressed reset through the target,
     * then resolve only those heads' volume parts. No per-primer archive rescan.
     * Stored files remain outside these decoders' history, as before; this does not
     * admit archives that require stored bytes as solid dictionary/model input.
     */
    @Nullable
    static RarSolidReadPlan prepare(RarArchiveReader.RarEntry target,
            List<RarArchiveReader.RarEntry> entries, int version) throws IOException {
        checkpoint();
        if ((version != 4 && version != 5) || target.rarVersion != version
                || target.directory || target.splitBefore || isStored(target, version)) return null;
        List<RarArchiveReader.RarEntry> heads = new ArrayList<>();
        boolean resetFound = false, targetFound = false;
        for (RarArchiveReader.RarEntry entry : entries) {
            checkpoint();
            if (entry == null || entry.directory || entry.splitBefore || isStored(entry, version)) continue;
            if (!entry.solid) {
                heads.clear();
                resetFound = true;
            }
            if (resetFound) heads.add(entry);
            if (entry == target) {
                targetFound = true;
                break;
            }
        }
        if (!targetFound || !resetFound) return null;
        RarVolumeChain.Index volumes = RarVolumeChain.indexSelected(heads, entries);
        List<Step> steps = new ArrayList<>(heads.size());
        for (RarArchiveReader.RarEntry head : heads) {
            checkpoint();
            steps.add(new Step(head, volumes.chain(head)));
        }
        return new RarSolidReadPlan(steps);
    }

    private static boolean isStored(RarArchiveReader.RarEntry entry, int version) {
        return version == 4 ? RarFeatureClassifier.isRar3Or4StoredMethod(entry.method) : entry.method == 0;
    }

    private static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RAR solid planning cancelled");
    }
}
