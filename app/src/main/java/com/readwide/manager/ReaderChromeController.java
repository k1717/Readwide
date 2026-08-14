package com.readwide.manager;

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

import com.readwide.manager.model.Theme;
import com.readwide.manager.util.PrefsManager;

import java.io.File;

/**
 * Coordinates the TXT reader chrome: system bars, bottom toolbar, filename
 * overlay, page-status row, and inset application. Pagination math remains in
 * ReaderActivity; this class only applies the already-established chrome layout
 * rules.
 */
final class ReaderChromeController {
    private final ReaderActivity activity;
    /** Live system-bar sides belong to chrome overlays, never the TXT body. */
    private int overlaySideInsetLeft;
    private int overlaySideInsetRight;

    ReaderChromeController(@NonNull ReaderActivity activity) {
        this.activity = activity;
    }

    void applyStatusBarVisibilityPreference() {
        com.readwide.manager.util.EdgeToEdgeUtil.applyReaderSystemBarVisibility(
                activity,
                activity.readerRoot != null ? activity.readerRoot : activity.getWindow().getDecorView(),
                activity.toolbarVisible,
                activity.prefs != null && activity.prefs.getShowStatusBar());
    }

    void applyReaderSystemBarColors(int backgroundColor, int textColor, int toolbarColor) {
        activity.currentReaderBackgroundColor = backgroundColor;
        activity.currentReaderTextColor = textColor;
        activity.currentReaderToolbarColor = toolbarColor;
        // Status-bar color is owned by applyTopBandColors (called at the tail),
        // which knows the toolbar-band state; setting it here too briefly
        // painted the wrong color and split the ownership.
        if (activity.readerRoot != null) {
            activity.readerRoot.setBackgroundColor(backgroundColor);
        }
        if (activity.readerView != null) {
            activity.readerView.setBackgroundColor(backgroundColor);
        }


        if (activity.readerPageStatus != null) {
            // Background is owned by applyTopBandColors (tail of this method).
            activity.readerPageStatus.setTextColor(textColor);
        }
        if (activity.readerFileTitle != null) {
            activity.readerFileTitle.setTextColor(textColor);
            // Text size is owned by fitReaderFileTitleTextToStrip (it
            // re-baselines to 14sp on every mask update); setting it here too
            // could undo a fitted size between theme apply and the next mask.
        }

        activity.updateLoadingIndicatorColors(backgroundColor);
        applyTopBandColors();
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
            activity.navBarSpacer.setVisibility(isBottomMenuOpen() ? View.VISIBLE : View.GONE);
            if (isBottomMenuOpen()) activity.navBarSpacer.bringToFront();
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
        final int baseRootLeft = activity.readerRoot.getPaddingLeft();
        final int baseRootRight = activity.readerRoot.getPaddingRight();
        final int baseRootTop = activity.readerRoot.getPaddingTop();
        final int baseRootBottom = activity.readerRoot.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(activity.readerRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets cutout = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.displayCutout());
            // Keep the TXT pagination width independent from toolbar/nav-bar
            // visibility. Only a physical cutout reserves body width; a live
            // landscape navigation side is applied to overlay controls below.
            activity.readerRoot.setPadding(baseRootLeft + cutout.left, baseRootTop,
                    baseRootRight + cutout.right, baseRootBottom);
            // Children are already laid out inside root's cutout padding; add
            // only the live navigation excess beyond that physical reserve.
            overlaySideInsetLeft = Math.max(0, bars.left - cutout.left);
            overlaySideInsetRight = Math.max(0, bars.right - cutout.right);
            int topInset = (activity.prefs != null && activity.prefs.getShowStatusBar()) ? bars.top : 0;
            int bottomInset = activity.toolbarVisible ? bars.bottom : 0;
            activity.lastReaderTopInset = topInset;
            activity.lastReaderBottomInset = bottomInset;

            if (activity.navBarSpacer != null) {
                FrameLayout.LayoutParams spacerLp =
                        (FrameLayout.LayoutParams) activity.navBarSpacer.getLayoutParams();
                spacerLp.height = bottomInset;
                spacerLp.gravity = Gravity.BOTTOM;
                activity.navBarSpacer.setLayoutParams(spacerLp);
                activity.navBarSpacer.setVisibility(bottomInset > 0 ? View.VISIBLE : View.GONE);
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
                // TOP vertical alignment: the strip's top is positioned so the
                // title's baseline lands exactly on the body row's baseline (see
                // updateReaderFileTitleMaskBounds); horizontal centering stays.
                activity.readerFileTitle.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                // Match the body layout's include-pad convention so the probe
                // compensation below is exact.
                activity.readerFileTitle.setIncludeFontPadding(true);
                // Keep the mask at the first-line row, but pin the title text to the
                // top of that row so it sits closer to the page indicator.
                // Horizontal padding only: the vertical top padding is owned by
                // updateReaderFileTitleMaskBounds (it positions the text within
                // the band); resetting it to 0 here made the title jump up
                // whenever the theme path ran between mask updates.
                activity.readerFileTitle.setPadding(
                        activity.dpToPx(36) + overlaySideInsetLeft,
                        activity.readerFileTitle.getPaddingTop(),
                        activity.dpToPx(36) + overlaySideInsetRight, 0);
                updateReaderFileTitleMaskBounds();
                updateReaderFileTitleVisibility();
            }

            updateReaderContentTopPadding();

            if (activity.bottomBar != null) {
                activity.bottomBar.setPadding(
                        activity.dpToPx(20) + overlaySideInsetLeft,
                        activity.dpToPx(10),
                        activity.dpToPx(20) + overlaySideInsetRight,
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

    /** Fast-path key of the last applied mask inputs (see below). */
    private long lastTitleMaskInputKey = Long.MIN_VALUE;

    void updateReaderFileTitleMaskBounds() {
        if (activity.readerFileTitle == null || activity.readerView == null) return;
        if (activity.readerFileTitle.getVisibility() != View.VISIBLE) {
            // Nothing to lay out; also invalidate the fast-path key so the next
            // reveal recomputes from scratch.
            lastTitleMaskInputKey = Long.MIN_VALUE;
            return;
        }
        if (activity.readerView.getWidth() <= 0 || activity.readerView.getHeight() <= 0) {
            activity.readerView.post(this::updateReaderFileTitleMaskBounds);
            return;
        }
        // This runs on EVERY scroll event (onScrollChanged), but its inputs -
        // row geometry, status height, and the body font - change rarely.
        // Recomputing unconditionally would set text size/typeface and build a
        // probe StaticLayout per scroll frame; skip all of it when the inputs
        // are unchanged.
        long inputKey = ((long) activity.readerView.getStableFirstRowTopInView() << 42)
                ^ ((long) activity.readerView.getStableFirstRowBottomInView() << 21)
                ^ getReaderPageStatusVisualHeight()
                ^ ((long) Float.floatToIntBits(activity.readerView.getContentTextSizePx()) << 7)
                ^ System.identityHashCode(activity.readerView.getContentTypeface());
        if (inputKey == lastTitleMaskInputKey) return;
        lastTitleMaskInputKey = inputKey;

        FrameLayout.LayoutParams titleLp = (FrameLayout.LayoutParams) activity.readerFileTitle.getLayoutParams();

        // Keep the filename overlay in a fixed visual first-row slot.  Do not
        // follow getFirstVisibleLineTopInView(): on the final page, readerScrollY
        // can be clamped to maxScrollY and the actual first visible line shifts,
        // which made the title jump upward only on the last page.
        int pageStatusBottom = getReaderPageStatusVisualHeight();
        int rowTop = activity.readerView.getStableFirstRowTopInView();
        int rowBottom = activity.readerView.getStableFirstRowBottomInView();
        int top = Math.max(pageStatusBottom, rowTop);
        // The strip is HARD-CAPPED to the first-row slot: covering row one is
        // the intended masking, and the strip must never extend into row two.
        // When the title font's line is taller than the slot, the TEXT is
        // shrunk to fit (see below) instead of growing the strip - growing it
        // clipped the second content row under large font scales or title
        // fonts with unusual metrics. Only when the row metrics are unusable
        // does the strip fall back to one full line of the title's own font.
        android.graphics.Paint.FontMetricsInt titleFm =
                activity.readerFileTitle.getPaint().getFontMetricsInt();
        int titleLinePx = Math.max(1, titleFm.bottom - titleFm.top) + activity.dpToPx(6);
        int bottom;
        if (rowBottom > rowTop && rowBottom > top) {
            // The strip ends at the MIDDLE of the row's trailing leading (the
            // empty space below the glyphs inside the row box), keeping a
            // clear margin from the second line: the row box carries its
            // inter-line space at the bottom, so ending exactly at rowBottom
            // hugged line two.
            android.text.TextPaint bodyProbe = new android.text.TextPaint();
            bodyProbe.setTypeface(activity.readerView.getContentTypeface());
            bodyProbe.setTextSize(activity.readerView.getContentTextSizePx());
            android.graphics.Paint.FontMetricsInt bodyFm = bodyProbe.getFontMetricsInt();
            int leading = Math.max(0, (rowBottom - rowTop) - (bodyFm.bottom - bodyFm.top));
            // Per tuning: the mid-leading edge still read as sitting low; one
            // extra dp up.
            bottom = Math.max(top + 1, rowBottom - leading / 2 - activity.dpToPx(1));
        } else {
            bottom = top + titleLinePx;
        }
        bottom = Math.min(activity.readerView.getHeight(), bottom);

        // Fit first so the compensation below is measured with the FINAL text
        // size, then align baselines: a TextView's single line is a layout
        // "first line" and carries extra top font padding versus the normal
        // body row it replaces, so it would sit visibly lower. Extend the
        // strip upward by exactly that measured difference (probe layout with
        // the title's own paint, body spacing conventions): with TOP gravity
        // the title's baseline then lands where the masked row's baseline was.
        fitReaderFileTitleTextToStrip(Math.max(activity.dpToPx(16), bottom - top));
        // The compensation is measured with the BODY's paint so the strip (the
        // masking region) is fixed by the row's own metrics and never moves
        // when the title's size differs from the body's.
        int comp = activity.readerView.getFirstLinePadCompensationPx();
        // Lower the title by half its natural float: the smaller (-2sp) title
        // rides above the masked row's baseline by the difference of the two
        // fonts' first-line baseline offsets; sinking half of that reads as
        // the requested slight drop while keeping some of the balance lift.
        int floatUp = Math.max(0,
                activity.readerView.getFirstLineBaselineOffsetPx()
                        - activity.readerView.getFirstLineBaselineOffsetPx(
                                activity.readerFileTitle.getPaint()));
        int textTop = Math.max(pageStatusBottom, top - comp + floatUp / 2);
        // The band starts right under the page-status bar so the toolbar-on
        // coloring is one seamless block; the text keeps its exact position
        // via top padding (TOP gravity), so the baseline alignment on the
        // masked row is untouched.
        int stripTop = Math.min(pageStatusBottom, textTop);

        titleLp.topMargin = stripTop;
        titleLp.height = Math.max(activity.dpToPx(16), bottom - stripTop);
        activity.readerFileTitle.setLayoutParams(titleLp);
        activity.readerFileTitle.setPadding(
                activity.dpToPx(36) + overlaySideInsetLeft,
                textTop - stripTop,
                activity.dpToPx(36) + overlaySideInsetRight,
                0);
    }

    /**
     * Fits the title text inside the strip: baseline 14sp, proportionally
     * shrunk (never below 8dp) whenever one full line of the title font would
     * exceed the strip's height. This is what keeps unusual-metric fonts and
     * large system font scales from spilling out, without ever growing the
     * strip beyond the first-row slot.
     */
    private void fitReaderFileTitleTextToStrip(int stripHeightPx) {
        if (activity.readerFileTitle == null) return;
        // Match the body text: same typeface and same size, so the title's
        // line height tracks the body font and fills the first-row slot the
        // way a body line would. Centering within the strip is unchanged; the
        // shrink guard below still applies when the font's full extents exceed
        // the slot (tight line spacing, unusual metrics).
        if (activity.readerView != null) {
            activity.readerFileTitle.setTypeface(activity.readerView.getContentTypeface());
            // One app font-size step (1sp) below the body: the title reads as
            // subtly distinct from content. With TOP gravity the smaller line's
            // shorter first-line offset floats the text slightly upward within
            // the unchanged strip, balancing the space above and below.
            float oneSp = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, 1f,
                    activity.getResources().getDisplayMetrics());
            float titlePx = Math.max(activity.dpToPx(8),
                    activity.readerView.getContentTextSizePx() - 2f * oneSp);
            activity.readerFileTitle.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX, titlePx);
        } else {
            activity.readerFileTitle.setTextSize(14f);
        }
        android.graphics.Paint.FontMetricsInt fm =
                activity.readerFileTitle.getPaint().getFontMetricsInt();
        int line = fm.bottom - fm.top;
        int budget = stripHeightPx - activity.dpToPx(2);
        if (line > budget && line > 0 && budget > 0) {
            float fittedPx = activity.readerFileTitle.getTextSize() * budget / (float) line;
            activity.readerFileTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    Math.max(activity.dpToPx(8), fittedPx));
        }
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

    /**
     * Top band coloring, toolbar-state dependent (user-directed): with the
     * bottom toolbar ON, the whole top region - the status-bar/camera-cutout
     * area (window status bar color), the page-status bar, and the title
     * strip (which the mask-bounds pass extends up to the page-status bottom,
     * sealing the gap) - is painted in the toolbar color as one solid band,
     * like the document viewer's app-bar region. With the toolbar OFF,
     * everything reverts to the reader background as before. Solid colors
     * only: the title strip is a mask, so it must stay fully opaque.
     */
    void applyTopBandColors() {
        boolean band = activity.toolbarVisible;
        int bandColor = band
                ? activity.currentReaderToolbarColor
                : activity.currentReaderBackgroundColor;
        activity.getWindow().setStatusBarColor(bandColor);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(activity.isLightColor(bandColor));
        if (activity.readerPageStatus != null) {
            activity.readerPageStatus.setBackgroundColor(bandColor);
        }
        if (activity.readerFileTitle != null) {
            activity.readerFileTitle.setBackgroundColor(bandColor);
        }
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
        boolean rtl = ViewCompat.getLayoutDirection(activity.readerPageStatus)
                == ViewCompat.LAYOUT_DIRECTION_RTL;
        int overlayStart = rtl ? overlaySideInsetRight : overlaySideInsetLeft;
        int overlayEnd = rtl ? overlaySideInsetLeft : overlaySideInsetRight;
        activity.readerPageStatus.setPaddingRelative(
                startPadding + overlayStart, 0,
                endPadding + overlayEnd, activity.dpToPx(1));
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
        // Visibility FIRST: the mask update fast-path no-ops while the view is
        // not visible, so it must already be VISIBLE when the bounds compute.
        activity.readerFileTitle.setVisibility(showTitle ? View.VISIBLE : View.GONE);
        if (showTitle) updateReaderFileTitleMaskBounds();
        updateReaderContentTopPadding();
        if (showTitle) {
            activity.readerFileTitle.bringToFront();
            if (activity.readerPageStatus != null) activity.readerPageStatus.bringToFront();
            if (activity.bottomBar != null) activity.bottomBar.bringToFront();
        }
    }
}
