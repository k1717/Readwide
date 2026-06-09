package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Diagnostic classification for solid dictionary carryover between RAR3/RAR4 classic-LZ entries. */
final class Rar3SolidCarryoverCheck {
    private static final int WINDOW_MASK = (4 * 1024 * 1024) - 1;

    enum Status {
        NOT_VALIDATED,
        OK,
        TARGET_STARTED_WITHOUT_DICTIONARY,
        WINDOW_NOT_INITIALIZED_AFTER_WRITE,
        POSITION_ADVANCE_MISMATCH,
        CRC_MISMATCH_WITH_VALID_CARRYOVER
    }

    @NonNull final Status status;
    final int expectedPositionAfter;
    @NonNull final String detail;

    private Rar3SolidCarryoverCheck(@NonNull Status status,
                                    int expectedPositionAfter,
                                    @NonNull String detail) {
        this.status = status;
        this.expectedPositionAfter = expectedPositionAfter;
        this.detail = detail;
    }

    @NonNull
    static Rar3SolidCarryoverCheck analyze(boolean target,
                                           boolean solid,
                                           @NonNull Rar3SolidSequenceTrace.EntryStatus entryStatus,
                                           boolean initializedBefore,
                                           int writePositionBefore,
                                           boolean initializedAfter,
                                           int writePositionAfter,
                                           long written) {
        if (target && solid && !initializedBefore) {
            return new Rar3SolidCarryoverCheck(
                    Status.TARGET_STARTED_WITHOUT_DICTIONARY,
                    -1,
                    "solid target started before any primer initialized the shared dictionary");
        }
        if (entryStatus != Rar3SolidSequenceTrace.EntryStatus.SUCCESS
                && entryStatus != Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH) {
            return new Rar3SolidCarryoverCheck(
                    Status.NOT_VALIDATED,
                    -1,
                    "entry did not decode far enough to validate dictionary carryover");
        }
        if (written < 0L) {
            return new Rar3SolidCarryoverCheck(
                    Status.NOT_VALIDATED,
                    -1,
                    "decoded-byte count is unavailable");
        }
        int expected = (int) ((writePositionBefore + written) & WINDOW_MASK);
        if (!initializedAfter) {
            return new Rar3SolidCarryoverCheck(
                    Status.WINDOW_NOT_INITIALIZED_AFTER_WRITE,
                    expected,
                    "decoded entry wrote bytes but the shared dictionary was not marked initialized");
        }
        if (writePositionAfter != expected) {
            return new Rar3SolidCarryoverCheck(
                    Status.POSITION_ADVANCE_MISMATCH,
                    expected,
                    "write position should advance by decoded bytes across the shared solid dictionary");
        }
        if (entryStatus == Rar3SolidSequenceTrace.EntryStatus.CRC_MISMATCH) {
            return new Rar3SolidCarryoverCheck(
                    Status.CRC_MISMATCH_WITH_VALID_CARRYOVER,
                    expected,
                    "decoded-size and dictionary-position carryover look valid; remaining mismatch is likely inside classic-LZ decode semantics");
        }
        return new Rar3SolidCarryoverCheck(
                Status.OK,
                expected,
                "dictionary carryover advanced as expected");
    }

    @NonNull
    String compact() {
        return status + "(expectedPos=" + expectedPositionAfter + "; " + detail + ")";
    }

    @NonNull
    String markdownStatus() {
        return status + (expectedPositionAfter >= 0
                ? String.format(Locale.US, " / expectedPos=%d", expectedPositionAfter)
                : "");
    }
}
