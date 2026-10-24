package com.readwide.manager;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/** Exercises the installed inset listener and FrameLayout placement, not device bars. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public class DocumentSideInsetsInstrumentedTest {
    @Test public void asymmetricSideInsetsStayPhysicalInBothInterfaceDirections() {
        check(screen -> {
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                screen.root.setLayoutDirection(direction);
                for (int[] edges : new int[][]{{0, 48}, {48, 0}, {13, 37}}) {
                    int left = edges[0], right = edges[1];
                    WindowInsets insets = bars(left, right, 0);
                    screen.apply(insets);
                    View leftScrim = screen.views.get(R.id.document_system_bar_scrim_start);
                    View rightScrim = screen.views.get(R.id.document_system_bar_scrim_end);
                    assertEquals(Gravity.LEFT, ((FrameLayout.LayoutParams) leftScrim.getLayoutParams()).gravity);
                    assertEquals(Gravity.RIGHT, ((FrameLayout.LayoutParams) rightScrim.getLayoutParams()).gravity);
                    if (left > 0) {
                        assertEquals(0, leftScrim.getLeft());
                        assertEquals(left, leftScrim.getRight());
                    }
                    if (right > 0) {
                        assertEquals(320 - right, rightScrim.getLeft());
                        assertEquals(320, rightScrim.getRight());
                    }
                    assertEquals(left > 0 ? View.VISIBLE : View.GONE, leftScrim.getVisibility());
                    assertEquals(right > 0 ? View.VISIBLE : View.GONE, rightScrim.getVisibility());
                    assertEquals(3 + left, screen.content.getPaddingLeft());
                    assertEquals(5 + 24, screen.content.getPaddingTop());
                    assertEquals(7 + right, screen.content.getPaddingRight());
                    assertEquals(9, screen.content.getPaddingBottom());
                    assertEquals(7, screen.currentPage);
                    assertEquals("retained", screen.lastDocumentContentAnchorJson);
                }
            }
        });
    }

    @Test public void bottomNavigationReturnHidesSidesWithoutAccumulatingPadding() {
        check(screen -> {
            screen.root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            screen.apply(bars(0, 48, 0));
            WindowInsets bottom = bars(0, 0, 32);
            screen.apply(bottom);
            int colorUpdates = screen.colorUpdates;
            screen.apply(bottom);
            assertEquals(colorUpdates, screen.colorUpdates);
            assertEquals(View.GONE, screen.views.get(R.id.document_system_bar_scrim_start).getVisibility());
            assertEquals(View.GONE, screen.views.get(R.id.document_system_bar_scrim_end).getVisibility());
            assertEquals(3, screen.content.getPaddingLeft());
            assertEquals(29, screen.content.getPaddingTop());
            assertEquals(7, screen.content.getPaddingRight());
            assertEquals(41, screen.content.getPaddingBottom());
            View scrim = screen.views.get(R.id.document_system_bar_scrim_bottom);
            assertEquals(32, scrim.getLayoutParams().height);
            assertEquals(screen.readerPanel, ((ColorDrawable) scrim.getBackground()).getColor());
        });
    }

    @Test public void sideCutoutsDoNotPaintOverSafeContent() {
        check(screen -> {
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                screen.root.setLayoutDirection(direction);
                // cutout L/R, navigation L/R: cutout-only, same-side excess,
                // smaller same-side bar, opposite sides and two asymmetric cuts.
                for (int[] edges : new int[][]{{24, 0, 0, 0}, {0, 32, 0, 0},
                        {24, 0, 64, 0}, {0, 32, 0, 64}, {24, 0, 8, 0},
                        {24, 0, 0, 48}, {16, 32, 40, 8}}) {
                    int cutLeft = edges[0], cutRight = edges[1];
                    // EdgeToEdgeUtil owns these physical cutout pads on the root.
                    screen.root.setPadding(cutLeft, 0, cutRight, 0);
                    WindowInsets insets = new WindowInsets.Builder(bars(edges[2], edges[3], 0))
                            .setDisplayCutout(new DisplayCutout(Insets.of(cutLeft, 0, cutRight, 0),
                                    cutLeft > 0 ? new Rect(0, 80, cutLeft, 100) : null,
                                    null, cutRight > 0 ? new Rect(320 - cutRight, 80, 320, 100) : null,
                                    null)).build();
                    screen.apply(insets);
                    int extraLeft = Math.max(0, edges[2] - cutLeft);
                    int extraRight = Math.max(0, edges[3] - cutRight);
                    View left = screen.views.get(R.id.document_system_bar_scrim_start);
                    View right = screen.views.get(R.id.document_system_bar_scrim_end);
                    assertEquals(extraLeft, left.getLayoutParams().width);
                    assertEquals(extraRight, right.getLayoutParams().width);
                    assertEquals(extraLeft > 0 ? View.VISIBLE : View.GONE, left.getVisibility());
                    assertEquals(extraRight > 0 ? View.VISIBLE : View.GONE, right.getVisibility());
                    if (extraLeft > 0) {
                        assertEquals(cutLeft, left.getLeft());
                        assertEquals(cutLeft + extraLeft, left.getRight());
                    }
                    if (extraRight > 0) {
                        assertEquals(320 - cutRight - extraRight, right.getLeft());
                        assertEquals(320 - cutRight, right.getRight());
                    }
                    assertEquals(cutLeft, screen.content.getLeft());
                    assertEquals(320 - cutRight, screen.content.getRight());
                    assertEquals(3 + extraLeft, screen.content.getPaddingLeft());
                    assertEquals(7 + extraRight, screen.content.getPaddingRight());
                    assertEquals(7, screen.currentPage);
                    assertEquals("retained", screen.lastDocumentContentAnchorJson);
                }
                screen.root.setPadding(0, 0, 0, 0);
                screen.apply(bars(0, 0, 32));
                assertEquals(3, screen.content.getPaddingLeft());
                assertEquals(7, screen.content.getPaddingRight());
                assertEquals(View.GONE, screen.views.get(R.id.document_system_bar_scrim_start).getVisibility());
                assertEquals(View.GONE, screen.views.get(R.id.document_system_bar_scrim_end).getVisibility());
            }
        });
    }

    @Test public void nonEpubDocumentsKeepScrimsHiddenWithCutouts() {
        check(screen -> {
            screen.root.setPadding(24, 0, 0, 0);
            WindowInsets insets = new WindowInsets.Builder(bars(48, 0, 0))
                    .setDisplayCutout(new DisplayCutout(Insets.of(24, 0, 0, 0),
                            new Rect(0, 80, 24, 100), null, null, null)).build();
            for (String type : new String[]{"Word", "HWP", "Markdown"}) {
                screen.docType = type;
                screen.apply(insets);
                for (int id : new int[]{R.id.document_system_bar_scrim_start,
                        R.id.document_system_bar_scrim_end, R.id.document_system_bar_scrim_top,
                        R.id.document_system_bar_scrim_bottom}) {
                    assertEquals(View.GONE, screen.views.get(id).getVisibility());
                }
                assertEquals(3, screen.content.getPaddingLeft());
                assertEquals(5, screen.content.getPaddingTop());
                assertEquals(7, screen.content.getPaddingRight());
                assertEquals(9, screen.content.getPaddingBottom());
            }
        });
    }

    private static WindowInsets bars(int left, int right, int bottom) {
        return new WindowInsets.Builder()
                .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(left, 0, right, bottom))
                .build();
    }

    private interface Check { void run(Screen screen); }

    private static void check(Check check) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Screen screen = new Screen(InstrumentationRegistry.getInstrumentation().getTargetContext());
            try {
                Method install = DocumentPageStartupController.class.getDeclaredMethod("installEpubContentSafeInsets");
                install.setAccessible(true);
                install.invoke(new DocumentPageStartupController(screen));
                assertNotNull(screen.content.listener);
                check.run(screen);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                screen.activityDestroyed = true;
                screen.executor.shutdownNow();
            }
        });
    }

    private static final class Screen extends DocumentPageActivity {
        final Map<Integer, View> views = new HashMap<>();
        final FrameLayout root;
        final InsetView content;
        int colorUpdates;

        Screen(Context context) {
            docType = "EPUB";
            currentPage = 7;
            lastDocumentContentAnchorJson = "retained";
            readerBg = 0xff101020;
            readerPanel = 0xff303040;
            root = new FrameLayout(context);
            content = new InsetView(context);
            content.setPadding(3, 5, 7, 9);
            root.addView(content, new FrameLayout.LayoutParams(-1, -1));
            views.put(R.id.document_content_column, content);
            for (int id : new int[]{R.id.document_system_bar_scrim_top,
                    R.id.document_system_bar_scrim_bottom, R.id.document_system_bar_scrim_start,
                    R.id.document_system_bar_scrim_end}) {
                View scrim = new View(context);
                root.addView(scrim, new FrameLayout.LayoutParams(0, 0));
                views.put(id, scrim);
            }
        }

        void apply(WindowInsets insets) {
            assertSame(insets, content.listener.onApplyWindowInsets(content, insets));
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 320, 640);
        }

        @Override @SuppressWarnings("unchecked")
        public <T extends View> T findViewById(int id) { return (T) views.get(id); }
        @Override void applyDocumentSystemBarColors() {
            colorUpdates++;
            applyDocumentChromeFillColors();
        }
    }

    private static final class InsetView extends View {
        OnApplyWindowInsetsListener listener;
        InsetView(Context context) { super(context); }
        @Override public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener listener) {
            this.listener = listener;
        }
        @Override public void requestApplyInsets() { /* Listener is dispatched explicitly. */ }
    }
}
