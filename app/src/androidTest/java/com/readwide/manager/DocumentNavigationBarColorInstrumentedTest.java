package com.readwide.manager;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.core.view.WindowCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Color wiring only; actual gesture/three-button rendering needs device checks. */
@RunWith(AndroidJUnit4.class)
public class DocumentNavigationBarColorInstrumentedTest {
    @Test public void epubBottomEdgeFollowsPanelVisibilityAndThemeWithoutRelayout() {
        check(screen -> {
            screen.docType = "EPUB";
            screen.currentPage = 7;
            screen.lastDocumentContentAnchorJson = "unchanged-anchor";
            int[][] palettes = {
                    {0xff080f20, 0xff182238},
                    {0xfffafafa, 0xffe0e0e0},
                    {0xff352018, 0xffdbb080}
            };
            for (int[] palette : palettes) {
                screen.readerBg = palette[0];
                screen.readerPanel = palette[1];
                screen.readerToolbarBg = 0xff356488; // Must not select the top toolbar color.
                for (boolean visible : new boolean[]{true, false, true}) {
                    screen.documentChromeVisible = visible;
                    screen.applyDocumentChromeFillColors();
                    int expected = visible ? palette[1] : palette[0];
                    assertEquals(expected, screen.documentNavigationBarColor());
                    assertEquals(expected, color(screen.views.get(R.id.document_system_bar_scrim_bottom)));
                    for (int id : new int[]{R.id.document_system_bar_scrim_top,
                            R.id.document_system_bar_scrim_start, R.id.document_system_bar_scrim_end}) {
                        assertEquals(palette[0], color(screen.views.get(id)));
                    }
                    for (View view : screen.views.values()) {
                        assertEquals(24, view.getLayoutParams().height);
                        assertEquals(5, view.getPaddingTop());
                        assertEquals(9, view.getPaddingBottom());
                        assertEquals(View.VISIBLE, view.getVisibility());
                    }
                    assertEquals(7, screen.currentPage);
                    assertEquals("unchanged-anchor", screen.lastDocumentContentAnchorJson);
                    assertEquals(0, screen.documentAnchorPageGeneration);
                    assertEquals(0, screen.documentInteractionGeneration);
                }
            }
        });
    }

    @Test public void nonEpubNavigationPaletteIsUnchanged() {
        check(screen -> {
            screen.readerBg = 0xff101820;
            screen.readerPanel = 0xffaabbcc;
            for (String type : new String[]{"Markdown", "Word", "HWP", null}) {
                screen.docType = type;
                for (boolean visible : new boolean[]{true, false}) {
                    screen.documentChromeVisible = visible;
                    screen.applyDocumentChromeFillColors();
                    assertEquals(screen.readerBg, screen.documentNavigationBarColor());
                    assertEquals(screen.readerBg,
                            color(screen.views.get(R.id.document_system_bar_scrim_bottom)));
                }
            }
        });
    }

    @Test
    @SdkSuppress(minSdkVersion = 28, maxSdkVersion = 34)
    @SuppressWarnings("deprecation") // Verify legacy Window colors where Android honors them.
    public void legacyWindowDividerAndIconContrastFollowTheSameSurface() {
        check(screen -> {
            screen.docType = "EPUB";
            screen.readerBg = 0xff000000;
            screen.readerPanel = 0xffffffff;
            for (boolean visible : new boolean[]{true, false, true}) {
                screen.documentChromeVisible = visible;
                screen.applyDocumentSystemBarColors();
                Window window = screen.getWindow();
                int expected = visible ? screen.readerPanel : screen.readerBg;
                assertEquals(expected, window.getNavigationBarColor());
                assertEquals(expected, window.getNavigationBarDividerColor());
                assertEquals(screen.readerBg, window.getStatusBarColor());
                assertEquals(visible, WindowCompat.getInsetsController(window, window.getDecorView())
                        .isAppearanceLightNavigationBars());
                assertFalse(WindowCompat.getInsetsController(window, window.getDecorView())
                        .isAppearanceLightStatusBars());
                assertEquals(expected, color(screen.views.get(R.id.document_system_bar_scrim_bottom)));
            }
            screen.updateDocumentNavigationBarEdge(true);
            assertEquals(screen.readerBg, screen.documentNavigationBarColor());
            assertEquals(screen.readerBg, screen.getWindow().getNavigationBarColor());
            assertFalse(WindowCompat.getInsetsController(screen.getWindow(), screen.getWindow().getDecorView())
                    .isAppearanceLightNavigationBars());
            screen.updateDocumentNavigationBarEdge(false);
            assertEquals(screen.readerPanel, screen.getWindow().getNavigationBarColor());
        });
    }

    private static int color(View view) {
        return ((ColorDrawable) view.getBackground()).getColor();
    }

    private interface Check { void run(Screen screen); }

    private static void check(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Screen screen = new Screen();
            screen.windowOwner = new Dialog(context);
            try {
                for (int id : new int[]{R.id.document_system_bar_scrim_top,
                        R.id.document_system_bar_scrim_bottom, R.id.document_system_bar_scrim_start,
                        R.id.document_system_bar_scrim_end}) {
                    View view = new View(context);
                    view.setLayoutParams(new FrameLayout.LayoutParams(200, 24));
                    view.setPadding(3, 5, 7, 9);
                    screen.views.put(id, view);
                }
                check.run(screen);
            } finally {
                screen.activityDestroyed = true;
                screen.executor.shutdownNow();
                screen.windowOwner.dismiss();
            }
        });
    }

    private static final class Screen extends DocumentPageActivity {
        Dialog windowOwner;
        final Map<Integer, View> views = new HashMap<>();

        @Override public Window getWindow() { return windowOwner.getWindow(); }
        @Override void resolveReaderThemeColors() { /* Explicit palettes; do not edit saved themes. */ }
        @Override @SuppressWarnings("unchecked")
        public <T extends View> T findViewById(int id) { return (T) views.get(id); }
    }
}
