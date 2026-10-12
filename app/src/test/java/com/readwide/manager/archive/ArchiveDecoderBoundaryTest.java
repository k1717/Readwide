package com.readwide.manager.archive;

import org.junit.Test;

/** Same focused checks as scripts/check-archive-decoder-boundaries.py. */
public final class ArchiveDecoderBoundaryTest {
    @Test public void deflateNegativeSourceCount() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateNegativeSourceCount").run(); }
    @Test public void deflateOversizedSourceCount() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateOversizedSourceCount").run(); }
    @Test public void deflateMaximumSourceCount() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateMaximumSourceCount").run(); }
    @Test public void deflateZeroProgressSource() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateZeroProgressSource").run(); }
    @Test public void deflateTruncatedSourceSticky() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateTruncatedSourceSticky").run(); }
    @Test public void deflateSourceFailureIdentity() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateSourceFailureIdentity").run(); }
    @Test public void deflateLateInvalidCount() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateLateInvalidCount").run(); }
    @Test public void deflateKnownPayloads() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateKnownPayloads").run(); }
    @Test public void deflateShortCompressedReads() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateShortCompressedReads").run(); }
    @Test public void deflateMixedReadAndSkip() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateMixedReadAndSkip").run(); }
    @Test public void deflateCancellationSticky() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateCancellationSticky").run(); }
    @Test public void deflateCloseFailureClosesOnce() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateCloseFailureClosesOnce").run(); }
    @Test public void deflateInvalidArgumentsDoNotPoison() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("deflateInvalidArgumentsDoNotPoison").run(); }
    @Test public void alzValidTrimmedBzip() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzValidTrimmedBzip").run(); }
    @Test public void alzValidEncryptedTrimmedBzip() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzValidEncryptedTrimmedBzip").run(); }
    @Test public void alzValidManySplitTrimmedBzip() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzValidManySplitTrimmedBzip").run(); }
    @Test public void alzValidEncryptedManySplitTrimmedBzip() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzValidEncryptedManySplitTrimmedBzip").run(); }
    @Test public void alzCodeLengthWrapPreservesExisting() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzCodeLengthWrapPreservesExisting").run(); }
    @Test public void alzCodeLengthWrapRemovesNewPartial() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzCodeLengthWrapRemovesNewPartial").run(); }
    @Test public void alzRleWrapWithValidOuterCrcRejected() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzRleWrapWithValidOuterCrcRejected").run(); }
    @Test public void alzEncryptedBadTablePreservesExisting() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzEncryptedBadTablePreservesExisting").run(); }
    @Test public void alzMalformedSelectorClassifiesCorruption() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzMalformedSelectorClassifiesCorruption").run(); }
    @Test public void alzUnknownMethodStillUnsupported() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzUnknownMethodStillUnsupported").run(); }
    @Test public void alzWrongPasswordPreservesExisting() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzWrongPasswordPreservesExisting").run(); }
    @Test public void alzBadTrimmedHeaderClassifiesCorruption() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzBadTrimmedHeaderClassifiesCorruption").run(); }
    @Test public void alzProbeZeroProgress() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzProbeZeroProgress").run(); }
    @Test public void alzProbeNegativeCount() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzProbeNegativeCount").run(); }
    @Test public void alzProbeOversizedCount() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzProbeOversizedCount").run(); }
    @Test public void alzProbeCancellation() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzProbeCancellation").run(); }
    @Test public void alzProbeShortReads() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzProbeShortReads").run(); }
    @Test public void alzSourceIoCausePreserved() throws Exception { ArchiveDecoderBoundaryChecks.checks().get("alzSourceIoCausePreserved").run(); }
}
