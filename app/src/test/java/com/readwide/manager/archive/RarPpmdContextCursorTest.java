package com.readwide.manager.archive;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class RarPpmdContextCursorTest {
    @Test
    public void cursorTracksDecodedRootStateSuccessor() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64);
        RarPpmdContextChain chain = new RarPpmdContextChain(allocator);
        chain.initializeRootFullAlphabet(allocator);
        RarPpmdContext root = chain.rootContext();
        RarPpmdContextCursor cursor = new RarPpmdContextCursor();

        cursor.resetToRoot(root, 8);
        chain.ensureSuccessorForDecodedState(root, 0x89, allocator);
        cursor.noteDecoded(root, 0x89);

        RarPpmdContextChain.TraversalCandidate candidate = cursor.successorCandidate(chain);
        assertTrue(candidate != null);
        assertTrue(candidate.source.contains("cursor"));
        assertTrue(cursor.diagnostic().contains("foundSymbol=137"));
    }

    @Test
    public void cursorFallsBackToSuffixContext() throws Exception {
        RarPpmdSubAllocator allocator = new RarPpmdSubAllocator(64);
        RarPpmdContextChain chain = new RarPpmdContextChain(allocator);
        chain.initializeRootFullAlphabet(allocator);
        RarPpmdContext order1 = chain.ensureOrder1Context(0x50, allocator);
        RarPpmdContextCursor cursor = new RarPpmdContextCursor();

        cursor.resetToRoot(chain.rootContext(), 8);
        cursor.noteSuffixFallback(order1, chain);

        assertTrue(cursor.suffixFallbackCountForTest() == 1);
        assertTrue(cursor.diagnostic().contains("suffix-fallback"));
    }
}
