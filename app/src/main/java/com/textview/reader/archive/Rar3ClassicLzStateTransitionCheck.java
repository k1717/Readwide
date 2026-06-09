package com.textview.reader.archive;

import androidx.annotation.NonNull;

/** Verifies classic-LZ match state transitions for diagnostic solid probes. */
final class Rar3ClassicLzStateTransitionCheck {
    private Rar3ClassicLzStateTransitionCheck() {}

    @NonNull
    static Result check(@NonNull Rar3DecodeAction.Type actionType,
                        int distance,
                        int decodedLength,
                        int oldDistanceSlot,
                        @NonNull Snapshot before,
                        @NonNull Snapshot after) {
        int[] expectedOld = before.oldDistances.clone();
        int expectedLastDistance = before.lastDistance;
        int expectedLastLength = before.lastLength;

        switch (actionType) {
            case LONG_MATCH:
            case SHORT_DISTANCE_MATCH:
                if (distance > 0) {
                    expectedOld = push(expectedOld, distance);
                    expectedLastDistance = distance;
                }
                if (decodedLength > 0) expectedLastLength = decodedLength;
                break;
            case OLD_DISTANCE_MATCH:
                if (oldDistanceSlot < 0 || oldDistanceSlot >= before.oldDistances.length) {
                    return Result.suspicious("old-distance slot out of range: " + oldDistanceSlot);
                }
                int oldDistance = before.oldDistances[oldDistanceSlot];
                if (oldDistance <= 0) {
                    return Result.suspicious("old-distance slot has no previous distance: " + oldDistanceSlot);
                }
                if (distance != oldDistance) {
                    return Result.suspicious("old-distance decoded distance mismatch: expected="
                            + oldDistance + ", actual=" + distance + ", slot=" + oldDistanceSlot);
                }
                expectedOld = promote(expectedOld, oldDistanceSlot);
                expectedLastDistance = distance;
                if (decodedLength > 0) expectedLastLength = decodedLength;
                break;
            case REPEAT_LAST_MATCH:
                if (before.lastDistance <= 0 || before.lastLength <= 0) {
                    return Result.suspicious("repeat-last has no previous distance/length");
                }
                if (distance != before.lastDistance || decodedLength != before.lastLength) {
                    return Result.suspicious("repeat-last did not reuse previous distance/length: expected="
                            + before.lastDistance + '/' + before.lastLength
                            + ", actual=" + distance + '/' + decodedLength);
                }
                expectedLastLength = decodedLength;
                break;
            default:
                return Result.ok();
        }

        if (!sameOldDistances(expectedOld, after.oldDistances)) {
            return Result.suspicious("old-distance list transition mismatch: expected="
                    + formatOld(expectedOld) + ", actual=" + formatOld(after.oldDistances));
        }
        if (expectedLastDistance != after.lastDistance) {
            return Result.suspicious("last distance transition mismatch: expected="
                    + expectedLastDistance + ", actual=" + after.lastDistance);
        }
        if (expectedLastLength != after.lastLength) {
            return Result.suspicious("last length transition mismatch: expected="
                    + expectedLastLength + ", actual=" + after.lastLength);
        }
        return Result.ok();
    }

    @NonNull
    static Snapshot snapshot(@NonNull Rar3UnpackState state) {
        return new Snapshot(
                state.oldDistance(0),
                state.oldDistance(1),
                state.oldDistance(2),
                state.oldDistance(3),
                state.lastDistance(),
                state.lastLength());
    }

    private static int[] push(@NonNull int[] old, int distance) {
        int[] copy = old.clone();
        for (int i = copy.length - 1; i > 0; i--) copy[i] = copy[i - 1];
        copy[0] = distance;
        return copy;
    }

    private static int[] promote(@NonNull int[] old, int slot) {
        int[] copy = old.clone();
        if (slot <= 0) return copy;
        int distance = copy[slot];
        for (int i = slot; i > 0; i--) copy[i] = copy[i - 1];
        copy[0] = distance;
        return copy;
    }

    private static boolean sameOldDistances(@NonNull int[] left, @NonNull int[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) return false;
        }
        return true;
    }

    @NonNull
    private static String formatOld(@NonNull int[] old) {
        return '[' + String.valueOf(old[0]) + ',' + old[1] + ',' + old[2] + ',' + old[3] + ']';
    }

    static final class Snapshot {
        @NonNull final int[] oldDistances;
        final int lastDistance;
        final int lastLength;

        Snapshot(int old0, int old1, int old2, int old3, int lastDistance, int lastLength) {
            this.oldDistances = new int[] {old0, old1, old2, old3};
            this.lastDistance = lastDistance;
            this.lastLength = lastLength;
        }
    }

    static final class Result {
        final boolean ok;
        @NonNull final String detail;

        private Result(boolean ok, @NonNull String detail) {
            this.ok = ok;
            this.detail = detail;
        }

        @NonNull
        static Result ok() {
            return new Result(true, "ok");
        }

        @NonNull
        static Result suspicious(@NonNull String detail) {
            return new Result(false, detail);
        }
    }
}
