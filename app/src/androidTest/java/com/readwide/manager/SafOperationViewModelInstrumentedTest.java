package com.readwide.manager;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.readwide.manager.model.SafDocumentEntry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SafOperationViewModelInstrumentedTest {
    @Test public void onlyCompletedOrUncertainOutputConsumesSelection() {
        for (int result : new int[]{R.string.saf_operation_done,
                R.string.saf_source_remaining, R.string.saf_cleanup_required}) {
            assertTrue(SafOperationViewModel.consumesClipboardResult(result));
        }
        for (int result : retryableResults()) {
            assertFalse(SafOperationViewModel.consumesClipboardResult(result));
        }
        assertFalse(SafOperationViewModel.consumesClipboardResult(R.string.deleted));
    }

    @Test public void failedPasteKeepsSelectionAndSourcePinAcrossTreeSwitch() throws Exception {
        try (Fixture fixture = createFixture()) {
            for (int result : retryableResults()) {
                Ticket ticket = fixture.start(() -> result, true);
                assertEquals(result, ticket.await());
                onMain(() -> {
                    assertFalse(fixture.model.isRunning());
                    assertSame(fixture.selected, fixture.model.clipboardEntry);
                    assertEquals(fixture.source, fixture.model.clipboardTree);
                    assertEquals(1, pins(fixture.source));
                    assertEquals(1, pins(fixture.destination));
                });
            }
        }
    }

    @Test public void completedAndPartialPasteConsumeSelectionAfterWorkerPinsRelease() throws Exception {
        for (int result : new int[]{R.string.saf_operation_done,
                R.string.saf_source_remaining, R.string.saf_cleanup_required}) {
            try (Fixture fixture = createFixture()) {
                assertEquals(result, fixture.start(() -> result, true).await());
                onMain(() -> {
                    assertNull(fixture.model.clipboardEntry);
                    assertNull(fixture.model.clipboardTree);
                    assertEquals(0, pins(fixture.source));
                    assertEquals(1, pins(fixture.destination));
                });
            }
        }
    }

    @Test public void interruptedPasteRetainsSelectionForExplicitRetry() throws Exception {
        try (Fixture fixture = createFixture()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Ticket ticket = fixture.start(() -> {
                entered.countDown();
                release.await();
                return R.string.file_operation_failed;
            }, true);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                onMain(fixture.model::cancel);
                assertEquals(R.string.file_operation_cancelled, ticket.await());
                onMain(() -> {
                    assertSame(fixture.selected, fixture.model.clipboardEntry);
                    assertEquals(1, pins(fixture.source));
                });
            } finally { release.countDown(); }
        }
    }

    @Test public void oldCompletionCannotClearAReselectedIdenticalEntry() throws Exception {
        try (Fixture fixture = createFixture()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Ticket ticket = fixture.start(() -> {
                entered.countDown();
                release.await();
                return R.string.saf_operation_done;
            }, true);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                onMain(() -> fixture.model.select(fixture.source, fixture.source,
                        fixture.selected, false));
                release.countDown();
                assertEquals(R.string.saf_operation_done, ticket.await());
                onMain(() -> {
                    assertSame(fixture.selected, fixture.model.clipboardEntry);
                    assertEquals(1, pins(fixture.source));
                });
            } finally { release.countDown(); }
        }
    }

    @Test public void rejectedSubmissionKeepsClipboardAndBalancesOperationPins() throws Exception {
        try (Fixture fixture = createFixture()) {
            onMain(() -> worker(fixture.model).shutdown());
            assertEquals(R.string.file_operation_failed,
                    fixture.start(() -> R.string.saf_operation_done, true).await());
            onMain(() -> {
                assertFalse(fixture.model.isRunning());
                assertSame(fixture.selected, fixture.model.clipboardEntry);
                assertEquals(1, pins(fixture.source));
                assertEquals(1, pins(fixture.destination));
            });
        }
    }

    @Test public void nonTransferCompletionDoesNotConsumeClipboard() throws Exception {
        try (Fixture fixture = createFixture()) {
            assertEquals(R.string.saf_operation_done,
                    fixture.start(() -> R.string.saf_operation_done, false).await());
            onMain(() -> assertSame(fixture.selected, fixture.model.clipboardEntry));
        }
    }

    private static int[] retryableResults() {
        return new int[]{R.string.saf_transfer_conflict, R.string.saf_read_only_message,
                R.string.file_operation_failed, R.string.file_operation_cancelled,
                R.string.saf_delete_changed};
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static Fixture createFixture() {
        AtomicReference<Fixture> result = new AtomicReference<>();
        onMain(() -> result.set(new Fixture()));
        return result.get();
    }

    private static final class Fixture implements AutoCloseable {
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefsName = "saf-operation-fixture-" + UUID.randomUUID();
        final Uri source = Uri.parse("content://readwide.operation.fixture/tree/" + UUID.randomUUID());
        final Uri destination = Uri.parse("content://readwide.operation.fixture/tree/" + UUID.randomUUID());
        final SafDocumentEntry selected = new SafDocumentEntry(source,
                "entry", "book.txt", "text/plain", false, 4, 1, 0);
        final SafOperationViewModel model;

        Fixture() {
            SharedPreferences preferences = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            preferences.edit().putString("tree_uri", destination.toString()).apply();
            Context context = new ContextWrapper(target) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return preferences;
                }
            };
            model = new SafOperationViewModel(new FixtureApplication(context));
            model.setBrowserTree(destination);
            model.select(source, source, selected, false);
        }

        Ticket start(Callable<Integer> task, boolean consumesClipboard) {
            Ticket ticket = new Ticket(model);
            onMain(() -> {
                model.outcome.setValue(null);
                model.outcome.observeForever(ticket.observer);
                model.start(task, consumesClipboard, source, destination);
            });
            return ticket;
        }

        @Override public void close() {
            onMain(model::onCleared);
            try { worker(model).awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            target.deleteSharedPreferences(prefsName);
        }
    }

    private static final class Ticket {
        final SafOperationViewModel model;
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Integer> result = new AtomicReference<>();
        final Observer<Integer> observer = value -> {
            if (value != null) {
                result.set(value);
                completed.countDown();
            }
        };

        Ticket(SafOperationViewModel model) { this.model = model; }

        int await() throws InterruptedException {
            try {
                assertTrue("Operation did not complete", completed.await(5, TimeUnit.SECONDS));
                return result.get();
            } finally { onMain(() -> model.outcome.removeObserver(observer)); }
        }
    }

    private static final class FixtureApplication extends Application {
        FixtureApplication(Context context) { attachBaseContext(context); }
    }

    private static ExecutorService worker(SafOperationViewModel model) {
        try {
            Field field = SafOperationViewModel.class.getDeclaredField("worker");
            field.setAccessible(true);
            return (ExecutorService) field.get(model);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @SuppressWarnings("unchecked")
    private static int pins(Uri tree) {
        try {
            Field field = SafStorageAccess.class.getDeclaredField("pinnedTrees");
            field.setAccessible(true);
            synchronized (SafStorageAccess.class) {
                return ((Map<Uri, Integer>) field.get(null)).getOrDefault(tree, 0);
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
