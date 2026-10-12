package com.readwide.manager.util;

/**
 * Pure decision for whether a large-TXT save should use an owned pending
 * navigation target instead of the currently visible character.
 *
 * <p>An explicit partition jump owns a character destination even before the
 * exact page index is ready. Finishing that index may change its displayed
 * page number, but must not reinterpret an estimated page number as a different
 * character destination. An automatic viewport handoff instead preserves the
 * visible row unless later explicit navigation has taken ownership.</p>
 *
 * <p>The controller supplies the destination and any matching context; this
 * predicate deliberately does not depend on exact-index readiness.</p>
 */
public final class ReaderSaveAnchorMath {
    private ReaderSaveAnchorMath() {}

    /**
     * @param largeTextActive          whether the large-TXT partition path is active
     * @param partitionSwitchInProgress whether a partition switch is in flight
     * @param preserveVisiblePosition  whether an automatic handoff still owns the visible row
     * @param destinationChar          the owned absolute character; negative means unavailable
     * @return true if the save path should use the pending character destination
     */
    public static boolean shouldUsePendingTarget(boolean largeTextActive,
                                                 boolean partitionSwitchInProgress,
                                                 boolean preserveVisiblePosition,
                                                 int destinationChar) {
        return largeTextActive
                && partitionSwitchInProgress
                && !preserveVisiblePosition
                && destinationChar >= 0;
    }
}
