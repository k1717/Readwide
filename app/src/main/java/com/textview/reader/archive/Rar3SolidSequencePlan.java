package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validated first-party classic-LZ sequence needed to prime one RAR3/RAR4 solid target. */
final class Rar3SolidSequencePlan {
    @NonNull final RarArchiveReader.RarEntry target;
    @NonNull private final List<RarArchiveReader.RarEntry> sequenceEntries;
    final int runStartIndex;
    final int targetIndex;
    final int primerEntryCount;
    final long primerUnpackedBytes;
    final long sequenceUnpackedBytes;
    @NonNull final String detail;

    private Rar3SolidSequencePlan(@NonNull RarArchiveReader.RarEntry target,
                                  @NonNull List<RarArchiveReader.RarEntry> sequenceEntries,
                                  int runStartIndex,
                                  int targetIndex,
                                  int primerEntryCount,
                                  long primerUnpackedBytes,
                                  long sequenceUnpackedBytes,
                                  @NonNull String detail) {
        this.target = target;
        this.sequenceEntries = sequenceEntries;
        this.runStartIndex = runStartIndex;
        this.targetIndex = targetIndex;
        this.primerEntryCount = primerEntryCount;
        this.primerUnpackedBytes = primerUnpackedBytes;
        this.sequenceUnpackedBytes = sequenceUnpackedBytes;
        this.detail = detail;
    }

    @Nullable
    static Rar3SolidSequencePlan forTarget(@NonNull List<RarArchiveReader.RarEntry> entries,
                                           @NonNull RarArchiveReader.RarEntry target) {
        int targetIndex = indexOfIdentity(entries, target);
        if (targetIndex < 0 || !isEligibleSolidTarget(target)) return null;
        int runStart = findRunStart(entries, targetIndex);
        List<RarArchiveReader.RarEntry> sequence = new ArrayList<>();
        long primerBytes = 0L;
        long sequenceBytes = 0L;
        int primerCount = 0;
        for (int i = runStart; i <= targetIndex; i++) {
            RarArchiveReader.RarEntry entry = entries.get(i);
            if (!isSequenceEntry(entry, i == runStart)) return null;
            sequence.add(entry);
            sequenceBytes = addSaturated(sequenceBytes, entry.unpackedSize);
            if (i < targetIndex) {
                primerCount++;
                primerBytes = addSaturated(primerBytes, entry.unpackedSize);
            }
        }
        if (sequence.isEmpty()) return null;
        String detail = "targetIndex=" + targetIndex
                + "; runStartIndex=" + runStart
                + "; sequenceEntries=" + sequence.size()
                + "; primerEntries=" + primerCount
                + "; targetUnpacked=" + target.unpackedSize
                + "; primerUnpacked=" + primerBytes
                + "; sequenceUnpacked=" + sequenceBytes;
        return new Rar3SolidSequencePlan(
                target,
                Collections.unmodifiableList(sequence),
                runStart,
                targetIndex,
                primerCount,
                primerBytes,
                sequenceBytes,
                detail);
    }

    @NonNull
    List<RarArchiveReader.RarEntry> sequenceEntries() {
        return sequenceEntries;
    }

    private static int indexOfIdentity(@NonNull List<RarArchiveReader.RarEntry> entries,
                                       @NonNull RarArchiveReader.RarEntry target) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == target) return i;
        }
        return -1;
    }

    private static int findRunStart(@NonNull List<RarArchiveReader.RarEntry> entries, int targetIndex) {
        int runStart = targetIndex;
        for (int i = targetIndex; i >= 0; i--) {
            RarArchiveReader.RarEntry entry = entries.get(i);
            if (entry == null || entry.directory) break;
            runStart = i;
            if (!entry.solid) break;
        }
        return runStart;
    }

    private static boolean isEligibleSolidTarget(@NonNull RarArchiveReader.RarEntry entry) {
        return entry.solid
                && Rar3FirstPartyArchiveExtractor.isFirstPartyCompressedCandidate(entry)
                && entry.sourceArchive != null
                && entry.unpackedSize >= 0L;
    }

    private static boolean isSequenceEntry(@Nullable RarArchiveReader.RarEntry entry, boolean runStart) {
        if (entry == null || entry.directory || entry.unpackedSize < 0L || entry.sourceArchive == null) return false;
        if (entry.splitBefore || entry.splitAfter || entry.encrypted()) return false;
        if (!Rar3FirstPartyArchiveExtractor.isFirstPartyCompressedCandidate(entry)) return false;
        return runStart || entry.solid;
    }

    private static long addSaturated(long a, long b) {
        if (b < 0L) return Long.MAX_VALUE;
        if (Long.MAX_VALUE - a < b) return Long.MAX_VALUE;
        return a + b;
    }
}
