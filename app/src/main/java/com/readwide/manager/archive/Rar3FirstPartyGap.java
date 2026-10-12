package com.readwide.manager.archive;

/**
 * Failure messages for individual RAR3/RAR4 helper paths.
 *
 * <p>Normal compressed RAR remains libarchive-primary. These messages describe only
 * the helper that raised the error, not the capabilities of other live archive decoders.
 * PPMd and standard-filter implementations exist outside these helpers.</p>
 */
final class Rar3FirstPartyGap {
    private Rar3FirstPartyGap() {}

    static RarArchiveReader.UnsupportedRarFeatureException ppmdBlock(boolean keepOldTable) {
        return ppmdModel(keepOldTable);
    }

    static RarArchiveReader.UnsupportedRarFeatureException ppmdModel(boolean keepOldTable) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "The RAR3/RAR4 LZ table reader cannot parse this PPMd block. "
                        + "Archive PPMd decoding uses a separate model reader (keepOldTable="
                        + keepOldTable + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException ppmdVmFilter(long outputOffset) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "This RAR3/RAR4 PPMd control helper has no VM-filter output sink. "
                        + "The archive decoder handles standard and scoped custom filters through "
                        + "Rar3PpmdFilterOutput (outputOffset=" + outputOffset + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException vmFilter(long outputOffset) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "This RAR3/RAR4 decoder path has no VM-filter execution state "
                        + "(outputOffset=" + outputOffset + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException vmFilter(RarVmFilter filter) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "No standard-filter primitive matches this RAR3/RAR4 VM program. "
                        + "This diagnostic helper has no custom VM state; the live streaming decoder handles scoped custom programs ("
                        + filter.diagnosticSummary() + ")");
    }

    static RarArchiveReader.UnsupportedRarFeatureException vmFilterMissingExecutionState(
            RarVmFilter filter) {
        return new RarArchiveReader.UnsupportedRarFeatureException(
                "This RAR3/RAR4 filter plan is missing decoded VM execution state "
                        + "for the output length or file offset ("
                        + filter.diagnosticSummary() + ")");
    }
}
