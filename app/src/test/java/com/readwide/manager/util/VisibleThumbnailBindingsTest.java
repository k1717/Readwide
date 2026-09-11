package com.readwide.manager.util;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class VisibleThumbnailBindingsTest {
    @Test public void detachedLastHolderRemovesDemand() {
        VisibleThumbnailBindings<Object> bindings = new VisibleThumbnailBindings<>();
        Object holder = new Object();
        bindings.bind(holder, "1|a");
        assertTrue(bindings.hasSubscribers("1|a"));
        assertEquals("1|a", bindings.unbind(holder));
        assertFalse(bindings.hasSubscribers("1|a"));
        assertNull(bindings.unbind(holder));
    }

    @Test public void anotherVisibleDuplicateKeepsDemandAlive() {
        VisibleThumbnailBindings<Object> bindings = new VisibleThumbnailBindings<>();
        Object first = new Object(), second = new Object();
        bindings.bind(first, "1|a");
        bindings.bind(second, "1|a");
        bindings.unbind(first);
        assertTrue(bindings.hasSubscribers("1|a"));
        assertEquals(1, bindings.snapshot("1|a").size());
        assertSame(second, bindings.snapshot("1|a").get(0));
    }

    @Test public void recycledHolderNoLongerReceivesPreviousKey() {
        VisibleThumbnailBindings<Object> bindings = new VisibleThumbnailBindings<>();
        Object holder = new Object();
        bindings.bind(holder, "1|a");
        bindings.bind(holder, "1|b");
        assertTrue(bindings.snapshot("1|a").isEmpty());
        assertSame(holder, bindings.snapshot("1|b").get(0));
    }

    @Test public void generationAndSizeKeysDoNotShareSubscribers() {
        VisibleThumbnailBindings<Object> bindings = new VisibleThumbnailBindings<>();
        bindings.bind(new Object(), "1|a|192");
        bindings.bind(new Object(), "2|a|192");
        bindings.bind(new Object(), "2|a|256");
        assertEquals(1, bindings.snapshot("1|a|192").size());
        assertEquals(1, bindings.snapshot("2|a|192").size());
        assertEquals(1, bindings.snapshot("2|a|256").size());
    }

    @Test public void repeatedBindingDoesNotDuplicateHolder() {
        VisibleThumbnailBindings<Object> bindings = new VisibleThumbnailBindings<>();
        Object holder = new Object();
        for (int i = 0; i < 100; i++) bindings.bind(holder, "1|a");
        assertEquals(1, bindings.snapshot("1|a").size());
    }

    @Test public void equalButDistinctHoldersKeepIndependentLifetimes() {
        VisibleThumbnailBindings<String> bindings = new VisibleThumbnailBindings<>();
        String first = new String("holder"), second = new String("holder");
        bindings.bind(first, "1|a");
        bindings.bind(second, "1|a");
        assertEquals(2, bindings.snapshot("1|a").size());
        bindings.unbind(first);
        assertSame(second, bindings.snapshot("1|a").get(0));
    }

    @Test public void snapshotsCannotModifyRegistryAndClearReleasesDemand() {
        VisibleThumbnailBindings<Object> bindings = new VisibleThumbnailBindings<>();
        bindings.bind(new Object(), "1|a");
        List<Object> snapshot = bindings.snapshot("1|a");
        snapshot.clear();
        assertEquals(1, bindings.snapshot("1|a").size());
        bindings.clear();
        assertFalse(bindings.hasSubscribers("1|a"));
        assertTrue(bindings.snapshot("1|a").isEmpty());
    }

    @Test public void workerSeesDemandRemovedBeforeStartingDecode() throws Exception {
        VisibleThumbnailBindings<Object> bindings = new VisibleThumbnailBindings<>();
        Object holder = new Object();
        bindings.bind(holder, "1|a");
        CountDownLatch detached = new CountDownLatch(1);
        AtomicBoolean wanted = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            try { detached.await(); wanted.set(bindings.hasSubscribers("1|a")); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        worker.start();
        try {
            bindings.unbind(holder);
            detached.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertFalse(wanted.get());
        } finally { worker.interrupt(); worker.join(5000); }
    }
}
