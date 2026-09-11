package com.readwide.manager.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ArchiveViewerTimeoutPolicyTest {
    @Test public void deletingOrInvalidEditingDoesNotDisableTheSavedTimeout() {
        assertEquals(30, ArchiveViewerTimeoutPolicy.parseMinutes("", 30));
        assertEquals(30, ArchiveViewerTimeoutPolicy.parseMinutes("   ", 30));
        assertEquals(30, ArchiveViewerTimeoutPolicy.parseMinutes("12x", 30));
        assertEquals(30, ArchiveViewerTimeoutPolicy.parseMinutes("-1", 30));
        assertEquals(0, ArchiveViewerTimeoutPolicy.parseMinutes("0", 30));
    }

    @Test public void pastedNumbersSaturateWithoutOverflow() {
        assertEquals(10080, ArchiveViewerTimeoutPolicy.parseMinutes(
                "999999999999999999999999999999999999999999", 10));
        assertEquals(10, ArchiveViewerTimeoutPolicy.parseMinutes("00010", 30));
        assertEquals(30, ArchiveViewerTimeoutPolicy.parseMinutes("999999999x", 30));
    }

    @Test public void deadlineExpiresAtTheBoundaryNotBefore() {
        assertEquals(1L, ArchiveViewerTimeoutPolicy.remainingMillis(1, 500L, 60_499L));
        assertEquals(0L, ArchiveViewerTimeoutPolicy.remainingMillis(1, 500L, 60_500L));
        assertEquals(0L, ArchiveViewerTimeoutPolicy.remainingMillis(1, 500L, 90_500L));
    }

    @Test public void disabledOrUnknownIntervalsNeverExpire() {
        assertEquals(-1L, ArchiveViewerTimeoutPolicy.remainingMillis(0, 500L, 9_000_000L));
        assertEquals(-1L, ArchiveViewerTimeoutPolicy.remainingMillis(10, -1L, 9_000_000L));
        assertEquals(-1L, ArchiveViewerTimeoutPolicy.remainingMillis(10, 500L, 400L));
    }

    @Test public void returnAfterDeepSleepUsesElapsedTimeEvenIfNoCallbackRan() {
        assertEquals(0L, ArchiveViewerTimeoutPolicy.remainingMillis(10, 1000L, 901_000L));
    }

    @Test public void sevenDayLimitUsesLongArithmetic() {
        assertEquals(604_800_000L,
                ArchiveViewerTimeoutPolicy.remainingMillis(Integer.MAX_VALUE, 0L, 0L));
        assertEquals(0L,
                ArchiveViewerTimeoutPolicy.remainingMillis(10080, 0L, Long.MAX_VALUE));
    }

    @Test public void restoringInTheSameBootRetainsTheOriginalDeadline() {
        long restored = ArchiveViewerTimeoutPolicy.restoreStoppedAt(1000L, 4, 4, 601_000L);
        assertEquals(1000L, restored);
        assertEquals(0L, ArchiveViewerTimeoutPolicy.remainingMillis(10, restored, 601_000L));
    }

    @Test public void rebootOrUnknownBootDiscardsTheOldDeadline() {
        assertEquals(-1L, ArchiveViewerTimeoutPolicy.restoreStoppedAt(1000L, 4, 5, 901_000L));
        assertEquals(-1L, ArchiveViewerTimeoutPolicy.restoreStoppedAt(1000L, -1, -1, 901_000L));
        assertEquals(-1L, ArchiveViewerTimeoutPolicy.restoreStoppedAt(1000L, 4, 4, 500L));
    }
}
