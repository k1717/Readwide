package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RarPpmdModelUpdaterTest {
    @Test
    public void updaterCentralizesRootOrderTeachingAndSuccessorCreation() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64);
        RarPpmdContextChain chain = new RarPpmdContextChain(allocator);
        RarPpmdModelUpdater updater = new RarPpmdModelUpdater(2, allocator, chain);

        RarPpmdModelUpdater.UpdateResult first = updater.learnAfterOrder0Fallback('A', 'Z', 'B');

        assertTrue(first.successorCreated);
        assertTrue(first.taughtRoot);
        assertTrue(first.taughtOrder1);
        assertTrue(first.taughtOrder2);
        assertEquals(3, first.taughtContextCount());
        assertNotNull(chain.rootContext().findState('B'));
        assertNotNull(chain.existingOrder1Context('A').findState('B'));
        assertNotNull(chain.existingOrder2Context('A', 'Z').findState('B'));
        assertTrue(chain.rootContext().findState('B').hasSuccessor());
        assertTrue(first.diagnostic().contains("taughtContextCount=3"));
    }

    @Test
    public void updaterDoesNotDoubleCreateSuccessorForExistingDecodedState() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64);
        RarPpmdContextChain chain = new RarPpmdContextChain(allocator);
        RarPpmdModelUpdater updater = new RarPpmdModelUpdater(1, allocator, chain);
        RarPpmdContext root = chain.rootContext();
        root.insertOrUpdateState('X', 1, RarPpmdStateRecord.NO_SUCCESSOR);

        RarPpmdModelUpdater.UpdateResult first = updater.learnAfterContextDecode(-1, -1, 'X', root);
        RarPpmdModelUpdater.UpdateResult second = updater.learnAfterContextDecode(-1, -1, 'X', root);

        assertTrue(first.successorCreated);
        assertFalse(second.successorCreated);
        assertEquals(1, chain.allocatedSuccessorContexts());
    }
}
