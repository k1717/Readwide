package com.readwide.manager;

import android.os.Looper;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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

    @Test public void allocationFailurePublishesFailureAndReleasesBackupOwnership() {
        resourceFailureAllowsRetry(new OutOfMemoryError("backup allocation"));
    }

    @Test public void parserStackFailurePublishesFailureAndReleasesBackupOwnership() {
        resourceFailureAllowsRetry(new StackOverflowError("backup nesting"));
    }

    @Test public void resourceFailureReportsAdditionalRecoveryErrorsWithoutClaimingRollback() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsBackupViewModel[] model = new SettingsBackupViewModel[1];
        OutOfMemoryError failure = new OutOfMemoryError("backup allocation");
        // Stream close failures are also suppressed; they need not be failed rollback steps.
        failure.addSuppressed(new IOException("close"));
        main(() -> {
            model[0] = new SettingsBackupViewModel(queue::add);
            model[0].submit(SettingsBackupViewModel.Operation.EXPORT, () -> { throw failure; });
        });
        queue.remove().run();
        idle();
        main(() -> {
            try {
                SettingsBackupViewModel.State result = model[0].state().getValue();
                assertEquals(SettingsBackupViewModel.Phase.FAILURE, result.phase);
                assertTrue(result.text.startsWith("backup allocation"));
                assertTrue(result.text.contains("Additional recovery errors"));
                assertFalse(result.text.contains("rollback"));
                assertFalse(result.text.contains("restoring previous data also failed"));
            } finally { model[0].onCleared(); }
        });
    }

    private static void resourceFailureAllowsRetry(Error failure) {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsBackupViewModel[] model = new SettingsBackupViewModel[1];
        main(() -> {
            model[0] = new SettingsBackupViewModel(queue::add);
            model[0].submit(SettingsBackupViewModel.Operation.REPLACE, () -> { throw failure; });
        });
        queue.remove().run();
        idle();
        main(() -> {
            assertEquals(SettingsBackupViewModel.Phase.FAILURE, model[0].state().getValue().phase);
            assertEquals(failure.getMessage(), model[0].state().getValue().text);
            model[0].consumeResult();
            model[0].submit(SettingsBackupViewModel.Operation.MERGE,
                    SettingsBackupViewModelInstrumentedTest::success);
            assertTrue(model[0].isWorking());
            assertEquals(1, queue.size());
        });
        queue.remove().run();
        idle();
        main(() -> {
            assertEquals(SettingsBackupViewModel.Phase.SUCCESS, model[0].state().getValue().phase);
            model[0].onCleared();
        });
    }

    @Test public void executorAllocationFailureDoesNotStrandAnotherSettingsInstance() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsBackupViewModel[] models = new SettingsBackupViewModel[2];
        main(() -> {
            models[0] = new SettingsBackupViewModel(task -> { throw new OutOfMemoryError("worker allocation"); });
            models[1] = new SettingsBackupViewModel(queue::add);
            models[0].submit(SettingsBackupViewModel.Operation.EXPORT,
                    SettingsBackupViewModelInstrumentedTest::success);
            assertEquals(SettingsBackupViewModel.Phase.FAILURE, models[0].state().getValue().phase);
            models[1].submit(SettingsBackupViewModel.Operation.MERGE,
                    SettingsBackupViewModelInstrumentedTest::success);
            assertTrue(models[1].isWorking());
            assertEquals(1, queue.size());
        });
        queue.remove().run();
        idle();
        main(() -> { models[0].onCleared(); models[1].onCleared(); });
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

    @Test public void retainedRejectedWorkCannotRunOrUnlockItsRetry() {
        for (Throwable failure : new Throwable[] {new RejectedExecutionException("rejected"),
                new OutOfMemoryError("startup allocation"), new StackOverflowError("startup stack")}) {
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            AtomicInteger submissions = new AtomicInteger();
            AtomicInteger commits = new AtomicInteger();
            SettingsBackupViewModel[] models = new SettingsBackupViewModel[2];
            main(() -> {
                models[0] = new SettingsBackupViewModel(task -> {
                    queue.add(task);
                    if (submissions.incrementAndGet() == 1) {
                        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                        throw (Error) failure;
                    }
                });
                models[1] = new SettingsBackupViewModel(queue::add);
                models[0].submit(SettingsBackupViewModel.Operation.REPLACE,
                        () -> { commits.addAndGet(100); return success(); });
                assertEquals(SettingsBackupViewModel.Phase.FAILURE, models[0].state().getValue().phase);
                models[0].submit(SettingsBackupViewModel.Operation.MERGE,
                        () -> { commits.incrementAndGet(); return success(); });
                assertTrue(models[0].isWorking());
            });
            queue.remove().run();
            idle();
            main(() -> {
                assertEquals(0, commits.get());
                assertTrue(models[0].isWorking());
                models[1].submit(SettingsBackupViewModel.Operation.EXPORT,
                        SettingsBackupViewModelInstrumentedTest::success);
                assertEquals(SettingsBackupViewModel.Phase.FAILURE, models[1].state().getValue().phase);
                assertEquals(1, queue.size());
            });
            Runnable retry = queue.remove();
            retry.run();
            retry.run();
            idle();
            main(() -> {
                assertEquals(1, commits.get());
                assertEquals(SettingsBackupViewModel.Phase.SUCCESS, models[0].state().getValue().phase);
                models[0].onCleared();
                models[1].onCleared();
            });
        }
    }

    @Test public void alreadyStartedWorkKeepsLockAfterExecutorThrows() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread[] worker = new Thread[1];
        SettingsBackupViewModel[] models = new SettingsBackupViewModel[2];
        try {
            main(() -> {
                models[0] = new SettingsBackupViewModel(task -> {
                    worker[0] = new Thread(task, "backup-startup-test");
                    worker[0].start();
                    try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    throw new RejectedExecutionException("reported after start");
                });
                models[1] = new SettingsBackupViewModel(task -> fail("The running import owns the lock"));
                models[0].submit(SettingsBackupViewModel.Operation.MERGE, () -> {
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
                    return success();
                });
                assertTrue(models[0].isWorking());
                models[1].submit(SettingsBackupViewModel.Operation.EXPORT,
                        SettingsBackupViewModelInstrumentedTest::success);
                assertEquals(SettingsBackupViewModel.Phase.FAILURE, models[1].state().getValue().phase);
            });
            release.countDown();
            worker[0].join(5000);
            assertFalse(worker[0].isAlive());
            idle();
            main(() -> assertEquals(SettingsBackupViewModel.Phase.SUCCESS, models[0].state().getValue().phase));
        } finally {
            release.countDown();
            if (worker[0] != null) worker[0].join(5000);
            main(() -> { for (SettingsBackupViewModel model : models) if (model != null) model.onCleared(); });
        }
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
