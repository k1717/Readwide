package com.textview.reader.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TapZoneMathTest {
    @Test
    public void actionForTap_returnsMenuWhenContentOrTapPagingUnavailable() {
        assertEquals(TapZoneMath.ACTION_MENU, TapZoneMath.actionForTap(
                10, 10, 100, 100, false, true, PrefsManager.TAP_ZONE_HORIZONTAL, 35, 35));
        assertEquals(TapZoneMath.ACTION_MENU, TapZoneMath.actionForTap(
                10, 10, 100, 100, true, false, PrefsManager.TAP_ZONE_HORIZONTAL, 35, 35));
    }

    @Test
    public void actionForTap_horizontalZonesMapToPreviousMenuNext() {
        assertEquals(TapZoneMath.ACTION_PREVIOUS, TapZoneMath.actionForTap(
                20, 50, 100, 100, true, true, PrefsManager.TAP_ZONE_HORIZONTAL, 35, 35));
        assertEquals(TapZoneMath.ACTION_MENU, TapZoneMath.actionForTap(
                50, 50, 100, 100, true, true, PrefsManager.TAP_ZONE_HORIZONTAL, 35, 35));
        assertEquals(TapZoneMath.ACTION_NEXT, TapZoneMath.actionForTap(
                80, 50, 100, 100, true, true, PrefsManager.TAP_ZONE_HORIZONTAL, 35, 35));
    }

    @Test
    public void actionForTap_verticalZonesMapToPreviousMenuNext() {
        assertEquals(TapZoneMath.ACTION_PREVIOUS, TapZoneMath.actionForTap(
                50, 20, 100, 100, true, true, PrefsManager.TAP_ZONE_VERTICAL, 35, 35));
        assertEquals(TapZoneMath.ACTION_MENU, TapZoneMath.actionForTap(
                50, 50, 100, 100, true, true, PrefsManager.TAP_ZONE_VERTICAL, 35, 35));
        assertEquals(TapZoneMath.ACTION_NEXT, TapZoneMath.actionForTap(
                50, 80, 100, 100, true, true, PrefsManager.TAP_ZONE_VERTICAL, 35, 35));
    }

    @Test
    public void actionForTap_clampsOverlappingZonePercents() {
        assertEquals(TapZoneMath.ACTION_NEXT, TapZoneMath.actionForTap(
                96, 50, 100, 100, true, true, PrefsManager.TAP_ZONE_HORIZONTAL, 80, 80));
        assertEquals(TapZoneMath.ACTION_MENU, TapZoneMath.actionForTap(
                85, 50, 100, 100, true, true, PrefsManager.TAP_ZONE_HORIZONTAL, 80, 80));
    }
}
