package com.readwide.manager.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Diagnostic knobs for primary-context PPMd updates.
 *
 * <p>The reference RAR PPMd model updates a multi-state primary context by adding four to the decoded state's frequency
 * and then moving it only if it becomes more frequent than its predecessor. The target fixture now
 * matches the PNG signature, but diverges immediately after it. This policy object lets the
 * non-success probe compare that reference-shaped update with deliberately weaker variants without
 * enabling production extraction or pretending that any variant is correct.</p>
 */
final class RarPpmdPrimaryUpdatePolicy {
    @NonNull private final String name;
    private final int frequencyDelta;
    private final boolean promoteOneStepIfMoreFrequent;
    private final boolean rescaleIfNeeded;

    private RarPpmdPrimaryUpdatePolicy(@NonNull String name,
                                       int frequencyDelta,
                                       boolean promoteOneStepIfMoreFrequent,
                                       boolean rescaleIfNeeded) throws IOException {
        if (name.trim().length() == 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd primary update policy name is empty");
        }
        if (frequencyDelta < 0 || frequencyDelta > 16) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd primary update frequency delta is invalid: "
                            + frequencyDelta);
        }
        this.name = name;
        this.frequencyDelta = frequencyDelta;
        this.promoteOneStepIfMoreFrequent = promoteOneStepIfMoreFrequent;
        this.rescaleIfNeeded = rescaleIfNeeded;
    }

    @NonNull
    static RarPpmdPrimaryUpdatePolicy referenceShaped() throws IOException {
        return new RarPpmdPrimaryUpdatePolicy("reference-shaped", 4, true, true);
    }

    @NonNull
    static RarPpmdPrimaryUpdatePolicy light() throws IOException {
        return new RarPpmdPrimaryUpdatePolicy("light-delta-1", 1, true, true);
    }

    @NonNull
    static RarPpmdPrimaryUpdatePolicy noPromote() throws IOException {
        return new RarPpmdPrimaryUpdatePolicy("delta-4-no-promote", 4, false, true);
    }

    @NonNull
    static RarPpmdPrimaryUpdatePolicy frozen() throws IOException {
        return new RarPpmdPrimaryUpdatePolicy("frozen", 0, false, false);
    }

    @NonNull
    String name() {
        return name;
    }

    int frequencyDelta() {
        return frequencyDelta;
    }

    boolean promoteOneStepIfMoreFrequent() {
        return promoteOneStepIfMoreFrequent;
    }

    boolean rescaleIfNeeded() {
        return rescaleIfNeeded;
    }

    @NonNull
    String diagnostic() {
        return "name=" + name
                + "; frequencyDelta=" + frequencyDelta
                + "; promoteOneStep=" + promoteOneStepIfMoreFrequent
                + "; rescale=" + rescaleIfNeeded;
    }
}
