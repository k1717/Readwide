package com.readwide.manager;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainDrawerSizingInstrumentedTest {
    @Test public void unchangedFixedRowsDoNotRequestAnotherLayout() {
        check(fixture -> {
            fixture.fixed(5, 5);
            assertEquals(240, fixture.list.getLayoutParams().height);
            assertEquals(View.VISIBLE, fixture.list.getVisibility());
            assertFalse(fixture.list.isNestedScrollingEnabled());
            assertEquals(View.OVER_SCROLL_NEVER, fixture.list.getOverScrollMode());
            int updates = fixture.list.layoutUpdates;
            fixture.fixed(5, 5);
            assertEquals(updates, fixture.list.layoutUpdates);
        });
    }

    @Test public void fixedOverflowChangesScrollingWithoutChangingHeight() {
        check(fixture -> {
            fixture.fixed(5, 5);
            int updates = fixture.list.layoutUpdates;
            fixture.fixed(6, 5);
            assertEquals(updates, fixture.list.layoutUpdates);
            assertEquals(240, fixture.list.getLayoutParams().height);
            assertTrue(fixture.list.isNestedScrollingEnabled());
            assertTrue(fixture.list.isVerticalScrollBarEnabled());
            assertEquals(View.OVER_SCROLL_IF_CONTENT_SCROLLS, fixture.list.getOverScrollMode());
        });
    }

    @Test public void emptyFixedListHidesAndCanRestoreItsRows() {
        check(fixture -> {
            fixture.fixed(3, 5);
            fixture.fixed(0, 5);
            assertEquals(0, fixture.list.getLayoutParams().height);
            assertEquals(View.GONE, fixture.list.getVisibility());
            int updates = fixture.list.layoutUpdates;
            fixture.fixed(0, 5);
            assertEquals(updates, fixture.list.layoutUpdates);
            fixture.fixed(2, 5);
            assertEquals(updates + 1, fixture.list.layoutUpdates);
            assertEquals(96, fixture.list.getLayoutParams().height);
            assertEquals(View.VISIBLE, fixture.list.getVisibility());
        });
    }

    @Test public void recentListSetsWeightOnceAndStaysVisibleWhenEmpty() {
        check(fixture -> {
            fixture.recent(4);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) fixture.list.getLayoutParams();
            assertEquals(0, params.height);
            assertEquals(1f, params.weight, 0f);
            assertEquals(23, params.leftMargin);
            assertEquals(1, fixture.list.layoutUpdates);
            fixture.recent(0);
            assertEquals(1, fixture.list.layoutUpdates);
            assertEquals(View.VISIBLE, fixture.list.getVisibility());
            assertTrue(fixture.list.isNestedScrollingEnabled());
            assertFalse(fixture.list.isVerticalScrollBarEnabled());
            assertEquals(View.OVER_SCROLL_NEVER, fixture.list.getOverScrollMode());
        });
    }

    @Test public void recentListRepairsWeightEvenWhenHeightIsAlreadyZero() {
        check(fixture -> {
            fixture.recent(2);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) fixture.list.getLayoutParams();
            params.weight = 0f;
            fixture.list.setLayoutParams(params);
            int updates = fixture.list.layoutUpdates;
            fixture.recent(2);
            assertEquals(updates + 1, fixture.list.layoutUpdates);
            assertEquals(0, params.height);
            assertEquals(1f, params.weight, 0f);
            fixture.recent(2);
            assertEquals(updates + 1, fixture.list.layoutUpdates);
        });
    }

    @Test public void recentListSupportsGenericLayoutParamsWithoutRepeatedUpdates() {
        check(fixture -> {
            fixture.list.setLayoutParams(new ViewGroup.LayoutParams(300, 81));
            fixture.list.layoutUpdates = 0;
            fixture.recent(2);
            assertEquals(0, fixture.list.getLayoutParams().height);
            assertEquals(300, fixture.list.getLayoutParams().width);
            assertEquals(1, fixture.list.layoutUpdates);
            fixture.recent(3);
            assertEquals(1, fixture.list.layoutUpdates);
        });
    }

    private static void check(Consumer<Fixture> assertion) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Fixture fixture = new Fixture(context);
            try {
                assertion.accept(fixture);
            } finally {
                fixture.screen.fileSearchHandler.removeCallbacksAndMessages(null);
                fixture.screen.uriOpenExecutor.shutdownNow();
                fixture.screen.fileSearchExecutor.shutdownNow();
                fixture.screen.fileOperationExecutor.shutdownNow();
                fixture.screen.folderValidationExecutor.shutdownNow();
            }
        });
    }

    private static final class Fixture {
        final Screen screen = new Screen();
        final MainDrawerController controller = new MainDrawerController(screen);
        final SizingList list;

        Fixture(Context context) {
            list = new SizingList(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(300, 50, 0f);
            params.leftMargin = 23;
            list.setLayoutParams(params);
            list.layoutUpdates = 0;
        }

        void fixed(int count, int maximum) {
            call("applyFixedRowListHeight", new Class<?>[]{RecyclerView.class, int.class, int.class},
                    list, count, maximum);
        }

        void recent(int count) {
            call("applyRecentFolderListHeight", new Class<?>[]{RecyclerView.class, int.class}, list, count);
        }

        private void call(String name, Class<?>[] parameters, Object... arguments) {
            try {
                Method method = MainDrawerController.class.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                method.invoke(controller, arguments);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class Screen extends MainActivity {
        @Override int dpToPx(float value) { return Math.round(value); }
    }

    private static final class SizingList extends RecyclerView {
        int layoutUpdates;

        SizingList(Context context) { super(context); }

        @Override public void setLayoutParams(ViewGroup.LayoutParams params) {
            super.setLayoutParams(params);
            layoutUpdates++;
        }
    }
}
