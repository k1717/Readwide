package com.readwide.manager.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Per-operation dependency plan for the plain, single-volume RAR4 compressed fallback. */
final class Rar3DecodePlan {
    enum Action { DIRECTORY, STORED, INDEPENDENT_LZ, START_CHECKED, CONTINUE_CHECKED }

    interface StartProbe { Rar3PpmdBlockProbe.Result probe(RarArchiveReader.RarEntry entry); }

    static final class Step {
        final RarArchiveReader.RarEntry entry;
        final Action action;
        /** Step containing this compressed run's primer, or -1 for non-compressed entries. */
        final int runStart;
        private Step(RarArchiveReader.RarEntry entry, Action action, int runStart) {
            this.entry = entry; this.action = action; this.runStart = runStart;
        }
    }

    private final List<Step> steps;
    private final List<RarArchiveReader.RarEntry> entries;
    final long totalUnpackedBytes;
    final boolean independentLzOnly;

    private Rar3DecodePlan(List<Step> steps, List<RarArchiveReader.RarEntry> entries,
                           long totalUnpackedBytes, boolean independentLzOnly) {
        this.steps = Collections.unmodifiableList(steps);
        this.entries = Collections.unmodifiableList(entries);
        this.totalUnpackedBytes = totalUnpackedBytes;
        this.independentLzOnly = independentLzOnly;
    }
    int size() { return steps.size(); }
    Step step(int index) { return steps.get(index); }
    List<RarArchiveReader.RarEntry> entries() { return entries; }

    static Rar3DecodePlan forArchive(List<RarArchiveReader.RarEntry> entries) throws IOException {
        return compile(entries, Rar3PpmdBlockProbe::probe);
    }

    /** Unrelated earlier/later unsupported runs must not prevent extracting an independent target. */
    static Rar3DecodePlan forTarget(List<RarArchiveReader.RarEntry> entries,
                                    RarArchiveReader.RarEntry target) throws IOException {
        checkCancelled();
        if (!isCompressedCandidate(target)) return null;
        List<RarArchiveReader.RarEntry> snapshot = new ArrayList<>(entries);
        int index = -1;
        for (int i = 0; i < snapshot.size(); i++) {
            checkCancelled();
            if (snapshot.get(i) == target) { index = i; break; }
        }
        if (index < 0) return null;
        int start = index;
        for (; start >= 0; start--) {
            checkCancelled();
            RarArchiveReader.RarEntry entry = snapshot.get(start);
            if (entry == null || entry.directory) continue;
            // Stored/unknown/encrypted/split entries cannot supply this decoder's history.
            if (!isCompressedCandidate(entry)) return null;
            if (!entry.solid) break;
        }
        return start < 0 ? null : forArchive(snapshot.subList(start, index + 1));
    }

    /** One classification/probe per independent start; continuations are never probed as headers. */
    static Rar3DecodePlan compile(List<RarArchiveReader.RarEntry> source, StartProbe probe) throws IOException {
        List<RarArchiveReader.RarEntry> entries = new ArrayList<>();
        for (RarArchiveReader.RarEntry entry : source) {
            checkCancelled();
            if (entry != null) entries.add(snapshot(entry));
        }
        Action[] actions = new Action[entries.size()];
        int[] runStarts = new int[entries.size()];
        java.util.Arrays.fill(runStarts, -1);
        int runStart = -1;
        boolean sawCompressed = false;
        long total = 0;
        for (int i = 0; i < entries.size(); i++) {
            checkCancelled();
            RarArchiveReader.RarEntry entry = entries.get(i);
            if (entry.directory) { actions[i] = Action.DIRECTORY; continue; }
            if (isIndependentStored(entry)) {
                actions[i] = Action.STORED;
                runStart = -1;
            } else {
                if (!isCompressedCandidate(entry)) return null;
                sawCompressed = true;
                if (entry.solid) {
                    if (runStart < 0) return null;
                    // Only a primer used by a later entry needs strict boundary/state retention.
                    actions[runStart] = Action.START_CHECKED;
                    actions[i] = Action.CONTINUE_CHECKED;
                } else {
                    Rar3PpmdBlockProbe.Result result = probe.probe(entry);
                    checkCancelled();
                    if (result.isClassicLz()) actions[i] = Action.INDEPENDENT_LZ;
                    else if (result.isPpmd() && (result.rawFlags & 0x2000) != 0) actions[i] = Action.START_CHECKED;
                    else return null;
                    runStart = i;
                }
                runStarts[i] = runStart;
            }
            total = entry.unpackedSize > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + entry.unpackedSize;
        }
        if (!sawCompressed) return null;
        List<Step> steps = new ArrayList<>(entries.size());
        boolean independentOnly = true;
        for (int i = 0; i < entries.size(); i++) {
            checkCancelled();
            steps.add(new Step(entries.get(i), actions[i], runStarts[i]));
            independentOnly &= actions[i] != Action.START_CHECKED && actions[i] != Action.CONTINUE_CHECKED;
        }
        return new Rar3DecodePlan(steps, entries, total, independentOnly);
    }

    static boolean isCompressedCandidate(RarArchiveReader.RarEntry entry) {
        return hasPlainData(entry) && entry.packedSize > 0
                && RarCompressedPayloadDecoder.isRar3Or4CompressionMethod(entry.method);
    }

    private static boolean isIndependentStored(RarArchiveReader.RarEntry entry) {
        return hasPlainData(entry) && !entry.solid && entry.packedSize == entry.unpackedSize
                && RarFeatureClassifier.isRar3Or4StoredMethod(entry.method);
    }

    private static boolean hasPlainData(RarArchiveReader.RarEntry entry) {
        return entry != null && entry.rarVersion == 4 && !entry.directory
                && !entry.encrypted() && !entry.splitBefore && !entry.splitAfter
                && entry.sourceArchive != null && entry.dataCrc >= 0 && entry.dataCrc <= 0xffffffffL
                && entry.unpackedSize >= 0 && entry.packedSize >= 0 && entry.dataOffset >= 0
                && entry.packedSize <= Long.MAX_VALUE - entry.dataOffset;
    }

    /** RarEntry's source field is mutable; do not retain a caller-owned routing decision. */
    private static RarArchiveReader.RarEntry snapshot(RarArchiveReader.RarEntry entry) {
        RarArchiveReader.RarEntry copy = new RarArchiveReader.RarEntry(entry.path, entry.directory,
                entry.unpackedSize, entry.packedSize, entry.dataOffset, entry.rarVersion, entry.method,
                entry.solid, entry.splitBefore, entry.splitAfter, entry.encryption, entry.dataCrc,
                entry.timeMillis, entry.rar5CompressionInfo, entry.blake2sp);
        copy.sourceArchive = entry.sourceArchive;
        return copy;
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAR extraction cancelled");
    }
}
