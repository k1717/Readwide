package com.readwide.manager.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class TapZoneProfileTest {
    @Test public void normalizationLeavesRoomForMenu() {
        TapZoneProfile value = new TapZoneProfile(999, 90, 90);
        assertEquals(PrefsManager.TAP_ZONE_VERTICAL, value.mode);
        assertEquals(80, value.leading);
        assertEquals(10, value.trailing);
        assertEquals(10, value.middle());
        value = new TapZoneProfile(0, -1, -1);
        assertEquals(5, value.leading);
        assertEquals(5, value.trailing);
    }

    @Test public void documentTypesSelectIndependentProfiles() {
        assertEquals(TapZoneProfile.Viewer.EPUB, TapZoneProfile.forDocument("EPUB"));
        assertEquals(TapZoneProfile.Viewer.MARKDOWN, TapZoneProfile.forDocument("Markdown"));
        assertEquals(TapZoneProfile.Viewer.WORD, TapZoneProfile.forDocument("Word"));
        assertEquals(TapZoneProfile.Viewer.WORD, TapZoneProfile.forDocument("HWP"));
        assertEquals(TapZoneProfile.Viewer.TXT, TapZoneProfile.forDocument(null));
    }

    @Test public void profileOverloadPreservesExistingHitTests() {
        for (int mode : new int[]{0, 1, 999}) for (int leading : new int[]{-1, 5, 35, 80, 99}) {
            TapZoneProfile profile = new TapZoneProfile(mode, leading, 80);
            for (int x = 0; x <= 100; x++) for (int y = 0; y <= 100; y++) {
                assertEquals(TapZoneMath.actionForTap(x, y, 100, 100, true, true, mode, leading, 80),
                        TapZoneMath.actionForTap(x, y, 100, 100, true, true, profile));
            }
        }
    }

    @Test public void originalImageBoundaryIncludingRightEdgeIsUnchanged() {
        TapZoneProfile profile = new TapZoneProfile(1, 33, 33);
        for (boolean mirrored : new boolean[]{false, true}) {
            for (float x : new float[]{0f, 0.32999f, 0.33f, 0.5f, 0.66999f, 0.67f, 1f, Float.NaN}) {
                assertEquals(ImageSequenceNavigationMath.visualTapZoneDelta(x, mirrored),
                        ImageSequenceNavigationMath.visualTapZoneDelta(x, 0.9f, profile, mirrored));
            }
        }
    }

    @Test public void verticalImageUsesYAndPreservesMirroring() {
        TapZoneProfile profile = new TapZoneProfile(0, 20, 40);
        assertEquals(-1, ImageSequenceNavigationMath.visualTapZoneDelta(0.5f, 0.1f, profile, false));
        assertEquals(0, ImageSequenceNavigationMath.visualTapZoneDelta(0.1f, 0.5f, profile, false));
        assertEquals(1, ImageSequenceNavigationMath.visualTapZoneDelta(0.5f, 0.9f, profile, false));
        assertEquals(-1, ImageSequenceNavigationMath.visualTapZoneDelta(0.5f, 0.9f, profile, true));
    }

    @Test public void allProfileKeysHaveBackupTypesAndResetCoverage() {
        String[] keys = TapZoneProfile.withResetKeys(new String[]{"existing"});
        assertEquals(21, keys.length);
        assertEquals(21, new java.util.HashSet<>(java.util.Arrays.asList(keys)).size());
        for (TapZoneProfile.Viewer viewer : TapZoneProfile.Viewer.values()) {
            if (viewer == TapZoneProfile.Viewer.TXT) continue;
            assertEquals(Boolean.class, PreferenceBackupSchema.valueType(TapZoneProfile.key(viewer, "use_txt")));
            assertEquals(Integer.class, PreferenceBackupSchema.valueType(TapZoneProfile.key(viewer, "mode")));
            assertFalse(PreferenceBackupSchema.accepts(TapZoneProfile.key(viewer, "leading"), "35"));
            assertTrue(java.util.Arrays.asList(keys).contains(TapZoneProfile.key(viewer, "trailing")));
        }
    }
}
