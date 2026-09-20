package com.readwide.manager;

import android.os.SystemClock;
import android.view.KeyEvent;
import com.readwide.manager.util.PrefsManager;

/** Behavioral host checks; does not simulate Android's actual GestureDetector. */
public final class PdfTapPagingHostCheck {
    private static int passed, failed;

    private static PdfReaderActivity reader() {
        SystemClock.now = 0;
        PdfReaderActivity a = new PdfReaderActivity();
        a.installButtons();
        return a;
    }

    private static void eq(int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }

    private static void yes(boolean value) {
        if (!value) throw new AssertionError("Expected consumed action");
    }

    private static void check(String name, Runnable test) {
        try {
            test.run(); passed++; System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failed++; System.out.println("FAIL " + name + ": " + failure);
        }
    }

    public static void main(String[] args) {
        check("first tap has no startup delay", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500); eq(6, a.currentPage);
        });
        check("rapid second tap turns again without toggling toolbar", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500);
            SystemClock.now = 30; a.onMatrixTap(900, 500);
            eq(7, a.currentPage); eq(0, a.chromeToggles);
        });
        check("distinct requests one millisecond apart have no interval limit", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500);
            SystemClock.now = 1; a.onMatrixTap(900, 500); eq(7, a.currentPage);
            SystemClock.now = 2; a.onMatrixTap(900, 500); eq(8, a.currentPage);
            SystemClock.now = 3; a.onMatrixTap(900, 500); eq(9, a.currentPage);
        });
        check("spaced repeated taps continue normally", () -> {
            PdfReaderActivity a = reader();
            for (int i = 0; i < 5; i++) { SystemClock.now = i * 100; a.onMatrixTap(900, 500); }
            eq(10, a.currentPage);
        });
        check("opposite direction immediately corrects overshoot", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500);
            SystemClock.now = 20; a.onMatrixTap(100, 500); eq(5, a.currentPage);
            SystemClock.now = 40; a.onMatrixTap(100, 500); eq(4, a.currentPage);
        });
        check("center tap still toggles chrome", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500);
            SystemClock.now = 20; a.onMatrixTap(500, 500); eq(1, a.chromeToggles); eq(6, a.currentPage);
        });
        check("continuous vertical mode accepts every tap", () -> {
            PdfReaderActivity a = reader(); a.verticalPageSlideMode = true;
            yes(a.fast(900, 500)); SystemClock.now = 40; yes(a.fast(900, 500)); eq(7, a.currentPage);
            SystemClock.now = 80; yes(a.fast(900, 500)); eq(8, a.currentPage);
        });
        check("confirmed legacy tap path accepts every tap", () -> {
            PdfReaderActivity a = reader(); yes(a.confirmed(900, 500));
            SystemClock.now = 40; yes(a.confirmed(900, 500)); eq(7, a.currentPage);
        });
        check("previous and next buttons accept rapid taps and reversal", () -> {
            PdfReaderActivity a = reader(); a.nextButton.performClick();
            SystemClock.now = 40; a.nextButton.performClick(); eq(7, a.currentPage);
            a.prevButton.performClick(); eq(6, a.currentPage);
            SystemClock.now = 60; a.prevButton.performClick(); eq(5, a.currentPage);
        });
        check("switching tap entry points does not discard requests", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500);
            SystemClock.now = 40; a.nextButton.performClick(); yes(a.confirmed(900, 500)); eq(8, a.currentPage);
            SystemClock.now = 80; a.nextButton.performClick(); eq(9, a.currentPage);
        });
        check("hardware keys remain immediate with held-key repeat suppression", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500); SystemClock.now = 10;
            yes(a.key(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 0))); eq(7, a.currentPage);
            yes(a.key(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 1))); eq(7, a.currentPage);
            yes(a.key(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN, 0))); eq(7, a.currentPage);
        });
        check("swipes remain immediate", () -> {
            PdfReaderActivity a = reader(); a.onMatrixTap(900, 500); SystemClock.now = 10;
            yes(a.onMatrixPageSwipe(1)); yes(a.onMatrixPageSwipe(1)); eq(8, a.currentPage);
        });
        check("landscape advances one spread per tap", () -> {
            PdfReaderActivity a = reader(); a.spread = true; a.currentPage = 0;
            a.onMatrixTap(900, 500); eq(2, a.currentPage);
            SystemClock.now = 40; a.onMatrixTap(900, 500); eq(4, a.currentPage);
        });
        check("disabled tap paging retains toolbar behavior", () -> {
            PdfReaderActivity a = reader(); a.prefs.enabled = false; a.onMatrixTap(900, 500);
            eq(5, a.currentPage); eq(1, a.chromeToggles);
        });
        check("confirmed tap on a zoomed page toggles chrome instead of paging", () -> {
            PdfReaderActivity a = reader(); a.pdfPageMatrixView.zoomed = true;
            a.onMatrixTap(900, 500); eq(5, a.currentPage); eq(1, a.chromeToggles);
            a.pdfPageMatrixView.zoomed = false;
            a.onMatrixTap(900, 500); eq(6, a.currentPage);
        });
        check("pinch and cancelled touch do not turn a page", () -> {
            PdfReaderActivity a = reader(); a.gestureSawMultiTouch = true;
            if (a.fast(900, 500)) throw new AssertionError("Pinch consumed as tap");
            a.gestureSawMultiTouch = false;
            if (a.cancelTap()) throw new AssertionError("Cancelled touch consumed as tap");
            eq(5, a.currentPage);
        });
        check("vertical tap-zone layout accepts every tap", () -> {
            PdfReaderActivity a = reader(); a.prefs.mode = PrefsManager.TAP_ZONE_VERTICAL;
            a.onMatrixTap(500, 900); SystemClock.now = 40; a.onMatrixTap(500, 900); eq(7, a.currentPage);
        });
        check("last-page tap does not prevent immediate return", () -> {
            PdfReaderActivity a = reader(); a.currentPage = a.pageCount - 1;
            a.onMatrixTap(900, 500); SystemClock.now = 10; a.onMatrixTap(100, 500); eq(28, a.currentPage);
        });
        System.out.println("TOTAL: " + passed + " passed; " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
