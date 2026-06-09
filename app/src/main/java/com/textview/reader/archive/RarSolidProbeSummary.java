package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Aggregates compressed-solid first-party probe outcomes without enabling live extraction. */
final class RarSolidProbeSummary {
    final int total;
    final int firstPartySuccess;
    final int firstPartyFailure;
    final int referenceCompared;
    final int referenceMatched;
    final int referenceMismatched;
    final RarSolidProbeFailure.Cause dominantCause;
    final RarSolidProbeFailure.NextStep dominantNextStep;

    private final EnumMap<RarSolidProbeFailure.Cause, Integer> causeCounts;
    private final EnumMap<RarSolidProbeFailure.NextStep, Integer> nextStepCounts;

    private RarSolidProbeSummary(int total,
                                 int firstPartySuccess,
                                 int firstPartyFailure,
                                 int referenceCompared,
                                 int referenceMatched,
                                 int referenceMismatched,
                                 @NonNull EnumMap<RarSolidProbeFailure.Cause, Integer> causeCounts,
                                 @NonNull EnumMap<RarSolidProbeFailure.NextStep, Integer> nextStepCounts) {
        this.total = total;
        this.firstPartySuccess = firstPartySuccess;
        this.firstPartyFailure = firstPartyFailure;
        this.referenceCompared = referenceCompared;
        this.referenceMatched = referenceMatched;
        this.referenceMismatched = referenceMismatched;
        this.causeCounts = new EnumMap<>(causeCounts);
        this.nextStepCounts = new EnumMap<>(nextStepCounts);
        this.dominantCause = dominantCause(causeCounts);
        this.dominantNextStep = dominantNextStep(nextStepCounts);
    }

    @NonNull
    static RarSolidProbeSummary fromResults(@NonNull List<RarSolidFirstPartyProbe.Result> results) {
        EnumMap<RarSolidProbeFailure.Cause, Integer> causes = emptyCauseMap();
        EnumMap<RarSolidProbeFailure.NextStep, Integer> nextSteps = emptyNextStepMap();
        int success = 0;
        int failure = 0;
        int compared = 0;
        int matched = 0;
        int mismatched = 0;
        for (RarSolidFirstPartyProbe.Result result : results) {
            if (result == null) continue;
            increment(causes, result.failure.cause);
            increment(nextSteps, result.failure.nextStep);
            if (result.firstPartyStatus == RarSolidFirstPartyProbe.FirstPartyStatus.SUCCESS) {
                success++;
            } else {
                failure++;
            }
            if (result.comparisonStatus == RarSolidFirstPartyProbe.ComparisonStatus.MATCH
                    || result.comparisonStatus == RarSolidFirstPartyProbe.ComparisonStatus.MISMATCH) {
                compared++;
            }
            if (result.comparisonStatus == RarSolidFirstPartyProbe.ComparisonStatus.MATCH) matched++;
            if (result.comparisonStatus == RarSolidFirstPartyProbe.ComparisonStatus.MISMATCH) mismatched++;
        }
        return new RarSolidProbeSummary(results.size(), success, failure, compared, matched, mismatched, causes, nextSteps);
    }

    @NonNull
    static RarSolidProbeSummary fromFailuresForTest(@NonNull List<RarSolidProbeFailure> failures) {
        EnumMap<RarSolidProbeFailure.Cause, Integer> causes = emptyCauseMap();
        EnumMap<RarSolidProbeFailure.NextStep, Integer> nextSteps = emptyNextStepMap();
        int failureCount = 0;
        int successCount = 0;
        for (RarSolidProbeFailure failure : failures) {
            if (failure == null) continue;
            increment(causes, failure.cause);
            increment(nextSteps, failure.nextStep);
            if (failure.cause == RarSolidProbeFailure.Cause.NONE) {
                successCount++;
            } else {
                failureCount++;
            }
        }
        return new RarSolidProbeSummary(failures.size(), successCount, failureCount, 0, 0, 0, causes, nextSteps);
    }

    int count(@NonNull RarSolidProbeFailure.Cause cause) {
        Integer value = causeCounts.get(cause);
        return value == null ? 0 : value;
    }

    int count(@NonNull RarSolidProbeFailure.NextStep nextStep) {
        Integer value = nextStepCounts.get(nextStep);
        return value == null ? 0 : value;
    }

    @NonNull
    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Solid probe summary\n\n");
        sb.append("This summary is diagnostic only. It does not enable first-party compressed-solid extraction; ")
                .append("libarchive remains the primary backend for normal compressed and solid RAR.\n\n");
        sb.append("- probe rows: ").append(total).append('\n');
        sb.append("- first-party successes: ").append(firstPartySuccess).append('\n');
        sb.append("- first-party failures/skips/gaps: ").append(firstPartyFailure).append('\n');
        sb.append("- reference comparisons: ").append(referenceCompared).append('\n');
        sb.append("- reference matches: ").append(referenceMatched).append('\n');
        sb.append("- reference mismatches: ").append(referenceMismatched).append('\n');
        sb.append("- dominant cause: ").append(dominantCause).append('\n');
        sb.append("- dominant next step: ").append(dominantNextStep).append("\n\n");
        sb.append("| Cause | Count |\n");
        sb.append("|---|---:|\n");
        for (Map.Entry<RarSolidProbeFailure.Cause, Integer> entry : causeCounts.entrySet()) {
            if (entry.getValue() > 0) {
                sb.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
            }
        }
        sb.append("\n| Next step | Count |\n");
        sb.append("|---|---:|\n");
        for (Map.Entry<RarSolidProbeFailure.NextStep, Integer> entry : nextStepCounts.entrySet()) {
            if (entry.getValue() > 0) {
                sb.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
            }
        }
        return sb.toString();
    }

    @NonNull
    private static EnumMap<RarSolidProbeFailure.Cause, Integer> emptyCauseMap() {
        EnumMap<RarSolidProbeFailure.Cause, Integer> map = new EnumMap<>(RarSolidProbeFailure.Cause.class);
        for (RarSolidProbeFailure.Cause cause : RarSolidProbeFailure.Cause.values()) map.put(cause, 0);
        return map;
    }

    @NonNull
    private static EnumMap<RarSolidProbeFailure.NextStep, Integer> emptyNextStepMap() {
        EnumMap<RarSolidProbeFailure.NextStep, Integer> map = new EnumMap<>(RarSolidProbeFailure.NextStep.class);
        for (RarSolidProbeFailure.NextStep nextStep : RarSolidProbeFailure.NextStep.values()) map.put(nextStep, 0);
        return map;
    }

    private static <E extends Enum<E>> void increment(@NonNull EnumMap<E, Integer> map, @NonNull E key) {
        Integer value = map.get(key);
        map.put(key, value == null ? 1 : value + 1);
    }

    @NonNull
    private static RarSolidProbeFailure.Cause dominantCause(@NonNull EnumMap<RarSolidProbeFailure.Cause, Integer> counts) {
        RarSolidProbeFailure.Cause best = RarSolidProbeFailure.Cause.NONE;
        int bestCount = -1;
        for (Map.Entry<RarSolidProbeFailure.Cause, Integer> entry : counts.entrySet()) {
            if (entry.getKey() == RarSolidProbeFailure.Cause.NONE) continue;
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestCount <= 0 ? RarSolidProbeFailure.Cause.NONE : best;
    }

    @NonNull
    private static RarSolidProbeFailure.NextStep dominantNextStep(@NonNull EnumMap<RarSolidProbeFailure.NextStep, Integer> counts) {
        RarSolidProbeFailure.NextStep best = RarSolidProbeFailure.NextStep.NONE;
        int bestCount = -1;
        for (Map.Entry<RarSolidProbeFailure.NextStep, Integer> entry : counts.entrySet()) {
            if (entry.getKey() == RarSolidProbeFailure.NextStep.NONE) continue;
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestCount <= 0 ? RarSolidProbeFailure.NextStep.NONE : best;
    }
}
