package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict split-volume routing result used before parsing or backend handoff. */
final class RarVolumeChainResolution {
    private final RarVolumeNameResolver.Result resolverResult;
    private final boolean selectedInContiguousChain;
    private final boolean knownGapBeforeSelected;
    private final boolean knownGapInDiscoveredChain;
    private final String diagnostic;

    private RarVolumeChainResolution(@NonNull RarVolumeNameResolver.Result resolverResult) {
        this.resolverResult = resolverResult;
        this.selectedInContiguousChain = containsSameFile(resolverResult.volumes(), resolverResult.selected());
        this.knownGapInDiscoveredChain = resolverResult.hasKnownGap();
        this.knownGapBeforeSelected = resolverResult.selectedPartIndex() > 0
                && resolverResult.nextMissingPartIndex() >= 0
                && resolverResult.nextMissingPartIndex() < resolverResult.selectedPartIndex();
        this.diagnostic = buildDiagnostic(resolverResult,
                selectedInContiguousChain,
                knownGapBeforeSelected,
                knownGapInDiscoveredChain);
    }

    @NonNull
    static RarVolumeChainResolution resolve(@NonNull File selected) {
        return new RarVolumeChainResolution(RarVolumeNameResolver.resolve(selected));
    }

    @NonNull
    RarVolumeNameResolver.Style style() {
        return resolverResult.style();
    }

    @NonNull
    File selected() {
        return resolverResult.selected();
    }

    @NonNull
    File firstVolume() {
        return resolverResult.firstVolume();
    }

    @NonNull
    List<File> volumes() {
        return resolverResult.volumes();
    }

    boolean selectedLaterVolume() {
        return resolverResult.selectedLaterVolume();
    }

    boolean selectedInContiguousChain() {
        return selectedInContiguousChain;
    }

    boolean knownGapBeforeSelected() {
        return knownGapBeforeSelected;
    }

    boolean knownGapInDiscoveredChain() {
        return knownGapInDiscoveredChain;
    }

    int selectedPartIndex() {
        return resolverResult.selectedPartIndex();
    }

    int nextMissingPartIndex() {
        return resolverResult.nextMissingPartIndex();
    }

    int maxSeenPartIndex() {
        return resolverResult.maxSeenPartIndex();
    }

    @NonNull
    String diagnostic() {
        return diagnostic;
    }

    @NonNull
    List<File> requireReadableChain() throws IOException {
        if (knownGapBeforeSelected || (resolverResult.selectedLaterVolume() && !selectedInContiguousChain)) {
            throw incompleteChainException();
        }
        if (knownGapInDiscoveredChain && resolverResult.volumes().size() <= 1) {
            throw incompleteChainException();
        }
        return new ArrayList<>(resolverResult.volumes());
    }

    @NonNull
    IOException incompleteChainException() {
        return new IOException("RAR split volume chain is incomplete: " + diagnostic);
    }

    @NonNull
    private static String buildDiagnostic(@NonNull RarVolumeNameResolver.Result result,
                                          boolean selectedInContiguousChain,
                                          boolean knownGapBeforeSelected,
                                          boolean knownGapInDiscoveredChain) {
        StringBuilder sb = new StringBuilder();
        sb.append("style=").append(result.style());
        sb.append(", selected=").append(result.selected().getName());
        sb.append(", firstVolume=").append(result.firstVolume().getName());
        sb.append(", selectedPartIndex=").append(result.selectedPartIndex());
        sb.append(", selectedLaterVolume=").append(result.selectedLaterVolume());
        sb.append(", selectedInContiguousChain=").append(selectedInContiguousChain);
        sb.append(", nextMissingPartIndex=").append(result.nextMissingPartIndex());
        sb.append(", maxSeenPartIndex=").append(result.maxSeenPartIndex());
        sb.append(", knownGapBeforeSelected=").append(knownGapBeforeSelected);
        sb.append(", knownGapInDiscoveredChain=").append(knownGapInDiscoveredChain);
        sb.append(", volumes=").append(volumeNames(result.volumes()));
        return sb.toString();
    }

    @NonNull
    private static List<String> volumeNames(@NonNull List<File> volumes) {
        List<String> names = new ArrayList<>();
        for (File volume : volumes) {
            names.add(volume.getName());
        }
        return Collections.unmodifiableList(names);
    }

    private static boolean containsSameFile(@NonNull List<File> files, @NonNull File needle) {
        for (File file : files) {
            if (sameFileIdentity(file, needle)) return true;
        }
        return false;
    }

    private static boolean sameFileIdentity(@NonNull File a, @NonNull File b) {
        if (a.equals(b)) return true;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }
}
