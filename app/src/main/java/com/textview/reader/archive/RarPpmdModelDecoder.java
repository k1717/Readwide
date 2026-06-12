package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Narrow decode facade for the evolving first-party RAR3/RAR4 PPMd model.
 *
 * <p>The production path still stops before pretending to decode full RAR PPMd. A diagnostic
 * context/escape/order-0 fallback path is available to unit tests so the symbol source is no
 * longer just a throw-only placeholder and the model/state objects can be exercised
 * deterministically.</p>
 */
final class RarPpmdModelDecoder {
    @NonNull private final RarPpmdModel model;
    @NonNull private final RarPpmdRangeDecoder rangeDecoder;
    @NonNull private final String stateDiagnostic;
    @NonNull private final String blockDiagnostic;
    private final boolean allowDiagnosticModelDecode;
    @NonNull private final RarPpmdDiagnosticOptions diagnosticOptions;

    RarPpmdModelDecoder(@NonNull RarPpmdModel model,
                        @NonNull RarPpmdRangeDecoder rangeDecoder,
                        @NonNull String stateDiagnostic,
                        @NonNull String blockDiagnostic,
                        boolean allowDiagnosticModelDecode) throws IOException {
        this(model, rangeDecoder, stateDiagnostic, blockDiagnostic, allowDiagnosticModelDecode,
                RarPpmdDiagnosticOptions.standard());
    }

    RarPpmdModelDecoder(@NonNull RarPpmdModel model,
                        @NonNull RarPpmdRangeDecoder rangeDecoder,
                        @NonNull String stateDiagnostic,
                        @NonNull String blockDiagnostic,
                        boolean allowDiagnosticModelDecode,
                        @NonNull RarPpmdDiagnosticOptions diagnosticOptions) {
        this.model = model;
        this.rangeDecoder = rangeDecoder;
        this.stateDiagnostic = stateDiagnostic;
        this.blockDiagnostic = blockDiagnostic;
        this.allowDiagnosticModelDecode = allowDiagnosticModelDecode;
        this.diagnosticOptions = diagnosticOptions;
    }

    int decodeSymbol() throws IOException {
        if (allowDiagnosticModelDecode) {
            return model.decodeDiagnosticContextOrOrder0Symbol(rangeDecoder, diagnosticOptions);
        }
        throw new RarArchiveReader.UnsupportedRarFeatureException(
                "RAR3/RAR4 PPMd statistical model decoding is not implemented in the "
                        + "first-party Java path yet. Initialized diagnostic primitives now own "
                        + "the model state holder, range decoder, order-0 bootstrap primitive, "
                        + "allocator/context skeleton, SEE estimator, escape/masked-symbol helper, "
                        + "diagnostic order-2/order-1/root suffix fallback, PPMd-I-shaped 25x16 SEE selector table, "
                        + "production-facing masked-symbol arithmetic primitive, explicit successor/context creation scaffold, "
                        + "variant live probes for order0/root/SEE scale attribution, and solid-state handoff. Remaining gaps: "
                        + "live integration of successor traversal, full multi-order context tree traversal, "
                        + "fixture-verified exact RAR SEE constants, and exact model-update/rescale rules. "
                        + "State: " + stateDiagnostic
                        + "; model=" + model.diagnostic()
                        + "; block=" + blockDiagnostic + ".");
    }
}
