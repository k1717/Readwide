package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Locale;

/**
 * Switches for the non-success RAR3/RAR4 PPMd live diagnostic probe.
 *
 * <p>These options deliberately do not enable production PPMd extraction. They let fixture probes
 * route the same payload through controlled variants so scale/count mismatches can be attributed to
 * order-0 bootstrap, context/root entry, or SEE escape-scale assumptions without marking any cache or
 * image decode as successful.</p>
 */
final class RarPpmdDiagnosticOptions {
    private static final int NO_FIXED_ESCAPE_SCALE = -1;

    private static final int SEE_MODE_STANDARD = 0;
    private static final int SEE_MODE_FIXED = 1;
    private static final int SEE_MODE_EARLY_LOW = 2;
    private static final int SEE_MODE_STATE_RATIO = 3;
    private static final int SEE_MODE_MASK_PRESSURE = 4;

    @NonNull private final String name;
    private final boolean contextFallbackEnabled;
    private final boolean rootContextEnabled;
    private final int fixedEscapeScale;
    private final int seeMode;
    private final boolean rarPrimaryRootEnabled;
    private final boolean rarPrimaryRootOnly;
    @NonNull private final RarPpmdPrimaryUpdatePolicy primaryUpdatePolicy;
    private final boolean contextCursorEnabled;
    private final boolean suffixLoopEnabled;
    private final boolean terminalRootEscapeEnabled;
    private final boolean cursorOrderFallGateEnabled;
    private final boolean pendingTextSuccessorsEnabled;
    private final int createSuccessorSeedMode;

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode) throws IOException {
        this(name, contextFallbackEnabled, rootContextEnabled, fixedEscapeScale, seeMode, false);
    }

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode,
                                     boolean rarPrimaryRootEnabled) throws IOException {
        this(name, contextFallbackEnabled, rootContextEnabled, fixedEscapeScale, seeMode,
                rarPrimaryRootEnabled, false);
    }

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode,
                                     boolean rarPrimaryRootEnabled,
                                     boolean rarPrimaryRootOnly) throws IOException {
        this(name, contextFallbackEnabled, rootContextEnabled, fixedEscapeScale, seeMode,
                rarPrimaryRootEnabled, rarPrimaryRootOnly,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), false, false);
    }

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode,
                                     boolean rarPrimaryRootEnabled,
                                     boolean rarPrimaryRootOnly,
                                     @NonNull RarPpmdPrimaryUpdatePolicy primaryUpdatePolicy)
            throws IOException {
        this(name, contextFallbackEnabled, rootContextEnabled, fixedEscapeScale, seeMode,
                rarPrimaryRootEnabled, rarPrimaryRootOnly, primaryUpdatePolicy, false, false);
    }

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode,
                                     boolean rarPrimaryRootEnabled,
                                     boolean rarPrimaryRootOnly,
                                     @NonNull RarPpmdPrimaryUpdatePolicy primaryUpdatePolicy,
                                     boolean contextCursorEnabled,
                                     boolean suffixLoopEnabled)
            throws IOException {
        this(name, contextFallbackEnabled, rootContextEnabled, fixedEscapeScale, seeMode,
                rarPrimaryRootEnabled, rarPrimaryRootOnly, primaryUpdatePolicy, contextCursorEnabled,
                suffixLoopEnabled, false, false, false);
    }

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode,
                                     boolean rarPrimaryRootEnabled,
                                     boolean rarPrimaryRootOnly,
                                     @NonNull RarPpmdPrimaryUpdatePolicy primaryUpdatePolicy,
                                     boolean contextCursorEnabled,
                                     boolean suffixLoopEnabled,
                                     boolean terminalRootEscapeEnabled,
                                     boolean cursorOrderFallGateEnabled)
            throws IOException {
        this(name, contextFallbackEnabled, rootContextEnabled, fixedEscapeScale, seeMode,
                rarPrimaryRootEnabled, rarPrimaryRootOnly, primaryUpdatePolicy, contextCursorEnabled,
                suffixLoopEnabled, terminalRootEscapeEnabled, cursorOrderFallGateEnabled, false);
    }

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode,
                                     boolean rarPrimaryRootEnabled,
                                     boolean rarPrimaryRootOnly,
                                     @NonNull RarPpmdPrimaryUpdatePolicy primaryUpdatePolicy,
                                     boolean contextCursorEnabled,
                                     boolean suffixLoopEnabled,
                                     boolean terminalRootEscapeEnabled,
                                     boolean cursorOrderFallGateEnabled,
                                     boolean pendingTextSuccessorsEnabled)
            throws IOException {
        this(name, contextFallbackEnabled, rootContextEnabled, fixedEscapeScale, seeMode,
                rarPrimaryRootEnabled, rarPrimaryRootOnly, primaryUpdatePolicy, contextCursorEnabled,
                suffixLoopEnabled, terminalRootEscapeEnabled, cursorOrderFallGateEnabled,
                pendingTextSuccessorsEnabled, RarPpmdCreateSuccessors.SEED_NONE);
    }

    private RarPpmdDiagnosticOptions(@NonNull String name,
                                     boolean contextFallbackEnabled,
                                     boolean rootContextEnabled,
                                     int fixedEscapeScale,
                                     int seeMode,
                                     boolean rarPrimaryRootEnabled,
                                     boolean rarPrimaryRootOnly,
                                     @NonNull RarPpmdPrimaryUpdatePolicy primaryUpdatePolicy,
                                     boolean contextCursorEnabled,
                                     boolean suffixLoopEnabled,
                                     boolean terminalRootEscapeEnabled,
                                     boolean cursorOrderFallGateEnabled,
                                     boolean pendingTextSuccessorsEnabled,
                                     int createSuccessorSeedMode)
            throws IOException {
        if (name.trim().length() == 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd diagnostic option name is empty");
        }
        if (!contextFallbackEnabled && !rootContextEnabled) {
            // Fine: order0-only explicitly disables every context path.
        }
        if (fixedEscapeScale != NO_FIXED_ESCAPE_SCALE && fixedEscapeScale <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd fixed diagnostic escape scale is invalid: "
                            + fixedEscapeScale);
        }
        this.name = name;
        this.contextFallbackEnabled = contextFallbackEnabled;
        this.rootContextEnabled = rootContextEnabled;
        this.fixedEscapeScale = fixedEscapeScale;
        this.seeMode = seeMode;
        this.rarPrimaryRootEnabled = rarPrimaryRootEnabled;
        this.rarPrimaryRootOnly = rarPrimaryRootOnly;
        this.primaryUpdatePolicy = primaryUpdatePolicy;
        this.contextCursorEnabled = contextCursorEnabled;
        this.suffixLoopEnabled = suffixLoopEnabled;
        this.terminalRootEscapeEnabled = terminalRootEscapeEnabled;
        this.cursorOrderFallGateEnabled = cursorOrderFallGateEnabled;
        this.pendingTextSuccessorsEnabled = pendingTextSuccessorsEnabled;
        this.createSuccessorSeedMode = createSuccessorSeedMode;
    }

    @NonNull
    static RarPpmdDiagnosticOptions standard() throws IOException {
        return new RarPpmdDiagnosticOptions("standard", true, true, NO_FIXED_ESCAPE_SCALE,
                SEE_MODE_STANDARD);
    }

    @NonNull
    static RarPpmdDiagnosticOptions order0Only() throws IOException {
        return new RarPpmdDiagnosticOptions("order0-only", false, false,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rootDisabled() throws IOException {
        return new RarPpmdDiagnosticOptions("root-disabled", true, false,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRoot() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootOnly() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-only", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, true);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootLightUpdate() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-light-update", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.light());
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootNoPromote() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-no-promote", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.noPromote());
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootFrozen() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-frozen", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.frozen());
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootCursor() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-cursor", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, false);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootCursorLoop() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-cursor-loop", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, true);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootCursorOrderFall() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-cursor-orderfall", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, false, false, true);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootCursorLoopTerminal() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-cursor-loop-terminal", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, true, true, true);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootPendingSuccessor() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-pending-successor", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, false, false, true, true);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootCreateSuccessors() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-create-successors", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, false, false, true, true,
                RarPpmdCreateSuccessors.SEED_OWNER_SYMBOL);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootCreateSuccessorsPendingSeed() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-create-successors-pending-seed", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, false, false, true, true,
                RarPpmdCreateSuccessors.SEED_PENDING_SYMBOL);
    }

    @NonNull
    static RarPpmdDiagnosticOptions rarPrimaryRootCreateSuccessorsHistorySeed() throws IOException {
        return new RarPpmdDiagnosticOptions("rar-primary-root-create-successors-history-seed", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STANDARD, true, false,
                RarPpmdPrimaryUpdatePolicy.unrarShaped(), true, false, false, true, true,
                RarPpmdCreateSuccessors.SEED_HISTORY_NEWEST);
    }

    @NonNull
    static RarPpmdDiagnosticOptions fixedEscapeScale(int escapeScale) throws IOException {
        return new RarPpmdDiagnosticOptions(
                String.format(Locale.US, "fixed-see-%d", escapeScale), true, true,
                escapeScale, SEE_MODE_FIXED);
    }

    @NonNull
    static RarPpmdDiagnosticOptions candidateEarlyLowSee() throws IOException {
        return new RarPpmdDiagnosticOptions("candidate-see-early-low", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_EARLY_LOW);
    }

    @NonNull
    static RarPpmdDiagnosticOptions candidateStateRatioSee() throws IOException {
        return new RarPpmdDiagnosticOptions("candidate-see-state-ratio", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_STATE_RATIO);
    }

    @NonNull
    static RarPpmdDiagnosticOptions candidateMaskPressureSee() throws IOException {
        return new RarPpmdDiagnosticOptions("candidate-see-mask-pressure", true, true,
                NO_FIXED_ESCAPE_SCALE, SEE_MODE_MASK_PRESSURE);
    }

    @NonNull
    static RarPpmdDiagnosticOptions[] comparisonSet() throws IOException {
        return new RarPpmdDiagnosticOptions[] {
                standard(),
                order0Only(),
                rootDisabled(),
                rarPrimaryRoot(),
                rarPrimaryRootOnly(),
                rarPrimaryRootLightUpdate(),
                rarPrimaryRootNoPromote(),
                rarPrimaryRootFrozen(),
                rarPrimaryRootCursor(),
                rarPrimaryRootCursorLoop(),
                rarPrimaryRootCursorOrderFall(),
                rarPrimaryRootCursorLoopTerminal(),
                rarPrimaryRootPendingSuccessor(),
                rarPrimaryRootCreateSuccessors(),
                rarPrimaryRootCreateSuccessorsPendingSeed(),
                rarPrimaryRootCreateSuccessorsHistorySeed(),
                fixedEscapeScale(1),
                fixedEscapeScale(32),
                candidateEarlyLowSee(),
                candidateStateRatioSee(),
                candidateMaskPressureSee()
        };
    }

    @NonNull
    String name() {
        return name;
    }

    boolean contextFallbackEnabled() {
        return contextFallbackEnabled;
    }

    boolean rootContextEnabled() {
        return rootContextEnabled;
    }

    boolean hasFixedEscapeScale() {
        return fixedEscapeScale != NO_FIXED_ESCAPE_SCALE;
    }

    boolean rarPrimaryRootEnabled() {
        return rarPrimaryRootEnabled;
    }

    boolean isRarPrimaryRootOnly() {
        return rarPrimaryRootOnly;
    }

    @NonNull
    RarPpmdPrimaryUpdatePolicy primaryUpdatePolicy() {
        return primaryUpdatePolicy;
    }

    boolean contextCursorEnabled() {
        return contextCursorEnabled;
    }

    boolean suffixLoopEnabled() {
        return suffixLoopEnabled;
    }

    boolean terminalRootEscapeEnabled() {
        return terminalRootEscapeEnabled;
    }

    boolean cursorOrderFallGateEnabled() {
        return cursorOrderFallGateEnabled;
    }

    boolean pendingTextSuccessorsEnabled() {
        return pendingTextSuccessorsEnabled;
    }

    boolean createSuccessorSeedEnabled() {
        return createSuccessorSeedMode != RarPpmdCreateSuccessors.SEED_NONE;
    }

    int createSuccessorSeedMode() {
        return createSuccessorSeedMode;
    }

    @NonNull
    RarPpmdSeeContext seeContextFor(@NonNull RarPpmdSeeContext selected,
                                    int orderDepth,
                                    @NonNull RarPpmdContext context,
                                    @NonNull RarPpmdEscapeMask mask,
                                    int previousSymbolCount) throws IOException {
        if (seeMode == SEE_MODE_STANDARD) return selected;
        if (seeMode == SEE_MODE_FIXED) {
            // Use shift 3 so summary >>> shift equals the requested fixed mean. The context is
            // intentionally per-decode-probe and does not mutate the main SEE table.
            return fixedMeanContext(fixedEscapeScale, 4);
        }
        int selectedMean = selected.mean();
        int stateCount = context.stateCount();
        int maskedCount = mask.maskedCount();
        int candidateMean;
        if (seeMode == SEE_MODE_EARLY_LOW) {
            // The target fixture fails immediately when root has four states and SEE mean is 7.
            // Keep the early root/suffix escape low, then let the selected table take over after
            // the history is no longer bootstrap-small.
            candidateMean = (previousSymbolCount < 8) ? 1 : Math.max(1, Math.min(selectedMean, 4));
        } else if (seeMode == SEE_MODE_STATE_RATIO) {
            // Scale escape by the number of currently reachable states instead of the table row
            // alone. This approximates the PPMd-I idea that escape probability falls once a dense
            // context starts explaining the stream.
            candidateMean = Math.max(1, Math.min(selectedMean, (stateCount + 1) >>> 1));
        } else if (seeMode == SEE_MODE_MASK_PRESSURE) {
            // Keep unmasked contexts narrow, but allow escape to grow when suffix fallback has
            // already masked symbols from higher-order contexts.
            candidateMean = Math.max(1, Math.min(selectedMean, 1 + maskedCount + (orderDepth >>> 1)));
        } else {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd diagnostic SEE mode is unknown: " + seeMode);
        }
        return fixedMeanContext(candidateMean, Math.max(1, 4 + maskedCount));
    }

    @NonNull
    private static RarPpmdSeeContext fixedMeanContext(int mean, int count) throws IOException {
        if (mean <= 0) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd diagnostic SEE candidate mean is invalid: " + mean);
        }
        return new RarPpmdSeeContext(mean << 3, 3, count);
    }

    @NonNull
    String diagnostic() {
        return "name=" + name
                + "; contextFallback=" + contextFallbackEnabled
                + "; rootContext=" + rootContextEnabled
                + "; fixedEscapeScale=" + fixedEscapeScale
                + "; seeMode=" + seeMode
                + "; rarPrimaryRoot=" + rarPrimaryRootEnabled
                + "; rarPrimaryRootOnly=" + rarPrimaryRootOnly
                + "; primaryUpdatePolicy={" + primaryUpdatePolicy.diagnostic() + "}"
                + "; contextCursorEnabled=" + contextCursorEnabled
                + "; suffixLoopEnabled=" + suffixLoopEnabled
                + "; terminalRootEscape=" + terminalRootEscapeEnabled
                + "; cursorOrderFallGate=" + cursorOrderFallGateEnabled
                + "; pendingTextSuccessors=" + pendingTextSuccessorsEnabled
                + "; createSuccessorSeedMode=" + createSuccessorSeedMode
                + "; createSuccessorSeed=" + createSuccessorSeedEnabled();
    }
}
