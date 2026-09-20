package com.readwide.manager;

import android.os.Looper;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsBackupViewModelInstrumentedTest {
    private static void main(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }
    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private static SettingsBackupViewModel.State success() {
        return new SettingsBackupViewModel.State(SettingsBackupViewModel.Phase.SUCCESS,
                SettingsBackupViewModel.Operation.MERGE, null);
    }

    @Test public void queuesWorkAndRetainsConsumableResultInViewModelStore() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ViewModelStore owner = new ViewModelStore();
        main(() -> {
            SettingsBackupViewModel model = new SettingsBackupViewModel(queue::add);
            owner.put("backup", model);
            model.submit(SettingsBackupViewModel.Operation.MERGE, () -> {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper());
                return success();
            });
            assertTrue(model.isWorking());
            assertEquals(1, queue.size());
        });
        queue.remove().run();
        idle();
        main(() -> {
            // A recreated Activity keeps the same owner/store, rather than repeating the job.
            SettingsBackupViewModel retained = (SettingsBackupViewModel) owner.get("backup");
            assertEquals(SettingsBackupViewModel.Phase.SUCCESS, retained.state().getValue().phase);
            retained.consumeResult();
            assertEquals(SettingsBackupViewModel.Phase.IDLE, retained.state().getValue().phase);
            owner.clear();
        });
    }

    @Test public void workerFailurePublishesFailureInsteadOfSuccess() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsBackupViewModel[] model = new SettingsBackupViewModel[1];
        main(() -> {
            model[0] = new SettingsBackupViewModel(queue::add);
            model[0].submit(SettingsBackupViewModel.Operation.MERGE, () -> { throw new IOException("disk full"); });
        });
        queue.remove().run();
        idle();
        main(() -> {
            assertEquals(SettingsBackupViewModel.Phase.FAILURE, model[0].state().getValue().phase);
            assertEquals("disk full", model[0].state().getValue().text);
            model[0].onCleared();
        });
    }

    @Test public void closingOwnerDoesNotInterruptCommitOrPublishToClosedScreen() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsBackupViewModel[] model = new SettingsBackupViewModel[1];
        AtomicInteger completed = new AtomicInteger();
        main(() -> {
            model[0] = new SettingsBackupViewModel(queue::add);
            model[0].submit(SettingsBackupViewModel.Operation.REPLACE, () -> { completed.incrementAndGet(); return success(); });
            model[0].onCleared();
        });
        queue.remove().run();
        idle();
        main(() -> {
            assertEquals(1, completed.get());
            assertEquals(SettingsBackupViewModel.Phase.IDLE, model[0].state().getValue().phase);
        });
    }

    @Test public void secondSettingsInstanceCannotInterleaveBackupWork() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsBackupViewModel[] models = new SettingsBackupViewModel[2];
        main(() -> {
            models[0] = new SettingsBackupViewModel(queue::add);
            models[1] = new SettingsBackupViewModel(queue::add);
            models[0].submit(SettingsBackupViewModel.Operation.MERGE, SettingsBackupViewModelInstrumentedTest::success);
            models[1].submit(SettingsBackupViewModel.Operation.EXPORT, SettingsBackupViewModelInstrumentedTest::success);
            assertEquals(1, queue.size());
            assertEquals(SettingsBackupViewModel.Phase.FAILURE, models[1].state().getValue().phase);
        });
        queue.remove().run();
        idle();
        main(() -> { models[0].onCleared(); models[1].onCleared(); });
    }
}
