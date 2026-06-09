package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validation bridge between parsed RAR3/RAR4 VM-filter markers and the delayed-output scaffold.
 *
 * <p>This deliberately does not decode VM registers or enable live VM-filtered extraction. It only
 * centralizes the point where a parsed filter plus already-decoded VM execution state may become an
 * immutable {@link RarVmFilterExecutionPlan}. If the VM execution state is missing, the first-party
 * decoder must still stop with a precise unsupported gap while libarchive remains the primary path
 * for normal compressed RAR.</p>
 */
final class RarVmFilterPlanCollector {
    private static final int DEFAULT_MAX_PENDING_PLANS = 1024;

    private final ArrayList<RarVmFilterExecutionPlan> plans = new ArrayList<>();
    private final int maxPendingPlans;
    private long emittedOffset;

    RarVmFilterPlanCollector() {
        this(0L, DEFAULT_MAX_PENDING_PLANS);
    }

    RarVmFilterPlanCollector(long emittedOffset) throws IOException {
        this(emittedOffset, DEFAULT_MAX_PENDING_PLANS);
    }

    RarVmFilterPlanCollector(long emittedOffset, int maxPendingPlans) {
        if (emittedOffset < 0) {
            throw new IllegalArgumentException("Invalid RAR VM emitted output offset");
        }
        if (maxPendingPlans <= 0) {
            throw new IllegalArgumentException("Invalid RAR VM pending filter-plan limit");
        }
        this.emittedOffset = emittedOffset;
        this.maxPendingPlans = maxPendingPlans;
    }

    @NonNull
    RarVmFilterExecutionPlan addParsedFilter(@NonNull RarVmFilter filter,
                                             @NonNull RarVmFilterExecutionState state)
            throws IOException {
        if (filter == null) {
            throw new IOException("Missing RAR VM filter metadata");
        }
        if (state == null) {
            throw Rar3FirstPartyGap.vmFilterMissingExecutionState(filter);
        }
        if (plans.size() >= maxPendingPlans) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR VM filter-plan count exceeds first-party safety limit");
        }
        RarVmFilterExecutionPlan plan = state.toPlan(filter);
        plan.validateTargetNotEmitted(emittedOffset);
        validateOrderedNonOverlapping(plan);
        plans.add(plan);
        return plan;
    }

    @NonNull
    RarVmFilterExecutionPlan addParsedFilterWithoutDecodedVmState(@NonNull RarVmFilter filter)
            throws IOException {
        if (filter == null) {
            throw new IOException("Missing RAR VM filter metadata");
        }
        throw Rar3FirstPartyGap.vmFilterMissingExecutionState(filter);
    }

    void queueInto(@NonNull RarVmFilterPipeline pipeline) throws IOException {
        if (pipeline == null) {
            throw new IOException("Missing RAR VM filter pipeline");
        }
        for (RarVmFilterExecutionPlan plan : plans) {
            pipeline.queueExecutionPlan(plan);
        }
    }

    void updateEmittedOffset(long emittedOffset) throws IOException {
        if (emittedOffset < 0) {
            throw new IOException("Invalid RAR VM emitted output offset");
        }
        if (emittedOffset < this.emittedOffset) {
            throw new IOException("RAR VM emitted output offset moved backwards");
        }
        for (RarVmFilterExecutionPlan plan : plans) {
            plan.validateTargetNotEmitted(emittedOffset);
        }
        this.emittedOffset = emittedOffset;
    }

    int size() {
        return plans.size();
    }

    boolean isEmpty() {
        return plans.isEmpty();
    }

    @NonNull
    List<RarVmFilterExecutionPlan> plans() {
        return Collections.unmodifiableList(plans);
    }

    @NonNull
    RarVmFilterQueue toQueue() throws IOException {
        return RarVmFilterExecutionPlan.toQueue(plans);
    }

    private void validateOrderedNonOverlapping(@NonNull RarVmFilterExecutionPlan plan)
            throws IOException {
        if (plans.isEmpty()) return;
        RarVmFilterExecutionPlan previous = plans.get(plans.size() - 1);
        if (plan.outputOffset < previous.endOffsetExclusive()) {
            throw new IOException("Overlapping or out-of-order RAR VM filter execution plan");
        }
    }
}