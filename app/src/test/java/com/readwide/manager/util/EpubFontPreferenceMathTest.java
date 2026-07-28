package com.readwide.manager.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EpubFontPreferenceMathTest {
    @Test
    public void newInstallUsesBookFont() {
        assertEquals(EpubFontPreferenceMath.BOOK_FONT,
                EpubFontPreferenceMath.resolveInitialValue(
                        false, null, false, null));
    }

    @Test
    public void legacyCustomSelectionMigratesOnce() {
        assertEquals("My Reader Font",
                EpubFontPreferenceMath.resolveInitialValue(
                        false, null, true, "My Reader Font"));
    }

    @Test
    public void explicitLegacySystemSansIsPreserved() {
        assertEquals("default",
                EpubFontPreferenceMath.resolveInitialValue(
                        false, null, true, "default"));
    }

    @Test
    public void dedicatedBookFontWinsOverLegacyCustomFont() {
        assertEquals(EpubFontPreferenceMath.BOOK_FONT,
                EpubFontPreferenceMath.resolveInitialValue(
                        true, EpubFontPreferenceMath.BOOK_FONT,
                        true, "Old Font"));
    }

    @Test
    public void activitySelectionWinsDuringImmediateRefresh() {
        assertEquals("serif",
                EpubFontPreferenceMath.resolveActiveSelection(
                        "serif", EpubFontPreferenceMath.BOOK_FONT));
    }

    @Test
    public void newBookUsesPersistedEpubSelection() {
        assertEquals("monospace",
                EpubFontPreferenceMath.resolveActiveSelection(null, "monospace"));
    }
}
