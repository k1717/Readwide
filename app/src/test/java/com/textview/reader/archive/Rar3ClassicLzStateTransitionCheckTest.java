package com.textview.reader.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Rar3ClassicLzStateTransitionCheckTest {
    @Test
    public void check_newDistancePushesOldDistanceAndLastState() {
        Rar3ClassicLzStateTransitionCheck.Result result = Rar3ClassicLzStateTransitionCheck.check(
                Rar3DecodeAction.Type.LONG_MATCH,
                9,
                5,
                -1,
                new Rar3ClassicLzStateTransitionCheck.Snapshot(7, 6, 5, 4, 7, 3),
                new Rar3ClassicLzStateTransitionCheck.Snapshot(9, 7, 6, 5, 9, 5));

        assertTrue(result.ok);
    }

    @Test
    public void check_oldDistancePromotesSelectedSlot() {
        Rar3ClassicLzStateTransitionCheck.Result result = Rar3ClassicLzStateTransitionCheck.check(
                Rar3DecodeAction.Type.OLD_DISTANCE_MATCH,
                5,
                4,
                2,
                new Rar3ClassicLzStateTransitionCheck.Snapshot(7, 6, 5, 4, 7, 3),
                new Rar3ClassicLzStateTransitionCheck.Snapshot(5, 7, 6, 4, 5, 4));

        assertTrue(result.ok);
    }

    @Test
    public void check_repeatLastKeepsOldDistanceList() {
        Rar3ClassicLzStateTransitionCheck.Result result = Rar3ClassicLzStateTransitionCheck.check(
                Rar3DecodeAction.Type.REPEAT_LAST_MATCH,
                7,
                3,
                -1,
                new Rar3ClassicLzStateTransitionCheck.Snapshot(7, 6, 5, 4, 7, 3),
                new Rar3ClassicLzStateTransitionCheck.Snapshot(7, 6, 5, 4, 7, 3));

        assertTrue(result.ok);
    }

    @Test
    public void check_detectsSuspiciousTransition() {
        Rar3ClassicLzStateTransitionCheck.Result result = Rar3ClassicLzStateTransitionCheck.check(
                Rar3DecodeAction.Type.LONG_MATCH,
                9,
                5,
                -1,
                new Rar3ClassicLzStateTransitionCheck.Snapshot(7, 6, 5, 4, 7, 3),
                new Rar3ClassicLzStateTransitionCheck.Snapshot(7, 6, 5, 4, 9, 5));

        assertFalse(result.ok);
        assertTrue(result.detail.contains("old-distance list"));
    }
}
