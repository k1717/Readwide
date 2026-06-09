package com.textview.reader.util;

public final class ImageSequenceNavigationMath {
    private ImageSequenceNavigationMath() {}

    public static int clampIndex(int index, int itemCount) {
        if (itemCount <= 0) return 0;
        return Math.max(0, Math.min(itemCount - 1, index));
    }

    public static int nextIndex(int currentIndex, int direction, int itemCount) {
        if (itemCount <= 0) return 0;
        int next = currentIndex + direction;
        if (next < 0 || next >= itemCount) return clampIndex(currentIndex, itemCount);
        return next;
    }
}
