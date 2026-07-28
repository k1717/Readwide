package com.readwide.manager.util;

/**
 * Pure preference-selection rules for the EPUB reader font.
 *
 * <p>The EPUB preference is intentionally separate from the TXT/legacy
 * {@code font_family} preference. Existing installs are migrated once from the
 * legacy value so a font previously selected from an EPUB is not lost.</p>
 */
public final class EpubFontPreferenceMath {
    public static final String BOOK_FONT = "document_default";

    private EpubFontPreferenceMath() {
    }

    public static String normalize(String value) {
        if (value == null) return BOOK_FONT;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? BOOK_FONT : trimmed;
    }

    public static String resolveInitialValue(boolean hasDedicatedValue,
                                             String dedicatedValue,
                                             boolean hasLegacyGlobalValue,
                                             String legacyGlobalValue) {
        if (hasDedicatedValue) return normalize(dedicatedValue);
        if (!hasLegacyGlobalValue) return BOOK_FONT;
        String legacy = legacyGlobalValue == null ? "" : legacyGlobalValue.trim();
        return legacy.isEmpty() ? BOOK_FONT : legacy;
    }

    public static String resolveActiveSelection(String activityOverride,
                                                String persistedValue) {
        if (activityOverride != null && !activityOverride.trim().isEmpty()) {
            return normalize(activityOverride);
        }
        return normalize(persistedValue);
    }
}
