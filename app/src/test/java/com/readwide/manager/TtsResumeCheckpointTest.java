package com.readwide.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class TtsResumeCheckpointTest {
    @Test public void savedFirstCharacterDoesNotNeedANonzeroOffset() {
        assertTrue(ReaderTtsController.hasSavedCheckpoint("book.txt", "book.txt", 1L));
    }

    @Test public void missingOrUnrelatedStateIsNotResumable() {
        assertFalse(ReaderTtsController.hasSavedCheckpoint("book.txt", "book.txt", 0L));
        assertFalse(ReaderTtsController.hasSavedCheckpoint("book.txt", "other.txt", 1L));
        assertFalse(ReaderTtsController.hasSavedCheckpoint("", "", 1L));
        assertFalse(ReaderTtsController.hasSavedCheckpoint(null, "book.txt", 1L));
        assertFalse(ReaderTtsController.hasSavedCheckpoint("book.txt", null, 1L));
    }
}
