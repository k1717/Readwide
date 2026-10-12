package com.readwide.manager;

import static org.junit.Assert.*;
import com.readwide.manager.util.FileOperationProgress;
import org.junit.Test;

/** Token ownership only, not Android dialog execution. */
public class MainFileOperationProgressQueueTest {
    @Test public void queuedJobCannotHidePausedHead() {
        MainFileOperationProgressController.ProgressQueue queue = queue();
        FileOperationProgress head = token(), next = token();
        head.setPaused(true); queue.add(head); queue.add(next);
        assertSame(head, queue.current()); assertTrue(head.isPaused());
        assertFalse(next.isPaused()); assertFalse(next.isComplete());
    }

    @Test public void completionPromotesNextWithoutCompletingIt() {
        MainFileOperationProgressController.ProgressQueue queue = queue();
        FileOperationProgress head = token(), next = token();
        queue.add(head); queue.add(next); queue.finish(head);
        assertSame(next, queue.current()); assertTrue(head.isComplete());
        assertFalse(next.isComplete()); queue.finish(next); assertNull(queue.current());
    }

    @Test public void cancelledHeadRetainsOwnershipUntilWorkerFinishesCleanup() {
        MainFileOperationProgressController.ProgressQueue queue = queue();
        FileOperationProgress head = token(), next = token();
        queue.add(head); queue.add(next); head.cancel();
        assertSame(head, queue.current());
        queue.finish(head); assertSame(next, queue.current());
        assertTrue(head.isCancelled()); assertFalse(next.isCancelled());
    }

    @Test public void rejectedQueuedJobDoesNotRemovePausedHead() {
        MainFileOperationProgressController.ProgressQueue queue = queue();
        FileOperationProgress head = token(), rejected = token(), next = token();
        head.setPaused(true); queue.add(head); queue.add(rejected); queue.add(next);
        queue.finish(rejected);
        assertSame(head, queue.current()); assertTrue(head.isPaused());
        assertTrue(rejected.isComplete()); queue.finish(head); assertSame(next, queue.current());
    }

    @Test public void destructionDetachesListenersBeforeCancellingEveryToken() {
        MainFileOperationProgressController.ProgressQueue queue = queue();
        FileOperationProgress head = token(), next = token();
        head.setPaused(true); next.setPaused(true); queue.add(head); queue.add(next);
        for (FileOperationProgress progress : new FileOperationProgress[]{head, next}) {
            progress.setListener(snapshot -> { if (snapshot.cancelled) fail("stale UI listener"); });
        }
        queue.cancelAll(); queue.cancelAll();
        assertNull(queue.current());
        for (FileOperationProgress progress : new FileOperationProgress[]{head, next}) {
            assertTrue(progress.isCancelled()); assertFalse(progress.isPaused());
            assertFalse(progress.checkpoint());
        }
    }

    @Test public void lateOrUnknownCompletionCannotLoseCurrentOwner() {
        MainFileOperationProgressController.ProgressQueue queue = queue();
        FileOperationProgress old = token(), current = token(), unknown = token();
        queue.add(old); queue.add(current); queue.finish(old);
        queue.finish(old); queue.finish(unknown); queue.finish(null);
        assertSame(current, queue.current()); assertFalse(current.isComplete());
        assertTrue(unknown.isComplete());
    }

    private static FileOperationProgress token() { return new FileOperationProgress("operation", null); }
    private static MainFileOperationProgressController.ProgressQueue queue() {
        return new MainFileOperationProgressController.ProgressQueue();
    }
}
