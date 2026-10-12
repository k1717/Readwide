package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.readwide.manager.view.CustomReaderView;

import org.junit.Test;

import java.util.ArrayList;

/** Source regressions for the interval before a debounced page-index rebuild. */
public class LargeTextExactPageIndexInvalidationTest {
    @Test public void changedGeometryRetiresReadyAnchorsImmediately() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int old = state.beginBuild("portrait");
        state.completeCurrent(old, "portrait", anchors(0, 100, 200), false, "");

        assertTrue(state.invalidateIfSignatureChanged("landscape"));
        assertFalse(state.isReady());
        assertEquals(0, state.readyPageCount());
        assertNull(state.anchorForPage(2));
        assertTrue(state.copyAnchors().isEmpty());
        assertNull(state.tapTargetIfUsable("portrait", 0, 1));
        assertFalse(state.completeCurrent(old, "portrait", anchors(0, 100), false, "").current);

        int replacement = state.beginBuild("landscape");
        assertTrue(replacement > old);
        assertTrue(state.completeCurrent(replacement, "landscape", anchors(0, 180), false, "").ready);
        assertEquals(2, state.readyPageCount());
    }

    @Test public void redundantGeometryNotificationKeepsReadyIndex() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("same-layout");
        state.completeCurrent(generation, "same-layout", anchors(0, 100), false, "");

        assertFalse(state.invalidateIfSignatureChanged("same-layout"));
        assertTrue(state.isGenerationCurrent(generation));
        assertTrue(state.isReady());
        assertEquals(2, state.readyPageCount());
        assertEquals(-1, state.beginBuild("same-layout"));
    }

    @Test public void changedFontCancelsRunningWorkerBeforeItsCommit() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int old = state.beginBuild("old-font");

        assertTrue(state.invalidateIfSignatureChanged("new-font"));
        assertFalse(state.isGenerationCurrent(old));
        assertFalse(state.beginCalculation(old, "old-font"));
        assertFalse(state.completeCurrent(old, "old-font", anchors(0, 100), false, "").current);
        assertFalse(state.isReady());

        int replacement = state.beginBuild("new-font");
        assertTrue(replacement > old);
        assertTrue(state.beginCalculation(replacement, "new-font"));
        assertTrue(state.completeCurrent(replacement, "new-font", anchors(0, 80), false, "").ready);
    }

    @Test public void matchingLayoutKeepsRunningCacheValidation() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int generation = state.beginBuild("same-layout");

        assertFalse(state.invalidateIfSignatureChanged("same-layout"));
        assertTrue(state.isGenerationCurrent(generation));
        assertTrue(state.isCheckingCache());
        assertEquals(-1, state.beginBuild("same-layout"));
        assertTrue(state.completeCurrent(generation, "same-layout", anchors(0, 100), false, "").ready);
    }

    @Test public void intermediatePreferenceChangesCannotResurrectRetiredModel() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int old = state.beginBuild("old");
        state.completeCurrent(old, "old", anchors(0, 100), false, "");

        assertTrue(state.invalidateIfSignatureChanged("new-margin-old-font"));
        assertFalse(state.invalidateIfSignatureChanged("new-margin-old-font"));
        assertTrue(state.invalidateIfSignatureChanged("new-margin-new-font"));
        assertFalse(state.isReady());
        assertNull(state.anchorForPage(1));
        assertFalse(state.completeCurrent(old, "old", anchors(0, 100), false, "").current);

        int current = state.beginBuild("new-margin-new-font");
        assertTrue(state.completeCurrent(current, "new-margin-new-font", anchors(0, 75), false, "").ready);
    }

    @Test public void freshLoadKeepsCacheCheckUntilThereIsAnOldModel() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        assertFalse(state.invalidateIfSignatureChanged("startup-layout"));
        assertTrue(state.isCheckingCache());
        int generation = state.beginBuild("startup-layout");
        assertTrue(generation > 0);
        assertTrue(state.isCheckingCache());
    }

    @Test public void unavailableLayoutRetiresReadyModelAndRejectsLateCompletion() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        int old = state.beginBuild("usable-layout");
        state.completeCurrent(old, "usable-layout", anchors(0, 100), false, "");

        assertTrue(state.invalidateIfSignatureChanged(""));
        assertFalse(state.isReady());
        assertEquals(0, state.readyPageCount());
        assertNull(state.anchorForPage(1));
        assertFalse(state.completeCurrent(old, "usable-layout", anchors(0, 100), false, "").current);
        assertFalse(state.invalidateIfSignatureChanged(""));

        // Returning to the same real geometry can check the existing disk cache.
        int restored = state.beginBuild("usable-layout");
        assertTrue(restored > old);
        assertTrue(state.isCheckingCache());
        assertTrue(state.completeCurrent(restored, "usable-layout", anchors(0, 100), false, "").ready);
    }

    @Test public void unavailableStartupLayoutDoesNotConvertCacheCheckIntoRecount() {
        LargeTextExactPageIndexState state = new LargeTextExactPageIndexState();
        assertFalse(state.invalidateIfSignatureChanged(""));
        assertTrue(state.isCheckingCache());
        assertFalse(state.isReady());
        int generation = state.beginBuild("first-usable-layout");
        assertTrue(state.isCheckingCache());
        assertTrue(state.completeCurrent(generation, "first-usable-layout", anchors(0, 100), false, "").ready);
    }

    private static ArrayList<CustomReaderView.PageTextAnchor> anchors(int... positions) {
        ArrayList<CustomReaderView.PageTextAnchor> result = new ArrayList<>();
        for (int position : positions) result.add(new CustomReaderView.PageTextAnchor(position, "", ""));
        return result;
    }
}
