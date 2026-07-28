package com.readwide.manager.util;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Android 15 / targetSdk 35 makes apps draw behind status and navigation bars.
 * This helper adds the required safe-area padding so toolbars, lists, and
 * bottom controls are not hidden by the status bar or 3-button navigation bar.
 */
public final class EdgeToEdgeUtil {
    private EdgeToEdgeUtil() {}

    public interface ChromeVisibilityProvider {
        boolean isChromeVisible();
    }

    public static void applyStandardInsets(Activity activity,
                                           View root,
                                           @Nullable View topBar,
                                           @Nullable View bottomContent) {
        prepareWindow(activity, root);
        final Padding rootPad = new Padding(root);
        final Padding topPad = topBar != null ? new Padding(topBar) : null;
        final Padding bottomPad = bottomContent != null ? new Padding(bottomContent) : null;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());

            root.setPadding(rootPad.left + bars.left, rootPad.top,
                    rootPad.right + bars.right, rootPad.bottom);

            if (topBar != null) {
                topBar.setPadding(topPad.left, topPad.top + bars.top,
                        topPad.right, topPad.bottom);
            }
            if (bottomContent != null) {
                int bottomInset = imeVisible ? Math.max(bars.bottom, ime.bottom) : bars.bottom;
                bottomContent.setPadding(bottomPad.left, bottomPad.top,
                        bottomPad.right, bottomPad.bottom + bottomInset);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    public static void applyFoldableChromeInsets(Activity activity,
                                                 View root,
                                                 @Nullable View topBar,
                                                 @Nullable View bottomContent,
                                                 @Nullable View foldedContent,
                                                 ChromeVisibilityProvider chromeVisibilityProvider) {
        applyFoldableChromeInsetsInternal(activity, root, topBar, bottomContent, foldedContent,
                chromeVisibilityProvider, false);
    }

    /**
     * Same as applyFoldableChromeInsets, but keeps bottom chrome fixed when the
     * soft keyboard appears. DocumentPageActivity uses this because its Word/EPUB
     * bottom toolbar should not jump above the IME while the Find dialog has focus.
     */
    public static void applyFoldableChromeInsetsImeFixed(Activity activity,
                                                        View root,
                                                        @Nullable View topBar,
                                                        @Nullable View bottomContent,
                                                        @Nullable View foldedContent,
                                                        ChromeVisibilityProvider chromeVisibilityProvider) {
        applyFoldableChromeInsetsInternal(activity, root, topBar, bottomContent, foldedContent,
                chromeVisibilityProvider, true);
    }

    private static void applyFoldableChromeInsetsInternal(Activity activity,
                                                         View root,
                                                         @Nullable View topBar,
                                                         @Nullable View bottomContent,
                                                         @Nullable View foldedContent,
                                                         ChromeVisibilityProvider chromeVisibilityProvider,
                                                         boolean keepBottomChromeFixedDuringIme) {
        prepareWindow(activity, root);
        final Padding rootPad = new Padding(root);
        final Padding topPad = topBar != null ? new Padding(topBar) : null;
        final Padding bottomPad = bottomContent != null ? new Padding(bottomContent) : null;
        final Padding foldedPad = foldedContent != null ? new Padding(foldedContent) : null;
        final int foldedExtraInset = dpToPx(activity, 6);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // A display cutout is a physical body constraint, not transient
            // system chrome. Keep it reserved even while immersive bars hide.
            Insets cutout = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());

            boolean chromeVisible = chromeVisibilityProvider == null || chromeVisibilityProvider.isChromeVisible();

            // Keep the document canvas full-width in landscape. A side navigation
            // bar belongs to the overlay controls; applying it to the root shrank
            // each EPUB spread pane and broke fixed-layout scaling.
            root.setPadding(rootPad.left + ReaderBodyInsetMath.bodySideInset(cutout.left),
                    rootPad.top,
                    rootPad.right + ReaderBodyInsetMath.bodySideInset(cutout.right),
                    rootPad.bottom);

            int controlLeft = ReaderBodyInsetMath.overlaySideInset(
                    systemBars.left, cutout.left);
            int controlRight = ReaderBodyInsetMath.overlaySideInset(
                    systemBars.right, cutout.right);
            int topInset = Math.max(systemBars.top, cutout.top);

            if (topBar != null) {
                topBar.setPadding(topPad.left + controlLeft, topPad.top + topInset,
                        topPad.right + controlRight, topPad.bottom);
            }
            if (bottomContent != null) {
                int bottomInset = keepBottomChromeFixedDuringIme
                        ? systemBars.bottom
                        : (imeVisible ? Math.max(systemBars.bottom, ime.bottom) : systemBars.bottom);
                bottomContent.setPadding(bottomPad.left + controlLeft, bottomPad.top,
                        bottomPad.right + controlRight, bottomPad.bottom + bottomInset);
            }
            if (foldedContent != null) {
                int bottomInset = keepBottomChromeFixedDuringIme
                        ? systemBars.bottom
                        : (imeVisible ? 0 : systemBars.bottom);
                boolean visibleTopStripOwnsInset = !chromeVisible
                        && topBar != null
                        && topBar.getVisibility() == View.VISIBLE
                        && topBar.getHeight() > 0;
                int foldedTopInset = chromeVisible || visibleTopStripOwnsInset ? 0 : topInset + foldedExtraInset;
                int foldedBottomInset = chromeVisible ? 0 : bottomInset + foldedExtraInset;
                foldedContent.setPadding(foldedPad.left, foldedPad.top + foldedTopInset,
                        foldedPad.right, foldedPad.bottom + foldedBottomInset);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }


    /**
     * PDF keeps Android system bars visible while its own chrome is toggled. The
     * root therefore owns stable left/right system-safe edges (including a
     * landscape three-button navigation rail), while the visible app bars own
     * their top/bottom insets. No extra navigation spacer is ever needed.
     */
    public static void applyPdfReaderInsets(Activity activity,
                                            View root,
                                            @Nullable View topBar,
                                            @Nullable View bottomContent,
                                            @Nullable View hiddenNavigationSpacer) {
        prepareWindow(activity, root);
        final Padding rootPad = new Padding(root);
        final Padding topPad = topBar != null ? new Padding(topBar) : null;
        final Padding bottomPad = bottomContent != null ? new Padding(bottomContent) : null;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            // PDF keeps system bars visible. Ignoring transient visibility avoids
            // a one-frame safe-area collapse during Samsung rotation/bar updates.
            Insets systemBars = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars());
            Insets cutout = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            int bodyLeft = Math.max(systemBars.left, cutout.left);
            int bodyRight = Math.max(systemBars.right, cutout.right);
            root.setPadding(rootPad.left + ReaderBodyInsetMath.bodySideInset(bodyLeft),
                    rootPad.top,
                    rootPad.right + ReaderBodyInsetMath.bodySideInset(bodyRight),
                    rootPad.bottom);

            // Root padding already protects both content and overlay controls.
            int controlLeft = 0;
            int controlRight = 0;
            int topInset = Math.max(systemBars.top, cutout.top);

            if (topBar != null) {
                topBar.setPadding(topPad.left + controlLeft, topPad.top + topInset,
                        topPad.right + controlRight, topPad.bottom);
            }
            if (bottomContent != null) {
                int systemBottom = Math.max(systemBars.bottom, cutout.bottom);
                int bottomInset = imeVisible
                        ? Math.max(systemBottom, ime.bottom) : systemBottom;
                bottomContent.setPadding(bottomPad.left + controlLeft, bottomPad.top,
                        bottomPad.right + controlRight, bottomPad.bottom + bottomInset);
            }
            if (hiddenNavigationSpacer != null) {
                // Visible chrome owns bars.bottom through bottomContent padding.
                // Hidden chrome keeps its safe edge in PdfReaderActivity's
                // viewport reserve, so a second spacer would recreate the old
                // empty bottom band.
                int spacerHeight = 0;
                android.view.ViewGroup.LayoutParams lp = hiddenNavigationSpacer.getLayoutParams();
                if (lp != null && lp.height != spacerHeight) {
                    lp.height = spacerHeight;
                    hiddenNavigationSpacer.setLayoutParams(lp);
                }
                hiddenNavigationSpacer.setVisibility(spacerHeight > 0 ? View.VISIBLE : View.GONE);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** PDF-only policy: app chrome may hide, but Android status/navigation bars stay. */
    public static void applyPdfSystemBarVisibility(Activity activity, View root) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), root);
        controller.show(WindowInsetsCompat.Type.systemBars());
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * Shared reader system-bar policy. Reader controls own the navigation bar while
     * visible; collapsed controls hide it immersively and allow a swipe to reveal a
     * transient overlay. The independent status-bar preference remains authoritative
     * in both states.
     */
    public static void applyReaderSystemBarVisibility(Activity activity,
                                                      View root,
                                                      boolean chromeVisible,
                                                      boolean showStatusBar) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), root);
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.navigationBars());
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars());
        }
        if (showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars());
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars());
        }
        ViewCompat.requestApplyInsets(root);
    }

    public static void applyReaderInsets(Activity activity,
                                         View root,
                                         @Nullable View topBar,
                                         View reader,
                                         @Nullable View bottomBar) {
        prepareWindow(activity, root);
        final Padding rootPad = new Padding(root);
        final Padding topPad = topBar != null ? new Padding(topBar) : null;
        final Padding readerPad = new Padding(reader);
        final Padding bottomPad = bottomBar != null ? new Padding(bottomBar) : null;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());

            // Horizontal inset at the root clears the side nav bar / cutout for the
            // whole reader; children only handle their own top/bottom insets.
            root.setPadding(rootPad.left + bars.left, rootPad.top,
                    rootPad.right + bars.right, rootPad.bottom);

            if (topBar != null) {
                topBar.setPadding(topPad.left, topPad.top + bars.top,
                        topPad.right, topPad.bottom);
            }
            reader.setPadding(readerPad.left, readerPad.top + bars.top,
                    readerPad.right, readerPad.bottom + bars.bottom);
            if (bottomBar != null) {
                bottomBar.setPadding(bottomPad.left, bottomPad.top,
                        bottomPad.right, bottomPad.bottom + bars.bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static void prepareWindow(Activity activity, View root) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        boolean darkMode = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, root);
        controller.setAppearanceLightStatusBars(false);      // app bar is neutral/dark
        controller.setAppearanceLightNavigationBars(!darkMode);
    }

    private static int dpToPx(Activity activity, float dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    private static final class Padding {
        final int left, top, right, bottom;
        Padding(View v) {
            left = v.getPaddingLeft();
            top = v.getPaddingTop();
            right = v.getPaddingRight();
            bottom = v.getPaddingBottom();
        }
    }

    /**
     * Sets left/right margins on a view so its full extent (including background)
     * stops short of the side navigation bar in landscape. Padding only insets the
     * content, not the background, so an opaque bar would still paint under the
     * nav bar; a margin shrinks the bar itself. baseLeft/baseRight are the view's
     * original margins to preserve.
     */
    private static void setHorizontalMargins(View view, int baseLeft, int baseRight,
                                             int extraLeft, int extraRight) {
        if (view == null) return;
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(lp instanceof android.view.ViewGroup.MarginLayoutParams)) return;
        android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
        int newLeft = baseLeft + extraLeft;
        int newRight = baseRight + extraRight;
        if (mlp.leftMargin != newLeft || mlp.rightMargin != newRight) {
            mlp.leftMargin = newLeft;
            mlp.rightMargin = newRight;
            view.setLayoutParams(mlp);
        }
    }
}
