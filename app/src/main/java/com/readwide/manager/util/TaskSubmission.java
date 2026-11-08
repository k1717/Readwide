package com.readwide.manager.util;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/** Cancels queued work if executor startup fails after accepting the task. */
public final class TaskSubmission {
    private TaskSubmission() { }

    public static FutureTask<Void> submit(Executor executor, Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        try {
            executor.execute(task);
            return task;
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            task.cancel(false);
            throw failure;
        }
    }
}
