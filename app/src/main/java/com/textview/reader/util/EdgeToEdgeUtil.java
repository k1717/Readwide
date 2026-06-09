package com.textview.reader.util;

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
        final Padding topPad = topBar != null ? new Padding(topBar) : null;
        final Padding bottomPad = bottomContent != null ? new Padding(bottomContent) : null;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());

            if (topBar != null) {
                topBar.setPadding(topPad.left, topPad.top + bars.top, topPad.right, topPad.bottom);
            }
            if (bottomContent != null) {
                int bottomInset = imeVisible ? Math.max(bars.bottom, ime.bottom) : bars.bottom;
                bottomContent.setPadding(bottomPad.left, bottomPad.top, bottomPad.right,
                        bottomPad.bottom + bottomInset);
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
        final Padding topPad = topBar != null ? new Padding(topBar) : null;
        final Padding bottomPad = bottomContent != null ? new Padding(bottomContent) : null;
        final Padding foldedPad = foldedContent != null ? new Padding(foldedContent) : null;
        final int foldedExtraInset = dpToPx(activity, 6);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());

            boolean chromeVisible = chromeVisibilityProvider == null || chromeVisibilityProvider.isChromeVisible();

            if (topBar != null) {
                topBar.setPadding(topPad.left, topPad.top + bars.top, topPad.right, topPad.bottom);
            }
            if (bottomContent != null) {
                int bottomInset = keepBottomChromeFixedDuringIme
                        ? bars.bottom
                        : (imeVisible ? Math.max(bars.bottom, ime.bottom) : bars.bottom);
                bottomContent.setPadding(bottomPad.left, bottomPad.top, bottomPad.right,
                        bottomPad.bottom + bottomInset);
            }
            if (foldedContent != null) {
                int bottomInset = keepBottomChromeFixedDuringIme
                        ? bars.bottom
                        : (imeVisible ? 0 : bars.bottom);
                int foldedTopInset = chromeVisible ? 0 : bars.top + foldedExtraInset;
                int foldedBottomInset = chromeVisible ? 0 : bottomInset + foldedExtraInset;
                foldedContent.setPadding(foldedPad.left, foldedPad.top + foldedTopInset,
                        foldedPad.right, foldedPad.bottom + foldedBottomInset);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    public static void applyReaderInsets(Activity activity,
                                         View root,
                                         @Nullable View topBar,
                                         View reader,
                                         @Nullable View bottomBar) {
        prepareWindow(activity, root);
        final Padding topPad = topBar != null ? new Padding(topBar) : null;
        final Padding readerPad = new Padding(reader);
        final Padding bottomPad = bottomBar != null ? new Padding(bottomBar) : null;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());

            if (topBar != null) {
                topBar.setPadding(topPad.left, topPad.top + bars.top, topPad.right, topPad.bottom);
            }
            reader.setPadding(readerPad.left, readerPad.top + bars.top,
                    readerPad.right, readerPad.bottom + bars.bottom);
            if (bottomBar != null) {
                bottomBar.setPadding(bottomPad.left, bottomPad.top, bottomPad.right,
                        bottomPad.bottom + bars.bottom);
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
}
