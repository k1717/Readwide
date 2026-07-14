package com.readwide.manager.util;

/** Separates stable reader-canvas insets from live overlay-control insets. */
final class ReaderBodyInsetMath {
    private ReaderBodyInsetMath() {}

    static int bodySideInset(int displayCutoutInset) {
        return Math.max(0, displayCutoutInset);
    }

    static int overlaySideInset(int systemBarInset, int displayCutoutInset) {
        return Math.max(0, systemBarInset - Math.max(0, displayCutoutInset));
    }
}
