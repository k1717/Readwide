package com.readwide.manager.archive;

/**
 * Centralized first-party RAR3/RAR4 compressed-decoder gap messages.
 *
 * <p>Normal compressed RAR remains libarchive-primary. These messages describe only the
 * fallback first-party unpacker path, so they must not be worded as broad app-level RAR
 * incompatibilities.</p>
 */
final class Rar3FirstPartyGap {
    private Rar3FirstPartyGap() {}

    static RarArchiveReader.UnsupportedRarFeatureException ppmdBlock(boolean keepOldTable) {
        return ppmdModel(keepOldTable);
    }

    static RarArchiveReader.UnsupportedRarFeatureException ppmdModel(boolean keepOldTable) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 PPMd statistical model decoding is not implemented in the "
                        + "first-party unpacker path. The current source keeps only bounded "
                        + "diagnostic primitives for this path; real PPMd context traversal, "
                        + "suffix fallback, masked-symbol arithmetic decoding, SEE table "
                        + "selection, and full model updates are not live. libarchive remains "
                        + "the primary backend for normal compressed RAR (keepOldTable="
                        + keepOldTable + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException ppmdVmFilter(long outputOffset) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 VM filters embedded in PPMd control streams are not implemented in "
                        + "the first-party unpacker path; libarchive remains the primary backend "
                        + "for normal compressed RAR (outputOffset=" + outputOffset + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException vmFilter(long outputOffset) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 VM filters are not implemented in the first-party unpacker path; "
                        + "libarchive remains the primary backend for normal compressed RAR "
                        + "(outputOffset=" + outputOffset + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException vmFilter(RarVmFilter filter) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 VM filters are not wired into the first-party unpacker output "
                        + "pipeline. Metadata parsing and standard-filter primitives exist for "
                        + "diagnostics and narrow validated cases, but live extractor wiring, "
                        + "custom VM bytecode, Delta/RGB/Audio/Itanium/Upcase semantics, and "
                        + "PPMd-embedded filters remain gaps. libarchive remains the primary "
                        + "backend for normal compressed RAR ("
                        + filter.diagnosticSummary() + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException vmFilterMissingExecutionState(
            RarVmFilter filter) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 VM filters are not enabled in the first-party unpacker path; "
                        + "the live decoder still does not decode enough RAR VM register state "
                        + "to obtain the filtered output length and file offset. Normal "
                        + "compressed RAR remains libarchive-primary, and first-party VM filters "
                        + "must keep failing cleanly until VM state decoding, output pipeline "
                        + "timing, and filtered CRC validation are proven ("
                        + filter.diagnosticSummary() + ")");
    }
}
