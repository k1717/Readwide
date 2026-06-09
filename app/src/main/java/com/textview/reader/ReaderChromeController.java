package com.textview.reader;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.textview.reader.model.Theme;
import com.textview.reader.util.PrefsManager;

import java.io.File;

/**
 * Coordinates the TXT reader chrome: system bars, bottom toolbar, filename
 * overlay, page-status row, and inset application. Pagination math remains in
 * ReaderActivity; this class only applies the already-established chrome layout
 * rules.
 */
final class ReaderChromeController {
    private final ReaderActivity activity;

    ReaderChromeController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void applyStatusBarVisibilityPreference() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());

        if (activity.prefs != null && activity.prefs.getShowStatusBar()) {
            controller.show(WindowInsetsCompat.Type.statusBars());
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    void applyReaderSystemBarColors(int backgroundColor, int textColor, int toolbarColor) {
        activity.currentReaderBackgroundColor = backgroundColor;
        activity.currentReaderTextColor = textColor;
        activity.currentReaderToolbarColor = toolbarColor;
        activity.getWindow().setStatusBarColor(backgroundColor);
        if (activity.readerRoot != null) {
            activity.readerRoot.setBackgroundColor(backgroundColor);
        }
        if (activity.readerView != null) {
            activity.readerView.setBackgroundColor(backgroundColor);
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());

        // Status-bar/title/page backgrounds follow the reader background, not the toolbar color.
        controller.setAppearanceLightStatusBars(activity.isLightColor(backgroundColor));

        if (activity.readerPageStatus != null) {
            activity.readerPageStatus.setBackgroundColor(backgroundColor);
            activity.readerPageStatus.setTextColor(textColor);
        }
        if (activity.readerFileTitle != null) {
            activity.readerFileTitle.setBackgroundColor(backgroundColor);
            activity.readerFileTitle.setTextColor(textColor);
            activity.readerFileTitle.setTextSize(14f);
        }

        activity.updateLoadingIndicatorColors(backgroundColor);
        updateBottomMenuBackground();
        applyBottomToolbarForegroundColors(textColor, toolbarColor);
        updateNavigationBarForBottomMenu();
        applyStatusBarVisibilityPreference();
    }

    private void applyBottomToolbarForegroundColors(int foregroundColor, int toolbarBackgroundColor) {
        if (activity.bottomBar != null) {
            tintToolbarTextAndIcons(activity.bottomBar, foregroundColor);
            activity.bottomBar.setElevation(0f);
            activity.bottomBar.setTranslationZ(0f);
            ViewCompat.setElevation(activity.bottomBar, 0f);
        }

        if (activity.positionLabel != null) {
            activity.positionLabel.setTextColor(foregroundColor);
        }

        if (activity.seekBar != null) {
            int trackColor = activity.dialogStyler().blendColors(toolbarBackgroundColor, foregroundColor,
                    activity.isLightColor(toolbarBackgroundColor) ? 0.26f : 0.30f);
            activity.seekBar.setThumbTintList(ColorStateList.valueOf(foregroundColor));
            activity.seekBar.setProgressTintList(ColorStateList.valueOf(foregroundColor));
            activity.seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(trackColor));
        }
    }

    private void tintToolbarTextAndIcons(View view, int color) {
        if (view == null) return;

        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextColor(color);
            Drawable[] drawables = textView.getCompoundDrawables();
            boolean hasDrawable = false;
            for (int i = 0; i < drawables.length; i++) {
                if (drawables[i] != null) {
                    drawables[i] = drawables[i].mutate();
                    drawables[i].setTint(color);
                    hasDrawable = true;
                }
            }
            if (hasDrawable) {
                textView.setCompoundDrawables(drawables[0], drawables[1], drawables[2], drawables[3]);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintToolbarTextAndIcons(group.getChildAt(i), color);
            }
        }
    }

    private GradientDrawable bottomMenuRoundedBackground(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);

        float r = activity.dpToPx(12);
        bg.setCornerRadii(new float[]{
                r, r,   // top-left
                r, r,   // top-right
                0, 0,   // bottom-right
                0, 0    // bottom-left
        });

        return bg;
    }

    private int bottomMenuBlendColor() {
        // The TXT middle/bottom toolbar uses the reading theme toolbar background color directly.
        return activity.currentReaderToolbarColor;
    }

    private boolean isBottomMenuOpen() {
        return activity.toolbarVisible && activity.bottomBar != null && activity.bottomBar.getVisibility() == View.VISIBLE;
    }

    private int currentNavigationAreaColor() {
        return isBottomMenuOpen() ? bottomMenuBlendColor() : activity.currentReaderBackgroundColor;
    }

    void updateBottomMenuBackground() {
        if (activity.bottomBar != null) {
            activity.bottomBar.setBackground(bottomMenuRoundedBackground(bottomMenuBlendColor()));
        }
    }

    void updateNavigationBarForBottomMenu() {
        int navColor = currentNavigationAreaColor();

        // System nav color alone is not enough on modern edge-to-edge Android,
        // but still set it for devices that honor it.
        activity.getWindow().setNavigationBarColor(navColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.getWindow().setNavigationBarDividerColor(navColor);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }

        // Real view behind Android 3-button area. This is what makes the color
        // visibly toggle when the middle-tap menu opens/closes.
        if (activity.navBarSpacer != null) {
            activity.navBarSpacer.setBackgroundColor(navColor);
            activity.navBarSpacer.setVisibility(View.VISIBLE);
            activity.navBarSpacer.bringToFront();
        }

        // Keep the bottom menu above the spacer when open.
        if (activity.bottomBar != null) {
            activity.bottomBar.setElevation(0f);
            activity.bottomBar.setTranslationZ(0f);
            ViewCompat.setElevation(activity.bottomBar, 0f);
        }
        if (activity.bottomBar != null && isBottomMenuOpen()) {
            activity.bottomBar.bringToFront();
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(activity.isLightColor(navColor));
    }

    void applyTheme() {
        Theme theme = activity.themeManager.getActiveTheme();
        if (theme != null) {
            activity.currentReaderBackgroundColor = theme.getBackgroundColor();
        }
        if (activity.readerRoot != null && theme != null) {
            activity.readerRoot.setBackgroundColor(theme.getBackgroundColor());
        }
        if (activity.readerView != null && theme != null) {
            activity.readerView.setBackgroundColor(theme.getBackgroundColor());
        }
        // navigation bar follows reader theme background; set in applyReaderSystemBarColors()
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(false);
        activity.applyPreferences();
    }

    void applyReaderInsets() {
        if (activity.readerRoot == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(activity.readerRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int topInset = (activity.prefs != null && activity.prefs.getShowStatusBar()) ? bars.top : 0;
            int bottomInset = bars.bottom;
            activity.lastReaderTopInset = topInset;
            activity.lastReaderBottomInset = bottomInset;

            if (activity.navBarSpacer != null) {
                FrameLayout.LayoutParams spacerLp =
                        (FrameLayout.LayoutParams) activity.navBarSpacer.getLayoutParams();
                spacerLp.height = bottomInset;
                spacerLp.gravity = Gravity.BOTTOM;
                activity.navBarSpacer.setLayoutParams(spacerLp);
            }

            int readerLineHeight = getStableStatusOffTopPaddingPx();

            // Option B for TXT pagination stability: use the status-bar-OFF top
            // spacing as the canonical layout in both status-bar modes. This keeps
            // page anchors/page count stable when the user toggles the Android
            // status bar.  The page indicator itself is given that extra row of
            // visual height, so the number appears one text row lower instead of
            // stealing or returning vertical space from the paginated TXT body.
            activity.lastStatusOffExtraTopPadding = Math.max(0, readerLineHeight);

            if (activity.readerPageStatus != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) activity.readerPageStatus.getLayoutParams();
                lp.topMargin = 0;
                lp.height = getReaderPageStatusVisualHeight();
                activity.readerPageStatus.setLayoutParams(lp);
                applyPageStatusAlignment(topInset);
            }
            if (activity.readerFileTitle != null) {
                activity.readerFileTitle.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                activity.readerFileTitle.setIncludeFontPadding(false);
                // Keep the mask at the first-line row, but pin the title text to the
                // top of that row so it sits closer to the page indicator.
                activity.readerFileTitle.setPadding(activity.dpToPx(36), 0, activity.dpToPx(36), 0);
                updateReaderFileTitleMaskBounds();
                updateReaderFileTitleVisibility();
            }

            updateReaderContentTopPadding();

            if (activity.bottomBar != null) {
                activity.bottomBar.setPadding(
                        activity.dpToPx(20),
                        activity.dpToPx(10),
                        activity.dpToPx(20),
                        activity.dpToPx(6));

                FrameLayout.LayoutParams bottomLp =
                        (FrameLayout.LayoutParams) activity.bottomBar.getLayoutParams();
                bottomLp.bottomMargin = bottomInset;
                activity.bottomBar.setLayoutParams(bottomLp);
            }

            updateBottomMenuBackground();
            updateNavigationBarForBottomMenu();

            if (activity.readerView != null) {
                activity.readerView.post(activity::updatePositionLabel);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(activity.readerRoot);
    }

    void updateReaderFileTitleMaskBounds() {
        if (activity.readerFileTitle == null || activity.readerView == null) return;
        if (activity.readerView.getWidth() <= 0 || activity.readerView.getHeight() <= 0) {
            activity.readerView.post(this::updateReaderFileTitleMaskBounds);
            return;
        }

        FrameLayout.LayoutParams titleLp = (FrameLayout.LayoutParams) activity.readerFileTitle.getLayoutParams();

        // Keep the filename overlay in a fixed visual first-row slot.  Do not
        // follow getFirstVisibleLineTopInView(): on the final page, readerScrollY
        // can be clamped to maxScrollY and the actual first visible line shifts,
        // which made the title jump upward only on the last page.
        int pageStatusBottom = getReaderPageStatusVisualHeight();
        int rowTop = activity.readerView.getStableFirstRowTopInView();
        int rowBottom = activity.readerView.getStableFirstRowBottomInView();
        int top = Math.max(pageStatusBottom, rowTop);
        int bottom = Math.max(top + activity.dpToPx(24), rowBottom + activity.dpToPx(2));
        bottom = Math.min(activity.readerView.getHeight(), bottom);

        titleLp.topMargin = top;
        titleLp.height = Math.max(activity.dpToPx(24), bottom - top);
        activity.readerFileTitle.setLayoutParams(titleLp);
    }

    private boolean shouldShowReaderFileTitle() {
        if (activity.readerFileTitle == null) return false;
        boolean hasTitle = activity.readerFileTitle.getText() != null
                && activity.readerFileTitle.getText().toString().trim().length() > 0;
        return activity.toolbarVisible && hasTitle;
    }

    void updateReaderContentTopPadding() {
        if (activity.readerView == null) return;

        // Top padding: canonical (status-bar-OFF spacing).
        // Bottom padding: canonical too. The previous "lastReaderBottomInset + 12dp"
        // made readerView.getViewportHeight() depend on the system navigation bar
        // inset, which was the dominant cause of the "stabilized total page count
        // differs by a few pages between runs" complaint on large TXT files.
        //
        // The actual navigation bar is still covered by navBarSpacer (an opaque
        // overlay at bottom-gravity in the FrameLayout), so display is identical
        // to the inset-following layout we used before.
        activity.readerView.setPadding(
                activity.readerView.getPaddingLeft(),
                getReaderContentTopPadding(),
                activity.readerView.getPaddingRight(),
                getStableReaderBottomPaddingPx());
    }

    int getStableStatusOffTopPaddingPx() {
        if (activity.prefs == null) return 0;
        return Math.max(0, Math.round(
                activity.prefs.getFontSize()
                        * activity.prefs.getLineSpacing()
                        * activity.getResources().getDisplayMetrics().scaledDensity));
    }

    /**
     * Canonical bottom padding for the readerView in pixels. Independent of the
     * live system navigation bar inset AND of the user's current font size.
     *
     * <p>This is the central invariant that keeps the large-TXT exact page count
     * deterministic across runs: viewport height feeds page anchors, and page
     * anchors decide the total page count. If viewport varies by even 1px because
     * the OS navigation bar settled to a slightly different inset between runs,
     * a multi-thousand-page TXT can accumulate that drift into a different total.
     *
     * <p>The actual navigation bar is still visually covered by {@code navBarSpacer},
     * which sits in the FrameLayout at bottom-gravity with an opaque background;
     * text painted within this canonical bottom band is hidden by that spacer.
     * The constant ~60dp is chosen to match what 3-button-nav users already saw
     * (their old "bottomInset + 12dp" ~= 60dp). Gesture-nav users get a slightly
     * larger bottom gap, but it sits behind navBarSpacer's opaque band.
     */
    int getStableReaderBottomPaddingPx() {
        return activity.dpToPx(60);
    }

    int getReaderPageStatusBaseHeight() {
        return activity.dpToPx(28);
    }

    int getReaderPageStatusVisualHeight() {
        return getReaderPageStatusBaseHeight() + Math.max(0, activity.lastStatusOffExtraTopPadding);
    }

    int getReaderContentTopPadding() {
        return getReaderPageStatusVisualHeight() + activity.dpToPx(8);
    }

    void applyPageStatusAlignment(int topInset) {
        if (activity.readerPageStatus == null) return;

        int alignment = activity.prefs != null
                ? activity.prefs.getPageStatusAlignment()
                : PrefsManager.PAGE_STATUS_ALIGN_CENTER;

        if (alignment == PrefsManager.PAGE_STATUS_ALIGN_HIDDEN) {
            activity.readerPageStatus.setVisibility(View.INVISIBLE);
            return;
        }

        activity.readerPageStatus.setVisibility(View.VISIBLE);

        int horizontalGravity;
        int startPadding;
        int endPadding;

        // Extra side padding keeps left/right indicators away from curved edges,
        // punch-hole/camera cutouts, and gesture-status areas.
        int sideInset = Math.max(activity.dpToPx(36), topInset + activity.dpToPx(18));
        int nearSideInset = activity.dpToPx(16);

        if (alignment == PrefsManager.PAGE_STATUS_ALIGN_LEFT) {
            horizontalGravity = Gravity.START;
            startPadding = sideInset;
            endPadding = nearSideInset;
        } else if (alignment == PrefsManager.PAGE_STATUS_ALIGN_RIGHT) {
            horizontalGravity = Gravity.END;
            startPadding = nearSideInset;
            endPadding = sideInset;
        } else {
            horizontalGravity = Gravity.CENTER_HORIZONTAL;
            startPadding = sideInset;
            endPadding = sideInset;
        }

        activity.readerPageStatus.setGravity(Gravity.BOTTOM | horizontalGravity);
        // Keep vertical placement independent from the Android status-bar inset.
        // The TextView is already one reader-row taller, so bottom gravity moves
        // the page indicator down while preserving stable TXT pagination.
        activity.readerPageStatus.setPadding(startPadding, 0, endPadding, activity.dpToPx(1));
    }

    void updateReaderFileTitle() {
        if (activity.readerFileTitle == null) return;
        String title = activity.fileName;
        if ((title == null || title.trim().isEmpty()) && activity.filePath != null) {
            title = new File(activity.filePath).getName();
        }
        activity.readerFileTitle.setText(title != null ? title : "");
        updateReaderFileTitleVisibility();
    }

    void updateReaderFileTitleVisibility() {
        if (activity.readerFileTitle == null) return;
        boolean showTitle = shouldShowReaderFileTitle();
        if (showTitle) updateReaderFileTitleMaskBounds();
        activity.readerFileTitle.setVisibility(showTitle ? View.VISIBLE : View.GONE);
        updateReaderContentTopPadding();
        if (showTitle) {
            activity.readerFileTitle.bringToFront();
            if (activity.readerPageStatus != null) activity.readerPageStatus.bringToFront();
            if (activity.bottomBar != null) activity.bottomBar.bringToFront();
        }
    }
}
