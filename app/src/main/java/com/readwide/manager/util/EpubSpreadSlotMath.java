package com.readwide.manager.util;

/**
 * Pure physical-slot mapping for an EPUB two-page spread.
 *
 * <p>The primary/secondary indices describe logical reading order. In RTL
 * mode Android reverses the spread container, so the primary page occupies
 * the physical right slot instead of the physical left slot.</p>
 */
public final class EpubSpreadSlotMath {

    public static final int PHYSICAL_LEFT = -1;
    public static final int CENTER = 0;
    public static final int PHYSICAL_RIGHT = 1;

    private EpubSpreadSlotMath() {
    }

    /**
     * Returns the physical slot occupied by {@code pageIndex}.
     *
     * <p>A missing or invalid two-page pair, and any page outside that pair,
     * maps to {@link #CENTER}. This keeps standalone final pages centered.</p>
     */
    public static int physicalSlot(
            int pageIndex,
            int primaryIndex,
            int secondaryIndex,
            boolean rtl) {
        if (primaryIndex < 0 || secondaryIndex < 0 || primaryIndex == secondaryIndex) {
            return CENTER;
        }
        if (pageIndex == primaryIndex) {
            return rtl ? PHYSICAL_RIGHT : PHYSICAL_LEFT;
        }
        if (pageIndex == secondaryIndex) {
            return rtl ? PHYSICAL_LEFT : PHYSICAL_RIGHT;
        }
        return CENTER;
    }
}
